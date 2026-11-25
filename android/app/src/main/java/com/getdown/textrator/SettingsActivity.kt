package com.getdown.textrator

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bind(R.id.swBubble, Prefs.bubbleEnabled) { Prefs.bubbleEnabled = it; applyBubble() }
        bind(R.id.swSmart, Prefs.smartExpand) { Prefs.smartExpand = it; applyBubble() }
        bind(R.id.swOcr, Prefs.useOcr) { Prefs.useOcr = it }
        bind(R.id.swHistory, Prefs.saveHistory) { Prefs.saveHistory = it }
        bind(R.id.swHaptics, Prefs.haptics) { Prefs.haptics = it }

        val sizeLabel = findViewById<TextView>(R.id.sizeLabel)
        val size = findViewById<SeekBar>(R.id.size)
        size.progress = Prefs.bubbleSizeDp - 40
        sizeLabel.text = "Bubble size: ${Prefs.bubbleSizeDp} dp"
        size.setOnSeekBarChangeListener(seek({ p -> sizeLabel.text = "Bubble size: ${p + 40} dp" }) { p ->
            Prefs.bubbleSizeDp = p + 40; applyBubble()
        })

        val opLabel = findViewById<TextView>(R.id.opacityLabel)
        val op = findViewById<SeekBar>(R.id.opacity)
        op.progress = Prefs.bubbleOpacity - 25
        opLabel.text = "See-through level: ${100 - Prefs.bubbleOpacity}%"
        op.setOnSeekBarChangeListener(seek({ p -> opLabel.text = "See-through level: ${100 - (p + 25)}%" }) { p ->
            Prefs.bubbleOpacity = p + 25; applyBubble()
        })

        findViewById<Button>(R.id.permissions).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<Button>(R.id.clear).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear history?")
                .setMessage("This deletes everything you've copied with Textrator on this phone. You can't undo this.")
                .setPositiveButton("Clear") { _, _ -> HistoryDb.get(this).clear() }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<TextView>(R.id.version).text =
            "GETDOWN Textrator v${BuildConfig.VERSION_NAME}\nby Getdown Foundation · getdownfoundation.in"
    }

    override fun onResume() {
        super.onResume()
        val accountText = findViewById<TextView>(R.id.accountText)
        val btn = findViewById<Button>(R.id.accountBtn)
        if (Backend.isLoggedIn) {
            accountText.text = "Signed in as ${Prefs.userEmail}"
            btn.text = "Log out"
            btn.setOnClickListener {
                Backend.signOut()
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        } else {
            accountText.text = if (Backend.configured) "Using as guest" else "Offline mode (no account needed)"
            btn.text = "Log in or create account"
            btn.isEnabled = Backend.configured
            btn.setOnClickListener {
                Prefs.guestMode = false
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
    }

    private fun bind(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun applyBubble() { TractorService.instance?.refreshBubble() }

    private fun seek(onMove: (Int) -> Unit, onDone: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { onMove(p) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) { onDone(s.progress) }
    }
}
