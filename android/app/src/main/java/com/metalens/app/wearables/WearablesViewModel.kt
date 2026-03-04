package com.metalens.app.wearables

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.metalens.app.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "WearablesViewModel"
    }

    private val _uiState = MutableStateFlow(WearablesUiState())
    val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

    val deviceSelector: DeviceSelector = AutoDeviceSelector()
    private var deviceSelectorJob: Job? = null
    private var monitoringStarted = false
    private val deviceMetadataJobs = mutableMapOf<DeviceIdentifier, Job>()

    // Temporary session used to make "instant" photo after countdown.
    private var preparedPhotoSession: StreamSession? = null
    private var preparePhotoJob: Job? = null
    private var capturePhotoJob: Job? = null

    private fun closePreparedPhotoSessionOnce() {
        val session = preparedPhotoSession
        preparedPhotoSession = null
        runCatching { session?.close() }
    }

    fun startMonitoring() {
        if (monitoringStarted) return
        Log.d(TAG, "startMonitoring()")
        monitoringStarted = true

        deviceSelectorJob =
            viewModelScope.launch {
                deviceSelector.activeDevice(Wearables.devices).collect { device ->
                    Log.d(TAG, "activeDevice changed: $device (hasActiveDevice=${device != null})")
                    _uiState.update { it.copy(activeDevice = device, hasActiveDevice = device != null) }
                }
            }

        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "registrationState changed: ${state::class.simpleName} ($state)")
                _uiState.update { it.copy(registrationState = state) }
            }
        }

        viewModelScope.launch {
            Wearables.devices.collect { devices ->
                Log.d(TAG, "devices changed: count=${devices.size}, ids=$devices")
                _uiState.update { it.copy(devices = devices.toList()) }
                monitorDeviceMetadata(devices)
            }
        }
    }

    private fun monitorDeviceMetadata(devices: Set<DeviceIdentifier>) {
        // cancel removed
        val removed = deviceMetadataJobs.keys - devices
        removed.forEach { id ->
            Log.d(TAG, "Device removed: $id")
            deviceMetadataJobs[id]?.cancel()
            deviceMetadataJobs.remove(id)
            _uiState.update { state ->
                state.copy(deviceDisplayNames = state.deviceDisplayNames - id.toString())
            }
        }

        // start for new
        val newDevices = devices - deviceMetadataJobs.keys
        newDevices.forEach { deviceId ->
            Log.d(TAG, "New device detected: $deviceId — starting metadata monitor")
            val job =
                viewModelScope.launch {
                    Wearables.devicesMetadata[deviceId]?.collect { metadata ->
                        val idKey = deviceId.toString()
                        val name = metadata.name.ifEmpty { idKey }
                        Log.d(TAG, "Device metadata: id=$idKey, name='$name', compatibility=${metadata.compatibility}")
                        _uiState.update { state ->
                            state.copy(deviceDisplayNames = state.deviceDisplayNames + (idKey to name))
                        }

                        if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
                            Log.w(TAG, "Device '$name' requires an update to work with this app")
                            setRecentError("Device '$name' requires an update to work with this app")
                        }
                    }
                }
            deviceMetadataJobs[deviceId] = job
        }
    }

    fun startRegistration() {
        Log.d(TAG, "startRegistration()")
        Wearables.startRegistration(getApplication())
    }

    fun startUnregistration() {
        Log.d(TAG, "startUnregistration()")
        Wearables.startUnregistration(getApplication())
    }

    fun setRecentError(error: String?) {
        if (error != null) Log.e(TAG, "recentError: $error")
        _uiState.update { it.copy(recentError = error) }
    }

    fun resetPictureAnalysis() {
        preparePhotoJob?.cancel()
        preparePhotoJob = null
        capturePhotoJob?.cancel()
        capturePhotoJob = null
        closePreparedPhotoSessionOnce()
        _uiState.update {
            it.copy(
                isPreparingPhotoSession = false,
                isPhotoSessionReady = false,
                isCapturingPhoto = false,
                capturedPhoto = null,
                recentError = null,
            )
        }
    }

    /**
     * Prepare a short-lived camera session and wait until it is STREAMING.
     * After this succeeds, the UI can start a 3..2..1 countdown and call [capturePreparedPhoto].
     */
    fun preparePhotoCaptureSession() {
        // Don't prepare if we're already ready / preparing / capturing.
        if (_uiState.value.isPreparingPhotoSession || _uiState.value.isPhotoSessionReady || _uiState.value.isCapturingPhoto) {
            Log.d(TAG, "preparePhotoCaptureSession() skipped (already preparing/ready/capturing)")
            return
        }
        Log.d(TAG, "preparePhotoCaptureSession()")

        preparePhotoJob?.cancel()
        preparePhotoJob =
            viewModelScope.launch {
            if (!_uiState.value.hasActiveDevice) {
                Log.w(TAG, "preparePhotoCaptureSession: no active device")
                setRecentError("Connect to glasses first")
                return@launch
            }

            // Reset any previous session
            closePreparedPhotoSessionOnce()

            _uiState.update {
                it.copy(
                    isPreparingPhotoSession = true,
                    isPhotoSessionReady = false,
                    isCapturingPhoto = false,
                    capturedPhoto = null,
                    recentError = null,
                )
            }

            var assignedToField = false
            val session =
                try {
                    val quality = AppSettings.getCameraVideoQuality(getApplication())
                    Log.d(TAG, "preparePhotoCaptureSession: starting stream session, quality=$quality")
                    Wearables.startStreamSession(
                        getApplication(),
                        deviceSelector,
                        StreamConfiguration(videoQuality = quality, 24),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "preparePhotoCaptureSession: startStreamSession failed", t)
                    _uiState.update {
                        it.copy(
                            isPreparingPhotoSession = false,
                            isPhotoSessionReady = false,
                            recentError = t.message ?: "Failed to start camera session",
                        )
                    }
                    return@launch
                }

            try {
                // Wait for STREAMING so capture is instant later
                Log.d(TAG, "preparePhotoCaptureSession: waiting for STREAMING state (timeout 8s)")
                val streamingState =
                    withTimeoutOrNull(8_000) {
                        session.state.first { it == StreamSessionState.STREAMING }
                    }

                if (streamingState == null) {
                    Log.w(TAG, "preparePhotoCaptureSession: timed out waiting for STREAMING")
                    _uiState.update {
                        it.copy(
                            isPreparingPhotoSession = false,
                            isPhotoSessionReady = false,
                            recentError = "Timed out waiting for stream",
                        )
                    }
                    return@launch
                }

                Log.d(TAG, "preparePhotoCaptureSession: session is STREAMING, photo capture ready")
                // Publish prepared session only once fully ready.
                preparedPhotoSession = session
                assignedToField = true
                _uiState.update { it.copy(isPreparingPhotoSession = false, isPhotoSessionReady = true) }
            } finally {
                // If we never stored it, we must close to avoid leaks.
                if (!assignedToField) {
                    runCatching { session.close() }
                }
            }
        }
    }

    /**
     * Capture the photo from the already-prepared session (should be instant).
     * This will close the prepared session afterward.
     */
    fun capturePreparedPhoto() {
        if (_uiState.value.isCapturingPhoto) return
        val session = preparedPhotoSession ?: run {
            Log.w(TAG, "capturePreparedPhoto: no prepared session")
            setRecentError("Camera not ready")
            return
        }
        Log.d(TAG, "capturePreparedPhoto()")

        capturePhotoJob?.cancel()
        capturePhotoJob =
            viewModelScope.launch {
            _uiState.update { it.copy(isCapturingPhoto = true, recentError = null) }
            try {
                val result = session.capturePhoto()

                var bitmap: Bitmap? = null
                result
                    .onSuccess { photoData ->
                        Log.d(TAG, "capturePreparedPhoto: photo received, type=${photoData::class.simpleName}")
                        bitmap =
                            when (photoData) {
                                is PhotoData.Bitmap -> photoData.bitmap
                                is PhotoData.HEIC -> {
                                    val byteArray = ByteArray(photoData.data.remaining())
                                    photoData.data.get(byteArray)
                                    BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                                }
                            }
                    }
                    .onFailure { err ->
                        Log.e(TAG, "capturePreparedPhoto: capturePhoto failed", err)
                        throw err
                    }

                if (bitmap == null) {
                    throw IllegalStateException("Failed to decode photo")
                }

                Log.d(TAG, "capturePreparedPhoto: bitmap decoded successfully")
                _uiState.update { it.copy(capturedPhoto = bitmap, isCapturingPhoto = false, isPhotoSessionReady = false) }
            } catch (t: Throwable) {
                Log.e(TAG, "capturePreparedPhoto: error", t)
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = false,
                        isPhotoSessionReady = false,
                        recentError = t.message ?: "Photo capture failed",
                    )
                }
            } finally {
                closePreparedPhotoSessionOnce()
                _uiState.update { it.copy(isPreparingPhotoSession = false, isPhotoSessionReady = false) }
            }
        }
    }

    /**
     * Capture a single photo from the glasses camera.
     *
     * Note: caller should ensure wearable CAMERA permission is granted (via Meta AI app flow)
     * before invoking this.
     *
     * Implementation mirrors the "temporary stream for photo" pattern: start a session,
     * wait for STREAMING, call capturePhoto(), close session.
     */
    fun captureSinglePhoto() {
        // Avoid concurrent captures
        if (_uiState.value.isCapturingPhoto || _uiState.value.isPreparingPhotoSession || _uiState.value.isPhotoSessionReady) return
        Log.d(TAG, "captureSinglePhoto()")

        viewModelScope.launch {
            // Pre-flight: require an active device
            if (!_uiState.value.hasActiveDevice) {
                Log.w(TAG, "captureSinglePhoto: no active device")
                setRecentError("Connect to glasses first")
                return@launch
            }

            _uiState.update { it.copy(isCapturingPhoto = true, capturedPhoto = null, recentError = null) }

            val session =
                try {
                    val quality = AppSettings.getCameraVideoQuality(getApplication())
                    Log.d(TAG, "captureSinglePhoto: starting stream session, quality=$quality")
                    Wearables.startStreamSession(
                        getApplication(),
                        deviceSelector,
                        StreamConfiguration(videoQuality = quality, 24),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "captureSinglePhoto: startStreamSession failed", t)
                    _uiState.update {
                        it.copy(
                            isCapturingPhoto = false,
                            recentError = t.message ?: "Failed to start camera session",
                        )
                    }
                    return@launch
                }

            try {
                // Wait until the stream is actually running (avoid hanging forever)
                Log.d(TAG, "captureSinglePhoto: waiting for STREAMING state (timeout 8s)")
                val streamingState =
                    withTimeoutOrNull(8_000) {
                        // Session.state is a Flow/StateFlow exposed by SDK
                        session.state.first { it == StreamSessionState.STREAMING }
                    }
                if (streamingState == null) {
                    Log.w(TAG, "captureSinglePhoto: timed out waiting for STREAMING")
                    throw IllegalStateException("Timed out waiting for stream")
                }

                Log.d(TAG, "captureSinglePhoto: session STREAMING, capturing photo")
                val result = session.capturePhoto()

                var bitmap: Bitmap? = null
                result
                    .onSuccess { photoData ->
                        Log.d(TAG, "captureSinglePhoto: photo received, type=${photoData::class.simpleName}")
                        bitmap =
                            when (photoData) {
                                is PhotoData.Bitmap -> photoData.bitmap
                                is PhotoData.HEIC -> {
                                    val byteArray = ByteArray(photoData.data.remaining())
                                    photoData.data.get(byteArray)
                                    BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                                }
                            }
                    }
                    .onFailure { err ->
                        Log.e(TAG, "captureSinglePhoto: capturePhoto failed", err)
                        throw err
                    }

                if (bitmap == null) {
                    throw IllegalStateException("Failed to decode photo")
                }

                Log.d(TAG, "captureSinglePhoto: bitmap decoded successfully")
                _uiState.update { it.copy(capturedPhoto = bitmap, isCapturingPhoto = false) }
            } catch (t: Throwable) {
                Log.e(TAG, "captureSinglePhoto: error", t)
                _uiState.update {
                    it.copy(
                        isCapturingPhoto = false,
                        recentError = t.message ?: "Photo capture failed",
                    )
                }
            } finally {
                session.close()
            }
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared()")
        super.onCleared()
        preparePhotoJob?.cancel()
        capturePhotoJob?.cancel()
        closePreparedPhotoSessionOnce()
        deviceSelectorJob?.cancel()
        deviceMetadataJobs.values.forEach { it.cancel() }
        deviceMetadataJobs.clear()
    }
}

