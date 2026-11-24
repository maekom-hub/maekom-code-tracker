package com.maekom.codetracker.analysis

import com.intellij.openapi.project.Project
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.MarkdownFileRef
import com.maekom.codetracker.model.MarkdownSection
import com.maekom.codetracker.model.WorkEpisode
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Parses Markdown (.md) documents using CommonMark AST and automatically links
 * relevant headings (#, ##, ###) and checked checklist items (- [x]) to code commits
 * within the same or immediately preceding Work Episode.
 */
object MarkdownContextMatcher {

    private val parser: Parser = Parser.builder().build()

    private val CHECKED_ITEM_REGEX = Regex("""^\[[xX]]\s*(.+)$""")
    private val TOKEN_SPLIT_REGEX = Regex("""[^a-zA-Z0-9가-힣]+""")
    private val CAMEL_SPLIT_REGEX = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

    private val STOP_WORDS = setOf(
        "src", "main", "test", "java", "kotlin", "kt", "com", "org", "net", "io",
        "service", "controller", "repository", "entity", "dto", "config", "impl",
        "client", "mapper", "xml", "sql", "yml", "yaml", "properties", "md",
        "get", "set", "find", "save", "update", "delete", "create", "list", "all",
        "the", "and", "for", "with", "from", "into", "this", "that", "fix", "feat", "chore", "refactor"
    )

    internal data class RawSection(
        val heading: String,
        val level: Int,
        val bodyLines: MutableList<String> = mutableListOf(),
        val checkedItems: MutableList<String> = mutableListOf()
    )

    fun matchContextForCommit(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        fileDetails: List<FileDiffDetail>,
        currentEpisode: WorkEpisode?,
        previousEpisode: WorkEpisode?
    ): List<MarkdownSection> {
        val candidateRefs = linkedMapOf<String, MarkdownFileRef>()
        // Prioritize .md files from the same episode, then from the immediately preceding episode
        currentEpisode?.markdownFiles?.forEach { ref ->
            candidateRefs[ref.filePath] = ref
        }
        previousEpisode?.markdownFiles?.forEach { ref ->
            candidateRefs.putIfAbsent(ref.filePath, ref)
        }
        if (candidateRefs.isEmpty()) return emptyList()

        val keywords = extractCommitKeywords(commit, fileDetails)
        val matched = mutableListOf<MarkdownSection>()

        for (ref in candidateRefs.values.take(10)) {
            val content = GitRepositoryService.readFileAtRevision(
                project = project,
                repoRootPath = repoRootPath,
                revisionSpec = "${ref.commitHash}:${ref.filePath}"
            )
            if (content.isBlank()) continue

            val sections = parseMarkdownSections(content)
            val isSameEpisode = currentEpisode?.markdownFiles?.any { it.filePath == ref.filePath } == true

            for (sec in sections) {
                val combinedText = buildString {
                    append(sec.heading).append('\n')
                    sec.checkedItems.forEach { append(it).append('\n') }
                    sec.bodyLines.forEach { append(it).append('\n') }
                }
                val lowerCombined = combinedText.lowercase()
                val lowerHeading = sec.heading.lowercase()

                val hits = mutableListOf<String>()
                var score = 0

                for (kw in keywords) {
                    val lowerKw = kw.lowercase()
                    if (lowerCombined.contains(lowerKw)) {
                        hits.add(kw)
                        score += when {
                            lowerHeading.contains(lowerKw) -> 5
                            sec.checkedItems.any { it.lowercase().contains(lowerKw) } -> 4
                            else -> 2
                        }
                    }
                }

                // Bonus for same-episode checklists or direct .md commit
                if (isSameEpisode && sec.checkedItems.isNotEmpty()) {
                    score += 2
                }
                if (ref.commitHash == commit.hash) {
                    score += 3
                }

                if (score > 0 && (hits.isNotEmpty() || sec.checkedItems.isNotEmpty() || ref.commitHash == commit.hash)) {
                    matched.add(
                        MarkdownSection(
                            filePath = ref.filePath,
                            commitHash = ref.commitHash,
                            heading = sec.heading.ifBlank { ref.filePath.substringAfterLast('/') },
                            level = sec.level,
                            bodyLines = sec.bodyLines.take(6),
                            checkedItems = sec.checkedItems.take(8),
                            matchedKeywords = hits.distinct().take(6),
                            score = score
                        )
                    )
                }
            }
        }

        return matched
            .sortedByDescending { it.score }
            .take(5)
    }

    internal fun parseMarkdownSections(markdown: String): List<RawSection> {
        val document = parser.parse(markdown)
        val sections = mutableListOf<RawSection>()
        var current = RawSection(heading = "", level = 0)

        var node: Node? = document.firstChild
        while (node != null) {
            when (node) {
                is Heading -> {
                    if (current.heading.isNotBlank() || current.bodyLines.isNotEmpty() || current.checkedItems.isNotEmpty()) {
                        sections.add(current)
                    }
                    val headingText = extractPlainText(node).trim()
                    current = RawSection(heading = headingText, level = node.level)
                }

                is BulletList, is OrderedList -> {
                    var item: Node? = node.firstChild
                    while (item != null) {
                        if (item is ListItem) {
                            val itemText = extractPlainText(item).trim()
                            val checkedMatch = CHECKED_ITEM_REGEX.matchEntire(itemText)
                            if (checkedMatch != null) {
                                current.checkedItems.add(checkedMatch.groupValues[1].trim())
                            } else if (itemText.isNotBlank()) {
                                current.bodyLines.add("- $itemText")
                            }
                        }
                        item = item.next
                    }
                }

                is Paragraph -> {
                    val text = extractPlainText(node).trim()
                    if (text.isNotBlank()) {
                        current.bodyLines.add(text)
                    }
                }

                is FencedCodeBlock -> {
                    // Ignore large code blocks so code comments inside markdown don't drown out headings/checklists
                }
            }
            node = node.next
        }

        if (current.heading.isNotBlank() || current.bodyLines.isNotEmpty() || current.checkedItems.isNotEmpty()) {
            sections.add(current)
        }
        return sections
    }

    private fun extractPlainText(root: Node): String {
        val sb = StringBuilder()
        root.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                sb.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                sb.append(' ')
            }
        })
        return sb.toString()
    }

    private fun extractCommitKeywords(
        commit: CommitSummary,
        fileDetails: List<FileDiffDetail>
    ): Set<String> {
        val keywords = linkedSetOf<String>()

        // 1. Filenames & class names & domain tokens
        for (file in commit.files) {
            if (file.path.endsWith(".md", ignoreCase = true)) continue
            val baseName = file.fileName.substringBeforeLast('.')
            if (baseName.length >= 3) {
                keywords.add(baseName)
            }
            splitIdentifierTokens(baseName).forEach { keywords.add(it) }
            // Parent folder domain name
            val segments = file.path.split('/')
            segments.dropLast(1).takeLast(2).forEach { seg ->
                splitIdentifierTokens(seg).forEach { keywords.add(it) }
            }
        }

        // 2. Detected method names & API paths
        for (detail in fileDetails) {
            for (hunk in detail.hunks) {
                hunk.detectedMethodName?.let { method ->
                    val clean = method.substringAfter('#')
                    if (clean.length >= 3) {
                        keywords.add(clean)
                        splitIdentifierTokens(clean).forEach { keywords.add(it) }
                    }
                }
            }
            for (api in detail.apiHints) {
                api.pathOrUri.split('/', '-', '_', ' ').forEach { seg ->
                    val cleaned = seg.trim('{', '}', ',', '(', ')', '"')
                    if (cleaned.length >= 3 && cleaned.lowercase() !in STOP_WORDS) {
                        keywords.add(cleaned)
                    }
                }
            }
        }

        // 3. Commit subject domain tokens
        TOKEN_SPLIT_REGEX.split(commit.subject).forEach { token ->
            if (token.length >= 2 && token.lowercase() !in STOP_WORDS) {
                keywords.add(token)
            }
        }

        return keywords
    }

    private fun splitIdentifierTokens(raw: String): List<String> {
        return TOKEN_SPLIT_REGEX.split(raw)
            .flatMap { CAMEL_SPLIT_REGEX.split(it) }
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in STOP_WORDS }
    }
}
