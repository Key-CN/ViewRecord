package io.keyss.view_record

import android.util.Log

object VRLogger {
    private const val TAG = "ViewRecord"
    var logLevel = Log.WARN

    fun v(msg: String) {
        if (logLevel <= Log.VERBOSE) {
            Log.v(TAG, msg)
        }
    }

    fun d(msg: String) {
        if (logLevel <= Log.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    fun i(msg: String) {
        if (logLevel <= Log.INFO) {
            Log.i(TAG, msg)
        }
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (logLevel <= Log.WARN) {
            Log.w(TAG, msg, tr)
        }
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (logLevel <= Log.ERROR) {
            Log.e(TAG, msg, tr)
        }
    }
}
