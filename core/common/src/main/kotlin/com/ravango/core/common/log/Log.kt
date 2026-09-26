package com.ravango.core.common.log

import android.util.Log

/**
 * Minimal logger. Debug/verbose output is stripped in release by R8 (see app proguard rules);
 * warnings and errors are kept and forwarded to the optional [sink] (e.g. a crash reporter once the user consents).
 */
object RgLog {
    @Volatile var sink: ((level: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? = null
    @Volatile var debugEnabled: Boolean = true

    fun d(tag: String, message: String) { if (debugEnabled) Log.d("RG/$tag", message) }
    fun i(tag: String, message: String) { if (debugEnabled) Log.i("RG/$tag", message) }
    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w("RG/$tag", message, t); sink?.invoke(Log.WARN, tag, message, t)
    }
    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e("RG/$tag", message, t); sink?.invoke(Log.ERROR, tag, message, t)
    }
}
