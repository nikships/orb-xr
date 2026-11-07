package com.ghosttyxr.app

import android.Manifest
import com.ghosttyxr.app.ui.HAND_TRACKING_PERMISSION
import com.ghosttyxr.app.ui.HEAD_TRACKING_PERMISSION
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {

    @Test
    fun `required always includes core camera mic and xr tracking`() {
        val perms = RuntimePermissions.required(sdkInt = 24)
        assertTrue(Manifest.permission.CAMERA in perms)
        assertTrue(Manifest.permission.RECORD_AUDIO in perms)
        assertTrue(HAND_TRACKING_PERMISSION in perms)
        assertTrue(HEAD_TRACKING_PERMISSION in perms)
        assertTrue(RuntimePermissions.SCENE_UNDERSTANDING_PERMISSION in perms)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in perms)
        assertFalse(Manifest.permission.ACCESS_LOCAL_NETWORK in perms)
    }

    @Test
    fun `required adds post notifications on api 33+`() {
        val perms = RuntimePermissions.required(sdkInt = 33)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in perms)
        assertFalse(Manifest.permission.ACCESS_LOCAL_NETWORK in perms)
    }

    @Test
    fun `required adds local network on api 37+`() {
        val perms = RuntimePermissions.required(sdkInt = 37)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in perms)
        assertTrue(Manifest.permission.ACCESS_LOCAL_NETWORK in perms)
    }
}
