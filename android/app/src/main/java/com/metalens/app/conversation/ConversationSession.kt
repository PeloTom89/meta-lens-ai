package com.metalens.app.conversation

import android.content.Context
import com.metalens.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.metalens.app.settings.AppSettings
import kotlin.math.abs

/**
 * Owns a single live conversation session: audio routing + mic capture + OpenAI Realtime WS.
 *
 * This is intentionally NOT tied to Activity/Compose lifecycle; it is designed to be hosted
 * by a Foreground Service so it can keep running while the screen is locked.
 */
class ConversationSession(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val audioIo = AudioIoController(appContext.applicationContext)
    private var realtimeClient: OpenAIRealtimeClient? = null

    private var micJob: Job? = null
    private var speakingDebounceJob: Job? = null
    private var currentAssistantMessageId: String? = null
    private val userMessageByItemId = linkedMapOf<String, String>()
    private val pendingUserPlaceholderJobs = linkedMapOf<String, Job>()
    private var assistantAudioActiveUntilMs: Long = 0L
    private var localInterruptFrames: Int = 0
    private var lastLocalInterruptMs: Long = 0L

    private fun ensureActiveConversationMeta() {
        val now = System.currentTimeMillis()
        ConversationRuntime.update { state ->
            state.copy(
                activeConversationId = state.activeConversationId ?: java.util.UUID.randomUUID().toString(),
                conversationStartedAtMs = state.conversationStartedAtMs ?: now,
                conversationStartMessageIndex = state.conversationStartMessageIndex ?: state.messages.size,
            )
        }
    }

    fun start() {
        val status = ConversationRuntime.uiState.value.status
        if (status == ConversationStatus.Connecting ||
            status == ConversationStatus.Listening ||
            status == ConversationStatus.Speaking
        ) {
            return
        }

        ConversationRuntime.update {
            it.copy(
                status = ConversationStatus.Connecting,
                recentError = null,
            )
        }
        ensureActiveConversationMeta()

        scope.launch {
            try {
                audioIo.startRoutingToBluetoothSco()
                audioIo.startPcmPlayback()

                val apiKey = AppSettings.getOpenAiApiKey(appContext).trim()
                val model = AppSettings.getOpenAiModel(appContext).trim()
                val systemInstructions = AppSettings.getConversationSystemInstructions(appContext).trim()

                val client =
                    OpenAIRealtimeClient(
                        apiKey = apiKey,
                        model = model.ifBlank { OpenAIRealtimeClient.DEFAULT_MODEL },
                        instructions = systemInstructions,
                        onConnected = {
                            ConversationRuntime.update { it.copy(status = ConversationStatus.Listening) }
                        },
                        onDisconnected = { reason ->
                            val s = ConversationRuntime.uiState.value.status
                            if (s != ConversationStatus.Idle) {
                                ConversationRuntime.update {
                                    it.copy(
                                        status = ConversationStatus.Idle,
                                        recentError = "Disconnected: $reason",
                                    )
                                }
                            }
                        },
                        onError = { message ->
                            ConversationRuntime.update {
                                it.copy(status = ConversationStatus.Error, recentError = message)
                            }
                        },
                        onAudioDelta = { pcm16 ->
                            audioIo.playPcm16Mono(pcm16)
                            assistantAudioActiveUntilMs = android.os.SystemClock.elapsedRealtime() + 1000L
                            bumpAssistantSpeaking()
                        },
                        onAssistantTextDelta = { delta -> appendAssistantDelta(delta) },
                        onUserUtteranceStarted = { itemId -> ensureUserMessagePlaceholder(itemId) },
                        onUserUtteranceStopped = { itemId, durationMs ->
                            // If it was just noise/very brief, don't show a phantom message.
                            if (durationMs < 250) {
                                pendingUserPlaceholderJobs.remove(itemId)?.cancel()
                                removeUserMessagePlaceholder(itemId)
                            } else {
                                // Ensure placeholder exists (in case transcription is slow).
                                ensureUserMessagePlaceholderImmediate(itemId)
                            }
                        },
                        onUserTranscript = { itemId, transcript -> setUserMessageTranscript(itemId, transcript) },
                        onAssistantResponseStarted = { _ -> /* don't create empty AI messages */ },
                        onAssistantResponseDone = { currentAssistantMessageId = null },
                        onInputSpeechState = { isSpeaking ->
                            if (isSpeaking) {
                                // Stop any buffered assistant audio immediately when the user interrupts.
                                audioIo.interruptPlayback()
                            }
                            ConversationRuntime.update { it.copy(isUserSpeaking = isSpeaking) }
                        },
                    )

                realtimeClient = client
                client.connect()

                micJob?.cancel()
                micJob =
                    audioIo.startMicCapture(scope) { chunk ->
                        maybeInterruptOnLocalVoice(chunk)
                        realtimeClient?.sendPcm16Audio(chunk)
                    }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                stop()
                ConversationRuntime.update {
                    it.copy(
                        status = ConversationStatus.Error,
                        recentError = t.message ?: "Failed to start conversation",
                    )
                }
            }
        }
    }

    /**
     * Local (client-side) interruption detection.
     *
     * Why: server VAD can trigger a bit late; we want to stop playback immediately once the user
     * clearly starts speaking, but avoid stopping on brief/noise.
     */
    private fun maybeInterruptOnLocalVoice(pcm16Chunk: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()

        // Only try to interrupt while assistant audio is actively playing/recent.
        val assistantActive = now < assistantAudioActiveUntilMs
        if (!assistantActive) {
            localInterruptFrames = 0
            return
        }

        // Peak amplitude from 16-bit PCM (little endian).
        val peak = peakPcm16(pcm16Chunk)

        // Tunables: require ~60ms of clear voice energy.
        val threshold = 2500 // increase if you get false interrupts from noise
        val framesRequired = 3 // 3 * 20ms = 60ms (AudioIoController uses ~20ms frames)
        val cooldownMs = 800L

        if (peak >= threshold) {
            localInterruptFrames++
        } else {
            localInterruptFrames = 0
        }

        if (localInterruptFrames >= framesRequired && now - lastLocalInterruptMs > cooldownMs) {
            lastLocalInterruptMs = now
            localInterruptFrames = 0
            audioIo.interruptPlayback()
            realtimeClient?.cancelActiveResponse()
        }
    }

    private fun peakPcm16(pcm16Chunk: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm16Chunk.size) {
            val lo = pcm16Chunk[i].toInt() and 0xFF
            val hi = pcm16Chunk[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val amp = abs(sample.toShort().toInt())
            if (amp > peak) peak = amp
            i += 2
        }
        return peak
    }

    private fun addUserMessage(text: String) {
        ConversationRuntime.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = text),
            )
        }
    }

    private fun ensureUserMessagePlaceholder(itemId: String) {
        if (userMessageByItemId.containsKey(itemId) || pendingUserPlaceholderJobs.containsKey(itemId)) return
        // Delay placeholder slightly to avoid showing "..." for noise at conversation start.
        pendingUserPlaceholderJobs[itemId] =
            scope.launch {
                delay(250)
                ensureUserMessagePlaceholderImmediate(itemId)
            }
    }

    private fun ensureUserMessagePlaceholderImmediate(itemId: String) {
        if (userMessageByItemId.containsKey(itemId)) return
        pendingUserPlaceholderJobs.remove(itemId)?.cancel()
        ConversationRuntime.update { state ->
            if (userMessageByItemId.containsKey(itemId)) return@update state
            val msg = ChatMessage(id = itemId, role = ChatRole.User, text = "…")
            userMessageByItemId[itemId] = msg.id
            state.copy(messages = state.messages + msg)
        }
    }

    private fun removeUserMessagePlaceholder(itemId: String) {
        val messageId = userMessageByItemId[itemId] ?: return
        ConversationRuntime.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == messageId })
        }
        userMessageByItemId.remove(itemId)
    }

    private fun setUserMessageTranscript(itemId: String, transcript: String) {
        pendingUserPlaceholderJobs.remove(itemId)?.cancel()
        ConversationRuntime.update { state ->
            val messageId = userMessageByItemId[itemId]
            if (messageId == null) {
                // Fallback if transcription arrives before we created the placeholder.
                val msg = ChatMessage(id = itemId, role = ChatRole.User, text = transcript)
                userMessageByItemId[itemId] = msg.id
                return@update state.copy(messages = state.messages + msg)
            }
            val updated =
                state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(text = transcript) else msg
                }
            state.copy(messages = updated)
        }
    }

    private fun ensureAssistantMessage(responseId: String? = null) {
        if (currentAssistantMessageId != null) return
        ConversationRuntime.update { state ->
            if (currentAssistantMessageId != null) return@update state
            val msg = ChatMessage(id = responseId ?: java.util.UUID.randomUUID().toString(), role = ChatRole.Ai, text = "")
            currentAssistantMessageId = msg.id
            state.copy(messages = state.messages + msg)
        }
    }

    private fun appendAssistantDelta(delta: String) {
        if (delta.isBlank()) return
        ConversationRuntime.update { state ->
            val messageId = currentAssistantMessageId
            if (messageId == null) {
                val msg = ChatMessage(role = ChatRole.Ai, text = delta)
                currentAssistantMessageId = msg.id
                state.copy(messages = state.messages + msg)
            } else {
                val updated =
                    state.messages.map { msg ->
                        if (msg.id == messageId) msg.copy(text = msg.text + delta) else msg
                    }
                state.copy(messages = updated)
            }
        }
    }

    private fun bumpAssistantSpeaking() {
        if (ConversationRuntime.uiState.value.status != ConversationStatus.Speaking) {
            ConversationRuntime.update { it.copy(status = ConversationStatus.Speaking) }
        }
        speakingDebounceJob?.cancel()
        speakingDebounceJob =
            scope.launch {
                delay(500)
                if (ConversationRuntime.uiState.value.status == ConversationStatus.Speaking) {
                    ConversationRuntime.update { it.copy(status = ConversationStatus.Listening) }
                }
            }
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // Show immediately in UI (never delayed/queued behind AI output).
        addUserMessage(trimmed)

        // Also stop any buffered AI audio immediately.
        audioIo.interruptPlayback()

        realtimeClient?.sendUserText(trimmed)
    }

    fun stop() {
        micJob?.cancel()
        micJob = null
        speakingDebounceJob?.cancel()
        speakingDebounceJob = null
        currentAssistantMessageId = null

        realtimeClient?.close()
        realtimeClient = null

        audioIo.stop()

        // Stop session but keep transcript in UI.
        ConversationRuntime.update {
            it.copy(
                status = ConversationStatus.Idle,
                isUserSpeaking = false,
                recentError = null,
            )
        }
        userMessageByItemId.clear()
        pendingUserPlaceholderJobs.values.forEach { it.cancel() }
        pendingUserPlaceholderJobs.clear()
        assistantAudioActiveUntilMs = 0L
        localInterruptFrames = 0
        lastLocalInterruptMs = 0L
    }
}

