package com.maekom.codetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.maekom.codetracker.model.BadgeSeverity
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import java.awt.Color
import java.time.format.DateTimeFormatter

/**
 * Generates clean, theme-aware local HTML for the Right-Top
 * [수정 사유 & 아키텍처 체킹 패널]. Contains zero external links or assets.
 */
object RationaleHtmlRenderer {

    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun renderPlaceholder(message: String): String {
        val fg = toHex(UIUtil.getContextHelpForeground())
        val bg = toHex(UIUtil.getPanelBackground())
        return """
            <html><body style="font-family: sans-serif; font-size: 12px; color: $fg; background-color: $bg; margin: 10px;">
            <p>${escapeHtml(message)}</p>
            </body></html>
        """.trimIndent()
    }

    fun renderLoading(commit: CommitSummary): String {
        val fg = toHex(UIUtil.getLabelForeground())
        val muted = toHex(UIUtil.getContextHelpForeground())
        val bg = toHex(UIUtil.getPanelBackground())
        return """
            <html><body style="font-family: sans-serif; font-size: 12px; color: $fg; background-color: $bg; margin: 10px;">
            <div style="font-weight: bold; font-size: 13px;">[${escapeHtml(commit.shortHash)}] ${escapeHtml(commit.subject)}</div>
            <div style="color: $muted; margin-top: 6px;">단일 커밋 Diff, 연관 .md 문서, 아키텍처 체킹 포인트를 백그라운드에서 분석 중입니다...</div>
            </body></html>
        """.trimIndent()
    }

    fun renderCommitDetail(detail: CommitDetail): String {
        val c = detail.summary
        val fg = toHex(UIUtil.getLabelForeground())
        val muted = toHex(UIUtil.getContextHelpForeground())
        val bg = toHex(UIUtil.getPanelBackground())
        val cardBg = toHex(JBColor(Color(0xF5F7FA), Color(0x2B2D30)))
        val borderColor = toHex(JBColor(Color(0xD8DEE9), Color(0x43454A)))
        val accentBlue = toHex(JBColor(Color(0x1D5FD6), Color(0x6B9BFA)))
        val flowBg = toHex(JBColor(Color(0xEBF3FF), Color(0x243347)))

        return buildString {
            append("<html><body style=\"font-family: sans-serif; font-size: 12px; color: $fg; background-color: $bg; margin: 8px;\">")

            // Header
            append("<div style=\"margin-bottom: 6px;\">")
            append("<span style=\"font-weight: bold; font-size: 13px; color: $accentBlue;\">[${escapeHtml(c.shortHash)}]</span> ")
            append("<span style=\"font-weight: bold; font-size: 13px;\">${escapeHtml(c.subject)}</span>")
            append("</div>")

            append("<div style=\"color: $muted; font-size: 11px; margin-bottom: 8px;\">")
            append("작성자: <b>${escapeHtml(c.authorName)}</b>")
            if (c.authorEmail.isNotBlank()) {
                append(" &lt;${escapeHtml(c.authorEmail)}&gt;")
            }
            append(" &nbsp;|&nbsp; 시각: ${c.timestamp.format(DT_FMT)}")
            if (detail.issueTags.isNotEmpty()) {
                append(" &nbsp;|&nbsp; 이슈: ")
                detail.issueTags.forEach { tag ->
                    append("<span style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 1px 4px;\"><b>${escapeHtml(tag)}</b></span> ")
                }
            }
            append("</div>")

            // 5) Cross-layer flow summary
            append("<div style=\"background-color: $flowBg; border-left: 3px solid $accentBlue; padding: 6px 8px; margin-bottom: 8px;\">")
            append("<b>${escapeHtml(detail.crossLayerFlowSummary)}</b>")
            append("</div>")

            // 4) Architecture check point badges
            if (detail.checkBadges.isNotEmpty()) {
                append("<div style=\"margin-bottom: 8px;\">")
                append("<div style=\"font-weight: bold; font-size: 11px; color: $muted; margin-bottom: 4px;\">현업 아키텍처 체킹 포인트</div>")
                for (badge in detail.checkBadges) {
                    val (bBg, bFg) = when (badge.severity) {
                        BadgeSeverity.HIGH -> toHex(JBColor(Color(0xFDECEC), Color(0x4A2525))) to toHex(JBColor(Color(0xB42318), Color(0xFF8A80)))
                        BadgeSeverity.MEDIUM -> toHex(JBColor(Color(0xFEF4E5), Color(0x47381E))) to toHex(JBColor(Color(0xB54708), Color(0xFFCA7A)))
                        BadgeSeverity.INFO -> toHex(JBColor(Color(0xEBF5FF), Color(0x21364A))) to toHex(JBColor(Color(0x175CD3), Color(0x82B1FF)))
                    }
                    append("<div style=\"background-color: $bBg; color: $bFg; padding: 3px 6px; margin-bottom: 3px;\">")
                    append("<b>${escapeHtml(badge.label)}</b> <span style=\"color: $fg;\">${escapeHtml(badge.description)}</span>")
                    append("</div>")
                }
                append("</div>")
            }

            // 1) Commit Body
            if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
                append("<div style=\"margin-bottom: 8px;\">")
                append("<div style=\"font-weight: bold; font-size: 11px; color: $muted; margin-bottom: 3px;\">커밋 메시지 본문</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 6px; white-space: pre-wrap;\">")
                append(escapeHtml(c.body.trim()).replace("\n", "<br/>"))
                append("</div></div>")
            }

            // 2) Matched Markdown (.md) context
            if (detail.matchedMarkdownSections.isNotEmpty()) {
                append("<div style=\"margin-bottom: 8px;\">")
                append("<div style=\"font-weight: bold; font-size: 11px; color: $muted; margin-bottom: 3px;\">연관 문서(.md) 맥락 인용</div>")
                for (sec in detail.matchedMarkdownSections) {
                    append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 6px; margin-bottom: 4px;\">")
                    append("<div><b>${escapeHtml(sec.filePath)}</b> &raquo; <span style=\"color: $accentBlue;\">${escapeHtml(sec.heading)}</span></div>")
                    if (sec.matchedKeywords.isNotEmpty()) {
                        append("<div style=\"font-size: 11px; color: $muted;\">매칭 키워드: ${escapeHtml(sec.matchedKeywords.joinToString(", "))}</div>")
                    }
                    if (sec.checkedItems.isNotEmpty()) {
                        append("<ul style=\"margin: 4px 0 2px 16px; padding: 0;\">")
                        sec.checkedItems.forEach { item ->
                            append("<li>[x] ${escapeHtml(item)}</li>")
                        }
                        append("</ul>")
                    }
                    if (sec.bodyLines.isNotEmpty()) {
                        append("<div style=\"margin-top: 3px;\">")
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
                append("<div style=\"margin-bottom: 8px;\">")
                append("<div style=\"font-weight: bold; font-size: 11px; color: $muted; margin-bottom: 3px;\">추출된 내부 / 외부 API 경로</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 5px 7px;\">")
                for (api in detail.apiHints.take(15)) {
                    append("<div style=\"margin-bottom: 2px;\">")
                    append("<b>[${escapeHtml(api.kind)}]</b> ")
                    append("<code>${escapeHtml(api.methodOrAnnotation)}</code> ")
                    append("<span style=\"color: $accentBlue;\">${escapeHtml(api.pathOrUri)}</span> ")
                    append("<span style=\"color: $muted; font-size: 11px;\">(${escapeHtml(api.sourceFile)}:${api.lineNumber})</span>")
                    append("</div>")
                }
                append("</div></div>")
            }

            // 3) Newly added comments
            if (detail.addedComments.isNotEmpty()) {
                append("<div style=\"margin-bottom: 6px;\">")
                append("<div style=\"font-weight: bold; font-size: 11px; color: $muted; margin-bottom: 3px;\">새로 추가된 코드 주석 (+)</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 5px 7px;\">")
                for ((fileName, comment) in detail.addedComments.take(12)) {
                    append("<div style=\"margin-bottom: 2px;\"><b>${escapeHtml(fileName)}</b>: ${escapeHtml(comment)}</div>")
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
