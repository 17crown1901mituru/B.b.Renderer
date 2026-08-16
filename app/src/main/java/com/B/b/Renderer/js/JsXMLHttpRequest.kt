package com.B.b.Renderer.js

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.io.IOException

/**
 * `new XMLHttpRequest()` をJSから呼べるようにするRhinoホストクラス。
 *
 * Rhinoの host object 規約: ScriptableObject を継承し、公開の no-arg コンストラクタを持つ
 * (defineClass経由での `new` 呼び出しに使われる)。 メソッドは `jsFunction_` プレフィックス、
 * プロパティは `jsGet_`/`jsSet_` プレフィックスの命名規則で自動的にJSへ公開される。
 * (これはJsElement等で採用している素のLiveConnect方式とは別の、
 * JS側の `new` をサポートする必要がある場合専用の規約)
 *
 * htmx.jsはfetch()ではなくXMLHttpRequestを使う2.x系列を想定しているため、
 * このシムはXHRの一般的な使用パターン(open/setRequestHeader/send/onreadystatechange/onload)
 * をカバーする。ストリーミング(progress event逐次発火)は簡易化のため未対応。
 */
class JsXMLHttpRequest : ScriptableObject() {

    companion object {
        // OkHttpClientはスレッドセーフ・不変なので、単一インスタンスを共有する。
        // JsEngine初期化時に一度だけセットする。
        @Volatile
        private var sharedHttpClient: OkHttpClient? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun install(scope: org.mozilla.javascript.Scriptable, httpClient: OkHttpClient) {
            sharedHttpClient = httpClient
            ScriptableObject.defineClass(scope, JsXMLHttpRequest::class.java)
        }

        const val UNSENT = 0
        const val OPENED = 1
        const val HEADERS_RECEIVED = 2
        const val LOADING = 3
        const val DONE = 4
    }

    override fun getClassName(): String = "XMLHttpRequest"

    // --- 状態 ---
    private var readyStateValue = UNSENT
    private var statusValue = 0
    private var statusTextValue = ""
    private var responseTextValue = ""
    private var responseHeadersRaw = ""
    private var method = "GET"
    private var url = ""
    private var async = true
    private val requestHeaders = mutableMapOf<String, String>()
    private var activeCall: Call? = null

    var onreadystatechange: Function? = null
    var onload: Function? = null
    var onerror: Function? = null

    fun jsGet_readyState(): Int = readyStateValue
    fun jsGet_status(): Int = statusValue
    fun jsGet_statusText(): String = statusTextValue
    fun jsGet_responseText(): String = responseTextValue
    fun jsGet_response(): String = responseTextValue

    fun jsFunction_open(method: String, url: String, async: Any?) {
        this.method = method
        this.url = url
        this.async = (async as? Boolean) ?: true
        readyStateValue = OPENED
        fireReadyStateChange()
    }

    fun jsFunction_setRequestHeader(name: String, value: String) {
        requestHeaders[name] = value
    }

    fun jsFunction_getAllResponseHeaders(): String = responseHeadersRaw

    fun jsFunction_getResponseHeader(name: String): String? {
        return responseHeadersRaw.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    fun jsFunction_abort() {
        activeCall?.cancel()
        readyStateValue = UNSENT
    }

    fun jsFunction_send(body: Any?) {
        val client = sharedHttpClient
        if (client == null) {
            invokeCallback(onerror)
            return
        }

        val bodyStr = when (body) {
            is String -> body
            null, org.mozilla.javascript.Undefined.instance -> null
            else -> Context.toString(body)
        }

        val requestBuilder = Request.Builder().url(url)
        requestHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        when (method.uppercase()) {
            "GET", "HEAD" -> requestBuilder.method(method, null)
            else -> {
                val contentType = (requestHeaders["Content-Type"] ?: "text/plain").toMediaTypeOrNull()
                requestBuilder.method(method, (bodyStr ?: "").toRequestBody(contentType))
            }
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call
        readyStateValue = HEADERS_RECEIVED
        fireReadyStateChange()

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    statusValue = 0
                    readyStateValue = DONE
                    fireReadyStateChange()
                    invokeCallback(onerror)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string() ?: ""
                val headers = response.headers.joinToString("\r\n") { (k, v) -> "$k: $v" }
                mainHandler.post {
                    statusValue = response.code
                    statusTextValue = response.message
                    responseTextValue = text
                    responseHeadersRaw = headers
                    readyStateValue = DONE
                    fireReadyStateChange()
                    invokeCallback(onload)
                }
            }
        })
    }

    private fun fireReadyStateChange() {
        invokeCallback(onreadystatechange)
    }

    private fun invokeCallback(fn: Function?) {
        val callback = fn ?: return
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(this)
            callback.call(ctx, scope, this, emptyArray())
        } finally {
            Context.exit()
        }
    }
}
