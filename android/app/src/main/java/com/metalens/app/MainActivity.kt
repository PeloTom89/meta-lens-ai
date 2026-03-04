package com.metalens.app

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.INTERNET
import android.os.Bundle
import android.util.Log
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
        private const val TAG = "MainActivity"
        val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, INTERNET)
    }

    private val wearablesViewModel: WearablesViewModel by viewModels()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
            Log.d(TAG, "Wearables permission result: $permissionStatus")
            permissionContinuation?.resume(permissionStatus)
            permissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")

        checkPermissions {
            Log.d(TAG, "All Android permissions granted — initializing Wearables SDK")
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
        Log.d(TAG, "requestWearablesPermission: $permission")
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }
    }

    private fun checkPermissions(onPermissionsGranted: () -> Unit) {
        Log.d(TAG, "checkPermissions(): requesting ${PERMISSIONS.toList()}")
        registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
            val granted = permissionsResult.entries.all { it.value }
            Log.d(TAG, "Android permissions result: $permissionsResult (allGranted=$granted)")
            if (granted) {
                onPermissionsGranted()
            } else {
                Log.w(TAG, "Not all Android permissions were granted: ${permissionsResult.filter { !it.value }.keys}")
                wearablesViewModel.setRecentError(
                    "Allow All Permissions (Bluetooth, Bluetooth Connect, Bluetooth Scan, Internet)"
                )
            }
        }.launch(PERMISSIONS)
    }
}

