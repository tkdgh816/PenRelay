package dev.penrelay.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class PageThumbnail(context: Context, private val book: PageBook, private val page: CanvasInfo) : View(context) {
    private data class Key(val id: String, val revision: Long, val width: Int, val height: Int, val inset: Int)
    private companion object {
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val cache = object : LruCache<Key,Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
        }
    }
    private val edge = PenRelayUi(context).dp(2)
    private var requested: Key? = null
    private var pending: Future<*>? = null

    override fun onDraw(canvas: Canvas) {
        if (width <= 2*edge || height <= 2*edge) return
        val key = Key(page.id,page.revision,width,height,edge)
        val bitmap = cache.get(key)
        if (bitmap != null) canvas.drawBitmap(bitmap,0f,0f,null)
        else if (key != requested) load(key)
    }

    private fun load(key: Key) {
        pending?.cancel(true)
        requested = key
        val version = page.copy()
        val loaded = if (isSelected) book.document.loadedImage else null
        pending = worker.submit {
            val image = loaded ?: try { book.store.loadPage(version) } catch (_: IOException) { return@submit }
            val result = Bitmap.createBitmap(key.width,key.height,Bitmap.Config.ARGB_8888)
            result.density = Bitmap.DENSITY_NONE
            val target = Canvas(result)
            val scale = minOf((key.width-2f*edge)/image.width,(key.height-2f*edge)/image.height)
            target.translate((key.width-image.width*scale)/2,(key.height-image.height*scale)/2)
            target.scale(scale,scale)
            target.clipRect(0f,0f,image.width.toFloat(),image.height.toFloat())
            target.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (stroke in image.strokes) {
                if (Thread.currentThread().isInterrupted) return@submit
                stroke.drawOnPaper(target,paint)
            }
            main.post {
                if (requested == key && page.revision == key.revision) {
                    cache.put(key,result)
                    requested = null
                    invalidate()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        pending?.cancel(true)
        requested = null
        super.onDetachedFromWindow()
    }
}
