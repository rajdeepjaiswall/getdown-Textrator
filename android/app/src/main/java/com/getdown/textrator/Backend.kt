package com.getdown.textrator

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class AuthResult(val ok: Boolean, val message: String)

/**
 * Talks to Supabase over plain REST (no SDK = small APK).
 * - If SUPABASE_URL / SUPABASE_ANON_KEY are empty the app still works fully offline in guest mode.
 * - Usage events never contain the copied text, only counts, lengths and the source app.
 * - Events are queued in SQLite and flushed when the network is back.
 */
object Backend {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Mutex()
    private val refreshLock = Mutex()

    val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private val base: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    val isLoggedIn: Boolean get() = Prefs.refreshToken.isNotEmpty()

    /** "optional" | "required" | "off". The admin dashboard can change it without an app update. */
    fun authMode(): String {
        val remote = Prefs.remoteAuthMode
        return if (remote in setOf("optional", "required", "off")) remote else BuildConfig.AUTH_MODE
    }

    /** The bypass: login is only forced when the backend exists AND the mode is "required". */
    fun needsLogin(): Boolean {
        if (!configured || isLoggedIn) return false
        return when (authMode()) {
            "off" -> false
            "required" -> true
            else -> !Prefs.guestMode
        }
    }

    fun start() {
        scope.launch {
            fetchConfig()
            ping()
            flush()
        }
    }

    // ---------------- Auth ----------------

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password)
        val r = post("$base/auth/v1/token?grant_type=password", body, null)
        if (r.first in 200..299) {
            saveSession(JSONObject(r.second)); Prefs.guestMode = false; ping()
            AuthResult(true, "Signed in")
        } else AuthResult(false, errorText(r.second, "Wrong email or password"))
    }

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password)
        val r = post("$base/auth/v1/signup", body, null)
        if (r.first in 200..299) {
            val j = JSONObject(r.second)
            if (j.has("access_token")) {
                saveSession(j); Prefs.guestMode = false; ping()
                AuthResult(true, "Account created")
            } else AuthResult(true, "Check your email to confirm your account, then sign in.")
        } else AuthResult(false, errorText(r.second, "Could not create account"))
    }

    suspend fun resetPassword(email: String): AuthResult = withContext(Dispatchers.IO) {
        val r = post("$base/auth/v1/recover", JSONObject().put("email", email), null)
        if (r.first in 200..299) AuthResult(true, "Password reset link sent to $email")
        else AuthResult(false, errorText(r.second, "Could not send reset email"))
    }

    fun signOut() {
        val token = Prefs.accessToken
        Prefs.clearSession()
        Prefs.guestMode = false
        if (configured && token.isNotEmpty()) scope.launch {
            runCatching { post("$base/auth/v1/logout", JSONObject(), token) }
        }
    }

    private fun saveSession(j: JSONObject) {
        Prefs.accessToken = j.optString("access_token")
        Prefs.refreshToken = j.optString("refresh_token")
        Prefs.tokenExpiry = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        val user = j.optJSONObject("user")
        if (user != null) {
            Prefs.userEmail = user.optString("email")
            Prefs.userId = user.optString("id")
        }
    }

    /** Returns a valid access token, refreshing it if needed; null = use the anon key. */
    private suspend fun token(): String? = refreshLock.withLock {
        if (!isLoggedIn) return null
        if (System.currentTimeMillis() < Prefs.tokenExpiry - 60_000) return Prefs.accessToken
        val r = post("$base/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", Prefs.refreshToken), null)
        return when {
            r.first in 200..299 -> { saveSession(JSONObject(r.second)); Prefs.accessToken }
            r.first in 400..499 -> { Prefs.clearSession(); null } // refresh token revoked/expired
            else -> Prefs.accessToken // offline: keep session, try later
        }
    }

    // ---------------- Config, presence, usage ----------------

    private suspend fun rpc(fn: String, args: JSONObject): Pair<Int, String> =
        post("$base/rest/v1/rpc/$fn", args, token())

    suspend fun fetchConfig() {
        if (!configured) return
        runCatching {
            val r = rpc("tt_get_config", JSONObject())
            if (r.first in 200..299) {
                val j = JSONObject(r.second)
                Prefs.remoteAuthMode = j.optString("auth_mode", "")
                Prefs.announcement = j.optString("announcement", "")
            }
        }
    }

    fun ping() {
        if (!configured) return
        scope.launch {
            runCatching {
                rpc(
                    "tt_ping",
                    JSONObject()
                        .put("p_device_id", Prefs.deviceId)
                        .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
                        .put("p_android", Build.VERSION.SDK_INT)
                        .put("p_app_version", BuildConfig.VERSION_NAME)
                )
            }
        }
    }

    /** type = capture | copy ; method = text | ocr | mixed | image | share */
    fun logEvent(type: String, chars: Int, source: String, method: String) {
        val e = JSONObject()
            .put("type", type).put("chars", chars).put("source", source.take(120))
            .put("method", method).put("at", isoNow())
        scope.launch {
            HistoryDb.get(App.instance).addPending(e.toString())
            flush()
        }
    }

    suspend fun flush() {
        if (!configured) return
        flushLock.withLock {
            val db = HistoryDb.get(App.instance)
            repeat(10) {
                val batch = db.takePending(100)
                if (batch.isEmpty()) return
                val arr = JSONArray()
                batch.forEach { arr.put(JSONObject(it.second)) }
                val r = runCatching {
                    rpc("tt_log_events", JSONObject().put("p_device_id", Prefs.deviceId).put("p_events", arr))
                }.getOrNull() ?: return
                if (r.first !in 200..299) return
                db.deletePending(batch.map { it.first })
            }
        }
    }

    // ---------------- HTTP helpers ----------------

    private fun post(url: String, body: JSONObject, bearer: String?): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${bearer ?: BuildConfig.SUPABASE_ANON_KEY}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun errorText(raw: String, fallback: String): String = runCatching {
        val j = JSONObject(raw)
        listOf("error_description", "msg", "message", "error").firstNotNullOfOrNull { k ->
            j.optString(k).takeIf { it.isNotBlank() }
        } ?: fallback
    }.getOrDefault(fallback)

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }
}
