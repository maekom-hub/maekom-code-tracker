package com.maekom.codetracker.analysis

import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.WorkEpisode
import java.time.format.DateTimeFormatter

/**
 * Generates clean Markdown reports for the clipboard export action in current language (KO/EN).
 */
object MarkdownReportExporter {

    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun exportCommitDetail(detail: CommitDetail): String = buildString {
        val c = detail.summary
        val flowSummary = RationaleAndCheckEngine.buildCrossLayerFlowSummary(
            detail.summary,
            detail.fileDetails,
            detail.allStackTags
        )

        appendLine("## [${c.shortHash}] ${c.subject}")
        appendLine("- **${CodeTrackerI18n.tr("작성자", "Author")}**: ${c.authorName} (${c.authorEmail})")
        appendLine("- **${CodeTrackerI18n.tr("일시", "Time")}**: ${c.timestamp.format(DT_FMT)}")
        if (detail.issueTags.isNotEmpty()) {
            appendLine("- **${CodeTrackerI18n.tr("이슈 태그", "Issue Tags")}**: ${detail.issueTags.joinToString(" ")}")
        }
        appendLine("- **$flowSummary**")
        if (detail.allStackTags.isNotEmpty()) {
            appendLine(
                "- **${CodeTrackerI18n.tr("연관 데이터 저장소/스택", "Related Data Stores / Stacks")}**: " +
                    detail.allStackTags.joinToString(", ") { it.label }
            )
        }
        if (detail.checkBadges.isNotEmpty()) {
            appendLine(
                "- **${CodeTrackerI18n.tr("체킹 포인트", "Check Points")}**: " +
                    detail.checkBadges.joinToString(" ") { "`${it.localizedLabel()}`" }
            )
        }
        appendLine()

        if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
            appendLine("### ${CodeTrackerI18n.tr("커밋 메시지 본문", "Commit Message Body")}")
            appendLine("```text")
            appendLine(c.body.trim())
            appendLine("```")
            appendLine()
        }

        if (detail.matchedMarkdownSections.isNotEmpty()) {
            appendLine("### ${CodeTrackerI18n.tr("연관 문서(.md) 맥락", "Related Markdown (.md) Context")}")
            for (sec in detail.matchedMarkdownSections) {
                appendLine("- **${sec.filePath}** (`${sec.heading}`)")
                sec.checkedItems.forEach { appendLine("  - [x] $it") }
                sec.bodyLines.take(2).forEach { appendLine("  - $it") }
            }
            appendLine()
        }

        if (detail.apiHints.isNotEmpty()) {
            appendLine("### ${CodeTrackerI18n.tr("추출된 내부/외부 API 경로", "Extracted Internal / External API Paths")}")
            for (api in detail.apiHints) {
                appendLine("- [${api.kind}] `${api.methodOrAnnotation}` `${api.pathOrUri}` (${api.sourceFile}:${api.lineNumber})")
            }
            appendLine()
        }

        if (detail.addedComments.isNotEmpty()) {
            appendLine("### ${CodeTrackerI18n.tr("새로 추가된 주석", "Newly Added Comments")}")
            for ((file, comment) in detail.addedComments.take(10)) {
                appendLine("- `$file`: $comment")
            }
            appendLine()
        }

        appendLine("### ${CodeTrackerI18n.tr("수정 위치 포인터", "Smart Code Pointers")}")
        val groupedByLayer = detail.fileDetails.groupBy { it.summary.primaryLayer }
        for ((layer, files) in groupedByLayer.entries.sortedBy { it.key.flowOrder }) {
            appendLine("#### ${layer.badgeText}")
            for (fd in files) {
                val tagsStr = if (fd.stackTags.isNotEmpty()) " ${fd.stackTags.joinToString(" ") { it.label }}" else ""
                appendLine("- `${fd.summary.path}` (${fd.summary.status.localizedLabel()}, +${fd.addedLinesTotal}/-${fd.deletedLinesTotal})$tagsStr")
                when {
                    fd.skippedLargeOrBinary -> {
                        appendLine("  - _${fd.skipReason ?: CodeTrackerI18n.defaultSkipText()}_")
                    }
                    fd.onlyImportOrWhitespaceChanges -> {
                        appendLine("  - _${CodeTrackerI18n.importWhitespaceOnlyText()}_")
                    }
                    else -> {
                        for (h in fd.hunks) {
                            appendLine("  - 📍 Line ${h.newStartLine} : `${h.displayContext}` (+${h.addedLines}/-${h.deletedLines})")
                            if (!h.matchedMdFullNote.isNullOrBlank()) {
                                val noteSingleLine = h.matchedMdFullNote.replace(Regex("\\s+"), " ").trim()
                                appendLine("    - > 📝 $noteSingleLine")
                            }
                        }
                    }
                }
            }
        }
    }

    fun exportEpisodeSummary(episode: WorkEpisode, cachedDetails: Map<String, CommitDetail>): String = buildString {
        appendLine("## ${CodeTrackerI18n.tr("작업 에피소드 요약", "Work Episode Summary")} (${episode.date} | ${episode.authorName})")
        appendLine("- **${CodeTrackerI18n.tr("작업 시간", "Time Range")}**: ${episode.startTime.format(DT_FMT)} ~ ${episode.endTime.format(DT_FMT)}")
        appendLine("- **${CodeTrackerI18n.tr("커밋 수", "Commits")}**: ${CodeTrackerI18n.itemsCountUnit(episode.commits.size)}")
        if (episode.markdownFiles.isNotEmpty()) {
            appendLine("- **${CodeTrackerI18n.tr("연관 문서(.md)", "Related Docs (.md)")}**: ${episode.markdownFiles.map { it.filePath }.distinct().joinToString(", ")}")
        }
        appendLine()

        for (c in episode.commits) {
            val detail = cachedDetails[c.hash]
            if (detail != null) {
                appendLine(exportCommitDetail(detail))
                appendLine("---")
            } else {
                val layers = c.primaryLayers.joinToString(" ") { it.badgeText }
                appendLine("### [${c.shortHash}] ${c.subject} (${c.timestamp.format(DT_FMT)})")
                appendLine("- ${CodeTrackerI18n.tr("레이어", "Layers")}: $layers")
                for (f in c.files) {
                    appendLine("  - `${f.path}` (${f.status.localizedLabel()}, ${f.primaryLayer.badgeText})")
                }
                appendLine()
            }
        }
    }
}
