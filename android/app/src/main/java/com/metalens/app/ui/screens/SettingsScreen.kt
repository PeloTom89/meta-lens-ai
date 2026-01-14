package com.metalens.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metalens.app.BuildConfig
import com.metalens.app.R
import com.metalens.app.ui.components.FeatureActionCard
import com.metalens.app.wearables.WearablesViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metalens.app.conversation.OpenAIRealtimeClient
import com.metalens.app.settings.AppSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as ComponentActivity
    val wearablesViewModel: WearablesViewModel = viewModel(activity)
    val uiState by wearablesViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var openAiApiKey by rememberSaveable { mutableStateOf(AppSettings.getOpenAiApiKey(context)) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    val modelOptions =
        rememberSaveable {
            listOf(
                "gpt-4o-realtime-preview",
                "gpt-4o-mini-realtime-preview",
            )
        }

    var openAiModel by rememberSaveable {
        val saved = AppSettings.getOpenAiModel(context).trim()
        val initial = saved.takeIf { it in modelOptions } ?: OpenAIRealtimeClient.DEFAULT_MODEL
        mutableStateOf(initial)
    }

    var showEditApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectModelDialog by rememberSaveable { mutableStateOf(false) }
    var apiKeyDraft by rememberSaveable { mutableStateOf(openAiApiKey) }
    var modelDraft by rememberSaveable { mutableStateOf(openAiModel) }

    val scope = rememberCoroutineScope()
    var isCheckingConnection by rememberSaveable { mutableStateOf(false) }
    var lastConnectionCheckResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showConnectedDevicesDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        wearablesViewModel.startMonitoring()
    }

    LaunchedEffect(showEditApiKeyDialog) {
        if (showEditApiKeyDialog) {
            apiKeyDraft = openAiApiKey
            apiKeyVisible = false
        }
    }

    LaunchedEffect(showSelectModelDialog) {
        if (showSelectModelDialog) {
            modelDraft = openAiModel
        }
    }

    if (showEditApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showEditApiKeyDialog = false },
            title = { Text(stringResource(R.string.settings_openai_api_key)) },
            text = {
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation =
                        if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalized = apiKeyDraft.trim()
                        AppSettings.setOpenAiApiKey(context, normalized)
                        openAiApiKey = normalized
                        showEditApiKeyDialog = false
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditApiKeyDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showSelectModelDialog) {
        AlertDialog(
            onDismissRequest = { showSelectModelDialog = false },
            title = { Text(stringResource(R.string.settings_openai_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modelOptions.forEach { model ->
                        val subtitle =
                            when {
                                model == openAiModel -> stringResource(R.string.settings_current)
                                model == modelDraft -> stringResource(R.string.settings_selected)
                                else -> null
                            }
                        FeatureActionCard(
                            title = model,
                            subtitle = subtitle,
                            icon = Icons.Filled.Psychology,
                            onClick = { modelDraft = model },
                            enabled = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalized = modelDraft.trim()
                        AppSettings.setOpenAiModel(context, normalized)
                        openAiModel = normalized
                        showSelectModelDialog = false
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelectModelDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showConnectedDevicesDialog) {
        val connectedDevices = uiState.connectedDevices
        AlertDialog(
            onDismissRequest = { showConnectedDevicesDialog = false },
            title = { Text(stringResource(R.string.settings_connected_devices)) },
            text = {
                if (connectedDevices.isEmpty()) {
                    Text(text = stringResource(R.string.settings_no_connected_devices))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        connectedDevices.forEach { deviceId ->
                            val id = deviceId.toString()
                            val name = uiState.deviceDisplayNames[id] ?: stringResource(R.string.settings_unknown_device)
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectedDevicesDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 20.dp, vertical = 24.dp)),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsSectionTitle(text = stringResource(R.string.settings_group_hardware))

        uiState.recentError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val glassesStatusSubtitle =
            if (uiState.hasActiveDevice) {
                stringResource(R.string.glasses_status_connected)
            } else {
                stringResource(R.string.glasses_status_not_connected)
            }

        FeatureActionCard(
            title =
                if (uiState.isRegistered) {
                    stringResource(R.string.disconnect_my_glasses)
                } else {
                    stringResource(R.string.connect_my_glasses)
                },
            subtitle = glassesStatusSubtitle,
            icon = if (uiState.isRegistered) Icons.Filled.BluetoothDisabled else Icons.Filled.Bluetooth,
            onClick = {
                if (uiState.isRegistered) {
                    wearablesViewModel.startUnregistration()
                } else {
                    wearablesViewModel.startRegistration()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val connectedDevices = uiState.connectedDevices
        val connectedDevicesSubtitle =
            if (connectedDevices.isEmpty()) {
                stringResource(R.string.settings_no_connected_devices)
            } else {
                stringResource(R.string.settings_connected_devices_count, connectedDevices.size)
            }

        FeatureActionCard(
            title = stringResource(R.string.settings_connected_devices),
            subtitle = connectedDevicesSubtitle,
            icon = Icons.Filled.Devices,
            onClick = { showConnectedDevicesDialog = true },
            enabled = connectedDevices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionTitle(text = stringResource(R.string.settings_group_ai))

        Spacer(modifier = Modifier.height(12.dp))

        val apiKeySubtitle =
            if (openAiApiKey.isBlank()) {
                stringResource(R.string.settings_not_set)
            } else {
                "***"
            }

        FeatureActionCard(
            title = stringResource(R.string.settings_openai_api_key),
            subtitle = apiKeySubtitle,
            icon = Icons.Filled.Key,
            onClick = { showEditApiKeyDialog = true },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        FeatureActionCard(
            title = stringResource(R.string.settings_openai_model),
            subtitle = openAiModel,
            icon = Icons.Filled.Psychology,
            onClick = { showSelectModelDialog = true },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val checkSubtitle =
            when {
                isCheckingConnection -> stringResource(R.string.settings_checking_connection)
                lastConnectionCheckResult != null -> lastConnectionCheckResult
                else -> stringResource(R.string.settings_tap_to_test)
            }

        val connectionOkText = stringResource(R.string.settings_connection_ok)
        FeatureActionCard(
            title = stringResource(R.string.settings_check_connection),
            subtitle = checkSubtitle,
            icon = if (lastConnectionCheckResult == connectionOkText) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.Wifi
            },
            enabled = !isCheckingConnection,
            onClick = {
                if (isCheckingConnection) return@FeatureActionCard
                isCheckingConnection = true
                lastConnectionCheckResult = null
                scope.launch {
                    val connectionFailedPrefix = context.getString(R.string.settings_connection_failed)
                    val connectionOk = context.getString(R.string.settings_connection_ok)
                    // Use the persisted settings, same source as conversation start.
                    val apiKey = AppSettings.getOpenAiApiKey(context).trim()
                    val model = AppSettings.getOpenAiModel(context).trim()
                    val result =
                        if (apiKey.isBlank()) {
                            Result.failure(IllegalStateException("Missing API key"))
                        } else {
                            checkOpenAiRealtimeHandshake(
                                apiKey = apiKey,
                                model = model,
                            )
                        }

                    lastConnectionCheckResult =
                        result.fold(
                            onSuccess = { connectionOk },
                            onFailure = { t ->
                                // Keep UX simple; don't surface provider error details here.
                                connectionFailedPrefix
                            },
                        )
                    isCheckingConnection = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionTitle(text = stringResource(R.string.settings_group_about))

        Spacer(modifier = Modifier.height(12.dp))

        FeatureActionCard(
            title = stringResource(R.string.settings_version),
            subtitle = BuildConfig.VERSION_NAME,
            icon = Icons.Filled.Info,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        FeatureActionCard(
            title = stringResource(R.string.settings_support_development),
            subtitle = stringResource(R.string.settings_support_development_subtitle),
            icon = Icons.Filled.Coffee,
            onClick = {
                val intent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(context.getString(R.string.settings_support_development_url)),
                    )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun SettingsKeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private suspend fun checkOpenAiRealtimeHandshake(
    apiKey: String,
    model: String,
): Result<Unit> {
    return try {
        withTimeout(7_000) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                val client = OkHttpClient()
                val request =
                    Request.Builder()
                        .url("wss://api.openai.com/v1/realtime?model=$model")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("OpenAI-Beta", "realtime=v1")
                        .build()

                val socketRef = arrayOfNulls<WebSocket>(1)
                val socket =
                    client.newWebSocket(
                        request,
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                // Don't mark success on open: invalid_api_key can arrive as an "error" event.
                                // Send a minimal session.update to prompt a response from the server.
                                webSocket.send("""{"type":"session.update","session":{}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val type =
                                    try {
                                        JSONObject(text).optString("type")
                                    } catch (_: Throwable) {
                                        ""
                                    }

                                if (type == "error") {
                                    val obj = runCatching { JSONObject(text) }.getOrNull()
                                    val err = obj?.optJSONObject("error")
                                    val code = err?.optString("code")?.takeIf { it.isNotBlank() }
                                    val msg =
                                        err?.optString("message")?.takeIf { it.isNotBlank() }
                                            ?: obj?.optString("message")?.takeIf { it.isNotBlank() }
                                            ?: "Realtime error"
                                    val detail = if (code != null) "$code: $msg" else msg
                                    webSocket.close(1000, "error")
                                    if (resumed.compareAndSet(false, true) && cont.isActive) {
                                        cont.resume(Result.failure(IllegalStateException(detail)))
                                    }
                                    return
                                }

                                // Any non-error server event means auth worked and we can consider this "OK".
                                webSocket.close(1000, "ok")
                                if (resumed.compareAndSet(false, true) && cont.isActive) {
                                    cont.resume(Result.success(Unit))
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                val message =
                                    if (response != null) {
                                        "HTTP ${response.code}: ${response.message}"
                                    } else {
                                        t.message ?: "Connection error"
                                    }
                                if (resumed.compareAndSet(false, true) && cont.isActive) {
                                    cont.resume(Result.failure(IllegalStateException(message, t)))
                                }
                            }
                        },
                    )

                socketRef[0] = socket
                cont.invokeOnCancellation {
                    socketRef[0]?.cancel()
                }
            }.getOrThrow()
        }
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen()
}

