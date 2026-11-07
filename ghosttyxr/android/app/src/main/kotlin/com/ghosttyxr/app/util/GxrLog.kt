package com.ghosttyxr.app.util

import android.util.Log
import com.ghosttyxr.app.BuildConfig

/**
 * Thin logging facade for Orb XR.
 *
 * The point of routing logs through here rather than calling [android.util.Log]
 * directly is the [d] overload: verbose, debug-only chatter (connection paths,
 * pane lifecycle, pairing routing) is built behind a lambda that is **only
 * invoked in debug builds**. In release the `BuildConfig.DEBUG` branch is
 * statically false, so both the `Log.d` call and the message-construction
 * string work compile out — release Logcat stays quiet and we pay no runtime
 * cost for string building.
 *
 * Warnings and errors ([w] / [e] / [i]) pass straight through unconditionally:
 * they're rare and we want them in release crash logs.
 */
object GxrLog {

    /** Debug-only verbose log. The [message] lambda is not evaluated in release builds. */
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    fun i(tag: String, message: String) = Log.i(tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
