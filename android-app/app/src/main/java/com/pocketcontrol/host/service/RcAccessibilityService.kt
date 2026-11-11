package com.pocketcontrol.host.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍服务：远控会话期间接收主控端指令，注入点击 / 滑动 / 多点触控 / 全局动作。
 * 通过 WRITE_SECURE_SETTINGS 由 App 自动启用，无需手动到设置里打开。
 *
 * 触控模型：
 *  - 手指按下：新建一条持续笔画（willContinue=true）
 *  - 移动：以约 60Hz 的节奏把笔画"续写"到新坐标
 *  - 抬起：续写最后一小段并将 willContinue 置 false，笔画结束
 *  - 多指：同一手势里同时携带多条笔画，支持双指捏合 / 拖拽
 */
class RcAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RcAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val strokes = LinkedHashMap<Int, GestureDescription.StrokeDescription>()
    private val sent = HashMap<Int, PointF>()
    private val target = HashMap<Int, PointF>()
    private val finishing = HashSet<Int>()
    private data class TouchCommand(val action: String, val pointer: Int, val point: PointF)
    private val touchQueue = java.util.ArrayDeque<TouchCommand>()
    private var touchBusy = false
    private var releaseRequested = false
    private var genCounter = 0
    private var scrollBusy = false
    private var pendingScroll = 0

    private fun callbackFor(gen: Int) = object : GestureResultCallback() {
        override fun onCompleted(gesture: GestureDescription?) {
            if (gen != genCounter) return
            for (p in finishing) {
                strokes.remove(p)
                sent.remove(p)
                target.remove(p)
            }
            finishing.clear()
            touchBusy = false
            drainTouches()
        }
        override fun onCancelled(gesture: GestureDescription?) {
            if (gen != genCounter) return
            cancelTouches()
        }
    }

    private val scrollCb = object : GestureResultCallback() {
        override fun onCompleted(gesture: GestureDescription?) { onScrollDone() }
        override fun onCancelled(gesture: GestureDescription?) { onScrollDone() }
    }

    private val am by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 输入法是独立窗口：窗口增删/切换都会触发，稍等一拍再读窗口列表拿最终状态
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(imeNotify)
                handler.postDelayed(imeNotify, 80)
            }
        }
    }
    override fun onInterrupt() {}

    private val imeNotify = Runnable { SessionService.instance?.onImeMaybeChanged() }

    /** 屏幕底部是否挂着系统输入法窗口（供主控决定是否跟随弹出自己的键盘）。
     *  输入法窗口隐藏时高度为 0，据此过滤掉部分输入法保留的空窗口。 */
    fun imeShown(): Boolean {
        return try {
            val ws = windows ?: return false
            val r = android.graphics.Rect()
            for (w in ws) {
                if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                w.getBoundsInScreen(r)
                if (r.height() > 0) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun dispSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    /** 全局动作：back / home / recents / lock */
    fun globalAction(k: String): Boolean {
        val a = when (k) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(a)
    }

    fun volume(delta: Int) {
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ================= 文字/按键兜底（输入法未绑定时） =================

    private fun focusedInput(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        // 部分应用/WebView 的焦点节点 findFocus 拿不到，遍历活动窗口找「已聚焦且可编辑」的节点兜底
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isFocused && (n.isEditable || n.className?.toString()?.contains("EditText") == true)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** 当前窗口里可编辑控件的位置（归一化到屏幕 0..1），供主控判断这一下点击是不是落在输入框上。
     *  主控网页据此只在点中输入框时唤起本机键盘，点其它地方不再弹键盘。 */
    fun editableRects(): List<FloatArray> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (w <= 0 || h <= 0) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<FloatArray>()
        val rect = android.graphics.Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && out.size < 24 && guard++ < 400) {
            val n = queue.removeFirst()
            if (n.isEditable || n.className?.toString()?.contains("EditText") == true) {
                n.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    out.add(
                        floatArrayOf(
                            rect.left.toFloat() / w, rect.top.toFloat() / h,
                            rect.right.toFloat() / w, rect.bottom.toFloat() / h
                        )
                    )
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** 在光标处粘贴调用方已写好的剪贴板内容。
     *  只粘贴、不改写整段文本，避免覆盖用户刚输入的字；粘贴不可用时返回失败，不做整段覆盖。 */
    fun paste(): Boolean {
        val node = focusedInput() ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Throwable) {
            false
        }
    }

    /** 无输入法时的按键兜底：删除/回车/方向键，基于焦点节点的文本与光标操作 */
    fun inputKeyFallback(k: String): Boolean {
        val node = focusedInput() ?: return false
        return try {
            when (k) {
                "del" -> {
                    val text = node.text?.toString() ?: ""
                    val lo = minOf(node.textSelectionStart, node.textSelectionEnd).coerceAtLeast(0)
                    val hi = maxOf(node.textSelectionStart, node.textSelectionEnd).coerceAtLeast(0)
                    val (newText, cursor) = when {
                        lo != hi -> text.removeRange(lo, hi.coerceAtMost(text.length)) to lo
                        lo > 0 -> text.removeRange(lo - 1, lo) to (lo - 1)
                        else -> return false
                    }
                    setText(node, newText, cursor)
                }
                "enter" -> {
                    val text = node.text?.toString() ?: ""
                    val pos = node.textSelectionEnd.coerceIn(0, text.length)
                    setText(node, text.substring(0, pos) + "\n" + text.substring(pos), pos + 1)
                }
                "left", "right", "up", "down" -> {
                    val gran = if (k == "up" || k == "down")
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                    else AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                    val action = if (k == "left" || k == "up")
                        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                    else AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, gran)
                        putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
                    }
                    node.performAction(action, args)
                }
                else -> false
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 滚轮：主控滚一格 = 屏幕上滑动一段距离（约 2 个单位）。
     *  滚动手势串行派发：上一次结束再发下一次。系统规定派发新手势会中断进行中的手势，
     *  高频滚轮若各自新建手势会互相取消，也会打断拖拽链。 */
    fun scroll(dy: Int) {
        if (dy == 0) return
        handler.post {
            if (strokes.isNotEmpty()) return@post          // 拖拽中不插滚动手势
            if (scrollBusy) { pendingScroll = (pendingScroll + dy).coerceIn(-8, 8); return@post }
            dispatchScroll(dy.coerceIn(-8, 8))
        }
    }

    private fun dispatchScroll(dy: Int) {
        if (dy == 0) return
        val (w, h) = dispSize()
        val steps = Math.abs(dy)
        val px = (95f * steps).coerceAtMost(h * 0.4f)
        val startY = h / 2f
        val endY = startY - px * Math.signum(dy.toFloat())   // 滚轮向下 = 手指上滑
        val path = Path().apply {
            moveTo(w / 2f, startY)
            lineTo(w / 2f, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, (110 + 25 * steps).toLong())
        val gb = GestureDescription.Builder().addStroke(stroke)
        scrollBusy = true
        if (!dispatchGesture(gb.build(), scrollCb, handler)) {
            scrollBusy = false
            pendingScroll = 0
        }
    }

    private fun onScrollDone() {
        scrollBusy = false
        val dy = pendingScroll
        pendingScroll = 0
        drainTouches()
        if (dy != 0 && strokes.isEmpty() && !touchBusy) dispatchScroll(dy)
    }

    fun touch(action: String, p: Int, nx: Float, ny: Float) {
        if (action !in setOf("down", "move", "up")) return
        val (w, h) = dispSize()
        if (w <= 0 || h <= 0 || !nx.isFinite() || !ny.isFinite()) return
        val command = TouchCommand(action, p, PointF(
            Math.round(nx * w).coerceIn(0, w - 1).toFloat(),
            Math.round(ny * h).coerceIn(0, h - 1).toFloat()
        ))
        handler.post {
            if (action == "down") pendingScroll = 0
            val last = touchQueue.peekLast()
            if (action == "move" && last?.action == "move" && last.pointer == p) {
                touchQueue.removeLast()
            }
            touchQueue.addLast(command)
            drainTouches()
        }
    }

    fun resetTouches() {
        handler.post {
            touchQueue.clear()
            pendingScroll = 0
            releaseRequested = true
            drainTouches()
        }
    }

    private fun drainTouches() {
        if (touchBusy || scrollBusy) return
        if (releaseRequested) {
            releaseRequested = false
            if (strokes.isNotEmpty()) {
                finishing.addAll(strokes.keys)
                dispatchTouch()
                return
            }
        }
        while (touchQueue.isNotEmpty()) {
            val command = touchQueue.removeFirst()
            val p = command.pointer
            when (command.action) {
                "down" -> {
                    if (strokes.containsKey(p) || strokes.size >= GestureDescription.getMaxStrokeCount()) continue
                    target[p] = command.point
                    dispatchTouch(newP = p)
                    return
                }
                "move" -> {
                    if (strokes.containsKey(p)) target[p] = command.point
                    while (touchQueue.peekFirst()?.action == "move") {
                        val move = touchQueue.removeFirst()
                        if (strokes.containsKey(move.pointer)) target[move.pointer] = move.point
                    }
                    // 静止保持不派发空续笔：系统保留按下状态，空续笔反而可能被拒绝。
                    if (target.any { (id, point) -> sent[id]?.let { it.x != point.x || it.y != point.y } == true }) {
                        dispatchTouch()
                        return
                    }
                }
                "up" -> {
                    if (!strokes.containsKey(p)) continue
                    target[p] = command.point
                    finishing.add(p)
                    dispatchTouch()
                    return
                }
            }
        }
    }

    private fun cancelTouches() {
        genCounter++
        touchBusy = false
        releaseRequested = false
        touchQueue.clear()
        strokes.clear()
        sent.clear()
        target.clear()
        finishing.clear()
        SessionService.instance?.log("触控被系统中断，请松手后重新操作")
    }

    private fun dispatchTouch(newP: Int = -1) {
        val gb = GestureDescription.Builder()
        val nextStrokes = LinkedHashMap<Int, GestureDescription.StrokeDescription>()
        val nextSent = HashMap<Int, PointF>()
        for ((p, last) in strokes) {
            val s = sent.getValue(p)
            val t = target.getValue(p)
            val path = Path().apply {
                moveTo(s.x, s.y)
                if (s.x != t.x || s.y != t.y) lineTo(t.x, t.y)
            }
            val next = last.continueStroke(path, 0, 16, p !in finishing)
            nextStrokes[p] = next
            nextSent[p] = t
            gb.addStroke(next)
        }
        if (newP >= 0) {
            val t = target.getValue(newP)
            // 首采样点只能包含旧触点，否则系统无法匹配续笔；新增手指晚 1ms 按下。
            val start = if (strokes.isEmpty()) 0L else 1L
            val path = Path().apply { moveTo(t.x, t.y) }
            val next = GestureDescription.StrokeDescription(path, start, 16 - start, true)
            nextStrokes[newP] = next
            nextSent[newP] = t
            gb.addStroke(next)
        }
        touchBusy = true
        if (dispatchGesture(gb.build(), callbackFor(++genCounter), handler)) {
            strokes.putAll(nextStrokes)
            sent.putAll(nextSent)
        } else {
            // 未提交不等于已取消：保留有效续笔，供松手或断线清理时收尾。
            genCounter++
            touchBusy = false
            touchQueue.clear()
            finishing.clear()
            target.clear()
            target.putAll(sent)
            releaseRequested = strokes.isNotEmpty()
            SessionService.instance?.log("触控未能发送，请松手后重新操作")
        }
    }
}
