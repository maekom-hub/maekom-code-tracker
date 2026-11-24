package com.maekom.codetracker.ui

import com.intellij.icons.AllIcons
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
import com.maekom.codetracker.update.PluginUpdateChecker
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CodeTrackerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodeTrackerPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(VersionAndUpdateTitleAction(panel)))
    }

    private class VersionAndUpdateTitleAction(
        private val panel: CodeTrackerPanel
    ) : DumbAwareAction("GitHub 최신 릴리즈 확인", "GitHub 최신 릴리즈 확인", AllIcons.Actions.Refresh),
        CustomComponentAction {

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

            val updateButton = JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "GitHub 최신 릴리즈 확인"
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

            return JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 0, 2)
                add(versionLabel)
                add(updateButton)
            }
        }
    }
}
