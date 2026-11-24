package com.maekom.codetracker.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.RadarSeverity
import com.maekom.codetracker.model.SideEffectAlert
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Scrollable vertical container for the unified detail JBScrollPane.
 * Tracks viewport width (`true`) so child blocks wrap cleanly with zero horizontal clipping,
 * while scrolling vertically (`false`).
 */
class ScrollableDetailContainer : JPanel(), Scrollable {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        isOpaque = true
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height.coerceAtLeast(JBUI.scale(60))
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/**
 * Block 2 inside the unified detail scroll pane: Side-Effect Risk Radar card.
 * Completely hidden (`isVisible = false`) when 0 alerts exist.
 * - Left-click: Opens Before / After commit Diff scrolled to the exact risk line (Side.LEFT / Side.RIGHT).
 * - Right-click: Context menu to jump to the matching code snippet in the current editor.
 */
class SideEffectRadarPanel(
    private val onAlertLeftClick: (SideEffectAlert) -> Unit,
    private val onAlertJumpToEditor: (SideEffectAlert) -> Unit
) : JPanel(BorderLayout()) {

    private var currentAlerts: List<SideEffectAlert> = emptyList()

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
        border = JBUI.Borders.empty(0, 6, 4, 6)
    }

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        background = UIUtil.getPanelBackground()
        add(titleLabel, BorderLayout.NORTH)
        add(rowsContainer, BorderLayout.CENTER)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    fun updateAlerts(alerts: List<SideEffectAlert>) {
        currentAlerts = alerts
        render()
    }

    fun clear() {
        currentAlerts = emptyList()
        rowsContainer.removeAll()
        isVisible = false
        revalidate()
        repaint()
    }

    fun refreshLocalization() {
        render()
    }

    private fun render() {
        rowsContainer.removeAll()
        if (currentAlerts.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }

        titleLabel.text = CodeTrackerI18n.sideEffectRadarTitle()
        val baseFont = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        val textFont = baseFont.deriveFont(Font.PLAIN, baseFont.size2D.coerceAtMost(12f))

        for (alert in currentAlerts) {
            rowsContainer.add(createAlertRow(alert, textFont))
        }

        isVisible = true
        revalidate()
        repaint()
    }

    private fun createAlertRow(alert: SideEffectAlert, textFont: Font): JPanel {
        val (bgColor, badgeColor, borderColor) = when (alert.severity) {
            RadarSeverity.HIGH -> Triple(
                JBColor(Color(0xFDECEC), Color(0x4A2222)),
                JBColor(Color(0xC62828), Color(0xFF7B72)),
                JBColor(Color(0xF5B5B5), Color(0x7A3333))
            )
            RadarSeverity.WARN -> Triple(
                JBColor(Color(0xFFF4E5), Color(0x47361C)),
                JBColor(Color(0xC25E00), Color(0xFFB74D)),
                JBColor(Color(0xF5D099), Color(0x7A5A28))
            )
        }
        val tooltip = CodeTrackerI18n.radarItemTooltip()
        val sideTag = if (alert.preferLeftSide) "Before" else "After"

        val badgeLabel = JBLabel(
            "${alert.severity.localizedBadge()} ${alert.rule.localizedTitle()} (${alert.fileName}:${alert.displayLineNumber()} $sideTag)"
        ).apply {
            font = textFont.deriveFont(Font.BOLD)
            foreground = badgeColor
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val detailArea = JTextArea(alert.localizedDetail()).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = textFont
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(1, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = true
            background = bgColor
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(0, 0, 3, 0),
                JBUI.Borders.compound(
                    JBUI.Borders.customLine(borderColor, 0, 3, 0, 0),
                    JBUI.Borders.empty(3, 6, 3, 6)
                )
            )
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(badgeLabel)
            add(detailArea)
        }

        val mouseListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    onAlertLeftClick(alert)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val popup = JPopupMenu()
                popup.add(JMenuItem(CodeTrackerI18n.menuDiffCompare()).apply {
                    addActionListener { onAlertLeftClick(alert) }
                })
                popup.add(JMenuItem(CodeTrackerI18n.menuJumpToCurrentEditor()).apply {
                    addActionListener { onAlertJumpToEditor(alert) }
                })
                popup.show(e.component, e.x, e.y)
            }
        }
        row.addMouseListener(mouseListener)
        badgeLabel.addMouseListener(mouseListener)
        detailArea.addMouseListener(mouseListener)

        return row
    }
}
