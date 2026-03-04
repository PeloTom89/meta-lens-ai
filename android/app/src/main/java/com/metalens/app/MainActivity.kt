package com.metalens.app

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.INTERNET
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.Wearables
import com.metalens.app.ui.navigation.MetaLensApp
import com.metalens.app.ui.theme.MetaLensTheme
import com.metalens.app.wearables.LocalWearablesPermissionRequester
import com.metalens.app.wearables.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    companion object {
        val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, INTERNET)
    }

    private val wearablesViewModel: WearablesViewModel by viewModels()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
            permissionContinuation?.resume(permissionStatus)
            permissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions {
            // Must be called before using any Wearables APIs
            Wearables.initialize(this)
            wearablesViewModel.startMonitoring()
        }

        setContent {
            MetaLensTheme {
                CompositionLocalProvider(
                    LocalWearablesPermissionRequester provides
                        com.metalens.app.wearables.WearablesPermissionRequester(::requestWearablesPermission),
                ) {
                    MetaLensApp()
                }
            }
        }
    }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }
    }

    private fun checkPermissions(onPermissionsGranted: () -> Unit) {
        registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
            val granted = permissionsResult.entries.all { it.value }
            if (granted) {
                onPermissionsGranted()
            } else {
                wearablesViewModel.setRecentError(
                    "Allow All Permissions (Bluetooth, Bluetooth Connect, Bluetooth Scan, Internet)"
                )
            }
        }.launch(PERMISSIONS)
    }
}

