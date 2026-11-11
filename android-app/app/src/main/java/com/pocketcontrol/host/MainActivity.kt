package com.pocketcontrol.host

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.pocketcontrol.host.service.SessionService
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQ = 7001
        private val QUALITIES = arrayOf("low", "mid", "high", "orig")
    }

    private lateinit var btnShizuku: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCopy: Button
    private lateinit var tvShizuku: TextView
    private lateinit var dotShizuku: android.view.View
    private lateinit var boxSession: LinearLayout
    private lateinit var tvUrls: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvLog: TextView
    private lateinit var imgQr: ImageView
    private lateinit var spQuality: Spinner
    private lateinit var etPort: EditText
    private lateinit var rgMode: RadioGroup
    private lateinit var etRelay: EditText
    private lateinit var swAutoAcc: CheckBox
    private lateinit var etPin: EditText
    private lateinit var btnNewPin: Button
    private lateinit var btnCopyLog: Button

    private var sessionUrls: List<String> = emptyList()

    /** 主控切换画质后，同步手机上的画质下拉框（以被控端设置为准） */
    private val qualityReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val q = i?.getStringExtra("quality") ?: return
            val pos = QUALITIES.indexOf(q)
            if (pos >= 0 && pos != spQuality.selectedItemPosition) spQuality.setSelection(pos)
        }
    }

    private val reqListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ) {
                runOnUiThread {
                    refreshShizuku()
                    if (grantResult == PackageManager.PERMISSION_GRANTED) grantNow(true)
                    else toast("未获得 Shizuku 授权")
                }
            }
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                appendLog("已获得投屏授权")
                // 同一进程内直接交给 Service，避免经 Intent 二次打包丢失录屏令牌（部分 MIUI 机型）
                SessionService.pendingProjCode = res.resultCode
                SessionService.pendingProjData = data
                val i = Intent(this, SessionService::class.java)
                    .putExtra("code", res.resultCode)
                    .putExtra("data", data)
                try {
                    ContextCompat.startForegroundService(this, i)
                } catch (t: Throwable) {
                    appendLog("启动会话服务失败：${t.message}")
                }
            } else if (res.resultCode == Activity.RESULT_OK) {
                appendLog("投屏授权未完成，请再点一次「开始会话」")
            } else {
                appendLog("未获得投屏授权")
                toast("没有获得投屏授权，无法开始会话")
            }
        }

    private var startAfterNotif = false
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (startAfterNotif) {
                startAfterNotif = false
                launchProjection()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pref.init(this)
        setContentView(R.layout.activity_main)

        btnShizuku = findViewById(R.id.btnShizuku)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCopy = findViewById(R.id.btnCopy)
        tvShizuku = findViewById(R.id.tvShizuku)
        dotShizuku = findViewById(R.id.dotShizuku)
        boxSession = findViewById(R.id.boxSession)
        tvUrls = findViewById(R.id.tvUrls)
        tvPin = findViewById(R.id.tvPin)
        tvLog = findViewById(R.id.tvLog)
        imgQr = findViewById(R.id.imgQr)
        spQuality = findViewById(R.id.spQuality)
        etPort = findViewById(R.id.etPort)
        rgMode = findViewById(R.id.rgMode)
        etRelay = findViewById(R.id.etRelay)
        swAutoAcc = findViewById(R.id.swAutoAcc)
        etPin = findViewById(R.id.etPin)
        btnNewPin = findViewById(R.id.btnNewPin)
        btnCopyLog = findViewById(R.id.btnCopyLog)

        tvLog.movementMethod = ScrollingMovementMethod()

        // ---- 设置初始化 ----
        spQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("流畅 480p", "均衡 720p", "高清 1080p", "原画（最高）")
        )
        spQuality.setSelection(QUALITIES.indexOf(Pref.quality).coerceAtLeast(0))
        spQuality.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val q = QUALITIES[pos]
                val sess = SessionService.instance
                // 会话中修改设置要立即生效并同步给主控；否则只保存，下次会话使用
                if (sess != null && sess.isSessionActive) sess.applyQuality(q) else Pref.quality = q
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        etPort.setText(Pref.port.toString())
        etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) etPort.text.toString().toIntOrNull()?.let {
                if (it in 1024..65535) Pref.port = it
            }
        }

        if (Pref.mode == "relay") rgMode.check(R.id.rbRelay) else rgMode.check(R.id.rbDirect)
        etRelay.isEnabled = Pref.mode == "relay"
        rgMode.setOnCheckedChangeListener { _, id ->
            Pref.mode = if (id == R.id.rbRelay) "relay" else "direct"
            etRelay.isEnabled = Pref.mode == "relay"
        }
        etRelay.setText(Pref.relayUrl)
        etRelay.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Pref.relayUrl = etRelay.text.toString().trim()
        }
        swAutoAcc.isChecked = Pref.autoCloseAcc
        swAutoAcc.setOnCheckedChangeListener { _, c -> Pref.autoCloseAcc = c }
        etPin.setText(Pref.pin)
        etPin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitPin()
        }
        btnNewPin.setOnClickListener {
            Pref.regenPin()
            etPin.setText(Pref.pin)
            toast("PIN 已更新，下次会话生效：${Pref.pin}")
        }
        btnCopyLog.setOnClickListener {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("随手控日志", tvLog.text))
            toast("运行日志已复制到剪贴板")
        }

        // ---- 按钮 ----
        btnShizuku.setOnClickListener { onShizuku() }
        btnStart.setOnClickListener { onStartSession() }
        btnStop.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        }
        btnCopy.setOnClickListener {
            val text = sessionUrls.joinToString("\n") + "\nPIN: " + (SessionService.instance?.sessionPin ?: "")
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("随手控", text))
            toast("连接地址与 PIN 已复制")
        }

        SessionService.uiCb = { type, data -> runOnUiThread { onUi(type, data) } }

        // ---- Shizuku ----
        Shizuku.addBinderReceivedListenerSticky { runOnUiThread { refreshShizuku() } }
        Shizuku.addRequestPermissionResultListener(reqListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refreshShizuku()
    }

    override fun onStart() {
        super.onStart()
        try { registerReceiver(qualityReceiver, IntentFilter(SessionService.ACTION_QUALITY)) } catch (_: Throwable) {}
    }

    override fun onStop() {
        try { unregisterReceiver(qualityReceiver) } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(reqListener)
        if (isFinishing) SessionService.uiCb = null
        super.onDestroy()
    }

    // ================= Shizuku 授权 =================

    private fun onShizuku() {
        if (AccessUtil.canWrite(this)) {
            toast("已授权，无需重复操作")
            return
        }
        val shizukuOk = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!shizukuOk) {
            toast("Shizuku 未运行，请打开 Shizuku 并启动服务（无线调试）")
            try {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.let { startActivity(it) }
            } catch (_: Exception) {}
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            grantNow(true)
        } else {
            try { Shizuku.requestPermission(SHIZUKU_REQ) } catch (e: Throwable) { toast("无法发起授权请求：${e.message}") }
        }
    }

    /**
     * 通过 Shizuku 执行授权，并回读系统真实状态验证：
     * 1) pm grant WRITE_SECURE_SETTINGS
     * 2) appops 放开「受限设置」（安卓13+ 从聊天工具侧载的 App 会被拦无障碍）
     * 3) 轮询 checkSelfPermission 确认真正生效，并收集系统报错原文用于提示
     */
    private fun grantNow(manual: Boolean) {
        thread {
            var exitOk = false
            var granted = false
            var detail = ""
            try {
                val outer = Shizuku.getBinder()
                    ?: throw IllegalStateException("Shizuku Binder 不可用")
                val svc = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(outer))
                val proc = svc.newProcess(
                    arrayOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS"),
                    null, null
                )
                exitOk = proc.waitFor() == 0
                detail = readAll(proc.errorStream) + readAll(proc.inputStream)

                try {
                    val p2 = svc.newProcess(
                        arrayOf("appops", "set", packageName, "ACCESS_RESTRICTED_SETTINGS", "allow"),
                        null, null
                    )
                    p2.waitFor()
                } catch (_: Throwable) {}

                var tries = 0
                granted = AccessUtil.canWrite(this)
                while (!granted && tries < 2) {
                    Thread.sleep(300)
                    // 显式指定用户再授一次，兜底多用户场景
                    try {
                        val p4 = svc.newProcess(
                            arrayOf("pm", "grant", "--user", "0", packageName, "android.permission.WRITE_SECURE_SETTINGS"),
                            null, null
                        )
                        p4.waitFor()
                    } catch (_: Throwable) {}
                    granted = AccessUtil.canWrite(this)
                    tries++
                }
            } catch (t: Throwable) {
                detail = (t.message ?: t.toString()) + " / " + detail
            }
            runOnUiThread {
                refreshShizuku()
                if (granted) {
                    toast(if (manual) "授权成功并已生效，Shizuku 可以关闭"
                    else "权限已修复，请重新点击「开始会话」")
                } else {
                    appendLog("授权未生效${if (detail.isNotBlank()) "：${detail.take(120)}" else ""}")
                    toast(if (exitOk) "授权尚未生效，请完全退出并重新打开随手控后重试"
                    else "授权失败：${detail.take(120)}")
                }
            }
        }
    }

    private fun readAll(pfd: android.os.ParcelFileDescriptor?): String = try {
        if (pfd == null) ""
        else android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText().trim() }
    } catch (t: Throwable) {
        ""
    }

    private fun refreshShizuku() {
        val hasWs = AccessUtil.canWrite(this)
        val shizukuOk = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        val dot = when {
            hasWs -> R.color.ok_text
            shizukuOk -> R.color.status_warn
            else -> R.color.status_idle
        }
        dotShizuku.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dot))
        tvShizuku.text = when {
            hasWs -> "已授权"
            shizukuOk -> "待授权"
            else -> "未运行"
        }
        tvShizuku.setTextColor(ContextCompat.getColor(this, if (hasWs) R.color.ok_text else R.color.text_secondary))
        btnShizuku.visibility = if (hasWs) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ================= 开始 / 停止会话 =================

    /** 触摸模式下点按钮不会让输入框失焦，故「开始会话」前必须显式提交 PIN，否则自定义 PIN 不生效 */
    private fun commitPin(): Boolean {
        val v = etPin.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(v)) {
            etPin.setText(Pref.pin)
            toast("PIN 必须为 6 位数字，已恢复为原值")
            return false
        }
        if (v != Pref.pin) {
            Pref.pin = v
            toast("配对 PIN 已保存，将在本次会话生效")
        }
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun onStartSession() {
        if (!AccessUtil.canWrite(this)) {
            val shizukuOk = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
            if (shizukuOk) {
                toast("授权尚未生效，正在自动修复…")
                grantNow(false)
            } else {
                toast("授权尚未生效，请启动 Shizuku，或完全退出应用后重新尝试")
            }
            return
        }
        if (Pref.mode == "relay" && Pref.relayUrl.isBlank()) {
            toast("中继模式需要先填写中继服务器地址")
            return
        }
        if (!commitPin()) return
        // 通知权限弹窗和投屏授权弹窗不能同时拉起：MIUI 上后者会被顶掉/直接返回取消。
        // 先等通知权限结果，再申请投屏。
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startAfterNotif = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // 安卓 14+ 显式指定「整块屏幕」，避免系统弹窗里被切成单应用投屏（部分定制系统会记住该选择）
            val req = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            else mpm.createScreenCaptureIntent()
            projLauncher.launch(req)
        } catch (t: Throwable) {
            appendLog("无法打开投屏授权弹窗：${t.message}")
            toast("无法打开投屏授权弹窗，请检查系统的权限限制")
        }
    }

    // ================= UI 回调 =================

    @SuppressLint("SetTextI18n")
    private fun onUi(type: String, data: String) {
        when (type) {
            "log" -> appendLog(data)
            "vid" -> appendLog("画面分辨率：$data")
            "starting" -> appendLog(data)
            "direct-ready" -> {
                boxSession.visibility = android.view.View.VISIBLE
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                fillDirectUrls()
            }
            "relay-ready" -> {
                boxSession.visibility = android.view.View.VISIBLE
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                val pin = SessionService.instance?.sessionPin ?: "------"
                tvUrls.text = "主控浏览器打开：\n$data"
                tvPin.text = "PIN：$pin"
                makeQr(data)
            }
            // 连接/断开已由 SessionService 记日志（带 2 秒去抖），这里不再重复追加
            "client", "saved" -> {}
            "stopped" -> {
                boxSession.visibility = android.view.View.GONE
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                appendLog("会话已结束：$data")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun fillDirectUrls() {
        val port = SessionService.instance?.actualPort?.takeIf { it > 0 } ?: Pref.port
        val ips = ArrayList<Pair<Int, String>>()
        try {
            val e = java.net.NetworkInterface.getNetworkInterfaces()
            while (e.hasMoreElements()) {
                val ni = e.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val ee = ni.inetAddresses
                while (ee.hasMoreElements()) {
                    val a = ee.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val h = a.hostAddress ?: continue
                        val score = when {
                            h.startsWith("100.") -> 0        // 虚拟局域网 / CGNAT 网卡（Tailscale、EasyTier 等）
                            h.startsWith("192.168.") -> 1    // 家用 Wi-Fi
                            h.startsWith("10.") -> 2
                            h.startsWith("172.") -> 3
                            else -> 4
                        }
                        ips.add(score to h)
                    }
                }
            }
        } catch (_: Exception) {}
        val pin = SessionService.instance?.sessionPin ?: "------"
        sessionUrls = ips.sortedBy { it.first }.map { "http://${it.second}:$port" }
        tvUrls.text = if (sessionUrls.isEmpty()) "未发现可用网络地址（请确认 Wi-Fi 或虚拟局域网已连接）"
        else sessionUrls.joinToString("\n") { u -> "$u" }
        tvPin.text = "PIN：$pin"
        // 二维码用查询参数带 PIN：有的扫码器会丢掉 # 片段，?pin= 能保留，扫完直接连上
        sessionUrls.firstOrNull()?.let { makeQr("$it/?pin=$pin") }
    }

    private fun makeQr(content: String) {
        try {
            val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600)
            val bmp = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until bits.width) {
                for (y in 0 until bits.height) {
                    bmp.setPixel(x, y, if (bits.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            imgQr.setImageBitmap(bmp)
        } catch (e: Exception) {
            appendLog("二维码生成失败：${e.message}")
        }
    }

    private val logClock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    private fun appendLog(s: String) {
        val line = logClock.format(java.util.Date()) + "  " + s
        val cur = tvLog.text.toString()
        val lines = (if (cur.isBlank()) line else cur + "\n" + line).split('\n')
        tvLog.text = if (lines.size > 120) lines.takeLast(120).joinToString("\n") else lines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
