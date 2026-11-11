/*
 * 端到端自检：① 加密互操作（模拟 Kotlin Crypt.kt 的 Node crypto ↔ 主控网页的 WebCrypto 逻辑）
 *            ② relay.js 中继管道（文本 pipe 包装 + 二进制透传，双向）
 * 运行：node e2e/test-protocol.js
 */
"use strict";
const { spawn } = require("child_process");
const crypto = require("crypto");
const path = require("path");

let failures = 0;
function assert(cond, msg) {
  if (!cond) { failures++; console.error("FAIL:", msg); }
  else console.log("PASS:", msg);
}

const PIN = "123456";
const SALT = crypto.randomBytes(16);
const ITER = 200000;

/* ---------- Kotlin Crypt.kt 的等价实现 ---------- */
function ktKey(pin) { return crypto.pbkdf2Sync(pin, SALT, ITER, 32, "sha256"); }
function ktEncrypt(text) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", ktKey(PIN), iv);
  const ct = Buffer.concat([c.update(Buffer.from(text, "utf8")), c.final(), c.getAuthTag()]);
  return Buffer.concat([iv, ct]);
}
function ktDecrypt(buf, pin = PIN) {
  const iv = buf.subarray(0, 12), ct = buf.subarray(12);
  const d = crypto.createDecipheriv("aes-256-gcm", ktKey(pin), iv);
  d.setAuthTag(ct.subarray(ct.length - 16));
  return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
}

/* ---------- 主控网页（index.html）的等价实现，跑在 Node WebCrypto 上 ---------- */
let webKey = null;
function b64ToBytes(s) { return new Uint8Array(Buffer.from(s, "base64")); }
function bytesToB64(u8) { return Buffer.from(u8).toString("base64"); }
async function webDerive() {
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(PIN), "PBKDF2", false, ["deriveKey"]);
  webKey = await crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: new Uint8Array(SALT), iterations: ITER, hash: "SHA-256" },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}
async function webEncText(o) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const pt = new TextEncoder().encode(JSON.stringify(o));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, webKey, pt));
  const out = new Uint8Array(iv.length + ct.length); out.set(iv); out.set(ct, iv.length);
  return JSON.stringify({ t: "enc", d: bytesToB64(out) });
}
async function webDecText(s) {
  const j = JSON.parse(s);
  if (j.t !== "enc") return j;
  const raw = b64ToBytes(j.d);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(0, 12) }, webKey, raw.slice(12));
  return JSON.parse(new TextDecoder().decode(pt));
}
async function webEncBin(u8) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, webKey, u8));
  const out = new Uint8Array(iv.length + ct.length); out.set(iv); out.set(ct, iv.length);
  return out;
}
async function webDecBin(u8) {
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: u8.slice(0, 12) }, webKey, u8.slice(12));
  return new Uint8Array(pt);
}

async function cryptoTests() {
  await webDerive();
  const msg = JSON.stringify({ t: "touch", a: "down", p: 1, x: 0.5, y: 0.5 });

  const wrapped = JSON.parse(await webEncText(JSON.parse(msg)));
  assert(wrapped.t === "enc" && typeof wrapped.d === "string", "网页加密包装为 {t:enc,d:...}");
  const r1 = ktDecrypt(Buffer.from(wrapped.d, "base64")).toString("utf8");
  assert(r1 === msg, "网页加密 → Kotlin 逻辑解密一致");

  const enc2 = ktEncrypt(msg);
  const r2 = await webDecText(JSON.stringify({ t: "enc", d: enc2.toString("base64") }));
  assert(JSON.stringify(r2) === msg, "Kotlin 逻辑加密 → 网页解密一致");

  const frame = new Uint8Array([1, 0, 0, 0, 1, 65, 66, 67, 200, 31]);
  const ef = await webEncBin(frame);
  const df = new Uint8Array(ktDecrypt(Buffer.from(ef)));
  assert(Buffer.from(df).equals(Buffer.from(frame)), "视频帧二进制往返一致");

  const eb = await (async () => { // Kotlin 加密二进制 → 网页解密
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv("aes-256-gcm", ktKey(PIN), iv);
    const ct = Buffer.concat([c.update(Buffer.from(frame)), c.final(), c.getAuthTag()]);
    return new Uint8Array(Buffer.concat([iv, ct]));
  })();
  assert(Buffer.from(await webDecBin(eb)).equals(Buffer.from(frame)), "Kotlin 加密二进制 → 网页解密一致");

  let threw = false;
  try { ktDecrypt(enc2, "000000"); } catch (e) { threw = true; }
  assert(threw, "错误 PIN 被 GCM 认证拒绝");
}

/* ---------- relay.js 管道测试 ---------- */
function relayTests() {
  return new Promise((resolve) => {
    const relay = spawn("node", [path.join(__dirname, "..", "relay-server", "relay.js")],
      { env: { ...process.env, PORT: "8788" }, stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    relay.stdout.on("data", d => out += d);
    relay.stderr.on("data", d => out += d);

    setTimeout(() => {
      const WebSocket = require(path.join(__dirname, "..", "relay-server", "node_modules", "ws"));
      const host = new WebSocket("ws://127.0.0.1:8788/host");
      let roomId = "";
      let client = null;

      host.on("open", () => host.send(JSON.stringify({ t: "relay-auth", role: "host", secret: "0123456789abcdef0123456789abcdef" })));
      host.on("message", (data, isBinary) => {
        const s = data.toString();
        let j; try { j = JSON.parse(s); } catch (e) { return; }
        if (j.t === "relay-room") {
          roomId = j.room;
          assert(/^[A-Z2-9]{6}$/.test(roomId), "中继分配房间号: " + roomId);
          client = new WebSocket("ws://127.0.0.1:8788/client?room=" + roomId);
          let gotHello = null, gotBin = null;
          client.on("message", (d2, isBin2) => {
            if (isBin2) gotBin = d2;
            else if (!gotHello) gotHello = d2.toString();
          });
          client.on("open", () => {
            client.send(JSON.stringify({ t: "auth", pin: "123456" }));
            client.send(Buffer.from([2, 1, 2, 3, 4, 5]));
          });
          setTimeout(() => {
            assert(gotHello === JSON.stringify({ t: "hello", w: 720, h: 1600 }), "主控文本原样透传到客户端");
            assert(gotBin && gotBin.equals(Buffer.from([1, 9, 9, 9])), "主控二进制原样透传到客户端");
            relay.kill();
            assert(out.includes("created"), "中继日志正常: " + out.split("\n")[0]);
            resolve();
          }, 600);
        } else if (j.t === "relay-join") {
          host.send(JSON.stringify({ t: "hello", w: 720, h: 1600 }));
          host.send(Buffer.from([1, 9, 9, 9]));
        } else if (j.t === "pipe") {
          assert(j.data === JSON.stringify({ t: "auth", pin: "123456" }), "主控收到 pipe 包装的客户端文本");
        }
      });
      host.on("message", (data, isBinary) => {
        if (isBinary) assert(data.equals(Buffer.from([2, 1, 2, 3, 4, 5])), "客户端二进制原样透传到主控");
      });
      setTimeout(() => { try { relay.kill(); } catch (e) {} resolve(); }, 4000);
    }, 700);

    relay.on("exit", () => {});
  });
}

(async () => {
  console.log("== ① 加密互操作 ==");
  await cryptoTests();
  console.log("== ② relay.js 管道 ==");
  await relayTests();
  console.log(failures === 0 ? "\n全部通过 ✔" : `\n${failures} 项失败 ✘`);
  process.exit(failures === 0 ? 0 : 1);
})();
