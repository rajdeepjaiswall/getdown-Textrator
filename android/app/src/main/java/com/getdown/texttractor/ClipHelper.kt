package com.getdown.texttractor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

object ClipHelper {
    /** Copies, saves to the user's in-app history (dashboard) and records a usage event. */
    fun copy(ctx: Context, text: String, source: String, method: String, toast: Boolean = true) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Text Tractor", clean))
        save(ctx, clean, source, method)
        Backend.logEvent("copy", clean.length, source, method)
        // Android 13+ shows its own "Copied" confirmation; avoid a double toast.
        if (toast && Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun save(ctx: Context, text: String, source: String, method: String) {
        if (Prefs.saveHistory) HistoryDb.get(ctx).add(text, source, method)
    }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appLabel(ctx: Context, pkg: String): String {
        if (pkg.isBlank()) return "Unknown app"
        return try {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }
}
