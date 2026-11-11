package com.pocketcontrol.host.server

import com.pocketcontrol.host.service.SessionService
import com.pocketcontrol.host.service.VideoSink
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 直连模式的内置服务器：同端口托管主控网页（HTTP）+ 会话通道（WebSocket）。
 * 安全：PIN 鉴权（错误 6 次拉黑 IP）；握手后所有帧走 AES-GCM 端到端加密。
 */
class HostServer(port: Int, private val svc: SessionService) : NanoWSD(port) {

    val clients = CopyOnWriteArrayList<Sock>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private val banned = ConcurrentHashMap.newKeySet<String>()

    inner class Sock(val hs: NanoHTTPD.IHTTPSession) : WebSocket(hs), VideoSink {
        @Volatile var authed = false
        val ip: String = hs.remoteIpAddress ?: "unknown"
        val sendLock = Any()
        var sendFails = 0

        override fun onOpen() {}

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, byRemote: Boolean) {
            clients.remove(this)
            if (authed) svc.onClientGone()
        }

        override fun onPong(frame: WebSocketFrame?) {}

        override fun onException(e: IOException?) {
            clients.remove(this)
        }

        override fun onMessage(frame: WebSocketFrame) {
            try {
                if (frame.opCode == WebSocketFrame.OpCode.Text) {
                    val text = frame.textPayload ?: return
                    if (authed) svc.onClientText(text) else handleAuth(text)
                } else if (frame.opCode == WebSocketFrame.OpCode.Binary) {
                    val payload = frame.binaryPayload ?: return
                    if (!authed || payload.isEmpty()) return
                    // 二进制帧（文件 chunks）与视频一样端到端加密，必须先解密再分发，
                    // 否则 data[0] 是 IV 字节而非 0x02，上传的文件会写成 0 字节
                    val plain = svc.unwrapFrame(payload) ?: return
                    svc.onClientBinary(plain)
                }
            } catch (_: Exception) {}
        }

        private fun handleAuth(text: String) {
            if (banned.contains(ip)) { closeSock(); return }
            if (svc.tryAuth(text)) {
                authed = true
                clients.add(this)
                // hello 必须明文发送：浏览器要靠它携带的盐派生密钥，此后才切换到加密
                try { send(svc.helloJson()) } catch (_: Exception) {}
                svc.onClientHello()
                svc.sendConfigTo(this)
                failCount.remove(ip)
            } else {
                val n = (failCount[ip] ?: 0) + 1
                failCount[ip] = n
                try { send("""{"t":"auth-fail"}""") } catch (_: Exception) {}
                if (n >= 6) banned.add(ip)
                closeSock()
            }
        }

        fun closeSock() {
            try { close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) } catch (_: Exception) {}
        }

        /** VideoSink 实现：发送已含类型前缀的帧 */
        override fun sendFrame(frame: ByteArray) = synchronized(sendLock) {
            try { send(svc.wrapFrame(frame)) } catch (e: Exception) { noteSendFail(e) }
        }

        fun noteSendFail(e: Exception) {
            sendFails++
            if (sendFails == 1 || sendFails % 100 == 0) {
                svc.log("向主控端发送数据失败（第 $sendFails 次）：${e.message}")
            }
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket = Sock(handshake)

    /** TCP_NODELAY：默认的 Nagle 会把每帧末尾的小包压到收到 ACK 才发（实测可多 40ms），
     *  视频帧和触控这类小包必须立刻出去。NanoHTTPD 自己不设，只能在接收连接时补上。 */
    override fun createClientHandler(finalAccept: java.net.Socket, inputStream: java.io.InputStream): ClientHandler {
        try { finalAccept.tcpNoDelay = true } catch (_: Throwable) {}
        return super.createClientHandler(finalAccept, inputStream)
    }

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: "/"
        return try {
            when {
                uri == "/" || uri == "/index.html" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8",
                        svc.assets.open("index.html")
                    ).apply { addHeader("Cache-Control", "no-store") }
                uri == "/jmuxer.min.js" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "application/javascript; charset=utf-8",
                        svc.assets.open("jmuxer.min.js")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri == "/crypto-fallback.js" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "application/javascript; charset=utf-8",
                        svc.assets.open("crypto-fallback.js")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri == "/favicon-192.png" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "image/png",
                        svc.assets.open("favicon-192.png")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri == "/favicon-128.png" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "image/png",
                        svc.assets.open("favicon-128.png")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri == "/favicon-64.png" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "image/png",
                        svc.assets.open("favicon-64.png")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri == "/favicon-32.png" ->
                    newChunkedResponse(
                        NanoHTTPD.Response.Status.OK, "image/png",
                        svc.assets.open("favicon-32.png")
                    ).apply { addHeader("Cache-Control", "max-age=3600") }
                uri.startsWith("/stream") -> serveStream(session)
                uri.startsWith("/file") -> serveFileDownload(session)
                uri.startsWith("/upload") -> serveUpload(session)
                else -> newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "asset missing")
        }
    }

    /** 下行流：视频+文本统一走 HTTP chunked，绕开 WS 下行（库在部分安卓环境握手后发送不可达） */
    private fun serveStream(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val pin = session.parameters["pin"]?.firstOrNull() ?: ""
        if (pin != svc.sessionPin) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "bad pin")
        }
        val queue = StreamQueue()
        val sink = object : SessionService.StreamSink {
            override fun send(type: Int, payload: ByteArray) {
                val l = payload.size
                val framed = ByteArray(5 + l)
                framed[0] = type.toByte()
                framed[1] = (l ushr 24 and 0xff).toByte()
                framed[2] = (l ushr 16 and 0xff).toByte()
                framed[3] = (l ushr 8 and 0xff).toByte()
                framed[4] = (l and 0xff).toByte()
                System.arraycopy(payload, 0, framed, 5, l)
                queue.enqueue(framed)
            }
            override fun close() { queue.finish() }
            override fun flushVideo() { queue.dropVideoFrames() }
        }
        svc.registerStream(sink)
        // HTTP 响应结束（主控断开/刷新）时注销 sink，否则旧流一直占着「观看者」名额，
        // 日志里会出现 下行流已建立（2） 且编码器无法按无人观看暂停
        queue.onClose = { svc.unregisterStream(sink) }
        try {
            svc.cachedCfg()?.let { sink.send(0x01, svc.videoFrameFor(it)) }
            svc.requestKeyFrame()
        } catch (_: Exception) {}
        return newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", queue)
            .apply { addHeader("Cache-Control", "no-store") }
    }

    /** 专用下载通道：单独一条 HTTP 连接把文件加密推给主控，不再挤视频下行流（传文件时画面不卡、不丢帧）。
     *   body 格式与上行一致：[4 字节大端长度][AES-GCM 密文]…；X-File-Size 给主控算进度。
     *   skip=N：跳过前 N 个完整 256KB 明文块（主控暂停后继续用，密文块与明文块一一对应）。 */
    private fun serveFileDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val pin = session.parameters["pin"]?.firstOrNull() ?: ""
        if (pin != svc.sessionPin)
            return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "bad pin")
        val path = session.parameters["path"]?.firstOrNull() ?: ""
        val f = svc.fileForDownload(path)
            ?: return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "no file")
        val skip = session.parameters["skip"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // 分片并行下载：count 限定本连接只发多少个 256KB 块（缺省=发到文件尾）
        val count = session.parameters["count"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE
        // enc=0：主控在文件面板关了「传输加密」，本连接只发明文帧（仅文件内容；PIN 仍校验）
        val plain = session.parameters["enc"]?.firstOrNull() == "0"
        svc.beginTransfer()
        val ins = FramedCipherInputStream(java.io.FileInputStream(f), svc, skip, count, plain) { svc.endTransfer() }
        return newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", ins).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-File-Name", java.net.URLEncoder.encode(f.name, "UTF-8"))
            addHeader("X-File-Size", f.length().toString())
        }
    }

    /** 专用上传通道：直接从 socket 流式读请求体（NanoHTTPD 不预先缓冲 body），解密写入接收目录。
     *  独立连接，触控指令仍走原 WS，互不排队。结果 JSON 加密后返回。 */
    private fun serveUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val pin = session.parameters["pin"]?.firstOrNull() ?: ""
        if (pin != svc.sessionPin)
            return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "bad pin")
        if (session.method != NanoHTTPD.Method.POST)
            return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST only")
        val name = session.parameters["name"]?.firstOrNull() ?: "file"
        val dest = session.parameters["dest"]?.firstOrNull() ?: "app"
        val rel = session.parameters["rel"]?.firstOrNull() ?: ""
        val total = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (total < 0)
            return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "need content-length")
        // enc=0：主控关掉了「传输加密」，body 只有长度前缀没有密文，回执也是明文 JSON
        val plain = session.parameters["enc"]?.firstOrNull() == "0"
        val uid = session.parameters["uid"]?.firstOrNull()
        val off = session.parameters["off"]?.firstOrNull()?.toLongOrNull()
        val part = session.parameters["part"]?.firstOrNull()?.toIntOrNull()
        val parts = session.parameters["parts"]?.firstOrNull()?.toIntOrNull()
        val size = session.parameters["size"]?.firstOrNull()?.toLongOrNull()
        svc.beginTransfer()
        val result = try {
            if (uid != null && off != null && part != null && parts != null && size != null)
                svc.receiveHttpUploadPart(uid, name, dest, rel, size, off, part, parts, total, session.inputStream, plain)
            else
                svc.receiveHttpUpload(name, dest, rel, total, session.inputStream, plain)
        } finally {
            svc.endTransfer()
        }
        val bytes = result.toByteArray(Charsets.UTF_8)
        val body = if (plain) bytes else svc.wrapFrame(bytes)
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/octet-stream",
            java.io.ByteArrayInputStream(body), body.size.toLong()
        ).apply { addHeader("Connection", "close") }
    }
}

/** 把明文 InputStream 切成 256KB 块、逐块 AES-GCM 加密并加 4 字节大端长度前缀输出，
 *  供专用下载通道流式发送（边读边加密边发，主控边收边解密，不必整文件进内存）。
 *  每块读满 256KB（末块除外），保证「第 N 个密文块 = 第 N 个明文块」，主控暂停后可用 skip=N 续传。 */
private class FramedCipherInputStream(
    private val plain: java.io.InputStream,
    private val svc: SessionService,
    skipChunks: Int = 0,
    maxChunks: Int = Int.MAX_VALUE,
    private val plainMode: Boolean = false,
    private val onClose: (() -> Unit)? = null
) : java.io.InputStream() {
    private val buf = ByteArray(256 * 1024)
    private var out: ByteArray? = null
    private var pos = 0
    private var eof = false
    private var leftChunks = maxChunks

    init {
        // 续传：跳过前 skipChunks 个完整块（只读盘不加密，开销很小）
        var left = skipChunks
        while (left > 0 && !eof) {
            if (readFull() < buf.size) eof = true
            left--
        }
    }

    /** 读满 buf；返回实际读到的字节数（<buf.size 表示已到文件尾） */
    private fun readFull(): Int {
        var n = 0
        while (n < buf.size) {
            val r = try { plain.read(buf, n, buf.size - n) } catch (e: IOException) { -1 }
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun next(): Boolean {
        if (eof || leftChunks <= 0) return false
        val n = readFull()
        if (n == 0) { eof = true; return false }
        if (n < buf.size) eof = true
        leftChunks--
        val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
        val enc = if (plainMode) chunk else svc.wrapFrame(chunk)
        val frame = ByteArray(4 + enc.size)
        frame[0] = (enc.size ushr 24 and 0xff).toByte()
        frame[1] = (enc.size ushr 16 and 0xff).toByte()
        frame[2] = (enc.size ushr 8 and 0xff).toByte()
        frame[3] = (enc.size and 0xff).toByte()
        System.arraycopy(enc, 0, frame, 4, enc.size)
        out = frame; pos = 0
        return true
    }

    override fun read(): Int {
        while (true) {
            val o = out
            if (o != null && pos < o.size) return o[pos++].toInt() and 0xff
            if (!next()) return -1
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val o = out
            if (o != null && pos < o.size) {
                val n = minOf(len, o.size - pos)
                System.arraycopy(o, pos, b, off, n); pos += n; return n
            }
            if (!next()) return -1
        }
    }

    override fun close() {
        try { plain.close() } catch (_: Exception) {}
        try { onClose?.invoke() } catch (_: Exception) {}
    }
}

/** 多写者安全的下行流缓冲，替代 PipedInputStream/PipedOutputStream。
 *  Piped 流会记住「最后一个写线程」，任何短命线程（文件/剪贴板回报）写完即死会触发
 *  「Write end dead」使整条下行流永久关闭（画面卡死但操控仍有效）。队列式无此问题。
 *  积压策略：丢最旧的视频帧而不是结束流。结束流会让主控看门狗把整条连接判死，
 *  形成「掉线→重连→再掉线」的循环；丢几帧画面只会短暂花屏，立刻被关键帧恢复。 */
private class StreamQueue(private val capacity: Int = 64) : java.io.InputStream() {
    private val q = ArrayDeque<ByteArray>()
    private val lock = Object()
    @Volatile private var closed = false
    private var cur: ByteArray? = null
    private var pos = 0
    var onClose: (() -> Unit)? = null

    /** 传输开始/结束时清掉积压的旧视频帧：不清的话网络恢复后旧帧一股脑涌到主控，
     *  画面会落后好几秒（「传完文件画面卡半天」的根因）。文本帧（回执/心跳）保留。 */
    fun dropVideoFrames() {
        synchronized(lock) {
            val it = q.iterator()
            while (it.hasNext()) { if (it.next()[0] != 0x10.toByte()) it.remove() }
        }
    }

    fun enqueue(b: ByteArray) {
        synchronized(lock) {
            if (closed) throw java.io.IOException("stream closed")
            if (q.size >= capacity) {
                // 文本帧（0x10：心跳回执/文件回执）绝不丢；从队头找最旧的视频帧丢弃
                val it = q.iterator()
                var dropped = false
                while (it.hasNext()) {
                    if (it.next()[0] != 0x10.toByte()) { it.remove(); dropped = true; break }
                }
                if (!dropped) { finishLocked(); throw java.io.IOException("stream backlog") }
            }
            q.addLast(b)
            lock.notifyAll()
        }
    }

    fun finish() { synchronized(lock) { finishLocked() } }

    private fun finishLocked() {
        if (closed) return
        closed = true
        q.clear()
        lock.notifyAll()
        try { onClose?.invoke() } catch (_: Exception) {}
    }

    private fun chunk(): ByteArray? {
        while (true) {
            val c = cur
            if (c != null && pos < c.size) return c
            synchronized(lock) {
                while (q.isEmpty() && !closed) {
                    try { lock.wait() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
                }
                if (q.isEmpty()) { cur = null; return null }
                cur = q.removeFirst(); pos = 0
            }
        }
    }

    override fun read(): Int {
        val c = chunk() ?: return -1
        return c[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val c = chunk() ?: return -1
        val n = minOf(len, c.size - pos)
        System.arraycopy(c, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int {
        val c = cur ?: return 0
        return c.size - pos
    }

    override fun close() { finish() }
}
