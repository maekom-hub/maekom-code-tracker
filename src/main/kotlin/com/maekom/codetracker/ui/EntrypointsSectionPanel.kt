package com.maekom.codetracker.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.EntrypointTarget
import com.maekom.codetracker.model.EntrypointTraceResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Native Swing line-by-line Impacted Entrypoints card placed inside the unified detail scroll pane.
 * - 1..4 items: exact content height, inner vertical scrollbar hidden, wheel always bubbles to outer scroll pane.
 * - 5+ items: fixed 4-row height with inner vertical scrollbar, wheel bubbles to outer scroll pane at top/bottom edges.
 * - 0 items (and not DumbMode): completely hidden (`isVisible = false`) with zero empty space.
 */
class EntrypointsSectionPanel(
    private val project: Project,
    private val outerScrollPaneProvider: () -> JScrollPane? = { null }
) : JPanel(BorderLayout()) {

    companion object {
        private const val MAX_VISIBLE_ROWS = 4
    }

    private var currentTrace: EntrypointTraceResult? = null
    private var currentEntrypointCount: Int = 0

    private val titleLabel = JBLabel().apply {
        font = (UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()).let {
            it.deriveFont(Font.BOLD, (it.size2D - 1f).coerceAtLeast(11f))
        }
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 6, 2, 6)
    }

    private val rowsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val scrollPane = JBScrollPane(rowsContainer).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        isOpaque = false
        viewport.isOpaque = false
    }

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        background = JBColor(Color(0xF7F9FC), Color(0x282A2E))
        add(titleLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Bubble mouse wheel events seamlessly to the outer unified detail scroll pane
        scrollPane.addMouseWheelListener { e ->
            val outer = outerScrollPaneProvider() ?: return@addMouseWheelListener
            val bar = scrollPane.verticalScrollBar
            val atTop = bar.value <= bar.minimum
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum
            val shouldBubble = currentEntrypointCount <= MAX_VISIBLE_ROWS ||
                (e.wheelRotation < 0 && atTop) ||
                (e.wheelRotation > 0 && atBottom)
            if (shouldBubble) {
                val converted = SwingUtilities.convertMouseEvent(scrollPane, e, outer)
                outer.dispatchEvent(converted)
                e.consume()
            }
        }
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    fun updateTrace(trace: EntrypointTraceResult?) {
        currentTrace = trace
        render()
    }

    fun clear() {
        currentTrace = null
        currentEntrypointCount = 0
        rowsContainer.removeAll()
        isVisible = false
        revalidate()
        repaint()
    }

    fun refreshLocalization() {
        render()
    }

    private fun render() {
        val trace = currentTrace
        rowsContainer.removeAll()

        if (trace == null || (!trace.isDumbMode && trace.entrypoints.isEmpty())) {
            currentEntrypointCount = 0
            isVisible = false
            revalidate()
            repaint()
            return
        }

        val headerText = if (trace.truncatedByLimit) {
            "${CodeTrackerI18n.impactedEntrypointsTitle()}  ${CodeTrackerI18n.entrypointTruncatedHint()}"
        } else {
            CodeTrackerI18n.impactedEntrypointsTitle()
        }
        titleLabel.text = headerText

        val baseFont = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        val rowFont = baseFont.deriveFont(Font.PLAIN, baseFont.size2D.coerceAtMost(12f))
        val badgeFont = rowFont.deriveFont(Font.BOLD)
        val rowHeight = JBUI.scale(24)
        val verticalPadding = JBUI.scale(4)

        val scrollHeight: Int
        if (trace.isDumbMode) {
            currentEntrypointCount = 0
            val notice = JBLabel(CodeTrackerI18n.entrypointDumbModeNotice()).apply {
                font = rowFont
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(2, 8, 5, 8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            rowsContainer.add(notice)
            scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            scrollHeight = JBUI.scale(26)
        } else {
            currentEntrypointCount = trace.entrypoints.size
            for ((idx, ep) in trace.entrypoints.withIndex()) {
                val isLast = idx == trace.entrypoints.lastIndex
                rowsContainer.add(createEntrypointRow(ep, badgeFont, rowFont, rowHeight, isLast))
            }
            if (currentEntrypointCount <= MAX_VISIBLE_ROWS) {
                scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                scrollHeight = currentEntrypointCount * rowHeight + verticalPadding
            } else {
                scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                scrollHeight = MAX_VISIBLE_ROWS * rowHeight + verticalPadding
            }
        }

        scrollPane.preferredSize = Dimension(0, scrollHeight)
        scrollPane.minimumSize = Dimension(0, scrollHeight)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, scrollHeight)

        val totalHeight = titleLabel.preferredSize.height + scrollHeight + JBUI.scale(2)
        preferredSize = Dimension(0, totalHeight)
        minimumSize = Dimension(0, totalHeight)
        maximumSize = Dimension(Int.MAX_VALUE, totalHeight)

        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }

        isVisible = true
        revalidate()
        repaint()
    }

    private fun createEntrypointRow(
        ep: EntrypointTarget,
        badgeFont: Font,
        reasonFont: Font,
        rowHeight: Int,
        isLast: Boolean
    ): JPanel {
        val defaultBg = JBColor(Color(0xF7F9FC), Color(0x282A2E))
        val hoverBg = JBColor(Color(0xE8F1FE), Color(0x313E52))
        val accentBlue = JBColor(Color(0x1D5FD6), Color(0x6B9BFA))
        val reasonColor = UIUtil.getContextHelpForeground()
        val tooltipHtml = buildRowTooltipHtml(ep)

        val leftLabel = JBLabel(ep.displayLabel).apply {
            font = badgeFont
            foreground = accentBlue
            toolTipText = tooltipHtml
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val reasonText = ep.localizedImpactReason()
        val rightLabel = JBLabel(reasonText).apply {
            font = reasonFont
            foreground = reasonColor
            toolTipText = tooltipHtml
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            minimumSize = Dimension(0, rowHeight - JBUI.scale(4))
        }

        val row = object : JPanel(BorderLayout(8, 0)) {
            override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, rowHeight)
            override fun getMinimumSize(): Dimension = Dimension(0, rowHeight)
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, rowHeight)
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = true
            background = defaultBg
            border = JBUI.Borders.compound(
                if (isLast) JBUI.Borders.empty() else JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(2, 8, 2, 8)
            )
            toolTipText = tooltipHtml
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(leftLabel, BorderLayout.WEST)
            add(rightLabel, BorderLayout.CENTER)
        }

        val clickAndHover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                row.background = hoverBg
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent?) {
                row.background = defaultBg
                row.repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    CodeNavigator.navigateToAbsoluteFileLine(project, ep.filePath, ep.lineNumber1Based)
                }
            }
        }
        row.addMouseListener(clickAndHover)
        leftLabel.addMouseListener(clickAndHover)
        rightLabel.addMouseListener(clickAndHover)

        return row
    }

    private fun buildRowTooltipHtml(ep: EntrypointTarget): String {
        val chainText = if (ep.callChain.isNotEmpty()) {
            ep.callChain.joinToString(" -> ")
        } else {
            "${ep.className}.${ep.methodName}()"
        }
        val reasonText = ep.localizedImpactReason()
        return buildString {
            append("<html><body style='width: 380px;'>")
            append("<b>").append(escapeHtml(ep.displayLabel)).append("</b>")
            append("<br/><br/>")
            append("<b>").append(escapeHtml(CodeTrackerI18n.entrypointTooltipCallChain())).append(":</b><br/>")
            append(escapeHtml(chainText))
            if (reasonText.isNotBlank()) {
                append("<br/><br/>")
                append("<b>").append(escapeHtml(CodeTrackerI18n.entrypointTooltipReason())).append(":</b><br/>")
                append(escapeHtml(reasonText))
            }
            append("<br/><br/><i>").append(escapeHtml(CodeTrackerI18n.entrypointTooltipJumpHint())).append("</i>")
            append("</body></html>")
        }
    }

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
