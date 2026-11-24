package dev.penrelay.app

import android.content.Context
import android.content.res.Configuration
import android.util.Size
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal enum class CanvasOrientation(val label: String, val preferenceKey: String) {
    Landscape("가로 모드", "canvas-landscape"),
    Portrait("세로 모드", "canvas-portrait");

    companion object {
        fun from(config: Configuration) = when (config.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> Landscape
            Configuration.ORIENTATION_PORTRAIT -> Portrait
            else -> if (config.screenWidthDp > config.screenHeightDp) Landscape else Portrait
        }
    }
}

internal class PageBook(private val context: Context) {
    val store = StrokeStore(context)
    private val prefs = context.getSharedPreferences("penrelay-book", Context.MODE_PRIVATE)
    private val pages = store.documents().toMutableList()
    private val titles = store.titles()
    private data class Group(val id: String, val canvases: List<String>)
    private var groups = store.value("groups")?.let { json ->
        val array = JSONArray(json)
        List(array.length()) { i ->
            val row = array.getJSONObject(i)
            val ids = row.getJSONArray("canvases")
            Group(row.getString("id"),List(ids.length()) { ids.getString(it) })
        }
    } ?: emptyList()
    lateinit var selected: CanvasInfo
        private set
    var pendingExport: PageImage? = null
    private var editor: CanvasDocument? = null
    val document: CanvasDocument get() = editor ?: open(selected).also { editor = it }

    private fun open(page: CanvasInfo, image: PageImage? = null): CanvasDocument {
        val version = page.copy()
        return CanvasDocument(page.width,page.height,page.id,page.revision,image?.strokes.orEmpty(),
            load = if (image == null) ({ store.loadPage(version) }) else null,
            save = { before, next ->
                store.save(page,before,next)
                page.revision = next.revision
            })
    }

    private fun activate(page: CanvasInfo) {
        if (!::selected.isInitialized || selected !== page) editor = null
        selected = page
    }
    init {
        val selectedId = store.value("selected")
        selected = pages.firstOrNull { it.id == selectedId } ?: pages.firstOrNull() ?: add()
    }
    fun documents() = pages.toList()
    private fun group(id: String) = groups.first { id in it.canvases }
    fun canvases() = group(selected.id).canvases.map { id -> pages.first { it.id == id } }
    fun pageCount(page: CanvasInfo) = group(page.id).canvases.size
    fun pageChoices() = groups.map { g -> if (selected.id in g.canvases) selected else pages.first { it.id == g.canvases.first() } }
    private fun encode(rows: List<Group>) = JSONArray().apply {
        rows.forEach { put(JSONObject().put("id",it.id).put("canvases",JSONArray(it.canvases))) }
    }
    fun layout() = encode(groups)
    fun moveCanvas(source: String, destination: String): Boolean {
        val row = group(selected.id)
        val order = row.canvases.toMutableList()
        val target = order.indexOf(destination)
        if (source == destination || target < 0 || !order.remove(source)) return false
        order.add(target,source)
        commit(groups.map { if (it == row) it.copy(canvases = order) else it },selected)
        return true
    }
    fun moveNote(source: String, destination: String): Boolean {
        val from = groups.indexOfFirst { source in it.canvases }
        val to = groups.indexOfFirst { destination in it.canvases }
        if (from < 0 || to < 0 || from == to) return false

        val reordered = groups.toMutableList()
        reordered.add(to,reordered.removeAt(from))
        // 위치에서 생성된 기본 제목도 노트를 옮길 때 함께 유지한다.
        val savedTitles = mutableMapOf<String,String>()
        for (index in minOf(from,to)..maxOf(from,to)) {
            val id = groups[index].id
            if (id !in titles) savedTitles[id] = defaultTitle(index)
        }
        commit(reordered,selected,savedTitles = savedTitles)
        return true
    }
    private fun commit(rows: List<Group>, active: CanvasInfo, created: CanvasInfo? = null,
        removed: List<String> = emptyList(), savedTitles: Map<String,String> = emptyMap()) {
        store.edit { db, _ ->
            if (created != null) store.create(db,created)
            for (id in removed) store.remove(db,id)
            for (group in groups) {
                if (rows.none { it.id == group.id }) db.delete("meta","k=?",arrayOf("title-" + group.id))
            }
            for ((id,title) in savedTitles) store.put("title-$id",title)
            store.put("groups",encode(rows).toString())
            store.put("selected",active.id)
        }
        pages.removeAll { it.id in removed }
        if (created != null) pages.add(created)
        titles.keys.retainAll(rows.map { it.id }.toSet())
        titles.putAll(savedTitles)
        groups = rows
        activate(active)
    }
    fun customTitle(id: String): String? = titles[group(id).id]
    private fun defaultTitle(index: Int) = "노트 ${index + 1}"
    fun title(page: CanvasInfo): String = customTitle(page.id) ?: defaultTitle(groups.indexOf(group(page.id)))
    fun rename(title: String, page: CanvasInfo = selected) {
        require(title.isNotBlank() && title.length <= 80)
        val id = group(page.id).id
        if (titles[id] == title) return
        store.edit { _, _ -> store.put("title-$id", title) }
        titles[id] = title
    }
    fun select(page: CanvasInfo) {
        if (selected.id != page.id) store.put("selected",page.id)
        activate(page)
    }
    fun defaultSize(config: Configuration, orientation: CanvasOrientation = CanvasOrientation.from(config)): Size {
        val key = orientation.preferenceKey
        val width = prefs.getInt("$key-width",0)
        val height = prefs.getInt("$key-height",0)
        if (width in 64..2048 && height in 64..2048) return Size(width,height)

        val w = config.screenWidthDp.coerceAtLeast(1)
        val h = config.screenHeightDp.coerceAtLeast(1)
        val shortSide = (1024 * minOf(w,h) / maxOf(w,h)).coerceAtLeast(64)
        return if (orientation == CanvasOrientation.Landscape) Size(1024,shortSide) else Size(shortSide,1024)
    }
    fun setDefaultSize(orientation: CanvasOrientation, size: Size) {
        require(size.width in 64..2048 && size.height in 64..2048)
        val key = orientation.preferenceKey
        if (!prefs.edit().putInt("$key-width",size.width).putInt("$key-height",size.height).commit())
            throw IOException("기본 크기를 저장할 수 없습니다.")
    }
    fun add(config: Configuration = context.resources.configuration) = create(defaultSize(config)) { groups + Group(it,listOf(it)) }
    fun addCanvas(config: Configuration = context.resources.configuration) = create(defaultSize(config)) { id ->
        groups.map { if (selected.id in it.canvases) it.copy(canvases = it.canvases + id) else it }
    }
    private fun create(size: Size, removed: List<String> = emptyList(), layout: (String) -> List<Group>): CanvasInfo {
        val page = CanvasInfo(size.width,size.height)
        commit(layout(page.id),page,page,removed)
        editor = open(page,PageImage(page.id,0,page.width,page.height,emptyList()))
        return page
    }
    fun resize(width: Int,height: Int, old: CanvasInfo = selected): CanvasInfo {
        val before = if (old === selected) document.frozen() else store.loadPage(old)
        val next = before.resized(width,height)
        store.save(old,before,next)
        val page = CanvasInfo(width,height,old.id,next.revision,old.textured)
        pages[pages.indexOf(old)] = page
        if (selected === old) {
            activate(page)
            editor = open(page,next)
        }
        return page
    }
    fun deletePage(target: CanvasInfo = selected, config: Configuration = context.resources.configuration): CanvasInfo {
        val old = group(target.id)
        val remaining = groups - old
        if (remaining.isEmpty()) create(defaultSize(config),old.canvases) { listOf(Group(it,listOf(it))) }
        else {
            val neighbor = remaining[groups.indexOf(old).coerceAtMost(remaining.lastIndex)]
            commit(remaining,if (selected.id in old.canvases) pages.first { it.id == neighbor.canvases.first() } else selected,removed = old.canvases)
        }
        return selected
    }
    fun deleteCanvas(target: CanvasInfo = selected): CanvasInfo {
        val old = group(target.id)
        require(old.canvases.size > 1) { "노트에는 페이지가 한 장 이상 필요합니다." }
        val id = target.id
        val ids = old.canvases - id
        val next = ids[old.canvases.indexOf(id).coerceAtMost(ids.lastIndex)]
        commit(groups.map { if (it == old) old.copy(canvases = ids) else it },if (selected === target) pages.first { it.id == next } else selected,removed = listOf(id))
        return selected
    }
}
