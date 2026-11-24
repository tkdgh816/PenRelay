package dev.penrelay.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Size
import android.view.View
import android.widget.PopupMenu
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.text.InputFilter
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors
import java.io.File
import java.util.UUID

internal class PageActions(private val activity: ComponentActivity, private val book: () -> PageBook,
    private val select: (CanvasInfo) -> Unit, private val changed: () -> Unit,
    private val notice: (String) -> Unit) {
    var exportId: String? = null
    var exportRevision = -1L
    private val worker = Executors.newSingleThreadExecutor()
    private val pngFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { export(it,false) }
    private val svgFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { export(it,true) }
    private fun export(uri: Uri?, svg: Boolean) {
        val frozen = book().pendingExport
        val version = book().documents().firstOrNull { it.id == exportId && it.revision == exportRevision }?.copy()
        val store = book().store
        book().pendingExport = null; exportId = null
        if (uri == null) return
        worker.execute {
            val result = runCatching {
                val page = frozen ?: version?.let(store::loadPage) ?: error("선택했던 페이지 버전을 열 수 없습니다.")
                val output = activity.contentResolver.openOutputStream(uri,"wt") ?: error("파일을 열 수 없습니다.")
                output.use { if (svg) page.svg(it) else page.png(it) }
            }
            activity.runOnUiThread { if (!activity.isDestroyed) notice(result.fold({ "페이지를 내보냈습니다." },{ "내보내기 실패 · ${it.localizedMessage}" })) }
        }
    }
    private fun selectedImage(): PageImage? = try {
        book().document.frozen()
    } catch (e: java.io.IOException) {
        notice("필기 복원 실패 · ${e.localizedMessage}")
        null
    }
    private fun startExport(svg: Boolean) {
        val document = book().selected
        book().pendingExport = selectedImage() ?: return
        exportId = document.id
        exportRevision = document.revision
        val title = book().title(document).map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("")
        if (svg) svgFile.launch("$title.svg") else pngFile.launch("$title.png")
    }
    fun noteMenu(target: CanvasInfo) {
        val ui = PenRelayUi(activity)
        val title = ui.label(book().title(target),18,ui.ink,true).apply { ui.inset(this,24,16) }
        AlertDialog.Builder(activity).setCustomTitle(title)
            .setItems(arrayOf("이름 변경","노트 삭제")) { _, index ->
                if (index == 0) rename(target) else deletePage(target)
            }.setNegativeButton("닫기",null).show()
    }
    fun addPage() {
        runCatching { select(book().add(activity.resources.configuration)); changed() }
            .onFailure { notice("새 노트를 만들 수 없습니다. " + it.localizedMessage) }
    }
    fun addCanvas() {
        runCatching { select(book().addCanvas(activity.resources.configuration)); changed() }.onFailure { notice("페이지 추가 실패 · " + it.localizedMessage) }
    }
    fun confirmAddNote() {
        AlertDialog.Builder(activity).setTitle("노트 추가")
            .setMessage("새 노트를 추가할까요?")
            .setNegativeButton("취소",null).setPositiveButton("추가") { _,_ -> addPage() }.show()
    }
    fun confirmAddCanvas() {
        val page = book().selected
        AlertDialog.Builder(activity).setTitle("페이지 추가")
            .setMessage("${book().title(page)}에 새 페이지를 추가할까요?")
            .setNegativeButton("취소",null).setPositiveButton("추가") { _,_ ->
                if (book().selected === page) addCanvas()
            }.show()
    }
    private fun deleteCanvas(target: CanvasInfo, label: String) {
        AlertDialog.Builder(activity).setTitle("$label 삭제")
            .setMessage("이 페이지에 저장된 필기도 삭제됩니다.").setNegativeButton("취소",null)
            .setPositiveButton("삭제") { _,_ ->
                runCatching { editBook { book().deleteCanvas(target) } }
                    .onFailure { notice("페이지 삭제 실패 · " + it.localizedMessage) }
            }.show()
    }
    private fun rename(target: CanvasInfo) {
        val field = EditText(activity).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(80))
            setText(book().title(target)); selectAll()
        }
        val dialog = AlertDialog.Builder(activity).setTitle("노트 제목 수정").setView(field)
            .setNegativeButton("취소",null).setPositiveButton("저장",null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = field.text.toString().trim()
            if (title.isBlank()) field.error = "제목을 입력하세요."
            else { book().rename(title,target); changed(); dialog.dismiss() }
        }
    }
    fun share() {
        val page = selectedImage() ?: return
        val title = book().title(book().selected)
        worker.execute {
            val result = runCatching {
                val dir = File(activity.cacheDir,"shared-pages").apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
                val file = File(dir,"${UUID.randomUUID()}.png")
                file.outputStream().buffered().use(page::png)
                FileProvider.getUriForFile(activity,activity.packageName + ".pages",file,"$title.png")
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                result.onSuccess { uri ->
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newUri(activity.contentResolver,title,uri)
                    activity.startActivity(Intent.createChooser(send,"페이지 공유"))
                }.onFailure { notice("공유 실패 · ${it.localizedMessage}") }
            }
        }
    }
    fun clearPage(cleared: () -> Unit) {
        val page = book().selected
        AlertDialog.Builder(activity).setTitle("이 페이지의 필기를 모두 지울까요?")
            .setMessage("빈 페이지는 노트에 남습니다.").setNegativeButton("취소",null)
            .setPositiveButton("전체 지우기") { _,_ ->
                runCatching { if (book().selected === page) { book().document.clear(); changed() } }
                    .onSuccess { if (book().selected === page) cleared() }
                    .onFailure { notice("페이지 저장 실패 · ${it.localizedMessage}") }
            }.show()
    }
    private fun resizeCanvas(page: CanvasInfo, label: String) {
        editSize("$label 크기","기존 필기는 비율을 유지해 가운데에 배치합니다.",Size(page.width,page.height)) { size ->
            if (size.width != page.width || size.height != page.height)
                editBook { book().resize(size.width,size.height,page) }
        }
    }
    private fun editDefaultSize(orientation: CanvasOrientation) {
        val initial = book().defaultSize(activity.resources.configuration,orientation)
        editSize("${orientation.label} 기본 크기","이 방향에서 새로 만드는 노트와 페이지에 적용합니다.",initial) { size ->
            book().setDefaultSize(orientation,size)
        }
    }
    private fun editSize(title: String, description: String, initial: Size, save: (Size) -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad/2,pad,0) }
        fun dimension(label: String, initial: Int): EditText {
            panel.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText(initial.toString()); setSelectAllOnFocus(true)
                panel.addView(this)
            }
        }
        val w = dimension("너비 (px)",initial.width); val h = dimension("높이 (px)",initial.height)
        val dialog = AlertDialog.Builder(activity).setTitle(title)
            .setMessage("$description 각 변은 64부터 2048 px까지입니다.")
            .setView(ScrollView(activity).apply { addView(panel) }).setNegativeButton("취소",null).setPositiveButton("적용",null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val width = w.text.toString().toIntOrNull() ?: 0; val height = h.text.toString().toIntOrNull() ?: 0
            when {
                width !in 64..2048 -> w.error = "64부터 2048 사이로 입력하세요."
                height !in 64..2048 -> h.error = "64부터 2048 사이로 입력하세요."
                else -> {
                    runCatching { save(Size(width,height)) }
                        .onSuccess { dialog.dismiss() }
                        .onFailure { notice("크기 저장 실패 · ${it.localizedMessage}") }
                }
            }
        }
    }
    private fun editBook(edit: () -> Unit) {
        val before = book().selected
        edit()
        if (book().selected !== before) select(book().selected)
        changed()
    }
    private fun deletePage(target: CanvasInfo) {
        val pages = book().pageChoices()
        AlertDialog.Builder(activity).setTitle("${book().title(target)} 삭제")
            .setMessage(if (pages.size == 1) "마지막 노트를 삭제하고 새 빈 노트를 엽니다." else "이 노트의 모든 페이지와 필기를 삭제합니다.")
            .setNegativeButton("취소",null).setPositiveButton("삭제") { _,_ ->
                runCatching { editBook { book().deletePage(target,activity.resources.configuration) } }.onFailure { notice("노트 삭제 실패 · ${it.localizedMessage}") }
            }.show()
    }
    fun canvasMenu(anchor: View, target: CanvasInfo, index: Int) {
        val label = "${book().title(target)} · 페이지 ${index + 1}"
        PopupMenu(activity,anchor).apply {
            menu.add("크기 변경").setOnMenuItemClickListener { resizeCanvas(target,label); true }
            menu.add("페이지 삭제").apply {
                isEnabled = book().canvases().size > 1
                setOnMenuItemClickListener { deleteCanvas(target,label); true }
            }
        }.show()
    }
    fun moveNote(source: String, destination: String) {
        runCatching { if (book().moveNote(source,destination)) changed() }
            .onFailure { notice("노트 순서 저장 실패 · " + it.localizedMessage) }
    }
    fun moveCanvas(source: String, destination: String) {
        runCatching { if (book().moveCanvas(source,destination)) changed() }
            .onFailure { notice("페이지 순서 저장 실패 · ${it.localizedMessage}") }
    }
    private fun choosePreviewMode(mode: PreviewMode, setPreview: (PreviewMode) -> Unit) {
        val builder = AlertDialog.Builder(activity).setTitle("PC에 필기 표시").setNegativeButton("닫기", null)
        val context = builder.context
        val ui = PenRelayUi(context)
        val group = RadioGroup(context).apply { ui.inset(this, 20, 4) }
        val dialog = builder.setView(ScrollView(context).apply { addView(group) }).create()
        for (option in PreviewMode.entries) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = SpannableString("${option.label}\n${option.description}").apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, option.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ui.inset(this, 8, 12)
                setOnClickListener {
                    setPreview(option)
                    dialog.dismiss()
                }
            }
            group.addView(button)
            if (option == mode) group.check(button.id)
        }
        dialog.show()
    }
    fun mainMenu(anchor: View, mode: PreviewMode, setPreview: (PreviewMode) -> Unit,
        home: () -> Unit, digitizer: () -> Unit) {
        PopupMenu(activity,anchor).apply {
            menu.add("홈").setOnMenuItemClickListener { home(); true }
            menu.add("PC에 필기 표시").setOnMenuItemClickListener { choosePreviewMode(mode, setPreview); true }
            val defaults = menu.addSubMenu("새 페이지 기본 크기")
            for (orientation in CanvasOrientation.entries) {
                defaults.add(orientation.label).setOnMenuItemClickListener { editDefaultSize(orientation); true }
            }
            val formats = menu.addSubMenu("내보내기")
            formats.add("PNG 내보내기").setOnMenuItemClickListener { startExport(false); true }
            formats.add("SVG 내보내기").setOnMenuItemClickListener { startExport(true); true }
            menu.add("공유").setOnMenuItemClickListener { share(); true }
            menu.add("디지타이저 모드").setOnMenuItemClickListener { digitizer(); true }
        }.show()
    }
    fun close() = worker.shutdown()
}
