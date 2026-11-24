package com.maekom.codetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maekom.codetracker.analysis.RationaleAndCheckEngine
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.BadgeSeverity
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.EntrypointTraceResult
import com.maekom.codetracker.model.MergedFileAnalysisDetail
import com.maekom.codetracker.model.RadarSeverity
import com.maekom.codetracker.model.SideEffectAlert
import java.awt.Color
import java.time.format.DateTimeFormatter
import javax.swing.UIManager

/**
 * Generates clean, theme-aware local HTML for the Right-Top
 * [Change Reason & Architecture Check] panel.
 * Supports clickable local links (`ep:<idx>` for Impacted Entrypoints, `radar:<idx>` for Side-Effect Radar),
 * zero-restart KO/EN localization, and Tree/Label compact font sizing (12px body, 13px bold title).
 */
object RationaleHtmlRenderer {

    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val SHORT_DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun baseFontFamily(): String {
        val treeFont = UIManager.getFont("Tree.font") ?: JBUI.Fonts.label()
        return escapeHtml(treeFont.family)
    }

    private fun commonStyleBlock(): String {
        val fontFamily = baseFontFamily()
        val fg = toHex(UIUtil.getLabelForeground())
        val bg = toHex(UIUtil.getPanelBackground())
        val accentBlue = toHex(JBColor(Color(0x1D5FD6), Color(0x6B9BFA)))
        return """
            <style>
                body { font-family: '$fontFamily', sans-serif; font-size: 12px; color: $fg; background-color: $bg; margin: 6px; }
                .title { font-size: 13px; font-weight: bold; }
                .meta { font-size: 11px; }
                .sec-label { font-size: 11px; font-weight: bold; margin-bottom: 2px; }
                a { color: $accentBlue; text-decoration: none; }
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

            // Top Section A: Impacted Entrypoints (PSI call-graph upward trace, clickable chips)
            appendEntrypointsSection(detail.entrypointTrace, muted, cardBg, borderColor, accentBlue)

            // Top Section B: Backend Side-Effect Risk Radar (clickable jump to exact hunk line)
            appendSideEffectRadarSection(detail.sideEffectAlerts, fg, muted)

            // Cross-layer flow summary
            append("<div style=\"background-color: $flowBg; border-left: 3px solid $accentBlue; padding: 3px 6px; margin-bottom: 5px;\">")
            append("<b>${escapeHtml(localizedFlowSummary)}</b>")
            append("</div>")

            // Architecture check point badges
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

            // Commit Body
            if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
                append("<div style=\"margin-bottom: 5px;\">")
                append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.tr("커밋 메시지 본문", "Commit Message Body"))}</div>")
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px; white-space: pre-wrap;\">")
                append(escapeHtml(c.body.trim()).replace("\n", "<br/>"))
                append("</div></div>")
            }

            // Matched Markdown (.md) context
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

            // Newly added comments
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

    fun renderMergedFileDetail(merged: MergedFileAnalysisDetail): String {
        val mf = merged.mergedFile
        val fg = toHex(UIUtil.getLabelForeground())
        val muted = toHex(UIUtil.getContextHelpForeground())
        val cardBg = toHex(JBColor(Color(0xF5F7FA), Color(0x2B2D30)))
        val borderColor = toHex(JBColor(Color(0xD8DEE9), Color(0x43454A)))
        val accentBlue = toHex(JBColor(Color(0x1D5FD6), Color(0x6B9BFA)))
        val flowBg = toHex(JBColor(Color(0xEBF3FF), Color(0x243347)))

        return buildString {
            append("<html><head>${commonStyleBlock()}</head><body>")

            append("<div class=\"title\" style=\"margin-bottom: 3px;\">${escapeHtml(mf.fileName)}</div>")
            append("<div class=\"meta\" style=\"color: $muted; margin-bottom: 5px;\">")
            append("${escapeHtml(mf.path)} &nbsp;|&nbsp; ${escapeHtml(CodeTrackerI18n.flowModifiedBadge(mf.modifyCount, mf.totalAddedLines, mf.totalDeletedLines))}")
            append("</div>")

            append("<div style=\"background-color: $flowBg; border-left: 3px solid $accentBlue; padding: 3px 6px; margin-bottom: 5px;\">")
            append("<b>${escapeHtml(mf.stage.localizedTitle())}</b>")
            if (merged.allStackTags.isNotEmpty()) {
                append(" &nbsp;&raquo;&nbsp; ${escapeHtml(merged.allStackTags.joinToString(", ") { it.label })}")
            }
            append("</div>")

            appendEntrypointsSection(merged.entrypointTrace, muted, cardBg, borderColor, accentBlue)
            appendSideEffectRadarSection(merged.sideEffectAlerts, fg, muted)

            // Related commits timeline for this merged file
            append("<div style=\"margin-bottom: 5px;\">")
            append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.flowMergedHeader(mf.fileName, merged.commitSections.size))}</div>")
            for (sec in merged.commitSections) {
                val c = sec.commit
                val fd = sec.fileDetail
                append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 4px 6px; margin-bottom: 3px;\">")
                append("<div><span style=\"color: $accentBlue;\"><b>[${escapeHtml(c.shortHash)}]</b></span> <b>${escapeHtml(c.subject)}</b></div>")
                append("<div class=\"meta\" style=\"color: $muted;\">${escapeHtml(c.authorName)} &nbsp;|&nbsp; ${c.timestamp.format(SHORT_DT_FMT)} &nbsp;|&nbsp; ${escapeHtml(fd.summary.status.localizedLabel())} (+${fd.addedLinesTotal}/-${fd.deletedLinesTotal})</div>")
                if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
                    append("<div style=\"margin-top: 2px;\">${escapeHtml(c.body.trim().take(240)).replace("\n", "<br/>")}</div>")
                }
                append("</div>")
            }
            append("</div>")

            append("</body></html>")
        }
    }

    private fun StringBuilder.appendEntrypointsSection(
        trace: EntrypointTraceResult,
        muted: String,
        cardBg: String,
        borderColor: String,
        accentBlue: String
    ) {
        if (!trace.isDumbMode && trace.entrypoints.isEmpty()) return

        append("<div style=\"margin-bottom: 5px;\">")
        append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.impactedEntrypointsTitle())}")
        if (trace.truncatedByLimit) {
            append(" <span style=\"font-weight: normal;\">${escapeHtml(CodeTrackerI18n.entrypointTruncatedHint())}</span>")
        }
        append("</div>")

        if (trace.isDumbMode) {
            append("<div style=\"background-color: $cardBg; border: 1px solid $borderColor; padding: 3px 6px; color: $muted;\">")
            append(escapeHtml(CodeTrackerI18n.entrypointDumbModeNotice()))
            append("</div>")
        } else {
            val chipBg = toHex(JBColor(Color(0xEAF2FF), Color(0x23344D)))
            val chipBorder = toHex(JBColor(Color(0xB8D0F8), Color(0x3C5A82)))
            append("<div style=\"line-height: 1.8;\">")
            trace.entrypoints.forEachIndexed { idx, ep ->
                val depthSuffix = if (ep.hopDepth > 0) " (${ep.hopDepth} hop)" else ""
                append(
                    "<a href=\"ep:$idx\" style=\"background-color: $chipBg; border: 1px solid $chipBorder; " +
                        "color: $accentBlue; padding: 1px 5px; margin-right: 4px;\">" +
                        "<b>${escapeHtml(ep.displayLabel)}</b>${escapeHtml(depthSuffix)}</a> "
                )
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendSideEffectRadarSection(
        alerts: List<SideEffectAlert>,
        fg: String,
        muted: String
    ) {
        if (alerts.isEmpty()) return

        append("<div style=\"margin-bottom: 5px;\">")
        append("<div class=\"sec-label\" style=\"color: $muted;\">${escapeHtml(CodeTrackerI18n.sideEffectRadarTitle())}</div>")
        alerts.forEachIndexed { idx, alert ->
            val (bgHex, badgeFgHex, borderHex) = when (alert.severity) {
                RadarSeverity.HIGH -> Triple(
                    toHex(JBColor(Color(0xFDECEC), Color(0x4A2222))),
                    toHex(JBColor(Color(0xC62828), Color(0xFF7B72))),
                    toHex(JBColor(Color(0xF5B5B5), Color(0x7A3333)))
                )
                RadarSeverity.WARN -> Triple(
                    toHex(JBColor(Color(0xFFF4E5), Color(0x47361C))),
                    toHex(JBColor(Color(0xC25E00), Color(0xFFB74D))),
                    toHex(JBColor(Color(0xF5D099), Color(0x7A5A28)))
                )
            }
            append("<div style=\"background-color: $bgHex; border-left: 3px solid $borderHex; padding: 2px 5px; margin-bottom: 2px;\">")
            append("<a href=\"radar:$idx\" style=\"color: $fg;\">")
            append("<span style=\"color: $badgeFgHex; font-weight: bold;\">${escapeHtml(alert.severity.localizedBadge())}</span> ")
            append("<b>${escapeHtml(alert.rule.localizedTitle())}</b> ")
            append("<span class=\"meta\" style=\"color: $muted;\">(${escapeHtml(alert.fileName)}:${alert.hunkPointer.newStartLine})</span>")
            append(" — ${escapeHtml(alert.localizedDetail())}")
            append("</a></div>")
        }
        append("</div>")
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
