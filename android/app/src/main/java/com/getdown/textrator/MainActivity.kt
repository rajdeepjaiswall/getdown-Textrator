package com.getdown.textrator

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The user's dashboard: service status, today's numbers and everything they've copied. */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var search: EditText
    private var suppressSwitch = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            startActivity(Intent(this, ReaderActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Backend.needsLogin()) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        if (!Prefs.consentGiven) { startActivity(Intent(this, SetupActivity::class.java)) }
        setContentView(R.layout.activity_main)

        adapter = HistoryAdapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        search = findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { reload() }
        })
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.fixSetup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<Button>(R.id.fromImage).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialSwitch>(R.id.bubbleSwitch).setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            Prefs.bubbleEnabled = on
            TractorService.instance?.refreshBubble()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        refreshStatus()
        reload()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Backend.fetchConfig() }
            showAnnouncement()
            if (Backend.needsLogin()) { startActivity(Intent(this@MainActivity, LoginActivity::class.java)); finish() }
        }
    }

    private fun showAnnouncement() {
        val a = findViewById<TextView>(R.id.announcement)
        val msg = Prefs.announcement
        a.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        a.text = msg
    }

    private fun refreshStatus() {
        val enabled = TractorService.isEnabled(this)
        val title = findViewById<TextView>(R.id.statusTitle)
        val text = findViewById<TextView>(R.id.statusText)
        val fix = findViewById<Button>(R.id.fixSetup)
        val sw = findViewById<MaterialSwitch>(R.id.bubbleSwitch)
        suppressSwitch = true
        sw.isChecked = Prefs.bubbleEnabled
        sw.isEnabled = enabled
        suppressSwitch = false
        when {
            !enabled -> {
                title.text = "Textrator is off"
                text.text = "Turn on the accessibility service to get the floating bubble."
                fix.visibility = View.VISIBLE
            }
            !Prefs.bubbleEnabled -> {
                title.text = "Bubble is hidden"
                text.text = "Switch it on, or use the Textrator tile in Quick Settings."
                fix.visibility = View.GONE
            }
            else -> {
                title.text = "Bubble is on"
                text.text = "Tap the floating bubble on any screen to select and copy its text. Drag it to move it. Long-press it to open this app."
                fix.visibility = View.GONE
            }
        }
        findViewById<TextView>(R.id.account).text =
            if (Backend.isLoggedIn) Prefs.userEmail else "Guest · by Getdown Foundation"
    }

    private fun reload() {
        val q = if (::search.isInitialized) search.text.toString() else ""
        lifecycleScope.launch {
            val (items, today, total) = withContext(Dispatchers.IO) {
                val db = HistoryDb.get(this@MainActivity)
                Triple(db.list(q), db.countToday(), db.countAll())
            }
            adapter.submit(items)
            findViewById<TextView>(R.id.statToday).text = today.toString()
            findViewById<TextView>(R.id.statTotal).text = total.toString()
            findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items: List<HistoryItem> = emptyList()

        fun submit(list: List<HistoryItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.text)
            val meta: TextView = v.findViewById(R.id.meta)
            val pin: TextView = v.findViewById(R.id.pin)
            val copy: TextView = v.findViewById(R.id.copy)
            val share: TextView = v.findViewById(R.id.share)
            val delete: TextView = v.findViewById(R.id.delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            val ctx = h.itemView.context
            h.text.text = it.text
            val method = when (it.method) { "ocr" -> "from image"; "mixed" -> "text + image"; "image" -> "photo"; else -> "text" }
            h.meta.text = "${if (it.pinned) "📌 " else ""}${ClipHelper.appLabel(ctx, it.source)} · $method · " +
                DateUtils.getRelativeTimeSpanString(it.created, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.pin.text = if (it.pinned) "Unpin" else "Pin"
            h.itemView.setOnClickListener { _ ->
                startActivity(
                    Intent(ctx, ReaderActivity::class.java)
                        .putExtra(ReaderActivity.EXTRA_TEXT, it.text)
                        .putExtra(ReaderActivity.EXTRA_SOURCE, it.source)
                        .putExtra(ReaderActivity.EXTRA_FROM_HISTORY, true)
                )
            }
            h.copy.setOnClickListener { _ -> ClipHelper.copy(ctx, it.text, it.source, "history") }
            h.share.setOnClickListener { _ -> ClipHelper.share(ctx, it.text) }
            h.pin.setOnClickListener { _ -> HistoryDb.get(ctx).setPinned(it.id, !it.pinned); reload() }
            h.delete.setOnClickListener { _ ->
                MaterialAlertDialogBuilder(ctx)
                    .setMessage("Delete this item from your history?")
                    .setPositiveButton("Delete") { _, _ -> HistoryDb.get(ctx).delete(it.id); reload() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
