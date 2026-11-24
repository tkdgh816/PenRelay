package dev.penrelay.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

internal class PenRelayUi(val context: Context) {
    val ink = 0xff201d32.toInt()
    val violet = 0xff5043bb.toInt()
    val mist = 0xffedebfb.toInt()
    val paper = 0xfff8f7fb.toInt()
    private val muted = 0xff534e64.toInt()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun label(value: String, size: Int = 14, color: Int = muted, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun inset(view: View, h: Int, v: Int) = view.setPadding(dp(h), dp(v), dp(h), dp(v))
    private fun buttonSurface(fill: Int): InsetDrawable {
        val surface = RippleDrawable(ColorStateList.valueOf(0x268981c8),shape(fill,4),null)
        return InsetDrawable(surface,dp(3),dp(3),dp(3),dp(3))
    }
    private fun style(button: Button, fill: Int, foreground: Int) {
        button.backgroundTintList = null
        button.background = buttonSurface(fill)
        button.stateListAnimator = null
        button.setTextColor(foreground)
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL))
        inset(button,16,8)
    }
    fun action(title: String, click: () -> Unit): Button = Button(context).apply {
        text = title; isAllCaps = false; minHeight = dp(48)
        minWidth = 0; minimumWidth = 0; letterSpacing = 0f; textSize = 14f
        style(this,mist,violet)
        setOnClickListener { click() }
    }
    fun icon(id: Int, color: Int, size: Int) = ImageView(context).apply {
        setImageResource(id); setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun hero(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xff26252c.toInt(), 0xff38353f.toInt())).apply { cornerRadius = dp(20).toFloat() }
        inset(this, 24, 22)
        addView(column().apply {
            addView(label(context.getString(R.string.app_name), 32, Color.WHITE, true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.penrelay_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(68), dp(68)))
    }
    fun mode(title: String, detail: String, glyph: Int, click: () -> Unit) =
        action(title, click).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(88); textSize = 18f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            val copy = android.text.SpannableString("$title\n$detail")
            val start = title.length + 1
            copy.setSpan(android.text.style.RelativeSizeSpan(.75f), start, copy.length, 0)
            copy.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), start, copy.length, 0)
            text = copy
            style(this,mist,ink)
            inset(this, 16, 12)
            val image = context.getDrawable(glyph)!!.mutate().apply { setTint(violet); setBounds(0, 0, dp(28), dp(28)) }
            compoundDrawablePadding = dp(14)
            setCompoundDrawables(image, null, null, null)
        }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    fun commandGroup(sidebar: Boolean) = if (sidebar) column() else row()
    fun toolbarAction(title: String, click: () -> Unit) = action(title,click).apply { height = dp(48) }
    fun workArea(sidebar: Boolean, navigation: LinearLayout, tools: LinearLayout, commands: LinearLayout,
        surface: View, strip: View? = null) = LinearLayout(context).apply {
        setBackgroundColor(paper)
        if (sidebar) {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(workSidebar(navigation,tools,commands),LinearLayout.LayoutParams(dp(112),-1))
            if (strip != null) addView(strip,LinearLayout.LayoutParams(dp(108),-1))
            addView(surface,LinearLayout.LayoutParams(0,-1,1f))
        } else {
            orientation = LinearLayout.VERTICAL
            addView(workToolbar(navigation,tools,commands,fillWidth = surface is CanvasSurface))
            if (strip != null) addView(strip)
            addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        }
    }
    private fun workToolbar(pages: LinearLayout, editor: LinearLayout, commands: LinearLayout, fillWidth: Boolean): View {
        val groups = listOf(pages,editor,commands)
        val buttonCount = groups.sumOf { it.childCount }
        for (group in groups) {
            if (group.orientation != LinearLayout.HORIZONTAL) continue
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                val params = child.layoutParams as LinearLayout.LayoutParams
                params.weight = 1f
                if (child is Button) {
                    child.setSingleLine()
                    inset(child,6,2)
                    child.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                }
            }
        }
        return object : FrameLayout(context) {
            init { groups.forEach { addView(it) } }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val free = MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
                if (fillWidth) {
                    val padding = ((width / buttonCount - dp(44)) / 2).coerceIn(dp(6),dp(16))
                    for (group in groups) for (index in 0 until group.childCount) {
                        val button = group.getChildAt(index) as? Button ?: continue
                        if (button.paddingLeft != padding) button.setPadding(padding,dp(2),padding,dp(2))
                    }
                }
                groups.forEach { it.measure(free,free) }
                val total = groups.sumOf { it.measuredWidth }
                if (total > width || fillWidth) {
                    // 좁으면 콘텐츠 너비에 비례해 줄이고, 여유 공간은 각 버튼에 고르게 배분한다.
                    var remaining = width
                    groups.forEachIndexed { index, group ->
                        val share = when {
                            index == groups.lastIndex -> remaining
                            total > width -> (width.toLong() * group.measuredWidth / total).toInt()
                            else -> group.measuredWidth + (width-total) * group.childCount / buttonCount
                        }
                        group.measure(MeasureSpec.makeMeasureSpec(share,MeasureSpec.EXACTLY),free)
                        remaining -= share
                    }
                }
                setMeasuredDimension(width,resolveSize(groups.maxOf { it.measuredHeight },heightMeasureSpec))
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                fun place(child: View, start: Int) {
                    val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width-start-child.measuredWidth else start
                    val y = (height-child.measuredHeight)/2
                    child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
                }
                place(pages,0)
                val end = width-commands.measuredWidth
                place(editor,((width-editor.measuredWidth)/2).coerceIn(pages.measuredWidth,end-editor.measuredWidth))
                place(commands,end)
            }
        }
    }
    fun rail(row: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row); inset(this, 8, 0)
    }
    private fun workSidebar(pages: LinearLayout, editor: View, commands: LinearLayout): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(object : FrameLayout(context) {
                init {
                    inset(this,4,4)
                    addView(pages,LayoutParams(-1,-2,Gravity.TOP))
                    addView(editor,LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
                    addView(commands,LayoutParams(-1,-2,Gravity.BOTTOM))
                }
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec,heightMeasureSpec)
                    val needed = editor.measuredHeight + 2 * maxOf(pages.measuredHeight,commands.measuredHeight) +
                        paddingTop + paddingBottom
                    setMeasuredDimension(measuredWidth,resolveSize(needed,heightMeasureSpec))
                }
            })
        }
    }
    fun historyButton(title: String, glyph: Int, click: () -> Unit) = ImageButton(context).apply {
        contentDescription = title
        tooltipText = title
        layoutParams = LinearLayout.LayoutParams(dp(48),dp(48))
        background = buttonSurface(mist)
        setImageResource(glyph)
        setColorFilter(violet)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { click() }
    }
    fun editor(view: CanvasSurface, clearPage: (() -> Unit) -> Unit, undo: View, redo: View, sidebar: Boolean, changed: () -> Unit): LinearLayout {
        val row = if (sidebar) column() else row()
        val history = if (sidebar) row().apply { gravity = Gravity.CENTER; row.addView(this) } else row
        history.addView(undo)
        history.addView(redo)
        val brush = toolbarAction(view.brush.label) {}
        val erase = toolbarAction("지우개") {}
        val width = toolbarAction("") {}
        if (sidebar) inset(width,4,4)
        val selected = intArrayOf(android.R.attr.state_selected)
        val states = arrayOf(selected, intArrayOf())
        val colors = ColorStateList(states, intArrayOf(Color.WHITE, ink))
        for ((button, iconId) in listOf(brush to R.drawable.penrelay_nib, erase to R.drawable.penrelay_eraser)) {
            button.background = android.graphics.drawable.StateListDrawable().apply {
                addState(selected, buttonSurface(violet))
                addState(intArrayOf(), buttonSurface(Color.WHITE))
            }
            button.setTextColor(colors)
            button.compoundDrawableTintList = colors
            val glyph = context.getDrawable(iconId)!!.mutate().apply { setBounds(0,0,dp(18),dp(18)) }
            button.setCompoundDrawables(glyph,null,null,null)
            button.compoundDrawablePadding = dp(6)
            inset(button,4,4)
        }
        if (!sidebar) {
            brush.setSingleLine()
            val labelWidth = PenKind.entries.maxOf { android.text.Layout.getDesiredWidth(it.label,brush.paint) }
            brush.minWidth = kotlin.math.ceil(labelWidth).toInt() + brush.compoundPaddingLeft + brush.compoundPaddingRight + dp(2)
        }
        fun select(value: Brush, persist: Boolean = true) {
            view.brush = value
            if (value.erases) view.lastEraser = value else view.lastInk = value
            fun label(button: Button, text: String) { if (button.text != text) button.text = text }
            val erasing = value.erases || view.temporaryErasing
            label(width, "굵기 · ${widthText(if (erasing) view.eraserWidth else view.penWidth)} px")
            label(brush, if (view.lastInk == Brush.Pen) view.penTexture.label else view.lastInk.label)
            label(erase, if (erasing) view.lastEraser.label else "지우개")
            brush.isSelected = !erasing
            erase.isSelected = erasing
            if (persist) changed()
        }
        view.onToolChanged = { select(view.brush) }
        view.onTemporaryEraserChanged = { select(view.brush,persist = false) }
        brush.setOnClickListener {
            if (view.brush.erases) select(view.lastInk) else PopupMenu(context,brush).apply {
                for (pen in PenKind.entries) {
                    menu.add(1,pen.ordinal,pen.ordinal,pen.label).apply {
                        setOnMenuItemClickListener { view.selectPen(pen); select(view.brush); true }
                    }
                }
                menu.setGroupCheckable(1,true,true)
                menu.findItem(view.currentPen.ordinal).isChecked = true
                menu.add(0,View.NO_ID,PenKind.entries.size,"S펜 버튼으로 전환할 펜").setOnMenuItemClickListener {
                    chooseCyclePens(view,changed); true
                }
            }.show()
        }
        fun confirmClear() = clearPage { select(view.lastInk) }
        erase.setOnLongClickListener { confirmClear(); true }
        erase.setOnClickListener {
            if (!view.brush.erases) select(view.lastEraser) else PopupMenu(context,erase).apply {
                for (tool in listOf(Brush.Partial,Brush.Whole))
                    menu.add(tool.label).setOnMenuItemClickListener { select(tool); true }
                menu.add("페이지 전체 지우기").setOnMenuItemClickListener {
                    confirmClear(); true
                }
            }.show()
        }
        row.addView(brush); row.addView(erase)
        select(view.brush, persist = false)
        val color = toolbarAction("색상") {}
        fun chip() {
            val dot = shape(view.penColor,10).apply { setBounds(0,0,dp(16),dp(16)) }
            color.setCompoundDrawables(dot,null,null,null); color.compoundDrawablePadding = dp(5)
            color.contentDescription = "색상 선택 · #%06X".format(view.penColor and 0xffffff)
        }
        chip()
        color.setOnClickListener {
            openPalette(view.penColor) { picked -> view.penColor = picked; select(view.lastInk); chip() }
        }
        row.addView(color)
        width.setOnClickListener {
            brushSettings(view) { select(view.brush) }
        }
        row.addView(width)
        row.background = shape(Color.WHITE,8); inset(row,4,2)
        return row
    }
    private fun widthText(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun chooseCyclePens(view: CanvasSurface, changed: () -> Unit) {
        val pens = PenKind.entries
        val checked = BooleanArray(pens.size) { pens[it] in view.cyclePens }
        android.app.AlertDialog.Builder(context).setTitle("S펜 버튼으로 전환할 펜")
            .setMultiChoiceItems(pens.map { it.label }.toTypedArray(),checked) { dialog, index, enabled ->
                checked[index] = enabled
                (dialog as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }.setNegativeButton("취소",null).setPositiveButton("적용") { _,_ ->
                view.cyclePens = pens.filterIndexed { index,_ -> checked[index] }.toSet()
                changed()
            }.show()
    }

    private fun brushSettings(view: CanvasSurface, applied: () -> Unit) {
        val panel = column().apply { inset(this,20,8) }
        val erasing = view.brush.erases
        val widths = if (erasing) (1..288).map { it.toFloat() }
            else (1..20).map { it / 5f } + (5..96).map { it.toFloat() }
        val start = widths.indices.minBy { kotlin.math.abs(widths[it] - view.activeWidth) }
        val amount = label("굵기 · ${widthText(widths[start])} px", 18)
        val bar = slider("굵기", widths.lastIndex, start) { amount.text = "굵기 · ${widthText(widths[it])} px" }
        panel.addView(amount)
        panel.addView(row().apply {
            addView(action("−") { bar.progress-- }.apply { contentDescription = "굵기 한 단계 줄이기" })
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(action("+") { bar.progress++ }.apply { contentDescription = "굵기 한 단계 늘리기" })
        })
        var strength = if (view.pressureEnabled) view.pressureStrength else 0
        if (!erasing) {
            val level = label("필압 반영 · $strength%")
            panel.addView(level)
            panel.addView(slider("필압 반영 정도", 100, strength) {
                strength = it
                level.text = "필압 반영 · $it%"
            })
        }
        android.app.AlertDialog.Builder(context).setTitle(if (erasing) "지우개 굵기" else "${view.brush.label} 설정")
            .setView(ScrollView(context).apply { addView(panel) })
            .setNegativeButton("취소",null).setPositiveButton("적용") { _,_ ->
                view.activeWidth = widths[bar.progress]
                if (!erasing) {
                    view.pressureStrength = strength
                    view.pressureEnabled = strength > 0
                }
                applied()
            }.show()
    }

    private fun openPalette(initial: Int, apply: (Int) -> Unit) {
        val config = context.resources.configuration
        val wide = config.screenWidthDp >= 560
        val dialogWidth = (config.screenWidthDp-32).coerceIn(240,780)
        val panel = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            inset(this,16,8); gravity = Gravity.CENTER
        }
        val options = column().apply { inset(this,10,8) }
        val sample = label("",15,ink,true).apply { gravity = Gravity.CENTER; minHeight = dp(40) }
        fun preview(color: Int) {
            sample.background = shape(color,16)
            sample.text = "#%06X".format(color and 0xffffff)
            sample.setTextColor(if (Color.luminance(color) > .4f) Color.BLACK else Color.WHITE)
        }
        val wheel = InkPalette(context,initial,::preview).apply {
            contentDescription = "바깥 고리에서 색상, 안쪽 면에서 채도와 밝기 선택"
        }
        val wheelSize = minOf(if (wide) (dialogWidth-48)*3/5 else dialogWidth-40,
            (config.screenHeightDp-180).coerceAtLeast(256),420)
        panel.addView(wheel,LinearLayout.LayoutParams(dp(wheelSize),dp(wheelSize)))
        panel.addView(options,if (wide) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        options.addView(sample,LinearLayout.LayoutParams(-1,dp(44)))
        options.addView(label("추천 색상",15,ink,true).apply { inset(this,2,8) })
        val colors = listOf("먹색" to 0xff20212c, "회색" to 0xff888895, "흰색" to 0xffffffff,
            "빨강" to 0xffd94950, "주황" to 0xfff48a43, "노랑" to 0xfff6d45e,
            "초록" to 0xff309e66, "청록" to 0xff32aaa3, "하늘" to 0xff62b7e7,
            "파랑" to 0xff356dc5, "보라" to 0xff6550c9, "분홍" to 0xffdc6892)
        for (group in colors.chunked(if (wide) 3 else 6)) {
            val swatches = row()
            for ((name,argb) in group) {
                val button = ImageButton(context).apply {
                    contentDescription = name
                    backgroundTintList = null
                    background = RippleDrawable(ColorStateList.valueOf(0x24000000),null,shape(Color.WHITE))
                    val disc = shape(argb.toInt(),30).apply { setStroke(dp(1),0x28000000) }
                    setImageDrawable(disc.apply { setSize(dp(30),dp(30)) })
                    scaleType = ImageView.ScaleType.CENTER
                    setOnClickListener { wheel.select(argb.toInt()) }
                }
                swatches.addView(button,LinearLayout.LayoutParams(0,dp(42),1f))
            }
            options.addView(swatches,LinearLayout.LayoutParams(-1,-2))
        }
        preview(initial)
        android.app.AlertDialog.Builder(context).setTitle("색상 팔레트")
            .setView(ScrollView(context).apply { addView(panel) })
            .setNegativeButton("취소",null).setPositiveButton("적용") { _,_ -> apply(wheel.color) }
            .show().window?.apply {
                setBackgroundDrawable(shape(Color.WHITE,28)); setLayout(dp(dialogWidth),-2)
            }
    }
    private fun slider(name: String, limit: Int, value: Int, update: (Int) -> Unit) = SeekBar(context).apply {
        contentDescription = name; max = limit; progress = value; minimumHeight = dp(48)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = update(progress)
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }
}
