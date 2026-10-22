package app.svc.core.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.svc.core.BuildConfig
import app.svc.core.R
import app.svc.core.a1.A1
import app.svc.core.a1.Client
import app.svc.core.a1.Device
import app.svc.core.a1.int
import app.svc.core.a1.list
import app.svc.core.a1.str
import app.svc.core.mask.Masks
import app.svc.core.push.Notifier
import app.svc.core.push.Push
import app.svc.core.store.Prefs
import app.svc.core.store.Vault
import app.svc.core.update.Updater
import app.svc.core.web.LocalServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

object S {
    var client: Client? = null
    var server: LocalServer? = null
    var hi: Map<String, Any?> = emptyMap()
    var lockMs = 0L
    var onLock: (() -> Unit)? = null
    private var last = 0L
    private val h = Handler(Looper.getMainLooper())
    private val tick = Runnable { check() }

    fun drop() {
        h.removeCallbacks(tick)
        server?.stop()
        server = null
    }

    fun touch() {
        last = SystemClock.elapsedRealtime()
        arm()
    }

    // elapsedRealtime идёт и во сне телефона, поэтому после пробуждения проверка верна, даже если таймер опоздал.
    fun expired(): Boolean = server != null && lockMs > 0 && SystemClock.elapsedRealtime() - last >= lockMs

    fun arm() {
        h.removeCallbacks(tick)
        if (server == null || lockMs <= 0) return
        h.postDelayed(tick, maxOf(1000L, last + lockMs - SystemClock.elapsedRealtime()))
    }

    private fun check() {
        if (!expired()) return arm()
        val cb = onLock
        if (cb != null) cb() else drop()
    }
}

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var vault: Vault
    private lateinit var prefs: Prefs
    private var web: WebView? = null
    private var pendingTab: String? = null
    private var screen = ""
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var afterUnlock: (() -> Unit)? = null
    private var newCode = false
    private var frame: FrameLayout? = null
    private var setView: View? = null
    private var tabs: List<Ui.Tab> = emptyList()
    private var dlg: AlertDialog? = null
    private val lockCb: () -> Unit = { logout() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        vault = Vault(this)
        prefs = Prefs(this)
        if (prefs.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        S.lockMs = prefs.lockMin * 60_000L
        S.onLock = lockCb
        take(intent)
        if (S.server?.hasSession() == true && !S.expired()) showAdmin() else { S.drop(); entry() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
        val t = pendingTab
        if ((screen == "admin" || screen == "settings") && t != null) {
            if (S.expired()) return logout()
            pendingTab = null
            showAdmin()
            web?.loadUrl(S.server!!.origin + "/admin/index.php?tab=" + t)
        }
    }

    override fun onResume() {
        super.onResume()
        S.onLock = lockCb
        if (S.expired()) logout() else S.arm()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) S.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN) S.touch()
        return super.dispatchKeyEvent(ev)
    }

    private fun take(i: Intent?) {
        i?.getStringExtra("tab")?.let { pendingTab = it }
        i?.getStringExtra("nk")?.let { Notifier.clear(this, it) }
    }

    private fun card(): Map<String, Any?> = mapOf(
        "name" to (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim(),
        "os" to "android",
        "osv" to Build.VERSION.RELEASE,
        "app" to BuildConfig.VERSION_NAME,
    )

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun bg(work: () -> Unit) = Thread { work() }.start()

    private fun page(build: (LinearLayout) -> Unit) {
        dlg?.dismiss()
        dlg = null
        web?.let { it.stopLoading(); it.destroy() }
        web = null
        frame = null
        setView = null
        tabs = emptyList()
        val col = ui.column(24)
        col.gravity = Gravity.CENTER_HORIZONTAL
        build(col)
        val sc = ui.scroll(col)
        sc.fitsSystemWindows = true
        setContentView(sc)
    }

    private fun head(col: LinearLayout, longPress: (() -> Unit)? = null) {
        val m = Masks.current(this)
        val icon = ImageView(this).apply {
            setImageResource(m.launcher)
            layoutParams = LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { topMargin = ui.dp(36); gravity = Gravity.CENTER_HORIZONTAL }
        }
        col.addView(icon)
        val t = ui.title(prefs.label("title"))
        t.gravity = Gravity.CENTER
        if (longPress != null) t.setOnLongClickListener { longPress(); true }
        col.addView(t, ui.lp(18))
    }

    private fun status(col: LinearLayout): TextView {
        val s = ui.label("", 14f, ui.bad)
        s.gravity = Gravity.CENTER
        col.addView(s, ui.lp(14))
        return s
    }

    private fun showStart(msg: String = "") {
        screen = "start"
        page { col ->
            head(col)
            val paired = vault.device() != null
            val host = ui.input(prefs.label("host"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            col.addView(host, ui.lp(26))
            val code = ui.input(prefs.label("code"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            code.visibility = if (!paired || newCode) View.VISIBLE else View.GONE
            col.addView(code, ui.lp(10))
            val st = status(col)
            st.text = msg
            val go = ui.button(prefs.label("go")) {}
            col.addView(go, ui.lp(18))
            if (paired) {
                val alt = ui.label(if (newCode) "Без кода" else "Есть новый код", 14f, ui.muted)
                alt.gravity = Gravity.CENTER
                alt.setPadding(0, ui.dp(14), 0, ui.dp(14))
                alt.setOnClickListener { newCode = !newCode; showStart() }
                col.addView(alt, ui.lp(6))
            }
            go.setOnClickListener {
                val base = Client.normBase(host.text.toString())
                val needPair = !paired || newCode
                val c = A1.normCode(code.text.toString())
                if (base.length < 10) { st.text = "Введите адрес"; return@setOnClickListener }
                if (needPair && c == null) { st.text = "Код — 12 знаков, как на экране добавления"; return@setOnClickListener }
                go.isEnabled = false
                st.setTextColor(ui.muted)
                st.text = "Проверяем…"
                bg {
                    val res = try {
                        var dev: Device? = vault.device()
                        if (needPair) {
                            dev = Client(base, null).pair(c!!, card())
                            vault.saveDevice(dev, base)
                            vault.forgetCreds()
                        }
                        hello(base, dev!!)
                        ""
                    } catch (e: Client.Silent) {
                        if (needPair) "Код не подошёл или устарел" else "Не отвечает. Проверьте адрес и время на телефоне"
                    } catch (e: IOException) {
                        "Нет связи с сервером"
                    } catch (e: Exception) {
                        "Не получилось: " + (e.message ?: e.javaClass.simpleName)
                    }
                    runOnUiThread {
                        go.isEnabled = true
                        if (res.isEmpty()) {
                            newCode = false
                            Push.apply(this)
                            showLogin()
                        } else {
                            st.setTextColor(ui.bad)
                            st.text = res
                        }
                    }
                }
            }
        }
    }

    private fun hello(base: String, d: Device): Client {
        val cl = Client(base, d) { vault.saveDevice(d, base) }
        val hi = cl.call("hi", card(), readMs = 30_000)
        if (hi.st != 200) throw Client.Silent("hi")
        vault.saveHost(base)
        prefs.knownHooks = hi.f.list("wh").mapNotNull { it as? String }
        prefs.evw = hi.f.int("evw", 280)
        S.client = cl
        S.hi = hi.f
        return cl
    }

    private fun loginFirst(): Boolean = prefs.entry == Prefs.ENTRY_LOGIN && vault.device() != null && vault.host().isNotEmpty()

    private fun entry(msg: String = "") = if (loginFirst()) showLogin(msg) else showStart(msg)

    private fun showLogin(msg: String = "") {
        screen = "login"
        page { col ->
            head(col)
            val user = ui.input(prefs.label("user"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            user.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            col.addView(user, ui.lp(26))
            val pass = ui.input(prefs.label("pass"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            pass.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            col.addView(pass, ui.lp(10))
            val remember = ui.check("Запомнить под отпечатком или PIN", false)
            if (vault.secure()) col.addView(remember, ui.lp(8))
            val st = status(col)
            st.text = msg
            val go = ui.button(prefs.label("go")) {}
            col.addView(go, ui.lp(14))
            val doLogin = { u: String, p: String, save: Boolean ->
                go.isEnabled = false
                st.setTextColor(ui.muted)
                st.text = "Входим…"
                bg {
                    var c = S.client
                    var ck = ""
                    var fresh = false
                    val res = try {
                        if (c == null) {
                            val d = vault.device()
                            val base = vault.host()
                            if (d == null || base.isEmpty()) throw Client.Silent("none")
                            fresh = true
                            c = hello(base, d)
                        }
                        val r = c.call("login", mapOf("u" to u, "p" to p), readMs = 30_000)
                        when (r.st) {
                            200 -> { ck = r.f.str("ck"); "" }
                            401 -> "Неверный логин или пароль"
                            429 -> "Слишком много попыток. Подождите 15 минут"
                            else -> "Сервер ответил ошибкой ${r.st}"
                        }
                    } catch (e: Client.Silent) {
                        if (fresh) "Не отвечает. Проверьте время на телефоне" else "Не отвечает. Начните сначала"
                    } catch (e: IOException) {
                        "Нет связи с сервером"
                    }
                    runOnUiThread {
                        go.isEnabled = true
                        if (fresh && S.client != null) Push.apply(this)
                        if (res.isEmpty() && ck.isNotEmpty()) {
                            S.drop()
                            S.server = LocalServer(S.client!!, ck) { runOnUiThread { lost() } }.start()
                            S.touch()
                            if (save) unlock { try { vault.saveCreds(u, p) } catch (_: Exception) {} }
                            showAdmin()
                        } else {
                            st.setTextColor(ui.bad)
                            st.text = res
                            if (S.client == null && !loginFirst()) showStart(res)
                        }
                    }
                }
            }
            go.setOnClickListener {
                if (user.text.isEmpty() || pass.text.isEmpty()) { st.text = "Введите логин и пароль"; return@setOnClickListener }
                doLogin(user.text.toString(), pass.text.toString(), remember.isChecked)
            }
            if (vault.hasCreds()) {
                val quick = ui.button("Войти по отпечатку или PIN", false) {
                    unlock {
                        val cr = try { vault.creds() } catch (e: Exception) { null }
                        if (cr == null) { st.text = "Сохранённый вход недоступен"; vault.forgetCreds() } else doLogin(cr.first, cr.second, false)
                    }
                }
                col.addView(quick, ui.lp(10))
            }
            if (loginFirst()) {
                val alt = ui.label("Другой адрес", 14f, ui.muted)
                alt.gravity = Gravity.CENTER
                alt.setPadding(0, ui.dp(14), 0, ui.dp(14))
                alt.setOnClickListener { showStart() }
                col.addView(alt, ui.lp(6))
            }
        }
    }

    private fun lost() {
        S.drop()
        if (screen == "admin" || screen == "settings") entry("Нужно войти снова")
    }

    private fun logout() {
        val srv = S.server
        val w = web
        var done = false
        val finish = {
            if (!done) {
                done = true
                S.drop()
                entry()
            }
        }
        if (srv == null || w == null || (screen != "admin" && screen != "settings")) return finish()
        w.evaluateJavascript("(function(){var a=document.querySelector('a.rw-out');return a?a.href:''})()") { v ->
            val href = try { org.json.JSONArray("[$v]").optString(0, "") } catch (_: Exception) { "" }
            finish()
            if (href.startsWith(srv.origin + "/admin/")) {
                bg { try { srv.fetch("GET", href.substring(srv.origin.length), emptyMap(), ByteArray(0)) } catch (_: Exception) {} }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(finish, 1500)
    }

    private fun shell(): FrameLayout {
        frame?.let { return it }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ui.bg)
        val fr = FrameLayout(this)
        root.addView(fr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = ui.bar()
        val t = listOf(
            ui.tab(R.drawable.nav_menu, "Разделы") { sections() },
            ui.tab(R.drawable.nav_settings, "Настройки") { if (screen == "settings") showAdmin() else showSettings() },
            ui.tab(R.drawable.nav_out, "Выход") { logout() },
        )
        for (x in t) bar.addView(x.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val pad = ui.dp(6)
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val k = ins.getInsets(WindowInsets.Type.ime())
                val ime = ins.isVisible(WindowInsets.Type.ime())
                root.setPadding(b.left, b.top, b.right, if (ime) maxOf(k.bottom, b.bottom) else 0)
                bar.setPadding(pad, 0, pad, b.bottom)
                bar.visibility = if (ime) View.GONE else View.VISIBLE
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                val bottom = ins.systemWindowInsetBottom
                val ime = bottom > ui.dp(120)
                @Suppress("DEPRECATION")
                root.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, if (ime) bottom else 0)
                bar.setPadding(pad, 0, pad, if (ime) 0 else bottom)
                bar.visibility = if (ime) View.GONE else View.VISIBLE
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }
        setContentView(root)
        frame = fr
        tabs = t
        return fr
    }

    private fun mark() {
        if (tabs.size == 3) ui.paint(tabs[1], screen == "settings")
    }

    private fun dropSettings() {
        val v = setView ?: return
        currentFocus?.clearFocus()
        frame?.removeView(v)
        setView = null
    }

    private fun sections() {
        val open = screen == "settings"
        if (open) showAdmin()
        val js = if (open) "add" else "toggle"
        web?.evaluateJavascript("(function(){var a=document.querySelector('.rw-app');if(a)a.classList.$js('nav-open');})()", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showAdmin() {
        val srv = S.server ?: return entry()
        val fr = shell()
        dropSettings()
        screen = "admin"
        mark()
        web?.let { it.visibility = View.VISIBLE; return }
        val w = WebView(this)
        w.setBackgroundColor(ui.bg)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(srv.origin, "a1l=" + srv.secret + "; path=/")
        cm.flush()
        val local = { u: Uri -> u.scheme == "http" && u.host == "127.0.0.1" && u.port == srv.port }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (local(req.url)) return false
                if (req.url.scheme == "https" || req.url.scheme == "http") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val sch = req.url.scheme
                if (sch == "data" || sch == "blob" || local(req.url)) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), 42)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
        w.setDownloadListener { url, _, disp, mime, _ ->
            val u = Uri.parse(url)
            if (!local(u)) return@setDownloadListener
            val name = URLUtil.guessFileName(url, disp, mime)
            toast("Скачивание: $name")
            bg {
                val msg = try {
                    val r = srv.fetch("GET", u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: ""), emptyMap(), ByteArray(0))
                    if (r.st != 200) "Не скачалось: ${r.st}" else "Сохранено: " + save(name, mime ?: "application/octet-stream", r.body)
                } catch (e: Exception) {
                    "Не скачалось"
                }
                runOnUiThread { toast(msg) }
            }
        }
        web = w
        fr.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val t = pendingTab
        pendingTab = null
        w.loadUrl(srv.origin + "/admin/index.php" + (if (t != null) "?tab=$t" else ""))
    }

    private fun save(name: String, mime: String, data: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("store")
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            return "Загрузки/$name"
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        File(dir, name).writeBytes(data)
        return File(dir, name).path
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else if (requestCode == 43) {
            val cb = afterUnlock
            afterUnlock = null
            if (resultCode == RESULT_OK) cb?.invoke()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "admin" -> back()
            "settings" -> showAdmin()
            "login" -> if (loginFirst()) @Suppress("DEPRECATION") super.onBackPressed() else showStart()
            "start" -> if (loginFirst()) showLogin() else @Suppress("DEPRECATION") super.onBackPressed()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun back() {
        val w = web ?: run { moveTaskToBack(true); return }
        w.evaluateJavascript("(function(){var a=document.querySelector('.rw-app.nav-open');if(a){a.classList.remove('nav-open');return 1}return 0})()") { v ->
            if (v == "1" || screen != "admin") return@evaluateJavascript
            if (w.canGoBack()) w.goBack() else moveTaskToBack(true)
        }
    }

    private fun unlock(ok: () -> Unit) {
        val title = prefs.label("title")
        val fallback = {
            val km = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent(title, null)
            if (i == null) ok() else { afterUnlock = ok; startActivityForResult(i, 43) }
        }
        if (Build.VERSION.SDK_INT < 28) return fallback()
        val b = BiometricPrompt.Builder(this).setTitle(title)
        when {
            Build.VERSION.SDK_INT >= 30 -> b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else -> b.setNegativeButton("Отмена", mainExecutor) { _, _ -> }
        }
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                ok()
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                if (Build.VERSION.SDK_INT == 28 && code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) fallback()
            }
        })
    }

    private fun showSettings() {
        if (S.server == null) return entry()
        val fr = shell()
        val y = (setView as? ScrollView)?.scrollY ?: 0
        dropSettings()
        screen = "settings"
        mark()
        val col = ui.column(20)
        col.addView(ui.title("Настройки", 22f), ui.lp(8))

        col.addView(ui.section("Уведомления"))
        val pb = ui.box()
        val rg = RadioGroup(this)
        val modes = listOf(Prefs.PUSH_OFF to "Выключены", Prefs.PUSH_LIVE to "Сразу — держать соединение", Prefs.PUSH_ECO to "Экономно — раз в 15 минут")
        for ((v, t) in modes) {
            rg.addView(RadioButton(this).apply {
                id = 100 + v
                text = t
                setTextColor(ui.text)
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                isChecked = prefs.push == v
            })
        }
        rg.setOnCheckedChangeListener { _, id ->
            prefs.push = id - 100
            if (prefs.push != Prefs.PUSH_OFF) askPush()
            Push.apply(this)
        }
        pb.addView(rg)
        pb.addView(ui.label("Что присылать", 13f), ui.lp(12))
        val g = prefs.groups.toMutableSet()
        val tog = { k: String, on: Boolean -> if (on) g.add(k) else g.remove(k); prefs.groups = g }
        pb.addView(ui.check("Сообщения в чате", "chat" in g) { tog("chat", it) })
        pb.addView(ui.check("Отчёты Clod Clash", "rep" in g) { tog("rep", it) })
        pb.addView(ui.check("Новые абоненты защищённого канала", "chan_new" in g) { tog("chan_new", it) })
        pb.addView(ui.check("Вебхуки панели", "wh" in g) { tog("wh", it) })
        val known = prefs.knownHooks
        if (known.isNotEmpty()) {
            pb.addView(ui.label("Какие вебхуки. Ничего не отмечено — все.", 13f), ui.lp(10))
            val h = prefs.hooks.toMutableSet()
            for (ev in known) pb.addView(ui.check(ev, ev in h) { on -> if (on) h.add(ev) else h.remove(ev); prefs.hooks = h })
        }
        pb.addView(ui.check("Не показывать название события", prefs.hideNames) { prefs.hideNames = it }, ui.lp(8))
        col.addView(pb)

        col.addView(ui.section("Тексты в шторке"))
        val tb = ui.box()
        tb.addView(ui.label("Нажмите, чтобы задать свой текст", 13f), ui.lp(2))
        for ((k, t) in listOf("chat" to "Сообщение в чате", "rep" to "Отчёт Clod Clash", "chan_new" to "Новый абонент канала", "wh" to "Вебхук — общий текст", "fg" to "Постоянное уведомление")) {
            tb.addView(ui.divider(), ui.lp(6))
            tb.addView(textRow(t, k, Prefs.DEFAULT_TEXT[k] ?: k))
        }
        val hooks = prefs.knownHooks
        if (hooks.isNotEmpty()) {
            tb.addView(ui.section("Вебхуки по отдельности"))
            tb.addView(ui.label("Без своего текста — общий текст и название события", 13f))
            for (ev in hooks) {
                tb.addView(ui.divider(), ui.lp(6))
                tb.addView(textRow(ev, "wh:$ev", null))
            }
        }
        col.addView(tb)

        col.addView(ui.section("Значок и название"))
        val mb = ui.box()
        val cur = Masks.current(this)
        for (m in Masks.all) {
            val r = ui.row()
            r.setPadding(0, ui.dp(6), 0, ui.dp(6))
            r.addView(ImageView(this).apply { setImageResource(m.launcher) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            val tv = ui.label(getString(m.name) + if (m.id == cur.id) "  ·  сейчас" else "", 16f, ui.text)
            tv.setPadding(ui.dp(14), 0, 0, 0)
            r.addView(tv)
            r.setOnClickListener {
                Masks.apply(this, m.id)
                toast("Значок сменится через несколько секунд")
                showSettings()
            }
            mb.addView(r)
        }
        mb.addView(ui.label("Свой ярлык на рабочий стол — название и текущий значок", 13f), ui.lp(12))
        val sc = ui.input("Название ярлыка")
        mb.addView(sc, ui.lp(6))
        mb.addView(ui.button("Добавить ярлык", false) {
            val name = sc.text.toString().trim()
            if (name.isEmpty()) toast("Введите название") else if (!Masks.pin(this, name, Masks.current(this))) toast("Рабочий стол не поддерживает ярлыки")
        }, ui.lp(8))
        col.addView(mb)

        col.addView(ui.section("Экран входа"))
        val lb = ui.box()
        val entryNames = listOf("Адрес сервера", "Логин и пароль")
        val ei = if (prefs.entry == Prefs.ENTRY_LOGIN) 1 else 0
        lb.addView(ui.pick("Что показывать", entryNames[ei]) {
            choose("Экран входа", entryNames, ei) { i ->
                prefs.entry = if (i == 1) Prefs.ENTRY_LOGIN else Prefs.ENTRY_HOST
                showSettings()
            }
        })
        lb.addView(ui.label(if (ei == 1) "Адрес — тот, к которому привязан телефон" else "Адрес вводится при каждом входе", 13f))
        for ((k, t) in listOf("title" to "Заголовок", "host" to "Поле адреса", "code" to "Поле кода", "user" to "Поле логина", "pass" to "Поле пароля", "go" to "Кнопка")) {
            lb.addView(ui.label(t, 13f), ui.lp(8))
            lb.addView(field(prefs.label(k)) { prefs.setLabel(k, it) }, ui.lp(4))
        }
        col.addView(lb)

        col.addView(ui.section("Безопасность"))
        val sb = ui.box()
        val li = Prefs.LOCK_MIN.indexOf(prefs.lockMin)
        sb.addView(ui.pick("Автовыход", lockName(prefs.lockMin)) {
            choose("Автовыход", Prefs.LOCK_MIN.map { lockName(it) }, li) { i ->
                prefs.lockMin = Prefs.LOCK_MIN[i]
                S.lockMs = prefs.lockMin * 60_000L
                S.touch()
                showSettings()
            }
        })
        sb.addView(ui.label("Если столько времени не трогать приложение", 13f))
        sb.addView(ui.divider(), ui.lp(8))
        sb.addView(ui.check("Скрывать экран в списке приложений", prefs.secureScreen) {
            prefs.secureScreen = it
            if (it) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }, ui.lp(4))
        if (vault.hasCreds()) sb.addView(ui.button("Забыть сохранённый вход", false) { vault.forgetCreds(); toast("Забыт"); showSettings() }, ui.lp(8))
        sb.addView(ui.button("Отвязать телефон", false) { unpair() }, ui.lp(8))
        col.addView(sb)

        col.addView(ui.section("Приложение"))
        val ub = ui.box()
        val info = Updater.info(S.hi["apk"])
        ub.addView(ui.label("Версия " + BuildConfig.VERSION_NAME + (if (info != null) " · на сервере " + info.v else ""), 15f, ui.text))
        if (info != null && Updater.newer(info.v)) {
            ub.addView(ui.button("Обновить до " + info.v, true) { update(info) }, ui.lp(10))
        }
        col.addView(ub)
        col.addView(ui.gap(30))
        val pane = ui.scroll(col)
        fr.addView(pane, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setView = pane
        web?.visibility = View.INVISIBLE
        if (y > 0) pane.post { pane.scrollTo(0, y) }
    }

    private fun lockName(m: Int): String = if (m == 0) "Никогда" else "$m мин"

    private fun choose(title: String, items: List<String>, sel: Int, done: (Int) -> Unit) {
        dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), sel) { d, i -> d.dismiss(); if (i != sel) done(i) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun textRow(name: String, key: String, def: String?): View {
        val cur = prefs.custom(key)
        val shown = cur ?: def ?: ("Как общий — " + prefs.text("wh"))
        return ui.item(name, shown, cur != null) { rename(name, key, def ?: prefs.text("wh")) }
    }

    private fun rename(name: String, key: String, def: String) {
        val e = ui.input(def)
        e.setText(prefs.custom(key) ?: "")
        e.setSelection(e.text.length)
        val box = LinearLayout(this)
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        box.addView(e, ui.lp(0))
        val after = {
            Notifier.channels(this)
            showSettings()
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(name)
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ -> prefs.setText(key, e.text.toString()); after() }
            .setNeutralButton("Сбросить") { _, _ -> prefs.setText(key, ""); after() }
            .setNegativeButton("Отмена", null)
            .show()
        e.requestFocus()
        dlg?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun field(value: String, save: (String) -> Unit): EditText {
        val e = ui.input("")
        e.setText(value)
        e.setOnFocusChangeListener { _, has -> if (!has) save(e.text.toString()) }
        return e
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }

    private fun askPush() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        if (prefs.push == Prefs.PUSH_LIVE) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (_: Exception) {}
            }
        }
    }

    private fun unpair() {
        AlertDialog.Builder(this)
            .setMessage("Отвязать телефон? Вход и уведомления перестанут работать, понадобится новый код.")
            .setPositiveButton("Отвязать") { _, _ ->
                val c = S.client
                bg {
                    try { c?.call("bye", readMs = 15_000) } catch (_: Exception) {}
                    runOnUiThread {
                        prefs.push = Prefs.PUSH_OFF
                        Push.apply(this)
                        vault.forgetCreds()
                        vault.forgetDevice()
                        prefs.cursor = -1
                        S.drop()
                        S.client = null
                        showStart()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun update(info: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Разрешите установку из этого приложения и нажмите ещё раз")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            return
        }
        val c = S.client ?: return
        toast("Скачиваем " + info.v + "…")
        bg {
            val err = try {
                Updater.install(this, Updater.download(c, info))
                ""
            } catch (e: SecurityException) {
                "Файл не прошёл проверку"
            } catch (e: Exception) {
                "Не скачалось"
            }
            if (err.isNotEmpty()) runOnUiThread { toast(err) }
        }
    }

    override fun onDestroy() {
        if (S.onLock === lockCb) S.onLock = null
        if (isFinishing && screen != "admin") web?.destroy()
        super.onDestroy()
    }
}
