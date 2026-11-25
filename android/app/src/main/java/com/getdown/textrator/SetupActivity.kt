package com.getdown.textrator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Guided permissions: disclosure → accessibility (+ restricted-settings fix) → battery → OEM autostart. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val consent = findViewById<CheckBox>(R.id.consent)
        consent.isChecked = Prefs.consentGiven
        consent.setOnCheckedChangeListener { _, c -> Prefs.consentGiven = c; refresh() }

        findViewById<Button>(R.id.s2Btn).setOnClickListener {
            if (!Prefs.consentGiven) { toast("Please read and accept step 1 first."); return@setOnClickListener }
            if (Engine.USES_ACCESSIBILITY) {
                OemHelper.openAccessibility(this)
                toast("Find “GETDOWN Textrator” and switch it on")
            } else {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) { OemHelper.openAppInfo(this) }
                toast("Switch on “Allow display over other apps”")
            }
        }
        if (!Engine.USES_ACCESSIBILITY) {
            findViewById<TextView>(R.id.disclosure).text = getString(R.string.disclosure_go)
            findViewById<TextView>(R.id.s2Text).text = "Tap the button, then switch on Allow display over other apps for GETDOWN Textrator. When you start the bubble, Android will also ask to allow screen capture. Choose Entire screen."
        }
        findViewById<Button>(R.id.restrictedBtn).setOnClickListener { OemHelper.openAppInfo(this) }
        findViewById<Button>(R.id.s3Btn).setOnClickListener { OemHelper.requestIgnoreBattery(this) }
        findViewById<Button>(R.id.s4Btn).setOnClickListener {
            if (!OemHelper.openAutostart(this)) toast("Open Battery or Autostart and allow GETDOWN Textrator")
        }
        findViewById<Button>(R.id.done).setOnClickListener {
            if (!Prefs.consentGiven) { toast("Please accept step 1 first."); return@setOnClickListener }
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            // Go build: start the bubble straight away (asks for screen capture once).
            if (!Engine.USES_ACCESSIBILITY && Engine.isReady(this) && !Engine.isBubbleOn()) Engine.setBubble(this, true)
            finish()
        }

        // Restricted-settings help only matters for sideloaded installs on Android 13+.
        val showRestricted = Build.VERSION.SDK_INT >= 33
        findViewById<View>(R.id.restrictedHelp).visibility = if (showRestricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.restrictedBtn).visibility = if (showRestricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.s4Card).visibility = if (OemHelper.hasAutostart()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        mark(R.id.s1Title, "1. How Textrator uses your screen", Prefs.consentGiven)
        val a11y = Engine.isReady(this)
        mark(R.id.s2Title, if (Engine.USES_ACCESSIBILITY) "2. Turn on Textrator in Accessibility" else "2. Allow display over other apps", a11y)
        mark(R.id.s3Title, "3. Keep the bubble alive", OemHelper.isIgnoringBattery(this))
        findViewById<Button>(R.id.s2Btn).text = when {
            a11y -> "Turned on ✓"
            Engine.USES_ACCESSIBILITY -> "Open Accessibility settings"
            else -> "Allow display over other apps"
        }
        findViewById<Button>(R.id.s3Btn).text = if (OemHelper.isIgnoringBattery(this)) "Allowed ✓" else "Allow running in background"
        findViewById<Button>(R.id.done).text = if (a11y) "Done, start using it" else "Done (finish step 2 later)"
    }

    private fun mark(id: Int, label: String, ok: Boolean) {
        val t = findViewById<TextView>(id)
        t.text = if (ok) "✓ $label" else label
        t.setTextColor(ContextCompat.getColor(this, if (ok) R.color.leaf else R.color.ink))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
