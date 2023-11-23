package io.keyss.view_record.utils

import android.util.Log

object VRLogger {
    private const val TAG = "ViewRecord"
    var logLevel = Log.WARN

    @JvmStatic
    fun v(msg: String) {
        if (logLevel <= Log.VERBOSE) {
            Log.v(TAG, getThreadName() + msg)
        }
    }

    @JvmStatic
    fun d(msg: String) {
        if (logLevel <= Log.DEBUG) {
            Log.d(TAG, getThreadName() + msg)
        }
    }

    @JvmStatic
    fun i(msg: String) {
        if (logLevel <= Log.INFO) {
            Log.i(TAG, getThreadName() + msg)
        }
    }

    @JvmStatic
    fun w(msg: String, tr: Throwable? = null) {
        if (logLevel <= Log.WARN) {
            Log.w(TAG, getThreadName() + msg, tr)
        }
    }

    @JvmStatic
    fun e(msg: String, tr: Throwable? = null) {
        if (logLevel <= Log.ERROR) {
            Log.e(TAG, getThreadName() + msg, tr)
        }
    }

    private fun getThreadName(): String {
        return "Thread: ${Thread.currentThread().name} - "
    }
}
