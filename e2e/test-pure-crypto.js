/*
 * 纯 JS 加密回退 vs Node 官方 crypto 的对照测试
 * 运行：node e2e/test-pure-crypto.js
 */
"use strict";
const crypto = require("crypto");
const PureCrypto = require("../master-web/crypto-fallback.js");

let failures = 0;
function assert(cond, msg) {
  if (!cond) { failures++; console.error("FAIL:", msg); }
  else console.log("PASS:", msg);
}
function hex(b) { return Buffer.from(b).toString("hex"); }

/* ① SHA-256 已知向量 */
assert(hex(PureCrypto.sha256(new TextEncoder().encode("abc"))) ===
  "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256('abc')");
assert(hex(PureCrypto.sha256(new Uint8Array(0))) ===
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "SHA-256('')");

/* ② SBox 已知值（AES 规范） */
assert(PureCrypto._sbox0 === 0x63, "SBOX[0]=0x63");
assert(PureCrypto._sbox1 === 0x7c, "SBOX[1]=0x7c");

/* ③ AES-256 单块 FIPS-197 向量 */
const keyFips = Uint8Array.from({ length: 32 }, (_, i) => i);
const ptFips = Uint8Array.from([0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
assert(hex(PureCrypto._ecbBlock(keyFips, ptFips)) === "8ea2b7ca516745bfeafc49904b496089", "AES-256 FIPS-197 单块向量");

/* ④ PBKDF2 vs Node（与 Kotlin Crypt.kt 同参数） */
const pin = "654321", salt = crypto.randomBytes(16);
const kJs = PureCrypto.pbkdf2Sha256(pin, salt, 200000, 32);
const kNode = crypto.pbkdf2Sync(pin, salt, 200000, 32, "sha256");
assert(hex(kJs) === hex(kNode), "PBKDF2(200000 轮) 与 Node/Kotlin 一致");

/* ⑤ GCM 双向互操作（iv||ct||tag 布局 = Kotlin Crypt 格式） */
function nodeSeal(keyBytes, iv, pt) {
  const c = crypto.createCipheriv("aes-256-gcm", keyBytes, iv);
  const ct = Buffer.concat([c.update(pt), c.final(), c.getAuthTag()]);
  return Buffer.concat([iv, ct]);
}
function nodeOpen(keyBytes, data) {
  const iv = data.subarray(0, 12), ct = data.subarray(12);
  const d = crypto.createDecipheriv("aes-256-gcm", keyBytes, iv);
  d.setAuthTag(ct.subarray(ct.length - 16));
  return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
}
const key = kNode;
const ctx = PureCrypto.context(key);
const sizes = [0, 1, 15, 16, 17, 255, 256, 1000, 4096, 65536];
let allOk = true;
for (const size of sizes) {
  const pt = crypto.randomBytes(size);
  const iv = crypto.randomBytes(12);
  try {
    // JS 加密 → Node 解密（seal 不含 IV，需拼上）
    const sealed = ctx.seal(iv, pt);
    const byNode = nodeOpen(key, Buffer.concat([iv, Buffer.from(sealed)]));
    const d1 = Buffer.from(byNode).equals(pt);
    // Node 加密 → JS 解密
    const sealed2 = nodeSeal(key, iv, pt);
    const d2 = Buffer.from(ctx.open(iv, sealed2.subarray(12))).equals(pt);
    if (!(d1 && d2)) { allOk = false; console.error(`FAIL size=${size}`); }
  } catch (e) { allOk = false; console.error(`FAIL size=${size}: ${e.message}`); }
}
assert(allOk, `GCM 双向互操作 ${sizes.length} 种长度全部一致`);

/* ⑥ 篡改检测 */
const pt = crypto.randomBytes(100), iv = crypto.randomBytes(12);
const sealed = Buffer.from(ctx.seal(iv, pt));
sealed[sealed.length - 3] ^= 0x01;
let threw = false;
try { ctx.open(iv, sealed); } catch (e) { threw = true; }
assert(threw, "密文被篡改时 GCM 拒绝解密");

console.log(failures === 0 ? "\n纯 JS 加密回退：全部通过 ✔" : `\n${failures} 项失败 ✘`);
process.exit(failures === 0 ? 0 : 1);
