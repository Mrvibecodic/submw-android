package app.svc.core.a1

import app.svc.core.crypto.B64
import app.svc.core.crypto.X25519
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Client(base: String, private val dev: Device?, private val onKey: (String) -> Unit = {}) {
    class Silent(msg: String) : IOException(msg)

    val base: String = normBase(base)

    @Volatile
    private var live: HttpURLConnection? = null

    fun abort() {
        try { live?.disconnect() } catch (_: Exception) {}
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

        fun normBase(s: String): String {
            var t = s.trim().trimEnd('/')
            if (t.isEmpty()) return t
            if (!t.startsWith("https://") && !t.startsWith("http://")) t = "https://$t"
            return t
        }
    }

    fun pair(code: String, card: Map<String, Any?>): Device {
        val c = A1.normCode(code) ?: throw IllegalArgumentException("code")
        val secret = X25519.newSecret()
        val fields = LinkedHashMap<String, Any?>()
        fields["dpub"] = B64.enc(X25519.publicKey(secret))
        fields.putAll(card)
        val s = A1.sealPair(c, fields)
        val wire = post("/a1/0/0", s.wire, 30_000)
        val m = try { A1.openPair(wire, s, c) } catch (e: Exception) { throw Silent("pair") }
        val did = m.f.str("did")
        val sp = m.f.str("sp")
        if (m.st != 200 || did.isEmpty() || B64.dec(sp)?.size != 32) throw Silent("pair")
        return Device(secret, did, sp)
    }

    fun call(op: String, fields: Map<String, Any?> = emptyMap(), body: ByteArray = ByteArray(0), readMs: Int = 60_000): A1.Msg {
        val d = dev ?: throw IllegalStateException("no device")
        val kdev = d.kdev()
        val kid = A1.kid(kdev, A1.epoch())
        val s = A1.sealRequest(kdev, kid, d.spPublic(), op, fields, body)
        val wire = post("/a1/" + kid + "/" + A1.spid(d.spPublic()), s.wire, readMs)
        val m = try { A1.openResponse(wire, s, kdev, kid) } catch (e: Exception) { throw Silent(op) }
        val sp = m.f.str("sp")
        if (sp.isNotEmpty() && sp != d.sp && B64.dec(sp)?.size == 32) {
            d.sp = sp
            onKey(sp)
        }
        return m
    }

    private fun post(path: String, wire: String, readMs: Int): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        live = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            val out = wire.toByteArray(Charsets.US_ASCII)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = c.responseCode
            if (code != 200) {
                try { (c.errorStream ?: c.inputStream)?.close() } catch (_: Exception) {}
                throw Silent("http $code")
            }
            val ct = c.contentType ?: ""
            if (!ct.startsWith("application/octet-stream")) throw Silent("type")
            val bo = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(16384)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 96L * 1024 * 1024) throw Silent("big")
                    bo.write(buf, 0, n)
                }
            }
            return String(bo.toByteArray(), Charsets.US_ASCII)
        } finally {
            live = null
            c.disconnect()
        }
    }
}
