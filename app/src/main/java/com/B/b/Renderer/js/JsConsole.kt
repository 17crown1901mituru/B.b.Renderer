package com.B.b.Renderer.js

import android.util.Log
import com.B.b.Renderer.debug.BehaviorAuditLog

class JsConsole(private val tag: String = "PageJS") {
    fun log(message: Any?) {
        Log.d(tag, message.toString())
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_CONSOLE, "log: $message")
    }
    fun warn(message: Any?) {
        Log.w(tag, message.toString())
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_CONSOLE, "warn: $message")
    }
    fun error(message: Any?) {
        Log.e(tag, message.toString())
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_CONSOLE, "error: $message")
    }
    fun info(message: Any?) {
        Log.i(tag, message.toString())
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_CONSOLE, "info: $message")
    }
}
