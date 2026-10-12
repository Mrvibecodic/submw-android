package app.svc.core.a1

import app.svc.core.crypto.B64
import app.svc.core.crypto.ChaChaPoly
import app.svc.core.crypto.Hkdf
import app.svc.core.crypto.X25519
import java.nio.ByteBuffer
import java.security.SecureRandom

object A1 {
    const val SALT = "submw-a1"
    const val PAIR_SALT = "submw-a1-pair"
    const val REQ_SMALL = 512
    const val REQ_LIMIT = 4096
    const val BLOCK = 3072
    private const val ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val ZERO12 = ByteArray(12)
    private val rnd = SecureRandom()

    class Bad(msg: String) : Exception(msg)

    class Msg(val f: Map<String, Any?>, val body: ByteArray) {
        val st: Int get() = f.int("st")
    }

    fun utf(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun nonce(): String = B64.enc(ByteArray(16).also { rnd.nextBytes(it) })

    fun epoch(nowSec: Long = System.currentTimeMillis() / 1000): Long = Math.floorDiv(nowSec, 86400L)

    fun kdev(shared: ByteArray, did: ByteArray): ByteArray = Hkdf.derive(shared, utf(SALT), utf("dev") + did)

    fun kid(kdev: ByteArray, epoch: Long): String = B64.enc(Hkdf.hmac(kdev, utf("kid|$epoch")).copyOf(9))

    fun spid(pub: ByteArray): String = B64.enc(Hkdf.sha256(pub)).substring(0, 6)

    fun normCode(s: String): String? {
        val c = s.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; else -> it } }.joinToString("")
        return if (c.length == 12 && c.all { it in ABC }) c else null
    }

    fun frame(json: ByteArray, body: ByteArray, block: Int): ByteArray {
        val raw = 8 + json.size + body.size
        val pad = (block - ((32 + raw + 16) % block)) % block
        val bb = ByteBuffer.allocate(raw + pad)
        bb.putInt(json.size).put(json).putInt(body.size).put(body)
        return bb.array()
    }

    fun reqBlock(raw: Int): Int = if (raw <= REQ_LIMIT) REQ_SMALL else BLOCK

    fun parseFrame(plain: ByteArray): Msg {
        if (plain.size < 8) throw Bad("frame")
        val bb = ByteBuffer.wrap(plain)
        val jl = bb.int
        if (jl < 2 || 8L + jl > plain.size) throw Bad("frame")
        val json = String(plain, 4, jl, Charsets.UTF_8)
        bb.position(4 + jl)
        val bl = bb.int
        if (bl < 0 || 8L + jl + bl > plain.size) throw Bad("frame")
        val body = plain.copyOfRange(8 + jl, 8 + jl + bl)
        return Msg(Json.obj(json), body)
    }

    class Sealed(val wire: String, val ephSecret: ByteArray, val ephPublic: ByteArray, val nonce: String)

    fun head(op: String, fields: Map<String, Any?>): Pair<String, LinkedHashMap<String, Any?>> {
        val n = nonce()
        val m = LinkedHashMap<String, Any?>()
        m["v"] = 1
        m["t"] = System.currentTimeMillis() / 1000
        m["n"] = n
        m["op"] = op
        m.putAll(fields)
        return n to m
    }

    fun sealRequest(kdev: ByteArray, kid: String, spPublic: ByteArray, op: String, fields: Map<String, Any?>, body: ByteArray, ephSecret: ByteArray = X25519.newSecret()): Sealed {
        val (n, m) = head(op, fields)
        return sealRaw(kdev, kid, spPublic, utf(Json.write(m)), body, ephSecret, n)
    }

    fun sealRaw(kdev: ByteArray, kid: String, spPublic: ByteArray, json: ByteArray, body: ByteArray, ephSecret: ByteArray, n: String = ""): Sealed {
        val ephPublic = X25519.publicKey(ephSecret)
        val key = Hkdf.derive(kdev + X25519.shared(ephSecret, spPublic), utf(kid), utf("req") + ephPublic)
        val plain = frame(json, body, reqBlock(32 + 8 + json.size + body.size + 16))
        val ct = ChaChaPoly.seal(key, ZERO12, plain, utf("a1") + utf(kid) + ephPublic)
        return Sealed(B64.enc(ephPublic + ct), ephSecret, ephPublic, n)
    }

    fun sealPair(code: String, fields: Map<String, Any?>, ephSecret: ByteArray = X25519.newSecret()): Sealed {
        val (n, m) = head("pair", fields)
        return sealPairRaw(code, utf(Json.write(m)), ephSecret, n)
    }

    fun sealPairRaw(code: String, json: ByteArray, ephSecret: ByteArray, n: String = ""): Sealed {
        val ephPublic = X25519.publicKey(ephSecret)
        val key = Hkdf.derive(utf(code), utf(PAIR_SALT), utf("req") + ephPublic)
        val plain = frame(json, ByteArray(0), reqBlock(32 + 8 + json.size + 16))
        val ct = ChaChaPoly.seal(key, ZERO12, plain, utf("a1p") + ephPublic)
        return Sealed(B64.enc(ephPublic + ct), ephSecret, ephPublic, n)
    }

    private fun raw(wire: String): ByteArray {
        val raw = B64.dec(wire.trim()) ?: throw Bad("b64")
        if (raw.size < 32 + 16 + 8) throw Bad("short")
        return raw
    }

    fun openResponse(wire: String, s: Sealed, kdev: ByteArray, kid: String): Msg {
        val raw = raw(wire)
        val srv = raw.copyOf(32)
        val key = Hkdf.derive(kdev + X25519.shared(s.ephSecret, srv), utf(kid), utf("res") + s.ephPublic)
        val plain = ChaChaPoly.open(key, ZERO12, raw, 32, raw.size - 32, utf("a1r") + utf(kid) + s.ephPublic + srv)
        return check(parseFrame(plain), s)
    }

    fun openPair(wire: String, s: Sealed, code: String): Msg {
        val raw = raw(wire)
        val srv = raw.copyOf(32)
        val key = Hkdf.derive(utf(code) + X25519.shared(s.ephSecret, srv), utf(PAIR_SALT), utf("res") + s.ephPublic)
        val plain = ChaChaPoly.open(key, ZERO12, raw, 32, raw.size - 32, utf("a1pr") + s.ephPublic + srv)
        return check(parseFrame(plain), s)
    }

    private fun check(m: Msg, s: Sealed): Msg {
        if (m.f.int("v") != 1) throw Bad("version")
        if (s.nonce.isNotEmpty() && m.f.str("n") != s.nonce) throw Bad("nonce")
        return m
    }
}

class Device(val secret: ByteArray, val did: String, var sp: String) {
    val public: ByteArray get() = X25519.publicKey(secret)

    fun spPublic(): ByteArray = B64.dec(sp) ?: throw A1.Bad("sp")

    fun kdev(): ByteArray = A1.kdev(X25519.shared(secret, spPublic()), B64.dec(did) ?: throw A1.Bad("did"))
}
