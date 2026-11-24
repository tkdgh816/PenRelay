package dev.penrelay.app

import android.graphics.*
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.floor

internal fun distanceToSegment2(x: Float,y: Float,ax: Float,ay: Float,bx: Float,by: Float): Float {
    val dx = bx-ax; val dy = by-ay
    val len2 = dx*dx+dy*dy
    val t = if (len2 == 0f) 0f else (((x-ax)*dx+(y-ay)*dy)/len2).coerceIn(0f,1f)
    val ux = x-(ax+t*dx); val uy = y-(ay+t*dy)
    return ux*ux+uy*uy
}

// 페이지 순서대로 구간을 공간 격자에 등록한다. 각 칸 안에서만 부분 지우개
// 마스크를 빼므로 다른 곳에 쌓인 획이나 마스크는 입력 비용에 영향을 주지 않는다.
internal class StrokeHitIndex(private val width: Int, private val height: Int) {
    private val cellSize = 64
    private val columns = (width + cellSize - 1) / cellSize
    private val rows = (height + cellSize - 1) / cellSize
    private val cells = HashMap<Int, MutableList<Entry>>()
    private class Entry(val stroke: InkStroke) {
        val sections = ArrayList<InkStroke.HitSection>()
    }
    private data class MaskKey(val cell: Int, val precision: Int, val section: InkStroke.HitSection)
    private val masks = LruCache<MaskKey, Region>(256)
    private val fineCells = HashSet<Int>()
    private val maskedCells = HashSet<Int>()
    private val matrix = Matrix()
    private val scaled = Path()
    private val clip = Region()

    private inline fun cellsIn(box: RectF, visit: (Int,Int,Int) -> Unit) {
        val right = ceil(box.right/cellSize).toInt().coerceAtMost(columns)
        val bottom = ceil(box.bottom/cellSize).toInt().coerceAtMost(rows)
        for (y in floor(box.top/cellSize).toInt().coerceAtLeast(0) until bottom)
            for (x in floor(box.left/cellSize).toInt().coerceAtLeast(0) until right) visit(y*columns+x,x,y)
    }
    fun add(stroke: InkStroke) {
        for (section in stroke.hitSections) {
            cellsIn(section.bounds) { id,_,_ ->
                val entries = cells.getOrPut(id) { ArrayList() }
                val last = entries.lastOrNull()
                val entry = if (last?.stroke === stroke) last else Entry(stroke).also(entries::add)
                entry.sections.add(section)
                if (stroke.brush == Brush.Partial) maskedCells.add(id)
                if (stroke.width < 2f) fineCells.add(id)
            }
        }
    }
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        for (entries in cells.values) entries.removeAll { it.stroke.id in ids }
    }
    inner class Gesture {
        private val visited = HashMap<Int,Region>()
        private val footprint = Path()
        private val sweep = Path()
        private val remaining = Region()
        private val overlap = Region()
        private val area = RectF()

        fun sample(stroke: InkStroke, a: Sample, b: Sample, removed: MutableSet<String>, dirty: RectF, onHit: (InkStroke) -> Unit = {}) {
            val radius = stroke.width / 2
            area.set(minOf(a.x,b.x)-radius,minOf(a.y,b.y)-radius,maxOf(a.x,b.x)+radius,maxOf(a.y,b.y)+radius)
            var masked = false
            if (maskedCells.isNotEmpty()) cellsIn(area) { id,_,_ -> if (id in maskedCells) masked = true }
            if (!masked) {
                cellsIn(area) { id,_,_ ->
                    for (entry in cells[id].orEmpty()) {
                        if (entry.stroke.id in removed) continue
                        if (entry.sections.any { RectF.intersects(it.bounds,area) && it.touches(a,b,radius) }) {
                            removed.add(entry.stroke.id)
                            dirty.union(entry.stroke.bounds)
                            onHit(entry.stroke)
                        }
                    }
                }
                return
            }
            footprint.rewind()
            footprint.addCircle(a.x,a.y,radius,Path.Direction.CW)
            if (a.x != b.x || a.y != b.y) {
                footprint.addCircle(b.x,b.y,radius,Path.Direction.CW)
                stroke.connect(footprint,a,radius,b,radius)
            }
            cellsIn(area) { id,x,y ->
                val entries = cells[id] ?: return@cellsIn
                val precision = if (id in fineCells) 64 else 8
                matrix.setScale(precision.toFloat(),precision.toFloat())
                footprint.transform(matrix,sweep)
                val edge = cellSize * precision
                clip.set(x*edge,y*edge,(x+1)*edge,(y+1)*edge)
                if (!remaining.setPath(sweep,clip)) return@cellsIn
                val seen = visited.getOrPut(id) { Region() }
                remaining.op(seen,Region.Op.DIFFERENCE)
                if (remaining.isEmpty) return@cellsIn
                seen.op(remaining,Region.Op.UNION)
                for (i in entries.lastIndex downTo 0) {
                    val entry = entries[i]
                    if (entry.stroke.id in removed) continue
                    for (section in entry.sections) {
                        if (!RectF.intersects(section.bounds,area)) continue
                        val key = MaskKey(id,precision,section)
                        val region = masks.get(key) ?: Region().also {
                            section.path.transform(matrix,scaled)
                            it.setPath(scaled,clip)
                            masks.put(key,it)
                        }
                        if (remaining.quickReject(region)) continue
                        if (entry.stroke.brush == Brush.Partial) {
                            remaining.op(region,Region.Op.DIFFERENCE)
                            if (remaining.isEmpty) return@cellsIn
                        } else if (overlap.op(remaining,region,Region.Op.INTERSECT)) {
                            removed.add(entry.stroke.id)
                            dirty.union(entry.stroke.bounds)
                            onHit(entry.stroke)
                            break
                        }
                    }
                }
            }
        }
    }
}
