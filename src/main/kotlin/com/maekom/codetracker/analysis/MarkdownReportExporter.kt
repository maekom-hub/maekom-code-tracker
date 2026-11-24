package com.maekom.codetracker.analysis

import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.WorkEpisode
import java.time.format.DateTimeFormatter

/**
 * Generates clean Markdown reports for the [마크다운 복사] clipboard action.
 */
object MarkdownReportExporter {

    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun exportCommitDetail(detail: CommitDetail): String = buildString {
        val c = detail.summary
        appendLine("## [${c.shortHash}] ${c.subject}")
        appendLine("- **작성자**: ${c.authorName} (${c.authorEmail})")
        appendLine("- **일시**: ${c.timestamp.format(DT_FMT)}")
        if (detail.issueTags.isNotEmpty()) {
            appendLine("- **이슈 태그**: ${detail.issueTags.joinToString(" ")}")
        }
        appendLine("- **${detail.crossLayerFlowSummary}**")
        if (detail.allStackTags.isNotEmpty()) {
            appendLine("- **연관 데이터 저장소/스택**: ${detail.allStackTags.joinToString(", ") { it.label }}")
        }
        if (detail.checkBadges.isNotEmpty()) {
            appendLine("- **체킹 포인트**: ${detail.checkBadges.joinToString(" ") { "`${it.label}`" }}")
        }
        appendLine()

        if (c.body.isNotBlank() && c.body.trim() != c.subject.trim()) {
            appendLine("### 커밋 메시지 본문")
            appendLine("```text")
            appendLine(c.body.trim())
            appendLine("```")
            appendLine()
        }

        if (detail.matchedMarkdownSections.isNotEmpty()) {
            appendLine("### 연관 문서(.md) 맥락")
            for (sec in detail.matchedMarkdownSections) {
                appendLine("- **${sec.filePath}** (`${sec.heading}`)")
                sec.checkedItems.forEach { appendLine("  - [x] $it") }
                sec.bodyLines.take(2).forEach { appendLine("  - $it") }
            }
            appendLine()
        }

        if (detail.apiHints.isNotEmpty()) {
            appendLine("### 추출된 내부/외부 API 경로")
            for (api in detail.apiHints) {
                appendLine("- [${api.kind}] `${api.methodOrAnnotation}` `${api.pathOrUri}` (${api.sourceFile}:${api.lineNumber})")
            }
            appendLine()
        }

        if (detail.addedComments.isNotEmpty()) {
            appendLine("### 새로 추가된 주석")
            for ((file, comment) in detail.addedComments.take(10)) {
                appendLine("- `$file`: $comment")
            }
            appendLine()
        }

        appendLine("### 수정 위치 포인터")
        val groupedByLayer = detail.fileDetails.groupBy { it.summary.primaryLayer }
        for ((layer, files) in groupedByLayer.entries.sortedBy { it.key.flowOrder }) {
            appendLine("#### ${layer.badgeText}")
            for (fd in files) {
                val tagsStr = if (fd.stackTags.isNotEmpty()) " ${fd.stackTags.joinToString(" ") { it.label }}" else ""
                appendLine("- `${fd.summary.path}` (${fd.summary.status.label}, +${fd.addedLinesTotal}/-${fd.deletedLinesTotal})$tagsStr")
                when {
                    fd.skippedLargeOrBinary -> {
                        appendLine("  - _${fd.skipReason}_")
                    }
                    fd.onlyImportOrWhitespaceChanges -> {
                        appendLine("  - _import/공백 정리만 포함됨_")
                    }
                    else -> {
                        for (h in fd.hunks) {
                            appendLine("  - 📍 Line ${h.newStartLine} : `${h.displayContext}` (+${h.addedLines}/-${h.deletedLines})")
                        }
                    }
                }
            }
        }
    }

    fun exportEpisodeSummary(episode: WorkEpisode, cachedDetails: Map<String, CommitDetail>): String = buildString {
        appendLine("## 작업 에피소드 요약 (${episode.date} | ${episode.authorName})")
        appendLine("- **작업 시간**: ${episode.startTime.format(DT_FMT)} ~ ${episode.endTime.format(DT_FMT)}")
        appendLine("- **커밋 수**: ${episode.commits.size}건")
        if (episode.markdownFiles.isNotEmpty()) {
            appendLine("- **연관 문서(.md)**: ${episode.markdownFiles.map { it.filePath }.distinct().joinToString(", ")}")
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
                appendLine("- 레이어: $layers")
                for (f in c.files) {
                    appendLine("  - `${f.path}` (${f.status.label}, ${f.primaryLayer.badgeText})")
                }
                appendLine()
            }
        }
    }
}
