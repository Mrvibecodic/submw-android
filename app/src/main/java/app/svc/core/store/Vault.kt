package app.svc.core.store

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import app.svc.core.a1.Device
import app.svc.core.a1.Json
import app.svc.core.a1.str
import app.svc.core.crypto.B64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(ctx: Context) {
    class Locked : Exception("locked")

    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("v", Context.MODE_PRIVATE)
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(alias: String, auth: Boolean): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (auth) {
            b.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(30)
            }
            b.setInvalidatedByBiometricEnrollment(false)
        }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(b.build())
        return g.generateKey()
    }

    private fun seal(alias: String, auth: Boolean, data: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            c.init(Cipher.ENCRYPT_MODE, key(alias, auth))
        } catch (e: UserNotAuthenticatedException) {
            throw Locked()
        }
        return B64.enc(c.iv) + "." + B64.enc(c.doFinal(data))
    }

    private fun open(alias: String, s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 2) return null
        val iv = B64.dec(parts[0]) ?: return null
        val ct = B64.dec(parts[1]) ?: return null
        val k = ks.getKey(alias, null) as? SecretKey ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            return c.doFinal(ct)
        } catch (e: UserNotAuthenticatedException) {
            throw Locked()
        } catch (e: Exception) {
            return null
        }
    }

    fun device(): Device? {
        val s = sp.getString("dev", null) ?: return null
        val raw = open("a1dev", s) ?: return null
        return try {
            val m = Json.obj(String(raw, Charsets.UTF_8))
            Device(B64.dec(m.str("s")) ?: return null, m.str("d"), m.str("p"))
        } catch (e: Exception) {
            null
        }
    }

    fun host(): String {
        val s = sp.getString("host", null) ?: return ""
        return open("a1dev", s)?.toString(Charsets.UTF_8) ?: ""
    }

    fun saveDevice(d: Device, host: String) {
        val j = Json.write(mapOf("s" to B64.enc(d.secret), "d" to d.did, "p" to d.sp))
        sp.edit()
            .putString("dev", seal("a1dev", false, j.toByteArray(Charsets.UTF_8)))
            .putString("host", seal("a1dev", false, host.toByteArray(Charsets.UTF_8)))
            .apply()
    }

    fun saveHost(host: String) {
        sp.edit().putString("host", seal("a1dev", false, host.toByteArray(Charsets.UTF_8))).apply()
    }

    fun forgetDevice() {
        sp.edit().remove("dev").remove("host").apply()
    }

    fun secure(): Boolean = (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun hasCreds(): Boolean = sp.contains("cred")

    fun saveCreds(user: String, pass: String) {
        val j = Json.write(mapOf("u" to user, "p" to pass))
        sp.edit().putString("cred", seal("a1cred", true, j.toByteArray(Charsets.UTF_8))).apply()
    }

    fun creds(): Pair<String, String>? {
        val s = sp.getString("cred", null) ?: return null
        val raw = open("a1cred", s) ?: return null
        val m = Json.obj(String(raw, Charsets.UTF_8))
        return m.str("u") to m.str("p")
    }

    fun forgetCreds() {
        sp.edit().remove("cred").apply()
        try { ks.deleteEntry("a1cred") } catch (_: Exception) {}
    }
}
