package app.svc.core

import app.svc.core.a1.A1
import app.svc.core.a1.Client
import app.svc.core.a1.Device
import app.svc.core.a1.Json
import app.svc.core.a1.int
import app.svc.core.a1.list
import app.svc.core.a1.map
import app.svc.core.a1.str
import app.svc.core.crypto.B64
import app.svc.core.crypto.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.Base64

class A1Test {
    private val v = Json.obj(res("app_vectors.json"))
    private val req = v.map("request")
    private val pair = v.map("pair")

    @Test
    fun keysAndLabels() {
        val dev = hex(v.str("dev_secret"))
        val sp = hex(v.str("sp_public"))
        assertArrayEquals(hex(v.str("dev_public")), X25519.publicKey(dev))
        assertArrayEquals(hex(v.str("shared")), X25519.shared(dev, sp))
        val d = Device(dev, v.str("did"), B64.enc(sp))
        assertArrayEquals(hex(v.str("kdev")), d.kdev())
        assertEquals(v.str("kid"), A1.kid(d.kdev(), v.int("epoch").toLong()))
        assertEquals(v.str("spid"), A1.spid(sp))
    }

    @Test
    fun requestsMatchServer() {
        val kdev = hex(v.str("kdev"))
        val sp = hex(v.str("sp_public"))
        val eph = hex(v.str("eph_secret"))
        val kid = v.str("kid")
        assertEquals(req.str("hi_wire"), A1.sealRaw(kdev, kid, sp, A1.utf(req.str("hi_json")), ByteArray(0), eph).wire)
        val body = Base64.getDecoder().decode(req.str("web_body"))
        assertEquals(req.str("web_wire"), A1.sealRaw(kdev, kid, sp, A1.utf(req.str("web_json")), body, eph).wire)
        val big = Base64.getDecoder().decode(req.str("big_body"))
        assertEquals(req.str("big_wire"), A1.sealRaw(kdev, kid, sp, A1.utf(req.str("web_json")), big, eph).wire)
    }

    @Test
    fun responseOpens() {
        val kdev = hex(v.str("kdev"))
        val eph = hex(v.str("eph_secret"))
        val s = A1.Sealed("", eph, X25519.publicKey(eph), "")
        val r = v.map("response")
        val m = A1.openResponse(r.str("wire"), s, kdev, v.str("kid"))
        assertEquals(200, m.st)
        assertEquals("Тест", m.f.str("x"))
        assertArrayEquals(Base64.getDecoder().decode(r.str("body")), m.body)
        assertEquals(0, B64.dec(r.str("wire"))!!.size % 3072)
    }

    @Test
    fun pairing() {
        val code = A1.normCode(pair.str("code_shown"))!!
        assertEquals(pair.str("code"), code)
        val eph = hex(pair.str("eph_secret"))
        assertEquals(pair.str("wire"), A1.sealPairRaw(code, A1.utf(pair.str("json")), eph).wire)
        val m = A1.openPair(pair.str("res_wire"), A1.Sealed("", eph, X25519.publicKey(eph), ""), code)
        assertEquals(v.str("did"), m.f.str("did"))
    }

    @Test
    fun codes() {
        assertEquals("101010101010", A1.normCode("iolo-IOIO lOlO"))
        assertNull(A1.normCode("UUUU-UUUU-UUUU"))
        assertNull(A1.normCode("ABC"))
    }

    @Test
    fun padding() {
        for (n in listOf(0, 1, 100, 400, 4000, 5000, 9000)) {
            val f = A1.frame(ByteArray(10), ByteArray(n), A1.reqBlock(32 + 8 + 10 + n + 16))
            val total = 32 + f.size + 16
            assertEquals(0, total % (if (32 + 8 + 10 + n + 16 <= 4096) 512 else 3072))
        }
    }

    @Test
    fun liveStand() {
        val base = System.getenv("A1_BASE")
        val code = System.getenv("A1_CODE")
        Assume.assumeTrue(base != null && code != null)
        val dev = Client(base!!, null).pair(code!!, mapOf("name" to "JVM", "os" to "android", "osv" to "15", "app" to "0.1.0"))
        val c = Client(base, dev)
        val hi = c.call("hi", mapOf("name" to "JVM test", "os" to "android", "osv" to "15", "app" to "0.1.0"))
        assertEquals(200, hi.st)
        assertTrue(hi.f.list("ev").isNotEmpty())
        assertEquals(401, c.call("login", mapOf("u" to "admin", "p" to "nope")).st)
        val ok = c.call("login", mapOf("u" to "admin", "p" to (System.getenv("A1_PASS") ?: "secretpass")))
        assertEquals(200, ok.st)
        val ck = ok.f.str("ck")
        val w = c.call("web", mapOf("m" to "GET", "p" to "/admin/index.php?tab=app", "h" to mapOf("cookie" to ck)))
        assertEquals(200, w.st)
        assertEquals(true, w.f["auth"])
        assertTrue(String(w.body).contains("Админка в телефоне"))
        val e = c.call("ev", mapOf("c" to -1, "w" to 0, "k" to listOf("chat")))
        assertEquals(200, e.st)
        assertEquals(200, c.call("bye").st)
    }
}
