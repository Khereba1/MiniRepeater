package com.khereba1.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PREFS = "app_prefs"

/** App entry point: starts the in-app error log. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}

/** Small file-based error log (private to the app, never sent anywhere). Also records crashes. */
object AppLog {
    private var file: File? = null
    private val lock = Any()
    private const val MAX_BYTES = 200_000

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log("CRASH", "Uncaught exception in thread " + thread.name, error)
            } catch (_: Throwable) {
                // ignore
            }
            previous?.uncaughtException(thread, error)
        }
        log(
            "APP",
            "Started. Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
                Build.MANUFACTURER + " " + Build.MODEL
        )
    }

    fun log(tag: String, message: String, error: Throwable? = null) {
        Log.i("MiniRepeater/" + tag, message)
        RepeaterState.appendLog("[$tag] $message")
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.takeLast(MAX_BYTES / 2))
                }
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append(time).append(" [").append(tag).append("] ").append(message).append('\n')
                if (error != null) sb.append(Log.getStackTraceString(error)).append('\n')
                f.appendText(sb.toString())
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    fun read(): String {
        val f = file ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear() {
        RepeaterState.clearLogs()
        try {
            file?.writeText("")
        } catch (_: Throwable) {
            // ignore
        }
    }
}

/** In-app language switch (system / Arabic / English). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("lang", "system") ?: "system"
        if (lang == "system") return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/** Persistent preferences for MiniRepeater settings. */
object Prefs {
    private const val KEY_SSID = "ssid"
    private const val KEY_PASS = "password"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_ROUTE_VPN = "route_vpn"
    private const val KEY_THEME = "theme"
    private const val KEY_LANG = "lang"

    fun getSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SSID, "DIRECT-MR-WiFi") ?: "DIRECT-MR-WiFi"

    fun saveSsid(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SSID, value).apply()
    }

    fun getPassword(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PASS, "12345678") ?: "12345678"

    fun savePassword(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASS, value).apply()
    }

    fun isAutoStart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, false)

    fun saveAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    fun isRouteViaVpn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROUTE_VPN, false)

    fun saveRouteViaVpn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ROUTE_VPN, enabled).apply()
    }

    fun getTheme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "system") ?: "system"

    fun saveTheme(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply()
    }

    fun getLang(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "system") ?: "system"

    fun saveLang(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, value).apply()
    }
}
