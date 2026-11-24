package dev.penrelay.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

// 첫 바이트는 필압 강도이며 좌표에는 보정 전 필압을 보관한다.
internal fun InkStroke.record(): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeByte(pressureStrength)
        out.writeByte(brush.ordinal)
        out.writeInt(color)
        out.writeFloat(width)
        out.writeBoolean(pressure)
        out.writeByte(texture.ordinal)
        writeStoredSamples(out)
    }
    return bytes.toByteArray()
}

internal data class CanvasInfo(val width: Int, val height: Int, val id: String = UUID.randomUUID().toString(),
    var revision: Long = 0, var textured: Boolean? = false)

internal data class StrokeManifest(val cursor: JSONObject, val reconcile: Boolean, val rows: List<JSONArray>)

internal class StrokeStore(context: Context) : SQLiteOpenHelper(context, "penrelay-strokes.db", null, 1) {
    init {
        setOpenParams(SQLiteDatabase.OpenParams.Builder().setSynchronousMode("NORMAL").build())
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO meta VALUES('book',?)", arrayOf(UUID.randomUUID().toString()))
        db.execSQL("CREATE TABLE canvas(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE stroke(id TEXT PRIMARY KEY, canvas TEXT NOT NULL, layer INTEGER NOT NULL, hash TEXT NOT NULL, body BLOB NOT NULL)")
        db.execSQL("CREATE INDEX drawing_order ON stroke(canvas,layer)")
        db.execSQL("CREATE TABLE history(seq INTEGER PRIMARY KEY, token TEXT NOT NULL)")
        db.execSQL("CREATE TABLE changes(seq INTEGER NOT NULL, id TEXT NOT NULL, PRIMARY KEY(seq,id)) WITHOUT ROWID")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("지원하지 않는 필기 보관함 버전입니다.")

    fun value(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE k=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun put(key: String, value: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta VALUES(?,?)", arrayOf(key, value))

    // 목록 변경도 빈 changes 집합을 가진 독립 순번으로 확정한다.
    fun edit(action: (SQLiteDatabase, Long) -> Unit) {
        try {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.execSQL("INSERT INTO history(token) VALUES(?)", arrayOf(UUID.randomUUID().toString()))
                val seq = android.database.DatabaseUtils.longForQuery(db, "SELECT max(seq) FROM history", null)
                action(db, seq)
                db.execSQL("DELETE FROM history WHERE seq<=?", arrayOf(seq - 2048))
                db.execSQL("DELETE FROM changes WHERE seq<=?", arrayOf(seq - 2048))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: SQLiteException) {
            throw IOException("필기 보관함 저장 실패", e)
        }
    }

    fun titles(): MutableMap<String,String> = readableDatabase.rawQuery(
        "SELECT substr(k,7),value FROM meta WHERE k GLOB 'title-*'", null).use { rows ->
        mutableMapOf<String,String>().apply { while (rows.moveToNext()) put(rows.getString(0),rows.getString(1)) }
    }

    fun create(db: SQLiteDatabase, page: CanvasInfo) {
        db.execSQL("INSERT INTO canvas VALUES(?,?,?,?)", arrayOf<Any>(page.id, page.revision, page.width, page.height))
        put("texture-${page.id}", "0")
    }

    // 전송을 제안한 시점의 원본은 그 뒤에 바뀌는 획에 한해서만 보관한다.
    // UI 스레드가 묶음의 시작·종료와 편집을 순서대로 처리한다.
    private var syncBatch: String? = null
    private var offered: Map<String,String>? = null
    private var originalsTouched = false
    private var syncSchemaReady = false
    private fun prepareSyncTables(db: SQLiteDatabase) {
        if (syncSchemaReady) return
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_original(id TEXT PRIMARY KEY,canvas TEXT,layer INTEGER,body BLOB)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_order ON sync_original(canvas,layer)")
        db.execSQL("""CREATE VIEW IF NOT EXISTS sync_stroke AS
            SELECT id,canvas,layer,body FROM stroke WHERE NOT EXISTS
            (SELECT 1 FROM sync_original o WHERE o.id=stroke.id)
            UNION ALL SELECT id,canvas,layer,body FROM sync_original WHERE canvas IS NOT NULL""")
        // 프로세스가 묶음 전송 중 종료됐다면 남아 있는 원본을 여기서 정리한다.
        db.delete("sync_original",null,null)
        originalsTouched = false
        syncSchemaReady = true
    }

    fun beginBatch(token: String, strokes: Map<String,String>?) {
        check(syncBatch == null)
        prepareSyncTables(writableDatabase)
        offered = strokes
        syncBatch = token
    }

    fun endBatch(token: String) {
        if (syncBatch == token) {
            syncBatch = null
            offered = null
            if (originalsTouched) {
                syncSchemaReady = false
                try {
                    writableDatabase.delete("sync_original",null,null)
                    originalsTouched = false
                    syncSchemaReady = true
                } catch (e: SQLiteException) {
                    // 다음 묶음이 시작되기 전에 prepareSyncTables가 정리를 재시도한다.
                    android.util.Log.w("StrokeStore","동기화 임시 획 정리 지연",e)
                }
            }
        }
    }

    private fun preserveStroke(db: SQLiteDatabase, id: String) {
        if (syncBatch == null || offered?.containsKey(id) == false) return
        originalsTouched = true
        db.execSQL("INSERT OR IGNORE INTO sync_original SELECT id,canvas,layer,body FROM stroke WHERE id=?", arrayOf(id))
        // 새 획은 묶음 기준 시점에는 없었다.
        if (offered == null) db.execSQL("INSERT OR IGNORE INTO sync_original(id) VALUES(?)", arrayOf(id))
    }

    fun remove(db: SQLiteDatabase, id: String) {
        if (syncBatch != null) {
            val requested = offered
            if (requested == null) {
                originalsTouched = true
                db.execSQL("INSERT OR IGNORE INTO sync_original SELECT id,canvas,layer,body FROM stroke WHERE canvas=?", arrayOf(id))
            } else {
                requested.filterValues { it == id }.keys.chunked(256).forEach { ids ->
                    originalsTouched = true
                    val args = ids.joinToString(",") { "?" }
                    db.execSQL("INSERT OR IGNORE INTO sync_original SELECT id,canvas,layer,body FROM stroke WHERE id IN ($args)", ids.toTypedArray())
                }
            }
        }
        db.delete("stroke", "canvas=?", arrayOf(id))
        db.delete("canvas", "id=?", arrayOf(id))
        db.delete("meta", "k=?", arrayOf("texture-$id"))
    }

    fun save(page: CanvasInfo, before: PageImage, next: PageImage) {
        val added = next.strokes.lastOrNull()
        val append = added != null && added.layer == next.revision && next.strokes.size == before.strokes.size + 1
        val textured = if (append && page.textured != null) page.textured == true || added?.texture != InkTexture.Smooth
            else next.strokes.any { it.texture != InkTexture.Smooth }
        edit { db, seq ->
            val updated = db.compileStatement("UPDATE canvas SET revision=?,width=?,height=? WHERE id=? AND revision=?").use {
                it.bindLong(1,next.revision); it.bindLong(2,next.width.toLong()); it.bindLong(3,next.height.toLong())
                it.bindString(4,next.id); it.bindLong(5,before.revision); it.executeUpdateDelete()
            }
            if (updated != 1) throw IOException("다른 캔버스 버전이 이미 저장되었습니다.")
            fun writeStroke(stroke: InkStroke) {
                preserveStroke(db, stroke.id)
                val body = stroke.record()
                val hash = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(body), android.util.Base64.NO_WRAP)
                db.execSQL("INSERT OR REPLACE INTO stroke VALUES(?,?,?,?,?)", arrayOf(stroke.id,next.id,stroke.layer,hash,body))
                db.execSQL("INSERT INTO changes VALUES(?,?)", arrayOf<Any>(seq,stroke.id))
            }
            if (textured != page.textured) put("texture-${next.id}", if (textured) "1" else "0")
            if (append) {
                writeStroke(checkNotNull(added))
                return@edit
            }
            val previous = before.strokes.associateBy { it.id }.toMutableMap()
            for (stroke in next.strokes) {
                if (previous.remove(stroke.id) !== stroke) writeStroke(stroke)
            }
            for (id in previous.keys) {
                preserveStroke(db, id)
                db.delete("stroke","id=?",arrayOf(id))
                db.execSQL("INSERT INTO changes VALUES(?,?)", arrayOf<Any>(seq,id))
            }
        }
        page.textured = textured
    }

    fun documents(): List<CanvasInfo> =
        readableDatabase.rawQuery("""SELECT c.id,c.revision,c.width,c.height,m.value FROM canvas c
            LEFT JOIN meta m ON m.k='texture-' || c.id ORDER BY c.rowid""", null).use { rows ->
            buildList {
                while (rows.moveToNext()) add(CanvasInfo(rows.getInt(2),rows.getInt(3),rows.getString(0),rows.getLong(1),
                    rows.getString(4)?.let { it != "0" }))
            }
        }

    fun loadPage(page: CanvasInfo): PageImage = try {
        val result = readPage(page, "stroke")
        val current = readableDatabase.rawQuery("SELECT revision FROM canvas WHERE id=?",arrayOf(page.id)).use {
            it.moveToFirst() && it.getLong(0) == page.revision
        }
        if (!current) throw IOException("캔버스 버전이 변경되었습니다.")
        result
    } catch (e: SQLiteException) {
        throw IOException("필기 보관함을 읽을 수 없습니다.", e)
    }

    fun batchPage(page: CanvasInfo) = readPage(page, "sync_stroke")

    private fun readPage(page: CanvasInfo, table: String): PageImage {
        val strokes = mutableListOf<InkStroke>()
        // 보통 크기의 획은 한 커서에서 읽고, CursorWindow보다 커질 수 있는 획만 나눠 읽는다.
        readableDatabase.rawQuery("""SELECT id,layer,length(body),
            CASE WHEN length(body)<=262144 THEN body END FROM $table WHERE canvas=? ORDER BY layer""",
            arrayOf(page.id)).use { rows ->
            while (rows.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val id = rows.getString(0)
                val bytes = if (rows.isNull(3)) readBody(table,id,rows.getInt(2)) else rows.getBlob(3)
                strokes.add(decode(id,rows.getLong(1),bytes))
            }
        }
        return PageImage(page.id,page.revision,page.width,page.height,strokes)
    }

    fun batchRecord(id: String): ByteArray {
        val size = readableDatabase.rawQuery("SELECT length(body),CASE WHEN length(body)<=262144 THEN body END FROM sync_stroke WHERE id=?",arrayOf(id)).use {
            if (!it.moveToFirst()) throw IOException("전송할 획을 찾을 수 없습니다.")
            if (!it.isNull(1)) return it.getBlob(1)
            it.getInt(0)
        }
        return readBody("sync_stroke", id, size)
    }

    private fun readBody(table: String, id: String, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val part = readableDatabase.rawQuery("SELECT substr(body,?,262144) FROM $table WHERE id=?",
                arrayOf((offset + 1).toString(),id)).use {
                if (!it.moveToFirst()) throw IOException("획을 읽을 수 없습니다.")
                it.getBlob(0)
            }
            if (part.isEmpty() || part.size > size-offset) throw IOException("획 데이터 크기가 변경되었습니다.")
            part.copyInto(bytes,offset)
            offset += part.size
        }
        return bytes
    }

    private fun decode(id: String, layer: Long, bytes: ByteArray): InkStroke =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val strength = input.readUnsignedByte()
            val brush = Brush.entries[input.readUnsignedByte()]
            val color = input.readInt()
            val width = input.readFloat()
            val pressure = input.readBoolean()
            val texture = InkTexture.entries[input.readUnsignedByte()]
            val points = List(input.readInt()) { Sample(input.readFloat(),input.readFloat(),input.readFloat()) }
            InkStroke(id,brush,color,width,pressure,points,texture,strength,layer)
        }

    fun manifest(peer: JSONObject): StrokeManifest {
        val db = readableDatabase
        val book = value("book")!!
        val base = peer.optLong("seq",-1)
        val matches = peer.optString("book") == book && db.rawQuery(
            "SELECT token FROM history WHERE seq=?", arrayOf(base.toString())).use {
            it.moveToFirst() && it.getString(0) == peer.optString("token")
        }
        val head = db.rawQuery("SELECT seq,token FROM history ORDER BY seq DESC LIMIT 1",null).use {
            check(it.moveToFirst())
            JSONObject().put("book",book).put("seq",it.getLong(0)).put("token",it.getString(1))
        }
        val sql = if (matches) """SELECT changed.id,s.canvas,s.layer,s.hash FROM
            (SELECT DISTINCT id FROM changes WHERE seq>?) changed LEFT JOIN stroke s ON s.id=changed.id"""
            else "SELECT id,canvas,layer,hash FROM stroke"
        val rows = mutableListOf<JSONArray>()
        db.rawQuery(sql,if (matches) arrayOf(base.toString()) else null).use {
            while (it.moveToNext()) {
                val row = JSONArray().put(it.getString(0))
                if (!it.isNull(1)) row.put(it.getString(1)).put(it.getLong(2)).put(it.getString(3))
                rows.add(row)
            }
        }
        return StrokeManifest(head,!matches,rows)
    }
}
