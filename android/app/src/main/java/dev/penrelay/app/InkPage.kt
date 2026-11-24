package dev.penrelay.app

import android.graphics.*
import java.io.OutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.lang.ref.SoftReference
import java.util.UUID
import kotlin.math.*

internal enum class Brush(val label: String) {
    Pen("펜"), Pencil("연필"), Marker("형광펜"), Partial("부분 지우개"), Whole("획 지우개");
    val erases get() = this == Partial || this == Whole
}
internal data class Sample(val x: Float, val y: Float, val pressure: Float)
internal data class InkStroke(val id: String, val brush: Brush, val color: Int, val width: Float,
    val pressure: Boolean, val points: List<Sample>, val texture: InkTexture = InkTexture.Smooth,
    val pressureStrength: Int = 100, val layer: Long = 0) {
    private var storedSamples: SoftReference<ByteArray>? = null
    fun writeStoredSamples(out: DataOutputStream) {
        out.writeInt(points.size)
        val packed = storedSamples?.get() ?: ByteBuffer.allocate(points.size * 12).apply {
            for (p in points) { putFloat(p.x); putFloat(p.y); putFloat(p.pressure) }
        }.array().also { storedSamples = SoftReference(it) }
        out.write(packed)
    }
    fun effectivePressure(p: Sample) = if (pressureStrength == 100) p.pressure
        else .5f + (p.pressure - .5f) * (pressureStrength / 100f)
    fun radius(p: Sample): Float {
        val factor = if (pressure) .25f + 1.5f * effectivePressure(p) else 1f
        return width * factor * when (brush) { Brush.Pencil -> .32f; Brush.Marker -> 1.5f; else -> .5f }
    }
    val opacity get() = when (brush) { Brush.Marker -> 80; Brush.Pencil -> 156; else -> 255 }
    fun connect(path: Path, a: Sample, ra: Float, b: Sample, rb: Float) {
        val dx = b.x-a.x; val dy = b.y-a.y
        val len = hypot(dx,dy)
        if (len <= .001f) return
        val nx = -dy/len; val ny = dx/len
        path.moveTo(a.x+nx*ra,a.y+ny*ra)
        path.lineTo(a.x-nx*ra,a.y-ny*ra)
        path.lineTo(b.x-nx*rb,b.y-ny*rb)
        path.lineTo(b.x+nx*rb,b.y+ny*rb)
        path.close()
    }
    val bounds: RectF by lazy { bounds(0, points.size) }
    fun bounds(start: Int, end: Int): RectF {
        val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in start until end) {
            val p = points[i]
            val r = radius(p) + 2
            box.left = min(box.left, p.x-r); box.top = min(box.top, p.y-r)
            box.right = max(box.right, p.x+r); box.bottom = max(box.bottom, p.y+r)
        }
        return box
    }
    // 같은 방향의 닫힌 윤곽들을 한 번에 채워 반투명 획의 이음새도 같은 농도로 그린다.
    inline fun geometry(circle: (Sample, Float) -> Unit, quad: (FloatArray) -> Unit, start: Int = 0, end: Int = points.size) {
        var prev: Sample? = null
        for (i in start until end) {
            val b = points[i]
            val rb = radius(b)
            circle(b, rb)
            val a = prev
            if (a != null) {
                val dx = b.x-a.x; val dy = b.y-a.y; val len = hypot(dx, dy)
                if (len > .001f) {
                    val nx = -dy/len; val ny = dx/len; val ra = radius(a)
                    quad(floatArrayOf(a.x+nx*ra, a.y+ny*ra, a.x-nx*ra, a.y-ny*ra,
                        b.x-nx*rb, b.y-ny*rb, b.x+nx*rb, b.y+ny*rb))
                }
            }
            prev = b
        }
    }
    val outline by lazy { outline(0, points.size) }
    private fun outline(start: Int, end: Int) =
        Path().also { path -> geometry({ p, r -> path.addCircle(p.x,p.y,r,Path.Direction.CW) }, { q ->
            path.moveTo(q[0],q[1]); path.lineTo(q[2],q[3]); path.lineTo(q[4],q[5]); path.lineTo(q[6],q[7]); path.close()
        }, start, end) }
    private val feltCore by lazy { copy(width = width * .76f, texture = InkTexture.Smooth) }
    private val textureShader by lazy { texture.shader(width) }
    private val tint by lazy { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
    // 이웃 구간은 경계 표본 하나를 공유해 구간을 가로지르는 선분도 포함한다.
    internal inner class HitSection(private val start: Int, private val end: Int) {
        val bounds = this@InkStroke.bounds(start, end)
        val path by lazy { outline(start, end) }

        // 원과 잇는 사각형으로 이루어진 실제 획에 둥근 지우개가 닿는지 계산한다.
        fun touches(a: Sample, b: Sample, eraserRadius: Float): Boolean {
            val limit = eraserRadius * eraserRadius
            for (i in start until end) {
                val p = points[i]
                val rp = radius(p)
                val reach = eraserRadius + rp
                if (distanceToSegment2(p.x,p.y,a.x,a.y,b.x,b.y) <= reach*reach) return true
                if (i == start) continue
                val q = points[i-1]
                val dx = p.x-q.x; val dy = p.y-q.y
                val length = hypot(dx,dy)
                if (length <= .001f) continue
                val nx = -dy/length; val ny = dx/length
                val rq = radius(q)
                // 선분 끝이 사다리꼴 안에 있거나 지우개 중심선이 가장자리를 지난다.
                fun inside(t: Sample): Boolean {
                    val along = ((t.x-q.x)*dx+(t.y-q.y)*dy)/(length*length)
                    return along in 0f..1f && abs((t.x-q.x)*nx+(t.y-q.y)*ny) <= rq + along*(rp-rq)
                }
                if (inside(a) || inside(b)) return true
                fun hitsEdge(sign: Float): Boolean {
                    val x = q.x+sign*nx*rq; val y = q.y+sign*ny*rq
                    val u = p.x+sign*nx*rp; val v = p.y+sign*ny*rp
                    val ex = u-x; val ey = v-y
                    val sx = b.x-a.x; val sy = b.y-a.y
                    val det = sx*ey-sy*ex
                    if (det != 0f) {
                        val t = ((x-a.x)*ey-(y-a.y)*ex)/det
                        val h = ((x-a.x)*sy-(y-a.y)*sx)/det
                        if (t in 0f..1f && h in 0f..1f) return true
                    }
                    // 모서리 끝점은 위의 원 판정에 포함된다.
                    return distanceToSegment2(a.x,a.y,x,y,u,v) <= limit ||
                        distanceToSegment2(b.x,b.y,x,y,u,v) <= limit
                }
                if (hitsEdge(1f) || hitsEdge(-1f)) return true
            }
            return false
        }
    }
    val hitSections by lazy {
        val count = maxOf(1, (points.size - 1 + 15) / 16)
        List(count) { i -> HitSection(i * 16, minOf(points.size, i * 16 + 17)) }
    }
    fun drawOnPaper(canvas: Canvas, paint: Paint) {
        if (points.size <= 32) {
            draw(canvas, paint, onPaper = true)
            return
        }
        if (canvas.quickReject(bounds)) return
        val needsMask = texture != InkTexture.Smooth || opacity != 255
        val layer = if (needsMask) canvas.saveLayer(bounds, null) else -1
        paint.reset()
        paint.isAntiAlias = true
        paint.color = if (needsMask || brush == Brush.Partial) Color.WHITE else color
        for (section in hitSections) {
            if (!canvas.quickReject(section.bounds)) canvas.drawPath(section.path, paint)
        }
        if (needsMask) {
            setInk(paint, true)
            paint.blendMode = BlendMode.SRC_IN
            canvas.drawRect(bounds, paint)
            canvas.restoreToCount(layer)
            paint.shader = null
            paint.colorFilter = null
            paint.blendMode = BlendMode.SRC_OVER
        }
        if (texture == InkTexture.Felt) feltCore.drawOnPaper(canvas, paint)
    }
    fun setInk(paint: Paint, onPaper: Boolean) {
        paint.blendMode = if (brush == Brush.Partial && !onPaper) BlendMode.CLEAR else BlendMode.SRC_OVER
        paint.color = if (brush == Brush.Partial && onPaper) Color.WHITE else color
        paint.alpha = opacity
        if (texture != InkTexture.Smooth) {
            paint.shader = textureShader
            paint.colorFilter = tint
        }
    }
    fun draw(canvas: Canvas, paint: Paint, onPaper: Boolean = false) {
        setInk(paint, onPaper)
        canvas.drawPath(outline, paint)
        paint.shader = null
        paint.colorFilter = null
        if (texture == InkTexture.Felt) canvas.drawPath(feltCore.outline, paint)
    }
    fun svg(out: Appendable, ink: Int = color) {
        val rgb = (ink and 0xffffff).toString(16).padStart(6,'0')
        val mask = if (texture == InkTexture.Smooth) "" else " mask='url(#${texture.maskId(width)})'"
        out.append("<g fill='#$rgb' opacity='${opacity / 255f}'$mask>")
        geometry({ p,r -> out.append("<circle cx='${p.x}' cy='${p.y}' r='$r'/>") },
            { q -> out.append("<polygon points='${q.joinToString(" ")}'/>") })
        out.append("</g>")
        if (texture == InkTexture.Felt) feltCore.svg(out, ink)
    }
}

internal data class PageImage(val id: String, val revision: Long, val width: Int, val height: Int,
    val strokes: List<InkStroke>) {
    fun resized(w: Int,h: Int): PageImage {
        require(w in 64..2048 && h in 64..2048)
        val scale = minOf(w.toFloat()/width,h.toFloat()/height)
        val dx = (w-width*scale)/2; val dy = (h-height*scale)/2
        return copy(width=w,height=h,revision=revision+1,strokes=strokes.map { stroke ->
            stroke.copy(width=stroke.width*scale,points=stroke.points.map { p ->
                p.copy(x=p.x*scale+dx,y=p.y*scale+dy)
            })
        })
    }
    fun writeVectors(output: OutputStream, textured: Boolean = false) {
        val data = java.io.DataOutputStream(output)
        data.writeInt(if (textured) 0x494E4B32 else 0x494E4B31); data.writeInt(width); data.writeInt(height); data.writeInt(strokes.size)
        for (stroke in strokes) {
            data.writeByte(when (stroke.brush) {
                Brush.Pen -> 0; Brush.Pencil -> 1; Brush.Marker -> 2; Brush.Partial -> 3
                Brush.Whole -> error("획 지우개 동작은 저장하지 않습니다.")
            })
            data.writeInt(stroke.color); data.writeFloat(stroke.width); data.writeBoolean(stroke.pressure)
            if (textured) data.writeByte(stroke.texture.ordinal)
            data.writeInt(stroke.points.size)
            for (point in stroke.points) { data.writeFloat(point.x); data.writeFloat(point.y); data.writeFloat(stroke.effectivePressure(point)) }
        }
    }
    fun draw(canvas: Canvas, draft: InkStroke? = null) {
        val masked = draft?.brush == Brush.Partial || strokes.any { it.brush == Brush.Partial }
        val layer = if (masked) canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null) else -1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        strokes.forEach { it.draw(canvas,paint) }
        draft?.draw(canvas,paint)
        if (masked) canvas.restoreToCount(layer)
    }

    fun bitmap(paper: Boolean): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        if (paper) canvas.drawColor(Color.WHITE)
        draw(canvas)
    }
    fun png(out: OutputStream) {
        val bitmap = bitmap(true)
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { bitmap.recycle() }
    }
    fun svg(out: OutputStream) {
        val writer = out.bufferedWriter()
        writer.append("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='$width' height='$height' viewBox='0 0 $width $height'><rect width='100%' height='100%' fill='white'/>")
        writer.append("<defs>")
        strokes.asSequence().filter { it.texture != InkTexture.Smooth }.distinctBy { it.texture.maskId(it.width) }
            .forEach { it.texture.writeMask(writer, it.width, width, height) }
        writer.append("</defs>")
        // 각 마스크는 해당 시점까지의 획에만 적용한다. 이후에 덧그린 획은 그대로 남는다.
        val erasers = strokes.filter { it.brush == Brush.Partial }
        if (erasers.isNotEmpty()) {
            writer.append("<defs>")
            for (eraser in erasers) {
                writer.append("<mask id='e${eraser.id}' maskUnits='userSpaceOnUse' x='0' y='0' width='$width' height='$height'><rect width='$width' height='$height' fill='white'/>")
                eraser.svg(writer, Color.BLACK)
                writer.append("</mask>")
            }
            writer.append("</defs>")
            for (eraser in erasers.asReversed()) writer.append("<g mask='url(#e${eraser.id})'>")
        }
        for (stroke in strokes) {
            if (stroke.brush == Brush.Partial) writer.append("</g>") else stroke.svg(writer)
        }
        writer.append("</svg>"); writer.flush()
    }
}

internal class CanvasDocument(val width: Int = 1024, val height: Int = 640,
    val id: String = UUID.randomUUID().toString(), private val initialRevision: Long = 0,
    initialStrokes: List<InkStroke> = emptyList(), private val load: (() -> PageImage)? = null,
    private val save: (PageImage, PageImage) -> Unit = { _, _ -> }) {
    @Volatile var loadedImage: PageImage? = if (load == null) PageImage(id, initialRevision, width, height, initialStrokes) else null
        private set
    val revision get() = loadedImage?.revision ?: initialRevision
    fun frozen(): PageImage = loadedImage ?: synchronized(this) {
        loadedImage ?: checkNotNull(load).invoke().also { loadedImage = it }
    }
    private val image get() = frozen()
    fun draw(canvas: Canvas) = image.draw(canvas)
    private var rect = Rect()
    var strokeId = ""
        private set
    var previousOverlayBase = initialRevision
        private set
    @Volatile var overlayBase = initialRevision
        private set
    private val undoStack = ArrayDeque<List<InkStroke>>()
    private val redoStack = ArrayDeque<List<InkStroke>>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    private fun pageBounds() = RectF(0f,0f,width.toFloat(),height.toFloat())
    private fun edit(strokes: List<InkStroke>, bounds: RectF, gesture: String, additive: Boolean = false) {
        val before = image.strokes
        replace(strokes,bounds,gesture,additive)
        if (undoStack.size == 64) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
    }
    fun undo() = restore(undoStack,redoStack)
    fun redo() = restore(redoStack,undoStack)
    private fun restore(from: ArrayDeque<List<InkStroke>>, to: ArrayDeque<List<InkStroke>>) {
        val strokes = from.lastOrNull() ?: return
        val before = image.strokes
        replace(strokes,pageBounds(),"")
        from.removeLast()
        to.addLast(before)
    }
    @Synchronized private fun replace(strokes: List<InkStroke>, bounds: RectF, id: String, additive: Boolean = false) {
        val next = image.copy(revision = revision + 1, strokes = strokes)
        save(image, next)
        bounds.roundOut(rect)
        rect.intersect(0, 0, width, height)
        strokeId = id
        if (!additive) {
            previousOverlayBase = overlayBase
            overlayBase = next.revision
        }
        loadedImage = next
    }
    fun clear() {
        if (image.strokes.isNotEmpty()) edit(emptyList(),pageBounds(),"")
    }
    fun commit(stroke: InkStroke) {
        require(stroke.brush != Brush.Whole)
        edit(image.strokes + stroke.copy(layer = revision + 1), stroke.bounds, stroke.id, additive = !stroke.brush.erases)
    }
    fun eraseStrokes(ids: Set<String>, gesture: String) {
        if (ids.isEmpty()) return
        edit(image.strokes.filterNot { it.id in ids }, pageBounds(), gesture)
    }
    data class Snapshot(val revision: Long, val rect: Rect, val pixels: IntArray, val full: Boolean, val stroke: String, val overlayBase: Long)
    fun snapshot(base: Long): Snapshot {
        val baseForOverlay: Long
        val (page, dirty, last) = synchronized(this) {
            baseForOverlay = overlayBase
            Triple(image, Rect(rect), strokeId)
        }
        val full = base < 0 || base + 1 != page.revision || dirty.isEmpty
        val pixels = IntArray(width*height)
        val bitmap = page.bitmap(false)
        try { bitmap.getPixels(pixels,0,width,0,0,width,height) } finally { bitmap.recycle() }
        return Snapshot(page.revision, if (full) Rect(0,0,width,height) else dirty, pixels, full, last, baseForOverlay)
    }
}
