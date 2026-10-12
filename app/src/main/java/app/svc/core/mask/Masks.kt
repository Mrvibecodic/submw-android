package app.svc.core.mask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.svc.core.R
import app.svc.core.store.Prefs
import app.svc.core.ui.MainActivity

object Masks {
    class Mask(val id: String, val name: Int, val icon: Int, val launcher: Int)

    val all = listOf(
        Mask("Default", R.string.m_default, R.drawable.ic_default, R.mipmap.l_default),
        Mask("Notes", R.string.m_notes, R.drawable.ic_notes, R.mipmap.l_notes),
        Mask("Calc", R.string.m_calc, R.drawable.ic_calc, R.mipmap.l_calc),
        Mask("Weather", R.string.m_weather, R.drawable.ic_weather, R.mipmap.l_weather),
        Mask("Files", R.string.m_files, R.drawable.ic_files, R.mipmap.l_files),
        Mask("Clock", R.string.m_clock, R.drawable.ic_clock, R.mipmap.l_clock),
    )

    fun current(ctx: Context): Mask = all.firstOrNull { it.id == Prefs(ctx).mask } ?: all[0]

    fun apply(ctx: Context, id: String) {
        val pm = ctx.packageManager
        val pick = all.firstOrNull { it.id == id } ?: all[0]
        pm.setComponentEnabledSetting(ComponentName(ctx, "app.svc.core.m.${pick.id}"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (m in all) {
            if (m.id == pick.id) continue
            pm.setComponentEnabledSetting(ComponentName(ctx, "app.svc.core.m.${m.id}"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        Prefs(ctx).mask = pick.id
    }

    fun pin(ctx: Context, label: String, mask: Mask): Boolean {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val intent = Intent(Intent.ACTION_MAIN).setClass(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = ShortcutInfo.Builder(ctx, "s" + System.currentTimeMillis())
            .setShortLabel(label)
            .setIcon(Icon.createWithResource(ctx, mask.launcher))
            .setIntent(intent)
            .build()
        return sm.requestPinShortcut(info, null)
    }
}
