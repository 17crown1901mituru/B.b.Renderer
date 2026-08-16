package com.B.b.Renderer.core

/**
 * DOM Event相当の汎用イベントオブジェクト。
 * 現状はcapture/bubbleフェーズを実装していない(target自身に登録されたリスナーのみ発火)が、
 * 将来それらを追加する際にリスナーのシグネチャを変えずに済むよう、最初からこの形にしておく。
 *
 * Rhino側(content JSのaddEventListener)からも同じインスタンスをそのまま
 * (あるいはJsラッパー経由で)参照できるよう、公開プロパティはpreventDefault/stopPropagationの
 * 呼び出し結果を読める形にしてある。
 */
class Event(
    val type: String,
    val target: Element,
    val detail: Any? = null,
) {
    var defaultPrevented: Boolean = false
        private set

    var propagationStopped: Boolean = false
        private set

    fun preventDefault() {
        defaultPrevented = true
    }

    fun stopPropagation() {
        propagationStopped = true
    }
}

typealias EventListener = (Event) -> Unit
