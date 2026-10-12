package app.svc.core.push

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import app.svc.core.a1.Client
import app.svc.core.a1.long
import app.svc.core.a1.map
import app.svc.core.store.Prefs
import app.svc.core.store.Vault
import java.io.IOException

object Push {
    fun apply(ctx: Context) {
        val p = Prefs(ctx)
        val js = ctx.getSystemService(JobScheduler::class.java)
        val paired = Vault(ctx).device() != null && Vault(ctx).host().isNotEmpty()
        if (p.push == Prefs.PUSH_ECO && paired) {
            js.schedule(
                JobInfo.Builder(7, ComponentName(ctx, EcoJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        } else {
            js.cancel(7)
        }
        val svc = Intent(ctx, EvService::class.java)
        if (p.push == Prefs.PUSH_LIVE && paired) ctx.startForegroundService(svc) else ctx.stopService(svc)
    }

    fun once(ctx: Context, waitSec: Int, client: Client? = null): Boolean {
        val v = Vault(ctx)
        val p = Prefs(ctx)
        val dev = v.device() ?: return false
        val host = v.host().ifEmpty { return false }
        val c = client ?: Client(host, dev) { v.saveDevice(dev, host) }
        val want = p.wanted()
        val r = c.call("ev", mapOf("c" to p.cursor, "w" to if (p.cursor < 0) 0 else waitSec, "k" to want), readMs = (waitSec + 25) * 1000)
        if (r.st != 200) return false
        val e = r.f.map("e")
        if (p.cursor >= 0 && e.isNotEmpty()) Notifier.post(ctx, e)
        p.cursor = r.f.long("c", p.cursor)
        return true
    }
}

class EvService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var client: Client? = null
    private var cm: ConnectivityManager? = null
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            client?.abort()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.foreground(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.FG_ID, n)
        }
        if (!running) {
            running = true
            cm = getSystemService(ConnectivityManager::class.java)
            try { cm?.registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}
            worker = Thread({ loop() }, "a1-ev").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        client?.abort()
        worker?.interrupt()
        try { cm?.unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun net(): String {
        val caps = cm?.getNetworkCapabilities(cm?.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }

    private fun loop() {
        val v = Vault(this)
        val p = Prefs(this)
        var w = 25
        var fails = 0
        while (running) {
            val dev = v.device()
            val host = v.host()
            if (dev == null || host.isEmpty() || p.push != Prefs.PUSH_LIVE) {
                stopSelf()
                return
            }
            val c = Client(host, dev) { v.saveDevice(dev, host) }
            client = c
            val nk = net()
            val ceil = p.ceiling(nk).takeIf { it > 0 } ?: 290
            val top = minOf(ceil, p.evw, 290).coerceAtLeast(25)
            w = w.coerceIn(25, top)
            val t0 = System.currentTimeMillis()
            try {
                if (!Push.once(this, w, c)) {
                    nap(60_000)
                    continue
                }
                val spent = ((System.currentTimeMillis() - t0) / 1000).toInt()
                if (spent >= w - 3 && w < top) w = minOf(w + 30, top)
                fails = 0
            } catch (e: Client.Silent) {
                fails++
                nap(300_000)
            } catch (e: IOException) {
                val spent = ((System.currentTimeMillis() - t0) / 1000).toInt()
                if (spent in 20 until w + 15 && running) {
                    val lim = maxOf(25, spent - 10)
                    p.setCeiling(nk, lim)
                    w = lim
                }
                fails++
                nap(minOf(60_000L, 2_000L shl minOf(fails, 5)))
            } catch (e: Exception) {
                fails++
                nap(30_000)
            }
            if (!running) return
        }
    }

    private fun nap(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}

class EcoJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var ok = false
            try { ok = Push.once(this, 0) } catch (_: Exception) {}
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

class Boot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { Push.apply(ctx) } catch (_: Exception) {}
        }
    }
}
