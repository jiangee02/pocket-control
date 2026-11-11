/*
 * crypto-fallback.js — 纯 JS 加密回退（无 WebCrypto 的非安全上下文使用，如 http 局域网直连）
 * 提供：SHA-256 / HMAC-SHA256 / PBKDF2-HMAC-SHA256 / AES-256-GCM(封装为 seal/open)
 * 输出格式与服务端 Crypt.kt 完全一致：seal 输出为 [12B IV][密文][16B Tag]，open 接受同格式。
 * 视频帧也是加密下发的（8Mbps ≈ 1MB/s），手机浏览器单线程解密必须够快：
 * AES 用 32 位 T 表（13 轮查表），GHASH 用 16×256 的 32 位表，全程不碰 BigInt。
 * 所有实现均通过与 Node crypto 的对照向量测试（e2e/test-pure-crypto.js）。
 */
(function (root) {
  "use strict";

  /* ================= SHA-256 ================= */
  // 常量由素数生成，避免手抄错误：H=前8个素数平方根小数部分，K=前64个素数立方根小数部分
  var H0 = (function () {
    var h = new Int32Array(8), n = 0;
    for (var i = 2; n < 8; i++) {
      var p = true;
      for (var j = 2; j * j <= i; j++) if (i % j === 0) { p = false; break; }
      if (!p) continue;
      var s = Math.sqrt(i);
      h[n++] = Math.floor((s - Math.floor(s)) * 4294967296) | 0;
    }
    return h;
  })();
  var K = (function () {
    var k = new Int32Array(64), n = 0;
    for (var i = 2; n < 64; i++) {
      var p = true;
      for (var j = 2; j * j <= i; j++) if (i % j === 0) { p = false; break; }
      if (!p) continue;
      var c = Math.cbrt(i);
      k[n++] = Math.floor((c - Math.floor(c)) * 4294967296) | 0;
    }
    return k;
  })();

  function sha256(msg) { // msg: Uint8Array → Uint8Array(32)
    var len = msg.length;
    var bitLenHi = Math.floor(len / 536870912);           // len*8 高 32 位
    var bitLenLo = (len << 3) >>> 0;
    var padded = new Uint8Array((((len + 8) >> 6) + 1) << 6);
    padded.set(msg);
    padded[len] = 0x80;
    var dv = new DataView(padded.buffer);
    dv.setUint32(padded.length - 8, bitLenHi);
    dv.setUint32(padded.length - 4, bitLenLo);
    var h = new Int32Array(H0);
    var w = new Int32Array(64);
    for (var off = 0; off < padded.length; off += 64) {
      for (var t = 0; t < 16; t++) w[t] = dv.getInt32(off + t * 4);
      for (t = 16; t < 64; t++) {
        var s0 = ((w[t - 15] >>> 7) | (w[t - 15] << 25)) ^ ((w[t - 15] >>> 18) | (w[t - 15] << 14)) ^ (w[t - 15] >>> 3);
        var s1 = ((w[t - 2] >>> 17) | (w[t - 2] << 15)) ^ ((w[t - 2] >>> 19) | (w[t - 2] << 13)) ^ (w[t - 2] >>> 10);
        w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
      for (t = 0; t < 64; t++) {
        var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        var ch = (e & f) ^ (~e & g);
        var t1 = (hh + S1 + ch + K[t] + w[t]) | 0;
        var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var t2 = (S0 + maj) | 0;
        hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
      h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
    }
    var out = new Uint8Array(32), odv = new DataView(out.buffer);
    for (var i = 0; i < 8; i++) odv.setInt32(i * 4, h[i]);
    return out;
  }

  function hmacSha256(key, msg) {
    var k = key.length > 64 ? sha256(key) : key;
    var ipad = new Uint8Array(64 + msg.length);
    var opad = new Uint8Array(64 + 32);
    var i;
    for (i = 0; i < 64; i++) {
      var kb = i < k.length ? k[i] : 0;
      ipad[i] = kb ^ 0x36; opad[i] = kb ^ 0x5c;
    }
    ipad.set(msg, 64);
    var inner = sha256(ipad);
    opad.set(inner, 64);
    return sha256(opad);
  }

  function pbkdf2Sha256(passwordStr, salt, iterations, dkLen) {
    var pw = new TextEncoder().encode(passwordStr);
    var out = new Uint8Array(dkLen);
    var blocks = Math.ceil(dkLen / 32);
    var saltBuf = new Uint8Array(salt.length + 4);
    saltBuf.set(salt);
    for (var b = 1; b <= blocks; b++) {
      saltBuf[salt.length] = (b >>> 24) & 0xff;
      saltBuf[salt.length + 1] = (b >>> 16) & 0xff;
      saltBuf[salt.length + 2] = (b >>> 8) & 0xff;
      saltBuf[salt.length + 3] = b & 0xff;
      var u = hmacSha256(pw, saltBuf);
      var t = new Uint8Array(u);
      for (var it = 1; it < iterations; it++) {
        u = hmacSha256(pw, u);
        for (var x = 0; x < 32; x++) t[x] ^= u[x];
      }
      out.set(t.subarray(0, Math.min(32, dkLen - (b - 1) * 32)), (b - 1) * 32);
    }
    return out;
  }

  /* ================= AES-256（32 位 T 表，只做加密方向，GCM 够用） ================= */
  var SBOX = (function () {
    var alog = new Uint8Array(256), log = new Uint8Array(256);
    var a = 1;
    for (var i = 0; i < 255; i++) {
      alog[i] = a; log[a] = i;
      var hi = a & 0x80;
      a = (a << 1) & 0xff;
      if (hi) a ^= 0x1b;
      a ^= alog[i]; // a = 3*oldA
    }
    alog[255] = 1; // 循环群回到起点（3^255 = 1）
    var s = new Uint8Array(256);
    for (var y = 0; y < 256; y++) {
      var inv = y === 0 ? 0 : alog[255 - log[y]];
      var b = inv, r = inv;
      for (var k = 1; k <= 4; k++) { b = ((b << 1) | (b >>> 7)) & 0xff; r ^= b; }
      s[y] = r ^ 0x63;
    }
    return s;
  })();

  function xtime(x) { return ((x << 1) ^ ((x & 0x80) ? 0x1b : 0)) & 0xff; }

  // Te0[x] = [2·S, S, S, 3·S]，Te1/2/3 依次循环右移 8 位
  var Te0 = new Int32Array(256), Te1 = new Int32Array(256),
      Te2 = new Int32Array(256), Te3 = new Int32Array(256);
  (function () {
    for (var x = 0; x < 256; x++) {
      var s = SBOX[x], s2 = xtime(s), s3 = s2 ^ s;
      Te0[x] = (s2 << 24) | (s << 16) | (s << 8) | s3;
      Te1[x] = (s3 << 24) | (s2 << 16) | (s << 8) | s;
      Te2[x] = (s << 24) | (s3 << 16) | (s2 << 8) | s;
      Te3[x] = (s << 24) | (s << 16) | (s3 << 8) | s2;
    }
  })();

  // 轮密钥展开成 60 个大端 32 位字
  function expandKey(key) {
    var rk = new Int32Array(60), i;
    for (i = 0; i < 8; i++)
      rk[i] = (key[i * 4] << 24) | (key[i * 4 + 1] << 16) | (key[i * 4 + 2] << 8) | key[i * 4 + 3];
    var rcon = 1, t;
    for (i = 8; i < 60; i++) {
      t = rk[i - 1];
      if (i % 8 === 0) {
        // RotWord + SubWord + Rcon
        t = ((SBOX[(t >>> 16) & 0xff] << 24) | (SBOX[(t >>> 8) & 0xff] << 16) |
             (SBOX[t & 0xff] << 8) | SBOX[(t >>> 24) & 0xff]) ^ (rcon << 24);
        rcon = ((rcon << 1) ^ ((rcon & 0x80) ? 0x1b : 0)) & 0xff;
      } else if (i % 8 === 4) {
        t = (SBOX[(t >>> 24) & 0xff] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) |
            (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff];
      }
      rk[i] = rk[i - 8] ^ t;
    }
    return rk;
  }

  // 加密一个 16B 块（4 个大端字），结果写在 _e0.._e3（避免每次分配对象）
  var _e0 = 0, _e1 = 0, _e2 = 0, _e3 = 0;
  function encryptWords(rk, s0, s1, s2, s3) {
    var t0 = s0 ^ rk[0], t1 = s1 ^ rk[1], t2 = s2 ^ rk[2], t3 = s3 ^ rk[3];
    var r = 1, k, a0, a1, a2, a3;
    for (; r < 14; r++) {
      k = r << 2;
      a0 = Te0[t0 >>> 24] ^ Te1[(t1 >>> 16) & 0xff] ^ Te2[(t2 >>> 8) & 0xff] ^ Te3[t3 & 0xff] ^ rk[k];
      a1 = Te0[t1 >>> 24] ^ Te1[(t2 >>> 16) & 0xff] ^ Te2[(t3 >>> 8) & 0xff] ^ Te3[t0 & 0xff] ^ rk[k + 1];
      a2 = Te0[t2 >>> 24] ^ Te1[(t3 >>> 16) & 0xff] ^ Te2[(t0 >>> 8) & 0xff] ^ Te3[t1 & 0xff] ^ rk[k + 2];
      a3 = Te0[t3 >>> 24] ^ Te1[(t0 >>> 16) & 0xff] ^ Te2[(t1 >>> 8) & 0xff] ^ Te3[t2 & 0xff] ^ rk[k + 3];
      t0 = a0; t1 = a1; t2 = a2; t3 = a3;
    }
    k = 56;
    _e0 = ((SBOX[t0 >>> 24] << 24) | (SBOX[(t1 >>> 16) & 0xff] << 16) | (SBOX[(t2 >>> 8) & 0xff] << 8) | SBOX[t3 & 0xff]) ^ rk[k];
    _e1 = ((SBOX[t1 >>> 24] << 24) | (SBOX[(t2 >>> 16) & 0xff] << 16) | (SBOX[(t3 >>> 8) & 0xff] << 8) | SBOX[t0 & 0xff]) ^ rk[k + 1];
    _e2 = ((SBOX[t2 >>> 24] << 24) | (SBOX[(t3 >>> 16) & 0xff] << 16) | (SBOX[(t0 >>> 8) & 0xff] << 8) | SBOX[t1 & 0xff]) ^ rk[k + 2];
    _e3 = ((SBOX[t3 >>> 24] << 24) | (SBOX[(t0 >>> 16) & 0xff] << 16) | (SBOX[(t1 >>> 8) & 0xff] << 8) | SBOX[t2 & 0xff]) ^ rk[k + 3];
  }

  /* ================= GCM（GF(2^128) 32 位查表，无 BigInt） ================= */
  // 128 位数右移一位（GCM 的反射约定），溢出时异或 R = 0xE1<<120
  function mulX(c) {
    var lsb = c[3] & 1;
    c[3] = ((c[3] >>> 1) | (c[2] << 31)) | 0;
    c[2] = ((c[2] >>> 1) | (c[1] << 31)) | 0;
    c[1] = ((c[1] >>> 1) | (c[0] << 31)) | 0;
    c[0] = ((c[0] >>> 1) ^ (lsb ? 0xE1000000 : 0)) | 0;
  }

  function GcmCtx(key) {
    this.rk = expandKey(key);
    encryptWords(this.rk, 0, 0, 0, 0);
    var c = new Int32Array(4);
    c[0] = _e0; c[1] = _e1; c[2] = _e2; c[3] = _e3;   // H = E(K, 0)
    // T[i][v] = v·H·x^(8*(15-i))：第 i 字节取 v 时对 Y·H 的贡献
    var T = new Int32Array(16 * 256 * 4);
    var vals = new Int32Array(8 * 4);
    var v, j, p, o0, o1, o2, o3;
    for (var i = 0; i < 16; i++) {   // 第 0 字节权重最高（x^0），逐字节乘 x^8
      for (j = 0; j < 8; j++) {
        vals[j * 4] = c[0]; vals[j * 4 + 1] = c[1]; vals[j * 4 + 2] = c[2]; vals[j * 4 + 3] = c[3];
        mulX(c);
      }
      var base = i << 10;   // i*256*4
      for (v = 0; v < 256; v++) {
        o0 = o1 = o2 = o3 = 0;
        for (j = 0; j < 8; j++) {
          if (v & (1 << (7 - j))) {
            o0 ^= vals[j * 4]; o1 ^= vals[j * 4 + 1]; o2 ^= vals[j * 4 + 2]; o3 ^= vals[j * 4 + 3];
          }
        }
        p = base + (v << 2);
        T[p] = o0; T[p + 1] = o1; T[p + 2] = o2; T[p + 3] = o3;
      }
    }
    this.T = T;
    this._g0 = this._g1 = this._g2 = this._g3 = 0;
    this._s0 = this._s1 = this._s2 = this._s3 = 0;
  }

  // Y = Y·H（Y: y0..y3 大端字）→ _g0.._g3
  GcmCtx.prototype._mulH = function (y0, y1, y2, y3) {
    var T = this.T, o0 = 0, o1 = 0, o2 = 0, o3 = 0, p;
    p = ((y0 >>> 24) & 0xff) << 2; o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 1024 + (((y0 >>> 16) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 2048 + (((y0 >>> 8) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 3072 + ((y0 & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 4096 + (((y1 >>> 24) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 5120 + (((y1 >>> 16) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 6144 + (((y1 >>> 8) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 7168 + ((y1 & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 8192 + (((y2 >>> 24) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 9216 + (((y2 >>> 16) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 10240 + (((y2 >>> 8) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 11264 + ((y2 & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 12288 + (((y3 >>> 24) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 13312 + (((y3 >>> 16) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 14336 + (((y3 >>> 8) & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    p = 15360 + ((y3 & 0xff) << 2); o0 ^= T[p]; o1 ^= T[p + 1]; o2 ^= T[p + 2]; o3 ^= T[p + 3];
    this._g0 = o0; this._g1 = o1; this._g2 = o2; this._g3 = o3;
  };

  // GHASH(ct)（无 AAD），结果在 _s0.._s3
  GcmCtx.prototype._ghash = function (data) {
    var n = data.length, full = n & ~15, off = 0;
    var y0 = 0, y1 = 0, y2 = 0, y3 = 0;
    var dv = new DataView(data.buffer, data.byteOffset, n);
    for (; off < full; off += 16) {
      y0 ^= dv.getInt32(off); y1 ^= dv.getInt32(off + 4);
      y2 ^= dv.getInt32(off + 8); y3 ^= dv.getInt32(off + 12);
      this._mulH(y0, y1, y2, y3);
      y0 = this._g0; y1 = this._g1; y2 = this._g2; y3 = this._g3;
    }
    var rem = n - full;
    if (rem) {
      var b0 = 0, b1 = 0, b2 = 0, b3 = 0, byt;
      for (var i = 0; i < rem; i++) {
        byt = data[full + i];
        if (i < 4) b0 |= byt << (24 - (i << 3));
        else if (i < 8) b1 |= byt << (24 - ((i - 4) << 3));
        else if (i < 12) b2 |= byt << (24 - ((i - 8) << 3));
        else b3 |= byt << (24 - ((i - 12) << 3));
      }
      y0 ^= b0; y1 ^= b1; y2 ^= b2; y3 ^= b3;
      this._mulH(y0, y1, y2, y3);
      y0 = this._g0; y1 = this._g1; y2 = this._g2; y3 = this._g3;
    }
    // 长度块：AAD 0 位，密文 n*8 位（大端 64 位）
    y2 ^= Math.floor(n / 536870912);
    y3 ^= (n << 3) >>> 0;
    this._mulH(y0, y1, y2, y3);
    this._s0 = this._g0; this._s1 = this._g1; this._s2 = this._g2; this._s3 = this._g3;
  };

  // GCM 计数器异或（J0 = iv||0x00000001，数据从 inc32(J0)=2 开始）
  GcmCtx.prototype._ctr = function (iv, data) {
    var n = data.length, out = new Uint8Array(n);
    var n0 = (iv[0] << 24) | (iv[1] << 16) | (iv[2] << 8) | iv[3];
    var n1 = (iv[4] << 24) | (iv[5] << 16) | (iv[6] << 8) | iv[7];
    var n2 = (iv[8] << 24) | (iv[9] << 16) | (iv[10] << 8) | iv[11];
    var inDv = new DataView(data.buffer, data.byteOffset, n);
    var outDv = new DataView(out.buffer);
    var ctr = 1, off = 0, i, kb, kdv;
    while (off < n) {
      ctr = (ctr + 1) | 0;
      encryptWords(this.rk, n0, n1, n2, ctr);
      if (off + 16 <= n) {
        outDv.setInt32(off, inDv.getInt32(off) ^ _e0);
        outDv.setInt32(off + 4, inDv.getInt32(off + 4) ^ _e1);
        outDv.setInt32(off + 8, inDv.getInt32(off + 8) ^ _e2);
        outDv.setInt32(off + 12, inDv.getInt32(off + 12) ^ _e3);
      } else {
        kb = new Uint8Array(16); kdv = new DataView(kb.buffer);
        kdv.setInt32(0, _e0); kdv.setInt32(4, _e1); kdv.setInt32(8, _e2); kdv.setInt32(12, _e3);
        for (i = 0; off + i < n; i++) out[off + i] = data[off + i] ^ kb[i];
      }
      off += 16;
    }
    return out;
  };

  // 返回 [密文||16B Tag]；Kotlin/服务端格式：外层再拼 12B IV
  GcmCtx.prototype.seal = function (iv, plain) {
    var ct = this._ctr(iv, plain);
    this._ghash(ct);
    var s0 = this._s0, s1 = this._s1, s2 = this._s2, s3 = this._s3;
    encryptWords(this.rk, (iv[0] << 24) | (iv[1] << 16) | (iv[2] << 8) | iv[3],
      (iv[4] << 24) | (iv[5] << 16) | (iv[6] << 8) | iv[7],
      (iv[8] << 24) | (iv[9] << 16) | (iv[10] << 8) | iv[11], 1);
    var out = new Uint8Array(ct.length + 16);
    out.set(ct);
    var dv = new DataView(out.buffer, ct.length, 16);
    dv.setInt32(0, _e0 ^ s0); dv.setInt32(4, _e1 ^ s1);
    dv.setInt32(8, _e2 ^ s2); dv.setInt32(12, _e3 ^ s3);
    return out;
  };

  GcmCtx.prototype.open = function (iv, data) { // data = ct||tag
    if (data.length < 16) throw new Error("gcm: too short");
    var n = data.length - 16;
    var ct = data.subarray(0, n), tag = data.subarray(n);
    this._ghash(ct);
    var s0 = this._s0, s1 = this._s1, s2 = this._s2, s3 = this._s3;
    encryptWords(this.rk, (iv[0] << 24) | (iv[1] << 16) | (iv[2] << 8) | iv[3],
      (iv[4] << 24) | (iv[5] << 16) | (iv[6] << 8) | iv[7],
      (iv[8] << 24) | (iv[9] << 16) | (iv[10] << 8) | iv[11], 1);
    var tdv = new DataView(tag.buffer, tag.byteOffset, 16);
    var diff = (tdv.getInt32(0) ^ _e0 ^ s0) | (tdv.getInt32(4) ^ _e1 ^ s1) |
               (tdv.getInt32(8) ^ _e2 ^ s2) | (tdv.getInt32(12) ^ _e3 ^ s3);
    if (diff !== 0) throw new Error("gcm: tag mismatch");
    return this._ctr(iv, ct);
  };

  /* ================= 导出 ================= */
  var PureCrypto = {
    sha256: sha256,
    pbkdf2Sha256: pbkdf2Sha256,
    /** keyBytes: Uint8Array(32)；返回 {seal(iv,pt), open(iv,ct||tag)} */
    context: function (keyBytes) { return new GcmCtx(keyBytes); },
    // —— 测试钩子 ——
    _ecbBlock: function (keyBytes, block) {
      var rk = expandKey(keyBytes);
      encryptWords(rk, (block[0] << 24) | (block[1] << 16) | (block[2] << 8) | block[3],
        (block[4] << 24) | (block[5] << 16) | (block[6] << 8) | block[7],
        (block[8] << 24) | (block[9] << 16) | (block[10] << 8) | block[11],
        (block[12] << 24) | (block[13] << 16) | (block[14] << 8) | block[15]);
      var out = new Uint8Array(16), dv = new DataView(out.buffer);
      dv.setInt32(0, _e0); dv.setInt32(4, _e1); dv.setInt32(8, _e2); dv.setInt32(12, _e3);
      return out;
    },
    _sbox0: SBOX[0], _sbox1: SBOX[1]
  };

  if (typeof module !== "undefined" && module.exports) module.exports = PureCrypto;
  else root.PureCrypto = PureCrypto;
})(typeof self !== "undefined" ? self : this);
