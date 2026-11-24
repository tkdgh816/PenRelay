package dev.penrelay.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

// 바깥 고리에서 색상을, 안쪽 면에서 채도와 밝기를 직접 고른다.
internal class InkPalette(context: Context, initial: Int, private val changed: (Int) -> Unit) : View(context) {
    private val hsv = FloatArray(3).apply { Color.colorToHSV(initial, this) }
    val color get() = Color.HSVToColor(hsv)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val field = RectF()
    private var radius = 0f
    private var band = 0f
    private var wheel: Shader? = null
    private var saturation: Shader? = null
    private var valueShade: Shader? = null
    private var drag = 0
    private val cx get() = width / 2f
    private val cy get() = height / 2f
    fun select(value: Int) {
        Color.colorToHSV(value, hsv)
        updateField()
        invalidate()
        changed(color)
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        radius = min(w,h) * .46f
        band = radius * .19f
        val half = (radius-band) * .63f
        field.set(cx-half,cy-half,cx+half,cy+half)
        wheel = SweepGradient(cx,cy, intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null)
        valueShade = LinearGradient(0f,field.top,0f,field.bottom,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
        updateField()
    }
    private fun updateField() {
        if (field.isEmpty) return
        saturation = LinearGradient(field.left,0f,field.right,0f,Color.WHITE,
            Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)),Shader.TileMode.CLAMP)
    }
    private fun cursor(canvas: Canvas,x: Float,y: Float,size: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK; paint.strokeWidth = size * .55f
        canvas.drawCircle(x,y,size,paint)
        paint.color = Color.WHITE; paint.strokeWidth = size * .32f
        canvas.drawCircle(x,y,size,paint)
    }
    override fun onDraw(canvas: Canvas) {
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = band
        paint.shader = wheel
        canvas.drawCircle(cx,cy,radius-band/2,paint)
        paint.style = Paint.Style.FILL; paint.shader = saturation
        canvas.drawRoundRect(field,5f,5f,paint)
        paint.shader = valueShade
        canvas.drawRoundRect(field,5f,5f,paint)
        val angle = Math.toRadians(hsv[0].toDouble())
        cursor(canvas,cx+cos(angle).toFloat()*(radius-band/2),cy+sin(angle).toFloat()*(radius-band/2),band*.32f)
        cursor(canvas,field.left+hsv[1]*field.width(),field.bottom-hsv[2]*field.height(),band*.30f)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    field.contains(x,y) -> 1
                    hypot(x-cx,y-cy) in (radius-band*1.25f)..(radius+band*.25f) -> 2
                    else -> return false
                }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (drag == 0) return false
            MotionEvent.ACTION_CANCEL -> { drag = 0; parent.requestDisallowInterceptTouchEvent(false); return true }
            else -> return false
        }
        if (drag == 2) {
            hsv[0] = ((Math.toDegrees(atan2((y-cy).toDouble(),(x-cx).toDouble()))+360) % 360).toFloat()
            updateField()
        } else {
            hsv[1] = ((x-field.left)/field.width()).coerceIn(0f,1f)
            hsv[2] = ((field.bottom-y)/field.height()).coerceIn(0f,1f)
        }
        changed(color); invalidate()
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            drag = 0; parent.requestDisallowInterceptTouchEvent(false); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
