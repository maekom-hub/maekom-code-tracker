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
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.FileHistoryCommitEntry
import com.maekom.codetracker.model.FileHistoryResult
import com.maekom.codetracker.model.FileHistoryVersionGroup
import java.awt.BorderLayout
import java.awt.Color
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
import javax.swing.text.html.HTMLEditorKit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Modeless DialogWrapper displaying version/tag-grouped file modification history
 * with IntelliJ native loading spinners, on-demand commit rationale, and smart location pointers.
 */
class FileHistoryDialog(
    private val project: Project,
    private val repoRootPath: String,
    private val branch: String,
    private val startDate: LocalDate,
    private val endDate: LocalDate,
    private val targetFilePath: String,
    private val noMerges: Boolean
) : DialogWrapper(project, true) {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
        private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private data class VersionGroupNodeData(val group: FileHistoryVersionGroup)
    private data class HistoryCommitNodeData(val entry: FileHistoryCommitEntry)
    private data class PointerHunkNodeData(val fileDetail: FileDiffDetail, val pointer: CodeHunkPointer)

    private val headerCountLabel = JBLabel("이력 조회 중...").apply {
        foreground = JBColor.GRAY
    }
    private val headerSpinner = AsyncProcessIcon("FileHistoryHeaderSpinner").apply {
        suspend()
        isVisible = false
    }

    // Left: Version/Tag nested history tree wrapped in JBLoadingPanel
    private val historyRoot = DefaultMutableTreeNode("History")
    private val historyModel = DefaultTreeModel(historyRoot)
    private val historyTree = Tree(historyModel)
    private lateinit var leftLoadingPanel: JBLoadingPanel

    // Right Top: Compact rationale & architecture check panel (same font & style as main panel)
    private val rationalePane = JEditorPane().apply {
        isEditable = false
        font = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        editorKit = HTMLEditorKit()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        putClientProperty(JEditorPane.W3C_LENGTH_UNITS, true)
        text = RationaleHtmlRenderer.renderPlaceholder("하위 커밋을 선택하면 해당 시점의 수정 사유와 스마트 위치 포인터가 표시됩니다.")
    }

    // Right Bottom: Commit-specific smart location pointer tree wrapped in JBLoadingPanel
    private val pointerRoot = DefaultMutableTreeNode("Pointers")
    private val pointerModel = DefaultTreeModel(pointerRoot)
    private val pointerTree = Tree(pointerModel)
    private lateinit var rightLoadingPanel: JBLoadingPanel

    private val openDiffButton = JButton(AllIcons.Actions.Diff).apply {
        val size = JBUI.size(28, 28)
        text = ""
        toolTipText = "이 변경 위치 Diff 비교"
        margin = JBUI.emptyInsets()
        preferredSize = size
        minimumSize = size
        maximumSize = size
        isFocusable = false
    }

    private var selectedEntry: FileHistoryCommitEntry? = null
    private var selectedDetail: CommitDetail? = null
    private val detailCache = ConcurrentHashMap<String, CommitDetail>()
    private val detailRequestSeq = AtomicInteger(0)

    init {
        isModal = false
        title = "파일 수정 이력 - ${targetFilePath.substringAfterLast('/')}"
        setCancelButtonText("닫기")
        Disposer.register(disposable, headerSpinner)
        init()
        loadHistoryAsync()
    }

    override fun getDimensionServiceKey(): String = "com.maekom.codetracker.FileHistoryDialog"

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

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
            val fileLabel = JBLabel(targetFilePath, AllIcons.FileTypes.Any_type, JBLabel.LEFT).apply {
                font = font.deriveFont(Font.BOLD)
            }
            val scopeLabel = JBLabel(
                "브랜치: $branch  |  기간: ${startDate.format(DATE_FMT)} ~ ${endDate.format(DATE_FMT)}"
            ).apply {
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
            }
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
            val header = JBLabel("  버전(태그)별 파일 수정 이력").apply {
                border = JBUI.Borders.empty(4, 4, 4, 4)
            }
            add(header, BorderLayout.NORTH)
            add(leftLoadingPanel, BorderLayout.CENTER)
        }

        // Right Top: Rationale
        val rightTopPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val header = JBLabel("  수정 사유 & 아키텍처 체킹 패널").apply {
                border = JBUI.Borders.empty(4, 4, 4, 4)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(rationalePane), BorderLayout.CENTER)
        }

        // Right Bottom: Smart Pointers inside JBLoadingPanel
        rightLoadingPanel.add(JBScrollPane(pointerTree), BorderLayout.CENTER)
        val rightBottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val headerBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 6, 2, 4)
                add(JBLabel("스마트 위치 포인터 (클릭: 코드 이동 / 우클릭: Diff 비교)"), BorderLayout.WEST)
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
        pointerTree.emptyText.text = "하위 커밋을 선택하면 이 파일의 변경 위치 포인터가 표시됩니다."
    }

    private fun wireListeners() {
        historyTree.addTreeSelectionListener {
            val node = historyTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is HistoryCommitNodeData -> {
                    selectedEntry = obj.entry
                    loadSingleFileDiffOnDemand(obj.entry)
                }

                is VersionGroupNodeData -> {
                    // Cancel any in-flight detail request and show clean guidance placeholder
                    detailRequestSeq.incrementAndGet()
                    selectedEntry = null
                    selectedDetail = null
                    rightLoadingPanel.stopLoading()
                    setHeaderSpinnerActive(false)
                    rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                        "하위 커밋을 선택하면 해당 시점의 수정 사유와 스마트 위치 포인터가 표시됩니다."
                    )
                    rationalePane.caretPosition = 0
                    pointerRoot.removeAllChildren()
                    pointerModel.reload()
                }
            }
        }

        // Right-click on left history tree commit node -> Diff 비교
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
                popup.add(JMenuItem("이 커밋의 파일 Diff 비교").apply {
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
                    navigateToWorkspacePointer(obj.pointer)
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
                popup.add(JMenuItem("이 변경 위치 Diff 비교").apply {
                    addActionListener { openEntryDiff(entry, obj.pointer) }
                })
                popup.add(JMenuItem("워크스페이스 코드 위치로 이동").apply {
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
                    navigateToWorkspacePointer(obj.pointer)
                }
            }
        })

        openDiffButton.addActionListener {
            val entry = selectedEntry
            if (entry == null) {
                Messages.showInfoMessage(project, "Diff를 비교할 커밋을 먼저 선택해 주세요.", "Maekom Code Tracker")
                return@addActionListener
            }
            val node = pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pointer = (node?.userObject as? PointerHunkNodeData)?.pointer
                ?: selectedDetail?.fileDetails?.firstOrNull()?.hunks?.firstOrNull()
            openEntryDiff(entry, pointer)
        }
    }

    private fun loadHistoryAsync() {
        leftLoadingPanel.setLoadingText("버전별 파일 변경 이력 및 태그 구간 분석 중...")
        leftLoadingPanel.startLoading()
        setHeaderSpinnerActive(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            var result: FileHistoryResult? = null
            var error: Exception? = null
            try {
                result = GitRepositoryService.collectFileHistoryWithVersions(
                    project = project,
                    repoRootPath = repoRootPath,
                    branch = branch,
                    startDate = startDate,
                    endDate = endDate,
                    filePath = targetFilePath,
                    noMerges = noMerges
                )
            } catch (e: Exception) {
                error = e
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed) return@invokeLater
                try {
                    if (error != null) {
                        headerCountLabel.text = "조회 실패"
                        historyTree.emptyText.text = "파일 수정 이력을 불러오지 못했습니다."
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            "파일 수정 이력 조회 중 오류가 발생했습니다: ${error.message}"
                        )
                    } else if (result != null) {
                        applyHistoryResult(result)
                    }
                } finally {
                    leftLoadingPanel.stopLoading()
                    if (selectedEntry == null) {
                        setHeaderSpinnerActive(false)
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

        val groupTypeDesc = if (result.hasTagsInRepo) {
            "버전/태그 그룹 ${result.groups.size}개"
        } else {
            "에피소드 그룹 ${result.groups.size}개"
        }
        headerCountLabel.text = "총 ${result.totalCommits}건 수정 ($groupTypeDesc)"

        if (result.totalCommits == 0) {
            historyTree.emptyText.text = "선택한 기간 및 조건 내 파일 수정 이력이 없습니다."
            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                "선택한 기간 및 조건 내 파일 수정 이력이 없습니다."
            )
            return
        }

        // Expand all version group nodes first, then auto-select the latest commit node
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
        val cacheKey = entry.commit.hash
        val cached = detailCache[cacheKey]
        if (cached != null) {
            rightLoadingPanel.stopLoading()
            setHeaderSpinnerActive(false)
            displayEntryDetail(cached)
            return
        }

        rationalePane.text = RationaleHtmlRenderer.renderLoading(entry.commit)
        rationalePane.caretPosition = 0
        pointerRoot.removeAllChildren()
        pointerModel.reload()

        rightLoadingPanel.setLoadingText("스마트 위치 포인터 및 변경 라인 분석 중...")
        rightLoadingPanel.startLoading()
        setHeaderSpinnerActive(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            var detail: CommitDetail? = null
            var error: Exception? = null
            try {
                detail = RationaleAndCheckEngine.analyzeSingleFileCommitDetail(
                    project = project,
                    repoRootPath = repoRootPath,
                    entry = entry
                )
                detailCache[cacheKey] = detail
            } catch (e: Exception) {
                error = e
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed) return@invokeLater
                // Ignore stale responses when user rapidly navigates with arrow keys or mouse
                if (detailRequestSeq.get() != requestId) return@invokeLater

                try {
                    if (error != null) {
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            "단일 파일 Diff 분석 중 오류가 발생했습니다: ${error.message}"
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

    private fun displayEntryDetail(detail: CommitDetail) {
        selectedDetail = detail
        rationalePane.text = RationaleHtmlRenderer.renderCommitDetail(detail)
        rationalePane.caretPosition = 0

        pointerRoot.removeAllChildren()
        for (fd in detail.fileDetails) {
            when {
                fd.skippedLargeOrBinary -> {
                    pointerRoot.add(DefaultMutableTreeNode(fd.skipReason ?: "Diff 파싱 생략"))
                }
                fd.onlyImportOrWhitespaceChanges -> {
                    pointerRoot.add(DefaultMutableTreeNode("import/공백 정리만 포함됨"))
                }
                fd.hunks.isEmpty() -> {
                    pointerRoot.add(DefaultMutableTreeNode("텍스트 변경 블록 없음 (${fd.summary.status.label})"))
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

        // Prefer current workspace file path (handles historical renames gracefully)
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

        // Secondary check: historical pathAtCommit if different and present
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

        // Neither path exists in current checkout -> offer standard Diff viewer at commit point
        val choice = Messages.showYesNoDialog(
            project,
            "파일 '${entry.pathAtCommit}' (현재 경로: '$targetFilePath')을(를) 현재 워크스페이스에서 찾을 수 없습니다.\n해당 커밋 시점의 Before / After Diff 창으로 확인하시겠습니까?",
            "Maekom Code Tracker - 파일 없음",
            "Diff 보기",
            "닫기",
            Messages.getInformationIcon()
        )
        if (choice == Messages.YES) {
            openEntryDiff(entry, pointer)
        }
    }

    private fun openEntryDiff(entry: FileHistoryCommitEntry, targetPointer: CodeHunkPointer?) {
        CodeNavigator.showCommitFileDiff(
            project = project,
            repoRootPath = repoRootPath,
            commit = entry.commit,
            file = entry.toFileSummary(),
            targetPointer = targetPointer
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
                    append(" (${obj.group.entries.size}건)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
