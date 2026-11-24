package com.maekom.codetracker.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.maekom.codetracker.analysis.RationaleAndCheckEngine
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.FileHistoryCommitEntry
import com.maekom.codetracker.model.FileHistoryResult
import com.maekom.codetracker.model.FileHistoryVersionGroup
import com.maekom.codetracker.model.RadarSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Project-scoped Single-Instance Modeless DialogWrapper displaying version/tag-grouped
 * file modification history with IntelliJ native loading spinners, on-demand commit rationale,
 * line-by-line Impacted Entrypoints, smart pointers, and zero-restart live KO/EN sync.
 */
class FileHistoryDialog private constructor(
    private val project: Project,
    private var repoRootPath: String,
    private var branch: String,
    private var startDate: LocalDate,
    private var endDate: LocalDate,
    private var targetFilePath: String,
    private var noMerges: Boolean
) : DialogWrapper(project, true) {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
        private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val activeDialogs = ConcurrentHashMap<Project, FileHistoryDialog>()

        /**
         * Single-Instance controller per Project:
         * - If a FileHistoryDialog is already open for [project], brings it to front and reloads it
         *   with the newly selected file's history immediately.
         * - Otherwise creates, caches, and shows a new modeless instance.
         */
        fun showOrUpdate(
            project: Project,
            repoRootPath: String,
            branch: String,
            startDate: LocalDate,
            endDate: LocalDate,
            targetFilePath: String,
            noMerges: Boolean
        ) {
            val existing = activeDialogs[project]
            if (existing != null && !existing.isDisposed && existing.window?.isShowing == true) {
                existing.reloadForTarget(
                    repoRootPath = repoRootPath,
                    branch = branch,
                    startDate = startDate,
                    endDate = endDate,
                    targetFilePath = targetFilePath,
                    noMerges = noMerges
                )
                existing.window?.toFront()
                existing.window?.requestFocus()
                return
            }

            val dialog = FileHistoryDialog(
                project = project,
                repoRootPath = repoRootPath,
                branch = branch,
                startDate = startDate,
                endDate = endDate,
                targetFilePath = targetFilePath,
                noMerges = noMerges
            )
            activeDialogs[project] = dialog
            dialog.show()
        }
    }

    private data class VersionGroupNodeData(val group: FileHistoryVersionGroup)
    private data class HistoryCommitNodeData(val entry: FileHistoryCommitEntry)
    private data class PointerHunkNodeData(val fileDetail: FileDiffDetail, val pointer: CodeHunkPointer)

    private val fileLabel = JBLabel(targetFilePath, AllIcons.FileTypes.Any_type, JBLabel.LEFT).apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val scopeLabel = JBLabel(
        CodeTrackerI18n.fileHistoryScope(branch, startDate.format(DATE_FMT), endDate.format(DATE_FMT))
    ).apply {
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont()
    }
    private val headerCountLabel = JBLabel(CodeTrackerI18n.fileHistoryLoadingCount()).apply {
        foreground = JBColor.GRAY
    }
    private val headerSpinner = AsyncProcessIcon("FileHistoryHeaderSpinner").apply {
        suspend()
        isVisible = false
    }

    private val leftHeaderLabel = JBLabel(CodeTrackerI18n.fileHistoryLeftTitle()).apply {
        border = JBUI.Borders.empty(4, 4, 4, 4)
    }
    private val rightTopHeaderLabel = JBLabel(CodeTrackerI18n.rationalePanelTitle()).apply {
        border = JBUI.Borders.empty(4, 4, 4, 4)
    }
    private val rightBottomHeaderLabel = JBLabel(CodeTrackerI18n.pointerPanelTitle())

    // Left: Version/Tag nested history tree wrapped in JBLoadingPanel
    private val historyRoot = DefaultMutableTreeNode("History")
    private val historyModel = DefaultTreeModel(historyRoot)
    private val historyTree = Tree(historyModel)
    private lateinit var leftLoadingPanel: JBLoadingPanel

    // Right Top: Single unified JBScrollPane (Entrypoints + Side-Effect Radar + Rationale)
    private lateinit var outerDetailScrollPane: JBScrollPane
    private val entrypointsPanel = EntrypointsSectionPanel(project) {
        if (::outerDetailScrollPane.isInitialized) outerDetailScrollPane else null
    }
    private val sideEffectRadarPanel = SideEffectRadarPanel(
        onAlertLeftClick = { alert ->
            val entry = selectedEntry ?: return@SideEffectRadarPanel
            openEntryDiff(entry, alert.hunkPointer, alert)
        },
        onAlertJumpToEditor = { alert ->
            val entry = selectedEntry ?: return@SideEffectRadarPanel
            val fileSummary = entry.toFileSummary().copy(path = targetFilePath)
            CodeNavigator.navigateToRadarInWorkspace(project, repoRootPath, entry.commit, fileSummary, alert)
        }
    )
    private val rationalePane = JEditorPane().apply {
        isEditable = false
        alignmentX = Component.LEFT_ALIGNMENT
        font = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        editorKit = HTMLEditorKit()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        putClientProperty(JEditorPane.W3C_LENGTH_UNITS, true)
        text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.fileHistorySelectCommitHint())
    }

    // Right Bottom: Commit-specific smart location pointer tree wrapped in JBLoadingPanel
    private val pointerRoot = DefaultMutableTreeNode("Pointers")
    private val pointerModel = DefaultTreeModel(pointerRoot)
    private val pointerTree = Tree(pointerModel)
    private lateinit var rightLoadingPanel: JBLoadingPanel

    private val openDiffButton = JButton(AllIcons.Actions.Diff).apply {
        val size = JBUI.size(28, 28)
        text = ""
        toolTipText = CodeTrackerI18n.menuPointerDiff()
        margin = JBUI.emptyInsets()
        preferredSize = size
        minimumSize = size
        maximumSize = size
        isFocusable = false
    }

    private var loadedResult: FileHistoryResult? = null
    private var loadFailed: Boolean = false
    private var selectedEntry: FileHistoryCommitEntry? = null
    private var selectedDetail: CommitDetail? = null
    private val detailCache = ConcurrentHashMap<String, CommitDetail>()
    private val historyRequestSeq = AtomicInteger(0)
    private val detailRequestSeq = AtomicInteger(0)

    init {
        isModal = false
        title = CodeTrackerI18n.fileHistoryDialogTitle(targetFilePath.substringAfterLast('/'))
        setCancelButtonText(CodeTrackerI18n.closeButton())
        Disposer.register(disposable, headerSpinner)
        CodeTrackerI18n.addListener(disposable) { applyLocalization() }
        init()
        loadHistoryAsync()
    }

    override fun dispose() {
        activeDialogs.remove(project, this)
        super.dispose()
    }

    override fun getDimensionServiceKey(): String = "com.maekom.codetracker.FileHistoryDialog"

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    fun reloadForTarget(
        repoRootPath: String,
        branch: String,
        startDate: LocalDate,
        endDate: LocalDate,
        targetFilePath: String,
        noMerges: Boolean
    ) {
        this.repoRootPath = repoRootPath
        this.branch = branch
        this.startDate = startDate
        this.endDate = endDate
        this.targetFilePath = targetFilePath
        this.noMerges = noMerges

        detailRequestSeq.incrementAndGet()
        detailCache.clear()
        loadedResult = null
        loadFailed = false
        selectedEntry = null
        selectedDetail = null

        title = CodeTrackerI18n.fileHistoryDialogTitle(targetFilePath.substringAfterLast('/'))
        fileLabel.text = targetFilePath
        scopeLabel.text = CodeTrackerI18n.fileHistoryScope(branch, startDate.format(DATE_FMT), endDate.format(DATE_FMT))
        headerCountLabel.text = CodeTrackerI18n.fileHistoryLoadingCount()

        historyRoot.removeAllChildren()
        historyModel.reload()
        historyTree.emptyText.text = ""

        entrypointsPanel.clear()
        sideEffectRadarPanel.clear()
        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.fileHistorySelectCommitHint())
        resetDetailScrollToTop()
        pointerRoot.removeAllChildren()
        pointerModel.reload()
        if (::rightLoadingPanel.isInitialized) {
            rightLoadingPanel.stopLoading()
        }

        loadHistoryAsync()
    }

    override fun createCenterPanel(): JComponent {
        leftLoadingPanel = JBLoadingPanel(BorderLayout(), disposable)
        rightLoadingPanel = JBLoadingPanel(BorderLayout(), disposable)

        configureTrees()
        wireListeners()

        val rootPanel = JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(960, 620)
            border = JBUI.Borders.empty(2)
        }

        // Top Summary Header with right-aligned compact AsyncProcessIcon
        val headerPanel = JPanel(BorderLayout(8, 2)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6, 6, 6)
            )
            val leftMeta = JPanel(BorderLayout(0, 2)).apply {
                isOpaque = false
                add(fileLabel, BorderLayout.NORTH)
                add(scopeLabel, BorderLayout.SOUTH)
            }
            val rightMeta = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(headerCountLabel)
                add(headerSpinner)
            }
            add(leftMeta, BorderLayout.CENTER)
            add(rightMeta, BorderLayout.EAST)
        }
        rootPanel.add(headerPanel, BorderLayout.NORTH)

        // Left: Version-grouped history tree inside JBLoadingPanel
        leftLoadingPanel.add(JBScrollPane(historyTree), BorderLayout.CENTER)
        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(leftHeaderLabel, BorderLayout.NORTH)
            add(leftLoadingPanel, BorderLayout.CENTER)
        }

        // Right Top: Single unified ScrollableDetailContainer inside one JBScrollPane
        val rationaleWrapper = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(rationalePane, BorderLayout.CENTER)
        }
        val detailContentPanel = ScrollableDetailContainer().apply {
            add(entrypointsPanel)
            add(sideEffectRadarPanel)
            add(rationaleWrapper)
        }
        outerDetailScrollPane = JBScrollPane(detailContentPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        val rightTopPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(rightTopHeaderLabel, BorderLayout.NORTH)
            add(outerDetailScrollPane, BorderLayout.CENTER)
        }

        // Right Bottom: Smart Pointers inside JBLoadingPanel
        rightLoadingPanel.add(JBScrollPane(pointerTree), BorderLayout.CENTER)
        val rightBottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val headerBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 6, 2, 4)
                add(rightBottomHeaderLabel, BorderLayout.WEST)
                add(openDiffButton, BorderLayout.EAST)
            }
            add(headerBar, BorderLayout.NORTH)
            add(rightLoadingPanel, BorderLayout.CENTER)
        }

        val rightSplitter = JBSplitter(true, 0.50f).apply {
            firstComponent = rightTopPanel
            secondComponent = rightBottomPanel
        }

        val mainSplitter = JBSplitter(false, 0.42f).apply {
            firstComponent = leftPanel
            secondComponent = rightSplitter
        }
        rootPanel.add(mainSplitter, BorderLayout.CENTER)
        return rootPanel
    }

    private fun applyLocalization() {
        if (project.isDisposed || isDisposed) return

        title = CodeTrackerI18n.fileHistoryDialogTitle(targetFilePath.substringAfterLast('/'))
        val closeText = CodeTrackerI18n.closeButton()
        setCancelButtonText(closeText)
        cancelAction.putValue(Action.NAME, closeText)

        scopeLabel.text = CodeTrackerI18n.fileHistoryScope(branch, startDate.format(DATE_FMT), endDate.format(DATE_FMT))
        leftHeaderLabel.text = CodeTrackerI18n.fileHistoryLeftTitle()
        rightTopHeaderLabel.text = CodeTrackerI18n.rationalePanelTitle()
        rightBottomHeaderLabel.text = CodeTrackerI18n.pointerPanelTitle()
        openDiffButton.toolTipText = CodeTrackerI18n.menuPointerDiff()
        pointerTree.emptyText.text = CodeTrackerI18n.fileHistorySelectCommitHint()
        entrypointsPanel.refreshLocalization()
        sideEffectRadarPanel.refreshLocalization()

        if (::leftLoadingPanel.isInitialized) {
            leftLoadingPanel.setLoadingText(CodeTrackerI18n.fileHistoryLoadingLeftText())
        }
        if (::rightLoadingPanel.isInitialized) {
            rightLoadingPanel.setLoadingText(CodeTrackerI18n.fileHistoryLoadingRightText())
        }

        val res = loadedResult
        when {
            loadFailed -> {
                headerCountLabel.text = CodeTrackerI18n.fileHistoryFailedCount()
            }
            res == null -> {
                headerCountLabel.text = CodeTrackerI18n.fileHistoryLoadingCount()
            }
            else -> {
                headerCountLabel.text = CodeTrackerI18n.fileHistoryTotalCount(
                    res.totalCommits,
                    res.groups.size,
                    res.hasTagsInRepo
                )
                if (res.totalCommits == 0) {
                    historyTree.emptyText.text = CodeTrackerI18n.fileHistoryEmptyText()
                }
            }
        }

        historyTree.revalidate()
        historyTree.repaint()

        val detail = selectedDetail
        if (detail != null) {
            val prevSelectedHunkLine = ((pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode)
                ?.userObject as? PointerHunkNodeData)?.pointer?.newStartLine
            displayEntryDetail(detail)
            if (prevSelectedHunkLine != null) {
                for (i in 0 until pointerRoot.childCount) {
                    val child = pointerRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                    val hunkObj = child.userObject as? PointerHunkNodeData ?: continue
                    if (hunkObj.pointer.newStartLine == prevSelectedHunkLine) {
                        pointerTree.selectionPath = TreePath(child.path)
                        break
                    }
                }
            }
        } else if (res != null && res.totalCommits == 0) {
            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.fileHistoryEmptyText())
        } else {
            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.fileHistorySelectCommitHint())
        }
    }

    private fun setHeaderSpinnerActive(active: Boolean) {
        if (active) {
            headerSpinner.isVisible = true
            headerSpinner.resume()
        } else {
            headerSpinner.suspend()
            headerSpinner.isVisible = false
        }
    }

    private fun configureTrees() {
        historyTree.isRootVisible = false
        historyTree.showsRootHandles = true
        historyTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        historyTree.cellRenderer = HistoryTreeCellRenderer()
        historyTree.emptyText.text = ""

        pointerTree.isRootVisible = false
        pointerTree.showsRootHandles = true
        pointerTree.toggleClickCount = 0
        pointerTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        pointerTree.cellRenderer = HistoryPointerTreeCellRenderer()
        pointerTree.emptyText.text = CodeTrackerI18n.fileHistorySelectCommitHint()
    }

    private fun wireListeners() {
        // Clickable Side-Effect Radar links inside rationalePane
        rationalePane.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val desc = e.description ?: return@addHyperlinkListener
            val detail = selectedDetail ?: return@addHyperlinkListener
            if (desc.startsWith("radar:")) {
                val idx = desc.removePrefix("radar:").toIntOrNull() ?: return@addHyperlinkListener
                val alert = detail.sideEffectAlerts.getOrNull(idx) ?: return@addHyperlinkListener
                navigateToWorkspacePointer(alert.hunkPointer)
            }
        }

        historyTree.addTreeSelectionListener {
            val node = historyTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is HistoryCommitNodeData -> {
                    selectedEntry = obj.entry
                    loadSingleFileDiffOnDemand(obj.entry)
                }

                is VersionGroupNodeData -> {
                    detailRequestSeq.incrementAndGet()
                    selectedEntry = null
                    selectedDetail = null
                    rightLoadingPanel.stopLoading()
                    setHeaderSpinnerActive(false)
                    entrypointsPanel.clear()
                    rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                        CodeTrackerI18n.fileHistorySelectCommitHint()
                    )
                    rationalePane.caretPosition = 0
                    pointerRoot.removeAllChildren()
                    pointerModel.reload()
                }
            }
        }

        // Right-click on left history tree commit node -> Diff compare
        historyTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowHistoryPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowHistoryPopup(e)

            private fun maybeShowHistoryPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = historyTree.getPathForLocation(e.x, e.y) ?: return
                historyTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val commitNode = node.userObject as? HistoryCommitNodeData ?: return
                val entry = commitNode.entry

                val popup = JPopupMenu()
                popup.add(JMenuItem(CodeTrackerI18n.menuCommitFileDiff()).apply {
                    addActionListener {
                        val cachedPointer = detailCache[entry.commit.hash]
                            ?.fileDetails?.firstOrNull()?.hunks?.firstOrNull()
                        openEntryDiff(entry, cachedPointer)
                    }
                })
                popup.show(historyTree, e.x, e.y)
            }
        })

        // Pointer tree left-click -> jump to code; right-click -> Diff popup
        pointerTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount >= 1) {
                    val path = pointerTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val obj = node.userObject as? PointerHunkNodeData ?: return
                    val radarAlert = obj.pointer.primaryRadarAlert
                    val entry = selectedEntry
                    if (radarAlert != null && entry != null) {
                        openEntryDiff(entry, obj.pointer, radarAlert)
                    } else {
                        navigateToWorkspacePointer(obj.pointer)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPointerPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPointerPopup(e)

            private fun maybeShowPointerPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = pointerTree.getPathForLocation(e.x, e.y) ?: return
                pointerTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val obj = node.userObject as? PointerHunkNodeData ?: return
                val entry = selectedEntry ?: return

                val popup = JPopupMenu()
                popup.add(JMenuItem(CodeTrackerI18n.menuPointerDiff()).apply {
                    addActionListener { openEntryDiff(entry, obj.pointer) }
                })
                popup.add(JMenuItem(CodeTrackerI18n.menuJumpToCode()).apply {
                    addActionListener { navigateToWorkspacePointer(obj.pointer) }
                })
                popup.show(pointerTree, e.x, e.y)
            }
        })

        pointerTree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val node = pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val obj = node.userObject as? PointerHunkNodeData ?: return
                    val radarAlert = obj.pointer.primaryRadarAlert
                    val entry = selectedEntry
                    if (radarAlert != null && entry != null) {
                        openEntryDiff(entry, obj.pointer, radarAlert)
                    } else {
                        navigateToWorkspacePointer(obj.pointer)
                    }
                }
            }
        })

        openDiffButton.addActionListener {
            val entry = selectedEntry
            if (entry == null) {
                Messages.showInfoMessage(
                    project,
                    CodeTrackerI18n.tr("Diff를 비교할 커밋을 먼저 선택해 주세요.", "Please select a commit to compare Diff."),
                    "Maekom Code Tracker"
                )
                return@addActionListener
            }
            val node = pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pointer = (node?.userObject as? PointerHunkNodeData)?.pointer
                ?: selectedDetail?.fileDetails?.firstOrNull()?.hunks?.firstOrNull()
            openEntryDiff(entry, pointer)
        }
    }

    private fun loadHistoryAsync() {
        val reqId = historyRequestSeq.incrementAndGet()
        val reqRepo = repoRootPath
        val reqBranch = branch
        val reqStart = startDate
        val reqEnd = endDate
        val reqFile = targetFilePath
        val reqNoMerges = noMerges

        leftLoadingPanel.setLoadingText(CodeTrackerI18n.fileHistoryLoadingLeftText())
        leftLoadingPanel.startLoading()
        setHeaderSpinnerActive(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            var result: FileHistoryResult? = null
            var error: Exception? = null
            try {
                result = GitRepositoryService.collectFileHistoryWithVersions(
                    project = project,
                    repoRootPath = reqRepo,
                    branch = reqBranch,
                    startDate = reqStart,
                    endDate = reqEnd,
                    filePath = reqFile,
                    noMerges = reqNoMerges
                )
            } catch (e: Exception) {
                error = e
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed || historyRequestSeq.get() != reqId) return@invokeLater
                try {
                    if (error != null) {
                        loadFailed = true
                        headerCountLabel.text = CodeTrackerI18n.fileHistoryFailedCount()
                        historyTree.emptyText.text = CodeTrackerI18n.tr(
                            "파일 수정 이력을 불러오지 못했습니다.",
                            "Failed to load file modification history."
                        )
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            CodeTrackerI18n.tr(
                                "파일 수정 이력 조회 중 오류가 발생했습니다: ${error.message}",
                                "Error loading file history: ${error.message}"
                            )
                        )
                    } else if (result != null) {
                        loadedResult = result
                        applyHistoryResult(result)
                    }
                } finally {
                    if (historyRequestSeq.get() == reqId) {
                        leftLoadingPanel.stopLoading()
                        if (selectedEntry == null) {
                            setHeaderSpinnerActive(false)
                        }
                    }
                }
            }
        }
    }

    private fun applyHistoryResult(result: FileHistoryResult) {
        historyRoot.removeAllChildren()
        val groupNodes = mutableListOf<DefaultMutableTreeNode>()
        var firstCommitNode: DefaultMutableTreeNode? = null

        for (group in result.groups) {
            val groupNode = DefaultMutableTreeNode(VersionGroupNodeData(group))
            groupNodes.add(groupNode)
            for (entry in group.entries) {
                val commitNode = DefaultMutableTreeNode(HistoryCommitNodeData(entry))
                if (firstCommitNode == null) {
                    firstCommitNode = commitNode
                }
                groupNode.add(commitNode)
            }
            historyRoot.add(groupNode)
        }

        historyModel.reload()

        headerCountLabel.text = CodeTrackerI18n.fileHistoryTotalCount(
            result.totalCommits,
            result.groups.size,
            result.hasTagsInRepo
        )

        if (result.totalCommits == 0) {
            historyTree.emptyText.text = CodeTrackerI18n.fileHistoryEmptyText()
            entrypointsPanel.clear()
            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.fileHistoryEmptyText())
            return
        }

        for (groupNode in groupNodes) {
            historyTree.expandPath(TreePath(groupNode.path))
        }
        if (firstCommitNode != null) {
            val treePath = TreePath(firstCommitNode.path)
            historyTree.selectionPath = treePath
            historyTree.scrollPathToVisible(treePath)
        }
    }

    private fun loadSingleFileDiffOnDemand(entry: FileHistoryCommitEntry) {
        val requestId = detailRequestSeq.incrementAndGet()
        val cacheKey = "${repoRootPath}::${entry.commit.hash}::${entry.pathAtCommit}"
        val cached = detailCache[cacheKey]
        if (cached != null) {
            rightLoadingPanel.stopLoading()
            setHeaderSpinnerActive(false)
            displayEntryDetail(cached)
            return
        }

        entrypointsPanel.clear()
        rationalePane.text = RationaleHtmlRenderer.renderLoading(entry.commit)
        rationalePane.caretPosition = 0
        pointerRoot.removeAllChildren()
        pointerModel.reload()

        rightLoadingPanel.setLoadingText(CodeTrackerI18n.fileHistoryLoadingRightText())
        rightLoadingPanel.startLoading()
        setHeaderSpinnerActive(true)

        val currentRepo = repoRootPath
        ApplicationManager.getApplication().executeOnPooledThread {
            var detail: CommitDetail? = null
            var error: Exception? = null
            try {
                detail = RationaleAndCheckEngine.analyzeSingleFileCommitDetail(
                    project = project,
                    repoRootPath = currentRepo,
                    entry = entry
                )
                detailCache[cacheKey] = detail
            } catch (e: Exception) {
                error = e
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed) return@invokeLater
                if (detailRequestSeq.get() != requestId) return@invokeLater

                try {
                    if (error != null) {
                        entrypointsPanel.clear()
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            CodeTrackerI18n.tr(
                                "단일 파일 Diff 분석 중 오류가 발생했습니다: ${error.message}",
                                "Error analyzing single-file Diff: ${error.message}"
                            )
                        )
                    } else if (detail != null) {
                        displayEntryDetail(detail)
                    }
                } finally {
                    if (detailRequestSeq.get() == requestId) {
                        rightLoadingPanel.stopLoading()
                        setHeaderSpinnerActive(false)
                    }
                }
            }
        }
    }

    private fun resetDetailScrollToTop() {
        if (!::outerDetailScrollPane.isInitialized) return
        SwingUtilities.invokeLater {
            outerDetailScrollPane.verticalScrollBar.value = 0
        }
    }

    private fun displayEntryDetail(detail: CommitDetail) {
        selectedDetail = detail
        entrypointsPanel.updateTrace(detail.entrypointTrace)
        sideEffectRadarPanel.updateAlerts(detail.sideEffectAlerts)
        rationalePane.text = RationaleHtmlRenderer.renderCommitDetail(detail)
        rationalePane.caretPosition = 0
        resetDetailScrollToTop()

        pointerRoot.removeAllChildren()
        for (fd in detail.fileDetails) {
            when {
                fd.skippedLargeOrBinary -> {
                    pointerRoot.add(DefaultMutableTreeNode(fd.skipReason ?: CodeTrackerI18n.defaultSkipText()))
                }
                fd.onlyImportOrWhitespaceChanges -> {
                    pointerRoot.add(DefaultMutableTreeNode(CodeTrackerI18n.importWhitespaceOnlyText()))
                }
                fd.hunks.isEmpty() -> {
                    pointerRoot.add(DefaultMutableTreeNode(CodeTrackerI18n.fileHistoryNoTextHunks(fd.summary.status.localizedLabel())))
                }
                else -> {
                    for (hunk in fd.hunks) {
                        pointerRoot.add(DefaultMutableTreeNode(PointerHunkNodeData(fd, hunk)))
                    }
                }
            }
        }
        pointerModel.reload()
    }

    private fun navigateToWorkspacePointer(pointer: CodeHunkPointer) {
        val entry = selectedEntry ?: return
        val fs = LocalFileSystem.getInstance()

        val currentTargetNio = Path.of(repoRootPath, targetFilePath).normalize()
        val currentVf = fs.refreshAndFindFileByNioFile(currentTargetNio)
        if (currentVf != null && currentVf.exists() && !currentVf.isDirectory) {
            val currentFileSummary = entry.toFileSummary().copy(path = targetFilePath)
            CodeNavigator.navigateToPointer(
                project = project,
                repoRootPath = repoRootPath,
                commit = entry.commit,
                file = currentFileSummary,
                pointer = pointer
            )
            return
        }

        if (entry.pathAtCommit != targetFilePath) {
            val histNio = Path.of(repoRootPath, entry.pathAtCommit).normalize()
            val histVf = fs.refreshAndFindFileByNioFile(histNio)
            if (histVf != null && histVf.exists() && !histVf.isDirectory) {
                CodeNavigator.navigateToPointer(
                    project = project,
                    repoRootPath = repoRootPath,
                    commit = entry.commit,
                    file = entry.toFileSummary(),
                    pointer = pointer
                )
                return
            }
        }

        val choice = Messages.showYesNoDialog(
            project,
            CodeTrackerI18n.tr(
                "파일 '${entry.pathAtCommit}' (현재 경로: '$targetFilePath')을(를) 현재 워크스페이스에서 찾을 수 없습니다.\n해당 커밋 시점의 Before / After Diff 창으로 확인하시겠습니까?",
                "File '${entry.pathAtCommit}' (current path: '$targetFilePath') was not found in the current workspace.\nWould you like to view its Before / After Diff for this commit?"
            ),
            CodeTrackerI18n.tr("Maekom Code Tracker - 파일 없음", "Maekom Code Tracker - File Not Found"),
            CodeTrackerI18n.tr("Diff 보기", "View Diff"),
            CodeTrackerI18n.closeButton(),
            Messages.getInformationIcon()
        )
        if (choice == Messages.YES) {
            openEntryDiff(entry, pointer)
        }
    }

    private fun openEntryDiff(
        entry: FileHistoryCommitEntry,
        targetPointer: CodeHunkPointer?,
        radarAlert: com.maekom.codetracker.model.SideEffectAlert? = null
    ) {
        CodeNavigator.showCommitFileDiff(
            project = project,
            repoRootPath = repoRootPath,
            commit = entry.commit,
            file = entry.toFileSummary(),
            targetPointer = targetPointer,
            radarAlert = radarAlert
        )
    }

    private inner class HistoryTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is VersionGroupNodeData -> {
                    icon = null
                    val prefix = if (obj.group.isTagGroup) "🏷️ " else "📅 "
                    append("$prefix${obj.group.groupLabel}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${CodeTrackerI18n.itemsCountUnit(obj.group.entries.size)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is HistoryCommitNodeData -> {
                    val entry = obj.entry
                    val c = entry.commit
                    icon = AllIcons.Vcs.CommitNode
                    append("[${c.timestamp.format(DT_FMT)}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("[${c.authorName}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append(c.subject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(" (${entry.status.code}, ${c.shortHash})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (entry.pathAtCommit != targetFilePath) {
                        append("  ← ${entry.pathAtCommit.substringAfterLast('/')}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }
                }

                else -> append(obj?.toString().orEmpty())
            }
        }
    }

    private inner class HistoryPointerTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is PointerHunkNodeData -> {
                    val p = obj.pointer
                    icon = AllIcons.Nodes.Method
                    p.highestRadarSeverity?.let { sev ->
                        val color = if (sev == RadarSeverity.HIGH) {
                            JBColor(Color(0xC62828), Color(0xFF7B72))
                        } else {
                            JBColor(Color(0xC25E00), Color(0xFFB74D))
                        }
                        append("${sev.localizedBadge()} ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                    }
                    append(
                        "📍 Line ${p.newStartLine} : ",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x1D5FD6), Color(0x6B9BFA)))
                    )
                    append(p.displayContext, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  (+${p.addedLines}/-${p.deletedLines})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                else -> {
                    icon = AllIcons.General.Information
                    append(obj?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
