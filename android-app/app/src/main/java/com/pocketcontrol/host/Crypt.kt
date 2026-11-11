package com.pocketcontrol.host

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密：
 * 密钥 = PBKDF2-HMAC-SHA256(PIN, 会话盐, 200000 轮, 256bit)
 * 每条消息随机 12 字节 IV，AES-256-GCM（认证标签 128bit，附在密文尾部，与 WebCrypto 一致）。
 * 握手阶段（auth / hello / auth-fail / bye）为明文，之后所有文本与二进制帧全部加密。
 */
object Crypt {

    const val ITERATIONS = 200_000
    private val rng = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { rng.nextBytes(it) }

    fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        return iv + ct
    }

    fun decrypt(key: SecretKeySpec, data: ByteArray): ByteArray {
        require(data.size > 13) { "ciphertext too short" }
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    fun b64(b: ByteArray): String = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
