package dev.penrelay.app

import android.graphics.*
import android.util.Base64

internal enum class InkTexture(val label: String, val asset: String? = null) {
    Smooth("기본 펜"), Ballpoint("볼펜", "ballpoint"), Felt("사인펜", "felt"), Graphite("흑연", "graphite");

    private val bytes by lazy { checkNotNull(javaClass.getResourceAsStream("/brushes/$asset.png")).use { it.readBytes() } }
    private val tile by lazy { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    private val imageUri by lazy { "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) }

    private fun period(width: Float) = (width * 12f).coerceIn(3f, 32f)
    fun shader(width: Float) = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        val scale = period(width) / tile.width
        setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    }

    fun maskId(width: Float) = "t${ordinal}w${period(width).toBits()}"
    fun writeMask(out: Appendable, width: Float, pageWidth: Int, pageHeight: Int) {
        val id = maskId(width)
        val size = period(width)
        out.append("<pattern id='p$id' patternUnits='userSpaceOnUse' width='$size' height='$size'>")
        out.append("<image width='$size' height='$size' xlink:href='$imageUri'/></pattern>")
        out.append("<mask id='$id' maskUnits='userSpaceOnUse' x='0' y='0' width='$pageWidth' height='$pageHeight'>")
        out.append("<rect width='$pageWidth' height='$pageHeight' fill='url(#p$id)'/></mask>")
    }
}
