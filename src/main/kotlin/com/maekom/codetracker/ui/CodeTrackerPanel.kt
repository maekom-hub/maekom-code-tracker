package com.maekom.codetracker.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.maekom.codetracker.analysis.EpisodeGrouper
import com.maekom.codetracker.analysis.MarkdownReportExporter
import com.maekom.codetracker.analysis.RationaleAndCheckEngine
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.model.AnalysisFilter
import com.maekom.codetracker.model.AnalysisResult
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.LayerFilterOption
import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.RepoOption
import com.maekom.codetracker.model.StackTag
import com.maekom.codetracker.model.WorkEpisode
import com.maekom.codetracker.update.PluginUpdateChecker
import com.maekom.codetracker.update.UpdateCheckResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.html.HTMLEditorKit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Main Master-Detail UI panel for Maekom Code Tracker Tool Window.
 */
class CodeTrackerPanel(private val project: Project) : JBPanel<CodeTrackerPanel>(BorderLayout()) {

    companion object {
        private const val AUTHOR_ALL = "전체"
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    }

    // Top Filter & Action Controls
    private val topContainer = JPanel(BorderLayout())
    private val repoCombo = ComboBox<RepoOption>()
    private val branchCombo = ComboBox<String>()
    private val startDateField = JBTextField(LocalDate.now().minusDays(30).format(DATE_FMT), 8)
    private val endDateField = JBTextField(LocalDate.now().format(DATE_FMT), 8)
    private val authorCombo = ComboBox<String>(DefaultComboBoxModel(arrayOf(AUTHOR_ALL)))
    private val keywordField = SearchTextField(false)
    private val layerFilterCombo = ComboBox<LayerFilterOption>(
        DefaultComboBoxModel(LayerFilterOption.defaultOptions().toTypedArray())
    )
    private val excludeMergesCheckBox = JBCheckBox("머지 제외", true)
    private val analyzeButton = JButton("실행", AllIcons.Actions.Execute)
    private val copyMarkdownButton = JButton(AllIcons.Actions.Copy)

    // Summary Strip
    private val summaryLabel = JBLabel("실행 버튼을 눌러 Git 커밋 및 워크플로우를 조회하세요.")

    // Left Panel: Episode & Commit Timeline Tree
    private val timelineRoot = DefaultMutableTreeNode("Timeline")
    private val timelineModel = DefaultTreeModel(timelineRoot)
    private val timelineTree = Tree(timelineModel)

    // Right Top Panel: Rationale & Architecture Check
    private val rationalePane = JEditorPane().apply {
        isEditable = false
        font = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        editorKit = HTMLEditorKit()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        putClientProperty(JEditorPane.W3C_LENGTH_UNITS, true)
        text = RationaleHtmlRenderer.renderPlaceholder(
            "좌측 타임라인에서 커밋을 선택하면 수정 사유(Why), 연관 .md 문서, 아키텍처 체킹 배지가 표시됩니다."
        )
    }

    // Right Bottom Panel: Smart Location Pointer Tree
    private val pointerRoot = DefaultMutableTreeNode("Pointers")
    private val pointerModel = DefaultTreeModel(pointerRoot)
    private val pointerTree = Tree(pointerModel)
    private val openDiffButton = JButton(AllIcons.Actions.Diff)

    // In-memory state & Phase 2 detail cache (repoRoot::commitHash -> CommitDetail)
    private var currentResult: AnalysisResult? = null
    private var selectedCommit: CommitSummary? = null
    private var selectedEpisode: WorkEpisode? = null
    private var selectedDetail: CommitDetail? = null
    private val detailCache = ConcurrentHashMap<String, CommitDetail>()
    private var suppressComboListeners = false

    // Tree node payloads
    private data class EpisodeNodeData(val episode: WorkEpisode)
    private data class CommitNodeData(val commit: CommitSummary, val episode: WorkEpisode)
    private data class LayerGroupNodeData(val layer: PrimaryLayer, val fileCount: Int)
    private data class FileNodeData(val fileDetail: FileDiffDetail)
    private data class HunkNodeData(val fileDetail: FileDiffDetail, val pointer: CodeHunkPointer)
    private data class PointerMdNoteNodeData(
        val fileDetail: FileDiffDetail,
        val pointer: CodeHunkPointer,
        val shortNote: String,
        val fullNote: String
    )

    init {
        border = JBUI.Borders.empty(4)
        configureControls()
        buildLayout()
        wireListeners()
        loadRepositoriesAsync()
    }

    private fun configureControls() {
        repoCombo.toolTipText = "대상 Git 저장소/모듈"
        repoCombo.preferredSize = Dimension(JBUI.scale(140), repoCombo.preferredSize.height)
        repoCombo.maximumSize = Dimension(JBUI.scale(150), repoCombo.preferredSize.height)

        branchCombo.prototypeDisplayValue = "origin/feature-branch"
        branchCombo.toolTipText = "대상 브랜치"
        branchCombo.preferredSize = Dimension(JBUI.scale(140), branchCombo.preferredSize.height)
        branchCombo.maximumSize = Dimension(JBUI.scale(145), branchCombo.preferredSize.height)

        startDateField.toolTipText = "시작일 (YYYY-MM-DD)"
        endDateField.toolTipText = "종료일 (YYYY-MM-DD)"

        authorCombo.toolTipText = "작성자 필터"
        authorCombo.preferredSize = Dimension(JBUI.scale(110), authorCombo.preferredSize.height)
        authorCombo.maximumSize = Dimension(JBUI.scale(120), authorCombo.preferredSize.height)

        layerFilterCombo.toolTipText = "아키텍처 레이어 필터"
        layerFilterCombo.preferredSize = Dimension(JBUI.scale(130), layerFilterCombo.preferredSize.height)
        layerFilterCombo.maximumSize = Dimension(JBUI.scale(140), layerFilterCombo.preferredSize.height)

        keywordField.textEditor.emptyText.text = "클래스/테이블/API"
        keywordField.textEditor.columns = 12
        keywordField.toolTipText = "파일명, 클래스, 테이블, API 키워드 필터"
        keywordField.preferredSize = Dimension(JBUI.scale(150), keywordField.preferredSize.height)

        excludeMergesCheckBox.toolTipText = "머지 커밋 제외 (--no-merges)"

        analyzeButton.toolTipText = "선택 조건으로 Git 워크플로우 분석 실행"
        analyzeButton.margin = JBUI.insets(2, 6, 2, 8)

        configureIconOnlyButton(
            copyMarkdownButton,
            "선택한 커밋의 아키텍처 및 수정 사유 요약을 마크다운으로 클립보드 복사"
        )
        configureIconOnlyButton(
            openDiffButton,
            "선택한 파일의 표준 Diff 비교 창 열기"
        )

        timelineTree.isRootVisible = false
        timelineTree.showsRootHandles = true
        timelineTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        timelineTree.cellRenderer = TimelineTreeCellRenderer()
        timelineTree.emptyText.text = "표시할 커밋이 없습니다."

        pointerTree.isRootVisible = false
        pointerTree.showsRootHandles = true
        pointerTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        pointerTree.cellRenderer = PointerTreeCellRenderer()
        pointerTree.emptyText.text = "커밋을 선택하면 레이어별 수정 위치 포인터가 표시됩니다."
        ToolTipManager.sharedInstance().registerComponent(pointerTree)
    }

    private fun configureIconOnlyButton(button: JButton, tooltip: String) {
        val size = JBUI.size(28, 28)
        button.text = ""
        button.toolTipText = tooltip
        button.margin = JBUI.emptyInsets()
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
        button.isFocusable = false
    }

    private fun buildLayout() {
        topContainer.add(buildFilterBar(), BorderLayout.CENTER)
        topContainer.add(buildSummaryStrip(), BorderLayout.SOUTH)
        add(topContainer, BorderLayout.NORTH)

        // Recalculate WrapLayout height smoothly whenever tool window width changes
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                topContainer.revalidate()
                topContainer.repaint()
            }
        })

        // Right vertical splitter: Rationale (Top) + Smart Pointers (Bottom)
        val rightTopPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val header = JBLabel("  수정 사유 & 아키텍처 체킹 패널").apply {
                border = JBUI.Borders.empty(4, 4, 4, 4)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(rationalePane), BorderLayout.CENTER)
        }

        val rightBottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val headerBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 6, 2, 4)
                add(JBLabel("스마트 위치 포인터 (클릭: 코드 이동 / 우클릭: Diff 비교)"), BorderLayout.WEST)
                add(openDiffButton, BorderLayout.EAST)
            }
            add(headerBar, BorderLayout.NORTH)
            add(JBScrollPane(pointerTree), BorderLayout.CENTER)
        }

        val rightSplitter = JBSplitter(true, 0.48f).apply {
            firstComponent = rightTopPanel
            secondComponent = rightBottomPanel
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val header = JBLabel("  작업 에피소드 & 커밋 타임라인").apply {
                border = JBUI.Borders.empty(4, 4, 4, 4)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(timelineTree), BorderLayout.CENTER)
        }

        val masterDetailSplitter = JBSplitter(false, 0.36f).apply {
            firstComponent = leftPanel
            secondComponent = rightSplitter
        }
        add(masterDetailSplitter, BorderLayout.CENTER)
    }

    private fun wrapIconControlPair(icon: Icon, tooltip: String, vararg controls: JComponent): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            isOpaque = false
            val iconLabel = JBLabel(icon).apply { toolTipText = tooltip }
            add(iconLabel)
            controls.forEach { add(it) }
        }
    }

    private fun buildFilterBar(): JPanel {
        return JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = JBUI.Borders.empty(2, 2, 4, 2)
            add(wrapIconControlPair(AllIcons.Nodes.Module, "대상 Git 저장소/모듈", repoCombo))
            add(wrapIconControlPair(AllIcons.Vcs.Branch, "대상 브랜치", branchCombo))
            add(
                wrapIconControlPair(
                    AllIcons.Vcs.History,
                    "조회 기간 (YYYY-MM-DD)",
                    startDateField,
                    JBLabel("~"),
                    endDateField
                )
            )
            add(wrapIconControlPair(AllIcons.General.User, "작성자 필터", authorCombo))
            add(wrapIconControlPair(AllIcons.Actions.GroupByPackage, "아키텍처 레이어 필터", layerFilterCombo))
            add(keywordField)
            add(excludeMergesCheckBox)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(analyzeButton)
                    add(copyMarkdownButton)
                }
            )
        }
    }

    private fun buildSummaryStrip(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
                JBUI.Borders.empty(4, 8, 4, 8)
            )
            add(summaryLabel, BorderLayout.CENTER)
        }
    }

    private fun wireListeners() {
        repoCombo.addActionListener {
            val repo = repoCombo.selectedItem as? RepoOption
            repoCombo.toolTipText = repo?.displayName ?: "대상 Git 저장소/모듈"
            if (!suppressComboListeners && repo != null) {
                loadBranchesAndAuthorsAsync(repo)
            }
        }

        branchCombo.addActionListener {
            val branch = branchCombo.selectedItem as? String
            branchCombo.toolTipText = branch ?: "대상 브랜치"
            if (!suppressComboListeners) {
                val repo = repoCombo.selectedItem as? RepoOption ?: return@addActionListener
                loadAuthorsAsync(repo, branch ?: "HEAD")
            }
        }

        analyzeButton.addActionListener { runPhase1Analysis() }

        copyMarkdownButton.addActionListener { copySelectedAsMarkdown() }

        // Real-time client-side filtering for keyword and layer filter
        keywordField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyClientSideFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyClientSideFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyClientSideFilter()
        })

        layerFilterCombo.addActionListener {
            if (!suppressComboListeners) {
                applyClientSideFilter()
            }
        }

        // Timeline selection -> Phase 2 on-demand detail analysis
        timelineTree.addTreeSelectionListener {
            val node = timelineTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is CommitNodeData -> {
                    selectedCommit = obj.commit
                    selectedEpisode = obj.episode
                    loadCommitDetailOnDemand(obj.commit)
                }
                is EpisodeNodeData -> {
                    selectedEpisode = obj.episode
                }
            }
        }

        // Pointer tree click -> smart code navigation; right-click -> popup menu
        pointerTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val path = pointerTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    when (val obj = node.userObject) {
                        is HunkNodeData -> {
                            if (e.clickCount >= 1) {
                                navigateToFileOrHunk(obj.fileDetail, obj.pointer)
                            }
                        }
                        is PointerMdNoteNodeData -> {
                            if (e.clickCount >= 1) {
                                navigateToFileOrHunk(obj.fileDetail, obj.pointer)
                            }
                        }
                        is FileNodeData -> {
                            if (e.clickCount >= 2) {
                                navigateToFileOrHunk(obj.fileDetail, obj.fileDetail.hunks.firstOrNull())
                            }
                        }
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = pointerTree.getPathForLocation(e.x, e.y) ?: return
                pointerTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                showPointerContextMenu(node, e)
            }
        })

        pointerTree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val node = pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    when (val obj = node.userObject) {
                        is HunkNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.pointer)
                        is PointerMdNoteNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.pointer)
                        is FileNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.fileDetail.hunks.firstOrNull())
                    }
                }
            }
        })

        openDiffButton.addActionListener { openDiffForSelectedPointerNode() }
    }

    private fun showPointerContextMenu(node: DefaultMutableTreeNode, e: MouseEvent) {
        val fileDetail: FileDiffDetail?
        val pointer: CodeHunkPointer?
        when (val obj = node.userObject) {
            is FileNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.fileDetail.hunks.firstOrNull()
            }
            is HunkNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.pointer
            }
            is PointerMdNoteNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.pointer
            }
            else -> return
        }

        val popup = JPopupMenu()
        popup.add(JMenuItem("Before / After Diff 비교").apply {
            addActionListener { openDiff(fileDetail, pointer) }
        })
        popup.add(JMenuItem("워크스페이스 코드 위치로 이동").apply {
            addActionListener { navigateToFileOrHunk(fileDetail, pointer) }
        })
        popup.addSeparator()
        popup.add(JMenuItem("이 커밋 마크다운 복사").apply {
            addActionListener { copySelectedAsMarkdown() }
        })
        popup.show(pointerTree, e.x, e.y)
    }

    private fun loadRepositoriesAsync() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: Git 저장소 검색 중",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val repos = GitRepositoryService.discoverRepositories(project)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    suppressComboListeners = true
                    repoCombo.model = DefaultComboBoxModel(repos.toTypedArray())
                    suppressComboListeners = false

                    val first = repos.firstOrNull()
                    repoCombo.toolTipText = first?.displayName ?: "대상 Git 저장소/모듈"
                    if (first != null) {
                        loadBranchesAndAuthorsAsync(first)
                    } else {
                        branchCombo.model = DefaultComboBoxModel(arrayOf("HEAD"))
                        branchCombo.toolTipText = "HEAD"
                        authorCombo.model = DefaultComboBoxModel(arrayOf(AUTHOR_ALL))
                        summaryLabel.text = "현재 프로젝트에서 Git 저장소를 찾지 못했습니다."
                    }
                }
            }
        })
    }

    private fun loadBranchesAndAuthorsAsync(repo: RepoOption) {
        val (startDate, endDate) = parseDateRangeLenient()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: 브랜치 및 작성자 목록 로딩 중",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val branches = GitRepositoryService.listBranches(project, repo.rootPath)
                val defaultBranch = branches.firstOrNull() ?: "HEAD"
                val authors = GitRepositoryService.listAuthors(project, repo, defaultBranch, startDate, endDate)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    suppressComboListeners = true
                    branchCombo.model = DefaultComboBoxModel(branches.toTypedArray())
                    branchCombo.toolTipText = branches.firstOrNull() ?: "대상 브랜치"
                    val authorItems = arrayOf(AUTHOR_ALL) + authors.toTypedArray()
                    authorCombo.model = DefaultComboBoxModel(authorItems)
                    suppressComboListeners = false
                }
            }
        })
    }

    private fun loadAuthorsAsync(repo: RepoOption, branch: String) {
        val (startDate, endDate) = parseDateRangeLenient()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: 작성자 목록 로딩 중",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val authors = GitRepositoryService.listAuthors(project, repo, branch, startDate, endDate)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    suppressComboListeners = true
                    val prev = authorCombo.selectedItem as? String
                    val authorItems = arrayOf(AUTHOR_ALL) + authors.toTypedArray()
                    authorCombo.model = DefaultComboBoxModel(authorItems)
                    if (prev != null && authorItems.contains(prev)) {
                        authorCombo.selectedItem = prev
                    }
                    suppressComboListeners = false
                }
            }
        })
    }

    private fun parseDateRangeLenient(): Pair<LocalDate, LocalDate> {
        val end = runCatching { LocalDate.parse(endDateField.text.trim(), DATE_FMT) }.getOrElse { LocalDate.now() }
        val start = runCatching { LocalDate.parse(startDateField.text.trim(), DATE_FMT) }.getOrElse { end.minusDays(30) }
        return if (start.isAfter(end)) end to start else start to end
    }

    private fun runPhase1Analysis() {
        val repo = repoCombo.selectedItem as? RepoOption
        if (repo == null) {
            Messages.showWarningDialog(project, "분석할 Git 저장소가 선택되지 않았습니다.", "Maekom Code Tracker")
            return
        }

        val startDate: LocalDate
        val endDate: LocalDate
        try {
            startDate = LocalDate.parse(startDateField.text.trim(), DATE_FMT)
            endDate = LocalDate.parse(endDateField.text.trim(), DATE_FMT)
        } catch (e: DateTimeParseException) {
            Messages.showErrorDialog(
                project,
                "날짜 형식이 올바르지 않습니다. YYYY-MM-DD 형식으로 입력해 주세요.\n(예: 2026-01-01)",
                "날짜 형식 오류"
            )
            return
        }

        if (startDate.isAfter(endDate)) {
            Messages.showErrorDialog(
                project,
                "시작일($startDate)은 종료일($endDate)보다 이후일 수 없습니다.",
                "날짜 범위 오류"
            )
            return
        }

        val branch = (branchCombo.selectedItem as? String)?.ifBlank { "HEAD" } ?: "HEAD"
        val selectedAuthorRaw = authorCombo.selectedItem as? String
        val author = if (selectedAuthorRaw.isNullOrBlank() || selectedAuthorRaw == AUTHOR_ALL) null else selectedAuthorRaw
        val layerFilter = (layerFilterCombo.selectedItem as? LayerFilterOption)?.layer

        val filter = AnalysisFilter(
            repo = repo,
            branch = branch,
            startDate = startDate,
            endDate = endDate,
            author = author,
            keyword = keywordField.text.trim(),
            layerFilter = layerFilter,
            excludeMerges = excludeMergesCheckBox.isSelected
        )

        analyzeButton.isEnabled = false
        summaryLabel.text = "1단계 Git 커밋 이력 조회 중 (최대 ${GitRepositoryService.MAX_COMMITS}커밋)..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: 커밋 타임라인 분석 중",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val (rawCommits, truncated) = GitRepositoryService.fetchCommitSummaries(project, filter)
                    val refreshedAuthors = rawCommits.map { it.authorName }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                    val episodes = EpisodeGrouper.groupIntoEpisodes(rawCommits)
                    val layerCounts = computeLayerCounts(rawCommits)
                    val totalFiles = rawCommits.sumOf { it.files.size }

                    val result = AnalysisResult(
                        filter = filter,
                        commits = rawCommits,
                        episodes = episodes,
                        layerCounts = layerCounts,
                        totalModifiedFiles = totalFiles,
                        truncatedToLimit = truncated
                    )

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        analyzeButton.isEnabled = true
                        currentResult = result
                        selectedCommit = null
                        selectedEpisode = null
                        selectedDetail = null

                        // Update author dropdown when querying all authors
                        if (author == null && refreshedAuthors.isNotEmpty()) {
                            suppressComboListeners = true
                            val items = arrayOf(AUTHOR_ALL) + refreshedAuthors.toTypedArray()
                            authorCombo.model = DefaultComboBoxModel(items)
                            authorCombo.selectedItem = AUTHOR_ALL
                            suppressComboListeners = false
                        }

                        applyClientSideFilter()
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            if (rawCommits.isEmpty()) {
                                "조회된 커밋이 없습니다. 브랜치, 기간, 작성자 필터를 확인해 주세요."
                            } else {
                                "좌측 타임라인에서 커밋을 선택하면 상세 Diff 분석 및 수정 사유가 표시됩니다."
                            }
                        )
                        clearPointerTree()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        analyzeButton.isEnabled = true
                        summaryLabel.text = "분석 오류: ${e.message}"
                        Messages.showErrorDialog(
                            project,
                            "Git 이력 분석 중 오류가 발생했습니다:\n${e.message}",
                            "Maekom Code Tracker"
                        )
                    }
                }
            }
        })
    }

    private fun applyClientSideFilter() {
        val result = currentResult ?: return
        val keyword = keywordField.text.trim().lowercase()
        val selectedLayer = (layerFilterCombo.selectedItem as? LayerFilterOption)?.layer

        val filteredCommits = result.commits.filter { commit ->
            val cachedDetail = detailCache["${result.filter.repo.rootPath}::${commit.hash}"]
            val matchesKeyword = keyword.isEmpty() ||
                commit.subject.lowercase().contains(keyword) ||
                commit.body.lowercase().contains(keyword) ||
                commit.authorName.lowercase().contains(keyword) ||
                commit.shortHash.lowercase().contains(keyword) ||
                commit.files.any { it.path.lowercase().contains(keyword) } ||
                (cachedDetail?.apiHints?.any { it.pathOrUri.lowercase().contains(keyword) } == true)

            val matchesLayer = selectedLayer == null ||
                commit.primaryLayers.contains(selectedLayer) ||
                (cachedDetail?.allStackTags?.any { it.relatedLayer == selectedLayer } == true)

            matchesKeyword && matchesLayer
        }

        val filteredEpisodes = EpisodeGrouper.groupIntoEpisodes(filteredCommits)
        rebuildTimelineTree(filteredEpisodes)
        updateSummaryStrip(filteredCommits, result.filter.repo.rootPath, result.truncatedToLimit)
    }

    private fun rebuildTimelineTree(episodes: List<WorkEpisode>) {
        timelineRoot.removeAllChildren()
        for (ep in episodes) {
            val epNode = DefaultMutableTreeNode(EpisodeNodeData(ep))
            for (commit in ep.commits) {
                epNode.add(DefaultMutableTreeNode(CommitNodeData(commit, ep)))
            }
            timelineRoot.add(epNode)
        }
        timelineModel.reload()
        // Expand all episode nodes for immediate visibility
        for (i in 0 until timelineTree.rowCount) {
            timelineTree.expandRow(i)
        }
    }

    private fun updateSummaryStrip(commits: List<CommitSummary>, repoRootPath: String, truncated: Boolean) {
        val totalCommits = commits.size
        val totalFiles = commits.sumOf { it.files.size }
        val layerCounts = computeLayerCounts(commits)
        val totalLayerHits = layerCounts.values.sum().coerceAtLeast(1)

        val layerRatioStr = layerCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(" | ") { (layer, count) ->
                val pct = (count * 100.0 / totalLayerHits).toInt()
                "${layer.displayName} ${pct}%($count)"
            }

        // Aggregate detected stack tags from already analyzed commits in cache
        val stackCounts = linkedMapOf<StackTag, Int>()
        for (c in commits) {
            val detail = detailCache["$repoRootPath::${c.hash}"] ?: continue
            for (tag in detail.allStackTags) {
                stackCounts[tag] = (stackCounts[tag] ?: 0) + 1
            }
        }
        val stackStr = if (stackCounts.isNotEmpty()) {
            "  ||  스택: " + stackCounts.entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key.label}(${it.value})" }
        } else {
            ""
        }

        val limitSuffix = if (truncated) " [최대 ${GitRepositoryService.MAX_COMMITS}커밋 제한]" else ""
        summaryLabel.text = "총 커밋: ${totalCommits}건 | 수정 파일: ${totalFiles}건$limitSuffix" +
            (if (layerRatioStr.isNotBlank()) "  ||  $layerRatioStr" else "") +
            stackStr
    }

    private fun computeLayerCounts(commits: List<CommitSummary>): Map<PrimaryLayer, Int> {
        val counts = linkedMapOf<PrimaryLayer, Int>()
        for (commit in commits) {
            for (file in commit.files) {
                counts[file.primaryLayer] = (counts[file.primaryLayer] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * Phase 2: On-demand background analysis of a single selected commit.
     */
    private fun loadCommitDetailOnDemand(commit: CommitSummary) {
        val result = currentResult ?: return
        val repoRootPath = result.filter.repo.rootPath
        val cacheKey = "$repoRootPath::${commit.hash}"

        val cached = detailCache[cacheKey]
        if (cached != null) {
            displayCommitDetail(cached)
            return
        }

        rationalePane.text = RationaleHtmlRenderer.renderLoading(commit)
        rationalePane.caretPosition = 0
        clearPointerTree()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: 커밋 상세 분석 (${commit.shortHash})",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val detail = RationaleAndCheckEngine.analyzeCommitDetail(
                        project = project,
                        repoRootPath = repoRootPath,
                        commit = commit,
                        allEpisodes = result.episodes
                    )
                    detailCache[cacheKey] = detail

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        // Ensure user hasn't already selected a different commit
                        if (selectedCommit?.hash == commit.hash) {
                            displayCommitDetail(detail)
                        }
                        updateSummaryStrip(result.commits, repoRootPath, result.truncatedToLimit)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (selectedCommit?.hash == commit.hash) {
                            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                                "상세 분석 중 오류가 발생했습니다: ${e.message}"
                            )
                        }
                    }
                }
            }
        })
    }

    private fun displayCommitDetail(detail: CommitDetail) {
        selectedDetail = detail
        rationalePane.text = RationaleHtmlRenderer.renderCommitDetail(detail)
        rationalePane.caretPosition = 0
        rebuildPointerTree(detail)
    }

    private fun clearPointerTree() {
        pointerRoot.removeAllChildren()
        pointerModel.reload()
    }

    private fun rebuildPointerTree(detail: CommitDetail) {
        pointerRoot.removeAllChildren()

        val grouped = detail.fileDetails.groupBy { it.summary.primaryLayer }
        for ((layer, files) in grouped.entries.sortedBy { it.key.flowOrder }) {
            val layerNode = DefaultMutableTreeNode(LayerGroupNodeData(layer, files.size))
            for (fd in files) {
                val fileNode = DefaultMutableTreeNode(FileNodeData(fd))
                when {
                    fd.skippedLargeOrBinary -> {
                        fileNode.add(DefaultMutableTreeNode(fd.skipReason ?: "Diff 파싱 생략"))
                    }
                    fd.onlyImportOrWhitespaceChanges -> {
                        fileNode.add(DefaultMutableTreeNode("import/공백 정리만 포함됨"))
                    }
                    else -> {
                        for (hunk in fd.hunks) {
                            val hunkNode = DefaultMutableTreeNode(HunkNodeData(fd, hunk))
                            if (!hunk.matchedMdShortNote.isNullOrBlank()) {
                                hunkNode.add(
                                    DefaultMutableTreeNode(
                                        PointerMdNoteNodeData(
                                            fileDetail = fd,
                                            pointer = hunk,
                                            shortNote = hunk.matchedMdShortNote,
                                            fullNote = hunk.matchedMdFullNote ?: hunk.matchedMdShortNote
                                        )
                                    )
                                )
                            }
                            fileNode.add(hunkNode)
                        }
                    }
                }
                layerNode.add(fileNode)
            }
            pointerRoot.add(layerNode)
        }

        pointerModel.reload()
        var row = 0
        while (row < pointerTree.rowCount) {
            pointerTree.expandRow(row)
            row++
        }
    }

    private fun navigateToFileOrHunk(fileDetail: FileDiffDetail, pointer: CodeHunkPointer?) {
        val result = currentResult ?: return
        val commit = selectedCommit ?: return
        CodeNavigator.navigateToPointer(
            project = project,
            repoRootPath = result.filter.repo.rootPath,
            commit = commit,
            file = fileDetail.summary,
            pointer = pointer
        )
    }

    private fun openDiffForSelectedPointerNode() {
        val node = pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val (fileDetail, pointer) = when (val obj = node?.userObject) {
            is FileNodeData -> obj.fileDetail to obj.fileDetail.hunks.firstOrNull()
            is HunkNodeData -> obj.fileDetail to obj.pointer
            is PointerMdNoteNodeData -> obj.fileDetail to obj.pointer
            else -> {
                val first = selectedDetail?.fileDetails?.firstOrNull()
                first to first?.hunks?.firstOrNull()
            }
        }
        if (fileDetail == null) {
            Messages.showInfoMessage(project, "Diff를 비교할 파일을 먼저 선택해 주세요.", "Maekom Code Tracker")
            return
        }
        openDiff(fileDetail, pointer)
    }

    private fun openDiff(fileDetail: FileDiffDetail, targetPointer: CodeHunkPointer? = null) {
        val result = currentResult ?: return
        val commit = selectedCommit ?: return
        CodeNavigator.showCommitFileDiff(
            project = project,
            repoRootPath = result.filter.repo.rootPath,
            commit = commit,
            file = fileDetail.summary,
            targetPointer = targetPointer
        )
    }

    fun triggerUpdateCheck(versionLabel: JBLabel? = null, triggerButton: JButton? = null) {
        triggerButton?.isEnabled = false
        // Register Custom Plugin Repository host in IntelliJ UpdateSettings before querying
        PluginUpdateChecker.ensureCustomPluginRepoRegistered()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: 최신 버전 확인 중",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = PluginUpdateChecker.checkLatestRelease()

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    triggerButton?.isEnabled = true
                    versionLabel?.text = "v${result.currentVersion}"

                    when (result) {
                        is UpdateCheckResult.UpdateAvailable -> {
                            val message = buildString {
                                appendLine("새 버전의 Maekom Code Tracker가 출시되었습니다.")
                                appendLine()
                                appendLine("버전: v${result.currentVersion} → v${result.latestVersion}")
                                appendLine()
                                append("확인을 누르면 IntelliJ Settings > Plugins 창이 열립니다. 목록에서 [Update] 버튼을 눌러 바로 업데이트하세요.")
                            }
                            Messages.showInfoMessage(
                                project,
                                message,
                                "Maekom Code Tracker - 새 버전 안내"
                            )
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                PluginManagerConfigurable::class.java
                            )
                        }

                        is UpdateCheckResult.UpToDate -> {
                            Messages.showInfoMessage(
                                project,
                                "현재 최신 버전(v${result.currentVersion})을 사용 중입니다.",
                                "Maekom Code Tracker - 업데이트 확인"
                            )
                        }

                        is UpdateCheckResult.RepoNotPublishedYet -> {
                            Messages.showInfoMessage(
                                project,
                                result.message,
                                "Maekom Code Tracker - 업데이트 저장소 등록"
                            )
                        }

                        is UpdateCheckResult.Error -> {
                            Messages.showWarningDialog(
                                project,
                                result.message,
                                "Maekom Code Tracker - 업데이트 확인 실패"
                            )
                        }
                    }
                }
            }
        })
    }

    private fun copySelectedAsMarkdown() {
        val markdown = when {
            selectedDetail != null -> MarkdownReportExporter.exportCommitDetail(selectedDetail!!)
            selectedEpisode != null -> {
                val repoRoot = currentResult?.filter?.repo?.rootPath ?: ""
                val epCache = selectedEpisode!!.commits.mapNotNull { c ->
                    detailCache["$repoRoot::${c.hash}"]?.let { c.hash to it }
                }.toMap()
                MarkdownReportExporter.exportEpisodeSummary(selectedEpisode!!, epCache)
            }
            else -> null
        }

        if (markdown.isNullOrBlank()) {
            Messages.showInfoMessage(
                project,
                "복사할 커밋 또는 작업 에피소드를 먼저 선택해 주세요.",
                "Maekom Code Tracker"
            )
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        copyMarkdownButton.icon = AllIcons.Actions.Checked
        javax.swing.Timer(1200) {
            copyMarkdownButton.icon = AllIcons.Actions.Copy
        }.apply { isRepeats = false; start() }
    }

    // Custom renderer for Left Timeline Tree
    private inner class TimelineTreeCellRenderer : ColoredTreeCellRenderer() {
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
                is EpisodeNodeData -> {
                    val ep = obj.episode
                    icon = AllIcons.Nodes.Folder
                    val range = if (ep.startTime.toLocalDate() == ep.endTime.toLocalDate()) {
                        "${ep.date} ${ep.startTime.format(TIME_FMT)}~${ep.endTime.format(TIME_FMT)}"
                    } else {
                        "${ep.startTime.toLocalDate()} ~ ${ep.endTime.toLocalDate()}"
                    }
                    append("[$range] ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(ep.authorName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(" (${ep.commits.size}커밋)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (ep.hasMarkdownDocs) {
                        append("  [.md ${ep.markdownFiles.size}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x175CD3), Color(0x82B1FF))))
                    }
                }

                is CommitNodeData -> {
                    val c = obj.commit
                    icon = if (c.hasMarkdownChanges) AllIcons.FileTypes.Text else AllIcons.Vcs.CommitNode
                    append("${c.timestamp.format(TIME_FMT)} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("[${c.authorName}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append(c.subject, SimpleTextAttributes.REGULAR_ATTRIBUTES)

                    val layerBadges = c.primaryLayers.take(2).joinToString(" ") { it.badgeText }
                    if (layerBadges.isNotBlank()) {
                        append("  $layerBadges", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(Color(0x0E7490), Color(0x67E8F9))))
                    }
                    if (c.hasMarkdownChanges || obj.episode.hasMarkdownDocs) {
                        append("  📄.md", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(Color(0x7E22CE), Color(0xC084FC))))
                    }
                }

                else -> append(obj?.toString().orEmpty())
            }
        }
    }

    // Custom renderer for Right Bottom Smart Pointer Tree
    private inner class PointerTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            toolTipText = null
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is LayerGroupNodeData -> {
                    icon = AllIcons.Nodes.Module
                    append(obj.layer.badgeText, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${obj.fileCount}파일)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is FileNodeData -> {
                    val fd = obj.fileDetail
                    icon = AllIcons.FileTypes.Any_type
                    append("[${fd.summary.status.code}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append(fd.summary.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (fd.stackTags.isNotEmpty()) {
                        append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append(
                            fd.stackTags.joinToString(" ") { it.label },
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0xB54708), Color(0xFFCA7A)))
                        )
                    }
                    append("  (+${fd.addedLinesTotal}/-${fd.deletedLinesTotal})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${fd.summary.path}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                }

                is HunkNodeData -> {
                    val p = obj.pointer
                    icon = AllIcons.Nodes.Method
                    append("📍 Line ${p.newStartLine} : ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x1D5FD6), Color(0x6B9BFA))))
                    append(p.displayContext, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  (+${p.addedLines}/-${p.deletedLines})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is PointerMdNoteNodeData -> {
                    icon = null
                    val noteColor = UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY
                    append("📝 ${obj.shortNote}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, noteColor))
                    toolTipText = formatMultilineTooltip(obj.fullNote)
                }

                else -> {
                    icon = AllIcons.General.Information
                    append(obj?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        private fun formatMultilineTooltip(raw: String): String {
            val escaped = buildString(raw.length) {
                for (c in raw) {
                    when (c) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\n' -> append("<br/>")
                        else -> append(c)
                    }
                }
            }
            return "<html><body style='width: 360px;'>$escaped</body></html>"
        }
    }
}
