package app.svc.core

import app.svc.core.push.Notifier
import app.svc.core.web.LocalServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeTest {
    @Test
    fun ownTextHidesEventName() {
        assertEquals("Сервер упал" to null, Notifier.shade("wh:node.connection_lost", "Сервер упал", "Событие", false))
        assertEquals("Привет" to null, Notifier.shade("chat", "Привет", "Новое сообщение", false))
    }

    @Test
    fun defaultTextKeepsEventName() {
        assertEquals("Событие" to "user.expired", Notifier.shade("wh:user.expired", null, "Событие", false))
        assertEquals("Событие" to null, Notifier.shade("wh:user.expired", null, "Событие", true))
        val (t, b) = Notifier.shade("rep", null, "Новый отчёт", false)
        assertEquals("Новый отчёт", t)
        assertNull(b)
    }

    @Test
    fun appStyleGoesIntoHead() {
        val html = "<!doctype html><html><head><title>Пользователи</title></head><body>ё</body></html>".toByteArray()
        val out = String(LocalServer.inApp(html), Charsets.UTF_8)
        assertTrue(out.contains(".navtoggle,.rw-out{display:none!important}</style></head>"))
        assertTrue(out.endsWith("<body>ё</body></html>"))
        val bare = "{\"ok\":true}".toByteArray()
        assertArrayEquals(bare, LocalServer.inApp(bare))
    }
}
