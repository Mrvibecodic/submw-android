package app.svc.core

import app.svc.core.a1.Client
import app.svc.core.a1.str
import app.svc.core.web.LocalServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalServerTest {
    private fun req(srv: LocalServer, path: String, method: String = "GET", body: ByteArray? = null, type: String? = null, auth: Boolean = true, extra: Map<String, String> = emptyMap()): Triple<Int, Map<String, List<String>>, ByteArray> {
        val c = URL(srv.origin + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        if (auth) c.setRequestProperty("Cookie", "a1l=" + srv.secret + "; submw_ui=c_x:1")
        for ((k, v) in extra) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            if (type != null) c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val b = try { c.inputStream.readBytes() } catch (e: Exception) { c.errorStream?.readBytes() ?: ByteArray(0) }
        return Triple(code, c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }, b)
    }

    @Test
    fun throughTunnel() {
        val base = System.getenv("A1_BASE")
        val code = System.getenv("A1_CODE2")
        Assume.assumeTrue(base != null && code != null)
        val dev = Client(base!!, null).pair(code!!, mapOf("name" to "JVM web"))
        val c = Client(base, dev)
        val ck = c.call("login", mapOf("u" to "admin", "p" to "secretpass")).f.str("ck")
        var lost = 0
        val srv = LocalServer(c, ck) { lost++ }.start()
        try {
            assertEquals(403, req(srv, "/admin/index.php", auth = false).first)
            val root = req(srv, "/")
            assertEquals(302, root.first)
            val page = req(srv, "/admin/index.php?tab=app")
            assertEquals(200, page.first)
            val html = String(page.third)
            assertTrue(html.contains("Админка в телефоне"))
            val css = req(srv, "/admin/assets/admin.css")
            assertEquals(200, css.first)
            val etag = css.second["etag"]!!.first()
            assertEquals(304, req(srv, "/admin/assets/admin.css", extra = mapOf("If-None-Match" to etag)).first)
            val csrf = Regex("name=\"csrf\" value=\"([0-9a-f]+)\"").find(html)!!.groupValues[1]
            val form = "csrf=$csrf&action=save_app&app_enabled=on&app_ev_wait_max=150&xhr=1".toByteArray()
            val saved = req(srv, "/admin/index.php", "POST", form, "application/x-www-form-urlencoded")
            assertEquals(200, saved.first)
            assertTrue(String(saved.third).contains("\"ok\":true"))
            val b = "----j1"
            val mp = ("--$b\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n$csrf\r\n--$b\r\nContent-Disposition: form-data; name=\"action\"\r\n\r\nbatch_wg_config\r\n" +
                "--$b\r\nContent-Disposition: form-data; name=\"squads[]\"\r\n\r\nsq-2\r\n--$b\r\nContent-Disposition: form-data; name=\"conf_files[]\"; filename=\"w.conf\"\r\nContent-Type: text/plain\r\n\r\n" +
                "[Interface]\nPrivateKey = aGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Qga2V5MTI=\nAddress = 10.0.0.3/32\n\n[Peer]\nPublicKey = aGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Qga2V5MTI=\nEndpoint = 1.2.3.5:51820\nAllowedIPs = 0.0.0.0/0\n\r\n--$b--\r\n").toByteArray()
            val up = req(srv, "/admin/index.php", "POST", mp, "multipart/form-data; boundary=$b")
            assertEquals(302, up.first)
            assertEquals("index.php?tab=wg_pool", up.second["location"]!!.first())
            val pool = String(req(srv, "/admin/index.php?tab=wg_pool").third)
            assertTrue(pool.contains("Добавлено WG/AWG: 1"))
            val out = req(srv, "/admin/index.php?logout=1&csrf=$csrf")
            assertEquals(200, out.first)
            assertEquals(1, lost)
            assertTrue(!srv.hasSession())
        } finally {
            srv.stop()
            c.call("bye")
        }
    }
}
