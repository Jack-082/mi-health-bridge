package io.github.mihealthbridge.mi

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 小米健康云请求加密原语（移植自 HealthMi / mi-fitness-mcp-cn）。
 *
 * - signed_nonce = SHA256(ssecurity || nonce)，作为 RC4 密钥
 * - RC4 丢弃前 1024 字节密钥流
 * - 签名 = Base64(SHA1("POST&path&data=..[&rc4_hash__=..]&b64(signed_nonce)"))
 */
object MiCrypto {
    private val random = SecureRandom()

    fun rc4(key: ByteArray, payload: ByteArray): ByteArray {
        if (key.isEmpty()) return payload.copyOf()
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        fun next(): Int {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            return s[(s[i] + s[j]) and 0xFF]
        }
        repeat(1024) { next() }
        return ByteArray(payload.size) { idx -> ((payload[idx].toInt() and 0xFF) xor next()).toByte() }
    }

    /** 8 字节随机数 + 4 字节大端的“当前分钟数”。 */
    fun genNonce(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val rand = ByteArray(8).also { random.nextBytes(it) }
        val minutes = (nowMillis / 60_000L).toInt()
        return rand + ByteBuffer.allocate(4).putInt(minutes).array()
    }

    fun signedNonce(ssecurity: ByteArray, nonce: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(ssecurity + nonce)

    fun signature(method: String, path: String, data: String, rc4Hash: String?, signedNonce: ByteArray): String {
        val sb = StringBuilder().append(method).append('&').append(path).append("&data=").append(data)
        if (rc4Hash != null) sb.append("&rc4_hash__=").append(rc4Hash)
        sb.append('&').append(b64(signedNonce))
        val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return b64(digest)
    }

    /** 生成加密后的表单字段：data / rc4_hash__ / signature / _nonce。 */
    fun buildForm(path: String, data: String, signedNonce: ByteArray, nonce: ByteArray): Map<String, String> {
        val rc4Hash = signature("POST", path, data, null, signedNonce)
        val encData = b64(rc4(signedNonce, data.toByteArray(Charsets.UTF_8)))
        val encHash = b64(rc4(signedNonce, rc4Hash.toByteArray(Charsets.UTF_8)))
        val sig = signature("POST", path, encData, encHash, signedNonce)
        return linkedMapOf(
            "data" to encData,
            "rc4_hash__" to encHash,
            "signature" to sig,
            "_nonce" to b64(nonce),
        )
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())
}
