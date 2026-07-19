package com.B.b.Renderer.device

import android.content.res.AssetManager

/**
 * bshの Create Command機能(`bsh.commands.*`)相当を、命名規約ベースの軽量な仕組みで
 * 代替する(DECISION_device_engine_rhino.md 実装タスク3)。
 *
 * `assets/shortcuts/`配下に置かれた`*.rjs`ファイルを走査し、ファイル名(拡張子除く)を
 * ショートカット名として、中身をそのままスクリプトとしてDeviceScriptEngineに登録する。
 * bshのCreate Commandのような動的登録APIは無いが、「ファイルを置けば使えるようになる」
 * という体験自体は再現できる。
 */
object RjsShortcutScanner {

    private const val SHORTCUTS_DIR = "shortcuts"
    private const val EXTENSION = ".rjs"

    /**
     * assets/shortcuts/配下の*.rjsを読み込み、`ファイル名(拡張子なし) -> スクリプト内容`の
     * マップを返す。ディレクトリが存在しない場合は空マップを返す(未使用でも支障なし)。
     */
    fun scan(assets: AssetManager): Map<String, String> {
        val names = try {
            assets.list(SHORTCUTS_DIR)
        } catch (e: java.io.IOException) {
            null
        } ?: return emptyMap()

        val result = mutableMapOf<String, String>()
        for (fileName in names) {
            if (!fileName.endsWith(EXTENSION)) continue
            val shortcutName = fileName.removeSuffix(EXTENSION)
            val script = try {
                assets.open("$SHORTCUTS_DIR/$fileName").bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                continue
            }
            result[shortcutName] = script
        }
        return result
    }
}
