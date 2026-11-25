package com.getdown.textrator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class HistoryItem(
    val id: Long,
    val text: String,
    val source: String,
    val method: String,
    val created: Long,
    val pinned: Boolean,
)

/** Local history of everything copied (the user's own dashboard) + an offline queue of usage events. */
class HistoryDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "textrator.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, source TEXT, " +
                "method TEXT, chars INTEGER, created INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_history_created ON history(created)")
        db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(text: String, source: String, method: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1
        // Skip exact duplicates copied within the last minute (double taps).
        readableDatabase.rawQuery(
            "SELECT id FROM history WHERE text = ? AND created > ? LIMIT 1",
            arrayOf(clean, (System.currentTimeMillis() - 60_000).toString())
        ).use { if (it.moveToFirst()) return it.getLong(0) }
        val v = ContentValues().apply {
            put("text", clean); put("source", source); put("method", method)
            put("chars", clean.length); put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("history", null, v)
    }

    fun list(query: String?, limit: Int = 500): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        val q = query?.trim().orEmpty()
        val c = if (q.isEmpty()) {
            readableDatabase.rawQuery(
                "SELECT id,text,source,method,created,pinned FROM history ORDER BY pinned DESC, created DESC LIMIT $limit", null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id,text,source,method,created,pinned FROM history WHERE text LIKE ? ORDER BY pinned DESC, created DESC LIMIT $limit",
                arrayOf("%$q%")
            )
        }
        c.use {
            while (it.moveToNext()) {
                out.add(
                    HistoryItem(
                        it.getLong(0), it.getString(1), it.getString(2) ?: "", it.getString(3) ?: "",
                        it.getLong(4), it.getInt(5) == 1
                    )
                )
            }
        }
        return out
    }

    fun delete(id: Long) { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) }
    fun clear() { writableDatabase.delete("history", null, null) }
    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.update("history", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun countAll(): Int = count(null)
    fun countToday(): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return count(cal.timeInMillis)
    }

    private fun count(since: Long?): Int {
        val c = if (since == null) readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null)
        else readableDatabase.rawQuery("SELECT COUNT(*) FROM history WHERE created >= ?", arrayOf(since.toString()))
        c.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun addPending(payload: String) {
        writableDatabase.insert("pending", null, ContentValues().apply { put("payload", payload) })
        // Never let the offline queue grow without limit.
        writableDatabase.execSQL("DELETE FROM pending WHERE id NOT IN (SELECT id FROM pending ORDER BY id DESC LIMIT 2000)")
    }

    fun takePending(limit: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id,payload FROM pending ORDER BY id LIMIT $limit", null).use {
            while (it.moveToNext()) out.add(it.getLong(0) to it.getString(1))
        }
        return out
    }

    fun deletePending(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM pending WHERE id IN (${ids.joinToString(",")})")
    }

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(ctx: Context): HistoryDb =
            inst ?: synchronized(this) { inst ?: HistoryDb(ctx.applicationContext).also { inst = it } }
    }
}
