package app.svc.core.crypto

import java.security.GeneralSecurityException

object ChaChaPoly {
    class AuthException : GeneralSecurityException("aead")

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte()
        b[o + 3] = (v ushr 24).toByte()
    }

    private fun block(key: ByteArray, counter: Int, nonce: ByteArray, out: ByteArray) {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        s[13] = le32(nonce, 0)
        s[14] = le32(nonce, 4)
        s[15] = le32(nonce, 8)
        val x = s.copyOf()
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) put32(out, i * 4, x[i] + s[i])
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray, from: Int, len: Int, out: ByteArray, at: Int) {
        val ks = ByteArray(64)
        var ctr = counter
        var i = 0
        while (i < len) {
            block(key, ctr++, nonce, ks)
            val n = minOf(64, len - i)
            for (j in 0 until n) out[at + i + j] = (data[from + i + j].toInt() xor ks[j].toInt()).toByte()
            i += n
        }
    }

    fun poly1305(key: ByteArray, msg: ByteArray, len: Int = msg.size): ByteArray {
        val m26 = 0x3ffffffL
        val r0 = le32(key, 0).toLong() and 0x3ffffff
        val r1 = (le32(key, 3).toLong() and 0xffffffffL ushr 2) and 0x3ffff03
        val r2 = (le32(key, 6).toLong() and 0xffffffffL ushr 4) and 0x3ffc0ff
        val r3 = (le32(key, 9).toLong() and 0xffffffffL ushr 6) and 0x3f03fff
        val r4 = (le32(key, 12).toLong() and 0xffffffffL ushr 8) and 0x00fffff
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val buf = ByteArray(17)
        var off = 0
        while (off < len) {
            val n = minOf(16, len - off)
            val hibit: Long
            if (n == 16) {
                System.arraycopy(msg, off, buf, 0, 16)
                hibit = 1L shl 24
            } else {
                java.util.Arrays.fill(buf, 0.toByte())
                System.arraycopy(msg, off, buf, 0, n)
                buf[n] = 1
                hibit = 0
            }
            h0 += le32(buf, 0).toLong() and 0xffffffffL and m26
            h1 += (le32(buf, 3).toLong() and 0xffffffffL ushr 2) and m26
            h2 += (le32(buf, 6).toLong() and 0xffffffffL ushr 4) and m26
            h3 += (le32(buf, 9).toLong() and 0xffffffffL ushr 6) and m26
            h4 += (le32(buf, 12).toLong() and 0xffffffffL ushr 8) or hibit
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and m26
            d1 += c; c = d1 ushr 26; h1 = d1 and m26
            d2 += c; c = d2 ushr 26; h2 = d2 and m26
            d3 += c; c = d3 ushr 26; h3 = d3 and m26
            d4 += c; c = d4 ushr 26; h4 = d4 and m26
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
            h1 += c
            off += n
        }
        var c = h1 ushr 26; h1 = h1 and m26
        h2 += c; c = h2 ushr 26; h2 = h2 and m26
        h3 += c; c = h3 ushr 26; h3 = h3 and m26
        h4 += c; c = h4 ushr 26; h4 = h4 and m26
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
        h1 += c
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and m26
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and m26
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and m26
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and m26
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }
        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = w0 + (le32(key, 16).toLong() and 0xffffffffL)
        val out = ByteArray(16)
        put32(out, 0, f.toInt())
        f = w1 + (le32(key, 20).toLong() and 0xffffffffL) + (f ushr 32)
        put32(out, 4, f.toInt())
        f = w2 + (le32(key, 24).toLong() and 0xffffffffL) + (f ushr 32)
        put32(out, 8, f.toInt())
        f = w3 + (le32(key, 28).toLong() and 0xffffffffL) + (f ushr 32)
        put32(out, 12, f.toInt())
        return out
    }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray, ctOff: Int, ctLen: Int): ByteArray {
        val pk = ByteArray(64)
        block(key, 0, nonce, pk)
        val p16 = { n: Int -> (16 - n % 16) % 16 }
        val total = aad.size + p16(aad.size) + ctLen + p16(ctLen) + 16
        val mac = ByteArray(total)
        System.arraycopy(aad, 0, mac, 0, aad.size)
        var o = aad.size + p16(aad.size)
        System.arraycopy(ct, ctOff, mac, o, ctLen)
        o += ctLen + p16(ctLen)
        put64(mac, o, aad.size.toLong())
        put64(mac, o + 8, ctLen.toLong())
        return poly1305(pk.copyOf(32), mac)
    }

    private fun put64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val out = ByteArray(plain.size + 16)
        chacha20(key, nonce, 1, plain, 0, plain.size, out, 0)
        val t = tag(key, nonce, aad, out, 0, plain.size)
        System.arraycopy(t, 0, out, plain.size, 16)
        return out
    }

    fun open(key: ByteArray, nonce: ByteArray, data: ByteArray, off: Int, len: Int, aad: ByteArray): ByteArray {
        if (len < 16) throw AuthException()
        val ctLen = len - 16
        val t = tag(key, nonce, aad, data, off, ctLen)
        var diff = 0
        for (i in 0 until 16) diff = diff or (t[i].toInt() xor data[off + ctLen + i].toInt())
        if (diff != 0) throw AuthException()
        val out = ByteArray(ctLen)
        chacha20(key, nonce, 1, data, off, ctLen, out, 0)
        return out
    }
}
