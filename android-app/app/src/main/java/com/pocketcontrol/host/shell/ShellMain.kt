package com.pocketcontrol.host.shell

import android.content.AttributionSource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.InputEvent
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * 系统级桥接服务端（借鉴 scrcpy-server）：由 Shizuku 以 shell uid 通过
 * `CLASSPATH=<apk> app_process /system/bin com.pocketcontrol.host.shell.ShellMain` 拉起，常驻读 stdin 行命令。
 * shell uid 拥有 INJECT_EVENTS 与剪贴板读写特权，故能：
 *  - 注入真实 KeyEvent（所有 App 的自定义编辑器/删除键都可靠，等同物理按键）
 *  - 读/写系统剪贴板（绕过安卓 10+ 后台读限制）
 * 不运行时 App 回退无障碍路径（部分应用受限）。
 */
object ShellMain {

    private var ctx: Context? = null
    private var clipCtx: Context? = null
    private var clipMgr: ClipboardManager? = null
    private var inject: java.lang.reflect.Method? = null
    private var im: Any? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintWriter(System.out, true)
        val inp = BufferedReader(InputStreamReader(System.`in`))
        out.println("READY")
        while (true) {
            val line = inp.readLine() ?: break
            val parts = line.split(' ', limit = 2)
            val cmd = parts[0]
            val arg = if (parts.size > 1) parts[1] else ""
            try {
                when (cmd) {
                    "ping" -> out.println("PONG")
                    "key" -> { injectKey(arg.toInt()); out.println("OK key") }
                    "paste" -> { injectKey(279); out.println("OK paste") }
                    // 带应答的按键注入：可带次数（删除多个字符）；失败时 App 会退回无障碍输入
                    "keyack" -> {
                        val sp = arg.indexOf(' ')
                        val id = if (sp > 0) arg.substring(0, sp) else "0"
                        val bits = (if (sp > 0) arg.substring(sp + 1) else "").trim().split(' ')
                        val code = bits.getOrNull(0)?.toIntOrNull() ?: 0
                        val times = (bits.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 32)
                        try {
                            repeat(times) { injectKey(code) }
                            out.println("ACK $id OK")
                        } catch (t: Throwable) {
                            out.println("ACK $id ERR ${b64(describe(t))}")
                        }
                    }
                    "clipset" -> { setClip(String(Base64.decode(arg, Base64.NO_WRAP), Charsets.UTF_8)); out.println("OK clipset") }
                    "clipget" -> out.println("CLIP $arg ${clipResponse()}")
                    "grantfiles" -> { appops("MANAGE_EXTERNAL_STORAGE", arg); out.println("OK grantfiles") }
                    "grantclip" -> { appops("READ_CLIPBOARD", arg); out.println("OK grantclip") }
                    "exit" -> break
                    else -> out.println("ERR unknown")
                }
            } catch (t: Throwable) {
                out.println("ERR ${describe(t)}")
            }
        }
    }

    private fun describe(t: Throwable): String {
        val sb = StringBuilder(one(t))
        for (s in t.suppressed) if (s !== t) sb.append(" / ").append(one(s))
        return sb.toString()
    }

    /** 展开 InvocationTargetException 的 cause，给出真正有用的异常名与消息 */
    private fun one(t0: Throwable): String {
        var c = t0
        var depth = 0
        while (c is java.lang.reflect.InvocationTargetException && c.cause != null && depth < 5) {
            c = c.cause!!
            depth++
        }
        val m = c.message
        return c.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    private fun context(): Context {
        ctx?.let { return it }
        val at = Class.forName("android.app.ActivityThread")
        var first: Throwable? = null
        try {
            val thread = at.getMethod("systemMain").invoke(null)
            return (at.getMethod("getSystemContext").invoke(thread) as Context).also { ctx = it }
        } catch (t: Throwable) { first = t }
        // 个别系统（如部分 HyperOS 版本）systemMain 初始化会抛异常：改用现存的 ActivityThread 取系统 Context
        try {
            val f = at.getDeclaredField("sCurrentActivityThread")
            f.isAccessible = true
            val thread = f.get(null) ?: throw IllegalStateException("ActivityThread 未初始化")
            return (at.getMethod("getSystemContext").invoke(thread) as Context).also { ctx = it }
        } catch (t: Throwable) {
            val e = IllegalStateException("无法创建系统 Context")
            e.addSuppressed(first)
            e.addSuppressed(t)
            throw e
        }
    }

    /** 包名伪装成 com.android.shell 的 Context（同 scrcpy FakeContext）：
     *  剪贴板服务按「调用 uid + 包名归属」校验，系统 Context 的包名 "android" 不属于 shell uid。 */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = ShellMain.shellPkg()
        override fun getOpPackageName(): String = ShellMain.shellPkg()
        override fun getAttributionTag(): String? = null
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(ShellMain.shellPkg()).build()
        override fun getApplicationContext(): Context = this
    }

    /** 与本进程 uid 匹配的包名：系统 Context 的包名是 "android"（属 system uid），
     *  appops 校验会报 "Calling uid 2000 does not own package android" 导致剪贴板读写被拒。
     *  Shizuku 若以 root 运行则本进程 uid=0，此时 "android" 才是正确的归属。 */
    private fun shellPkg(): String =
        if (Process.myUid() == 2000) "com.android.shell" else "android"

    private fun clipboard(): ClipboardManager {
        clipMgr?.let { return it }
        val c = clipCtx ?: ShellContext(context()).also { clipCtx = it }
        // 系统服务内部持有创建它的 Context（包名 "android"），需反射替换为 ShellContext；
        // 替换失败则只能沿用系统 Context（剪贴板读写可能被 appops 拒绝）。
        val m = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            val f = m.javaClass.getDeclaredField("mContext")
            f.isAccessible = true
            f.set(m, c)
        } catch (_: Throwable) {}
        clipMgr = m
        return m
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** 回传 "OK <base64内容>" 或 "ERR <base64错误>"，让 App 能区分「空」与「失败」。 */
    private fun clipResponse(): String = try {
        "OK ${b64(getClip())}"
    } catch (t: Throwable) {
        "ERR ${b64(describe(t))}"
    }

    // ---- 剪贴板：优先直接调系统服务 binder（不依赖 ActivityThread，HyperOS 上 systemMain 可能失败） ----
    private var clipSvc: Any? = null
    private var clipSvcErr: Throwable? = null

    private fun clipService(): Any {
        clipSvc?.let { return it }
        clipSvcErr?.let { throw it }
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? android.os.IBinder
                ?: throw IllegalStateException("剪贴板服务未就绪")
            val svc = Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: throw IllegalStateException("剪贴板接口为空")
            clipSvc = svc
            svc
        } catch (t: Throwable) {
            clipSvcErr = t
            throw t
        }
    }

    /** 按参数类型填壳：包名与 uid 归属一致，userId/deviceId 用 0；不认识的类型传 null。 */
    private fun clipArgs(m: java.lang.reflect.Method, clip: ClipData?): Array<Any?> =
        Array<Any?>(m.parameterTypes.size) { i ->
            val t = m.parameterTypes[i]
            when {
                t == ClipData::class.java -> clip
                t == String::class.java -> if ((clip == null && i == 0) || (clip != null && i == 1)) shellPkg() else null
                t == Int::class.javaPrimitiveType -> 0
                t == Long::class.javaPrimitiveType -> 0L
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }

    private fun binderGetClip(): String {
        val svc = clipService()
        val m = svc.javaClass.methods.firstOrNull {
            it.name == "getPrimaryClip" && it.parameterTypes.firstOrNull() == String::class.java
        } ?: throw IllegalStateException("剪贴板接口不兼容")
        val cd = m.invoke(svc, *clipArgs(m, null)) as? ClipData ?: return ""
        if (cd.itemCount == 0) return ""
        val item = cd.getItemAt(0)
        return item.text?.toString() ?: item.htmlText ?: item.uri?.toString()
            ?: item.intent?.toUri(0) ?: ""
    }

    private fun binderSetClip(s: String) {
        val svc = clipService()
        val m = svc.javaClass.methods.firstOrNull {
            it.name == "setPrimaryClip" && it.parameterTypes.firstOrNull() == ClipData::class.java
        } ?: throw IllegalStateException("剪贴板接口不兼容")
        m.invoke(svc, *clipArgs(m, ClipData.newPlainText("随手控", s)))
    }

    /** 读剪贴板：先走系统服务 binder，失败再退回系统 Context；两者都失败时把两个原因都带上。 */
    private fun getClip(): String {
        var binderErr: Throwable? = null
        try {
            return binderGetClip()
        } catch (t: Throwable) {
            binderErr = t
        }
        try {
            return clipboard().primaryClip?.getItemAt(0)
                ?.coerceToText(clipCtx ?: context())?.toString() ?: ""
        } catch (t: Throwable) {
            val e = IllegalStateException("剪贴板读取失败（uid ${Process.myUid()}）")
            e.addSuppressed(binderErr!!)
            e.addSuppressed(t)
            throw e
        }
    }

    private fun setClip(s: String) {
        var binderErr: Throwable? = null
        try {
            binderSetClip(s)
            return
        } catch (t: Throwable) {
            binderErr = t
        }
        try {
            clipboard().setPrimaryClip(ClipData.newPlainText("随手控", s))
        } catch (t: Throwable) {
            val e = IllegalStateException("剪贴板写入失败（uid ${Process.myUid()}）")
            e.addSuppressed(binderErr!!)
            e.addSuppressed(t)
            throw e
        }
    }

    private fun injectKey(code: Int) {
        if (inject == null) {
            val (mgr, m) = resolveInjector()
            im = mgr; inject = m
        }
        val now = SystemClock.uptimeMillis()
        inject!!.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), 0)
        inject!!.invoke(im, KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), 0)
    }

    /** 安卓14+ 注入接口迁到 InputManagerGlobal；旧版仍在 InputManager。两者都试，取可用者。 */
    private fun resolveInjector(): Pair<Any, java.lang.reflect.Method> {
        for (name in arrayOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val cls = Class.forName(name)
                val mgr = cls.getMethod("getInstance").invoke(null)
                val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                return mgr to m
            } catch (_: Throwable) {}
        }
        throw IllegalStateException("无法获取输入注入接口")
    }

    private fun appops(op: String, pkg: String) {
        val p = Runtime.getRuntime().exec(arrayOf("appops", "set", pkg, op, "allow"))
        p.waitFor()
    }
}