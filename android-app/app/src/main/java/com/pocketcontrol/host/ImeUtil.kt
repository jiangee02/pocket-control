package com.pocketcontrol.host

import android.content.ComponentName
import android.content.Context
import com.pocketcontrol.host.ime.RemoteIme

/** 随手控远控输入法的组件 id，用于会话启动时识别并清理残留的默认输入法。 */
object ImeUtil {

    fun imeId(ctx: Context): String =
        ComponentName(ctx, RemoteIme::class.java).flattenToString()
}
