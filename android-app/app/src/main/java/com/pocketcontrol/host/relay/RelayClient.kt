package com.pocketcontrol.host.relay

import com.pocketcontrol.host.service.SessionService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 中继模式客户端：主动连出（可穿 NAT），经 relay.js 把消息转给主控。
 * 文本指令以 {"t":"pipe","data":...} 包装；视频/文件二进制帧原样透传。
 * 加密与直连一致（端到端 AES-GCM，中继只见密文）。
 */
class RelayClient(addr: String, private val secret: String, private val svc: SessionService) {

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile var room: String = ""
    @Volatile private var pipeAuthed = false
    @Volatile private var closed = false
    private var attempts = 0

    private val url: String = normalize(addr)

    private fun normalize(a: String): String {
        val s = a.trim()
        return when {
            s.startsWith("ws://") || s.startsWith("wss://") -> if (s.endsWith("/host")) s else "$s/host"
            s.contains("://") -> s.replaceFirst(Regex("^https?://"), "ws://").let { if (it.endsWith("/host")) it else "$it/host" }
            else -> "ws://$s/host"
        }
    }

    fun connect() {
        if (closed) return
        attempts++
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, listener)
    }

    fun close() {
        closed = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        if (closed || !svc.isSessionActive) return
        Thread {
            try { Thread.sleep(3000L * attempts.coerceAtMost(10)) } catch (_: Exception) {}
            if (!closed && svc.isSessionActive) {
                pipeAuthed = false
                connect()
            }
        }.start()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            webSocket.send("""{"t":"relay-auth","role":"host","secret":"$secret"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val j = try { JSONObject(text) } catch (_: Exception) { return }
            when (j.optString("t")) {
                "relay-room" -> { room = j.optString("room"); svc.onRelayRoom(room) }
                "relay-join" -> { pipeAuthed = false; svc.log("主控端已进入房间") }
                "relay-leave" -> svc.onClientGone()
                "pipe" -> handlePipe(j.optString("data"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!pipeAuthed) return
            val plain = svc.unwrapFrame(bytes.toByteArray()) ?: return
            svc.onClientBinary(plain)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            svc.log("中继连接中断，正在重连${t.message?.let { "：$it" } ?: ""}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun handlePipe(data: String) {
        if (!pipeAuthed) {
            if (svc.tryAuth(data)) {
                pipeAuthed = true
                // hello 明文（浏览器需要盐），仅包在中继的 pipe 信封里
                try {
                    ws?.send(JSONObject().put("t", "pipe").put("data", svc.helloJson()).toString())
                } catch (_: Exception) {}
                svc.onClientHello()
                svc.sendConfigToRelay()
            } else {
                sendPipe("""{"t":"auth-fail"}""")
                try { Thread.sleep(400) } catch (_: Exception) {}
            }
        } else {
            svc.onClientText(data)
        }
    }

    fun sendPipe(text: String) {
        val j = JSONObject().put("t", "pipe").put("data", svc.wrapText(text))
        try { ws?.send(j.toString()) } catch (_: Exception) {}
    }

    fun broadcast(frame: ByteArray) {
        try { ws?.send(svc.wrapFrame(frame).toByteString()) } catch (_: Exception) {}
    }
}
