package com.jarves.mh.runtime

import android.util.Log
import com.jarves.mh.BuildConfig

/**
 * Central logging bridge (ISSUE-004, roadmap 2d).
 *
 * Debug diagnostics vanish in release builds — raw agent output, prompts and
 * environment keys must never reach logcat outside a debug session. Warnings
 * and errors survive, but callers must pass sanitized text only: no raw tool
 * output, no secrets, no command lines carrying provider keys.
 *
 * Release builds additionally strip `android.util.Log.d/v` via
 * `-assumenosideeffects` in proguard-rules.pro, which also silences stray
 * direct calls in third-party code.
 */
object AppLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}
