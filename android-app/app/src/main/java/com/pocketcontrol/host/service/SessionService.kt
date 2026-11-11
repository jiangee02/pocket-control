package com.pocketcontrol.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import android.webkit.MimeTypeMap
import com.pocketcontrol.host.AccessUtil
import com.pocketcontrol.host.Crypt
import com.pocketcontrol.host.ImeUtil
import com.pocketcontrol.host.MainActivity
import com.pocketcontrol.host.PickerActivity
import com.pocketcontrol.host.Pref
import com.pocketcontrol.host.R
import com.pocketcontrol.host.capture.ScreenEncoder
import com.pocketcontrol.host.ime.RemoteIme
import com.pocketcontrol.host.relay.RelayClient
import com.pocketcontrol.host.server.HostServer
import com.pocketcontrol.host.shell.ShellBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

interface VideoSink {
    fun sendFrame(frame: ByteArray)
}

/**
 * 会话服务：唯一的前台服务，只在会话期间存在。
 * 职责：投屏采集推流 / 接收并分发主控指令 / 文件收发 / PIN 鉴权 / 端到端加密。
 * 会话结束：自动关闭无障碍（可选）、还原输入法、停服务 —— 不留任何后台。
 */
class SessionService : Service() {

    companion object {
        const val NOTI_ID = 1001
        const val PICK_NOTI_ID = 1002
        const val OPEN_NOTI_ID = 1003
        const val CHANNEL_ID = "session"
        const val PICK_CHANNEL_ID = "pick"
        const val ACTION_STOP = "com.pocketcontrol.host.STOP"
        const val ACTION_QUALITY = "com.pocketcontrol.host.QUALITY"
        val QUALITY_PRESETS = setOf("low", "mid", "high", "orig")
        val QUALITY_NAMES = mapOf("low" to "流畅", "mid" to "均衡", "high" to "高清", "orig" to "原画")

        @Volatile var instance: SessionService? = null
        var uiCb: ((String, String) -> Unit)? = null

        // 投屏授权结果由 Activity 收到后原样放在这里，Service 直接用同一进程内的对象。
        // 部分小米/MIUI 机型经 Intent extra 二次打包后拿不到录屏令牌，getMediaProjection 会返回 null。
        @Volatile var pendingProjCode: Int? = null
        @Volatile var pendingProjData: Intent? = null

        // 本端刚写入剪贴板的内容（用于抑制剪贴板监听把自己的写入回声给主控）
        @Volatile var lastSelfClip: String? = null
        @Volatile var lastSelfClipAt = 0L

        // 最近一次已推给主控的内容：外部跨设备剪贴板（妙享/手机连接等）会把电脑内容回写手机，
        // 再被监听推回电脑形成回声环；同内容在窗口期内不再推送（回声持续时不断续期）
        @Volatile var lastPushedClip: String? = null
        @Volatile var lastPushedAt = 0L
        private const val CLIP_ECHO_WINDOW_MS = 8000L
    }

    lateinit var sessionPin: String; private set
    lateinit var sessionSalt: ByteArray; private set
    private var sessionKey: SecretKeySpec? = null
    @Volatile var isSessionActive = false; private set
    var curW = 0; var curH = 0
    var actualPort = 0; private set

    /** 下行流通道（视频/文本推给主控），独立于 WebSocket */
    interface StreamSink {
        fun send(type: Int, payload: ByteArray); fun close()
        /** 清掉队列里积压的旧视频帧（文本帧保留），传输开始/结束时让画面立刻回到实时 */
        fun flushVideo() {}
    }
    private val streamClients = CopyOnWriteArrayList<StreamSink>()

    fun registerStream(sink: StreamSink) {
        // 多主控：每个主控各自一条下行流。旧逻辑会关闭已有连接，导致先连的主控画面停在最后一帧
        streamClients.addIfAbsent(sink)
        updateEncoderPause()
    }

    /** 直连模式无观看者时暂停编码省电；中继模式需常驻推流供主控随时加入 */
    private fun updateEncoderPause() {
        val hasViewer = Pref.mode == "relay" || streamClients.isNotEmpty()
        encoder?.setPaused(!hasViewer)
    }

    fun cachedCfg(): ByteArray? = encoder?.cachedConfig()
    fun requestKeyFrame() { encoder?.requestKeyframe() }

    // ================= 文件传输让路：视频与文件共用手机 Wi-Fi，传输时压低视频码率 =================
    private val xferCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 专用通道开始传输时调用：首个任务把视频码率临时降到 4Mbps（分辨率不变），
     *  并清掉积压旧帧避免画面落后；全部结束后自动恢复原画质。 */
    fun beginTransfer() {
        if (xferCount.getAndIncrement() == 0) {
            encoder?.setTransferMode(true)
            flushVideoBacklog()
            requestKeyFrame()
        }
    }

    fun endTransfer() {
        val n = xferCount.decrementAndGet()
        if (n == 0) {
            encoder?.setTransferMode(false)
            flushVideoBacklog()
            requestKeyFrame()
        } else if (n < 0) {
            xferCount.set(0)
        }
    }

    private fun flushVideoBacklog() {
        for (s in streamClients) { try { s.flushVideo() } catch (_: Exception) {} }
    }

    fun unregisterStream(sink: StreamSink) {
        streamClients.remove(sink)
        updateEncoderPause()
    }

    fun streamBroadcast(type: Int, payload: ByteArray) {
        for (s in streamClients) {
            try { s.send(type, payload) } catch (e: Exception) { unregisterStream(s); try { s.close() } catch (_: Exception) {} }
        }
    }

    /** 给定原始帧，返回可直接下发的带类型前缀的加密帧 */
    fun videoFrameFor(b: ByteArray): ByteArray = wrapFrame(withTag(b))

    private var wake: PowerManager.WakeLock? = null
    // 息屏时投屏只能采到黑屏：把屏幕开关状态同步给主控显示「点一下唤醒」提示
    private var lastScreenOn: Boolean? = null
    private var lastWakeAt = 0L
    private val screenWatch = object : Runnable {
        override fun run() {
            if (!isSessionActive) return
            try {
                if (hasViewer()) {
                    val on = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                    if (lastScreenOn != on) broadcastScreenState()
                }
            } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 1000)
        }
    }
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    // 自动降档：只看实测的编码排队与出帧率，不猜机型/处理器（同一颗芯片不同系统版本表现差很多）
    private var autoQualityOff = false
    private var autoWarmupAt = 0L
    private var lagHighStreak = 0
    private var lastAutoDownAt = 0L
    private val QUALITY_ORDER = listOf("low", "mid", "high", "orig")
    private var server: HostServer? = null
    private var relay: RelayClient? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var shell: ShellBridge? = null
    private var authFails = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = SecureRandom()

    // 上传（主控 → 手机）
    private val upLock = Any()
    private var upOs: OutputStream? = null
    private var upId = -1
    private var upSize = 0L
    private var upDone = 0L
    private var upLastProg = 0L
    private var upName = ""
    private var upPath = ""
    // meta 已到、文件还没建好时先缓存数据块，避免开头几块因为流未就绪被丢掉
    private var upStarting = false
    private val upPre = ArrayList<ByteArray>()
    // 建文件期间就收到了 file-up-end：等流就绪后再收尾
    private var upFinishPending = false
    private var upFinishId = -1
    // 最近一次保存结果：主控重连后补发 file-up-end 时重放，避免“文件已保存但页面一直等不到确认”
    private var lastUpId = -1
    private var lastUpOk = false
    private var lastUpPath = ""
    private var lastUpName = ""
    // 并行上传：同一文件的多条连接各写一段偏移，收齐后统一落盘
    private val uploadParts = ConcurrentHashMap<String, UploadPart>()

    private class UploadPart(
        val temp: File, val name: String, val dest: String, val rel: String,
        val size: Long, val parts: Int
    ) {
        val done = BooleanArray(parts)
        var finished = false
        var result: String? = null
    }
    // 下载（手机 → 主控）
    private var dlId = 0
    private var lastSendErrLog = 0L

    // 旋转检测
    private var lastRealW = 0
    private var lastRealH = 0
    private val dispListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.removeCallbacks(rotCheck)
            // 旋转防抖 400→200ms：事件停止后更快重启采集，缩短横竖屏切换的定格时间
            mainHandler.postDelayed(rotCheck, 200)
        }
    }
    private val rotCheck = Runnable {
        val s = realSize()
        if (s.first != lastRealW || s.second != lastRealH) {
            lastRealW = s.first; lastRealH = s.second
            encoder?.requestRestart()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("手机端停止了会话")
            return START_NOT_STICKY
        }
        if (instance == null) startSession(intent)
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent?) {
        try {
            doStartSession(intent)
        } catch (t: Throwable) {
            log("启动失败：${t.message}")
            stopSession("启动失败：${t.message}")
        }
    }

    private fun doStartSession(intent: Intent?) {
        instance = this
        sessionPin = Pref.pin
        sessionSalt = Crypt.newSalt()
        sessionKey = try { Crypt.deriveKey(sessionPin, sessionSalt) } catch (e: Throwable) { null }
        isSessionActive = true
        autoQualityOff = false
        cleanupUploadParts(all = false)
        lagHighStreak = 0
        lastAutoDownAt = 0
        autoWarmupAt = SystemClock.elapsedRealtime() + 10000   // 首帧/首个关键帧期间数字失真，先别判
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        log("随手控 v$ver 会话已启动")

        createChannel()
        val n = buildNotif("正在启动…")
        if (Build.VERSION.SDK_INT >= 29) {
            // 必须显式声明 mediaProjection 类型：MIUI 在 Android 13 上就回移了安卓 14 的校验，
            // 类型为 0 时 getMediaProjection() 会抛 SecurityException（"require a foreground service..."）。
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, n)
        }

        val resultCode = pendingProjCode ?: intent?.getIntExtra("code", -1) ?: -1
        @Suppress("DEPRECATION")
        val data = pendingProjData ?: intent?.getParcelableExtra<Intent>("data")
        pendingProjCode = null
        pendingProjData = null
        projection = try {
            (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data ?: Intent())
        } catch (e: Throwable) {
            log("获取投屏许可失败：${e.javaClass.simpleName}：${e.message}")
            null
        }
        if (projection == null) {
            stopSession("投屏授权失败（系统没有返回录屏许可）")
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { mainHandler.post { stopSession("系统投屏已停止") } }
        }, mainHandler)

        @Suppress("DEPRECATION")
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "pc:session"
            )
            .also { it.acquire(6 * 60 * 60 * 1000L) }

        if (RcAccessibilityService.instance == null) {
            // 设置里可能仍写着「已启用」，但服务实际已解绑（系统回收过或手动关过一次）。
            // 这种状态下画面正常，触控/返回/主页/最近会静默全部失效，所以先强关再开触发重绑。
            if (AccessUtil.isEnabled(this)) {
                try { AccessUtil.disable(this) } catch (_: Exception) {}
            }
            if (!AccessUtil.enable(this)) {
                stopSession("无法自动开启无障碍：WRITE_SECURE_SETTINGS 未生效")
                return
            }
            // 绑定是异步的，主线程不能等；稍后确认一次，没连上就明确停下，不带着半个功能跑
            val sessKey = sessionKey
            mainHandler.postDelayed({
                if (isSessionActive && sessionKey === sessKey && RcAccessibilityService.instance == null) {
                    stopSession("无障碍服务没有连上，请检查Shizuku")
                }
            }, 3000)
        }
        // 不再把默认输入法切到隐藏的远控输入法：那会让手机键盘区在远控画面里变成黑块，
        // 且 HyperOS 会拒绝绑定。文字输入改走无障碍「剪贴板+粘贴」，真实输入法正常显示。
        cleanupLegacyIme()
        startClipWatch()
        // Shizuku 运行时启用系统级桥接（真实按键注入+剪贴板特权读写+所有文件访问）；否则回退无障碍
        shell = ShellBridge().also { b ->
            b.log = { log(it) }
            val ok = b.start(this)
            log(if (ok) "完整模式已启用（Shizuku 已连接）" else "受限模式（Shizuku 未运行）：部分输入与文件功能不可用")
            if (ok) {
                b.grantFiles(packageName)
                b.grantClip(packageName)
                thread {
                    val r = try { b.getClip() } catch (t: Throwable) { null }
                    if (r != null && !r.ok) log("剪贴板读取失败：${r.reason}")
                }
            }
        }

        val s = realSize()
        lastRealW = s.first; lastRealH = s.second
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(dispListener, mainHandler)

        encoder = ScreenEncoder(
            projection!!, this, Pref.quality,
            onVideo = { b, ptsUs -> pushVideo(b, ptsUs) },
            onConfig = { w, h ->
                curW = w; curH = h
                pushUi("vid", "$w x $h")
                broadcastText("""{"t":"vid-start","w":$w,"h":$h,"quality":"${Pref.quality}"}""")
            },
            onError = { log(it) }
        )
        encoder?.start()
        updateEncoderPause()   // 直连且尚无主控：先暂停编码，等主控连上再恢复（省电）

        if (Pref.mode == "relay") {
            pushUi("starting", "正在连接中继…")
            relay = RelayClient(Pref.relayUrl, Pref.relaySecret, this).also { it.connect() }
        } else {
            var started = false
            actualPort = Pref.port
            for (attempt in 0 until 12) {
                try {
                    val s = HostServer(actualPort, this)
                    s.start()
                    server = s
                    started = true
                    break
                } catch (e: Throwable) {
                    actualPort++
                }
            }
            if (!started) {
                stopSession("无法开启内置服务器：端口都被占用")
                return
            }
            pushUi("direct-ready", actualPort.toString())
        }
        mainHandler.removeCallbacks(screenWatch)
        mainHandler.postDelayed(screenWatch, 1000)
        notifyUpdate()
    }

    /** 把被控端屏幕开关状态推给主控：息屏时投屏只能采到黑屏，但触控仍可用 */
    private fun broadcastScreenState() {
        val on = try { (getSystemService(POWER_SERVICE) as PowerManager).isInteractive } catch (_: Throwable) { true }
        lastScreenOn = on
        try { broadcastText(JSONObject().put("t", "screen").put("on", on).toString()) } catch (_: Throwable) {}
        if (!on) log("被控端屏幕已息屏：画面为黑屏，主控点一下画面即可唤醒")
    }

    /** 主控点击黑屏时唤醒被控端：息屏下注入的手指事件唤不亮屏幕，必须走系统唤醒键 */
    private fun wakeIfScreenOff() {
        val pm = try { getSystemService(POWER_SERVICE) as PowerManager } catch (_: Throwable) { return }
        if (pm.isInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < 1500) return
        lastWakeAt = now
        val sh = shell
        if (sh != null && sh.alive) thread { try { sh.key(224) } catch (_: Throwable) {} }
        else mainHandler.post {
            // 没有 Shizuku 时退一步：重新拿一次「亮屏」锁，部分系统会顺带点亮屏幕
            try {
                val w = wake ?: return@post
                if (w.isHeld) w.release()
                w.acquire(6 * 60 * 60 * 1000L)
            } catch (_: Throwable) {}
        }
        mainHandler.postDelayed({ broadcastScreenState() }, 500)
    }

    // ================= 视频推流 =================

    private fun pushVideo(b: ByteArray, ptsUs: Long) {
        // 帧：0x01 + 4B 采集时间戳(ms, BE) + H.264，浏览器按真实间隔播放；配置包(SPS/PPS)：0x03 + H.264
        val isCfg = ptsUs < 0
        val head = if (isCfg) 1 else 5
        val frame = ByteArray(b.size + head)
        frame[0] = if (isCfg) 0x03 else 0x01
        if (!isCfg) {
            val pts = (ptsUs / 1000).toInt()
            frame[1] = (pts ushr 24 and 0xff).toByte()
            frame[2] = (pts ushr 16 and 0xff).toByte()
            frame[3] = (pts ushr 8 and 0xff).toByte()
            frame[4] = (pts and 0xff).toByte()
        }
        System.arraycopy(b, 0, frame, head, b.size)
        val now = SystemClock.elapsedRealtime()
        try {
            if (Pref.mode == "relay") relay?.broadcast(frame)
            else streamBroadcast(0x01, wrapFrame(frame))
        } catch (e: Exception) {
            if (now - lastSendErrLog > 5000) { lastSendErrLog = now; log("视频发送失败：${e.message}") }
        }
    }

    fun sendConfigTo(sink: VideoSink) {
        val cfg = encoder?.cachedConfig()
        if (cfg != null) sink.sendFrame(withTag(cfg))
        encoder?.requestKeyframe()
    }

    fun sendConfigToRelay() {
        val r = relay ?: return
        val cfg = encoder?.cachedConfig()
        if (cfg != null) r.broadcast(withTag(cfg))
        encoder?.requestKeyframe()
    }

    /** 配置包（SPS/PPS）前缀：0x03，与带时间戳的视频帧 0x01 区分 */
    private fun withTag(b: ByteArray): ByteArray {
        val f = ByteArray(b.size + 1)
        f[0] = 0x03
        System.arraycopy(b, 0, f, 1, b.size)
        return f
    }

    // ================= 协议：加密包装 =================

    fun wrapText(text: String): String {
        val k = sessionKey ?: return text
        return try {
            val enc = Crypt.encrypt(k, text.toByteArray(Charsets.UTF_8))
            JSONObject().put("t", "enc").put("d", Crypt.b64(enc)).toString()
        } catch (e: Exception) { text }
    }

    fun unwrapText(wrapped: String): String? = try {
        val j = JSONObject(wrapped)
        if (j.optString("t") == "enc") {
            val k = sessionKey ?: return null
            String(Crypt.decrypt(k, Crypt.unb64(j.optString("d"))), Charsets.UTF_8)
        } else wrapped
    } catch (e: Exception) { null }

    fun wrapFrame(frame: ByteArray): ByteArray {
        val k = sessionKey ?: return frame
        return try { Crypt.encrypt(k, frame) } catch (e: Exception) { frame }
    }

    fun unwrapFrame(data: ByteArray): ByteArray? {
        val k = sessionKey ?: return data
        return try { Crypt.decrypt(k, data) } catch (e: Exception) { null }
    }

    // ================= 协议：鉴权与握手 =================

    fun tryAuth(text: String): Boolean {
        val j = try { JSONObject(text) } catch (e: Exception) { return false }
        if (j.optString("t") != "auth") return false
        if (j.optString("pin") == sessionPin) return true
        authFails++
        log("PIN 验证失败（第 $authFails 次）")
        try { Thread.sleep(350) } catch (_: Exception) {}
        return false
    }

    fun helloJson(): String {
        val name = try {
            Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL
        } catch (e: Exception) { Build.MODEL }
        return JSONObject()
            .put("t", "hello")
            .put("w", curW)
            .put("h", curH)
            .put("name", name)
            .put("quality", Pref.quality)
            .put("salt", Crypt.b64(sessionSalt))
            .put("enc", sessionKey != null)
            .toString()
    }

    private var lastHelloLogAt = 0L
    private var lastGoneLogAt = 0L
    private var goneGrace: Runnable? = null
    private val GONE_GRACE_MS = 8000L

    fun onClientHello() {
        cancelGoneGrace()
        val now = SystemClock.elapsedRealtime()
        // 网页会同时建立指令 WS 与视频流，2 秒内重复回调只记一条日志
        if (now - lastHelloLogAt > 2000) log("主控端已连接")
        lastHelloLogAt = now
        // 新主控接手时清掉上一条连接残留的触点（断线/关页面会丢 up）
        RcAccessibilityService.instance?.resetTouches()
        // 息屏时投屏采集到的是全黑画面，且注入事件唤不亮 HyperOS 的锁屏；
        // 主控接入即唤醒一次，避免主控看到黑屏、点一下才有画面
        wakeIfScreenOff()
        broadcastScreenState()
        pushUi("client", "on")
    }

    /** 主控密钥就绪后重发编码配置（SPS/PPS）并请求关键帧 —— 解决密钥派生期间丢配置包导致黑屏 */
    fun onClientReady() {
        val cfg = encoder?.cachedConfig()
        if (cfg != null) {
            try {
                if (Pref.mode == "relay") relay?.broadcast(withTag(cfg))
                else streamBroadcast(0x01, wrapFrame(withTag(cfg)))
            } catch (_: Exception) {}
        }
        encoder?.requestKeyframe()
        sendEditRects()
        imeOn = false          // 新主控刚连上：把当前输入法状态无条件同步一次
        updateIme()
        startImeWatch()
    }

    /** 把当前窗口里的输入框位置发给所有主控：主控点击时据此决定是否弹出本机键盘。
     *  屏幕内容变化后主控会主动再问一次（editq），成本只有一次节点遍历。 */
    fun sendEditRects() {
        // 无障碍节点遍历放到主线程（服务回调线程），避免跨线程调用节点接口的不确定性
        mainHandler.post {
            val acc = RcAccessibilityService.instance ?: return@post
            try {
                val rects = JSONArray()
                for (r in acc.editableRects()) {
                    rects.put(
                        JSONArray().put((r[0] * 1000).toInt() / 1000.0).put((r[1] * 1000).toInt() / 1000.0)
                            .put((r[2] * 1000).toInt() / 1000.0).put((r[3] * 1000).toInt() / 1000.0)
                    )
                }
                broadcastText(JSONObject().put("t", "editrects").put("r", rects).toString())
            } catch (_: Throwable) {}
        }
    }

    // ================= 输入法状态（手机系统输入法弹出 → 主控跟随弹键盘） =================
    private var imeOn = false
    private val imeProbe = Runnable { updateIme() }

    /** 无障碍服务看到窗口变化时回调；也用于点击后短时间内的多次复查。 */
    fun onImeMaybeChanged() {
        if (!isSessionActive) return
        mainHandler.post(imeProbe)
    }

    /** 系统输入法弹出/收起时通知所有主控；主控只在收到「弹出」后才调起本机键盘。 */
    private fun updateIme() {
        val acc = RcAccessibilityService.instance ?: return
        val on = try { acc.imeShown() } catch (_: Throwable) { return }
        if (on == imeOn) return
        imeOn = on
        broadcastText(JSONObject().put("t", "ime").put("on", on).toString())
    }

    /** 点击后输入法要过几百毫秒才完全弹出，分几个时间点复查一次 */
    private fun checkImeSoon() {
        mainHandler.removeCallbacks(imeProbe)
        for (d in longArrayOf(120, 350, 800, 1500)) mainHandler.postDelayed(imeProbe, d)
    }

    /** 直连与中继都兜底轮询：部分系统不派发输入法窗口事件 */
    private val imePoll = object : Runnable {
        override fun run() {
            if (!isSessionActive) return
            updateIme()
            warnEncodeLag()
            sendHostStats()
            if (hasViewer()) mainHandler.postDelayed(this, 700)
        }
    }
    private var lastHstatAt = 0L
    /** 每 2 秒把编码排队耗时上报主控顶栏（「编码 xxms」） */
    private fun sendHostStats() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHstatAt < 2000) return
        lastHstatAt = now
        val lag = encoder?.encodeLagMs ?: 0
        broadcastText(JSONObject().put("t", "hstat").put("lag", lag).toString())
        autoTuneQuality(lag)
    }

    /**
     * 编码跟不上时自动降一档，让画面回到「跟手」。
     * 判据来自 ScreenEncoder 的实测值（排队耗时 + 实际出帧率），不看 Build.MODEL：
     * 同一个 SoC 在不同系统/温度/分辨率上差异巨大，只有现场数据是准的。
     * 只降不升（避免来回抖），用户手动改过画质后本次会话不再干预。
     */
    private fun autoTuneQuality(lag: Int) {
        if (autoQualityOff) return
        val enc = encoder ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < autoWarmupAt) return
        val fps = enc.outputFps
        val slow = lag >= 90 || (fps > 0 && fps * 100 / enc.targetFps < 70)
        if (!slow) { lagHighStreak = 0; return }
        lagHighStreak++
        if (lagHighStreak < 2 || now - lastAutoDownAt < 20000) return
        val cur = QUALITY_ORDER.indexOf(enc.preset).coerceAtLeast(0)
        if (cur <= 0) { lagHighStreak = 0; return }   // 已经在最低档
        val next = QUALITY_ORDER[cur - 1]
        lagHighStreak = 0
        lastAutoDownAt = now
        autoWarmupAt = now + 6000   // 重启编码后前一秒的数字不可信，别连环降
        applyQuality(next, auto = true)
    }
    private var lastLagWarnAt = 0L
    private fun warnEncodeLag() {
        val lag = encoder?.encodeLagMs ?: return
        if (lag < 80) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLagWarnAt < 20000) return
        lastLagWarnAt = now
        log("编码延迟偏高（约 ${lag}ms）：画面会比手指慢半拍，可把画质调低一档")
    }
    private fun hasViewer() = Pref.mode == "relay" || streamClients.isNotEmpty() ||
        (server?.clients?.isNotEmpty() == true)

    private fun startImeWatch() {
        mainHandler.removeCallbacks(imePoll)
        mainHandler.post(imePoll)
    }

    /** 画质以被控端设置为准：主控下拉框或手机设置任一处修改都走这里，
     *  重启编码并广播给所有主控，避免网页显示与实际编码不一致。 */
    fun applyQuality(q: String, auto: Boolean = false): Boolean {
        if (q !in QUALITY_PRESETS) return false
        if (q == Pref.quality) return false
        // 用户自己选过画质 = 他要的就是这个档，本次会话不再自动降档
        if (!auto) autoQualityOff = true
        Pref.quality = q
        encoder?.requestRestart(q)
        val msg = JSONObject().put("t", "quality").put("preset", q)
        if (auto) msg.put("auto", true)
        broadcastText(msg.toString())
        try {
            sendBroadcast(Intent(ACTION_QUALITY).setPackage(packageName).putExtra("quality", q))
        } catch (_: Throwable) {}
        if (auto) log("手机编码跟不上，画质已自动降为「${QUALITY_NAMES[q]}」（手动选画质可关闭自动调整）")
        return true
    }
    fun onClientGone() {
        val now = SystemClock.elapsedRealtime()
        RcAccessibilityService.instance?.resetTouches()
        if (now - lastGoneLogAt > 2000) log("主控端已断开")
        lastGoneLogAt = now
        pushUi("client", "off")
        // 浏览器切后台、打开文件选择器都会瞬间掐断 WS。先保留下行流和编码 8 秒：
        // 短暂断开再回来可以直接续上；确实关闭或刷新，到期后再清理并暂停编码省电。
        scheduleGoneCleanup()
        if (!hasViewer()) { mainHandler.removeCallbacks(imePoll); imeOn = false }
    }

    private fun cancelGoneGrace() {
        goneGrace?.let { mainHandler.removeCallbacks(it) }
        goneGrace = null
    }

    private fun scheduleGoneCleanup() {
        cancelGoneGrace()
        val r = Runnable {
            goneGrace = null
            if (hasViewer()) return@Runnable   // 宽限期内已经重新连上
            if (Pref.mode != "relay" && server?.clients?.isEmpty() != false) {
                for (s in streamClients) { try { s.close() } catch (_: Exception) {} }
                streamClients.clear()
            }
            updateEncoderPause()
            if (!hasViewer()) { mainHandler.removeCallbacks(imePoll); imeOn = false }
        }
        goneGrace = r
        mainHandler.postDelayed(r, GONE_GRACE_MS)
    }
    fun onRelayRoom(room: String) {
        val host = Pref.relayUrl.trim().removePrefix("ws://").removePrefix("wss://")
            .substringBefore('/').substringBefore(':')
        val port = Pref.relayUrl.trim().removePrefix("ws://").removePrefix("wss://")
            .substringBefore('/').substringAfter(':', "")
        // 查询参数带房间与 PIN：# 片段可能被扫码器丢弃，?参数 不会
        val url = "http://${if (port.isBlank()) host else "$host:$port"}/?room=$room&pin=$sessionPin"
        pushUi("relay-ready", url)
    }

    // ================= 协议：指令分发 =================

    fun onClientText(raw: String) {
        val text = unwrapText(raw) ?: return
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        val acc = RcAccessibilityService.instance
        val ime = RemoteIme.instance
        when (j.optString("t")) {
            "touch" -> {
                val a = j.optString("a")
                if (a == "down") wakeIfScreenOff()   // 主控点在黑屏上：先点亮屏幕再注入
                acc?.touch(
                    a, j.optInt("p"),
                    j.optDouble("x", 0.0).toFloat(), j.optDouble("y", 0.0).toFloat()
                )
                if (a == "up") checkImeSoon()
            }
            "scroll" -> acc?.scroll(j.optInt("dy"))
            "key" -> handleKey(j.optString("k"), j.optInt("n", 1), acc, ime)
            "text" -> {
                val s = j.optString("s")
                if (s.isEmpty()) return
                // 剪贴板由 App 侧写入（桥接进程的系统 Context 在部分 HyperOS 上创建失败，写不了剪贴板），
                // 再由桥接注入粘贴键在光标处插入：不重设整段文本，刚输入的字不会被冲掉
                val clipOk = try {
                    lastSelfClip = s; lastSelfClipAt = SystemClock.elapsedRealtime()
                    clipboard().setPrimaryClip(ClipData.newPlainText("随手控", s)); true
                } catch (_: Throwable) { false }
                val sh = shell
                val pasted = clipOk && ((sh != null && sh.alive && sh.key(279)) || acc?.paste() == true)
                if (!pasted) {
                    if (ime != null) ime.commit(s)
                    else broadcastText("""{"t":"toast","s":"请先在手机上点一下输入框"}""")
                }
            }
            "clip-set" -> {
                val s = j.optString("s")
                val ok = try {
                    lastSelfClip = s; lastSelfClipAt = SystemClock.elapsedRealtime()
                    clipboard().setPrimaryClip(ClipData.newPlainText("随手控", s)); true
                } catch (e: Throwable) { false }
                broadcastText(if (ok) """{"t":"clip-ok"}""" else """{"t":"toast","s":"写入手机剪贴板失败"}""")
            }
            "clip-get" -> {
                val sh = shell
                val r = if (sh != null && sh.alive) sh.getClip() else null
                var s = r?.text ?: ""
                if (s.isEmpty()) s = readLocalClip()
                if (s.isNotEmpty()) { lastPushedClip = s; lastPushedAt = SystemClock.elapsedRealtime() }
                val o = JSONObject().put("t", "clip").put("s", s)
                // peek：主控只是打开面板刷新记录，不要自动覆盖主控端剪贴板
                if (j.optBoolean("peek")) o.put("peek", true)
                if (s.isEmpty()) when {
                    sh == null || !sh.alive -> o.put("e", "Shizuku 桥接未运行")
                    r == null -> o.put("e", "桥接无响应（读取超时）")
                    !r.ok -> o.put("e", r.reason.ifEmpty { "读取失败" })
                    // r.ok 但内容为空：手机剪贴板确实为空，不提示失败
                }
                broadcastText(o.toString())
            }
            "file-up-meta" -> startUpload(j.optInt("id"), j.optString("name"), j.optLong("size"), j.optString("dest"))
            "file-up-end" -> finishUpload(j.optInt("id", -1))
            "file-dl-req" -> requestFilePick()
            "fs-list" -> handleFsList(j.optString("path"))
            "fs-get" -> handleFsGet(j.optString("path"))
            "open-fs-dir" -> openFsDir(j.optString("path"))
            "quality" -> applyQuality(j.optString("preset"))
            "editq" -> sendEditRects()
            // 主控迟迟收不到首帧时请求一个关键帧。只打标记，不重建编码器：
            // 重建会摘挂 VirtualDisplay 的 Surface，MIUI 上挂回后镜像不再刷新（画面卡在最后一帧）
            "kf" -> encoder?.requestKeyframe()
            "ready" -> onClientReady()
            "ping" -> broadcastText(
                JSONObject().put("t", "pong").put("ts", j.optDouble("ts")).toString()
            )
            "end" -> mainHandler.post { stopSession("主控端结束了会话") }
        }
    }

    private fun handleKey(k: String, times0: Int, acc: RcAccessibilityService?, ime: RemoteIme?) {
        val times = times0.coerceIn(1, 32)
        when (k) {
            "back" -> acc?.globalAction("back")
            "home" -> acc?.globalAction("home")
            "recents" -> acc?.globalAction("recents")
            "lock" -> {
                val ok = if (Build.VERSION.SDK_INT >= 28) acc?.globalAction("lock") else false
                if (ok != true) broadcastText("""{"t":"toast","s":"锁屏需要安卓 9+"}""")
            }
            "volup" -> acc?.volume(1)
            "voldown" -> acc?.volume(-1)
            "enter", "del", "tab", "esc", "up", "down", "left", "right" -> {
                val code = when (k) {
                    "enter" -> 66; "del" -> 67; "tab" -> 61; "esc" -> 111
                    "up" -> 19; "down" -> 20; "left" -> 21; "right" -> 22; else -> 0
                }
                val sh = shell
                // shell 注入真实 KeyEvent；失败时退回无障碍，避免按键静默失效
                val ok = sh != null && sh.alive && sh.key(code, times)
                if (!ok) {
                    if (ime != null) {
                        when (k) {
                            "enter" -> ime.keyEvent(66)
                            "del" -> ime.del(times)
                            "tab" -> ime.keyEvent(61)
                            "esc" -> ime.keyEvent(111)
                            "up" -> ime.keyEvent(19)
                            "down" -> ime.keyEvent(20)
                            "left" -> ime.keyEvent(21)
                            "right" -> ime.keyEvent(22)
                        }
                    } else repeat(times) { acc?.inputKeyFallback(k) }
                }
            }
        }
    }

    /** 若默认输入法是随手控的远控输入法（会让键盘区在投屏里变黑块），
     *  改回用户原来的输入法，并把远控输入法移出启用列表。 */
    private fun cleanupLegacyIme() {
        try {
            val ours = ImeUtil.imeId(this)
            val def = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
            val list = enabled.split(':').filter { it.isNotBlank() }
            if (def.equals(ours, ignoreCase = true)) {
                val alt = list.firstOrNull { !it.substringBefore(';').equals(ours, ignoreCase = true) }
                if (alt != null) {
                    val altId = alt.substringBefore(';')
                    Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, altId)
                }
            }
            if (list.any { it.substringBefore(';').equals(ours, ignoreCase = true) }) {
                val cleaned = list.filter { !it.substringBefore(';').equals(ours, ignoreCase = true) }
                Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS, cleaned.joinToString(":"))
            }
        } catch (_: Throwable) {
        }
    }

    fun onClientBinary(data: ByteArray) {
        if (data.isEmpty()) return
        if (data[0] == 0x02.toByte()) {
            val payload = data.copyOfRange(1, data.size)
            synchronized(upLock) {
                if (upStarting) { upPre.add(payload); return }
                val os = upOs ?: return
                writeUploadLocked(os, payload)
            }
        }
    }

    /** 调用方须持有 upLock */
    private fun writeUploadLocked(os: OutputStream, payload: ByteArray) {
        try {
            os.write(payload)
            upDone += payload.size
            if (upDone - upLastProg > 512 * 1024 || (upSize > 0 && upDone >= upSize)) {
                upLastProg = upDone
                broadcastText(
                    JSONObject().put("t", "file-progress").put("id", upId)
                        .put("done", upDone).toString()
                )
            }
        } catch (e: Exception) { log("写入文件失败：${e.message}") }
    }

    private fun clipboard(): ClipboardManager =
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

    /** 本进程直读剪贴板（需系统已授予后台读权限，见启动时 grantclip） */
    private fun readLocalClip(): String = try {
        clipboard().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    } catch (e: Throwable) { "" }

    /** 优先 shell 特权读（可靠），失败再退回本进程读 */
    private fun readClipText(): String {
        val sh = shell
        if (sh != null && sh.alive) {
            val r = try { sh.getClip() } catch (t: Throwable) { null }
            if (r != null && r.ok && r.text.isNotEmpty()) return r.text
        }
        return readLocalClip()
    }

    /** 剪贴板自动同步（手机→主控）：监听系统剪贴板变化，读到新内容就推给主控。
     *  注意安卓 10+ 限制后台读取剪贴板，后台时可能读不到（此时用主控「读取」按钮或手机前台复制）。 */
    private fun startClipWatch() {
        if (clipListener != null) return
        val cm = clipboard()
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            mainHandler.postDelayed({
                if (!isSessionActive) return@postDelayed
                // 读取放到后台线程：shell.getClip 会阻塞等待，绝不能卡主线程
                thread {
                    val s = try { readClipText() } catch (e: Throwable) { "" }
                    if (s.isEmpty()) return@thread
                    val now = SystemClock.elapsedRealtime()
                    if (s == lastSelfClip && now - lastSelfClipAt < 1500) return@thread
                    // 同内容回声：外部跨设备剪贴板把电脑剪贴板写回手机，不能再次推给电脑（否则成环）
                    if (s == lastPushedClip && now - lastPushedAt < CLIP_ECHO_WINDOW_MS) {
                        lastPushedAt = now
                        return@thread
                    }
                    lastPushedClip = s; lastPushedAt = now
                    broadcastText(JSONObject().put("t", "clip").put("s", s).put("auto", true).toString())
                }
            }, 150)
        }
        clipListener = l
        try { cm.addPrimaryClipChangedListener(l) } catch (_: Exception) {}
    }

    private fun stopClipWatch() {
        val l = clipListener ?: return
        clipListener = null
        try { clipboard().removePrimaryClipChangedListener(l) } catch (_: Exception) {}
    }

    fun broadcastText(text: String) {
        try {
            if (Pref.mode == "relay") relay?.sendPipe(text)
            else streamBroadcast(0x10, wrapText(text).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    // ================= 文件收发 =================

    private val pickNotiFallback = Runnable { showPickNotification() }

    /** 主控请求从手机下载文件：拉起透明 PickerActivity；被后台限制静默拦截时补发通知兜底 */
    private fun requestFilePick() {
        try {
            startActivity(Intent(this, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
        mainHandler.removeCallbacks(pickNotiFallback)
        mainHandler.postDelayed(pickNotiFallback, 1000)
        broadcastText("""{"t":"toast","s":"请在手机画面中选择文件；如未弹出，请点击通知栏「选择要发送的文件」"}""")
    }

    /** PickerActivity 成功起来后调用：取消兜底通知 */
    fun cancelPickFallback() {
        mainHandler.removeCallbacks(pickNotiFallback)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(PICK_NOTI_ID)
        } catch (_: Exception) {}
    }

    private fun showPickNotification() {
        try {
            val pi = PendingIntent.getActivity(
                this, 3,
                Intent(this, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, PICK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(getString(R.string.pick_title))
                .setContentText(getString(R.string.pick_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(PICK_NOTI_ID, n)
        } catch (e: Throwable) {
            log("发送文件选择通知失败：${e.message}")
        }
    }

    /** 主控→手机：保存到用户指定的接收目录（默认 下载/随手控），保留原文件名，完成后回报完整路径。 */
    private fun startUpload(id: Int, name: String, size: Long, dest: String) {
        val safeName = name.replace(Regex("[\\\\/]"), "_").ifBlank { "file" }
        // 接收位置白名单（借鉴 scrcpy --push-target 的「明确目的地」），避免任意路径写入
        val rel = when (dest) {
            "downloadroot" -> android.os.Environment.DIRECTORY_DOWNLOADS
            else -> android.os.Environment.DIRECTORY_DOWNLOADS + "/随手控"
        }
        synchronized(upLock) {
            closeUploadLocked()   // 上一份没收到结束帧的残留先关掉，避免数据写串到新文件
            upId = id; upSize = size; upDone = 0; upLastProg = 0; upName = safeName
            upPath = "$rel/$safeName"
            upStarting = true
        }
        thread {
            try {
                val os: OutputStream
                if (Build.VERSION.SDK_INT >= 29) {
                    // 不用 IS_PENDING：它会让 MediaStore 生成 .pending-<id>-<name> 临时文件名（用户看到的怪前缀）
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(safeName))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                    ) ?: throw IllegalStateException("MediaStore 插入失败")
                    os = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("打开文件失败")
                } else {
                    val dir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ), if (dest == "downloadroot") "" else "随手控"
                    )
                    dir.mkdirs()
                    os = FileOutputStream(File(dir, safeName))
                }
                var pendingFinish = -2
                synchronized(upLock) {
                    if (upId != id) {   // 已被更新的一次上传取代：丢弃本次
                        try { os.close() } catch (_: Exception) {}
                        return@thread
                    }
                    upOs = os
                    upStarting = false
                    for (b in upPre) writeUploadLocked(os, b)   // 补写建文件期间先到的块
                    upPre.clear()
                    if (upFinishPending) { pendingFinish = upFinishId; upFinishPending = false; upFinishId = -1 }
                }
                broadcastText("""{"t":"file-up-ack","id":$id}""")
                if (pendingFinish != -2) finishUpload(pendingFinish)
            } catch (e: Throwable) {
                synchronized(upLock) {
                    if (upId == id) {
                        try { upOs?.close() } catch (_: Exception) {}
                        clearUploadLocked()
                    }
                }
                log("接收文件失败：${e.message}")
                broadcastText(JSONObject().put("t", "file-end").put("id", id).put("ok", false).toString())
            }
        }
    }

    /** 主控发来 file-up-end。按 id 幂等：重复/重连后补发的结束帧重放上次结果，绝不动另一份正在接收的文件。 */
    private fun finishUpload(id0: Int) {
        thread {
            var msg: String? = null
            var savedName = ""
            synchronized(upLock) {
                if (upStarting) {
                    // 文件还在创建：先记下，等流就绪后由 startUpload 调用收尾
                    upFinishPending = true; upFinishId = id0
                    return@thread
                }
                val id = if (id0 >= 0) id0 else upId
                val os = upOs
                if (id != upId) {
                    msg = if (id >= 0 && id == lastUpId)
                        JSONObject().put("t", "file-end").put("id", id).put("ok", lastUpOk)
                            .put("name", lastUpName).put("path", lastUpPath).toString()
                    else
                        JSONObject().put("t", "file-end").put("id", id).put("ok", false)
                            .put("unknown", true).toString()
                } else if (os == null) {
                    msg = JSONObject().put("t", "file-end").put("id", id).put("ok", false)
                        .put("unknown", true).toString()
                } else {
                    val savedId = upId
                    savedName = upName
                    try { os.flush(); os.close() } catch (_: Exception) {}
                    lastUpId = savedId; lastUpOk = true; lastUpName = upName; lastUpPath = upPath
                    msg = JSONObject().put("t", "file-end").put("id", savedId).put("ok", true)
                        .put("path", upPath).put("name", upName).toString()
                    log("文件已保存：$upPath")
                    clearUploadLocked()
                }
            }
            msg?.let { broadcastText(it) }
            if (savedName.isNotEmpty()) pushUi("saved", savedName)
        }
    }

    /** 调用方须持有 upLock */
    private fun closeUploadLocked() {
        try { upOs?.flush() } catch (_: Exception) {}
        try { upOs?.close() } catch (_: Exception) {}
        clearUploadLocked()
    }

    /** 调用方须持有 upLock */
    private fun clearUploadLocked() {
        upOs = null; upId = -1; upSize = 0; upDone = 0; upLastProg = 0; upName = ""; upPath = ""
        upStarting = false; upPre.clear(); upFinishPending = false; upFinishId = -1
    }

    /** 专用 HTTP 上传通道：流式读请求体（[4 字节大端长度][AES-GCM 密文]…），解密直写接收目录。
     *  独立于 WS 上传状态机，不与触控指令/视频挤同一条队列。rel 为文件夹上传的相对子目录（逐段净化防穿越）。
     *  plain=true 表示主控关了「传输加密」：帧内是明文，不做解密（PIN 仍校验）。
     *  返回 JSON 结果字符串（由 HostServer 加密后回给主控）。调用方须在独立线程阻塞执行。 */
    fun receiveHttpUpload(name: String, dest: String, rel: String, total: Long, ins: java.io.InputStream,
                          plain: Boolean = false): String {
        val safeName = name.replace(Regex("[\\\\/]"), "_").ifBlank { "file" }
        val (relPath, savedPath) = uploadTarget(dest, rel, safeName)
        var os: OutputStream? = null
        var savedUri: Uri? = null      // 中断/取消时删掉半截文件，不留垃圾
        var savedFile: File? = null
        var completed = false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(safeName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("MediaStore 插入失败")
                savedUri = uri
                os = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("打开文件失败")
            } else {
                val f = File(android.os.Environment.getExternalStorageDirectory(), savedPath)
                f.parentFile?.mkdirs()
                savedFile = f
                os = FileOutputStream(f)
            }
            val din = java.io.DataInputStream(ins)
            val key = sessionKey
            var remaining = total
            while (remaining > 0) {
                if (remaining < 4) throw IllegalStateException("帧头不完整")
                val len = din.readInt().toLong() and 0xffffffffL
                remaining -= 4
                if (len <= 0 || len > remaining || len > 8 * 1024 * 1024) throw IllegalStateException("帧长度异常 $len")
                val enc = ByteArray(len.toInt())
                din.readFully(enc)
                remaining -= len
                val body = if (key != null && !plain) Crypt.decrypt(key, enc) else enc
                os.write(body)
            }
            os.flush(); os.close(); os = null
            completed = true
            log("文件已保存（专用通道）：$savedPath")
            pushUi("saved", safeName)
            JSONObject().put("ok", true).put("path", savedPath).put("name", safeName).toString()
        } catch (e: Throwable) {
            try { os?.close() } catch (_: Exception) {}
            if (!completed) {
                savedUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                savedFile?.let { try { it.delete() } catch (_: Exception) {} }
            }
            log("接收文件失败（专用通道）：${e.message}")
            JSONObject().put("ok", false).put("err", e.message ?: "写入失败").toString()
        }
    }

    /** 接收目录只允许「下载」或「下载/随手控」下的位置；rel 逐段净化，防止 ../ 穿越。 */
    private fun uploadTarget(dest: String, rel: String, name: String): Pair<String, String> {
        val baseRel = when (dest) {
            "downloadroot" -> android.os.Environment.DIRECTORY_DOWNLOADS
            else -> android.os.Environment.DIRECTORY_DOWNLOADS + "/随手控"
        }
        val sub = rel.split('/').map { it.replace(Regex("[\\\\:]"), "_").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
        val relPath = if (sub.isEmpty()) baseRel else "$baseRel/$sub"
        return relPath to "$relPath/$name"
    }

    private fun uploadTempDir(): File {
        val base = getExternalFilesDir(null) ?: cacheDir
        return File(base, "xfer").apply { mkdirs() }
    }

    /** 应用启动/结束时清理并行上传的临时文件：结束全删，启动只删一小时前的残留（可能还有别处在传） */
    private fun cleanupUploadParts(all: Boolean) {
        val now = System.currentTimeMillis()
        try {
            uploadTempDir().listFiles()?.forEach {
                if (all || now - it.lastModified() > 3600_000) it.delete()
            }
        } catch (_: Exception) {}
        if (all) uploadParts.clear()
    }

    /** 并行上传的一段：body 是 [4 字节长度][密文] 帧，按 off 写入临时文件；
     *  各段到齐后在最后一段的请求里统一落盘并返回保存路径，其余段只回 part ack。 */
    fun receiveHttpUploadPart(uid: String, name: String, dest: String, rel: String, size: Long,
                              off: Long, part: Int, parts: Int, total: Long,
                              ins: java.io.InputStream, plain: Boolean = false): String {
        fun err(m: String) = JSONObject().put("ok", false).put("err", m).toString()
        if (!Regex("^[A-Za-z0-9_-]{1,48}$").matches(uid)) return err("任务标识异常")
        if (size <= 0 || off < 0 || off >= size || part !in 0 until parts || parts !in 1..8 || total <= 0)
            return err("分片参数异常")
        val safeName = name.replace(Regex("[\\\\/]"), "_").ifBlank { "file" }
        val job = uploadParts.computeIfAbsent(uid) {
            UploadPart(File(uploadTempDir(), "$uid.part"), safeName, dest, rel, size, parts)
        }
        if (job.size != size || job.parts != parts || job.name != safeName) return err("分片信息不一致")
        synchronized(job) { if (job.finished) return job.result ?: err("已结束") }
        try {
            RandomAccessFile(job.temp, "rw").use { raf ->
                raf.seek(off)
                val din = java.io.DataInputStream(ins)
                val key = sessionKey
                var remaining = total
                var pos = off
                while (remaining > 0) {
                    if (remaining < 4) throw IllegalStateException("帧头不完整")
                    val len = din.readInt().toLong() and 0xffffffffL
                    remaining -= 4
                    if (len <= 0 || len > remaining || len > 8 * 1024 * 1024)
                        throw IllegalStateException("帧长度异常 $len")
                    val enc = ByteArray(len.toInt())
                    din.readFully(enc)
                    remaining -= len
                    val body = if (key != null && !plain) Crypt.decrypt(key, enc) else enc
                    if (pos + body.size > size) throw IllegalStateException("分片超出文件大小")
                    raf.write(body)
                    pos += body.size
                }
            }
        } catch (e: Throwable) {
            return err(e.message ?: "写入失败")
        }
        synchronized(job) {
            if (job.finished) return job.result ?: err("已结束")
            job.done[part] = true
            if (job.done.any { !it }) return JSONObject().put("ok", true).put("part", true).toString()
            job.finished = true
            val r = savePartUpload(job)
            job.result = r
            uploadParts.remove(uid)
            try { job.temp.delete() } catch (_: Exception) {}
            return r
        }
    }

    private fun savePartUpload(job: UploadPart): String {
        return try {
            if (job.temp.length() != job.size)
                throw IllegalStateException("文件不完整（${job.temp.length()}/${job.size}）")
            val (relPath, savedPath) = uploadTarget(job.dest, job.rel, job.name)
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, job.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(job.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("MediaStore 插入失败")
                try {
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("打开文件失败")
                    out.use { o -> job.temp.inputStream().use { it.copyTo(o, 256 * 1024) } }
                } catch (e: Throwable) {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    throw e
                }
            } else {
                val dir = File(android.os.Environment.getExternalStorageDirectory(), relPath)
                dir.mkdirs()
                job.temp.inputStream().use { i -> FileOutputStream(File(dir, job.name)).use { i.copyTo(it, 256 * 1024) } }
            }
            log("文件已保存：$savedPath")
            pushUi("saved", job.name)
            JSONObject().put("ok", true).put("path", savedPath).put("name", job.name).toString()
        } catch (e: Throwable) {
            log("接收文件失败：${e.message}")
            JSONObject().put("ok", false).put("err", e.message ?: "写入失败").toString()
        }
    }

    /** PickerActivity 的 SAF 选择器返回后调用 */
    fun startDownload(uri: Uri) {
        thread {
            val id = ++dlId
            try {
                val name = queryName(uri) ?: "file_${System.currentTimeMillis()}"
                val size = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (e: Exception) { -1L }
                broadcastText(
                    JSONObject().put("t", "file-meta").put("id", id)
                        .put("name", name).put("size", size).toString()
                )
                contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        val f = ByteArray(n + 1)
                        f[0] = 0x02
                        System.arraycopy(buf, 0, f, 1, n)
                        if (Pref.mode == "relay") relay?.broadcast(f)
                        else streamBroadcast(0x01, wrapFrame(f))
                    }
                }
                broadcastText(JSONObject().put("t", "file-end").put("id", id).put("ok", true).toString())
                log("文件已发送：$name")
            } catch (e: Throwable) {
                log("发送文件失败：${e.message}")
                broadcastText(JSONObject().put("t", "file-end").put("id", id).put("ok", false).toString())
            }
        }
    }

    // ================= 网页文件管理（手机→主控，借鉴小米妙享：电脑侧浏览手机存储） =================

    private fun fsRoot(): File = android.os.Environment.getExternalStorageDirectory()

    /** 是否拥有「所有文件访问」：Shizuku 桥接已 appops 授予 MANAGE_EXTERNAL_STORAGE（API30+），或旧版已授读权限 */
    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 把请求路径限制在 /sdcard 子树内，canonical 化防穿越；越界/非法返回 null */
    private fun resolveFs(path: String): File? = try {
        val rootCanon = fsRoot().canonicalPath
        val c = (if (path.isBlank()) fsRoot() else File(path)).canonicalFile
        if (c.canonicalPath == rootCanon || c.canonicalPath.startsWith(rootCanon + File.separator)) c else null
    } catch (e: Exception) { null }

    /** 专用下载通道：解析 /sdcard 内的单个文件（需「所有文件访问」），非法/越界/非文件返回 null */
    fun fileForDownload(path: String): File? {
        if (!hasAllFiles()) return null
        val f = resolveFs(path) ?: return null
        return if (f.isFile) f else null
    }

    /** 主控点「打开文件保存位置」：直接用系统文件管理打开该目录。
     *  第三方文件管理器（MT 等）不解析系统的目录 URI，只会回到自己上次的目录，所以不再让它们参与；
     *  系统文件管理（DocumentsUI / 小米文件管理）能按目录 URI 正确定位。 */
    fun openFsDir(path: String) {
        val f = resolveFs(path) ?: return
        val dir = if (f.isDirectory) f else f.parentFile ?: return
        try {
            val rel = dir.relativeToOrNull(fsRoot())?.invariantSeparatorsPath ?: ""
            val uri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel")
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = packageManager
            val sysPkg = listOf(
                "com.android.documentsui",           // AOSP / 原生
                "com.android.fileexplorer",          // 小米文件管理
                "com.google.android.documentsui",    // Google 文件
                "com.mi.android.globalFileexplorer"  // 小米国际版
            ).firstOrNull { pm.resolveActivity(Intent(view).setPackage(it), 0) != null }
            val launch = if (sysPkg != null) Intent(view).setPackage(sysPkg)
                else Intent.createChooser(view, getString(R.string.open_dir_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val mem = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(mem)
            if (mem.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                startActivity(launch)
                return
            }
            // 后台通过通知提供打开入口；是否自动展示全屏意图由系统决定。
            val pi = PendingIntent.getActivity(this, 4, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, PICK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(getString(R.string.open_dir_title))
                .setContentText(dir.name.ifBlank { "/" })
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(OPEN_NOTI_ID, n)
        } catch (e: Throwable) {
            log("打开文件位置失败：${e.message}")
        }
    }

    private fun handleFsList(path: String) {
        thread {
            if (!hasAllFiles()) {
                broadcastText(JSONObject().put("t", "fs-err").put("code", "noaccess")
                    .put("s", "浏览手机文件需要 Shizuku 运行（完整模式）；也可改用手机上选择文件").toString())
                return@thread
            }
            val dir = resolveFs(path)
            if (dir == null || !dir.isDirectory) {
                broadcastText(JSONObject().put("t", "fs-err").put("code", "notfound").put("s", "目录不存在或超出范围").toString())
                return@thread
            }
            val arr = JSONArray()
            val files = dir.listFiles() ?: emptyArray()
            files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).forEach { f ->
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("dir", f.isDirectory)
                    .put("size", if (f.isDirectory) 0L else f.length())
                    .put("mtime", f.lastModified()))
            }
            val parent = if (dir.canonicalPath == fsRoot().canonicalPath) "" else (dir.parentFile?.canonicalPath ?: "")
            broadcastText(JSONObject().put("t", "fs-list").put("path", dir.canonicalPath)
                .put("parent", parent).put("entries", arr).toString())
        }
    }

    private fun handleFsGet(path: String) {
        thread {
            if (!hasAllFiles()) {
                broadcastText(JSONObject().put("t", "fs-err").put("code", "noaccess").put("s", "需要 Shizuku 运行").toString())
                return@thread
            }
            val f = resolveFs(path)
            if (f == null || !f.isFile) {
                broadcastText(JSONObject().put("t", "fs-err").put("code", "notfound").put("s", "文件不存在或超出范围").toString())
                return@thread
            }
            startDownloadFile(f)
        }
    }

    /** 直接把手机本地文件推给主控（复用 file-meta/0x02 二进制/file-end 下载协议） */
    private fun startDownloadFile(f: File) {
        val id = ++dlId
        try {
            broadcastText(JSONObject().put("t", "file-meta").put("id", id)
                .put("name", f.name).put("size", f.length()).toString())
            FileInputStream(f).use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    val frame = ByteArray(n + 1)
                    frame[0] = 0x02
                    System.arraycopy(buf, 0, frame, 1, n)
                    if (Pref.mode == "relay") relay?.broadcast(frame)
                    else streamBroadcast(0x01, wrapFrame(frame))
                }
            }
            broadcastText(JSONObject().put("t", "file-end").put("id", id).put("ok", true).toString())
            log("文件已发送：${f.name}")
        } catch (e: Throwable) {
            log("发送文件失败：${e.message}")
            broadcastText(JSONObject().put("t", "file-end").put("id", id).put("ok", false).toString())
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    // ================= 生命周期 =================

    fun log(s: String) { pushUi("log", s) }

    private fun notifyUpdate() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_ID, buildNotif("PIN $sessionPin · 等待主控连接"))
        } catch (_: Exception) {}
    }

    private fun pushUi(type: String, data: String) {
        mainHandler.post { uiCb?.invoke(type, data) }
    }

    fun stopSession(reason: String) {
        if (!isSessionActive) return
        isSessionActive = false
        instance = null
        try { RcAccessibilityService.instance?.resetTouches() } catch (_: Throwable) {}
        cancelGoneGrace()
        mainHandler.removeCallbacks(imeProbe)
        mainHandler.removeCallbacks(imePoll)
        imeOn = false
        try { broadcastText(JSONObject().put("t", "bye").put("reason", reason).toString()) } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try { relay?.close() } catch (_: Exception) {}
        relay = null
        try { encoder?.shutdown() } catch (_: Exception) {}
        encoder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
        for (s in streamClients) { try { s.close() } catch (_: Exception) {} }
        streamClients.clear()
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(dispListener)
        if (Pref.autoCloseAcc) {
            try { AccessUtil.disable(this) } catch (_: Exception) {}
        }
        stopClipWatch()
        synchronized(upLock) { closeUploadLocked() }   // 会话结束：关掉未收尾的上传，避免文件句柄泄漏
        cleanupUploadParts(all = true)
        try { shell?.stop() } catch (_: Exception) {}
        shell = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mainHandler.removeCallbacks(pickNotiFallback)
        mainHandler.removeCallbacks(screenWatch)
        lastScreenOn = null
        nm.cancel(PICK_NOTI_ID)
        nm.cancel(NOTI_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        pushUi("stopped", reason)
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager ?: return 0 to 0
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    // ================= 通知 =================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
            val pch = NotificationChannel(PICK_CHANNEL_ID, getString(R.string.pick_channel),
                NotificationManager.IMPORTANCE_HIGH)
            pch.setShowBadge(false)
            nm.createNotificationChannel(pch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_fg),
                    getString(R.string.notif_stop), stopPi
                ).build()
            )
            .build()
    }
}
