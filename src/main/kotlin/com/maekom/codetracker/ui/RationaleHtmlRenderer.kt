package com.maekom.codetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maekom.codetracker.analysis.RationaleAndCheckEngine
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.BadgeSeverity
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import java.awt.Color
import java.time.format.DateTimeFormatter
import javax.swing.UIManager

/**
 * Generates clean, theme-aware local HTML for the Right-Top
 * [Change Reason & Architecture Check] panel. Dynamically honors current KO/EN language
 * with Tree/Label base font sizing (12px body, 13px bold title) and zero external links.
 */
object RationaleHtmlRenderer {

    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun baseFontFamily(): String {
        val treeFont = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        return escapeHtml(treeFont.family)
    }

    private fun commonStyleBlock(): String {
        val fontFamily = baseFontFamily()
        val fg = toHex(UIUtil.getLabelForeground())
        val bg = toHex(UIUtil.getPanelBackground())
        return """
            <style>
                body { font-family: '$fontFamily', sans-serif; font-size: 12px; color: $fg; background-color: $bg; margin: 6px; }
                .title { font-size: 13px; font-weight: bold; }
                .meta { font-size: 11px; }
                .sec-label { font-size: 11px; font-weight: bold; margin-bottom: 2px; }
                code { font-size: 11px; }
                ul { margin: 2px 0 2px 15px; padding: 0; }
                li { margin-bottom: 1px; }
            </style>
        """.trimIndent()
    }

    fun renderPlaceholder(message: String): String {
        val muted = toHex(UIUtil.getContextHelpForeground())
        return """
            <html><head>${commonStyleBlock()}</head>
            <body><div style="color: $muted;">${escapeHtml(message)}</div></body></html>
        """.trimIndent()
    }

    fun renderLoading(commit: CommitSummary): String {
        val muted = toHex(UIUtil.getContextHelpForeground())
        return """
            <html><head>${commonStyleBlock()}</head>
            <body>
            <div class="title">[${escapeHtml(commit.shortHash)}] ${escapeHtml(commit.subject)}</div>
            <div style="color: $muted; margin-top: 4px;">${escapeHtml(CodeTrackerI18n.loadingCommitDetailText())}</div>
            </body></html>
        """.trimIndent()
    }

    fun renderCommitDetail(detail: CommitDetail): String {
        val c = detail.summary
        val fg = toHex(UIUtil.getLabelForeground())
        val muted = toHex(UIUtil.getContextHelpForeground())
        val cardBg = toHex(JBColor(Color(0xF5F7FA), Color(0x2B2D30)))
        val borderColor = toHex(JBColor(Color(0xD8DEE9), Color(0x43454A)))
        val accentBlue = toHex(JBColor(Color(0x1D5FD6), Color(0x6B9BFA)))
        val flowBg = toHex(JBColor(Color(0xEBF3FF), Color(0x243347)))

        // Recompute cross-layer flow summary dynamically for instant KO/EN hot-reload on cached CommitDetail
        val localizedFlowSummary = RationaleAndCheckEngine.buildCrossLayerFlowSummary(
            detail.summary,
            detail.fileDetails,
            detail.allStackTags
        )

        return buildString {
            append("<html><head>${commonStyleBlock()}</head><body>")

            // Header
            append("<div class=\"title\" style=\"margin-bottom: 3px;\">")
            append("<span style=\"color: $accentBlue;\">[${escapeHtml(c.shortHash)}]</span> ")
            append(escapeHtml(c.subject))
            append("</div>")

            append("<div class=\"meta\" style=\"color: $muted; margin-bottom: 5px;\">")
            append("${escapeHtml(CodeTrackerI18n.tr("작성자", "Author"))}: <b>${escapeHtml(c.authorName)}</b>")
            if (c.authorEmail.isNotBlank()) {
                append(" &lt;${escapeHtml(c.authorEmail)}&gt;")
            }
            append(" &nbsp;|&nbsp; ${escapeHtml(CodeTrackerI18n.tr("시각", "Time"))}: ${c.timestamp.format(DT_FMT)}")
            if (detail.issueTags.isNotEmpty()) {
                append(" &nbsp;|&nbsp; ${escapeHtml(CodeTrackerI18n.tr("이슈", "Issues"))}: ")
                detail.issueTags.forEach { tag ->
                    append("<span style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 0px 3px;\"><b>${escapeHtml(tag)}</b></span> ")
                }
            }
            append("</div>")

            // 5) Cross-layer flow summary (slim padding)
            append("<div style=\"background-color: $flowBg; border-left: 3px solid $accentBlue; padding: 3px 6px; margin-bottom: 5px;\">")
            append("<b>${escapeHtml(localizedFlowSummary)}</b>")
            append("</div>")

            // 4) Architecture check point badges (slim padding)
            if (detail.checkBadges.isNotEmpty()) {
                append("<div style=\"margin-bottom: 5px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("현업 아키텍처 체킹 포인트", "Architecture Check Points"))}</div>")
                for (badge in detail.checkBadges) {
                    val (bBg, bFg) = when (badge.severity) {
                        BadgeSeverity.HIGH -> toHex(JBColor(Color(0xFDECEC), Color(0x4A2525))) to toHex(JBColor(Color(0xB42318), Color(0xFF8A80)))
                        BadgeSeverity.MEDIUM -> toHex(JBColor(Color(0xFEF4E5), Color(0x47381E))) to toHex(JBColor(Color(0xB54708), Color(0xFFCA7A)))
                        BadgeSeverity.INFO -> toHex(JBColor(Color(0xEBF5FF), Color(0x21364A))) to toHex(JBColor(Color(0x175CD3), Color(0x82B1FF)))
                    }
                    append("<div style=\"background-color: $bBg; color: $bFg; padding: 2px 5px; margin-bottom: 2px;\">")
                    append("<b>${escapeHtml(badge.localizedLabel())}</b> <span style=\"color: $fg;\">${escapeHtml(badge.localizedDescription())}</span>")
                    append("</div>")
                }
                append("</div>")
            }

            // 1) Commit Body
            if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
                append("<div style=\"margin-bottom: 5px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("커밋 메시지 본문", "Commit Message Body"))}</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px; white-space: pre-wrap;\">")
                append(escapeHtml(c.body.trim()).replace("\n", "<br/>"))
                append("</div></div>")
            }

            // 2) Matched Markdown (.md) context
            if (detail.matchedMarkdownSections.isNotEmpty()) {
                append("<div style=\"margin-bottom: 5px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("연관 문서(.md) 맥락 인용", "Related Markdown (.md) Context"))}</div>")
                for (sec in detail.matchedMarkdownSections) {
                    append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px; margin-bottom: 3px;\">")
                    append("<div><b>${escapeHtml(sec.filePath)}</b> &raquo; <span style=\"color: $accentBlue;\">${escapeHtml(sec.heading)}</span></div>")
                    if (sec.matchedKeywords.isNotEmpty()) {
                        append(
                            "<div class=\"meta\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("매칭 키워드", "Matched Keywords"))}: " +
                                "${escapeHtml(sec.matchedKeywords.joinToString(", "))}</div>"
                        )
                    }
                    if (sec.checkedItems.isNotEmpty()) {
                        append("<ul>")
                        sec.checkedItems.forEach { item ->
                            append("<li>[x] ${escapeHtml(item)}</li>")
                        }
                        append("</ul>")
                    }
                    if (sec.bodyLines.isNotEmpty()) {
                        append("<div style=\"margin-top: 2px;\">")
                        sec.bodyLines.take(3).forEach { line ->
                            append("<div>${escapeHtml(line)}</div>")
                        }
                        append("</div>")
                    }
                    append("</div>")
                }
                append("</div>")
            }

            // Extracted Internal / External API paths
            if (detail.apiHints.isNotEmpty()) {
                append("<div style=\"margin-bottom: 5px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("추출된 내부 / 외부 API 경로", "Extracted Internal / External API Paths"))}</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px;\">")
                for (api in detail.apiHints.take(15)) {
                    append("<div style=\"margin-bottom: 1px;\">")
                    append("<b>[${escapeHtml(api.kind)}]</b> ")
                    append("<code>${escapeHtml(api.methodOrAnnotation)}</code> ")
                    append("<span style=\"color: $accentBlue;\">${escapeHtml(api.pathOrUri)}</span> ")
                    append("<span class=\"meta\" style=\"color: $muted;\">(${escapeHtml(api.sourceFile)}:${api.lineNumber})</span>")
                    append("</div>")
                }
                append("</div></div>")
            }

            // 3) Newly added comments
            if (detail.addedComments.isNotEmpty()) {
                append("<div style=\"margin-bottom: 4px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("새로 추가된 코드 주석 (+)", "Newly Added Code Comments (+)"))}</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px;\">")
                for ((fileName, comment) in detail.addedComments.take(12)) {
                    append("<div style=\"margin-bottom: 1px;\"><b>${escapeHtml(fileName)}</b>: ${escapeHtml(comment)}</div>")
                }
                append("</div></div>")
            }

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
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun toHex(color: Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
}
