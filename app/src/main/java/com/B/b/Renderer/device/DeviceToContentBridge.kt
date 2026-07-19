package com.B.b.Renderer.device

import com.B.b.Renderer.js.JsEngine

/**
 * device側(ショートカット/.rjs)の実行結果を、content側(ページ内JS/JsEngine)へ
 * 値として注入するための橋渡し。Engineセッション版のDeviceToContentBridgeにあった
 * 「device→contentはJavaオブジェクト参照を渡さず、値のみ渡す」という安全設計の考え方を
 * そのまま踏襲している。
 *
 * device側は元々ShortcutApiという限定APIしか公開していないため、返ってくる値は
 * 基本的にString/Boolean/Int/JsElement程度に収まる想定だが、将来ShortcutApiの
 * 戻り値が増えた場合の保険として、ここでプリミティブ/Map/List以外は文字列化してから
 * content側へ渡すようにする(content側のページJSは信頼できないため、
 * 生のJava/Androidオブジェクト参照を絶対に渡さない)。
 */
class DeviceToContentBridge(
    private val jsEngine: JsEngine,
) {
    fun injectResult(varName: String, rawResult: Any?) {
        jsEngine.injectGlobal(varName, sanitize(rawResult))
    }

    private fun sanitize(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sanitize(v) }
        is List<*> -> value.map { sanitize(it) }
        else -> value.toString() // それ以外(Java/Androidオブジェクト等)はtoString()に落とす
    }
}
