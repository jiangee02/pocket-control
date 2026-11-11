package com.pocketcontrol.host

import android.content.Context
import android.content.SharedPreferences

/** 全局偏好设置 */
object Pref {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    }

    /** 直连模式端口 */
    var port: Int
        get() = sp.getInt("port", 9371)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 画质: low / mid / high / orig（默认原画，主控可向下切换，避免上行黑屏） */
    var quality: String
        get() = sp.getString("quality", "orig") ?: "orig"
        set(v) = sp.edit().putString("quality", v).apply()

    /** 会话结束自动关闭无障碍 */
    var autoCloseAcc: Boolean
        get() = sp.getBoolean("autoAcc", true)
        set(v) = sp.edit().putBoolean("autoAcc", v).apply()

    /** 连接模式: direct / relay */
    var mode: String
        get() = sp.getString("mode", "direct") ?: "direct"
        set(v) = sp.edit().putString("mode", v).apply()

    /** 中继服务器地址，如 relay.example.com:8787 */
    var relayUrl: String
        get() = sp.getString("relay", "") ?: ""
        set(v) = sp.edit().putString("relay", v).apply()

    /** 中继房间密钥（首次使用自动生成，永久不变） */
    var relaySecret: String
        get() {
            var s = sp.getString("relaySecret", "") ?: ""
            if (s.isBlank()) {
                s = randomHex(32)
                sp.edit().putString("relaySecret", s).apply()
            }
            return s
        }
        set(v) = sp.edit().putString("relaySecret", v).apply()

    /** 配对 PIN：每次安装固定不变，可自定义或随机更换（下次会话生效） */
    var pin: String
        get() {
            var s = sp.getString("pin", "") ?: ""
            if (!Regex("^\\d{6}$").matches(s)) {
                s = "%06d".format(java.util.Random().nextInt(1_000_000))
                sp.edit().putString("pin", s).apply()
            }
            return s
        }
        set(v) = sp.edit().putString("pin", v).apply()

    fun regenPin() {
        pin = "%06d".format(java.util.Random().nextInt(1_000_000))
    }

    fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(chars[(Math.random() * 16).toInt()])
        return sb.toString()
    }
}
