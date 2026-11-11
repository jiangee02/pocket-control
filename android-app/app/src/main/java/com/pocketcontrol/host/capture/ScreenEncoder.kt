package com.pocketcontrol.host.capture

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.pocketcontrol.host.service.SessionService

/**
 * 屏幕采集 + H.264 硬编码：
 * MediaProjection → VirtualDisplay → MediaCodec(Surface 输入) → Annex-B 码流回调
 * 支持画质预设（480p/720p/1080p/原画）切换与旋转时重启。
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val svc: SessionService,
    @Volatile var preset: String,
    private val onVideo: (ByteArray, Long) -> Unit,   // ptsUs；-1 表示 SPS/PPS 配置包
    private val onConfig: (Int, Int) -> Unit,
    private val onError: (String) -> Unit
) : Thread("pc-encoder") {

    @Volatile private var running = true
    @Volatile private var restartReq = false
    @Volatile private var keyReq = false
    @Volatile private var paused = false
    // 文件传输期间临时压低码率给传输让路（分辨率不变），传输结束恢复
    @Volatile private var transferMode = false

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var vd: VirtualDisplay? = null
    private var cfgNalu: ByteArray? = null
    private val info = MediaCodec.BufferInfo()
    private var lagSum = 0L
    private var lagN = 0
    private var fpsCount = 0
    private var fpsAt = System.nanoTime()
    private var resumeAtMs = 0L
    private var lastBacklogFlush = 0L

    /** 采集帧进入编码器 → 输出码流的平均耗时（ms）。偏大说明编码器内部在排队。 */
    @Volatile var encodeLagMs = 0

    /** 实际出帧率（每秒统计一次）：远低于目标帧率说明编码器喂不动，只能降档 */
    @Volatile var outputFps = 0

    /** 当前画质的目标帧率 */
    val targetFps: Int get() = params(preset).second

    companion object {
        // MediaCodecInfo.CodecCapabilities.AVCProfileBaseline 恒为 1；本机精简 android.jar 缺该常量
        private const val AVC_PROFILE_BASELINE = 1
        // 传输期码率上限：介于流畅(3M)与均衡(5M)之间，画面仍可操控，带宽大头让给文件
        private const val TRANSFER_BITRATE = 4_000_000
    }

    private val dpi by lazy { svc.resources.displayMetrics.densityDpi }

    private fun realSize(): Size {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Size(1080, 2400)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Size(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            Size(p.x, p.y)
        }
    }

    private fun params(p: String): Pair<Int, Int> = when (p) {
        // 码率按分辨率给足余量，否则滚动/动画画面发糊：流畅 480p→3M，均衡 720p→5M，
        // 高清 1080p→10M，原画 1440p→16M
        "low" -> 3_000_000 to 24
        "high" -> 10_000_000 to 30
        "orig" -> 16_000_000 to 30
        else -> 5_000_000 to 30
    }

    private fun scale(real: Size, p: String): Pair<Int, Int> {
        if (p == "orig") return even(real.width) to even(real.height)
        // 按短边定分辨率（竖屏时短边=宽），避免按长边缩放把宽度压到 216/324px 导致文字发糊；不放大超过原生。
        val target = when (p) { "low" -> 480; "high" -> 1080; else -> 720 }
        val short = minOf(real.width, real.height).toFloat()
        val s = (target / short).coerceAtMost(1f)
        fun sc(v: Int) = even((v * s).toInt())
        return sc(real.width).coerceAtLeast(160) to sc(real.height).coerceAtLeast(160)
    }

    private fun even(v: Int): Int {
        val x = if (v < 2) 2 else v
        return if (x % 2 == 0) x else x - 1
    }

    override fun run() {
        try { loop() } catch (t: Throwable) { onError(t.message ?: t.toString()) }
    }

    private fun loop() {
        while (running) {
            val (w, h) = scale(realSize(), preset)
            try {
                startEncoder(w, h)
            } catch (t: Throwable) {
                onError("编码器启动失败: ${t.message}")
                return
            }
            while (running && !restartReq) {
                if (paused) {
                    try { Thread.sleep(60) } catch (_: Exception) {}
                    continue
                }
                resumeInputIfDue()
                dropBacklogIfHopeless()
                if (keyReq) {
                    keyReq = false
                    try {
                        codec?.setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    } catch (_: Exception) {}
                }
                val c = codec ?: break
                val idx = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: Exception) { break }
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val b = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(b)
                        val isCfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (isCfg) {
                            cfgNalu = b
                        } else {
                            if (info.presentationTimeUs > 0) {
                                // Surface 时间戳与 nanoTime 同一时钟，差值即编码器的排队/出帧耗时
                                val lag = (System.nanoTime() / 1_000_000L - info.presentationTimeUs / 1000L).toInt()
                                if (lag in 0..1000) {
                                    lagSum += lag; lagN++
                                    if (lagN >= 30) { encodeLagMs = (lagSum / lagN).toInt(); lagSum = 0; lagN = 0 }
                                }
                            }
                            fpsCount++
                        }
                        try { onVideo(b, if (isCfg) -1L else info.presentationTimeUs) } catch (_: Exception) {}
                    }
                    c.releaseOutputBuffer(idx, false)
                }
                val now = System.nanoTime()
                if (now - fpsAt >= 1_000_000_000L) {
                    outputFps = (fpsCount * 1_000_000_000L / (now - fpsAt)).toInt()
                    fpsCount = 0
                    fpsAt = now
                }
            }
            stopEncoder()
            if (!restartReq || !running) break
            restartReq = false
        }
    }

    /**
     * 编码器内部排了几百毫秒的帧时，画面已经是「过去的录播」：
     * 逐帧追是追不回来的（输入还在按屏幕刷新率涌进来）。只能掐断输入一小段，
     * 让队列排空后再接上——主控看到的是短暂顿一下，随后立刻回到实时。
     * 支持 PARAMETER_KEY_SUSPEND 的编码器才有效，否则静默跳过（降档兜底）。
     */
    private fun dropBacklogIfHopeless() {
        if (encodeLagMs < 250 || resumeAtMs != 0L) return
        val now = System.nanoTime() / 1_000_000L
        if (now - lastBacklogFlush < 6000) return
        val c = codec ?: return
        try {
            c.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1) })
            lastBacklogFlush = now
            // 排空时间约等于当前积压；留 80ms 余量，最多停 600ms 免得看起来像卡死
            resumeAtMs = now + (encodeLagMs - 80).coerceIn(80, 600)
        } catch (_: Exception) {}
    }

    private fun resumeInputIfDue() {
        if (resumeAtMs == 0L) return
        if (System.nanoTime() / 1_000_000L < resumeAtMs) return
        resumeAtMs = 0L
        try {
            codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0) })
            keyReq = true   // 断开期间丢了参考帧，恢复后立刻给主控一个关键帧
        } catch (_: Exception) {}
    }

    private fun startEncoder(w: Int, h: Int) {
        try {
            startEncoderWith(w, h, tuned = true)
        } catch (t: Throwable) {
            stopEncoder()
            onError("编码参数不兼容，已切换兼容配置")
            startEncoderWith(w, h, tuned = false)
        }
    }

    private fun startEncoderWith(w: Int, h: Int, tuned: Boolean) {
        val (bitrate, fps) = params(preset)
        val startBitrate = if (transferMode) minOf(bitrate, TRANSFER_BITRATE) else bitrate
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, startBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 关键帧每秒一个：主控追帧要 seek 到缓冲末端，间隔太长时得从更早的关键帧解起
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // 浏览器 MSE 对 Baseline 兼容性最好（无 B 帧），回退时也保留
            setInteger(MediaFormat.KEY_PROFILE, AVC_PROFILE_BASELINE)
            if (tuned) {
                // LATENCY=1 减少编码器内部排队；PRIORITY=0 实时优先
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // 告诉编码器「我只按这个帧率消费」：屏幕是 120Hz 时 VirtualDisplay 仍按刷新率
                // 送帧，支持 operating-rate 的编码器会主动丢帧而不是越排越深（澎湃上实测有效，不支持则忽略）
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                // 显式变码率：静止画面几乎不占带宽（默认多为 CBR，静止也按满码率发，
                // 会一直挤占 Wi-Fi 导致文件传输掉速后不恢复）。个别机型不支持时随回退去掉。
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = c.createInputSurface()   // 必须在 Configured 状态创建、start 之前
        c.start()
        surface = input
        // 安卓 14+ 一个 MediaProjection 只允许创建一次 VirtualDisplay：
        // 首次创建后一直复用，切画质/旋转只换 Surface + 改尺寸，绝不 release 重建
        val display = vd
        if (display == null) {
            vd = projection.createVirtualDisplay(
                "pc-cast", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                input, null, null
            )
        } else {
            display.setSurface(input)
            display.resize(w, h, dpi)
        }
        if (paused) { try { vd?.setSurface(null) } catch (_: Exception) {} }
        cfgNalu = null
        onConfig(w, h)
    }

    private fun stopEncoder() {
        try { vd?.setSurface(null) } catch (_: Exception) {}
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    fun cachedConfig(): ByteArray? = cfgNalu

    fun requestKeyframe() { keyReq = true }

    /** 文件传输开始/结束：不重启编码器，动态把码率压到 4Mbps / 恢复原档。
     *  个别机型不支持运行时改码率时静默忽略（传输仍可用，只是不让路）。 */
    fun setTransferMode(on: Boolean) {
        if (transferMode == on) return
        transferMode = on
        val c = codec ?: return
        val (bitrate, _) = params(preset)
        val target = if (on) minOf(bitrate, TRANSFER_BITRATE) else bitrate
        try {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target)
            })
        } catch (_: Exception) {}
    }

    /** 无主控观看时暂停编码省电：摘掉 VirtualDisplay 的 Surface（不 release，安卓14+ 允许复用），
     *  恢复时重新挂回并请求关键帧。 */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        try {
            if (p) vd?.setSurface(null)
            else { vd?.setSurface(surface); keyReq = true }
        } catch (_: Exception) {}
    }

    fun requestRestart(newPreset: String? = null) {
        if (newPreset != null) preset = newPreset
        restartReq = true
    }

    fun shutdown() {
        running = false
        restartReq = false
        try { interrupt() } catch (_: Exception) {}
        try { join(2500) } catch (_: Exception) {}
        stopEncoder()
        try { vd?.release() } catch (_: Exception) {}
        vd = null
    }
}
