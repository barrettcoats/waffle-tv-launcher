package com.barrettcoats.waffle

import android.util.Log as AndroidLog

/**
 * All logging goes through one tag at INFO or above, because many TV builds (the Shield's
 * LineageOS among them) set `log.tag` to I and silently drop debug logs.
 *
 *     adb logcat -s Waffle
 */
object Log {
    const val TAG = "Waffle"

    fun i(message: String) {
        AndroidLog.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        AndroidLog.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        AndroidLog.e(TAG, message, error)
    }
}
