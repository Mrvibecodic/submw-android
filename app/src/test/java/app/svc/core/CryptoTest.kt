package app.svc.core

import app.svc.core.a1.Json
import app.svc.core.crypto.ChaChaPoly
import app.svc.core.crypto.Hkdf
import app.svc.core.crypto.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun res(name: String): String = CryptoTest::class.java.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

class CryptoTest {
    @Test
    fun x25519Rfc7748() {
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.publicKey(a))
        assertArrayEquals(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.publicKey(b))
        val k = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(k, X25519.shared(a, X25519.publicKey(b)))
        assertArrayEquals(k, X25519.shared(b, X25519.publicKey(a)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun x25519LowOrder() {
        X25519.shared(X25519.newSecret(), ByteArray(32))
    }

    @Test
    fun poly1305Rfc8439() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertArrayEquals(hex("a8061dc1305136c6c22b8baf0c0127a9"), ChaChaPoly.poly1305(key, "Cryptographic Forum Research Group".toByteArray()))
    }

    @Test
    fun aeadRfc8439() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val ct = ChaChaPoly.seal(key, nonce, pt, aad)
        assertArrayEquals(hex("1ae10b594f09e26a7e902ecbd0600691"), ct.copyOfRange(ct.size - 16, ct.size))
        assertArrayEquals(hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"), ct.copyOf(32))
        assertArrayEquals(pt, ChaChaPoly.open(key, nonce, ct, 0, ct.size, aad))
    }

    @Test(expected = ChaChaPoly.AuthException::class)
    fun aeadTamper() {
        val key = ByteArray(32)
        val ct = ChaChaPoly.seal(key, ByteArray(12), ByteArray(40), ByteArray(0))
        ct[3] = (ct[3].toInt() xor 1).toByte()
        ChaChaPoly.open(key, ByteArray(12), ct, 0, ct.size, ByteArray(0))
    }

    @Test
    fun hkdfRfc5869() {
        val okm = Hkdf.derive(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun crossCheckWithSodium() {
        val v = Json.obj(res("crypto_vectors.json"))
        var n = 0
        for (x in v["x25519"] as List<List<String>>) {
            assertArrayEquals(hex(x[1]), X25519.publicKey(hex(x[0])))
            assertArrayEquals(hex(x[3]), X25519.shared(hex(x[0]), hex(x[2])))
            n++
        }
        for (x in v["aead"] as List<List<String>>) {
            val ct = ChaChaPoly.seal(hex(x[0]), hex(x[1]), hex(x[3]), hex(x[2]))
            assertArrayEquals(hex(x[4]), ct)
            assertArrayEquals(hex(x[3]), ChaChaPoly.open(hex(x[0]), hex(x[1]), ct, 0, ct.size, hex(x[2])))
            n++
        }
        for (x in v["hkdf"] as List<List<String>>) {
            assertArrayEquals(hex(x[3]), Hkdf.derive(hex(x[0]), hex(x[1]), hex(x[2])))
            n++
        }
        assertTrue(n > 50)
    }

    @Test
    fun json() {
        val s = """{"a":1,"b":[true,false,null,"x\"y\\zЖ"],"c":{"d":-2.5e1},"e":"Тест"}"""
        val m = Json.obj(s)
        assertEquals(1L, m["a"])
        assertEquals("x\"y\\zЖ", (m["b"] as List<*>)[3])
        assertEquals(-25.0, (m["c"] as Map<*, *>)["d"])
        assertEquals(m, Json.obj(Json.write(m)))
    }
}
