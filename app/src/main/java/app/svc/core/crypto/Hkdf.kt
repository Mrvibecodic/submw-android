package app.svc.core.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

object B64 {
    private val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    private val dec = java.util.Base64.getUrlDecoder()

    fun enc(b: ByteArray): String = enc.encodeToString(b)

    fun dec(s: String): ByteArray? {
        if (s.isEmpty() || s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        return try { dec.decode(s) } catch (e: IllegalArgumentException) { null }
    }
}
