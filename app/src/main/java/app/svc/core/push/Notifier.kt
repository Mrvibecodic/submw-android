package app.svc.core.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.svc.core.mask.Masks
import app.svc.core.store.Prefs
import app.svc.core.ui.MainActivity

object Notifier {
    const val FG_ID = 1

    fun group(key: String): String = if (key.startsWith("wh:")) "wh" else key

    fun tab(key: String): String = when (group(key)) {
        "chat" -> "chat"
        "rep" -> "reports"
        "chan_new" -> "clod"
        else -> "whlog"
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val p = Prefs(ctx)
        for (g in Prefs.GROUPS) {
            nm.createNotificationChannel(NotificationChannel("g_$g", p.text(g), NotificationManager.IMPORTANCE_HIGH))
        }
        nm.createNotificationChannel(NotificationChannel("g_fg", p.text("fg"), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }

    fun foreground(ctx: Context): Notification {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, "g_fg")
            .setSmallIcon(Masks.current(ctx).icon)
            .setContentTitle(ctx.getString(Masks.current(ctx).name))
            .setContentText(Prefs(ctx).text("fg"))
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun post(ctx: Context, events: Map<String, Any?>) {
        if (events.isEmpty()) return
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val p = Prefs(ctx)
        val counts = ctx.getSharedPreferences("n", Context.MODE_PRIVATE)
        val ed = counts.edit()
        for ((key, v) in events) {
            val n = (v as? Number)?.toInt() ?: 1
            val total = counts.getInt(key, 0) + n
            ed.putInt(key, total)
            val g = group(key)
            val id = 1000 + (key.hashCode() and 0x7ffffff)
            val open = Intent(ctx, MainActivity::class.java).putExtra("tab", tab(key)).putExtra("nk", key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val del = PendingIntent.getBroadcast(ctx, id, Intent(ctx, Cleared::class.java).putExtra("nk", key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val (name, body) = shade(key, p.custom(key), p.text(g), p.hideNames)
            val b = Notification.Builder(ctx, "g_$g")
                .setSmallIcon(Masks.current(ctx).icon)
                .setContentTitle(name + if (total > 1) " · $total" else "")
                .setContentIntent(pi)
                .setDeleteIntent(del)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(if (g == "chat") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_EVENT)
            if (body != null) b.setContentText(body)
            nm.notify(id, b.build())
        }
        ed.apply()
    }

    fun shade(key: String, custom: String?, groupText: String, hideNames: Boolean): Pair<String, String?> {
        if (custom != null) return custom to null
        val body = if (group(key) == "wh" && !hideNames) key.removePrefix("wh:") else null
        return groupText to body
    }

    fun clear(ctx: Context, key: String) {
        ctx.getSharedPreferences("n", Context.MODE_PRIVATE).edit().remove(key).apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + (key.hashCode() and 0x7ffffff))
    }

    class Cleared : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val k = i.getStringExtra("nk") ?: return
            ctx.getSharedPreferences("n", Context.MODE_PRIVATE).edit().remove(k).apply()
        }
    }
}
