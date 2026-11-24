package com.maekom.codetracker.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.update.PluginUpdateChecker
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CodeTrackerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodeTrackerPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(VersionAndUpdateTitleAction(panel, toolWindow.disposable)))
    }

    /**
     * Title bar composite action displaying:
     * [v0.1.8]  [KO/EN]  [Refresh/Check Updates]
     */
    private class VersionAndUpdateTitleAction(
        private val panel: CodeTrackerPanel,
        private val parentDisposable: Disposable
    ) : DumbAwareAction(
        CodeTrackerI18n.updateCheckTooltip(),
        CodeTrackerI18n.updateCheckTooltip(),
        AllIcons.Actions.Refresh
    ), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            panel.triggerUpdateCheck()
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val versionLabel = JBLabel("v${PluginUpdateChecker.getCurrentVersion()}").apply {
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
                border = JBUI.Borders.empty(0, 2, 0, 2)
            }

            val langToggleButton = JButton(CodeTrackerI18n.languageCode()).apply {
                toolTipText = CodeTrackerI18n.langToggleTooltip()
                font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                foreground = JBColor(Color(0x1D5FD6), Color(0x6B9BFA))
                isFocusable = false
                isContentAreaFilled = false
                isBorderPainted = true
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(0, 4, 0, 4)
                )
                margin = JBUI.emptyInsets()
                preferredSize = JBUI.size(30, 18)
                minimumSize = JBUI.size(30, 18)
                maximumSize = JBUI.size(34, 18)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    CodeTrackerI18n.toggleLanguage()
                }
            }

            val updateButton = JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = CodeTrackerI18n.updateCheckTooltip()
                isFocusable = false
                isContentAreaFilled = false
                isBorderPainted = false
                isOpaque = false
                margin = JBUI.emptyInsets()
                preferredSize = JBUI.size(22, 22)
                minimumSize = JBUI.size(22, 22)
                maximumSize = JBUI.size(22, 22)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    panel.triggerUpdateCheck(versionLabel, this)
                }
            }

            // Live-update title bar controls when KO/EN toggles
            CodeTrackerI18n.addListener(parentDisposable) {
                langToggleButton.text = CodeTrackerI18n.languageCode()
                langToggleButton.toolTipText = CodeTrackerI18n.langToggleTooltip()
                updateButton.toolTipText = CodeTrackerI18n.updateCheckTooltip()
            }

            return JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 0, 2)
                add(versionLabel)
                add(langToggleButton)
                add(updateButton)
            }
        }
    }
}
