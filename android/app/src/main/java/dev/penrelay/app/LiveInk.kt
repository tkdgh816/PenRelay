package dev.penrelay.app

import android.graphics.*
import kotlin.math.ceil
import kotlin.math.floor

// 화면에 보이는 면만 화면 해상도로 보관한다. 불투명 마스크에 표본을 누적한 뒤
// 획 전체의 색과 농도를 한 번 적용하므로 형광펜이 자기 자신과 겹쳐도 진해지지 않는다.
internal class LiveInk(private val stroke: InkStroke, private val scale: Float,
    private val left: Float, private val top: Float, private val width: Int, private val height: Int) {
    private val size = 192
    private val columns = (width + size - 1) / size
    private class Tile(val x: Int, val y: Int, size: Int) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
    }
    private val tiles = mutableMapOf<Int,Tile>()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { stroke.setInk(it, true) }
    private val shape = Path()
    private var previous: Sample? = null
    private val core: LiveInk? = if (stroke.texture == InkTexture.Felt)
        LiveInk(stroke.copy(width = stroke.width * .76f, texture = InkTexture.Smooth), scale, left, top, width, height) else null

    fun append(point: Sample) {
        val last = previous
        if (last == point) return
        previous = point
        val r = stroke.radius(point)
        val a = last ?: point
        val ra = stroke.radius(a)
        val x0 = floor((minOf(a.x-ra,point.x-r)*scale+left-1)/size).toInt().coerceAtLeast(0)
        val y0 = floor((minOf(a.y-ra,point.y-r)*scale+top-1)/size).toInt().coerceAtLeast(0)
        val x1 = ceil((maxOf(a.x+ra,point.x+r)*scale+left+1)/size).toInt().coerceAtMost(columns)
        val y1 = ceil((maxOf(a.y+ra,point.y+r)*scale+top+1)/size).toInt().coerceAtMost((height+size-1)/size)
        shape.rewind()
        shape.addCircle(point.x, point.y, r, Path.Direction.CW)
        if (last != null) stroke.connect(shape, a, ra, point, r)
        for (y in y0 until y1) for (x in x0 until x1) {
            val tile = tiles.getOrPut(y * columns + x) {
                Tile(x * size, y * size, size).also {
                    it.canvas.translate(left-it.x, top-it.y)
                    it.canvas.scale(scale, scale)
                }
            }
            tile.canvas.drawPath(shape, maskPaint)
        }
        core?.append(point)
    }

    // 호출자는 페이지 좌표계를 적용한다. 질감의 기준점도 페이지에 고정된다.
    fun draw(canvas: Canvas) {
        val dest = RectF()
        for (tile in tiles.values) {
            dest.set((tile.x-left)/scale, (tile.y-top)/scale,
                (tile.x+size-left)/scale, (tile.y+size-top)/scale)
            canvas.drawBitmap(tile.bitmap, null, dest, inkPaint)
        }
        core?.draw(canvas)
    }
}
