package com.maekom.codetracker.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
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
import com.maekom.codetracker.analysis.ArchitectureFlowBuilder
import com.maekom.codetracker.analysis.EpisodeGrouper
import com.maekom.codetracker.analysis.MarkdownReportExporter
import com.maekom.codetracker.analysis.RationaleAndCheckEngine
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.AnalysisFilter
import com.maekom.codetracker.model.AnalysisResult
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.FlowMergedFile
import com.maekom.codetracker.model.FlowStageGroup
import com.maekom.codetracker.model.FlowSubGroup
import com.maekom.codetracker.model.LayerFilterOption
import com.maekom.codetracker.model.MergedFileAnalysisDetail
import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.RadarSeverity
import com.maekom.codetracker.model.RepoOption
import com.maekom.codetracker.model.SideEffectAlert
import com.maekom.codetracker.model.StackTag
import com.maekom.codetracker.model.WorkEpisode
import com.maekom.codetracker.update.PluginUpdateChecker
import com.maekom.codetracker.update.UpdateCheckResult
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Main Master-Detail UI panel for Maekom Code Tracker Tool Window.
 * Supports zero-restart live KO/EN language switching while preserving selection & tree state.
 */
class CodeTrackerPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JBPanel<CodeTrackerPanel>(BorderLayout()), Disposable {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private const val CARD_TIMELINE = "TIMELINE"
        private const val CARD_FLOW = "FLOW"
        private const val MAX_DISPLAYED_ISSUES = 8
        private val ISSUE_KEY_REGEX = Regex("""(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]{1,15}-\d+)(?![A-Za-z0-9_])""")
        private val ISSUE_KEY_BLACKLIST = setOf(
            "UTF-8", "UTF-16", "SHA-1", "SHA-256", "SHA-512", "MD-5", "ISO-8601",
            "JDK-8", "JDK-11", "JDK-17", "JDK-21",
            "HTTP-200", "HTTP-400", "HTTP-401", "HTTP-403", "HTTP-404", "HTTP-500",
            "TLS-1", "S-3", "EC-2"
        )
    }

    private data class AuthorOption(val rawName: String?) {
        override fun toString(): String = rawName ?: CodeTrackerI18n.allOption()

        companion object {
            val ALL = AuthorOption(null)
        }
    }

    // Top Filter & Action Controls
    private val topContainer = JPanel(BorderLayout())
    private val repoIconLabel = JBLabel(AllIcons.Nodes.Module)
    private val branchIconLabel = JBLabel(AllIcons.Vcs.Branch)
    private val dateIconLabel = JBLabel(AllIcons.Vcs.History)
    private val authorIconLabel = JBLabel(AllIcons.General.User)
    private val layerIconLabel = JBLabel(AllIcons.Actions.GroupByPackage)

    private val repoCombo = ComboBox<RepoOption>()
    private val branchCombo = ComboBox<String>()
    private val startDateField = JBTextField(LocalDate.now().minusDays(30).format(DATE_FMT), 8)
    private val endDateField = JBTextField(LocalDate.now().format(DATE_FMT), 8)
    private val authorCombo = ComboBox<AuthorOption>(DefaultComboBoxModel(arrayOf(AuthorOption.ALL)))
    private val keywordField = SearchTextField(false)
    private val layerFilterCombo = ComboBox<LayerFilterOption>(
        DefaultComboBoxModel(LayerFilterOption.defaultOptions().toTypedArray())
    )
    private val excludeMergesCheckBox = JBCheckBox(CodeTrackerI18n.excludeMergesLabel(), true)
    private val analyzeButton = JButton(CodeTrackerI18n.runButtonLabel(), AllIcons.Actions.Execute)
    private val copyMarkdownButton = JButton(AllIcons.Actions.Copy)

    // Panel Headers
    private val leftHeaderLabel = JBLabel(CodeTrackerI18n.timelinePanelTitle()).apply {
        border = JBUI.Borders.empty(4, 4, 4, 4)
    }
    private val rightTopHeaderLabel = JBLabel(CodeTrackerI18n.rationalePanelTitle()).apply {
        border = JBUI.Borders.empty(4, 4, 4, 4)
    }
    private val rightBottomHeaderLabel = JBLabel(CodeTrackerI18n.pointerPanelTitle())

    // Summary Strip
    private val summaryLabel = JBLabel(CodeTrackerI18n.initialSummaryText())

    // Left Panel: View Mode Toggle + Timeline Tree + 5-Stage Execution Flow Tree
    private val timelineModeButton = JToggleButton(CodeTrackerI18n.viewModeTimeline(), true).apply {
        isFocusable = false
        margin = JBUI.insets(2, 6, 2, 6)
    }
    private val flowModeButton = JToggleButton(CodeTrackerI18n.viewModeExecutionFlow(), false).apply {
        isFocusable = false
        margin = JBUI.insets(2, 6, 2, 6)
    }
    private val leftCardLayout = CardLayout()
    private val leftCardPanel = JPanel(leftCardLayout)

    private val timelineRoot = DefaultMutableTreeNode("Timeline")
    private val timelineModel = DefaultTreeModel(timelineRoot)
    private val timelineTree = Tree(timelineModel)

    private val flowRoot = DefaultMutableTreeNode("ExecutionFlow")
    private val flowModel = DefaultTreeModel(flowRoot)
    private val flowTree = Tree(flowModel)

    // Right Top Panel: Single unified JBScrollPane containing Block 1 (Entrypoints) + Block 2 (Side-Effect Radar) + Block 3 (Commit Rationale)
    private lateinit var outerDetailScrollPane: JBScrollPane
    private val entrypointsPanel = EntrypointsSectionPanel(project) {
        if (::outerDetailScrollPane.isInitialized) outerDetailScrollPane else null
    }
    private val sideEffectRadarPanel = SideEffectRadarPanel { alert -> navigateToRadarAlert(alert) }
    private val rationalePane = JEditorPane().apply {
        isEditable = false
        alignmentX = Component.LEFT_ALIGNMENT
        font = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        editorKit = HTMLEditorKit()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        putClientProperty(JEditorPane.W3C_LENGTH_UNITS, true)
        text = RationaleHtmlRenderer.renderPlaceholder(CodeTrackerI18n.rationaleInitialPlaceholder())
    }

    // Right Bottom Panel: Smart Location Pointer Tree
    private val pointerRoot = DefaultMutableTreeNode("Pointers")
    private val pointerModel = DefaultTreeModel(pointerRoot)
    private val pointerTree = Tree(pointerModel)
    private val openDiffButton = JButton(AllIcons.Actions.Diff)

    // In-memory state & Phase 2 detail cache (repoRoot::commitHash -> CommitDetail)
    private var currentResult: AnalysisResult? = null
    private var lastFilteredCommits: List<CommitSummary> = emptyList()
    private var lastRepoCount: Int = -1
    private var selectedCommit: CommitSummary? = null
    private var selectedEpisode: WorkEpisode? = null
    private var selectedDetail: CommitDetail? = null
    private var selectedMergedDetail: MergedFileAnalysisDetail? = null
    private val detailCache = ConcurrentHashMap<String, CommitDetail>()
    private val mergedFileCache = ConcurrentHashMap<String, MergedFileAnalysisDetail>()
    private val mergedLoadSeq = AtomicInteger(0)
    private var suppressComboListeners = false
    private val repoInitInProgress = AtomicBoolean(false)
    private val pendingRepoInit = AtomicBoolean(false)
    private val dateDebounceTimer = javax.swing.Timer(350) { onDateRangeEdited() }.apply {
        isRepeats = false
    }

    // Tree node payloads
    private data class EpisodeNodeData(val episode: WorkEpisode)
    private data class CommitNodeData(val commit: CommitSummary, val episode: WorkEpisode)
    private data class FlowStageNodeData(val group: FlowStageGroup)
    private data class FlowSubGroupNodeData(val subGroup: FlowSubGroup)
    private data class FlowMergedFileNodeData(val mergedFile: FlowMergedFile)
    private data class LayerGroupNodeData(val layer: PrimaryLayer, val fileCount: Int)
    private data class MergedCommitGroupNodeData(val commit: CommitSummary, val fileDetail: FileDiffDetail)
    private data class FileNodeData(val fileDetail: FileDiffDetail, val overrideCommit: CommitSummary? = null)
    private data class HunkNodeData(val fileDetail: FileDiffDetail, val pointer: CodeHunkPointer, val overrideCommit: CommitSummary? = null)
    private data class PointerMdNoteNodeData(
        val fileDetail: FileDiffDetail,
        val pointer: CodeHunkPointer,
        val shortNote: String,
        val fullNote: String,
        val overrideCommit: CommitSummary? = null
    )

    init {
        Disposer.register(parentDisposable, this)
        border = JBUI.Borders.empty(4)
        configureControls()
        buildLayout()
        wireListeners()
        CodeTrackerI18n.addListener(this) { applyLocalization() }
        registerRepositoryLifecycleHooks()
        initRepositoriesAsync()
    }

    override fun dispose() {
        dateDebounceTimer.stop()
    }

    private fun configureControls() {
        repoCombo.preferredSize = Dimension(JBUI.scale(140), repoCombo.preferredSize.height)
        repoCombo.maximumSize = Dimension(JBUI.scale(150), repoCombo.preferredSize.height)

        branchCombo.prototypeDisplayValue = "origin/feature-branch"
        branchCombo.preferredSize = Dimension(JBUI.scale(140), branchCombo.preferredSize.height)
        branchCombo.maximumSize = Dimension(JBUI.scale(145), branchCombo.preferredSize.height)

        authorCombo.preferredSize = Dimension(JBUI.scale(110), authorCombo.preferredSize.height)
        authorCombo.maximumSize = Dimension(JBUI.scale(120), authorCombo.preferredSize.height)

        layerFilterCombo.preferredSize = Dimension(JBUI.scale(130), layerFilterCombo.preferredSize.height)
        layerFilterCombo.maximumSize = Dimension(JBUI.scale(140), layerFilterCombo.preferredSize.height)

        keywordField.textEditor.columns = 12
        keywordField.preferredSize = Dimension(JBUI.scale(150), keywordField.preferredSize.height)

        analyzeButton.margin = JBUI.insets(2, 6, 2, 8)

        configureIconOnlyButton(copyMarkdownButton, CodeTrackerI18n.copyMarkdownTooltip())
        configureIconOnlyButton(openDiffButton, CodeTrackerI18n.openDiffTooltip())

        timelineTree.isRootVisible = false
        timelineTree.showsRootHandles = true
        timelineTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        timelineTree.cellRenderer = TimelineTreeCellRenderer()

        flowTree.isRootVisible = false
        flowTree.showsRootHandles = true
        flowTree.toggleClickCount = 0
        flowTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        flowTree.cellRenderer = ExecutionFlowTreeCellRenderer()

        pointerTree.isRootVisible = false
        pointerTree.showsRootHandles = true
        pointerTree.toggleClickCount = 0
        pointerTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        pointerTree.cellRenderer = PointerTreeCellRenderer()
        ToolTipManager.sharedInstance().registerComponent(pointerTree)

        val modeGroup = ButtonGroup()
        modeGroup.add(timelineModeButton)
        modeGroup.add(flowModeButton)

        applyStaticTexts()
    }

    private fun applyStaticTexts() {
        repoIconLabel.toolTipText = CodeTrackerI18n.repoTooltip()
        branchIconLabel.toolTipText = CodeTrackerI18n.branchTooltip()
        dateIconLabel.toolTipText = CodeTrackerI18n.dateRangeTooltip()
        authorIconLabel.toolTipText = CodeTrackerI18n.authorTooltip()
        layerIconLabel.toolTipText = CodeTrackerI18n.layerTooltip()

        val selectedRepo = repoCombo.selectedItem as? RepoOption
        repoCombo.toolTipText = selectedRepo?.localizedDisplayName() ?: CodeTrackerI18n.repoTooltip()
        val selectedBranch = branchCombo.selectedItem as? String
        branchCombo.toolTipText = selectedBranch ?: CodeTrackerI18n.branchTooltip()

        startDateField.toolTipText = CodeTrackerI18n.startDateTooltip()
        endDateField.toolTipText = CodeTrackerI18n.endDateTooltip()
        authorCombo.toolTipText = CodeTrackerI18n.authorTooltip()
        layerFilterCombo.toolTipText = CodeTrackerI18n.layerTooltip()

        keywordField.textEditor.emptyText.text = CodeTrackerI18n.keywordEmptyText()
        keywordField.toolTipText = CodeTrackerI18n.keywordTooltip()
        excludeMergesCheckBox.text = CodeTrackerI18n.excludeMergesLabel()
        excludeMergesCheckBox.toolTipText = CodeTrackerI18n.excludeMergesTooltip()
        analyzeButton.text = CodeTrackerI18n.runButtonLabel()
        analyzeButton.toolTipText = CodeTrackerI18n.runButtonTooltip()
        copyMarkdownButton.toolTipText = CodeTrackerI18n.copyMarkdownTooltip()
        openDiffButton.toolTipText = CodeTrackerI18n.openDiffTooltip()

        timelineModeButton.text = CodeTrackerI18n.viewModeTimeline()
        flowModeButton.text = CodeTrackerI18n.viewModeExecutionFlow()
        leftHeaderLabel.text = CodeTrackerI18n.timelinePanelTitle()
        rightTopHeaderLabel.text = CodeTrackerI18n.rationalePanelTitle()
        rightBottomHeaderLabel.text = CodeTrackerI18n.pointerPanelTitle()

        timelineTree.emptyText.text = CodeTrackerI18n.timelineEmptyText()
        flowTree.emptyText.text = CodeTrackerI18n.flowEmptyText()
        pointerTree.emptyText.text = CodeTrackerI18n.pointerEmptyText()
    }

    /**
     * Zero-restart live localization update preserving all combo selections,
     * tree expansion states, and currently selected commit/pointer/merged file.
     */
    private fun applyLocalization() {
        if (project.isDisposed) return

        applyStaticTexts()

        repoCombo.revalidate()
        repoCombo.repaint()
        authorCombo.revalidate()
        authorCombo.repaint()
        layerFilterCombo.revalidate()
        layerFilterCombo.repaint()

        val result = currentResult
        if (result != null) {
            updateSummaryStrip(lastFilteredCommits, result.filter.repo.rootPath, result.truncatedToLimit)
        } else {
            summaryLabel.text = if (lastRepoCount == 0) {
                CodeTrackerI18n.noGitRepoFound()
            } else {
                CodeTrackerI18n.initialSummaryText()
            }
        }

        timelineTree.revalidate()
        timelineTree.repaint()
        flowTree.revalidate()
        flowTree.repaint()
        entrypointsPanel.refreshLocalization()
        sideEffectRadarPanel.refreshLocalization()

        val merged = selectedMergedDetail
        val detail = selectedDetail
        when {
            merged != null -> {
                rationalePane.text = RationaleHtmlRenderer.renderMergedFileDetail(merged)
                rationalePane.caretPosition = 0
                rebuildPointerTreeForMergedFile(merged)
            }
            detail != null -> {
                rationalePane.text = RationaleHtmlRenderer.renderCommitDetail(detail)
                rationalePane.caretPosition = 0
                rebuildPointerTreePreservingState(detail)
            }
            else -> {
                val placeholder = when {
                    result == null -> CodeTrackerI18n.rationaleInitialPlaceholder()
                    result.commits.isEmpty() -> CodeTrackerI18n.rationaleNoCommitsPlaceholder()
                    else -> CodeTrackerI18n.rationaleSelectCommitPlaceholder()
                }
                rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(placeholder)
            }
        }

        topContainer.revalidate()
        topContainer.repaint()
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

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                topContainer.revalidate()
                topContainer.repaint()
            }
        })

        // Right vertical splitter: Single unified ScrollableDetailContainer inside one JBScrollPane
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

        val rightBottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            val headerBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 6, 2, 4)
                add(rightBottomHeaderLabel, BorderLayout.WEST)
                add(openDiffButton, BorderLayout.EAST)
            }
            add(headerBar, BorderLayout.NORTH)
            add(JBScrollPane(pointerTree), BorderLayout.CENTER)
        }

        val rightSplitter = JBSplitter(true, 0.48f).apply {
            firstComponent = rightTopPanel
            secondComponent = rightBottomPanel
        }

        leftCardPanel.add(JBScrollPane(timelineTree), CARD_TIMELINE)
        leftCardPanel.add(JBScrollPane(flowTree), CARD_FLOW)

        val leftModeBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(timelineModeButton)
            add(flowModeButton)
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(leftModeBar, BorderLayout.NORTH)
            add(leftCardPanel, BorderLayout.CENTER)
        }

        val masterDetailSplitter = JBSplitter(false, 0.36f).apply {
            firstComponent = leftPanel
            secondComponent = rightSplitter
        }
        add(masterDetailSplitter, BorderLayout.CENTER)
    }

    private fun wrapIconControlPair(iconLabel: JBLabel, vararg controls: JComponent): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            isOpaque = false
            add(iconLabel)
            controls.forEach { add(it) }
        }
    }

    private fun buildFilterBar(): JPanel {
        return JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = JBUI.Borders.empty(2, 2, 4, 2)
            add(wrapIconControlPair(repoIconLabel, repoCombo))
            add(wrapIconControlPair(branchIconLabel, branchCombo))
            add(wrapIconControlPair(dateIconLabel, startDateField, JBLabel("~"), endDateField))
            add(wrapIconControlPair(authorIconLabel, authorCombo))
            add(wrapIconControlPair(layerIconLabel, layerFilterCombo))
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
        timelineModeButton.addActionListener {
            leftCardLayout.show(leftCardPanel, CARD_TIMELINE)
        }
        flowModeButton.addActionListener {
            leftCardLayout.show(leftCardPanel, CARD_FLOW)
        }

        // Clickable Entrypoint chips (ep:<idx>) & Side-Effect Radar items (radar:<idx>)
        rationalePane.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            handleRationaleHyperlink(e.description ?: return@addHyperlinkListener)
        }

        repoCombo.addActionListener {
            val repo = repoCombo.selectedItem as? RepoOption
            repoCombo.toolTipText = repo?.localizedDisplayName() ?: CodeTrackerI18n.repoTooltip()
            if (!suppressComboListeners && repo != null) {
                loadBranchesAndAuthorsAsync(repo)
            }
        }

        branchCombo.addActionListener {
            val branch = branchCombo.selectedItem as? String
            branchCombo.toolTipText = branch ?: CodeTrackerI18n.branchTooltip()
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

        // Refresh author list automatically when start/end date changes
        val dateDocListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = dateDebounceTimer.restart()
            override fun removeUpdate(e: DocumentEvent?) = dateDebounceTimer.restart()
            override fun changedUpdate(e: DocumentEvent?) = dateDebounceTimer.restart()
        }
        startDateField.document.addDocumentListener(dateDocListener)
        endDateField.document.addDocumentListener(dateDocListener)

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
                    selectedMergedDetail = null
                    selectedCommit = obj.commit
                    selectedEpisode = obj.episode
                    loadCommitDetailOnDemand(obj.commit)
                }
                is EpisodeNodeData -> {
                    selectedEpisode = obj.episode
                }
            }
        }

        // 5-Stage Execution Flow Tree: single-click -> aggregated on-demand detail; double-click -> FileHistoryDialog
        flowTree.addTreeSelectionListener {
            val node = flowTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val mergedNode = node.userObject as? FlowMergedFileNodeData ?: return@addTreeSelectionListener
            loadMergedFlowFileOnDemand(mergedNode.mergedFile)
        }

        flowTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    val path = flowTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val mergedNode = node.userObject as? FlowMergedFileNodeData ?: return
                    openFileHistoryDialogByPath(mergedNode.mergedFile.path)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowFlowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowFlowPopup(e)

            private fun maybeShowFlowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = flowTree.getPathForLocation(e.x, e.y) ?: return
                flowTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val mergedNode = node.userObject as? FlowMergedFileNodeData ?: return

                val popup = JPopupMenu()
                popup.add(JMenuItem(CodeTrackerI18n.menuFileHistory()).apply {
                    addActionListener { openFileHistoryDialogByPath(mergedNode.mergedFile.path) }
                })
                popup.show(flowTree, e.x, e.y)
            }
        })

        // Pointer tree click -> smart code navigation / file history dialog; right-click -> popup menu
        pointerTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val path = pointerTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    when (val obj = node.userObject) {
                        is HunkNodeData -> {
                            if (e.clickCount >= 1) {
                                navigateToFileOrHunk(obj.fileDetail, obj.pointer, obj.overrideCommit)
                            }
                        }
                        is PointerMdNoteNodeData -> {
                            if (e.clickCount >= 1) {
                                navigateToFileOrHunk(obj.fileDetail, obj.pointer, obj.overrideCommit)
                            }
                        }
                        is FileNodeData -> {
                            if (e.clickCount == 2) {
                                openFileHistoryDialog(obj.fileDetail)
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
                        is HunkNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.pointer, obj.overrideCommit)
                        is PointerMdNoteNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.pointer, obj.overrideCommit)
                        is FileNodeData -> navigateToFileOrHunk(obj.fileDetail, obj.fileDetail.hunks.firstOrNull(), obj.overrideCommit)
                    }
                }
            }
        })

        openDiffButton.addActionListener { openDiffForSelectedPointerNode() }
    }

    private fun handleRationaleHyperlink(desc: String) {
        val result = currentResult ?: return
        val repoRoot = result.filter.repo.rootPath
        val merged = selectedMergedDetail
        val detail = selectedDetail

        when {
            desc.startsWith("ep:") -> {
                val idx = desc.removePrefix("ep:").toIntOrNull() ?: return
                val ep = merged?.entrypointTrace?.entrypoints?.getOrNull(idx)
                    ?: detail?.entrypointTrace?.entrypoints?.getOrNull(idx)
                    ?: return
                CodeNavigator.navigateToAbsoluteFileLine(project, ep.filePath, ep.lineNumber1Based)
            }

            desc.startsWith("radar:") -> {
                val idx = desc.removePrefix("radar:").toIntOrNull() ?: return
                val alert = merged?.sideEffectAlerts?.getOrNull(idx)
                    ?: detail?.sideEffectAlerts?.getOrNull(idx)
                    ?: return
                navigateToRadarAlert(alert)
            }
        }
    }

    private fun navigateToRadarAlert(alert: SideEffectAlert) {
        val result = currentResult ?: return
        val repoRoot = result.filter.repo.rootPath
        val merged = selectedMergedDetail
        val detail = selectedDetail
        val commit = merged?.commitSections?.firstOrNull { it.commit.hash == alert.commitHash }?.commit
            ?: merged?.commitSections?.firstOrNull()?.commit
            ?: selectedCommit
            ?: return
        val fileSummary = detail?.fileDetails?.firstOrNull { it.summary.path == alert.filePath }?.summary
            ?: merged?.commitSections?.firstOrNull { it.fileDetail.summary.path == alert.filePath }?.fileDetail?.summary
            ?: commit.files.firstOrNull { it.path == alert.filePath }
            ?: return
        CodeNavigator.navigateToPointer(
            project = project,
            repoRootPath = repoRoot,
            commit = commit,
            file = fileSummary,
            pointer = alert.hunkPointer
        )
    }

    private fun resetDetailScrollToTop() {
        if (!::outerDetailScrollPane.isInitialized) return
        SwingUtilities.invokeLater {
            outerDetailScrollPane.verticalScrollBar.value = 0
        }
    }

    private fun showPointerContextMenu(node: DefaultMutableTreeNode, e: MouseEvent) {
        val fileDetail: FileDiffDetail?
        val pointer: CodeHunkPointer?
        val overrideCommit: CommitSummary?
        when (val obj = node.userObject) {
            is FileNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.fileDetail.hunks.firstOrNull()
                overrideCommit = obj.overrideCommit
            }
            is MergedCommitGroupNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.fileDetail.hunks.firstOrNull()
                overrideCommit = obj.commit
            }
            is HunkNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.pointer
                overrideCommit = obj.overrideCommit
            }
            is PointerMdNoteNodeData -> {
                fileDetail = obj.fileDetail
                pointer = obj.pointer
                overrideCommit = obj.overrideCommit
            }
            else -> return
        }

        val popup = JPopupMenu()
        if (node.userObject is FileNodeData) {
            popup.add(JMenuItem(CodeTrackerI18n.menuFileHistory()).apply {
                addActionListener { openFileHistoryDialog(fileDetail) }
            })
            popup.addSeparator()
        }
        popup.add(JMenuItem(CodeTrackerI18n.menuDiffCompare()).apply {
            addActionListener { openDiff(fileDetail, pointer, overrideCommit) }
        })
        popup.add(JMenuItem(CodeTrackerI18n.menuJumpToCode()).apply {
            addActionListener { navigateToFileOrHunk(fileDetail, pointer, overrideCommit) }
        })
        popup.addSeparator()
        popup.add(JMenuItem(CodeTrackerI18n.menuCopyCommitMarkdown()).apply {
            addActionListener { copySelectedAsMarkdown() }
        })
        popup.show(pointerTree, e.x, e.y)
    }

    private fun openFileHistoryDialog(fileDetail: FileDiffDetail) {
        openFileHistoryDialogByPath(fileDetail.summary.path)
    }

    private fun openFileHistoryDialogByPath(filePath: String) {
        val repo = currentResult?.filter?.repo ?: (repoCombo.selectedItem as? RepoOption)
        if (repo == null) {
            Messages.showWarningDialog(
                project,
                CodeTrackerI18n.tr("대상 Git 저장소 정보를 찾을 수 없습니다.", "Target Git repository not found."),
                "Maekom Code Tracker"
            )
            return
        }
        val branch = currentResult?.filter?.branch
            ?: (branchCombo.selectedItem as? String)?.ifBlank { "HEAD" }
            ?: "HEAD"
        val (startDate, endDate) = currentResult?.filter?.let { it.startDate to it.endDate }
            ?: parseDateRangeLenient()
        val noMerges = currentResult?.filter?.excludeMerges ?: excludeMergesCheckBox.isSelected

        FileHistoryDialog.showOrUpdate(
            project = project,
            repoRootPath = repo.rootPath,
            branch = branch,
            startDate = startDate,
            endDate = endDate,
            targetFilePath = filePath,
            noMerges = noMerges
        )
    }

    private fun registerRepositoryLifecycleHooks() {
        // 1) Git4Idea / VCS initialization completion hook
        runCatching {
            ProjectLevelVcsManager.getInstance(project).runAfterInitialization {
                initRepositoriesAsync()
            }
        }
        runCatching {
            val gitUtilClass = Class.forName("git4idea.GitUtil")
            val getRepoMgr = gitUtilClass.getMethod("getRepositoryManager", Project::class.java)
            val repoManager = getRepoMgr.invoke(null, project)
            if (repoManager != null) {
                val runWhenInit = repoManager.javaClass.methods.firstOrNull {
                    it.name == "runWhenInitialized" && it.parameterCount == 1
                }
                if (runWhenInit != null) {
                    val paramType = runWhenInit.parameterTypes[0]
                    if (paramType == Runnable::class.java) {
                        runWhenInit.invoke(repoManager, Runnable { initRepositoriesAsync() })
                    } else if (Function0::class.java.isAssignableFrom(paramType)) {
                        runWhenInit.invoke(repoManager, { initRepositoriesAsync() })
                    }
                }
            }
        }

        // 2) Project StartupManager post-open hook
        runCatching {
            StartupManager.getInstance(project).runAfterOpened {
                initRepositoriesAsync()
            }
        }

        // 3) VCS configuration changed message bus listener (disposed automatically with panel)
        runCatching {
            project.messageBus.connect(this).subscribe(
                ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
                VcsListener { initRepositoriesAsync() }
            )
        }
    }

    private fun initRepositoriesAsync() {
        if (project.isDisposed) return
        if (!repoInitInProgress.compareAndSet(false, true)) {
            pendingRepoInit.set(true)
            return
        }

        val prevRepo = repoCombo.selectedItem as? RepoOption
        val prevBranch = branchCombo.selectedItem as? String
        val prevAuthorRaw = (authorCombo.selectedItem as? AuthorOption)?.rawName
        val (startDate, endDate) = parseDateRangeLenient()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repos = GitRepositoryService.discoverRepositories(project)
                val targetRepo = repos.firstOrNull {
                    prevRepo != null &&
                        it.rootPath == prevRepo.rootPath &&
                        it.moduleSubPath == prevRepo.moduleSubPath
                } ?: repos.firstOrNull()

                val branches = if (targetRepo != null) {
                    GitRepositoryService.listBranches(project, targetRepo.rootPath)
                } else {
                    listOf("HEAD")
                }

                val targetBranch = if (prevBranch != null && branches.contains(prevBranch)) {
                    prevBranch
                } else {
                    branches.firstOrNull() ?: "HEAD"
                }

                val authors = if (targetRepo != null) {
                    GitRepositoryService.loadRecentAuthors(
                        project = project,
                        repoRootPath = targetRepo.rootPath,
                        branch = targetBranch,
                        startDate = startDate,
                        endDate = endDate,
                        moduleSubPath = targetRepo.moduleSubPath
                    )
                } else {
                    emptyList()
                }

                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (project.isDisposed) return@invokeLater
                        lastRepoCount = repos.size
                        suppressComboListeners = true

                        repoCombo.model = DefaultComboBoxModel(repos.toTypedArray())
                        if (targetRepo != null) {
                            repoCombo.selectedItem = targetRepo
                            repoCombo.toolTipText = targetRepo.localizedDisplayName()
                        } else {
                            repoCombo.toolTipText = CodeTrackerI18n.repoTooltip()
                        }

                        branchCombo.model = DefaultComboBoxModel(branches.toTypedArray())
                        branchCombo.selectedItem = targetBranch
                        branchCombo.toolTipText = targetBranch

                        val authorItems = (listOf(AuthorOption.ALL) + authors.map { AuthorOption(it) }).toTypedArray()
                        authorCombo.model = DefaultComboBoxModel(authorItems)
                        val preservedAuthor = authorItems.firstOrNull { it.rawName == prevAuthorRaw } ?: AuthorOption.ALL
                        authorCombo.selectedItem = preservedAuthor

                        if (repos.isEmpty() && currentResult == null) {
                            summaryLabel.text = CodeTrackerI18n.noGitRepoFound()
                        } else if (repos.isNotEmpty() && currentResult == null) {
                            summaryLabel.text = CodeTrackerI18n.initialSummaryText()
                        }
                    } finally {
                        suppressComboListeners = false
                    }
                }
            } finally {
                repoInitInProgress.set(false)
                if (pendingRepoInit.getAndSet(false) && !project.isDisposed) {
                    initRepositoriesAsync()
                }
            }
        }
    }

    private fun onDateRangeEdited() {
        val repo = repoCombo.selectedItem as? RepoOption ?: return
        val branch = (branchCombo.selectedItem as? String)?.ifBlank { "HEAD" } ?: "HEAD"
        val start = runCatching { LocalDate.parse(startDateField.text.trim(), DATE_FMT) }.getOrNull() ?: return
        val end = runCatching { LocalDate.parse(endDateField.text.trim(), DATE_FMT) }.getOrNull() ?: return
        if (start.isAfter(end)) return
        loadAuthorsAsync(repo, branch)
    }

    private fun loadBranchesAndAuthorsAsync(repo: RepoOption) {
        val (startDate, endDate) = parseDateRangeLenient()
        val prevBranch = branchCombo.selectedItem as? String
        val prevAuthorRaw = (authorCombo.selectedItem as? AuthorOption)?.rawName

        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = GitRepositoryService.listBranches(project, repo.rootPath)
            val targetBranch = if (prevBranch != null && branches.contains(prevBranch)) {
                prevBranch
            } else {
                branches.firstOrNull() ?: "HEAD"
            }
            val authors = GitRepositoryService.loadRecentAuthors(
                project = project,
                repoRootPath = repo.rootPath,
                branch = targetBranch,
                startDate = startDate,
                endDate = endDate,
                moduleSubPath = repo.moduleSubPath
            )

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val currentRepo = repoCombo.selectedItem as? RepoOption
                if (currentRepo?.rootPath != repo.rootPath || currentRepo.moduleSubPath != repo.moduleSubPath) {
                    return@invokeLater
                }
                suppressComboListeners = true
                try {
                    branchCombo.model = DefaultComboBoxModel(branches.toTypedArray())
                    branchCombo.selectedItem = targetBranch
                    branchCombo.toolTipText = targetBranch

                    val authorItems = (listOf(AuthorOption.ALL) + authors.map { AuthorOption(it) }).toTypedArray()
                    authorCombo.model = DefaultComboBoxModel(authorItems)
                    val preservedAuthor = authorItems.firstOrNull { it.rawName == prevAuthorRaw } ?: AuthorOption.ALL
                    authorCombo.selectedItem = preservedAuthor
                } finally {
                    suppressComboListeners = false
                }
            }
        }
    }

    private fun loadAuthorsAsync(repo: RepoOption, branch: String) {
        val (startDate, endDate) = parseDateRangeLenient()
        val prevAuthorRaw = (authorCombo.selectedItem as? AuthorOption)?.rawName

        ApplicationManager.getApplication().executeOnPooledThread {
            val authors = GitRepositoryService.loadRecentAuthors(
                project = project,
                repoRootPath = repo.rootPath,
                branch = branch,
                startDate = startDate,
                endDate = endDate,
                moduleSubPath = repo.moduleSubPath
            )
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val currentRepo = repoCombo.selectedItem as? RepoOption
                val currentBranch = branchCombo.selectedItem as? String
                if (currentRepo?.rootPath != repo.rootPath ||
                    currentRepo.moduleSubPath != repo.moduleSubPath ||
                    currentBranch != branch
                ) {
                    return@invokeLater
                }
                suppressComboListeners = true
                try {
                    val authorItems = (listOf(AuthorOption.ALL) + authors.map { AuthorOption(it) }).toTypedArray()
                    authorCombo.model = DefaultComboBoxModel(authorItems)
                    val preservedAuthor = authorItems.firstOrNull { it.rawName == prevAuthorRaw } ?: AuthorOption.ALL
                    authorCombo.selectedItem = preservedAuthor
                } finally {
                    suppressComboListeners = false
                }
            }
        }
    }

    private fun parseDateRangeLenient(): Pair<LocalDate, LocalDate> {
        val end = runCatching { LocalDate.parse(endDateField.text.trim(), DATE_FMT) }.getOrElse { LocalDate.now() }
        val start = runCatching { LocalDate.parse(startDateField.text.trim(), DATE_FMT) }.getOrElse { end.minusDays(30) }
        return if (start.isAfter(end)) end to start else start to end
    }

    private fun runPhase1Analysis() {
        val repo = repoCombo.selectedItem as? RepoOption
        if (repo == null) {
            Messages.showWarningDialog(
                project,
                CodeTrackerI18n.tr("분석할 Git 저장소가 선택되지 않았습니다.", "No Git repository selected."),
                "Maekom Code Tracker"
            )
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
                CodeTrackerI18n.tr(
                    "날짜 형식이 올바르지 않습니다. YYYY-MM-DD 형식으로 입력해 주세요.\n(예: 2026-01-01)",
                    "Invalid date format. Please use YYYY-MM-DD.\n(e.g., 2026-01-01)"
                ),
                CodeTrackerI18n.tr("날짜 형식 오류", "Invalid Date Format")
            )
            return
        }

        if (startDate.isAfter(endDate)) {
            Messages.showErrorDialog(
                project,
                CodeTrackerI18n.tr(
                    "시작일($startDate)은 종료일($endDate)보다 이후일 수 없습니다.",
                    "Start date ($startDate) cannot be after end date ($endDate)."
                ),
                CodeTrackerI18n.tr("날짜 범위 오류", "Invalid Date Range")
            )
            return
        }

        val branch = (branchCombo.selectedItem as? String)?.ifBlank { "HEAD" } ?: "HEAD"
        val author = (authorCombo.selectedItem as? AuthorOption)?.rawName
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
        summaryLabel.text = CodeTrackerI18n.tr(
            "1단계 Git 커밋 이력 조회 중 (최대 ${GitRepositoryService.MAX_COMMITS}커밋)...",
            "Stage 1: Fetching Git commit history (up to ${GitRepositoryService.MAX_COMMITS} commits)..."
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            CodeTrackerI18n.tr("Maekom Code Tracker: 커밋 타임라인 분석 중", "Maekom Code Tracker: Analyzing commit timeline"),
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
                        selectedMergedDetail = null

                        if (author == null && refreshedAuthors.isNotEmpty()) {
                            suppressComboListeners = true
                            val items = (listOf(AuthorOption.ALL) + refreshedAuthors.map { AuthorOption(it) }).toTypedArray()
                            authorCombo.model = DefaultComboBoxModel(items)
                            authorCombo.selectedItem = AuthorOption.ALL
                            suppressComboListeners = false
                        }

                        applyClientSideFilter()
                        entrypointsPanel.clear()
                        sideEffectRadarPanel.clear()
                        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                            if (rawCommits.isEmpty()) {
                                CodeTrackerI18n.rationaleNoCommitsPlaceholder()
                            } else {
                                CodeTrackerI18n.rationaleSelectCommitPlaceholder()
                            }
                        )
                        clearPointerTree()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        analyzeButton.isEnabled = true
                        summaryLabel.text = CodeTrackerI18n.tr("분석 오류: ${e.message}", "Analysis error: ${e.message}")
                        Messages.showErrorDialog(
                            project,
                            CodeTrackerI18n.tr(
                                "Git 이력 분석 중 오류가 발생했습니다:\n${e.message}",
                                "An error occurred while analyzing Git history:\n${e.message}"
                            ),
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

        lastFilteredCommits = filteredCommits
        val filteredEpisodes = EpisodeGrouper.groupIntoEpisodes(filteredCommits)
        rebuildTimelineTree(filteredEpisodes)
        rebuildExecutionFlowTree(ArchitectureFlowBuilder.buildExecutionFlow(filteredCommits))
        updateSummaryStrip(filteredCommits, result.filter.repo.rootPath, result.truncatedToLimit)
    }

    private fun rebuildExecutionFlowTree(groups: List<FlowStageGroup>) {
        flowRoot.removeAllChildren()
        for (group in groups) {
            val stageNode = DefaultMutableTreeNode(FlowStageNodeData(group))
            for (subGroup in group.subGroups) {
                val subNode = DefaultMutableTreeNode(FlowSubGroupNodeData(subGroup))
                for (mf in subGroup.files) {
                    subNode.add(DefaultMutableTreeNode(FlowMergedFileNodeData(mf)))
                }
                stageNode.add(subNode)
            }
            flowRoot.add(stageNode)
        }
        flowModel.reload()
        // Expand every level ([Stage] -> [Sub-Depth] -> [Files]) by default
        var row = 0
        while (row < flowTree.rowCount) {
            flowTree.expandRow(row)
            row++
        }
    }

    private fun loadMergedFlowFileOnDemand(mergedFile: FlowMergedFile) {
        val result = currentResult ?: return
        val repoRoot = result.filter.repo.rootPath
        val requestId = mergedLoadSeq.incrementAndGet()
        val cacheKey = "$repoRoot::${mergedFile.path}::${mergedFile.commits.firstOrNull()?.hash.orEmpty()}::${mergedFile.modifyCount}"

        val cached = mergedFileCache[cacheKey]
        if (cached != null) {
            displayMergedFileDetail(cached)
            return
        }

        selectedDetail = null
        selectedMergedDetail = null
        entrypointsPanel.clear()
        sideEffectRadarPanel.clear()
        rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
            CodeTrackerI18n.flowLoadingMergedFile(mergedFile.fileName, mergedFile.commits.size)
        )
        rationalePane.caretPosition = 0
        resetDetailScrollToTop()
        clearPointerTree()

        ApplicationManager.getApplication().executeOnPooledThread {
            val computed = runCatching {
                RationaleAndCheckEngine.analyzeMergedFlowFile(project, repoRoot, mergedFile)
            }.getOrNull()

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || mergedLoadSeq.get() != requestId) return@invokeLater
                if (computed != null) {
                    mergedFileCache[cacheKey] = computed
                    displayMergedFileDetail(computed)
                } else {
                    rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                        CodeTrackerI18n.tr("파일 병합 분석 중 오류가 발생했습니다.", "Error loading merged file analysis.")
                    )
                }
            }
        }
    }

    private fun displayMergedFileDetail(merged: MergedFileAnalysisDetail) {
        selectedDetail = null
        selectedMergedDetail = merged
        selectedCommit = merged.commitSections.firstOrNull()?.commit
        entrypointsPanel.updateTrace(merged.entrypointTrace)
        sideEffectRadarPanel.updateAlerts(merged.sideEffectAlerts)
        rationalePane.text = RationaleHtmlRenderer.renderMergedFileDetail(merged)
        rationalePane.caretPosition = 0
        resetDetailScrollToTop()
        rebuildPointerTreeForMergedFile(merged)
    }

    private fun rebuildPointerTreeForMergedFile(merged: MergedFileAnalysisDetail) {
        pointerRoot.removeAllChildren()
        for (sec in merged.commitSections) {
            val commitGroupNode = DefaultMutableTreeNode(MergedCommitGroupNodeData(sec.commit, sec.fileDetail))
            val fd = sec.fileDetail
            when {
                fd.skippedLargeOrBinary -> {
                    commitGroupNode.add(DefaultMutableTreeNode(fd.skipReason ?: CodeTrackerI18n.defaultSkipText()))
                }
                fd.onlyImportOrWhitespaceChanges -> {
                    commitGroupNode.add(DefaultMutableTreeNode(CodeTrackerI18n.importWhitespaceOnlyText()))
                }
                else -> {
                    for (hunk in fd.hunks) {
                        commitGroupNode.add(DefaultMutableTreeNode(HunkNodeData(fd, hunk, overrideCommit = sec.commit)))
                    }
                }
            }
            pointerRoot.add(commitGroupNode)
        }
        pointerModel.reload()
        var row = 0
        while (row < pointerTree.rowCount) {
            pointerTree.expandRow(row)
            row++
        }
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
        var row = 0
        while (row < timelineTree.rowCount) {
            timelineTree.expandRow(row)
            row++
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

        val stackCounts = linkedMapOf<StackTag, Int>()
        for (c in commits) {
            val detail = detailCache["$repoRootPath::${c.hash}"] ?: continue
            for (tag in detail.allStackTags) {
                stackCounts[tag] = (stackCounts[tag] ?: 0) + 1
            }
        }
        val stackStr = if (stackCounts.isNotEmpty()) {
            stackCounts.entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key.label}(${it.value})" }
        } else {
            ""
        }

        val baseSummary = CodeTrackerI18n.summaryStrip(
            totalCommits = totalCommits,
            totalFiles = totalFiles,
            truncated = truncated,
            maxCommits = GitRepositoryService.MAX_COMMITS,
            layerRatio = layerRatioStr,
            stackPart = stackStr
        )

        val sortedIssues = aggregateIssueCounts(commits)
        if (sortedIssues.isEmpty()) {
            summaryLabel.text = baseSummary
            summaryLabel.toolTipText = null
        } else {
            val visiblePart = sortedIssues.take(MAX_DISPLAYED_ISSUES)
                .joinToString(" | ") { (key, count) -> "$key($count)" }
            val extraCount = sortedIssues.size - MAX_DISPLAYED_ISSUES
            val issuePart = if (extraCount > 0) {
                "$visiblePart | ${CodeTrackerI18n.tr("+${extraCount}건", "+$extraCount more")}"
            } else {
                visiblePart
            }
            summaryLabel.text = "$baseSummary || $issuePart"

            val fullIssuesHtml = sortedIssues.joinToString("<br/>") { (key, count) ->
                "<b>$key</b>: ${CodeTrackerI18n.commitsCountUnit(count)}"
            }
            val tooltipTitle = CodeTrackerI18n.tr(
                "조회 기간 내 이슈별 커밋 집계 (총 ${sortedIssues.size}개 이슈)",
                "Issue Commit Summary in Period (${sortedIssues.size} issues)"
            )
            summaryLabel.toolTipText = "<html><body><b>$tooltipTitle</b><br/>$fullIssuesHtml</body></html>"
        }
    }

    private fun aggregateIssueCounts(commits: List<CommitSummary>): List<Map.Entry<String, Int>> {
        val counts = HashMap<String, Int>()
        for (commit in commits) {
            val text = "${commit.subject}\n${commit.body}"
            val uniqueKeysInCommit = ISSUE_KEY_REGEX.findAll(text)
                .map { it.groupValues[1].uppercase() }
                .filter { it !in ISSUE_KEY_BLACKLIST }
                .distinct()
            for (key in uniqueKeysInCommit) {
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )
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

        entrypointsPanel.clear()
        sideEffectRadarPanel.clear()
        rationalePane.text = RationaleHtmlRenderer.renderLoading(commit)
        rationalePane.caretPosition = 0
        resetDetailScrollToTop()
        clearPointerTree()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            CodeTrackerI18n.tr(
                "Maekom Code Tracker: 커밋 상세 분석 (${commit.shortHash})",
                "Maekom Code Tracker: Analyzing commit detail (${commit.shortHash})"
            ),
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
                        if (selectedCommit?.hash == commit.hash) {
                            displayCommitDetail(detail)
                        }
                        updateSummaryStrip(lastFilteredCommits.ifEmpty { result.commits }, repoRootPath, result.truncatedToLimit)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (selectedCommit?.hash == commit.hash) {
                            rationalePane.text = RationaleHtmlRenderer.renderPlaceholder(
                                CodeTrackerI18n.tr(
                                    "상세 분석 중 오류가 발생했습니다: ${e.message}",
                                    "Error analyzing commit detail: ${e.message}"
                                )
                            )
                        }
                    }
                }
            }
        })
    }

    private fun displayCommitDetail(detail: CommitDetail) {
        selectedMergedDetail = null
        selectedDetail = detail
        entrypointsPanel.updateTrace(detail.entrypointTrace)
        sideEffectRadarPanel.updateAlerts(detail.sideEffectAlerts)
        rationalePane.text = RationaleHtmlRenderer.renderCommitDetail(detail)
        rationalePane.caretPosition = 0
        resetDetailScrollToTop()
        rebuildPointerTree(detail)
    }

    private fun clearPointerTree() {
        pointerRoot.removeAllChildren()
        pointerModel.reload()
    }

    private fun rebuildPointerTree(detail: CommitDetail) {
        populatePointerTreeNodes(detail)
        pointerModel.reload()
        var row = 0
        while (row < pointerTree.rowCount) {
            pointerTree.expandRow(row)
            row++
        }
    }

    private fun rebuildPointerTreePreservingState(detail: CommitDetail) {
        // Capture currently collapsed layer/file keys and selected node key
        val collapsedLayers = mutableSetOf<PrimaryLayer>()
        val collapsedFiles = mutableSetOf<String>()
        for (i in 0 until pointerRoot.childCount) {
            val layerNode = pointerRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val layerObj = layerNode.userObject as? LayerGroupNodeData ?: continue
            val layerPath = TreePath(layerNode.path)
            if (!pointerTree.isExpanded(layerPath)) {
                collapsedLayers.add(layerObj.layer)
            }
            for (j in 0 until layerNode.childCount) {
                val fileNode = layerNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                val fileObj = fileNode.userObject as? FileNodeData ?: continue
                if (!pointerTree.isExpanded(TreePath(fileNode.path))) {
                    collapsedFiles.add(fileObj.fileDetail.summary.path)
                }
            }
        }
        val selectedUserObj = (pointerTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

        populatePointerTreeNodes(detail)
        pointerModel.reload()

        var pathToSelect: TreePath? = null
        for (i in 0 until pointerRoot.childCount) {
            val layerNode = pointerRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val layerObj = layerNode.userObject as? LayerGroupNodeData ?: continue
            val layerPath = TreePath(layerNode.path)
            if (selectedUserObj is LayerGroupNodeData && selectedUserObj.layer == layerObj.layer) {
                pathToSelect = layerPath
            }
            if (layerObj.layer !in collapsedLayers) {
                pointerTree.expandPath(layerPath)
            }
            for (j in 0 until layerNode.childCount) {
                val fileNode = layerNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                val fileObj = fileNode.userObject as? FileNodeData ?: continue
                val filePath = TreePath(fileNode.path)
                if (selectedUserObj is FileNodeData && selectedUserObj.fileDetail.summary.path == fileObj.fileDetail.summary.path) {
                    pathToSelect = filePath
                }
                if (fileObj.fileDetail.summary.path !in collapsedFiles) {
                    pointerTree.expandPath(filePath)
                }
                for (k in 0 until fileNode.childCount) {
                    val hunkNode = fileNode.getChildAt(k) as? DefaultMutableTreeNode ?: continue
                    val hunkPath = TreePath(hunkNode.path)
                    pointerTree.expandPath(hunkPath)
                    val hObj = hunkNode.userObject
                    if (selectedUserObj is HunkNodeData && hObj is HunkNodeData &&
                        hObj.fileDetail.summary.path == selectedUserObj.fileDetail.summary.path &&
                        hObj.pointer.newStartLine == selectedUserObj.pointer.newStartLine
                    ) {
                        pathToSelect = hunkPath
                    }
                    for (m in 0 until hunkNode.childCount) {
                        val noteNode = hunkNode.getChildAt(m) as? DefaultMutableTreeNode ?: continue
                        val nObj = noteNode.userObject
                        if (selectedUserObj is PointerMdNoteNodeData && nObj is PointerMdNoteNodeData &&
                            nObj.fileDetail.summary.path == selectedUserObj.fileDetail.summary.path &&
                            nObj.pointer.newStartLine == selectedUserObj.pointer.newStartLine
                        ) {
                            pathToSelect = TreePath(noteNode.path)
                        }
                    }
                }
            }
        }
        if (pathToSelect != null) {
            pointerTree.selectionPath = pathToSelect
        }
    }

    private fun populatePointerTreeNodes(detail: CommitDetail) {
        pointerRoot.removeAllChildren()
        val grouped = detail.fileDetails.groupBy { it.summary.primaryLayer }
        for ((layer, files) in grouped.entries.sortedBy { it.key.flowOrder }) {
            val layerNode = DefaultMutableTreeNode(LayerGroupNodeData(layer, files.size))
            for (fd in files) {
                val fileNode = DefaultMutableTreeNode(FileNodeData(fd))
                when {
                    fd.skippedLargeOrBinary -> {
                        fileNode.add(DefaultMutableTreeNode(fd.skipReason ?: CodeTrackerI18n.defaultSkipText()))
                    }
                    fd.onlyImportOrWhitespaceChanges -> {
                        fileNode.add(DefaultMutableTreeNode(CodeTrackerI18n.importWhitespaceOnlyText()))
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
    }

    private fun navigateToFileOrHunk(
        fileDetail: FileDiffDetail,
        pointer: CodeHunkPointer?,
        overrideCommit: CommitSummary? = null
    ) {
        val result = currentResult ?: return
        val commit = overrideCommit ?: selectedCommit ?: return
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
        val triple: Triple<FileDiffDetail?, CodeHunkPointer?, CommitSummary?> = when (val obj = node?.userObject) {
            is FileNodeData -> Triple(obj.fileDetail, obj.fileDetail.hunks.firstOrNull(), obj.overrideCommit)
            is MergedCommitGroupNodeData -> Triple(obj.fileDetail, obj.fileDetail.hunks.firstOrNull(), obj.commit)
            is HunkNodeData -> Triple(obj.fileDetail, obj.pointer, obj.overrideCommit)
            is PointerMdNoteNodeData -> Triple(obj.fileDetail, obj.pointer, obj.overrideCommit)
            else -> {
                val firstMerged = selectedMergedDetail?.commitSections?.firstOrNull()
                if (firstMerged != null) {
                    Triple(firstMerged.fileDetail, firstMerged.fileDetail.hunks.firstOrNull(), firstMerged.commit)
                } else {
                    val first = selectedDetail?.fileDetails?.firstOrNull()
                    Triple(first, first?.hunks?.firstOrNull(), null)
                }
            }
        }
        val fileDetail = triple.first
        if (fileDetail == null) {
            Messages.showInfoMessage(
                project,
                CodeTrackerI18n.tr("Diff를 비교할 파일을 먼저 선택해 주세요.", "Please select a file to compare Diff."),
                "Maekom Code Tracker"
            )
            return
        }
        openDiff(fileDetail, triple.second, triple.third)
    }

    private fun openDiff(
        fileDetail: FileDiffDetail,
        targetPointer: CodeHunkPointer? = null,
        overrideCommit: CommitSummary? = null
    ) {
        val result = currentResult ?: return
        val commit = overrideCommit ?: selectedCommit ?: return
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
        PluginUpdateChecker.ensureCustomPluginRepoRegistered()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            CodeTrackerI18n.tr("Maekom Code Tracker: 최신 버전 확인 중", "Maekom Code Tracker: Checking for updates"),
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
                            Messages.showInfoMessage(
                                project,
                                CodeTrackerI18n.updateAvailableMessage(result.currentVersion, result.latestVersion),
                                CodeTrackerI18n.updateAvailableTitle()
                            )
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                PluginManagerConfigurable::class.java
                            )
                        }

                        is UpdateCheckResult.UpToDate -> {
                            Messages.showInfoMessage(
                                project,
                                CodeTrackerI18n.updateUpToDateMessage(result.currentVersion),
                                CodeTrackerI18n.updateUpToDateTitle()
                            )
                        }

                        is UpdateCheckResult.RepoNotPublishedYet -> {
                            Messages.showInfoMessage(
                                project,
                                result.message,
                                CodeTrackerI18n.updateRepoRegisteredTitle()
                            )
                        }

                        is UpdateCheckResult.Error -> {
                            Messages.showWarningDialog(
                                project,
                                result.message,
                                CodeTrackerI18n.updateFailedTitle()
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
                CodeTrackerI18n.tr(
                    "복사할 커밋 또는 작업 에피소드를 먼저 선택해 주세요.",
                    "Please select a commit or work episode to copy."
                ),
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
                    append(" (${CodeTrackerI18n.commitsCountUnit(ep.commits.size)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (ep.hasMarkdownDocs) {
                        append(
                            "  [.md ${ep.markdownFiles.size}]",
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x175CD3), Color(0x82B1FF)))
                        )
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
                        append(
                            "  $layerBadges",
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(Color(0x0E7490), Color(0x67E8F9)))
                        )
                    }
                    if (c.hasMarkdownChanges || obj.episode.hasMarkdownDocs) {
                        append(
                            "  📄.md",
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(Color(0x7E22CE), Color(0xC084FC)))
                        )
                    }
                }

                else -> append(obj?.toString().orEmpty())
            }
        }
    }

    // Custom renderer for 6-Stage + 2-Level Sub-Depth Execution Flow Tree
    private inner class ExecutionFlowTreeCellRenderer : ColoredTreeCellRenderer() {
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
                is FlowStageNodeData -> {
                    icon = AllIcons.Nodes.Module
                    append(obj.group.stage.localizedTitle(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${CodeTrackerI18n.filesCountUnit(obj.group.totalFileCount)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is FlowSubGroupNodeData -> {
                    icon = AllIcons.Nodes.Package
                    append(obj.subGroup.subCategory.displayLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${obj.subGroup.files.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is FlowMergedFileNodeData -> {
                    val mf = obj.mergedFile
                    icon = AllIcons.FileTypes.Any_type
                    append(mf.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(
                        "  ${CodeTrackerI18n.flowModifiedBadge(mf.modifyCount, mf.totalAddedLines, mf.totalDeletedLines)}",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x0E7490), Color(0x67E8F9)))
                    )
                    append("  ${mf.path}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
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
                    append(" (${CodeTrackerI18n.filesCountUnit(obj.fileCount)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is MergedCommitGroupNodeData -> {
                    val c = obj.commit
                    val fd = obj.fileDetail
                    icon = AllIcons.Vcs.CommitNode
                    append("[${c.shortHash}] ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(Color(0x1D5FD6), Color(0x6B9BFA))))
                    append(c.subject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${c.authorName}, +${fd.addedLinesTotal}/-${fd.deletedLinesTotal})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
