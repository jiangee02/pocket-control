/*
 * 随手控 · 中继服务器（relay.js）
 * 作用：让被控手机（主动连出）与主控浏览器在跨互联网时互相找到对方并转发加密流量。
 * 中继只看到密文，不能解密任何画面或指令。
 *
 * 部署（VPS 上，需 Node 16+）：
 *   npm i ws
 *   node relay.js            # 默认端口 8787，可用环境变量 PORT 覆盖
 * 建议用 pm2 或 systemd 常驻，详见交付文档。
 */
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = parseInt(process.env.PORT || "8787", 10);
const PUBLIC_DIR = path.join(__dirname, "public");
const MAX_ROOMS = 200;
const MAX_PAYLOAD = 8 * 1024 * 1024; // 8MB，足够单个视频帧
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

const rooms = new Map(); // roomId -> { host, client, created }

function roomId() {
  let s = "";
  for (let i = 0; i < 6; i++) s += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  return s;
}

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

function serveStatic(req, res) {
  let p = req.url.split("?")[0];
  if (p === "/" || p === "/index.html") p = "/index.html";
  const file = path.join(PUBLIC_DIR, path.normalize(p).replace(/^([/\\])+/, ""));
  if (!file.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end("404"); }
    res.writeHead(200, {
      "Content-Type": MIME[path.extname(file)] || "application/octet-stream",
      "Cache-Control": p === "/index.html" ? "no-store" : "max-age=3600",
    });
    res.end(data);
  });
}

const server = http.createServer(serveStatic);
const wss = new WebSocketServer({ server, maxPayload: MAX_PAYLOAD });

wss.on("connection", (ws, req) => {
  const u = new URL(req.url, "http://localhost");
  const pathname = u.pathname;

  if (pathname === "/host") {
    // 第一条消息必须是带密钥的认证
    ws.once("message", (data) => {
      let j;
      try { j = JSON.parse(data.toString()); } catch (e) { return ws.close(); }
      if (j.t !== "relay-auth" || j.role !== "host" || typeof j.secret !== "string" || j.secret.length < 16) {
        return ws.close();
      }
      if (rooms.size >= MAX_ROOMS) { ws.send(JSON.stringify({ t: "relay-full" })); return ws.close(); }
      let id;
      do { id = roomId(); } while (rooms.has(id));
      const room = { id, host: ws, client: null, created: Date.now() };
      rooms.set(id, room);
      ws.send(JSON.stringify({ t: "relay-room", room: id }));
      console.log(`[room] created ${id} (total ${rooms.size})`);

      ws.on("message", (d, isBinary) => {
        const r = rooms.get(id);
        if (!r || !r.client) return;
        try { r.client.send(d, { binary: isBinary }); } catch (e) {}
      });
      ws.on("close", () => {
        if (rooms.get(id) && rooms.get(id).host === ws) {
          if (room.client) { try { room.client.close(4000, "host-gone"); } catch (e) {} }
          rooms.delete(id);
          console.log(`[room] closed ${id}`);
        }
      });
      ws.on("error", () => {});
    });
    return;
  }

  if (pathname === "/client") {
    const rid = u.searchParams.get("room") || "";
    const r = rooms.get(rid);
    if (!r || r.client) {
      try { ws.send(JSON.stringify({ t: r ? "relay-busy" : "relay-noroom" })); } catch (e) {}
      return ws.close();
    }
    r.client = ws;
    try { r.host.send(JSON.stringify({ t: "relay-join" })); } catch (e) {}
    console.log(`[room] client joined ${rid}`);

    ws.on("message", (d, isBinary) => {
      if (!r.host) return;
      try {
        if (isBinary) r.host.send(d, { binary: true });
        else r.host.send(JSON.stringify({ t: "pipe", data: d.toString() }));
      } catch (e) {}
    });
    ws.on("close", () => {
      if (r.client === ws) {
        r.client = null;
        try { r.host.send(JSON.stringify({ t: "relay-leave" })); } catch (e) {}
        console.log(`[room] client left ${rid}`);
      }
    });
    ws.on("error", () => {});
    return;
  }

  ws.close();
});

// 每分钟检查一次：被控端连接已断或房间创建超过 24 小时就回收
setInterval(() => {
  const now = Date.now();
  for (const [id, r] of rooms) {
    const idle = now - r.created;
    const dead = (!r.host || r.host.readyState !== 1) || idle > 24 * 3600 * 1000;
    if (dead) {
      if (r.client) { try { r.client.close(); } catch (e) {} }
      if (r.host) { try { r.host.close(); } catch (e) {} }
      rooms.delete(id);
      console.log(`[room] reaped ${id}`);
    }
  }
}, 60 * 1000);

server.listen(PORT, () => {
  console.log(`随手控中继已启动: http://0.0.0.0:${PORT}  (WS: /host /client)`);
});
