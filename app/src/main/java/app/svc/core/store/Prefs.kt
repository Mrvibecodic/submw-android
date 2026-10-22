package app.svc.core.store

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("p", Context.MODE_PRIVATE)

    companion object {
        const val PUSH_OFF = 0
        const val PUSH_LIVE = 1
        const val PUSH_ECO = 2
        const val ENTRY_HOST = "host"
        const val ENTRY_LOGIN = "login"
        val LOCK_MIN = listOf(5, 10, 30, 60, 0)
        val GROUPS = listOf("chat", "rep", "chan_new", "wh")
        val DEFAULT_TEXT = mapOf(
            "chat" to "Новое сообщение",
            "rep" to "Новый отчёт",
            "chan_new" to "Новый абонент",
            "wh" to "Событие",
            "fg" to "Работает",
        )
        val DEFAULT_LABEL = mapOf(
            "title" to "Вход",
            "host" to "Адрес сервера",
            "code" to "Код",
            "user" to "Логин",
            "pass" to "Пароль",
            "go" to "Продолжить",
        )
    }

    var push: Int
        get() = sp.getInt("push", PUSH_OFF)
        set(v) = sp.edit().putInt("push", v).apply()

    var groups: Set<String>
        get() = sp.getStringSet("groups", setOf("chat", "rep", "chan_new"))!!.toSet()
        set(v) = sp.edit().putStringSet("groups", v).apply()

    var hooks: Set<String>
        get() = sp.getStringSet("hooks", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hooks", v).apply()

    var knownHooks: List<String>
        get() = (sp.getString("known", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("known", v.joinToString("\n")).apply()

    var evw: Int
        get() = sp.getInt("evw", 280)
        set(v) = sp.edit().putInt("evw", v).apply()

    var cursor: Long
        get() = sp.getLong("cur", -1)
        set(v) = sp.edit().putLong("cur", v).apply()

    fun ceiling(net: String): Int = sp.getInt("ceil_$net", 0)

    fun setCeiling(net: String, v: Int) = sp.edit().putInt("ceil_$net", v).apply()

    fun text(k: String): String = sp.getString("t_$k", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT[k] ?: k

    fun custom(k: String): String? = sp.getString("t_$k", null)?.takeIf { it.isNotBlank() }

    fun setText(k: String, v: String) {
        val t = v.trim()
        if (t.isEmpty()) sp.edit().remove("t_$k").apply() else sp.edit().putString("t_$k", t).apply()
    }

    var lockMin: Int
        get() = sp.getInt("lock", 5).takeIf { it in LOCK_MIN } ?: 5
        set(v) = sp.edit().putInt("lock", v).apply()

    var entry: String
        get() = if (sp.getString("entry", ENTRY_HOST) == ENTRY_LOGIN) ENTRY_LOGIN else ENTRY_HOST
        set(v) = sp.edit().putString("entry", v).apply()

    fun label(k: String): String = sp.getString("l_$k", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL[k] ?: k

    fun setLabel(k: String, v: String) = sp.edit().putString("l_$k", v.trim()).apply()

    var mask: String
        get() = sp.getString("mask", "Default") ?: "Default"
        set(v) = sp.edit().putString("mask", v).apply()

    var hideNames: Boolean
        get() = sp.getBoolean("hide", false)
        set(v) = sp.edit().putBoolean("hide", v).apply()

    var secureScreen: Boolean
        get() = sp.getBoolean("secure", true)
        set(v) = sp.edit().putBoolean("secure", v).apply()

    var lastApk: String
        get() = sp.getString("apk", "") ?: ""
        set(v) = sp.edit().putString("apk", v).apply()

    fun wanted(): List<String> {
        val g = groups
        val out = ArrayList<String>()
        if ("chat" in g) out.add("chat")
        if ("rep" in g) out.add("rep")
        if ("chan_new" in g) out.add("chan_new")
        if ("wh" in g) {
            val h = hooks
            if (h.isEmpty()) out.add("wh:*") else h.forEach { out.add("wh:$it") }
        }
        return out
    }
}
