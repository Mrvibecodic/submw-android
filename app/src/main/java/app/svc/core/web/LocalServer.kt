package app.svc.core.web

import app.svc.core.a1.A1
import app.svc.core.a1.Client
import app.svc.core.crypto.B64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

class LocalServer(private val client: Client, cookie: String, private val onAuthLost: () -> Unit) {
    class Res(val st: Int, val headers: List<Pair<String, String>>, val body: ByteArray, val auth: Boolean?)

    val secret: String = B64.enc(ByteArray(24).also { SecureRandom().nextBytes(it) })
    private val sessName = cookie.substringBefore('=')

    @Volatile
    var session: String = cookie

    private val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(6)

    @Volatile
    private var running = true

    val port: Int get() = ss.localPort
    val origin: String get() = "http://127.0.0.1:$port"

    private val pass = setOf("accept", "accept-language", "content-type", "user-agent", "x-requested-with", "if-none-match", "if-modified-since", "cookie")

    fun start(): LocalServer {
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (!running) break else continue }
                pool.execute { serve(s) }
            }
        }, "a1-local").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        try { ss.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    fun fetch(method: String, path: String, headers: Map<String, String>, body: ByteArray): Res {
        val h = LinkedHashMap<String, String>()
        for ((k, v) in headers) if (k in pass && k != "cookie") h[k] = v
        val jar = ArrayList<String>()
        headers["cookie"]?.split(';')?.map { it.trim() }?.forEach { kv ->
            val name = kv.substringBefore('=')
            if (kv.isNotEmpty() && name != "a1l" && name != sessName) jar.add(kv)
        }
        if (session.isNotEmpty()) jar.add(session)
        if (jar.isNotEmpty()) h["cookie"] = jar.joinToString("; ")
        val m = client.call("web", mapOf("m" to method, "p" to path, "h" to h), body, 120_000)
        val out = ArrayList<Pair<String, String>>()
        for (x in (m.f["h"] as? List<*>) ?: emptyList<Any?>()) {
            val pair = x as? List<*> ?: continue
            val name = (pair.getOrNull(0) as? String)?.lowercase() ?: continue
            var value = pair.getOrNull(1) as? String ?: continue
            if (name in setOf("content-length", "connection", "transfer-encoding", "keep-alive")) continue
            if (name == "set-cookie") {
                val nv = value.substringBefore(';').trim()
                if (nv.substringBefore('=') == sessName) {
                    val gone = nv.substringAfter('=').let { it.isEmpty() || it == "deleted" } || value.contains("Max-Age=0", true)
                    session = if (gone) "" else nv
                    continue
                }
                value = value.split(';').map { it.trim() }.filter { p -> !p.equals("secure", true) && !p.startsWith("domain=", true) }.joinToString("; ")
            }
            if (name == "location") value = local(value)
            out.add(name to value)
        }
        val auth = m.f["auth"] as? Boolean
        return Res(m.st, out, m.body, auth)
    }

    private fun local(loc: String): String {
        val base = client.base
        return if (loc.startsWith(base)) loc.substring(base.length).ifEmpty { "/" } else loc
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30_000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val head = readHead(inp) ?: return
                val lines = head.split("\r\n")
                val rl = lines[0].split(' ')
                if (rl.size < 3) return
                val method = rl[0].uppercase()
                val target = rl[1]
                val h = LinkedHashMap<String, String>()
                for (l in lines.drop(1)) {
                    val i = l.indexOf(':')
                    if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val body = readBody(inp, h) ?: return reply(out, 413, "text/plain", "big".toByteArray())
                val ok = h["cookie"]?.split(';')?.any { it.trim() == "a1l=$secret" } == true
                if (!ok || h["host"] != "127.0.0.1:$port") return reply(out, 403, "text/plain", ByteArray(0))
                if (target == "/" || target == "/admin") return redirect(out, "/admin/index.php")
                if (!target.startsWith("/admin/")) return reply(out, 404, "text/plain", ByteArray(0))
                if (method !in setOf("GET", "POST", "HEAD")) return reply(out, 405, "text/plain", ByteArray(0))
                val r = try {
                    fetch(method, target, h, body)
                } catch (e: Client.Silent) {
                    return reply(out, 502, "text/html; charset=utf-8", page("Сервер не отвечает как обычно. Попробуйте войти заново."))
                } catch (e: IOException) {
                    return reply(out, 504, "text/html; charset=utf-8", page("Нет связи с сервером."))
                } catch (e: A1.Bad) {
                    return reply(out, 502, "text/html; charset=utf-8", page("Ответ сервера не разобран."))
                }
                if (r.auth == false) {
                    session = ""
                    onAuthLost()
                    return reply(out, 200, "text/html; charset=utf-8", page(""))
                }
                val sb = StringBuilder("HTTP/1.1 ${r.st} X\r\n")
                for ((k, v) in r.headers) sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n")
                sb.append("content-length: ").append(if (method == "HEAD") 0 else r.body.size).append("\r\nconnection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                if (method != "HEAD") out.write(r.body)
                out.flush()
            } catch (_: SocketException) {
            } catch (_: IOException) {
            }
        }
    }

    private fun page(msg: String): ByteArray =
        ("<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px sans-serif;padding:24px;color:#8b98b5;background:transparent'>" +
            msg.replace("<", "&lt;") + "</body>").toByteArray(Charsets.UTF_8)

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 302 X\r\nlocation: $to\r\ncontent-length: 0\r\nconnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun reply(out: OutputStream, st: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $st X\r\ncontent-type: $type\r\ncache-control: no-store\r\ncontent-length: ${body.size}\r\nconnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readHead(inp: InputStream): String? {
        val bo = ByteArrayOutputStream()
        var m = 0
        while (bo.size() < 65536) {
            val b = inp.read()
            if (b < 0) return null
            bo.write(b)
            m = when {
                b == '\r'.code && (m == 0 || m == 2) -> m + 1
                b == '\n'.code && (m == 1 || m == 3) -> m + 1
                else -> 0
            }
            if (m == 4) return String(bo.toByteArray(), 0, bo.size() - 4, Charsets.ISO_8859_1)
        }
        return null
    }

    private fun readBody(inp: InputStream, h: Map<String, String>): ByteArray? {
        val max = 8 * 1024 * 1024
        val len = h["content-length"]?.toIntOrNull()
        if (len != null) {
            if (len > max) return null
            val b = ByteArray(len)
            var o = 0
            while (o < len) {
                val n = inp.read(b, o, len - o)
                if (n < 0) break
                o += n
            }
            return b
        }
        if (h["transfer-encoding"]?.contains("chunked", true) == true) {
            val bo = ByteArrayOutputStream()
            while (true) {
                val sz = line(inp).substringBefore(';').trim().toIntOrNull(16) ?: return null
                if (sz == 0) { line(inp); break }
                if (bo.size() + sz > max) return null
                val b = ByteArray(sz)
                var o = 0
                while (o < sz) {
                    val n = inp.read(b, o, sz - o)
                    if (n < 0) return null
                    o += n
                }
                bo.write(b)
                line(inp)
            }
            return bo.toByteArray()
        }
        return ByteArray(0)
    }

    private fun line(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    fun cookieName(): String = sessName

    fun hasSession(): Boolean = session.isNotEmpty()
}
