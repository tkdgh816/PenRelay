package dev.penrelay.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal enum class PenKind(val brush: Brush, val texture: InkTexture, val label: String) {
    Basic(Brush.Pen, InkTexture.Smooth, "기본 펜"),
    Ballpoint(Brush.Pen, InkTexture.Ballpoint, "볼펜"),
    Felt(Brush.Pen, InkTexture.Felt, "사인펜"),
    Pencil(Brush.Pencil, InkTexture.Graphite, "연필"),
    Marker(Brush.Marker, InkTexture.Smooth, "형광펜")
}

internal enum class PreviewMode(val label: String, val description: String) {
    Realtime(
        "실시간 표시",
        "그리거나 지우는 과정이 PC에 실시간으로 보입니다. 자주 갱신하므로 전송량과 PC의 그리기 부담이 커질 수 있습니다."
    ),
    CompletedFirst(
        "완성된 획 우선 표시",
        "보통은 펜을 뗄 때 PC에 표시합니다. 계속 그리거나 지우면 1초가 지난 시점부터 0.25초마다 새로 그리거나 지운 부분을 표시합니다. " +
            "화면 반영은 조금 늦을 수 있지만, 갱신 횟수가 줄어 전송량과 PC의 그리기 부담을 줄일 수 있습니다."
    )
}

internal class CanvasSurface(context: Context, val document: CanvasDocument,
    private val preview: (InkStroke,String,List<Sample>,Int) -> Unit,
    private val committed: () -> Unit, private val restored: () -> Unit = {},
    private val savedStroke: (InkStroke) -> Unit = {},
    private val wholePreview: (InkStroke, List<InkStroke>) -> Unit = { _, _ -> }) : View(context) {
    var penColor = INK
    var penWidth = BRUSH
    var eraserWidth = BRUSH * 3
    var lastInk = Brush.Pen
    var lastEraser = Brush.Partial
    var activeWidth: Float
        get() = if (brush.erases) eraserWidth else penWidth
        set(value) { if (brush.erases) eraserWidth = value else penWidth = value }
    var pressureEnabled = true
    var pressureStrength = 100
    var penTexture = InkTexture.Smooth
    var brush = Brush.Pen
    var previewMode = PreviewMode.Realtime
    private var strokeMode = PreviewMode.Realtime
    private var sentPoints = 0
    private var nextPreviewAt = 0L
    private var previewScheduled = false
    private val sendPending = Runnable {
        previewScheduled = false
        flushPreview()
    }

    private fun flushPreview() {
        val ink = draft ?: return
        if (ink.brush == Brush.Whole) {
            if (newHits.isEmpty()) return
            wholePreview(ink, newHits)
            sentPoints += newHits.size
            newHits.clear()
        } else {
            while (sentPoints < points.size) {
                val end = minOf(sentPoints + 256, points.size)
                val action = if (sentPoints == 0) "begin" else "append"
                preview(ink, action, points.subList(sentPoints, end), sentPoints)
                sentPoints = end
            }
        }
        val now = SystemClock.uptimeMillis()
        if (strokeMode == PreviewMode.CompletedFirst) {
            // 첫 1초를 기준으로 다음 갱신 시각을 계산해 전송 처리 시간이 누적되지 않게 한다.
            val elapsed = (now - nextPreviewAt).coerceAtLeast(0L)
            nextPreviewAt += (elapsed / COMPLETED_FIRST_INTERVAL_MS + 1) * COMPLETED_FIRST_INTERVAL_MS
        } else {
            nextPreviewAt = now + REALTIME_INTERVAL_MS
        }
    }

    private fun schedulePreview() {
        if (previewScheduled) return
        if (draft?.brush == Brush.Whole) {
            if (newHits.isEmpty()) return
        } else if (sentPoints == points.size) return
        val now = SystemClock.uptimeMillis()
        if (strokeMode == PreviewMode.CompletedFirst && sentPoints > 0 && now > nextPreviewAt) {
            // 입력 없이 지나간 주기는 건너뛰고 새 입력을 다음 경계에서 보낸다.
            val missed = (now - nextPreviewAt + COMPLETED_FIRST_INTERVAL_MS - 1) / COMPLETED_FIRST_INTERVAL_MS
            nextPreviewAt += missed * COMPLETED_FIRST_INTERVAL_MS
        }
        val delay = nextPreviewAt - now
        if (delay <= 0) flushPreview() else {
            previewScheduled = true
            postDelayed(sendPending, delay)
        }
    }
    var cyclePens: Set<PenKind> = PenKind.entries.toSet()
    var onToolChanged: () -> Unit = {}
    val currentPen get() = PenKind.entries.first { it.brush == lastInk && (lastInk != Brush.Pen || it.texture == penTexture) }
    fun selectPen(pen: PenKind) {
        brush = pen.brush
        lastInk = pen.brush
        if (pen.brush == Brush.Pen) penTexture = pen.texture
    }
    private var pressedAt = -1L
    private var ignoreUntilRelease = false
    var temporaryErasing = false
        private set
    var onTemporaryEraserChanged: () -> Unit = {}
    private val activateEraser = Runnable {
        if (pressedAt >= 0 && owner < 0 && !temporaryErasing) {
            temporaryErasing = true
            onTemporaryEraserChanged()
        }
    }
    private fun resetButton() {
        removeCallbacks(activateEraser)
        pressedAt = -1L
        if (temporaryErasing) {
            temporaryErasing = false
            onTemporaryEraserChanged()
        }
    }
    private fun trackSideButton(event: MotionEvent) {
        val index = if (event.actionMasked == MotionEvent.ACTION_MOVE && owner >= 0)
            event.findPointerIndex(owner) else event.actionIndex
        if (index < 0 || event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return
        val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
        val pressed = when {
            event.actionButton == primary && event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE -> false
            event.actionButton == primary && event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS -> true
            else -> event.buttonState and primary != 0
        }
        // 접촉 중 시작된 버튼 누름은 펜촉이 먼저 떨어져도 버튼을 놓을 때까지 소비한다.
        if (owner >= 0 || ignoreUntilRelease) {
            if (!pressed) {
                if (pressedAt >= 0) removeCallbacks(activateEraser)
                pressedAt = -1L
                ignoreUntilRelease = false
            } else if (pressedAt < 0) {
                ignoreUntilRelease = true
            }
            return
        }
        if (pressed) {
            if (pressedAt < 0) {
                pressedAt = event.eventTime
                postDelayed(activateEraser,ERASER_HOLD_MS)
            }
            if (event.eventTime - pressedAt >= ERASER_HOLD_MS) activateEraser.run()
            return
        }
        if (pressedAt < 0) return
        val longPress = event.eventTime - pressedAt >= ERASER_HOLD_MS
        if (longPress) activateEraser.run()
        val cycle = !temporaryErasing
        resetButton()
        if (!cycle) return
        if (brush.erases) {
            brush = if (brush == Brush.Partial) Brush.Whole else Brush.Partial
            lastEraser = brush
        } else {
            val kinds = PenKind.entries
            val start = currentPen.ordinal
            selectPen((1..kinds.size).map { kinds[(start + it) % kinds.size] }.first { it in cyclePens })
        }
        onToolChanged()
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        trackSideButton(event)
        if (event.actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY &&
            event.actionMasked in listOf(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) return true
        return super.onGenericMotionEvent(event)
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        trackSideButton(event)
        return super.onHoverEvent(event) || event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
    }
    var eraser: Boolean
        get() = brush.erases
        set(value) { brush = if (value) Brush.Partial else Brush.Pen }
    private val page = Rect(0,0,document.width,document.height)
    private var owner = -1
    private var draft: InkStroke? = null
    private var points = ArrayList<Sample>()
    private val touched = HashSet<String>()
    private val newHits = ArrayList<InkStroke>()
    private var live: LiveInk? = null
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var paper: Bitmap? = null
    private var paperRevision = -1L
    private val dirtyPaper = RectF()
    private var hitIndex: StrokeHitIndex? = null
    private var indexRevision = -1L
    private var wholeGesture: StrokeHitIndex.Gesture? = null
    private var warming: Future<*>? = null
    private var loadFailure: String? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var focusX = 0f
    private var focusY = 0f
    private val scale get() = minOf(width.toFloat()/page.width(),height.toFloat()/page.height()) * zoom
    private val leftMargin get() = (width-scale*page.width())/2 + panX
    private val topMargin get() = (height-scale*page.height())/2 + panY
    private val gestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            focusX = detector.focusX; focusY = detector.focusY; return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val x = (focusX-leftMargin)/scale; val y = (focusY-topMargin)/scale
            zoom = (zoom*detector.scaleFactor).coerceIn(1f,8f)
            focusX = detector.focusX; focusY = detector.focusY
            val dx = ((scale*page.width()-width)/2).coerceAtLeast(0f)
            val dy = ((scale*page.height()-height)/2).coerceAtLeast(0f)
            panX = (focusX-(width-scale*page.width())/2-x*scale).coerceIn(-dx,dx)
            panY = (focusY-(height-scale*page.height())/2-y*scale).coerceIn(-dy,dy)
            paperRevision = -1
            invalidate(); return true
        }
    }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    init { contentDescription = "캔버스 필기면" }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        warmHitIndex()
    }
    private fun warmHitIndex() {
        if (indexRevision == document.revision || warming != null) return
        warming = indexWorker.submit {
            val page = try {
                document.frozen()
            } catch (_: InterruptedException) {
                return@submit
            } catch (e: Exception) {
                post {
                    if (isAttachedToWindow) {
                        warming = null
                        loadFailure = "필기 복원 실패 · ${e.localizedMessage}"
                        invalidate()
                    }
                }
                return@submit
            }
            post {
                if (isAttachedToWindow) {
                    invalidate()
                    restored()
                }
            }
            val prepared = StrokeHitIndex(page.width,page.height)
            for (stroke in page.strokes) {
                if (Thread.currentThread().isInterrupted) return@submit
                prepared.add(stroke)
            }
            post {
                warming = null
                if (!isAttachedToWindow || wholeGesture != null || indexRevision == document.revision) return@post
                if (document.frozen() === page) {
                    hitIndex = prepared
                    indexRevision = page.revision
                } else warmHitIndex()
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xffe6e5e9.toInt())
        val image = document.loadedImage
        if (image == null) {
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textAlign = Paint.Align.CENTER
                textSize = 16 * resources.displayMetrics.scaledDensity
            }
            canvas.drawText(loadFailure ?: "필기를 불러오는 중",width / 2f,height / 2f,label)
            canvas.save()
            transformToPage(canvas)
            live?.draw(canvas)
            canvas.restore()
            return
        }
        val ink = draft
        val bitmap = paper ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.density = Bitmap.DENSITY_NONE
            paper = it
        }
        if (paperRevision != document.revision || !dirtyPaper.isEmpty) {
            val target = Canvas(bitmap)
            if (paperRevision != document.revision) bitmap.eraseColor(Color.TRANSPARENT)
            transformToPage(target)
            val partial = paperRevision == document.revision
            if (partial) target.clipRect(dirtyPaper)
            target.drawColor(Color.WHITE)
            for (s in image.strokes) {
                if (s.id !in touched && (!partial || RectF.intersects(s.bounds, dirtyPaper))) s.drawOnPaper(target, paperPaint)
            }
            paperRevision = document.revision
            dirtyPaper.setEmpty()
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.save()
        transformToPage(canvas)
        live?.draw(canvas)
        if (ink?.brush?.erases == true && points.isNotEmpty()) {
            val tip = points.last()
            ring.strokeWidth = 2f / scale; ring.color = Color.WHITE
            canvas.drawCircle(tip.x,tip.y,ink.width/2,ring)
            ring.color = INK; ring.strokeWidth = 1f / scale
            canvas.drawCircle(tip.x,tip.y,ink.width/2,ring)
        }
        canvas.restore()
    }
    private fun transformToPage(canvas: Canvas) {
        canvas.translate(leftMargin, topMargin)
        canvas.scale(scale, scale)
        canvas.clipRect(page)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (draft != null) cancelStroke()
        paper = null
        paperRevision = -1
    }
    fun cancelStroke() {
        draft?.let { if (sentPoints > 0) preview(it, "cancel", emptyList(), sentPoints) }
        if (touched.isNotEmpty()) paperRevision = -1
        if (pressedAt >= 0) ignoreUntilRelease = true
        resetButton()
        reset()
    }
    private fun reset() {
        removeCallbacks(sendPending)
        previewScheduled = false
        sentPoints = 0
        newHits.clear()
        draft = null; owner = -1; points = ArrayList(); touched.clear()
        if (temporaryErasing && pressedAt < 0) resetButton()
        live = null; wholeGesture = null; invalidate()
    }
    override fun onDetachedFromWindow() {
        warming?.cancel(true); warming = null
        cancelStroke(); paper = null; paperRevision = -1; hitIndex = null; indexRevision = -1
        super.onDetachedFromWindow()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0 &&
            (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(event.actionIndex) == owner) { cancelStroke(); return true }
        trackSideButton(event)
        val index = event.actionIndex
        val begin = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val tool = event.getToolType(index)
        if (owner < 0 && tool == MotionEvent.TOOL_TYPE_FINGER) { gestures.onTouchEvent(event); return true }
        if (begin) {
            if (owner >= 0 || tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return true
            val x = (event.getX(index)-leftMargin)/scale; val y = (event.getY(index)-topMargin)/scale
            if (x < 0 || x >= page.width() || y < 0 || y >= page.height()) return true
            if (pressedAt >= 0 && tool == MotionEvent.TOOL_TYPE_STYLUS) activateEraser.run()
            val temporaryEraser = tool == MotionEvent.TOOL_TYPE_ERASER || temporaryErasing
            val selected = if (temporaryEraser && !brush.erases) lastEraser else brush
            try { startStroke(selected, event.eventTime) }
            catch (e: java.io.IOException) {
                cancelStroke()
                Toast.makeText(context,"필기 복원 실패 · ${e.localizedMessage}",Toast.LENGTH_LONG).show()
                return true
            }
            requestUnbufferedDispatch(event)
            owner = event.getPointerId(index)
        }
        if (draft == null) return true
        val p = event.findPointerIndex(owner)
        if (p < 0 || end && p != index) return true
        if (end && event.flags and MotionEvent.FLAG_CANCELED != 0) { cancelStroke(); return true }
        if (!begin && !end && action != MotionEvent.ACTION_MOVE) return true
        val ink = draft!!
        fun sample(rawX: Float, rawY: Float, pressure: Float) {
            val x = ((rawX-leftMargin)/scale).coerceIn(0f,page.width()-1f)
            val y = ((rawY-topMargin)/scale).coerceIn(0f,page.height()-1f)
            val point = Sample(x,y,pressure.coerceIn(0f,1f))
            appendPoint(ink,point)
        }
        for (i in 0 until event.historySize)
            sample(event.getHistoricalX(p,i),event.getHistoricalY(p,i),event.getHistoricalPressure(p,i))
        sample(event.getX(p),event.getY(p),event.getPressure(p))
        if (!end) schedulePreview()
        if (end) {
            if (sentPoints > 0 || ink.brush.erases) flushPreview()
            try {
                val before = document.revision
                finishStroke(ink)
                reset()
                if (before != document.revision) committed()
            }
            catch (e: java.io.IOException) { cancelStroke(); Toast.makeText(context,"획 저장 실패 · ${e.localizedMessage}",Toast.LENGTH_LONG).show() }
        }
        invalidate(); return true
    }
    private fun startStroke(selected: Brush, eventTime: Long) {
        strokeMode = previewMode
        nextPreviewAt = eventTime + if (strokeMode == PreviewMode.CompletedFirst) COMPLETED_FIRST_DELAY_MS else 0L
        val ink = InkStroke(UUID.randomUUID().toString(),selected,penColor,
            if (selected.erases) eraserWidth else penWidth,pressureEnabled && !selected.erases,emptyList(),
            when (selected) {
                Brush.Pen -> penTexture
                Brush.Pencil -> InkTexture.Graphite
                else -> InkTexture.Smooth
            },pressureStrength)
        draft = ink
        if (selected == Brush.Whole) {
            if (indexRevision != document.revision) {
                hitIndex = StrokeHitIndex(document.width,document.height).also { index -> document.frozen().strokes.forEach(index::add) }
                indexRevision = document.revision
            }
            wholeGesture = hitIndex!!.Gesture()
        } else live = LiveInk(ink,scale,leftMargin,topMargin,width,height)
    }
    private fun appendPoint(ink: InkStroke, point: Sample) {
        if (ink.brush == Brush.Whole) {
            val previous = points.lastOrNull()
            if (previous?.x == point.x && previous.y == point.y) return
            wholeGesture?.sample(ink,previous ?: point,point,touched,dirtyPaper) { newHits.add(it) }
            points.clear()
        } else live?.append(point)
        points.add(point)
    }
    private fun finishStroke(ink: InkStroke) {
        val before = document.revision
        if (ink.brush == Brush.Whole) {
            document.eraseStrokes(touched,ink.id)
            if (indexRevision == before) hitIndex?.remove(touched)
        } else {
            val stroke = ink.copy(points = points)
            document.commit(stroke)
            if (!ink.brush.erases && sentPoints == 0) savedStroke(stroke)
            if (paperRevision == before) paper?.let {
                val canvas = Canvas(it)
                transformToPage(canvas)
                live?.draw(canvas)
            }
            if (indexRevision == before) hitIndex?.add(stroke)
        }
        if (paperRevision == before) paperRevision = document.revision
        if (indexRevision == before) indexRevision = document.revision
    }
    companion object {
        private const val REALTIME_INTERVAL_MS = 33L
        private const val COMPLETED_FIRST_INTERVAL_MS = 250L
        private const val COMPLETED_FIRST_DELAY_MS = 1000L
        private const val ERASER_HOLD_MS = 220L
        private val indexWorker = Executors.newSingleThreadExecutor()
        const val INK = 0xff4338ac.toInt()
        const val BRUSH = 5f
    }
}
