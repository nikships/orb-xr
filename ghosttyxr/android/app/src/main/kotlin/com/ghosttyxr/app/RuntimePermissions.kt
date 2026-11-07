package com.ghosttyxr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ghosttyxr.app.ui.HAND_TRACKING_PERMISSION
import com.ghosttyxr.app.ui.HEAD_TRACKING_PERMISSION

/**
 * Every dangerous / special runtime permission declared in the manifest.
 *
 * Requested once at app startup via [ActivityResultContracts.RequestMultiplePermissions]
 * so the system dialogs don't fire mid-cockpit-flow (mic hotkey, QR scan, hand tracking
 * configure) and glitch XR layout. Feature code still checks grant state and may offer a
 * soft re-request if the user denied at launch.
 */
object RuntimePermissions {

    const val SCENE_UNDERSTANDING_PERMISSION = "android.permission.SCENE_UNDERSTANDING_COARSE"

    /**
     * Runtime permissions the app needs, filtered by API level.
     * Install-time normals (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE) are omitted.
     */
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(HAND_TRACKING_PERMISSION)
        add(HEAD_TRACKING_PERMISSION)
        add(SCENE_UNDERSTANDING_PERMISSION)
        if (sdkInt >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdkInt >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    /** Permissions from [required] that are not currently granted. */
    fun missing(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        required(sdkInt)
            .filterNot { context.hasPermission(it) }
            .toTypedArray()

    fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
