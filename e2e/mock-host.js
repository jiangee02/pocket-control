/*
 * mock-host.js — 模拟被控端（与 SessionService / HostServer 协议一致），用于在真实浏览器里测试主控网页：
 *  - 提供 master-web 静态文件
 *  - WS /ws：PIN 鉴权 → hello(带盐) → 上行指令(全加密)，回 pong
 *  - GET /stream?pin=：下行 TLV 流 [1B type][4B len BE][payload]，0x10=加密JSON文本；
 *    二进制载荷：0x01+4B时间戳ms+H.264=视频帧，0x03+H.264=配置包，0x02=文件
 * 用法：node e2e/mock-host.js [H.264 Annex-B 文件路径]   （无文件则只测协议/UI，无画面）
 */
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { WebSocketServer } = require(path.join(__dirname, "..", "relay-server", "node_modules", "ws"));

const PORT = 9390;
const PIN = "123456";
const SALT = crypto.randomBytes(16);
const KEY = crypto.pbkdf2Sync(PIN, SALT, 200000, 32, "sha256");

let quality = "orig";   // hello / vid-start 携带 quality
let dlIdMock = 0;       // 手机→主控 文件 id
let upMock = null;      // 主控→手机 上传元信息
let lastUpMock = null;  // 最近一次保存结果（重连后补发 file-up-end 时重放，模拟真机幂等回执）
const upPartJobs = new Map();  // 并行上传的临时缓冲：uid → { buf, done[], name, savePath }
let upBytes = 0;        // 本次上传收到的数据字节数（用于验证块没有乱序/丢失）
// 模拟手机存储（用于在浏览器里测试网页文件管理 UI）
const FAKE_FS = {
  "/sdcard": [
    { name: "Download", dir: true }, { name: "DCIM", dir: true },
    { name: "笔记.txt", dir: false, size: 1280 }, { name: "截图.png", dir: false, size: 245760 },
  ],
  "/sdcard/Download": [
    { name: "随手控", dir: true }, { name: "报告.pdf", dir: false, size: 1048576 }, { name: "歌曲.mp3", dir: false, size: 5242880 },
  ],
  "/sdcard/Download/随手控": [ { name: "已接收.txt", dir: false, size: 64 } ],
  "/sdcard/DCIM": [ { name: "Camera", dir: true } ],
  "/sdcard/DCIM/Camera": [ { name: "IMG_0001.jpg", dir: false, size: 3145728 } ],
};
function fsParentOf(p) { if (p === "/sdcard") return ""; const i = p.lastIndexOf("/"); return i <= 0 ? "" : p.substring(0, i); }
function dimsFor(q) {
  return { low: [480, 1066], mid: [720, 1600], high: [1080, 2400], orig: [1440, 3200] }[q] || [720, 1600];
}

function wrapText(json) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", KEY, iv);
  const ct = Buffer.concat([c.update(Buffer.from(json, "utf8")), c.final(), c.getAuthTag()]);
  return JSON.stringify({ t: "enc", d: Buffer.concat([iv, ct]).toString("base64") });
}
function unwrapText(s) {
  try {
    const j = JSON.parse(s);
    if (j.t !== "enc") return j;
    const raw = Buffer.from(j.d, "base64");
    const d = crypto.createDecipheriv("aes-256-gcm", KEY, raw.subarray(0, 12));
    d.setAuthTag(raw.subarray(raw.length - 16));
    return JSON.parse(Buffer.concat([d.update(raw.subarray(12, raw.length - 16)), d.final()]).toString("utf8"));
  } catch (e) { return null; }
}
function wrapBin(buf) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", KEY, iv);
  const ct = Buffer.concat([c.update(buf), c.final(), c.getAuthTag()]);
  return Buffer.concat([iv, ct]);
}
function unwrapBin(raw) {
  const d = crypto.createDecipheriv("aes-256-gcm", KEY, raw.subarray(0, 12));
  d.setAuthTag(raw.subarray(raw.length - 16));
  return Buffer.concat([d.update(raw.subarray(12, raw.length - 16)), d.final()]);
}
// 专用通道：把明文按 256KB 切块加密并加 4 字节大端长度前缀（与 HostServer.FramedCipherInputStream 一致）
// skipChunks：跳过前 N 个完整块（模拟主控暂停后的续传）
function framedCipher(plain, skipChunks = 0) {
  const CH = 256 * 1024, out = [];
  let idx = 0;
  for (let off = 0; off < plain.length; off += CH, idx++) {
    if (idx < skipChunks) continue;
    const enc = wrapBin(plain.subarray(off, off + CH));
    const h = Buffer.alloc(4); h.writeUInt32BE(enc.length, 0);
    out.push(h, enc);
  }
  return Buffer.concat(out);
}
function fakeFileBytes(name, size) {
  const n = Math.max(1, Math.min(size || 1024, 8 * 1024 * 1024));
  const b = Buffer.alloc(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + name.length) & 0xff;
  return b;
}
function findFakeFile(p) {
  const i = p.lastIndexOf("/"); const dir = p.substring(0, i) || "/sdcard"; const name = p.substring(i + 1);
  const es = FAKE_FS[dir]; if (!es) return null;
  return es.find(e => !e.dir && e.name === name) || null;
}

/* ---------- H.264 Annex-B 解析 ---------- */
const SC = Buffer.from([0, 0, 0, 1]);
function splitNalus(buf) {
  const starts = [];
  for (let i = 0; i < buf.length - 3; i++) {
    if (buf[i] === 0 && buf[i + 1] === 0) {
      if (buf[i + 2] === 1) starts.push([i, 3]);
      else if (buf[i + 2] === 0 && buf[i + 3] === 1) { starts.push([i, 4]); i++; }
    }
  }
  const nalus = [];
  for (let k = 0; k < starts.length; k++) {
    const [s, l] = starts[k];
    const end = k + 1 < starts.length ? starts[k + 1][0] : buf.length;
    nalus.push(buf.subarray(s + l, end));
  }
  return nalus;
}

let cfgNalu = null;   // SPS+PPS（含起始码）
let frames = [];      // 每帧一个 Buffer（含起始码，可能带 SEI）
const videoPath = process.argv[2];
if (videoPath && fs.existsSync(videoPath)) {
  const nalus = splitNalus(fs.readFileSync(videoPath));
  const sps = [], pps = [];
  let pending = [];
  for (const n of nalus) {
    const t = n[0] & 0x1f;
    if (t === 7) sps.push(Buffer.concat([SC, n]));
    else if (t === 8) pps.push(Buffer.concat([SC, n]));
    else if (t === 5 || t === 1) { frames.push(Buffer.concat([...pending, SC, n])); pending = []; }
    else if (t === 6 || t === 9) pending.push(Buffer.concat([SC, n])); // SEI/AUD 并入下一帧
  }
  if (sps.length && pps.length) cfgNalu = Buffer.concat([...sps, ...pps]);
  console.log(`H.264: ${nalus.length} NALUs → ${frames.length} 帧, 配置包 ${cfgNalu ? cfgNalu.length : 0}B, profile_idc=${sps.length ? sps[0][5] : "?"}(66=Baseline,100=High)`);
}

/* ---------- 下行流（/stream TLV，与 HostServer.serveStream 一致） ---------- */
let streams = []; // { res, timer, idx, pts } —— 多主控各自一条流，互不影响
function writeStream(s, type, payload) {
  if (!streams.includes(s)) return;
  const h = Buffer.alloc(5);
  h[0] = type;
  h.writeUInt32BE(payload.length, 1);
  s.res.write(h);
  s.res.write(payload);
}
function streamSend(type, payload) {
  for (const s of streams) writeStream(s, type, payload);
}
function sendConfigTo(s) {
  if (cfgNalu) writeStream(s, 0x01, wrapBin(Buffer.concat([Buffer.from([3]), cfgNalu])));
}
function sendConfig() {
  if (cfgNalu) streamSend(0x01, wrapBin(Buffer.concat([Buffer.from([3]), cfgNalu])));
}
function sendFrame(s, h264) {
  s.pts = (s.pts || 0) + 33;
  const h = Buffer.alloc(5);
  h[0] = 1;
  h.writeUInt32BE(s.pts >>> 0, 1);
  writeStream(s, 0x01, wrapBin(Buffer.concat([h, h264])));
}
function broadcastText(json) { streamSend(0x10, Buffer.from(wrapText(json), "utf8")); }
// 模拟被控端上报「输入框位置」：顶部一条搜索框 + 中间一个输入框（归一化坐标）
function sendEditRects() {
  broadcastText(JSON.stringify({ t: "editrects", r: [[0.06, 0.06, 0.94, 0.12], [0.1, 0.55, 0.9, 0.63]] }));
}
// 模拟系统输入法：点中上面两个输入框就"弹输入法"，点别处就收起
const EDIT_RECTS = [[0.06, 0.06, 0.94, 0.12], [0.1, 0.55, 0.9, 0.63]];
let imeOn = false, imeTimer = null;
function setIme(on) {
  if (on === imeOn) return;
  imeOn = on;
  broadcastText(JSON.stringify({ t: "ime", on }));
  console.log("  → 模拟系统输入法:", on ? "弹出" : "收起");
}

const PUB = path.join(__dirname, "..", "master-web");
const MIME = { ".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8", ".wasm": "application/wasm" };
const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  const p = u.pathname;
  if (p === "/stream") {
    if (u.searchParams.get("pin") !== PIN) { res.writeHead(403); return res.end("bad pin"); }
    res.writeHead(200, { "Content-Type": "application/octet-stream", "Cache-Control": "no-store" });
    const st = { res, timer: null, idx: 0, pts: 0 };
    streams.push(st);
    console.log("[stream] 下行流已建立（" + streams.length + "）");
    sendConfigTo(st);
    const d0 = dimsFor(quality);
    broadcastText(JSON.stringify({ t: "vid-start", w: d0[0], h: d0[1], quality }));
    if (frames.length) {
      st.timer = setInterval(() => {
        if (!streams.includes(st)) return;
        if (st.idx >= frames.length) { st.idx = 0; sendConfigTo(st); }
        sendFrame(st, frames[st.idx++]);
      }, 33);
    }
    res.on("close", () => {
      clearInterval(st.timer);
      streams = streams.filter(x => x !== st);
      console.log("[stream] 已断开（剩 " + streams.length + "）");
    });
    return;
  }
  if (p === "/file") {
    if (u.searchParams.get("pin") !== PIN) { res.writeHead(403); return res.end("bad pin"); }
    const fp = u.searchParams.get("path") || "";
    const skip = parseInt(u.searchParams.get("skip") || "0", 10) || 0;
    const count = parseInt(u.searchParams.get("count") || "0", 10) || 0;
    const enc = u.searchParams.get("enc") !== "0";   // 主控关了「传输加密」→ enc=0 明文帧
    const plainFrame = b => { const h = Buffer.alloc(4); h.writeUInt32BE(b.length, 0); return Buffer.concat([h, b]); };
    const fe = findFakeFile(fp);
    if (!fe) { res.writeHead(404); return res.end("no file"); }
    const name = fe.name, size = fe.size || 1024;
    console.log("  → [专用下载] 文件:", fp, size, "字节", skip ? "(跳过 " + skip + " 块)" : "", count ? "(限 " + count + " 块)" : "");
    res.writeHead(200, {
      "Content-Type": "application/octet-stream", "Cache-Control": "no-store",
      "X-File-Name": encodeURIComponent(name), "X-File-Size": String(size),
    });
    // 分块慢速发送（每 120ms 一块），模拟真实网络，便于测试暂停/续传
    const plain = fakeFileBytes(name, size);
    const CH = 256 * 1024;
    let off = skip * CH;
    const end = count > 0 ? Math.min(plain.length, (skip + count) * CH) : plain.length;
    const timer = setInterval(() => {
      if (res.writableEnded || res.destroyed) { clearInterval(timer); return; }
      if (off >= end) { clearInterval(timer); return res.end(); }
      res.write(enc ? framedCipher(plain.subarray(off, off + CH)) : plainFrame(plain.subarray(off, off + CH)));
      off += CH;
    }, 120);
    res.on("close", () => clearInterval(timer));
    return;
  }
  if (p === "/upload") {
    if (u.searchParams.get("pin") !== PIN) { res.writeHead(403); return res.end("bad pin"); }
    if (req.method !== "POST") { res.writeHead(405); return res.end("POST only"); }
    const name = u.searchParams.get("name") || "file";
    const dest = u.searchParams.get("dest") || "app";
    const rel = u.searchParams.get("rel") || "";
    const enc = u.searchParams.get("enc") !== "0";
    const uid = u.searchParams.get("uid");
    const partCount = parseInt(u.searchParams.get("parts") || "0", 10) || 0;
    const part = parseInt(u.searchParams.get("part") || "-1", 10);
    const size = parseInt(u.searchParams.get("size") || "0", 10) || 0;
    const off = parseInt(u.searchParams.get("off") || "0", 10) || 0;
    const sub = rel ? rel + "/" : "";
    const savePath = (dest === "downloadroot" ? "Download/" : "Download/随手控/") + sub + name;
    if (uid && partCount > 0 && part >= 0) {
      // 分片并行上传：按偏移拼装临时缓冲，最后一段到达后统一回执
      const job = upPartJobs.get(uid) || (() => {
        const j = { buf: Buffer.alloc(size), done: new Array(partCount).fill(false), name, savePath };
        upPartJobs.set(uid, j); return j;
      })();
      const chunks = [];
      req.on("data", c => chunks.push(c));
      req.on("end", () => {
        const body = Buffer.concat(chunks);
        let o = 0, plainBytes = 0;
        try {
          while (o < body.length) {
            const len = body.readUInt32BE(o); o += 4;
            const plain = enc ? unwrapBin(body.subarray(o, o + len)) : body.subarray(o, o + len); o += len;
            plain.copy(job.buf, off + plainBytes);
            plainBytes += plain.length;
          }
          if (off + plainBytes > size) throw new Error("分片越界");
        } catch (e) {
          console.log("  !! [并行上传] 分片解密失败:", e.message);
          res.writeHead(200, { "Content-Type": "application/octet-stream", "Connection": "close" });
          return res.end(enc ? wrapBin(Buffer.from(JSON.stringify({ ok: false, err: e.message }))) : Buffer.from(JSON.stringify({ ok: false, err: e.message })));
        }
        job.done[part] = true;
        const allDone = job.done.every(Boolean);
        console.log("  → [并行上传] 分片", part + 1 + "/" + partCount, "偏移", off, plainBytes, "字节", allDone ? "→ 收齐" : "");
        const payload = allDone
          ? { ok: true, path: job.savePath, name: job.name }
          : { ok: true, part: true };
        if (allDone) {
          upPartJobs.delete(uid);
          console.log("  → [并行上传] 保存:", job.savePath, "共", size, "字节");
        }
        res.writeHead(200, { "Content-Type": "application/octet-stream", "Connection": "close" });
        res.end(enc ? wrapBin(Buffer.from(JSON.stringify(payload))) : Buffer.from(JSON.stringify(payload)));
      });
      return;
    }
    const chunks = [];
    req.on("data", c => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      let off = 0, plainBytes = 0, frames = 0, ok = true;
      try {
        while (off < body.length) {
          const len = body.readUInt32BE(off); off += 4;
          const plain = enc ? unwrapBin(body.subarray(off, off + len)) : body.subarray(off, off + len); off += len;
          plainBytes += plain.length; frames++;
        }
      } catch (e) { ok = false; console.log("  !! [专用上传] 解密失败:", e.message); }
      console.log("  → [专用上传] 保存:", savePath, "共", plainBytes, "字节 /", frames, "帧", ok ? "(校验通过)" : "(失败)", enc ? "" : "(未加密)");
      const result = enc ? wrapBin(Buffer.from(JSON.stringify({ ok, path: savePath, name }), "utf8"))
                         : Buffer.from(JSON.stringify({ ok, path: savePath, name }), "utf8");
      res.writeHead(200, { "Content-Type": "application/octet-stream", "Connection": "close" });
      res.end(result);
    });
    return;
  }
  let f = p === "/" ? "/index.html" : p;
  fs.readFile(path.join(PUB, f.replace(/^\/+/, "")), (err, data) => {
    if (err) { res.writeHead(404); return res.end("404"); }
    res.writeHead(200, { "Content-Type": MIME[path.extname(f)] || "application/octet-stream", "Cache-Control": "no-store" });
    res.end(data);
  });
});

/* ---------- 上行（WS /ws，与 HostServer.Sock 一致） ---------- */
const wss = new WebSocketServer({ server, path: "/ws" });
wss.on("connection", (ws) => {
  let authed = false;
  let n = 0;
  ws.on("message", (data, isBinary) => {
    if (!authed) {
      const j = JSON.parse(data.toString());
      if (j.t === "auth" && j.pin === PIN) {
        authed = true;
        const d = dimsFor(quality);
        ws.send(JSON.stringify({ t: "hello", w: d[0], h: d[1], name: "Mock 测试机", quality, salt: SALT.toString("base64"), enc: true }));
        console.log("[ws] 主控端已连接");
      } else {
        ws.send(JSON.stringify({ t: "auth-fail" }));
        ws.close();
      }
      return;
    }
    if (isBinary) {   // 上传数据块（0x02 加密帧）：只统计，验证 meta → 块 → end 的顺序
      upBytes += data.length;
      if (!upMock) console.log("  !! 数据块早于 file-up-meta 到达（乱序）");
      return;
    }
    const j = unwrapText(data.toString());
    if (!j) return;
    if (j.t === "ping") broadcastText(JSON.stringify({ t: "pong", ts: j.ts }));
    else if (j.t === "ready") { console.log("[ws] 主控密钥就绪，重发编码配置"); sendConfig(); sendEditRects(); }
    else if (j.t === "editq") sendEditRects();
    else if (j.t === "key") console.log("  → 按键:", j.k);
    else if (j.t === "touch") {
      if (n++ % 10 === 0) console.log("  → 触控:", j.a);
      if (j.a === "up") {
        clearTimeout(imeTimer);
        const hit = EDIT_RECTS.some(r => j.x >= r[0] && j.x <= r[2] && j.y >= r[1] && j.y <= r[3]);
        imeTimer = setTimeout(() => setIme(hit), 150);   // 系统输入法要过一会儿才真正弹出来
      }
    }
    else if (j.t === "text") console.log("  → 文字:", j.s);
    else if (j.t === "clip-get") broadcastText(JSON.stringify({ t: "clip", s: "手机剪贴板示例内容" }));
    else if (j.t === "clip-set") broadcastText(JSON.stringify({ t: "clip-ok" }));
    else if (j.t === "fs-list") {
      const p = j.path || "/sdcard";
      const entries = FAKE_FS[p];
      console.log("  → 浏览目录:", p);
      if (!entries) broadcastText(JSON.stringify({ t: "fs-err", code: "notfound", s: "目录不存在" }));
      else broadcastText(JSON.stringify({ t: "fs-list", path: p, parent: fsParentOf(p), entries: entries.map(e => ({ name: e.name, dir: e.dir, size: e.size || 0, mtime: Date.now() })) }));
    }
    else if (j.t === "fs-get") {
      const p = j.path || ""; const name = p.substring(p.lastIndexOf("/") + 1); const id = ++dlIdMock;
      console.log("  → 下载文件:", p);
      broadcastText(JSON.stringify({ t: "file-meta", id, name, size: 17 }));
      streamSend(0x01, wrapBin(Buffer.concat([Buffer.from([2]), Buffer.from("mock file content")])));
      broadcastText(JSON.stringify({ t: "file-end", id, ok: true }));
    }
    else if (j.t === "file-up-meta") {
      if (upMock) console.log("  !! 上一份上传还没收尾就开始了新上传");
      upMock = { id: j.id, name: j.name, dest: j.dest };
      upBytes = 0;
      console.log("  → 接收文件:", j.name, "保存到", j.dest);
    }
    else if (j.t === "file-up-end") {
      if (upMock && (j.id === undefined || j.id === upMock.id)) {
        const u = upMock;
        const p = (u.dest === "downloadroot" ? "Download/" : "Download/随手控/") + u.name;
        lastUpMock = { id: u.id, name: u.name, path: p };
        broadcastText(JSON.stringify({ t: "file-end", id: u.id, ok: true, path: p, name: u.name }));
        console.log("  → 保存完成:", u.name, "共", upBytes, "字节");
        upMock = null; upBytes = 0;
      } else if (lastUpMock && j.id === lastUpMock.id) {
        broadcastText(JSON.stringify({ t: "file-end", id: j.id, ok: true, path: lastUpMock.path, name: lastUpMock.name }));
        console.log("  → 重放保存结果:", lastUpMock.name);
      } else {
        broadcastText(JSON.stringify({ t: "file-end", id: j.id, ok: false, unknown: true }));
        console.log("  → 未知上传 id，回报 unknown:", j.id);
      }
    }
    else if (j.t === "quality") {
      quality = j.preset;
      const d = dimsFor(quality);
      console.log("  → 切画质:", quality, d.join("x"));
      broadcastText(JSON.stringify({ t: "quality", preset: quality }));
      broadcastText(JSON.stringify({ t: "vid-start", w: d[0], h: d[1], quality }));
      sendConfig();
    }
  });
  ws.on("close", () => console.log("[ws] 主控端已断开"));
});

// 模拟被控端每 2 秒上报编码排队耗时（hstat，主控顶栏显示「编码 xxms」）
setInterval(() => { if (streams.length) broadcastText(JSON.stringify({ t: "hstat", lag: 35 })); }, 2000);

server.listen(PORT, () => console.log(`mock host: http://127.0.0.1:${PORT}/  PIN=${PIN}${frames.length ? " (含视频 " + frames.length + " 帧)" : " (无视频文件)"}`));
