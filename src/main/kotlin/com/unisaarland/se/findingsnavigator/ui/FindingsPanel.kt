package com.unisaarland.se.findingsnavigator.ui

import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.unisaarland.se.findingsnavigator.model.Finding
import com.unisaarland.se.findingsnavigator.service.FindingsService
import com.unisaarland.se.findingsnavigator.ui.navigation.Navigator
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.nio.file.Paths
import java.io.File
import com.intellij.ui.OnePixelSplitter

class FindingsPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    val component = JPanel(BorderLayout())

    private val service = project.getService(FindingsService::class.java)

    private val rootNode = DefaultMutableTreeNode("Findings")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val detailsPanel = JPanel(BorderLayout())

    // filter UI: replace lists with checkbox panels
    private val categoryPanel = JPanel()
    private val tagPanel = JPanel()
    private val categoryScroll = JBScrollPane(categoryPanel)
    private val tagScroll = JBScrollPane(tagPanel)

    // Keep maps of checkboxes for quick state access
    private val categoryCheckboxes = linkedMapOf<String, JCheckBox>()
    private val tagCheckboxes = linkedMapOf<String, JCheckBox>()

    // persist No-tags selection across rebuilds
    private var noTagsSelected: Boolean = false

    // Small node wrapper used to show a category with its current filtered count
    private data class CategoryNode(val name: String, val count: Int) {
        override fun toString(): String = name
    }

     // PillCheckbox: a rounded colored checkbox used in filter panels
     private inner class PillCheckbox(text: String, val pillColor: Color) : JCheckBox(text) {
         init {
             // visual tweaks
             isOpaque = false
             foreground = getContrastingForeground(pillColor)
             horizontalTextPosition = SwingConstants.LEFT
             border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
             model.isArmed = false
             isFocusPainted = false
             // ensure hit area
             isContentAreaFilled = false
         }

         override fun paintComponent(g: java.awt.Graphics) {
             val g2 = g.create() as java.awt.Graphics2D
             try {
                 g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                 val w = width
                 val h = height
                 val arc = h / 2
                 // background based on selection
                 if (isSelected) {
                     g2.color = pillColor.darker()
                 } else {
                     g2.color = pillColor
                 }
                 g2.fillRoundRect(0, 0, w, h, arc, arc)
             } finally {
                 g2.dispose()
             }
             super.paintComponent(g)
         }
     }

     init {
         categoryPanel.layout = BoxLayout(categoryPanel, BoxLayout.Y_AXIS)
         tagPanel.layout = BoxLayout(tagPanel, BoxLayout.Y_AXIS)

         val loadButton = JButton("Load YAML")
        // autosave toggle
        val autoSaveCb = JCheckBox("Autosave")
        autoSaveCb.isSelected = service.isAutoSaveEnabled()
        autoSaveCb.addActionListener {
            service.setAutoSave(autoSaveCb.isSelected)
        }

        // Top toolbar (keeps load/autosave controls above the split area)
        val saveNowBtn = JButton("Save Now")
        saveNowBtn.toolTipText = "Force a synchronous save of pending changes to the YAML file"
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(loadButton)
        toolbar.add(autoSaveCb)
        toolbar.add(saveNowBtn)
        component.add(toolbar, BorderLayout.NORTH)

        // Build the filters area: two columns (categories / tags) with adjustable width
        categoryScroll.preferredSize = java.awt.Dimension(220, 200)
        tagScroll.preferredSize = java.awt.Dimension(220, 200)

        val catHeader = JPanel(FlowLayout(FlowLayout.LEFT))
        catHeader.add(JLabel("Categories:"))
        val tagHeader = JPanel(FlowLayout(FlowLayout.LEFT))
        tagHeader.add(JLabel("Tags:"))

        val categoryColumn = JPanel(BorderLayout())
        categoryColumn.add(catHeader, BorderLayout.NORTH)
        categoryColumn.add(categoryScroll, BorderLayout.CENTER)

        val tagColumn = JPanel(BorderLayout())
        val clearFilters = JButton("Clear Filters")
        clearFilters.addActionListener {
            for (cb in categoryCheckboxes.values) cb.isSelected = true
            for (cb in tagCheckboxes.values) cb.isSelected = true
            rebuildTree()
            saveCurrentFilters()
        }
        // Put clearFilters into tag header (so both headers show)
        tagHeader.add(clearFilters)
        tagColumn.add(tagHeader, BorderLayout.NORTH)
        tagColumn.add(tagScroll, BorderLayout.CENTER)

        // Horizontal splitter between categories and tags (adjust individual widths)
        val filtersSplitter = OnePixelSplitter(true, 0.5f)
        filtersSplitter.firstComponent = categoryColumn
        filtersSplitter.secondComponent = tagColumn

        val filtersContainer = JPanel(BorderLayout())
        filtersContainer.add(filtersSplitter, BorderLayout.CENTER)

        // Left vertical splitter: filters (top) and tree (bottom) so filter height is adjustable
        val leftSplitter = OnePixelSplitter(false, 0.28f)
        leftSplitter.firstComponent = filtersContainer
        val treeScroll = JBScrollPane(tree)
        treeScroll.preferredSize = java.awt.Dimension(300, 400)
        leftSplitter.secondComponent = treeScroll

        // Root splitter: left (filters+tree) and right (details) adjustable width
        val rootSplitter = OnePixelSplitter(true, 0.32f)
        rootSplitter.firstComponent = leftSplitter
        rootSplitter.secondComponent = detailsPanel

        component.add(rootSplitter, BorderLayout.CENTER)

         loadButton.addActionListener {
             val fd = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
             // show local files only
             fd.setHideIgnored(false)
             val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(fd, project, null)
             if (chosen != null) {
                 service.loadFromFile(Paths.get(chosen.path))
             }
         }
        // Auto-load last opened YAML (if exists) and restore saved filters
        val last = service.getLastLoadedFile()
        if (last != null) {
            val lf = java.io.File(last)
            if (lf.exists()) {
                try {
                    service.loadFromFile(Paths.get(last))
                } catch (_: Exception) {
                    // ignore load failures at startup
                }
            }
        }

         tree.addTreeSelectionListener {
             val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
             val user = node?.userObject
             if (user is Finding) {
                 showDetails(user)
             }
         }

         tree.cellRenderer = object : javax.swing.tree.DefaultTreeCellRenderer() {
             override fun getTreeCellRendererComponent(
                 tree: javax.swing.JTree?, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
             ): java.awt.Component {
                 val comp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus) as JLabel
                 val node = value as? DefaultMutableTreeNode
                 val user = node?.userObject
                 if (user is Finding) {
                    val f = user as Finding
                    val filename = f.location.filename.substringAfterLast('/')
                    val line = f.location.line ?: 0
                    val col = f.location.column ?: 0
                    comp.text = "${filename}:${line}:${col}"
                 } else if (user is CategoryNode) {
                     val cnode = user as CategoryNode
                     val c = service.colorForCategory(cnode.name)
                     comp.text = "${cnode.name} (${cnode.count})"
                     comp.icon = object : javax.swing.Icon {
                         override fun getIconWidth() = 10
                         override fun getIconHeight() = 10
                         override fun paintIcon(cmp: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {
                             if (g == null) return
                             g.color = c
                             g.fillRect(x, y, 10, 10)
                         }
                     }
                 }
                 return comp
             }
         }

         tree.addMouseListener(object : java.awt.event.MouseAdapter() {
             override fun mouseClicked(e: java.awt.event.MouseEvent) {
                 if (e.clickCount == 2) {
                     val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                     val user = node?.userObject
                     if (user is Finding) {
                         Navigator.open(project, user.location.filename, user.location.line, user.location.column)
                     }
                 }
             }
         })

        // Refresh editor markers when a file is opened so newly opened files get annotations
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                // compute currently visible (filtered) findings and update markers
                val selectedCats = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()
                val selectedTags = tagCheckboxes.filterValues { it.isSelected }.keys.toSet()
                val includeNoTags = (tagPanel.components.firstOrNull() as? JCheckBox)?.isSelected ?: false
                val filtered = service.findings.filter { f ->
                    val catOk = selectedCats.isEmpty() || selectedCats.contains(f.category)
                    val tagOk = if (selectedTags.isEmpty()) true
                    else f.tags.any { it in selectedTags } || (includeNoTags && f.tags.isEmpty())
                    catOk && tagOk
                }
                service.updateEditorMarkers(filtered)
            }
        })

        service.addListener(object : com.unisaarland.se.findingsnavigator.service.FindingsListener {
            override fun findingsChanged() {
                rebuildFilterPanels()
                rebuildTree()
            }
        })

        // initial empty details
        detailsPanel.add(JButton("Select a finding to see details"), BorderLayout.CENTER)

        // initial populate
        rebuildFilterPanels()
    }

    // Move saveCurrentFilters to a private class-level helper so all methods can call it
    private fun saveCurrentFilters() {
        val selectedCats = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()
        val selectedTags = tagCheckboxes.filterValues { it.isSelected }.keys.toSet()
        val includeNoTagsNow = (tagPanel.components.firstOrNull() as? JCheckBox)?.isSelected ?: false
        service.saveSelectedFilters(selectedCats, selectedTags, includeNoTagsNow)
    }

    private fun rebuildFilterPanels() {
         // preserve previous selection state
         val prevCatSelected = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()
         val prevTagSelected = tagCheckboxes.filterValues { it.isSelected }.keys.toSet()
         val wasInitialCats = categoryCheckboxes.isEmpty()
         val wasInitialTags = categoryCheckboxes.isEmpty()
         // remember whether the existing 'No tags' checkbox was selected
         val prevNoTags = (tagPanel.components.firstOrNull() as? JCheckBox)?.isSelected ?: false

         categoryCheckboxes.clear()
         tagCheckboxes.clear()
         categoryPanel.removeAll()
         tagPanel.removeAll()

         val cats = service.categories().sorted()
         val tags = service.allTags().sorted()

         val savedCats = service.getSavedSelectedCategories()
         val savedTags = service.getSavedSelectedTags()
         val hasSaved = savedCats.isNotEmpty() || savedTags.isNotEmpty() || service.getSavedSelectedNoTags()

        for (c in cats) {
             val cb = PillCheckbox(c, service.colorForCategory(c))
             cb.icon = null
             cb.selectedIcon = null
            cb.isSelected = if (wasInitialCats) true else prevCatSelected.contains(c)
            cb.addActionListener { rebuildTree() }
            cb.isSelected = when {
                wasInitialCats && !hasSaved -> true
                hasSaved -> savedCats.contains(c)
                else -> prevCatSelected.contains(c)
            }
            cb.addActionListener { rebuildTree(); saveCurrentFilters() }
             categoryCheckboxes[c] = cb
             categoryPanel.add(cb)
         }

         // add a checkbox to show entries with no tags
         val noTagsCb = JCheckBox("No tags")
         noTagsCb.isSelected = if (hasSaved) service.getSavedSelectedNoTags() else (prevNoTags || noTagsSelected)
         noTagsCb.addActionListener {
             noTagsSelected = noTagsCb.isSelected
             rebuildTree()
             saveCurrentFilters()
         }
         // place 'No tags' at top of tag panel
         tagPanel.add(noTagsCb)
         for (t in tags) {
             val cb = PillCheckbox(t, service.colorForTag(t))
             cb.icon = null
             cb.selectedIcon = null
             // select if previously selected or if newly added and scheduled for auto-select
             val wasSelected = if (wasInitialTags) true else prevTagSelected.contains(t)
             cb.isSelected = if (hasSaved) savedTags.contains(t) else wasSelected
             cb.addActionListener { rebuildTree(); saveCurrentFilters() }
             tagCheckboxes[t] = cb
             tagPanel.add(cb)
         }

         categoryPanel.revalidate()
         categoryPanel.repaint()
         tagPanel.revalidate()
         tagPanel.repaint()
     }

    private fun rebuildTree() {
        // capture UI state to avoid layout reset
        val selectedFindingId = captureSelectedFindingId()
        val expandedCategories = captureExpandedCategories()

        rootNode.removeAllChildren()
        val selectedCats = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()
        val selectedTags = tagCheckboxes.filterValues { it.isSelected }.keys.toSet()
        // prefer persisted state if present
        val includeNoTags = noTagsSelected || ((tagPanel.components.firstOrNull() as? JCheckBox)?.isSelected ?: false)

        val filtered = service.findings.filter { f ->
             val catOk = selectedCats.isEmpty() || selectedCats.contains(f.category)
            val tagOk = f.tags.any { it in selectedTags } || (includeNoTags && f.tags.isEmpty())
             catOk && tagOk
         }

        val byCategory = filtered.groupBy { it.category }
        for ((cat, list) in byCategory) {
            val catNode = DefaultMutableTreeNode(CategoryNode(cat, list.size))
            for (f in list) {
                val n = DefaultMutableTreeNode(f)
                catNode.add(n)
            }
            rootNode.add(catNode)
        }

        // update root label with total
        rootNode.userObject = "Findings (${filtered.size})"

        treeModel.reload()

        // restore expansion and selection
        restoreExpandedCategories(expandedCategories)
        restoreSelectionByFindingId(selectedFindingId)

        // ensure root is visible/expanded if nothing else
        if (tree.rowCount > 0) tree.expandRow(0)

        // Update editor markers for all findings that are currently visible (i.e., not filtered out)
        service.updateEditorMarkers(filtered)
    }

    private fun captureSelectedFindingId(): String? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val user = node.userObject
        return if (user is Finding) user.id else null
    }

    private fun captureExpandedCategories(): Set<String> {
        val expanded = mutableSetOf<String>()
        val rowCount = tree.rowCount
        for (r in 0 until rowCount) {
            val path = tree.getPathForRow(r)
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val user = node.userObject
            if (user is CategoryNode && tree.isExpanded(r)) {
                expanded.add(user.name)
            }
        }
        return expanded
    }

    private fun restoreExpandedCategories(categories: Set<String>) {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val user = child.userObject
            if (user is CategoryNode && user.name in categories) {
                val path = javax.swing.tree.TreePath(child.path)
                tree.expandPath(path)
            }
        }
    }

    private fun restoreSelectionByFindingId(id: String?) {
        if (id == null) return
        val node = findNodeByFindingId(rootNode, id)
        if (node != null) {
            val path = javax.swing.tree.TreePath(node.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun findNodeByFindingId(node: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        val user = node.userObject
        if (user is Finding && user.id == id) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findNodeByFindingId(child, id)
            if (found != null) return found
        }
        return null
    }

    private fun rebuildFilters() {
        // preserve selection
        val prevCats = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()
        val prevTags = categoryCheckboxes.filterValues { it.isSelected }.keys.toSet()

        val cats = service.categories().sorted()
        val tags = service.allTags().sorted()

        // keep panels in sync by rebuilding them
        rebuildFilterPanels()
    }

    private fun showDetails(f: Finding) {
        detailsPanel.removeAll()
        val p = JPanel(BorderLayout())

        // Local small rounded close button
        class CloseButton(val onClose: () -> Unit, var bg: Color) : JComponent() {
            private var hovered = false
            init {
                preferredSize = java.awt.Dimension(18, 18)
                isOpaque = false
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) { hovered = true; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent?) { hovered = false; repaint() }
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) { onClose() }
                })
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    val w = width
                    val h = height
                    val diameter = Math.min(w, h) - 4
                    val x = (w - diameter) / 2
                    val y = (h - diameter) / 2
                    if (hovered) {
                        g2.color = bg.darker()
                        g2.fillOval(x, y, diameter, diameter)
                    }
                    // draw 'x'
                    g2.color = if (hovered) java.awt.Color.WHITE else getContrastingForeground(bg)
                    g2.stroke = java.awt.BasicStroke(2f)
                    val pad = 4
                    g2.drawLine(x + pad, y + pad, x + diameter - pad, y + diameter - pad)
                    g2.drawLine(x + diameter - pad, y + pad, x + pad, y + diameter - pad)
                } finally {
                    g2.dispose()
                }
            }
        }

        // Tag pill component: rounded background with label and close button
        fun makeTagPill(tag: String): JComponent {
            val color = service.colorForTag(tag)
            val pill = object : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {
                init {
                    isOpaque = false
                }
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    try {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                        val r = 8
                        g2.color = color
                        g2.fillRoundRect(0, 0, width, height, r, r)
                    } finally {
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }

            val lbl = JLabel(tag)
            lbl.foreground = getContrastingForeground(service.colorForTag(tag))
            lbl.border = javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 2)
            val close = CloseButton(onClose = {
                project.getService(FindingsService::class.java).removeTag(f.id, tag)
            }, bg = color)
            // make sure button background blends
            close.toolTipText = "Remove tag '$tag'"
            pill.add(lbl)
            pill.add(close)
            pill.border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
            pill.isOpaque = false
            return pill
        }

        // Top area: show category badge and extras header
        val topInfo = JPanel(FlowLayout(FlowLayout.LEFT))
        val catColor = service.colorForCategory(f.category)
        val catBadge = object : JComponent() {
            init { preferredSize = java.awt.Dimension(20, 20) }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.color = catColor
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                    g2.color = getContrastingForeground(catColor)
                    g2.font = g2.font.deriveFont(10f)
                    val ab = service.abbreviationForCategory(f.category).take(2)
                    val fm = g2.fontMetrics
                    val tx = (width - fm.stringWidth(ab)) / 2
                    val ty = (height + fm.ascent - fm.descent) / 2
                    g2.drawString(ab, tx, ty)
                } finally { g2.dispose() }
            }
        }
        topInfo.add(catBadge)
        val catLabel = JLabel("  ${f.category}")
        topInfo.add(catLabel)
        p.add(topInfo, BorderLayout.NORTH)

        // center: tags and extras
        val center = JPanel(BorderLayout())
        val tagsArea = JPanel(FlowLayout(FlowLayout.LEFT))
        tagsArea.isOpaque = false
        for (t in f.tags.sorted()) {
            tagsArea.add(makeTagPill(t))
        }
        center.add(tagsArea, BorderLayout.NORTH)

        if (f.extras.isNotEmpty()) {
            val extrasPanel = JPanel()
            extrasPanel.layout = BoxLayout(extrasPanel, BoxLayout.Y_AXIS)
            val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
            for ((k, v) in f.extras) {
                val l = JLabel("$k: $v")
                l.font = font
                l.border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
                extrasPanel.add(l)
            }
            val extrasScroll = JBScrollPane(extrasPanel)
            extrasScroll.preferredSize = java.awt.Dimension(300, 100)
            center.add(extrasScroll, BorderLayout.CENTER)
        }

        // put tag editor closer to extras (south)
        p.add(center, BorderLayout.CENTER)

        // add tag input and button (prominent)
        val tagEditor = JPanel(FlowLayout(FlowLayout.LEFT))
        val tagField = JBTextField()
        tagField.columns = 18
        val addButton = JButton("Add")
        addButton.addActionListener {
            val t = tagField.text.trim()
            if (t.isNotEmpty()) {
                // Apply tag to all selected findings in the tree (including current one)
                val selectedFindings = mutableListOf<Finding>()
                val paths = tree.selectionPaths
                if (paths != null && paths.isNotEmpty()) {
                    for (p in paths) {
                        val node = p.lastPathComponent as? DefaultMutableTreeNode
                        val user = node?.userObject
                        if (user is Finding) selectedFindings.add(user)
                    }
                }
                if (selectedFindings.isEmpty()) selectedFindings.add(f)
                val svc = project.getService(FindingsService::class.java)
                val ids = selectedFindings.map { it.id }.distinct()
                svc.addTagToFindings(ids, t)
                tagField.text = ""
                // rebuild UI
                // show details of first selected (if any)
                showDetails(selectedFindings.first())
                rebuildFilterPanels()
                rebuildTree()
            }
        }
        tagField.addActionListener { addButton.doClick() }
        tagEditor.add(tagField)
        tagEditor.add(addButton)

        // quick-add buttons (small)
        val quickPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val topTags = service.allTags().sorted().filter { it !in f.tags }
        for ((i, t) in topTags.withIndex()) {
            val btn = JButton(t)
            btn.background = service.colorForTag(t)
            btn.foreground = getContrastingForeground(btn.background)
            btn.isOpaque = true
            btn.border = javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6)
            btn.addActionListener {
                val selectedFindings = mutableListOf<Finding>()
                val paths = tree.selectionPaths
                if (paths != null && paths.isNotEmpty()) {
                    for (p in paths) {
                        val node = p.lastPathComponent as? DefaultMutableTreeNode
                        val user = node?.userObject
                        if (user is Finding) selectedFindings.add(user)
                    }
                }
                if (selectedFindings.isEmpty()) selectedFindings.add(f)
                val svc = project.getService(FindingsService::class.java)
                val ids = selectedFindings.map { it.id }.distinct()
                svc.addTagToFindings(ids, t)
                showDetails(selectedFindings.first())
                rebuildFilterPanels()
                rebuildTree()
            }
            quickPanel.add(btn)
            // keyboard binding
            val ks = javax.swing.KeyStroke.getKeyStroke("ctrl ${i + 1}")
            p.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "quickAdd$i")
            p.actionMap.put("quickAdd$i", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) { btn.doClick() }
            })
        }

        val south = JPanel(BorderLayout())
        south.add(tagEditor, BorderLayout.NORTH)
        south.add(quickPanel, BorderLayout.SOUTH)
        p.add(south, BorderLayout.SOUTH)

        detailsPanel.add(p, BorderLayout.CENTER)
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun getContrastingForeground(bg: Color): Color {
        val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue)
        return if (luminance < 150) Color.WHITE else Color.BLACK
    }
}
