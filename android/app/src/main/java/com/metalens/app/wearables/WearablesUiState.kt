package com.metalens.app.wearables

import android.graphics.Bitmap
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.Unavailable(),
    val devices: List<DeviceIdentifier> = emptyList(),
    /**
     * Currently active (connected) device selected by the DeviceSelector, if any.
     */
    val activeDevice: DeviceIdentifier? = null,
    /**
     * Best-effort friendly names loaded from Wearables.devicesMetadata.
     * Keyed by deviceId.toString().
     */
    val deviceDisplayNames: Map<String, String> = emptyMap(),
    val hasActiveDevice: Boolean = false,
    /**
     * True while we are starting a short-lived camera session and waiting for it to become STREAMING.
     * Used so the UI can show "preparing" before the countdown starts.
     */
    val isPreparingPhotoSession: Boolean = false,
    /**
     * True when the temporary camera session is STREAMING and ready to take a photo instantly.
     */
    val isPhotoSessionReady: Boolean = false,
    val isCapturingPhoto: Boolean = false,
    val capturedPhoto: Bitmap? = null,
    val recentError: String? = null,
) {
    val isRegistered: Boolean = registrationState is RegistrationState.Registered

    val connectedDevices: List<DeviceIdentifier> =
        activeDevice?.let { listOf(it) } ?: emptyList()

    /** Human-readable summary of the current registration state for diagnostics. */
    val registrationStateLabel: String = registrationState::class.simpleName ?: registrationState.toString()
}

