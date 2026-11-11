package tv.qrmzi.client.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One watch-history row, including what is needed to resume it. */
data class HistoryEntry(
    val card: Card,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesUrl: String? = null
) {
    val progress: Float get() = Playback.progress(positionMs, durationMs)
    val isResumable: Boolean get() = Playback.isResumable(positionMs, durationMs)
}

/**
 * Lightweight persistence (framework SQLite, no Room/KSP): favourites, watch
 * history and recent searches, keyed by the page URL / query text.
 *
 * Design notes for the 1 GB target:
 * * three tiny tables, no BLOBs, no video files — the whole database stays in
 *   the kilobyte range;
 * * history is pruned to the newest 200 rows and searches to 12;
 * * every write is a single-row upsert, so it is cheap enough to run from the
 *   player's periodic progress heartbeat.
 */
class Store(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "qrmzi.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_FAVORITES)
        db.execSQL(CREATE_HISTORY)
        db.execSQL(CREATE_SEARCHES)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_updated ON history(updatedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_searches_at ON searches(at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive only: v1 had favourites + history, v2 adds search history,
        // v3 adds the `ribbon` badge column to both card tables.
        if (oldVersion < 1) {
            onCreate(db)
            return
        }
        if (oldVersion < 2) {
            db.execSQL(CREATE_SEARCHES)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_searches_at ON searches(at DESC)")
        }
        if (oldVersion < 3) {
            healColumns(db)
        }
    }

    /**
     * `CREATE TABLE IF NOT EXISTS` never adds a column to a table that already
     * exists, so a database written by an earlier build (or restored from a
     * backup) can be missing a column the current queries select. Healing here
     * — on every open, not only on a version bump — means such a database can
     * never make the UI throw "no such column".
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.beginTransaction()
        try {
            db.execSQL(CREATE_FAVORITES)
            db.execSQL(CREATE_HISTORY)
            db.execSQL(CREATE_SEARCHES)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_updated ON history(updatedAt DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_searches_at ON searches(at DESC)")
            healColumns(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Adds every column the current queries expect but the table lacks. */
    private fun healColumns(db: SQLiteDatabase) {
        for ((column, type) in FAVORITE_COLUMNS) addColumnIfMissing(db, "favorites", column, type)
        for ((column, type) in HISTORY_COLUMNS) addColumnIfMissing(db, "history", column, type)
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        type: String
    ) {
        if (hasColumn(db, table, column)) return
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $column $type") }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(1).equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }

    // ------------------------------------------------------------- favourites
    fun addFavorite(card: Card) {
        val values = ContentValues().apply {
            put("url", card.url)
            put("title", card.title)
            put("image", card.image)
            put("kind", card.kind.name)
            put("number", card.number)
            put("ribbon", card.ribbon)
            put("addedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFavorite(url: String) {
        writableDatabase.delete("favorites", "url = ?", arrayOf(url))
        writableDatabase.delete("favorites", "url = ?", arrayOf(url.trimEnd('/')))
    }

    fun isFavorite(url: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE url = ? OR url = ? LIMIT 1",
            arrayOf(url, url.trimEnd('/'))
        ).use { it.moveToFirst() }

    fun toggleFavorite(card: Card): Boolean {
        val now = isFavorite(card.url)
        if (now) removeFavorite(card.url) else addFavorite(card)
        return !now
    }

    fun favorites(): List<Card> {
        val out = mutableListOf<Card>()
        readableDatabase.rawQuery(
            "SELECT url, title, image, kind, number, ribbon FROM favorites ORDER BY addedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out += cardOf(c)
        }
        return out
    }

    // ---------------------------------------------------------------- history
    /**
     * Records that [card] was opened. Existing resume data is preserved unless
     * explicit non-negative values are passed, so merely opening a detail page
     * never wipes a saved position.
     */
    fun recordHistory(
        card: Card,
        seriesUrl: String? = null,
        positionMs: Long = -1,
        durationMs: Long = -1
    ) {
        val existing = stateOf(card.url)
        val values = ContentValues().apply {
            put("url", card.url)
            put("title", card.title.ifBlank { existing?.title ?: "" })
            // A caller without artwork must not erase the stored thumbnail.
            put("image", card.image ?: existing?.image)
            put("kind", card.kind.name)
            put("number", card.number)
            put("ribbon", card.ribbon)
            put("seriesUrl", seriesUrl ?: existing?.seriesUrl)
            put("positionMs", if (positionMs >= 0) positionMs else existing?.positionMs ?: 0L)
            put("durationMs", if (durationMs >= 0) durationMs else existing?.durationMs ?: 0L)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        // keep the table small on a 1 GB device
        writableDatabase.execSQL(
            "DELETE FROM history WHERE url NOT IN " +
                "(SELECT url FROM history ORDER BY updatedAt DESC LIMIT 200)"
        )
    }

    /**
     * Stores progress reported by the player. Returns true when the row was
     * written. Unreliable states (no duration, warm-up position, overflow) are
     * ignored; a finished episode has its position cleared so it stops showing
     * up in "متابعة المشاهدة" while staying in the watch history.
     */
    fun updatePosition(url: String, positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            if (Playback.isFinished(positionMs, durationMs)) {
                put("positionMs", 0L)
                put("durationMs", 0L)
            } else if (Playback.shouldStore(positionMs, durationMs)) {
                put("positionMs", positionMs)
                put("durationMs", durationMs)
            } else {
                return false
            }
            put("updatedAt", now)
        }
        return writableDatabase.update(
            "history",
            values,
            "url = ? OR url = ?",
            arrayOf(url, url.trimEnd('/'))
        ) > 0
    }

    fun history(limit: Int = 100): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        readableDatabase.rawQuery(
            "SELECT url, title, image, kind, number, ribbon, positionMs, durationMs, updatedAt, seriesUrl " +
                "FROM history ORDER BY updatedAt DESC LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(
                    cardOf(c),
                    c.getLong(6),
                    c.getLong(7),
                    c.getLong(8),
                    if (c.isNull(9)) null else c.getString(9)
                )
            }
        }
        return out
    }

    /**
     * The "continue watching" row: only rows with a trustworthy position that
     * is past the intro and not finished, newest first.
     */
    fun continueWatching(limit: Int = 12): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        val min = Playback.MIN_RESUME_MS
        val finished = Playback.FINISHED_FRACTION.toDouble()
        readableDatabase.rawQuery(
            "SELECT url, title, image, kind, number, ribbon, positionMs, durationMs, updatedAt, seriesUrl " +
                "FROM history WHERE positionMs >= $min AND durationMs > 0 " +
                "AND (CAST(positionMs AS REAL) / durationMs) < $finished " +
                "ORDER BY updatedAt DESC LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(
                    cardOf(c),
                    c.getLong(6),
                    c.getLong(7),
                    c.getLong(8),
                    if (c.isNull(9)) null else c.getString(9)
                )
            }
        }
        return out
    }

    /** Position to resume from, or 0 when there is nothing reliable. */
    fun resumePosition(url: String): Long = resumeOf(url)?.positionMs ?: 0L

    /** Resume data for a single page (null when the row is missing/not resumable). */
    fun resumeOf(url: String): HistoryEntry? =
        readableDatabase.rawQuery(
            "SELECT url, title, image, kind, number, ribbon, positionMs, durationMs, updatedAt, seriesUrl " +
                "FROM history WHERE url = ? OR url = ? LIMIT 1",
            arrayOf(url, url.trimEnd('/'))
        ).use { c ->
            if (!c.moveToFirst()) return null
            val entry = HistoryEntry(
                cardOf(c),
                c.getLong(6),
                c.getLong(7),
                c.getLong(8),
                if (c.isNull(9)) null else c.getString(9)
            )
            if (entry.isResumable) entry else null
        }

    fun progressOf(url: String): Long =
        readableDatabase.rawQuery(
            "SELECT positionMs FROM history WHERE url = ? OR url = ? LIMIT 1",
            arrayOf(url, url.trimEnd('/'))
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun removeHistory(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    fun countHistory(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun hasHistory(url: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM history WHERE url = ? OR url = ? LIMIT 1",
            arrayOf(url, url.trimEnd('/'))
        ).use { it.moveToFirst() }

    /** Marks an existing row as "just opened" without touching its progress. */
    fun touchHistory(url: String) {
        val values = ContentValues().apply { put("updatedAt", System.currentTimeMillis()) }
        writableDatabase.update(
            "history",
            values,
            "url = ? OR url = ?",
            arrayOf(url, url.trimEnd('/'))
        )
    }

    fun countFavorites(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM favorites", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------------------------------------------------------- search history
    /** Stores [raw] as the most recent search and returns the new list. */
    fun addSearch(raw: String): List<String> {
        val query = Playback.normalizeQuery(raw)
        if (query.isEmpty()) return searches()
        val values = ContentValues().apply {
            put("query", query)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "searches",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        writableDatabase.execSQL(
            "DELETE FROM searches WHERE query NOT IN " +
                "(SELECT query FROM searches ORDER BY at DESC LIMIT ${Playback.MAX_SEARCHES})"
        )
        return searches()
    }

    fun searches(limit: Int = Playback.MAX_SEARCHES): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT query FROM searches ORDER BY at DESC LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun removeSearch(query: String) {
        writableDatabase.delete("searches", "query = ?", arrayOf(query))
    }

    fun clearSearches() {
        writableDatabase.delete("searches", null, null)
    }

    fun countSearches(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM searches", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ------------------------------------------------------------------ helpers
    private class State(
        val title: String,
        val image: String?,
        val positionMs: Long,
        val durationMs: Long,
        val seriesUrl: String?
    )

    private fun stateOf(url: String): State? =
        readableDatabase.rawQuery(
            "SELECT title, image, positionMs, durationMs, seriesUrl FROM history " +
                "WHERE url = ? OR url = ? LIMIT 1",
            arrayOf(url, url.trimEnd('/'))
        ).use { c ->
            if (!c.moveToFirst()) null
            else State(
                title = c.getString(0),
                image = if (c.isNull(1)) null else c.getString(1),
                positionMs = c.getLong(2),
                durationMs = c.getLong(3),
                seriesUrl = if (c.isNull(4)) null else c.getString(4)
            )
        }

    private fun cardOf(c: android.database.Cursor): Card {
        val ribbonIndex = c.getColumnIndex("ribbon")
        return Card(
            url = c.getString(0),
            title = c.getString(1),
            image = if (c.isNull(2)) null else c.getString(2),
            kind = runCatching { Kind.valueOf(c.getString(3)) }.getOrDefault(Kind.UNKNOWN),
            number = if (c.isNull(4)) null else c.getInt(4),
            ribbon = if (ribbonIndex >= 0 && !c.isNull(ribbonIndex)) c.getString(ribbonIndex) else null
        )
    }

    private companion object {
        /** v3 = `ribbon` badge column on favourites + history. */
        const val DB_VERSION = 3

        /**
         * Non-key columns the card queries read. Each entry is what a fresh
         * install creates; `healColumns` adds any of them that an older
         * database is missing. Types carry `DEFAULT` where SQLite requires it
         * for `ALTER TABLE ... ADD COLUMN ... NOT NULL`.
         */
        val FAVORITE_COLUMNS = listOf(
            "title" to "TEXT NOT NULL DEFAULT ''",
            "image" to "TEXT",
            "kind" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
            "number" to "INTEGER",
            "ribbon" to "TEXT",
            "addedAt" to "INTEGER NOT NULL DEFAULT 0"
        )

        val HISTORY_COLUMNS = listOf(
            "title" to "TEXT NOT NULL DEFAULT ''",
            "image" to "TEXT",
            "kind" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
            "number" to "INTEGER",
            "ribbon" to "TEXT",
            "seriesUrl" to "TEXT",
            "positionMs" to "INTEGER NOT NULL DEFAULT 0",
            "durationMs" to "INTEGER NOT NULL DEFAULT 0",
            "updatedAt" to "INTEGER NOT NULL DEFAULT 0"
        )

        const val CREATE_FAVORITES =
            """CREATE TABLE IF NOT EXISTS favorites (
                 url TEXT PRIMARY KEY,
                 title TEXT NOT NULL,
                 image TEXT,
                 kind TEXT NOT NULL,
                 number INTEGER,
                 ribbon TEXT,
                 addedAt INTEGER NOT NULL
               )"""

        const val CREATE_HISTORY =
            """CREATE TABLE IF NOT EXISTS history (
                 url TEXT PRIMARY KEY,
                 title TEXT NOT NULL,
                 image TEXT,
                 kind TEXT NOT NULL,
                 number INTEGER,
                 seriesUrl TEXT,
                 positionMs INTEGER NOT NULL DEFAULT 0,
                 durationMs INTEGER NOT NULL DEFAULT 0,
                 updatedAt INTEGER NOT NULL
               )"""

        /** `COLLATE NOCASE` so "Ask" and "ask" are one entry. */
        const val CREATE_SEARCHES =
            """CREATE TABLE IF NOT EXISTS searches (
                 query TEXT PRIMARY KEY COLLATE NOCASE,
                 at INTEGER NOT NULL
               )"""
    }
}
