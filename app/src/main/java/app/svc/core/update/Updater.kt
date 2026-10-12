package app.svc.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.svc.core.BuildConfig
import app.svc.core.a1.Client
import app.svc.core.a1.str
import app.svc.core.crypto.Hkdf

object Updater {
    class Info(val v: String, val size: Long, val sha: String)

    fun newer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = remote.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = local.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun info(apk: Any?): Info? {
        @Suppress("UNCHECKED_CAST")
        val m = apk as? Map<String, Any?> ?: return null
        val v = m.str("v")
        if (v.isEmpty()) return null
        return Info(v, (m["size"] as? Number)?.toLong() ?: 0, m.str("sha256"))
    }

    fun download(c: Client, want: Info): ByteArray {
        val r = c.call("apk", readMs = 300_000)
        if (r.st != 200) throw IllegalStateException("st ${r.st}")
        val sha = Hkdf.sha256(r.body).joinToString("") { "%02x".format(it) }
        if (!sha.equals(want.sha, true) || !sha.equals(r.f.str("sha256"), true)) throw SecurityException("sha256")
        return r.body
    }

    fun install(ctx: Context, apk: ByteArray) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.size.toLong()).use { it.write(apk); s.fsync(it) }
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, Done::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(cb.intentSender)
        }
    }

    class Done : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
