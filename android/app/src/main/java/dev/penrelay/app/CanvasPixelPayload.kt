package dev.penrelay.app

import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

// tiles가 null이면 단일 사각형, 빈 배열이면 픽셀 변화가 없는 새 버전이다.
internal data class CanvasPixelPayload(val rect: Rect, val tiles: List<Int>?, val bytes: ByteArray,
    val compressed: Boolean = false) {
    fun compressIfSmaller(): CanvasPixelPayload {
        if (bytes.size < 1024) return this
        val encoder = Deflater(Deflater.BEST_SPEED)
        try {
            encoder.setInput(bytes)
            encoder.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!encoder.finished()) {
                val count = encoder.deflate(buffer)
                if (count == 0 || output.size() + count >= bytes.size) return this
                output.write(buffer, 0, count)
            }
            return copy(bytes = output.toByteArray(), compressed = true)
        } finally { encoder.end() }
    }

    companion object {
        private const val TILE = 32

        fun rectangle(rect: Rect, rgba: ByteArray, width: Int): CanvasPixelPayload {
            if (rect.width() * rect.height() * 4 == rgba.size) return CanvasPixelPayload(rect, null, rgba)
            val data = ByteArray(rect.width() * rect.height() * 4)
            copyRect(rect, rgba, width, data, 0)
            return CanvasPixelPayload(rect, null, data)
        }

        private fun copyRect(rect: Rect, rgba: ByteArray, width: Int, dest: ByteArray, offset: Int) {
            val stride = rect.width() * 4
            var at = offset
            for (y in rect.top until rect.bottom) {
                val start = (y * width + rect.left) * 4
                rgba.copyInto(dest, at, start, start + stride)
                at += stride
            }
        }

        fun changes(previous: IntArray, current: IntArray, rgba: ByteArray, width: Int, height: Int): CanvasPixelPayload {
            val columns = (width + TILE - 1) / TILE
            val changed = BooleanArray(columns * ((height + TILE - 1) / TILE))
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            for (y in 0 until height) {
                val row = y * width
                val tileRow = (y / TILE) * columns
                for (x in 0 until width) {
                    if (previous[row + x] == current[row + x]) continue
                    changed[tileRow + x / TILE] = true
                    left = minOf(left, x)
                    right = maxOf(right, x + 1)
                    top = minOf(top, y)
                    bottom = y + 1
                }
            }
            val canvas = Rect(0, 0, width, height)
            val ids = changed.indices.filter { changed[it] }
            if (ids.isEmpty()) return CanvasPixelPayload(canvas, ids, byteArrayOf())
            val tiles = ids.map { id ->
                val x = (id % columns) * TILE
                val y = (id / columns) * TILE
                Rect(x, y, minOf(x + TILE, width), minOf(y + TILE, height))
            }
            val tileBytes = tiles.sumOf { it.width() * it.height() * 4 }
            val bounds = Rect(left, top, right, bottom)
            if (bounds.width() * bounds.height() * 4 <= tileBytes + ids.sumOf { it.toString().length + 1 } + 10)
                return rectangle(bounds, rgba, width)
            val data = ByteArray(tileBytes)
            var offset = 0
            for (tile in tiles) {
                copyRect(tile, rgba, width, data, offset)
                offset += tile.width() * tile.height() * 4
            }
            return CanvasPixelPayload(canvas, ids, data)
        }
    }
}
