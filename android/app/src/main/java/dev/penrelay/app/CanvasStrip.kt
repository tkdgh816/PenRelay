package dev.penrelay.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Color
import android.graphics.Paint
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView

internal class CanvasStrip(context: Context, private val book: PageBook, private val select: (CanvasInfo) -> Unit,
    private val actions: PageActions, private val vertical: Boolean = false, private val notes: Boolean = false) : FrameLayout(context) {
    private companion object {
        const val TILE_HEIGHT = 80
        const val TILE_RADIUS = 9
    }
    private val ui = PenRelayUi(context)
    private val row = if (vertical) ui.column() else ui.row()
    private val scroll: FrameLayout = if (vertical) ScrollView(context) else HorizontalScrollView(context)
    private data class CanvasDrag(val strip: CanvasStrip, val id: String)
    private var hover: View? = null
    private var dragPosition = 0f
    private fun highlight(tile: View?) {
        if (tile === hover) return
        val previous = hover
        hover = tile
        previous?.let(::updateOutline)
        tile?.let(::updateOutline)
    }
    private fun updateOutline(tile: View) {
        tile.foreground = when {
            tile === hover -> ui.shape(Color.TRANSPARENT,TILE_RADIUS).apply { setStroke(ui.dp(3),ui.violet) }
            tile.isSelected -> ui.shape(Color.TRANSPARENT,TILE_RADIUS).apply { setStroke(ui.dp(2),0xff0067c0.toInt()) }
            else -> null
        }
    }
    private fun target(): View? {
        val point = dragPosition + if (vertical) scroll.scrollY else scroll.scrollX
        return (0 until row.childCount).map { row.getChildAt(it) }.filter { it.tag is CanvasInfo }.minByOrNull {
            kotlin.math.abs(point - if (vertical) (it.top + it.bottom) / 2f else (it.left + it.right) / 2f)
        }
    }
    private val edgeScroll = object : Runnable {
        override fun run() {
            val extent = if (vertical) scroll.height else scroll.width
            val edge = ui.dp(28)
            val step = when {
                dragPosition < edge -> -ui.dp(8)
                dragPosition > extent - edge -> ui.dp(8)
                else -> return
            }
            val canScroll = if (vertical) scroll.canScrollVertically(step) else scroll.canScrollHorizontally(step)
            if (canScroll) {
                if (vertical) scroll.scrollBy(0,step) else scroll.scrollBy(step,0)
                highlight(target())
                scroll.postOnAnimation(this)
            }
        }
    }
    init {
        scroll.isScrollbarFadingEnabled = false
        setBackgroundColor(0xfff2f3f6.toInt())
        row.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(8))
        scroll.addView(row)
        addView(scroll)
        scroll.setOnDragListener { _,event ->
            val drag = event.localState as? CanvasDrag
            if (drag?.strip !== this) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> {
                    dragPosition = if (vertical) event.y else event.x
                    highlight(target())
                    scroll.removeCallbacks(edgeScroll)
                    edgeScroll.run()
                }
                DragEvent.ACTION_DROP -> {
                    dragPosition = if (vertical) event.y else event.x
                    (target()?.tag as? CanvasInfo)?.let { target ->
                        if (notes) actions.moveNote(drag.id,target.id) else actions.moveCanvas(drag.id,target.id)
                    }
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    scroll.removeCallbacks(edgeScroll)
                    highlight(null)
                }
            }
            true
        }
        refresh()
    }
    fun refresh() {
        highlight(null)
        row.removeAllViews()
        if (notes) {
            val choices = book.pageChoices()
            for (note in choices) {
                val card = noteCard(note)
                enableDrag(card,note,choices.size)
                addTile(card,note)
            }
            addButton("새 노트",actions::addPage)
            return
        }
        val docs = book.canvases()
        docs.forEachIndexed { index, doc ->
            val thumb = PageThumbnail(context,book,doc).apply {
                background = ui.shape(0xffe6e9ee.toInt(),TILE_RADIUS)
                clipToOutline = true
                tag = doc.id
                isSelected = doc === book.selected
                contentDescription = "페이지 ${index + 1}"
                isFocusable = true
                setOnClickListener { if (doc !== book.selected) select(doc) }
                enableDrag(this,doc,docs.size)
            }
            val tile = FrameLayout(context).apply {
                // 썸네일 뷰의 문서 태그는 필기 변경 시 다시 그리기에 사용한다.
                addView(thumb,FrameLayout.LayoutParams(-1,-1))
                addMenuButton(this,"페이지 ${index + 1} 작업") { actions.canvasMenu(it,doc,index) }
            }
            addTile(tile,doc)
        }
        addButton("페이지 바로 추가",actions::addCanvas)
    }
    private fun reveal(tile: View) = post {
        if (tile.parent === row) {
            if (vertical) scroll.scrollTo(0,tile.top) else scroll.scrollTo(tile.left,0)
        }
    }
    private fun enableDrag(handle: View, item: CanvasInfo, count: Int) {
        if (count > 1) handle.setOnLongClickListener {
            handle.startDragAndDrop(null,View.DragShadowBuilder(handle),CanvasDrag(this,item.id),0)
        }
    }
    private fun addTile(tile: View, item: CanvasInfo) {
        tile.tag = item
        tile.isSelected = item === book.selected
        updateOutline(tile)
        row.addView(tile,LinearLayout.LayoutParams(ui.dp(96),ui.dp(TILE_HEIGHT)).apply {
            if (vertical) bottomMargin = ui.dp(4) else marginEnd = ui.dp(4)
        })
        if (tile.isSelected) reveal(tile)
    }
    private fun noteCard(note: CanvasInfo): FrameLayout {
        val title = book.title(note)
        val count = book.pageCount(note)
        val card = FrameLayout(context).apply {
            background = ui.shape(0xffa6bcfa.toInt(),TILE_RADIUS)
            clipToOutline = true
            addView(object : View(context) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffd2dcf5.toInt() }
                val folder = Path()
                override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
                    folder.reset()
                    folder.moveTo(0f,h*.24f)
                    folder.lineTo(w*.40f,h*.24f)
                    folder.cubicTo(w*.48f,h*.24f,w*.50f,h*.34f,w*.57f,h*.34f)
                    folder.lineTo(w.toFloat(),h*.34f)
                    folder.lineTo(w.toFloat(),h.toFloat())
                    folder.lineTo(0f,h.toFloat())
                    folder.close()
                }
                override fun onDraw(canvas: Canvas) = canvas.drawPath(folder,fill)
            })
            val labels = ui.column().apply {
                addView(ui.label(count.toString(),12))
                addView(ui.label(title,14,ui.ink,true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(labels,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
                setMargins(ui.dp(8),0,ui.dp(6),ui.dp(4))
            })
            contentDescription = "$title, 페이지 ${count}개"
            tooltipText = title
            isFocusable = true
            setOnClickListener { select(note) }
        }
        addMenuButton(card,"$title 노트 작업") { actions.noteMenu(note) }
        return card
    }
    private fun addMenuButton(tile: FrameLayout, label: String, open: (View) -> Unit) {
        val button = ui.action("⋮") {}.apply {
            contentDescription = label
            tooltipText = label
            minHeight = 0
            minimumHeight = 0
            ui.inset(this,0,0)
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = ui.shape(Color.WHITE,16)
            elevation = ui.dp(2).toFloat()
            setOnClickListener(open)
        }
        tile.addView(button,FrameLayout.LayoutParams(ui.dp(28),ui.dp(28),Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(3)
            marginEnd = ui.dp(3)
        })
    }
    private fun addButton(label: String, action: () -> Unit) {
        row.addView(ui.action("＋",action).apply {
            contentDescription = label
            tooltipText = contentDescription
        },LinearLayout.LayoutParams(ui.dp(if (vertical) 96 else 56),ui.dp(if (vertical) 48 else TILE_HEIGHT)))
    }
}
