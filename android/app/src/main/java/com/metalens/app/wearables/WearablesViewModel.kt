package com.metalens.app.wearables

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
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
import java.io.ByteArrayInputStream
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

    // Include all devices (Gen 1 and Gen 2) by not filtering out incompatible devices.
    // By default in SDK 0.4.0+, AutoDeviceSelector filters out incompatible devices, which
    // would exclude Ray-Ban Meta Gen 1 glasses. Passing filter = { _, _ -> true } ensures
    // Gen 1 devices are still selected and available for camera and conversation features.
    val deviceSelector: DeviceSelector = AutoDeviceSelector(filter = { _, _ -> true })
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
        monitoringStarted = true

        deviceSelectorJob =
            viewModelScope.launch {
                deviceSelector.activeDevice(Wearables.devices).collect { device ->
                    _uiState.update { it.copy(activeDevice = device, hasActiveDevice = device != null) }
                }
            }

        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                _uiState.update { it.copy(registrationState = state) }
            }
        }

        viewModelScope.launch {
            Wearables.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices.toList()) }
                monitorDeviceMetadata(devices)
            }
        }
    }

    private fun monitorDeviceMetadata(devices: Set<DeviceIdentifier>) {
        // cancel removed
        val removed = deviceMetadataJobs.keys - devices
        removed.forEach { id ->
            deviceMetadataJobs[id]?.cancel()
            deviceMetadataJobs.remove(id)
            _uiState.update { state ->
                state.copy(deviceDisplayNames = state.deviceDisplayNames - id.toString())
            }
        }

        // start for new
        val newDevices = devices - deviceMetadataJobs.keys
        newDevices.forEach { deviceId ->
            val job =
                viewModelScope.launch {
                    Wearables.devicesMetadata[deviceId]?.collect { metadata ->
                        val idKey = deviceId.toString()
                        val name = metadata.name.ifEmpty { idKey }
                        _uiState.update { state ->
                            state.copy(deviceDisplayNames = state.deviceDisplayNames + (idKey to name))
                        }

                        when (metadata.compatibility) {
                            DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> {
                                setRecentError("Device '$name' needs a firmware update. Update your glasses via the Meta AI app.")
                            }
                            DeviceCompatibility.INCOMPATIBLE -> {
                                setRecentError("Device '$name' may have limited functionality with this app. Some features might not work.")
                            }
                            else -> {}
                        }
                    }
                }
            deviceMetadataJobs[deviceId] = job
        }
    }

    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Wearables.startUnregistration(activity)
    }

    fun setRecentError(error: String?) {
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
            return
        }

        preparePhotoJob?.cancel()
        preparePhotoJob =
            viewModelScope.launch {
            if (!_uiState.value.hasActiveDevice) {
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
                    Wearables.startStreamSession(
                        getApplication(),
                        deviceSelector,
                        StreamConfiguration(videoQuality = quality, 24),
                    )
                } catch (t: Throwable) {
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
                val streamingState =
                    withTimeoutOrNull(8_000) {
                        session.state.first { it == StreamSessionState.STREAMING }
                    }

                if (streamingState == null) {
                    _uiState.update {
                        it.copy(
                            isPreparingPhotoSession = false,
                            isPhotoSessionReady = false,
                            recentError = "Timed out waiting for stream",
                        )
                    }
                    return@launch
                }

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
            setRecentError("Camera not ready")
            return
        }

        capturePhotoJob?.cancel()
        capturePhotoJob =
            viewModelScope.launch {
            _uiState.update { it.copy(isCapturingPhoto = true, recentError = null) }
            try {
                val result = session.capturePhoto()

                var bitmap: Bitmap? = null
                result
                    .onSuccess { photoData ->
                        bitmap =
                            when (photoData) {
                                is PhotoData.Bitmap -> photoData.bitmap
                                is PhotoData.HEIC -> {
                                    val byteArray = ByteArray(photoData.data.remaining())
                                    photoData.data.get(byteArray)
                                    decodeHeicWithOrientation(byteArray)
                                }
                            }
                    }
                    .onFailure { err ->
                        throw err
                    }

                if (bitmap == null) {
                    throw IllegalStateException("Failed to decode photo")
                }

                _uiState.update { it.copy(capturedPhoto = bitmap, isCapturingPhoto = false, isPhotoSessionReady = false) }
            } catch (t: Throwable) {
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

        viewModelScope.launch {
            // Pre-flight: require an active device
            if (!_uiState.value.hasActiveDevice) {
                setRecentError("Connect to glasses first")
                return@launch
            }

            _uiState.update { it.copy(isCapturingPhoto = true, capturedPhoto = null, recentError = null) }

            val session =
                try {
                    val quality = AppSettings.getCameraVideoQuality(getApplication())
                    Wearables.startStreamSession(
                        getApplication(),
                        deviceSelector,
                        StreamConfiguration(videoQuality = quality, 24),
                    )
                } catch (t: Throwable) {
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
                val streamingState =
                    withTimeoutOrNull(8_000) {
                        // Session.state is a Flow/StateFlow exposed by SDK
                        session.state.first { it == StreamSessionState.STREAMING }
                    }
                if (streamingState == null) {
                    throw IllegalStateException("Timed out waiting for stream")
                }

                val result = session.capturePhoto()

                var bitmap: Bitmap? = null
                result
                    .onSuccess { photoData ->
                        bitmap =
                            when (photoData) {
                                is PhotoData.Bitmap -> photoData.bitmap
                                is PhotoData.HEIC -> {
                                    val byteArray = ByteArray(photoData.data.remaining())
                                    photoData.data.get(byteArray)
                                    decodeHeicWithOrientation(byteArray)
                                }
                            }
                    }
                    .onFailure { err ->
                        throw err
                    }

                if (bitmap == null) {
                    throw IllegalStateException("Failed to decode photo")
                }

                _uiState.update { it.copy(capturedPhoto = bitmap, isCapturingPhoto = false) }
            } catch (t: Throwable) {
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
        super.onCleared()
        preparePhotoJob?.cancel()
        capturePhotoJob?.cancel()
        closePreparedPhotoSessionOnce()
        deviceSelectorJob?.cancel()
        deviceMetadataJobs.values.forEach { it.cancel() }
        deviceMetadataJobs.clear()
    }

    /**
     * Decode a HEIC byte array to a Bitmap, applying EXIF orientation correction.
     * Gen 1 and Gen 2 glasses may produce photos with different orientation metadata.
     */
    private fun decodeHeicWithOrientation(byteArray: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size) ?: return null
        val matrix = Matrix()
        try {
            ByteArrayInputStream(byteArray).use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation from HEIC, decoding without rotation", e)
        }
        return if (matrix.isIdentity) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

