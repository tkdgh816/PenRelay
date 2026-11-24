package dev.penrelay.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.penrelay.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors

// 세션 응답과 페이지 선택은 UI 스레드에서 받고, 불변 페이지의 PNG 변환과 송신은 별도로 수행한다.
internal class BookPublisher(private val book: PageBook, private val queue: SessionOutbox,
    private val vectors: Boolean, private val navigation: Boolean, private val texturedVectors: Boolean,
    private var peerCursor: JSONObject? = null, private val reconnect: () -> Unit, private val status: (String) -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var batch: String? = null
    private var proposed = emptyMap<String,CanvasInfo>()
    private var batchCursor: JSONObject? = null
    private var offeredStrokes = mutableMapOf<String,String>()
    private var dirtySince: Long? = null
    private var publishAt = 0L
    private val publish = Runnable { try { startBatch() } catch (e: Exception) { failed(e) } }
    @Volatile private var closed = false
    private var navigationSequence = 0L
    fun selectionChanged() {
        if (!navigation || closed) return
        val sequence = ++navigationSequence
        val body = JSONObject().put("selected",book.selected.id).put("navSeq",sequence).toString().encodeToByteArray()
        fun offer() {
            if (closed || sequence != navigationSequence) return
            if (queue.offer(FrameKind.PAGE_CURRENT,body) == OfferResult.BUSY) main.postDelayed(::offer,32)
        }
        offer()
    }
    fun changed(deferEdits: Boolean = false) {
        if (closed) return
        status("동기화 중")
        val now = SystemClock.uptimeMillis()
        val first = dirtySince ?: now
        val alreadyDue = dirtySince != null && publishAt <= now
        dirtySince = first
        publishAt = if (!deferEdits || alreadyDue) now else minOf(now + 650, first + 3000)
        scheduleBatch()
    }
    private fun scheduleBatch() {
        main.removeCallbacks(publish)
        if (closed || batch != null || dirtySince == null) return
        if (publishAt <= SystemClock.uptimeMillis()) publish.run() else main.postAtTime(publish, publishAt)
    }
    private fun startBatch() {
        val token = UUID.randomUUID().toString()
        batch = token
        dirtySince = null
        val cursor = peerCursor
        val manifest = cursor?.let(book.store::manifest)
        offeredStrokes = mutableMapOf()
        manifest?.rows?.forEach { if (it.length() > 1) offeredStrokes[it.getString(0)] = it.getString(1) }
        book.store.beginBatch(token, if (manifest == null) null else offeredStrokes)
        proposed = book.documents().associate { it.id to it.copy() }
        val list = JSONArray()
        val titles = JSONObject()
        proposed.values.forEach {
            book.customTitle(it.id)?.let { title -> titles.put(it.id,title) }
            val entry = JSONArray().put(it.id).put(it.revision)
            if (vectors) entry.put(it.width).put(it.height)
            if (vectors && texturedVectors && it.textured != false) entry.put("ink2")
            list.put(entry)
        }
        val body = JSONObject().put("batch",token).put("pages",list).put("titles",titles)
            .put("groups",book.layout()).put("selected",book.selected.id).put("navSeq",navigationSequence).toString().encodeToByteArray()
        if (manifest == null) {
            submit { send(FrameKind.PAGE_LIST, body) }
            return
        }
        // 목록과 순번은 UI 편집 경계에서 함께 잡고, 본문은 PC가 요청할 때 읽는다.
        batchCursor = manifest.cursor
        val metadata = JSONObject(body.decodeToString()).put("cursor",manifest.cursor)
            .put("base",cursor).put("reconcile",manifest.reconcile)
        submit {
            if (!send(FrameKind.PAGE_LIST, metadata.toString().encodeToByteArray())) return@submit
            for (rows in manifest.rows.chunked(256)) {
                val part = JSONObject().put("batch",token).put("records",JSONArray(rows))
                if (!send(FrameKind.PAGE_LIST,part.toString().encodeToByteArray())) return@submit
            }
            send(FrameKind.PAGE_LIST,JSONObject().put("batch",token).put("recordsEnd",true).toString().encodeToByteArray())
        }
    }
    fun reply(body: JSONObject) {
        if (closed || body.optString("batch") != batch) return
        if (body.optBoolean("complete")) {
            batchCursor?.let { peerCursor = it }
            batchCursor = null
            book.store.endBatch(body.getString("batch"))
            offeredStrokes.clear()
            batch = null
            proposed = emptyMap()
            if (dirtySince != null) scheduleBatch() else {
                val receipt = JSONObject().put("batch",body.getString("batch")).toString().encodeToByteArray()
                submit { send(FrameKind.PAGE_CURRENT,receipt) }
                status("동기화 완료")
            }
            return
        }
        if (body.has("stroke")) {
            val id = body.getString("stroke")
            if (id !in offeredStrokes) return failed(java.io.IOException("제안 목록에 없는 획 요청입니다."))
            val token = batch
            submit {
                sendParts(id,book.store.batchRecord(id))
                main.post { if (batch == token) offeredStrokes.remove(id) }
            }
            return
        }
        if (batchCursor != null) return failed(java.io.IOException("획 전송 묶음에 페이지 전체가 요청되었습니다."))
        val version = proposed[body.optString("need")] ?: return failed(java.io.IOException("제안 목록에 없는 페이지 요청입니다."))
        val ink = vectors && body.optString("format") == "ink1"
        val textured = texturedVectors && body.optString("format") == "ink2"
        submit {
            val page = book.store.batchPage(version)
            val output = ByteArrayOutputStream()
            when {
                ink || textured -> page.writeVectors(output, textured)
                !vectors && (page.width != 1024 || page.height != 640) -> page.resized(1024,640).png(output)
                else -> page.png(output)
            }
            sendParts(page.id,output.toByteArray())
        }
    }
    private fun sendParts(key: String, bytes: ByteArray) {
        val id = UUID.fromString(key)
        var offset = 0
        while (offset < bytes.size && !closed) {
            val size = minOf(32*1024,bytes.size-offset)
            val part = ByteBuffer.allocate(24+size).putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits).putInt(offset).putInt(bytes.size).put(bytes,offset,size)
            if (!send(FrameKind.PAGE_CHUNK,part.array())) return
            offset += size
        }
    }
    private fun submit(action: () -> Unit) {
        worker.execute {
            try { action() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (e: Exception) { main.post { failed(e) } }
        }
    }
    private fun send(kind: FrameKind, body: ByteArray): Boolean {
        if (closed) return false
        if (!queue.sendCanvasData(kind,body)) throw java.io.IOException("보관함 전송이 중단되었습니다.")
        return true
    }
    private fun failed(error: Exception) {
        if (closed) return
        try { close() } finally {
            status("보관함 재연결 중 · ${error.localizedMessage}")
            reconnect()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(publish)
        worker.shutdownNow()
        batch?.let(book.store::endBatch)
        proposed = emptyMap()
    }
}
