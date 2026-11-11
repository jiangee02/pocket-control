package com.pocketcontrol.host.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Base64
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * App 侧的系统级桥接客户端：经 Shizuku 以 shell uid 常驻拉起 [ShellMain]，
 * 用 stdin/stdout 行协议通信。Shizuku 未运行/未授权时 [alive]=false，调用方回退无障碍。
 */
class ShellBridge {

    class ClipResult(val ok: Boolean, val text: String, val reason: String)

    @Volatile
    var alive = false
        private set

    var log: ((String) -> Unit)? = null

    private var proc: IRemoteProcess? = null
    private var writer: PrintWriter? = null
    private val clipWaiters = ConcurrentHashMap<String, SynchronousQueue<ClipResult>>()
    private val acks = ConcurrentHashMap<String, SynchronousQueue<Pair<Boolean, String>>>()
    private var inputErrLogged = false

    fun start(ctx: Context): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
            val binder = Shizuku.getBinder() ?: return false
            val svc = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            val apk = ctx.applicationInfo.sourceDir
            val p = svc.newProcess(
                arrayOf("sh", "-c", "CLASSPATH='$apk' /system/bin/app_process /system/bin com.pocketcontrol.host.shell.ShellMain"),
                null, null
            )
            proc = p
            writer = PrintWriter(ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream), true)
            thread { readLoop(p) }
            val t0 = System.currentTimeMillis()
            while (!alive && System.currentTimeMillis() - t0 < 3000) Thread.sleep(40)
            if (!alive) { try { p.destroy() } catch (_: Throwable) {} }
            alive
        } catch (t: Throwable) {
            log?.invoke("系统级桥接启动失败：${t.message}")
            alive = false
            false
        }
    }

    private fun readLoop(p: IRemoteProcess) {
        try {
            val r = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)))
            while (true) {
                val line = r.readLine() ?: break
                when {
                    line == "READY" || line == "PONG" -> alive = true
                    line.startsWith("CLIP ") -> {
                        val rest = line.substring(5).split(' ', limit = 3)
                        val id = rest[0]
                        val status = if (rest.size > 1) rest[1] else "ERR"
                        val arg = if (rest.size > 2) rest[2] else ""
                        val txt = try {
                            String(Base64.decode(arg, Base64.NO_WRAP), Charsets.UTF_8)
                        } catch (_: Throwable) { "" }
                        val res = when (status) {
                            "OK" -> ClipResult(true, txt, "")
                            "ERR" -> ClipResult(false, "", txt.ifEmpty { "读取失败" })
                            else -> ClipResult(false, "", "未知响应：$status")
                        }
                        clipWaiters.remove(id)?.offer(res)
                    }
                    line.startsWith("ACK ") -> {
                        val rest = line.substring(4).split(' ', limit = 3)
                        val id = rest[0]
                        val ok = rest.getOrNull(1) == "OK"
                        val msg = if (rest.size > 2) try {
                            String(Base64.decode(rest[2], Base64.NO_WRAP), Charsets.UTF_8)
                        } catch (_: Throwable) { "" } else ""
                        acks.remove(id)?.offer(ok to msg)
                    }
                    line.startsWith("ERR") -> log?.invoke("系统级桥接：$line")
                }
            }
        } catch (_: Throwable) {
        }
        alive = false
    }

    private fun send(line: String) {
        try {
            writer?.println(line)
        } catch (_: Throwable) {
            alive = false
        }
    }

    fun injectKey(code: Int) { if (alive) send("key $code") }
    fun paste() { if (alive) send("paste") }

    /** 带应答的按键注入（可连发多次）；失败/超时返回 false，调用方回退无障碍 */
    fun key(code: Int, times: Int = 1, timeoutMs: Long = 700): Boolean {
        if (!alive) return false
        val id = System.nanoTime().toString()
        val q = SynchronousQueue<Pair<Boolean, String>>()
        acks[id] = q
        send("keyack $id $code ${times.coerceIn(1, 32)}")
        val r = try {
            q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally { acks.remove(id) }
        if (r?.first == true) { inputErrLogged = false; return true }
        logFirstInputError(r?.second)
        return false
    }

    private fun logFirstInputError(msg: String?) {
        if (inputErrLogged) return
        inputErrLogged = true
        log?.invoke("系统级输入不可用，已切换兼容输入${if (msg.isNullOrBlank()) "" else "：$msg"}")
    }

    fun setClip(s: String) {
        if (alive) send("clipset " + Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    }
    fun grantFiles(pkg: String) { if (alive) send("grantfiles $pkg") }
    fun grantClip(pkg: String) { if (alive) send("grantclip $pkg") }

    /** 以 shell 特权读系统剪贴板（绕过后台读限制）；桥接不可用/超时返回 null */
    fun getClip(timeoutMs: Long = 1500): ClipResult? {
        if (!alive) return null
        val id = System.nanoTime().toString()
        val q = SynchronousQueue<ClipResult>()
        clipWaiters[id] = q
        send("clipget $id")
        return try {
            q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            clipWaiters.remove(id)
        }
    }

    fun stop() {
        alive = false
        try { send("exit") } catch (_: Throwable) {}
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        writer = null
    }
}