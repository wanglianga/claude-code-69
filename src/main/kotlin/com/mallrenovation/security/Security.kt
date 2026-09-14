package com.mallrenovation.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

object Security {
    private const val ITER = 20_000
    private const val KEY_LEN = 256
    private val rng = SecureRandom()

    // 演示用固定密钥（生产应由环境注入）
    private val tokenSecret = (System.getenv("TOKEN_SECRET") ?: "mall-renovation-demo-secret-2026").toByteArray()

    fun hashPassword(password: String): Pair<String, String> {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val dk = pbkdf2(password.toCharArray(), salt)
        return Base64.getEncoder().encodeToString(dk) to Base64.getEncoder().encodeToString(salt)
    }

    fun verifyPassword(password: String, saltB64: String, hashB64: String): Boolean {
        val salt = Base64.getDecoder().decode(saltB64)
        val dk = pbkdf2(password.toCharArray(), salt)
        return MessageDigest.isEqual(dk, Base64.getDecoder().decode(hashB64))
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray =
        javax.crypto.spec.PBEKeySpec(password, salt, ITER, KEY_LEN).let { spec ->
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }

    // 简易 HMAC 签名令牌： base64(payload).hmac
    fun issueToken(userId: Long, role: String): String {
        val payload = "$userId:$role:${System.currentTimeMillis() + 24 * 3600_000}"
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "$body.${hmac(body)}"
    }

    fun parseToken(token: String): Pair<Long, String>? {
        val parts = token.split(".")
        if (parts.size != 2) return null
        if (!MessageDigest.isEqual(hmac(parts[0]).toByteArray(), parts[1].toByteArray())) return null
        val payload = String(Base64.getUrlDecoder().decode(parts[0]))
        val seg = payload.split(":")
        if (seg.size != 3) return null
        val expiry = seg[2].toLongOrNull() ?: return null
        if (expiry < System.currentTimeMillis()) return null
        return seg[0].toLongOrNull()?.let { it to seg[1] }
    }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenSecret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray()))
    }
}
