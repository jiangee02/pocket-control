package com.pocketcontrol.host.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent

/**
 * 远控备用输入法：不渲染键盘 UI，只通过 InputConnection 提交文本 / 按键。
 * 系统默认输入法不会被自动切换，仅在用户手动选用时作为文字输入的备用通道。
 */
class RemoteIme : InputMethodService() {

    companion object {
        @Volatile
        var instance: RemoteIme? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onEvaluateInputViewShown(): Boolean = false

    private fun ic() = currentInputConnection

    /** 提交文字（支持中文等任意 Unicode） */
    fun commit(s: String) {
        ic()?.commitText(s, 1)
    }

    /** 删除光标前 n 个字符 */
    fun del(n: Int) {
        ic()?.deleteSurroundingText(n, 0)
    }

    /** 发送原始按键（回车、方向键等） */
    fun keyEvent(code: Int) {
        val conn = ic() ?: return
        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
