package com.unisaarland.se.findingsnavigator.service

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.unisaarland.se.findingsnavigator.model.Finding
import java.nio.file.Path
import com.unisaarland.se.findingsnavigator.util.FindingsYamlParser
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon
import java.awt.Graphics
import java.awt.Component
import java.awt.Color
import kotlin.math.abs
import com.intellij.openapi.application.ApplicationManager
import java.io.File
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.LoaderOptions

interface FindingsListener : java.util.EventListener {
    fun findingsChanged()
}

@State(name = "FindingsServiceState", storages = [Storage("findingsNavigator.xml")])
@Service(Level.PROJECT)
class FindingsService(private val project: Project) : PersistentStateComponent<FindingsService.State> {
    private val dispatcher: com.intellij.util.EventDispatcher<FindingsListener> =
        com.intellij.util.EventDispatcher.create(FindingsListener::class.java)
    private val LOG: Logger = Logger.getInstance(FindingsService::class.java)

    private val _findings = mutableListOf<Finding>()
    val findings: List<Finding>
        get() = _findings

    // Index findings by candidate file paths (absolute/canonical) for quick lookup when updating editor markers
    private val findingsByFile: MutableMap<String, MutableList<Finding>> = mutableMapOf()

    // Caches to avoid repeated computations/allocation when rendering markers
    private val categoryColorCache: MutableMap<String, Color> = mutableMapOf()
    private val categoryAbbrCache: MutableMap<String, String> = mutableMapOf()
    private val categoryIconCache: MutableMap<String, javax.swing.Icon> = mutableMapOf()

    private var state: State = State()

    data class State(
        var tags: MutableMap<String, MutableList<String>> = mutableMapOf(),
        var lastLoadedFile: String? = null,
        var autoSave: Boolean = true,
        // persisted UI filter selections
        var selectedCategories: MutableList<String> = mutableListOf(),
        var selectedTags: MutableList<String> = mutableListOf(),
        var selectedNoTags: Boolean = false
    )

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // Persist and retrieve saved filter selections
    fun saveSelectedFilters(categories: Set<String>, tags: Set<String>, noTags: Boolean) {
        state.selectedCategories = categories.toMutableList()
        state.selectedTags = tags.toMutableList()
        state.selectedNoTags = noTags
    }

    fun getSavedSelectedCategories(): Set<String> = state.selectedCategories.toSet()
    fun getSavedSelectedTags(): Set<String> = state.selectedTags.toSet()
    fun getSavedSelectedNoTags(): Boolean = state.selectedNoTags

    // YAML mapper to persist tags back to the source YAML
    private val yamlMapper: com.fasterxml.jackson.dataformat.yaml.YAMLMapper


    init {
        val loaderOptions = LoaderOptions()
        loaderOptions.codePointLimit = 100 * 1024 * 1024 // 100 MB
        val yamlFactory = YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .build()

        yamlMapper = com.fasterxml.jackson.dataformat.yaml.YAMLMapper(yamlFactory)
    }

    fun isAutoSaveEnabled(): Boolean = state.autoSave
    fun setAutoSave(enabled: Boolean) {
        state.autoSave = enabled
    }

    fun addListener(l: FindingsListener) {
        dispatcher.addListener(l)
    }

    fun removeListener(l: FindingsListener) {
        dispatcher.removeListener(l)
    }

    fun loadFromFile(path: Path) {
        val file = path.toFile()
        val parsed = FindingsYamlParser.parse(file)
        _findings.clear()
        _findings.addAll(parsed)

        // build index for fast lookup per-file
        buildIndex()

        // apply persisted tags if present
        val tagsMap = state.tags
        for (f in _findings) {
            val persisted = tagsMap[f.id]
            if (persisted != null) {
                f.tags.clear()
                f.tags.addAll(persisted)
            }
        }

        state.lastLoadedFile = file.absolutePath
        dispatcher.multicaster.findingsChanged()
    }

    // Build an index from candidate file paths to findings to avoid repeated FS lookups
    private fun buildIndex() {
        findingsByFile.clear()
        val base = project.basePath
        for (f in _findings) {
            val filename = f.location.filename
            val candidates = mutableSetOf<String>()
            try {
                if (File(filename).isAbsolute) candidates.add(File(filename).canonicalPath)
            } catch (_: Exception) {
            }
            if (base != null) {
                try {
                    val p = File(base, filename)
                    candidates.add(p.path)
                    try {
                        candidates.add(p.canonicalPath)
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
            candidates.add(filename)
            if (base != null) candidates.add(base + "/" + filename)

            for (c in candidates) {
                val list = findingsByFile.getOrPut(c) { mutableListOf() }
                list.add(f)
            }
        }

        // build category caches
        categoryColorCache.clear()
        categoryAbbrCache.clear()
        categoryIconCache.clear()
        for (cat in _findings.map { it.category }.distinct()) {
            val color = colorForCategory(cat)
            categoryColorCache[cat] = color
            categoryAbbrCache[cat] = abbreviationForCategory(cat)
            // create a simple reusable icon; icon width based on abbreviation length
            val ab = categoryAbbrCache[cat] ?: "?"
            val iconWidth = 14 + (maxOf(0, ab.length - 2) * 8)
            val icon = object : javax.swing.Icon {
                private val height = 18
                private val padding = 6
                override fun getIconWidth(): Int = iconWidth
                override fun getIconHeight(): Int = height
                override fun paintIcon(cmp: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {
                    if (g == null) return
                    val g2 = g.create() as java.awt.Graphics2D
                    try {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                        val text = ab.take(3)
                        g2.font = g2.font.deriveFont(11f)
                        val fm = g2.fontMetrics
                        val textWidth = fm.stringWidth(text)
                        val rectW = maxOf(iconWidth, textWidth + padding * 2)
                        val rectH = height - 4
                        val rx = x
                        val ry = y + 2
                        val arc = 8
                        g2.color = color
                        g2.fillRoundRect(rx, ry, rectW, rectH, arc, arc)
                        val textColor = if ((0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 150) Color.white else Color.black
                        g2.color = textColor
                        val tx = rx + (rectW - textWidth) / 2
                        val ty = ry + ((rectH + fm.ascent - fm.descent) / 2)
                        g2.drawString(text, tx, ty)
                    } finally {
                        g2.dispose()
                    }
                }
            }
            categoryIconCache[cat] = icon
        }
    }

    fun addTag(findingId: String, tag: String) {
        val f = _findings.find { it.id == findingId } ?: return
        if (f.tags.add(tag)) {
            // persist in service state
            val list = state.tags.getOrPut(findingId) { mutableListOf() }
            if (!list.contains(tag)) list.add(tag)
            dispatcher.multicaster.findingsChanged()
            // persist back to YAML file on disk if autosave enabled
            if (state.autoSave) {
                // persist in background to avoid blocking the EDT
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        persistTagsToYaml()
                    } catch (ex: Exception) {
                        LOG.warn("Failed to persist tags to YAML", ex)
                        ApplicationManager.getApplication().invokeLater {
                            notifyError("Failed to save tags to YAML: ${ex.message}")
                        }
                    }
                }
            }
        }
    }

    fun removeTag(findingId: String, tag: String) {
        val f = _findings.find { it.id == findingId } ?: return
        if (f.tags.remove(tag)) {
            val list = state.tags[findingId]
            list?.remove(tag)
            if (list != null && list.isEmpty()) state.tags.remove(findingId)
            dispatcher.multicaster.findingsChanged()
            if (state.autoSave) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        persistTagsToYaml()
                    } catch (ex: Exception) {
                        LOG.warn("Failed to persist tags to YAML", ex)
                        ApplicationManager.getApplication().invokeLater {
                            notifyError("Failed to save tags to YAML: ${ex.message}")
                        }
                    }
                }
            }
        }
    }

    private fun notifyError(msg: String) {
        // Ensure notifications run on EDT
        ApplicationManager.getApplication().invokeLater {
            try {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("FindingsNavigator")
                val notification = group.createNotification(msg, NotificationType.ERROR)
                notification.notify(project)
            } catch (t: Throwable) {
                LOG.warn("Notification failed", t)
            }
        }
    }

    private fun notifyInfo(msg: String) {
        // Ensure notifications run on EDT
        ApplicationManager.getApplication().invokeLater {
            try {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("FindingsNavigator")
                val notification = group.createNotification(msg, NotificationType.INFORMATION)
                notification.notify(project)
            } catch (t: Throwable) {
                LOG.warn("Notification failed", t)
            }
        }
    }

    // Persist current tags for all findings back into the last loaded YAML file.
    // Matches entries by category + Location (Filename, Lineno, Colno) and writes/updates a `tags` array.
    private fun persistTagsToYaml() {
        val path = state.lastLoadedFile ?: throw java.io.IOException("No YAML file loaded to save to")
        val file = File(path)
        if (!file.exists()) throw java.io.IOException("YAML file does not exist: $path")

        val rootNode = yamlMapper.readTree(file) as? com.fasterxml.jackson.databind.node.ObjectNode
            ?: throw java.io.IOException("Unexpected YAML structure")

        // Take a snapshot of findings and their tags to avoid concurrent modification
        val snapshot = synchronized(_findings) { _findings.map { f -> f.copy(tags = f.tags.toMutableSet()) } }

        for (f in snapshot) {
            val catNode = rootNode.get(f.category) ?: continue
            if (!catNode.isArray) continue
            val arr = catNode as com.fasterxml.jackson.databind.node.ArrayNode
            for (i in 0 until arr.size()) {
                val item = arr.get(i)
                if (item == null || !item.isObject) continue
                val locationNode = item.get("Location") ?: item.get("location")
                if (locationNode == null) continue
                val filename = locationNode.get("Filename")?.asText()
                    ?: locationNode.get("filename")?.asText()
                    ?: item.get("file")?.asText()
                    ?: continue
                val linenoNode = locationNode.get("Lineno") ?: locationNode.get("lineno")
                val colnoNode = locationNode.get("Colno") ?: locationNode.get("colno")
                val lineno = linenoNode?.asInt()
                val colno = colnoNode?.asInt()

                if (filename == f.location.filename && ((lineno == null && f.location.line == null) || (lineno != null && f.location.line == lineno)) && ((colno == null && f.location.column == null) || (colno != null && f.location.column == colno))) {
                    // Match found - update tags array
                    val objItem = item as com.fasterxml.jackson.databind.node.ObjectNode
                    val tagsArray = yamlMapper.createArrayNode()
                    for (t in f.tags) tagsArray.add(t)
                    objItem.set<com.fasterxml.jackson.databind.JsonNode>("tags", tagsArray)
                    break
                }
            }
        }

        // Write back and refresh VFS so IDE notices changes
        try {
            yamlMapper.writeValue(file, rootNode)
            // refresh VFS; this may be called from background thread but fine to call here
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            // Notify on EDT
            ApplicationManager.getApplication().invokeLater {
                notifyInfo("Findings YAML saved")
            }
        } catch (ex: Exception) {
            // notify on EDT
            ApplicationManager.getApplication().invokeLater {
                notifyError("Writing YAML failed: ${ex.message}")
            }
            throw java.io.IOException("Writing YAML failed: ${ex.message}", ex)
        }
    }

    fun allTags(): Set<String> = _findings.flatMap { it.tags }.toSet()

    fun categories(): Set<String> = _findings.map { it.category }.toSet()

    // Deterministic color generation based on name hash - persistent across runs
    fun colorForTag(tag: String): Color = colorFromName(tag)
    fun colorForCategory(category: String): Color = colorFromName(category)

    private fun colorFromName(name: String): Color {
        val h = (abs(name.hashCode()) % 360) / 360f
        val s = 0.58f
        val b = 0.88f
        return Color.getHSBColor(h, s, b)
    }

    fun abbreviationForCategory(category: String): String {
        // Extract capital letters from CamelCase; fallback to initials
        try {
            val matches = Regex("[A-Z][a-z]*").findAll(category).map { it.value }.toList()
            if (matches.isNotEmpty()) return matches.map { it.first().uppercaseChar() }.joinToString("")
        } catch (_: Exception) {
        }
        // fallback: take first two non-space letters
        val letters = category.filter { it.isLetter() }
        return when {
            letters.length >= 3 -> letters.substring(0, 3).uppercase()
            letters.isNotEmpty() -> letters.substring(0, 2).uppercase()
            else -> "?"
        }
    }

    // --- Editor marker management ---
    private val activeHighlighters = mutableListOf<RangeHighlighter>()

    fun clearEditorMarkers() {
        // Remove only highlighters we previously created to avoid deleting unrelated ones
        val editors = getAllTextEditors()
        for (editor in editors) {
            val markup = editor.markupModel
            val toRemove = markup.allHighlighters.filter { it in activeHighlighters }
            for (r in toRemove) {
                try {
                    markup.removeHighlighter(r)
                } catch (_: Exception) {
                }
            }
        }
        activeHighlighters.clear()
    }

    private fun getAllTextEditors(): List<Editor> {
        val files = FileEditorManager.getInstance(project).openFiles
        val editors = mutableListOf<Editor>()
        for (vf in files) {
            val fileEditors = FileEditorManager.getInstance(project).getAllEditors(vf)
            for (fe in fileEditors) {
                val textEditor = fe as? com.intellij.openapi.fileEditor.TextEditor
                if (textEditor != null) editors.add(textEditor.editor)
            }
        }
        return editors
    }

    fun updateEditorMarkers(showFindings: Collection<Finding>) {
        // Run on EDT to be safe when manipulating editors/markup
        ApplicationManager.getApplication().invokeLater {
            // clear previous highlighters on EDT to avoid race conditions
            clearEditorMarkers()
            val created = mutableSetOf<Pair<Editor, Int>>()
            // fast lookup of visible finding ids
            val showIds = showFindings.map { it.id }.toSet()

            val fileEditorManager = FileEditorManager.getInstance(project)
            val openFiles = fileEditorManager.openFiles
            val base = project.basePath

            for (vf in openFiles) {
                val vfPath = try {
                    File(vf.path).canonicalPath
                } catch (_: Exception) {
                    vf.path
                }
                // candidate keys to lookup in index
                val keys = mutableListOf<String>()
                keys.add(vf.path)
                if (vfPath != null) keys.add(vfPath)
                if (base != null) keys.add(base + "/" + vf.path.substringAfter(base))

                // collect candidate findings
                val candidates = mutableListOf<Finding>()
                for (k in keys) {
                    val list = findingsByFile[k]
                    if (list != null) candidates.addAll(list)
                }

                if (candidates.isEmpty()) continue

                val fileEditors = fileEditorManager.getAllEditors(vf)
                for (fe in fileEditors) {
                    val textEditor = fe as? com.intellij.openapi.fileEditor.TextEditor
                    val editor = textEditor?.editor ?: continue
                    val markup = editor.markupModel

                    for (f in candidates) {
                        if (f.id !in showIds) continue
                        val line = f.location.line ?: continue
                        try {
                            val lineIndex = if (line > 0) line - 1 else 0
                            val key = editor to lineIndex
                            if (created.contains(key)) continue
                            val rh = markup.addLineHighlighter(lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                            val icon = categoryIconCache[f.category]
                                ?: run {
                                    val col = colorForCategory(f.category)
                                    val ab = abbreviationForCategory(f.category)
                                    val w = maxOf(22, 8 + ab.length * 10)
                                    val ic = object : javax.swing.Icon {
                                        private val h = 18
                                        override fun getIconWidth(): Int = w
                                        override fun getIconHeight(): Int = h
                                        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {
                                            if (g == null) return
                                            val g2 = g.create() as java.awt.Graphics2D
                                            try {
                                                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                                                val text = ab.take(3)
                                                g2.font = g2.font.deriveFont(11f)
                                                val fm = g2.fontMetrics
                                                val tw = fm.stringWidth(text)
                                                val rectW = maxOf(w, tw + 12)
                                                val rectH = h - 4
                                                val rx = x
                                                val ry = y + 2
                                                val arc = 8
                                                g2.color = col
                                                g2.fillRoundRect(rx, ry, rectW, rectH, arc, arc)
                                                val textColor = if ((0.299 * col.red + 0.587 * col.green + 0.114 * col.blue) < 150) Color.white else Color.black
                                                g2.color = textColor
                                                val tx = rx + (rectW - tw) / 2
                                                val ty = ry + ((rectH + fm.ascent - fm.descent) / 2)
                                                g2.drawString(text, tx, ty)
                                            } finally { g2.dispose() }
                                        }
                                    }
                                    categoryIconCache[f.category] = ic
                                    ic
                                }
                            rh.gutterIconRenderer = object : GutterIconRenderer() {
                                override fun getIcon(): javax.swing.Icon = icon
                                override fun equals(other: Any?): Boolean = other === this
                                override fun hashCode(): Int = System.identityHashCode(this)
                            }

                            activeHighlighters.add(rh)
                            created.add(key)
                        } catch (_: Exception) {
                            // ignore per-editor failures
                        }
                    }
                }
            }
        }
    }

    fun getLastLoadedFile(): String? = state.lastLoadedFile
}
