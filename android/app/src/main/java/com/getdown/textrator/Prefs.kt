package com.getdown.textrator

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** All local settings. Tokens live in a separate file that is excluded from backups. */
object Prefs {
    private lateinit var p: SharedPreferences
    private lateinit var s: SharedPreferences

    fun init(c: Context) {
        p = c.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        s = c.getSharedPreferences("tt_secure", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun pi(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun str(k: String) = s.getString(k, "") ?: ""
    private fun pstr(k: String, v: String) = s.edit().putString(k, v).apply()

    var bubbleEnabled: Boolean
        get() = b("bubble", true)
        set(v) = pb("bubble", v)
    var smartExpand: Boolean
        get() = b("smart_expand", true)
        set(v) = pb("smart_expand", v)
    var useOcr: Boolean
        get() = b("ocr", true)
        set(v) = pb("ocr", v)
    var saveHistory: Boolean
        get() = b("save_history", true)
        set(v) = pb("save_history", v)
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = pb("haptics", v)
    var consentGiven: Boolean
        get() = b("consent", false)
        set(v) = pb("consent", v)
    var guestMode: Boolean
        get() = b("guest", false)
        set(v) = pb("guest", v)

    var bubbleSizeDp: Int
        get() = i("bubble_size", 40)
        set(v) = pi("bubble_size", v)
    /** Idle opacity in percent: the bubble is see-through until touched. */
    var bubbleOpacity: Int
        get() = i("bubble_opacity", 60)
        set(v) = pi("bubble_opacity", v)
    var bubbleX: Int
        get() = i("bubble_x", -1)
        set(v) = pi("bubble_x", v)
    var bubbleY: Int
        get() = i("bubble_y", -1)
        set(v) = pi("bubble_y", v)

    val deviceId: String
        get() {
            var id = p.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                p.edit().putString("device_id", id).apply()
            }
            return id
        }

    var accessToken: String
        get() = str("access")
        set(v) = pstr("access", v)
    var refreshToken: String
        get() = str("refresh")
        set(v) = pstr("refresh", v)
    var tokenExpiry: Long
        get() = s.getLong("expiry", 0L)
        set(v) = s.edit().putLong("expiry", v).apply()
    var userEmail: String
        get() = str("email")
        set(v) = pstr("email", v)
    var userId: String
        get() = str("uid")
        set(v) = pstr("uid", v)

    /** Set remotely from the admin dashboard; overrides the build default. */
    var remoteAuthMode: String
        get() = p.getString("remote_auth_mode", "") ?: ""
        set(v) = p.edit().putString("remote_auth_mode", v).apply()
    var announcement: String
        get() = p.getString("announcement", "") ?: ""
        set(v) = p.edit().putString("announcement", v).apply()

    fun clearSession() {
        s.edit().clear().apply()
    }
}
