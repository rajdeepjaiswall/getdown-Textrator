package com.getdown.textrator

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Email login backed by Supabase Auth.
 * Bypass: "Continue without login" (guest) is shown unless the admin switches auth to "required".
 * If the backend isn't configured, the app skips this screen entirely.
 */
class LoginActivity : AppCompatActivity() {
    private var signUpMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Backend.configured || Backend.authMode() == "off") { goNext(); return }
        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val primary = findViewById<Button>(R.id.primary)
        val toggle = findViewById<Button>(R.id.toggle)
        val forgot = findViewById<Button>(R.id.forgot)
        val guest = findViewById<Button>(R.id.guest)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val message = findViewById<TextView>(R.id.message)

        guest.visibility = if (Backend.authMode() == "required") View.GONE else View.VISIBLE

        fun show(msg: String) { message.text = msg; message.visibility = View.VISIBLE }
        fun busy(b: Boolean) {
            progress.visibility = if (b) View.VISIBLE else View.GONE
            primary.isEnabled = !b; toggle.isEnabled = !b; guest.isEnabled = !b; forgot.isEnabled = !b
        }
        fun valid(): Boolean {
            val e = email.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { show("Enter a valid email address."); return false }
            if (password.text.length < 6) { show("Password must be at least 6 characters."); return false }
            return true
        }

        toggle.setOnClickListener {
            signUpMode = !signUpMode
            primary.text = if (signUpMode) "Create account" else "Sign in"
            toggle.text = if (signUpMode) "Already have an account? Sign in" else "New here? Create an account"
            message.visibility = View.GONE
        }

        primary.setOnClickListener {
            if (!valid()) return@setOnClickListener
            busy(true)
            lifecycleScope.launch {
                val e = email.text.toString().trim(); val p = password.text.toString()
                val r = try {
                    if (signUpMode) Backend.signUp(e, p) else Backend.signIn(e, p)
                } catch (ex: Exception) {
                    AuthResult(false, "No internet connection. Check your network and try again.")
                }
                busy(false)
                if (r.ok && Backend.isLoggedIn) goNext() else show(r.message)
            }
        }

        forgot.setOnClickListener {
            val e = email.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { show("Type your email above first."); return@setOnClickListener }
            busy(true)
            lifecycleScope.launch {
                val r = try { Backend.resetPassword(e) } catch (ex: Exception) { AuthResult(false, "No internet connection.") }
                busy(false); show(r.message)
            }
        }

        guest.setOnClickListener {
            Prefs.guestMode = true
            goNext()
        }
    }

    private fun goNext() {
        val next = if (Prefs.consentGiven) MainActivity::class.java else SetupActivity::class.java
        startActivity(Intent(this, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
