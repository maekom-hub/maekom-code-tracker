package com.maekom.codetracker.analysis

import com.intellij.openapi.project.Project
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.model.CodeHunkPointer
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
 * and individual Diff Hunks within the same or immediately preceding Work Episode.
 */
object MarkdownContextMatcher {

    private val parser: Parser = Parser.builder().build()

    private val CHECKED_ITEM_REGEX = Regex("""^\[[xX]]\s*(.+)$""")
    private val TOKEN_SPLIT_REGEX = Regex("""[^a-zA-Z0-9가-힣]+""")
    private val CAMEL_SPLIT_REGEX = Regex("""(?<=[a-z0-9])(?=[A-Z])""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private const val SHORT_NOTE_MAX_LEN = 65

    private val STOP_WORDS = setOf(
        "src", "main", "test", "java", "kotlin", "kt", "com", "org", "net", "io",
        "service", "controller", "repository", "entity", "dto", "config", "impl",
        "client", "mapper", "xml", "sql", "yml", "yaml", "properties", "md",
        "get", "set", "find", "save", "update", "delete", "create", "list", "all",
        "the", "and", "for", "with", "from", "into", "this", "that", "fix", "feat", "chore", "refactor"
    )

    private val GENERIC_METHOD_VERBS = setOf(
        "get", "set", "find", "save", "delete", "list", "create", "update",
        "execute", "process", "handle", "init", "build", "tostring", "equals", "hashcode"
    )

    internal data class RawSection(
        val heading: String,
        val level: Int,
        val bodyLines: MutableList<String> = mutableListOf(),
        val checkedItems: MutableList<String> = mutableListOf()
    )

    internal data class LoadedMarkdownSection(
        val ref: MarkdownFileRef,
        val isSameEpisode: Boolean,
        val heading: String,
        val level: Int,
        val bodyLines: List<String>,
        val checkedItems: List<String>,
        val searchableTextLower: String,
        val headingLower: String
    )

    internal fun loadAvailableMarkdownSections(
        project: Project,
        repoRootPath: String,
        currentEpisode: WorkEpisode?,
        previousEpisode: WorkEpisode?
    ): List<LoadedMarkdownSection> {
        val candidateRefs = linkedMapOf<String, MarkdownFileRef>()
        // Prioritize .md files from the same episode, then from the immediately preceding episode
        currentEpisode?.markdownFiles?.forEach { ref ->
            candidateRefs[ref.filePath] = ref
        }
        previousEpisode?.markdownFiles?.forEach { ref ->
            candidateRefs.putIfAbsent(ref.filePath, ref)
        }
        if (candidateRefs.isEmpty()) return emptyList()

        val loaded = mutableListOf<LoadedMarkdownSection>()
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
                val heading = sec.heading.ifBlank { ref.filePath.substringAfterLast('/') }
                val combinedText = buildString {
                    append(heading).append('\n')
                    sec.checkedItems.forEach { append(it).append('\n') }
                    sec.bodyLines.forEach { append(it).append('\n') }
                }
                loaded.add(
                    LoadedMarkdownSection(
                        ref = ref,
                        isSameEpisode = isSameEpisode,
                        heading = heading,
                        level = sec.level,
                        bodyLines = sec.bodyLines.take(6),
                        checkedItems = sec.checkedItems.take(8),
                        searchableTextLower = combinedText.lowercase(),
                        headingLower = heading.lowercase()
                    )
                )
            }
        }
        return loaded
    }

    internal fun matchContextForCommit(
        commit: CommitSummary,
        fileDetails: List<FileDiffDetail>,
        loadedSections: List<LoadedMarkdownSection>
    ): List<MarkdownSection> {
        if (loadedSections.isEmpty()) return emptyList()

        val keywords = extractCommitKeywords(commit, fileDetails)
        val matched = mutableListOf<MarkdownSection>()

        for (sec in loadedSections) {
            val hits = mutableListOf<String>()
            var score = 0

            for (kw in keywords) {
                val lowerKw = kw.lowercase()
                if (sec.searchableTextLower.contains(lowerKw)) {
                    hits.add(kw)
                    score += when {
                        sec.headingLower.contains(lowerKw) -> 5
                        sec.checkedItems.any { it.lowercase().contains(lowerKw) } -> 4
                        else -> 2
                    }
                }
            }

            if (sec.isSameEpisode && sec.checkedItems.isNotEmpty()) {
                score += 2
            }
            if (sec.ref.commitHash == commit.hash) {
                score += 3
            }

            if (score > 0 && (hits.isNotEmpty() || sec.checkedItems.isNotEmpty() || sec.ref.commitHash == commit.hash)) {
                matched.add(
                    MarkdownSection(
                        filePath = sec.ref.filePath,
                        commitHash = sec.ref.commitHash,
                        heading = sec.heading,
                        level = sec.level,
                        bodyLines = sec.bodyLines,
                        checkedItems = sec.checkedItems,
                        matchedKeywords = hits.distinct().take(6),
                        score = score
                    )
                )
            }
        }

        return matched
            .sortedByDescending { it.score }
            .take(5)
    }

    /**
     * Precision Hunk-level Markdown matching:
     * 1st priority: concrete method name (>= 4 chars, not generic verb) or detected API route URI in hunk range.
     * 2nd priority (fallback without duplication): only if zero hunks matched in the file, match class name
     * and attach solely to the file's first valid hunk.
     */
    internal fun attachHunkMarkdownNotes(
        fileDetails: List<FileDiffDetail>,
        loadedSections: List<LoadedMarkdownSection>
    ): List<FileDiffDetail> {
        if (loadedSections.isEmpty()) return fileDetails

        return fileDetails.map { fd ->
            if (fd.skippedLargeOrBinary ||
                fd.onlyImportOrWhitespaceChanges ||
                fd.hunks.isEmpty() ||
                fd.summary.path.endsWith(".md", ignoreCase = true)
            ) {
                return@map fd
            }

            var anyPriority1Matched = false
            val enrichedHunks = fd.hunks.map { hunk ->
                val p1Terms = extractPrecisionHunkTerms(fd, hunk)
                val bestSection = findBestSectionForTerms(p1Terms, loadedSections)
                if (bestSection != null) {
                    anyPriority1Matched = true
                    val (shortNote, fullNote) = formatHunkMarkdownNotes(bestSection)
                    hunk.copy(
                        matchedMdShortNote = shortNote,
                        matchedMdFullNote = fullNote
                    )
                } else {
                    hunk
                }
            }.toMutableList()

            // 2nd priority: class-name fallback attached only once to the first valid hunk
            if (!anyPriority1Matched && enrichedHunks.isNotEmpty()) {
                val className = fd.summary.fileName.substringBeforeLast('.').trim()
                if (className.length >= 4 && className.lowercase() !in GENERIC_METHOD_VERBS) {
                    val fallbackSection = findBestSectionForTerms(listOf(className), loadedSections)
                    if (fallbackSection != null) {
                        val (shortNote, fullNote) = formatHunkMarkdownNotes(fallbackSection)
                        enrichedHunks[0] = enrichedHunks[0].copy(
                            matchedMdShortNote = shortNote,
                            matchedMdFullNote = fullNote
                        )
                    }
                }
            }

            fd.copy(hunks = enrichedHunks)
        }
    }

    private fun extractPrecisionHunkTerms(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer
    ): List<String> {
        val terms = linkedSetOf<String>()

        // Concrete method name (strip parens and MyBatis tag# prefix, length >= 4, exclude generic verbs)
        val rawMethod = hunk.detectedMethodName
            ?.substringAfter('#')
            ?.substringBefore('(')
            ?.trim()
        if (!rawMethod.isNullOrBlank() &&
            rawMethod.length >= 4 &&
            rawMethod.lowercase() !in GENERIC_METHOD_VERBS
        ) {
            terms.add(rawMethod)
        }

        // Detected API routes (HTTP mappings / External URIs) falling within this hunk's line span
        val hunkEnd = hunk.newStartLine + hunk.newLineCount.coerceAtLeast(1)
        for (api in fd.apiHints) {
            if (api.lineNumber in hunk.newStartLine..hunkEnd) {
                val route = api.pathOrUri.trim()
                if (route.length >= 3 && route != "(root)") {
                    terms.add(route)
                }
            }
        }

        return terms.toList()
    }

    private fun findBestSectionForTerms(
        terms: List<String>,
        loadedSections: List<LoadedMarkdownSection>
    ): LoadedMarkdownSection? {
        if (terms.isEmpty()) return null
        var best: LoadedMarkdownSection? = null
        var bestScore = 0

        for (sec in loadedSections) {
            var score = 0
            for (term in terms) {
                val lowerTerm = term.lowercase()
                if (sec.searchableTextLower.contains(lowerTerm)) {
                    score += when {
                        sec.headingLower.contains(lowerTerm) -> 6
                        sec.checkedItems.any { it.lowercase().contains(lowerTerm) } -> 5
                        else -> 3
                    }
                }
            }
            if (score > 0) {
                if (sec.isSameEpisode) score += 2
                if (score > bestScore) {
                    bestScore = score
                    best = sec
                }
            }
        }
        return best
    }

    internal fun formatHunkMarkdownNotes(sec: LoadedMarkdownSection): Pair<String, String> {
        val docFileName = sec.ref.filePath.substringAfterLast('/')
        val summaryParts = mutableListOf<String>()
        sec.checkedItems.take(3).forEach { summaryParts.add("[x] $it") }
        if (summaryParts.size < 3) {
            val remaining = 3 - summaryParts.size
            sec.bodyLines.take(remaining).forEach { summaryParts.add(it) }
        }

        val summaryBody = summaryParts.joinToString("\n").trim()
        val fullNote = if (summaryBody.isNotBlank()) {
            "[$docFileName] ${sec.heading} -> $summaryBody"
        } else {
            "[$docFileName] ${sec.heading}"
        }

        val singleLine = WHITESPACE_REGEX.replace(fullNote, " ").trim()
        val shortNote = if (singleLine.length > SHORT_NOTE_MAX_LEN) {
            singleLine.take(SHORT_NOTE_MAX_LEN - 3).trimEnd() + "..."
        } else {
            singleLine
        }

        return shortNote to fullNote
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
