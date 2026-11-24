package dev.penrelay.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.WindowInsets
import android.widget.FrameLayout

internal class WindowInsetsLayout(context: Context) : FrameLayout(context) {
    private val radius = 8f * resources.displayMetrics.density
    private val bottomEdge = edge("navigation")
    private var bottomBar = 0
    private fun edge(name: String) = RenderNode(name).apply {
        setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP))
    }
    init {
        setBackgroundColor(0xfff8f7fb.toInt())
        clipToPadding = false
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bottom = maxOf(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom)
            bottomBar = if (bottom == bars.bottom) bottom else 0
            setPadding(bars.left,bars.top,bars.right,bottom)
            insets
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!canvas.isHardwareAccelerated || childCount == 0 || width == 0) return
        fun drawEdge(node: RenderNode, y: Int, size: Int, source: Int) {
            if (size <= 0) return
            node.setPosition(0,y,width,y + size)
            val sample = node.beginRecording(width,size)
            try {
                sample.translate(0f,-source.toFloat())
                drawChild(sample,getChildAt(0),drawingTime)
            } finally { node.endRecording() }
            canvas.drawRenderNode(node)
            canvas.save()
            canvas.clipRect(0,y,width,y + size)
            canvas.drawColor(0xccefebf8.toInt())
            canvas.restore()
        }
        drawEdge(bottomEdge,height-bottomBar,bottomBar,height-paddingBottom-bottomBar)
    }
}
