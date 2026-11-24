package com.maekom.codetracker.analysis

import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.ApiEndpointHint
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.FileChangeStatus
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.FileSummary
import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.StackTag

/**
 * Parses `git show --find-renames --unified=3` unified diff output into structured
 * per-file hunks, method signatures, HTTP/External API hints, added comments,
 * stack tags, and @Transactional change indicators.
 * Automatically skips binary files and >1MB per-file diffs.
 */
object UnifiedDiffParser {

    private const val MAX_FILE_DIFF_BYTES = 1_048_576 // 1MB

    private val HUNK_HEADER_REGEX = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

    // Kotlin function declaration: fun [generics] methodName(
    private val KOTLIN_FUN_REGEX = Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_.]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    // Java method declaration: modifiers/returnType methodName(
    private val JAVA_METHOD_REGEX = Regex(
        """^\s*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native)\s+)*(?:<[^>]+>\s+)?[A-Za-z0-9_$.<>\[\]?,\s]+\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\([^;]*$"""
    )

    // MyBatis XML query tag: <select id="findOrders"
    private val MYBATIS_ID_REGEX = Regex("""<(select|update|insert|delete)\s+[^>]*id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // Spring HTTP mapping annotations
    private val HTTP_MAPPING_REGEX = Regex(
        """@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(?:\(([^)]*)\))?"""
    )

    // FeignClient annotation
    private val FEIGN_CLIENT_REGEX = Regex("""@FeignClient\s*\(([^)]*)\)""")

    // Quoted string literal inside annotation or client call
    private val QUOTED_URI_REGEX = Regex(""""([^"]+)"""")

    // External HTTP URL or client call URI patterns
    private val EXTERNAL_CALL_REGEX = Regex(
        """(?:\.uri|\.getForObject|\.getForEntity|\.postForObject|\.postForEntity|\.exchange|\.put|\.delete|url\s*=|path\s*=)\s*\(?\s*"([^"]+)""""
    )
    private val DIRECT_HTTP_URL_REGEX = Regex(""""(https?://[^"\s]+)"""")

    private val CONTROL_KEYWORDS = setOf(
        "if", "for", "while", "switch", "catch", "when", "synchronized", "return", "new", "throw", "super", "this"
    )

    fun parseDiff(rawDiff: String, fileSummaries: List<FileSummary>): List<FileDiffDetail> {
        val summaryByPath = fileSummaries.associateBy { it.path }
        val sections = splitIntoFileSections(rawDiff)
        val detailsByPath = linkedMapOf<String, FileDiffDetail>()

        for (section in sections) {
            val detail = parseSingleFileSection(section, summaryByPath)
            if (detail != null) {
                detailsByPath[detail.summary.path] = detail
            }
        }

        // Preserve order from Phase 1 fileSummaries and include any files without textual diff
        val ordered = mutableListOf<FileDiffDetail>()
        for (summary in fileSummaries) {
            val found = detailsByPath.remove(summary.path)
            if (found != null) {
                ordered.add(found)
            } else {
                ordered.add(
                    FileDiffDetail(
                        summary = summary,
                        stackTags = LayerClassifier.detectStackTags("", summary.path),
                        hunks = emptyList(),
                        addedLinesTotal = 0,
                        deletedLinesTotal = 0,
                        addedComments = emptyList(),
                        apiHints = emptyList(),
                        hasTransactionalChange = false
                    )
                )
            }
        }
        ordered.addAll(detailsByPath.values)
        return ordered
    }

    private fun splitIntoFileSections(rawDiff: String): List<String> {
        if (rawDiff.isBlank()) return emptyList()
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        for (line in rawDiff.lineSequence()) {
            if (line.startsWith("diff --git ")) {
                if (current.isNotEmpty()) {
                    sections.add(current.toString())
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty() || line.startsWith("diff --git ")) {
                current.append(line).append('\n')
            }
        }
        if (current.isNotEmpty()) {
            sections.add(current.toString())
        }
        return sections
    }

    private fun parseSingleFileSection(
        section: String,
        summaryByPath: Map<String, FileSummary>
    ): FileDiffDetail? {
        val lines = section.lines()
        if (lines.isEmpty()) return null

        val headerLine = lines.first()
        var oldPath: String? = null
        var newPath: String? = null
        var isNewFile = false
        var isDeletedFile = false
        var isBinary = false

        for (line in lines.take(20)) {
            when {
                line.startsWith("rename from ") -> oldPath = line.removePrefix("rename from ").trim()
                line.startsWith("rename to ") -> newPath = line.removePrefix("rename to ").trim()
                line.startsWith("new file mode ") -> isNewFile = true
                line.startsWith("deleted file mode ") -> isDeletedFile = true
                line.startsWith("--- a/") -> if (oldPath == null) oldPath = line.removePrefix("--- a/").trim()
                line.startsWith("+++ b/") -> if (newPath == null) newPath = line.removePrefix("+++ b/").trim()
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> isBinary = true
            }
        }

        if (newPath == null) {
            val fallbackPaths = extractPathsFromDiffHeader(headerLine)
            oldPath = oldPath ?: fallbackPaths?.first
            newPath = fallbackPaths?.second
        }
        val resolvedPath = newPath ?: oldPath ?: return null

        val summary = summaryByPath[resolvedPath] ?: FileSummary(
            path = resolvedPath,
            oldPath = if (oldPath != resolvedPath) oldPath else null,
            status = when {
                isNewFile -> FileChangeStatus.ADDED
                isDeletedFile -> FileChangeStatus.DELETED
                oldPath != null && oldPath != resolvedPath -> FileChangeStatus.RENAMED
                else -> FileChangeStatus.MODIFIED
            },
            primaryLayer = LayerClassifier.classifyPrimaryLayer(resolvedPath)
        )

        if (isBinary) {
            return FileDiffDetail(
                summary = summary,
                stackTags = emptySet(),
                hunks = emptyList(),
                addedLinesTotal = 0,
                deletedLinesTotal = 0,
                addedComments = emptyList(),
                apiHints = emptyList(),
                hasTransactionalChange = false,
                skippedLargeOrBinary = true,
                skipReason = CodeTrackerI18n.binarySkipText()
            )
        }

        if (section.toByteArray(Charsets.UTF_8).size > MAX_FILE_DIFF_BYTES) {
            return FileDiffDetail(
                summary = summary,
                stackTags = LayerClassifier.detectStackTags("", resolvedPath),
                hunks = emptyList(),
                addedLinesTotal = 0,
                deletedLinesTotal = 0,
                addedComments = emptyList(),
                apiHints = emptyList(),
                hasTransactionalChange = false,
                skippedLargeOrBinary = true,
                skipReason = CodeTrackerI18n.largeDiffSkipText()
            )
        }

        val hunks = mutableListOf<CodeHunkPointer>()
        val addedComments = linkedSetOf<String>()
        val apiHints = mutableListOf<ApiEndpointHint>()
        val substantiveAddedLines = StringBuilder()
        var addedTotal = 0
        var deletedTotal = 0
        var hasTransactionalChange = false
        var rawHunkCount = 0
        var filteredNoiseHunkCount = 0

        var currentOldStart = 1
        var currentOldCount = 0
        var currentNewStart = 1
        var currentNewCount = 0
        var currentHeaderContext = ""
        var runningNewLine = 1
        var firstNonBlankChangedLine: Int? = null
        var firstAnyChangedLine: Int? = null
        var firstChangedCodeLine: String? = null
        var hunkAdded = 0
        var hunkDeleted = 0
        var hunkDetectedMethod: String? = null
        var hunkSignatureText: String? = null
        var hunkHasHeaderTransactional = false
        val hunkChangedLines = mutableListOf<String>()
        var inHunk = false

        fun flushCurrentHunk() {
            if (!inHunk) return
            rawHunkCount++
            if (hunkChangedLines.isEmpty() || hunkChangedLines.all { isNoiseChangeLine(it) }) {
                filteredNoiseHunkCount++
                return
            }

            if (hunkHasHeaderTransactional) {
                hasTransactionalChange = true
            }

            val methodFromHeader = extractMethodName(currentHeaderContext)
            val bestMethod = hunkDetectedMethod ?: methodFromHeader
            val displayContext = when {
                !hunkSignatureText.isNullOrBlank() -> hunkSignatureText!!
                currentHeaderContext.isNotBlank() -> currentHeaderContext
                !bestMethod.isNullOrBlank() -> bestMethod
                else -> CodeTrackerI18n.fallbackBlockContext()
            }
            val effectiveNewStartLine = (firstNonBlankChangedLine ?: firstAnyChangedLine ?: currentNewStart).coerceAtLeast(1)
            hunks.add(
                CodeHunkPointer(
                    filePath = summary.path,
                    newStartLine = effectiveNewStartLine,
                    newLineCount = currentNewCount,
                    oldStartLine = currentOldStart.coerceAtLeast(1),
                    oldLineCount = currentOldCount,
                    headerContext = currentHeaderContext,
                    detectedMethodName = bestMethod,
                    displayContext = displayContext.take(120),
                    addedLines = hunkAdded,
                    deletedLines = hunkDeleted,
                    firstChangedCodeLine = firstChangedCodeLine
                )
            )
        }

        for (line in lines) {
            val hunkMatch = HUNK_HEADER_REGEX.matchEntire(line)
            if (hunkMatch != null) {
                flushCurrentHunk()
                inHunk = true
                currentOldStart = hunkMatch.groupValues[1].toIntOrNull() ?: 1
                currentOldCount = hunkMatch.groupValues[2].toIntOrNull() ?: 1
                currentNewStart = hunkMatch.groupValues[3].toIntOrNull() ?: 1
                currentNewCount = hunkMatch.groupValues[4].toIntOrNull() ?: 1
                currentHeaderContext = hunkMatch.groupValues[5].trim()
                runningNewLine = currentNewStart
                firstNonBlankChangedLine = null
                firstAnyChangedLine = null
                firstChangedCodeLine = null
                hunkAdded = 0
                hunkDeleted = 0
                hunkDetectedMethod = null
                hunkSignatureText = null
                hunkHasHeaderTransactional = isSubstantiveCodeLine(currentHeaderContext) &&
                    stripInlineComment(currentHeaderContext).contains("@Transactional")
                hunkChangedLines.clear()
                continue
            }

            if (!inHunk) continue

            when {
                line.startsWith("+") && !line.startsWith("+++") -> {
                    val content = line.substring(1)
                    val trimmed = content.trim()
                    hunkAdded++
                    addedTotal++
                    hunkChangedLines.add(content)

                    if (firstAnyChangedLine == null) {
                        firstAnyChangedLine = runningNewLine
                    }
                    if (content.isNotBlank() && firstNonBlankChangedLine == null) {
                        firstNonBlankChangedLine = runningNewLine
                    }
                    if (firstChangedCodeLine == null &&
                        trimmed.length >= 6 &&
                        !trimmed.startsWith("//") &&
                        !trimmed.startsWith("/*") &&
                        !trimmed.startsWith("*") &&
                        !trimmed.startsWith("import ") &&
                        !trimmed.startsWith("package ")
                    ) {
                        firstChangedCodeLine = trimmed
                    }

                    val isSubstantive = isSubstantiveCodeLine(content)
                    val activeCode = if (isSubstantive) stripInlineComment(content) else ""

                    if (isSubstantive) {
                        substantiveAddedLines.append(activeCode).append('\n')
                        if (activeCode.contains("@Transactional")) {
                            hasTransactionalChange = true
                        }
                    }

                    // Extract method signature from added lines if not yet detected in this hunk
                    if (hunkDetectedMethod == null && isSubstantive) {
                        val methodName = extractMethodName(activeCode)
                        if (methodName != null) {
                            hunkDetectedMethod = methodName
                            hunkSignatureText = trimmed
                        }
                    }

                    // Extract newly added comments
                    extractCommentText(content)?.let { comment ->
                        if (addedComments.size < 20) {
                            addedComments.add(comment)
                        }
                    }

                    // Extract API endpoints / External URIs from substantive code lines
                    if (isSubstantive && isApiCandidateLine(summary, activeCode)) {
                        extractApiHints(summary, activeCode, runningNewLine, apiHints)
                    }

                    runningNewLine++
                }

                line.startsWith("-") && !line.startsWith("---") -> {
                    val content = line.substring(1)
                    hunkDeleted++
                    deletedTotal++
                    hunkChangedLines.add(content)

                    if (firstAnyChangedLine == null) {
                        firstAnyChangedLine = runningNewLine
                    }
                    if (content.isNotBlank() && firstNonBlankChangedLine == null) {
                        firstNonBlankChangedLine = runningNewLine
                    }

                    if (isSubstantiveCodeLine(content) && stripInlineComment(content).contains("@Transactional")) {
                        hasTransactionalChange = true
                    }
                    // Deleted line does not exist in the new file -> do NOT increment runningNewLine
                }

                line.startsWith(" ") -> {
                    runningNewLine++
                }
            }
        }
        flushCurrentHunk()

        val onlyImportOrWhitespace = rawHunkCount > 0 && hunks.isEmpty() && filteredNoiseHunkCount > 0
        val stackTags = if (onlyImportOrWhitespace) {
            emptySet()
        } else {
            LayerClassifier.detectStackTags(substantiveAddedLines.toString(), resolvedPath)
        }

        return FileDiffDetail(
            summary = summary,
            stackTags = stackTags,
            hunks = hunks,
            addedLinesTotal = addedTotal,
            deletedLinesTotal = deletedTotal,
            addedComments = addedComments.toList(),
            apiHints = apiHints.distinctBy { "${it.kind}|${it.methodOrAnnotation}|${it.pathOrUri}" },
            hasTransactionalChange = hasTransactionalChange,
            onlyImportOrWhitespaceChanges = onlyImportOrWhitespace
        )
    }

    private fun isNoiseChangeLine(codeLine: String): Boolean {
        val trimmed = codeLine.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("import ") || trimmed == "import") return true
        if (trimmed.startsWith("package ") || trimmed == "package") return true
        return false
    }

    internal fun isSubstantiveCodeLine(codeLine: String): Boolean {
        val trimmed = codeLine.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("import ") || trimmed == "import") return false
        if (trimmed.startsWith("package ") || trimmed == "package") return false
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
            return false
        }
        return true
    }

    private fun stripInlineComment(codeLine: String): String {
        val idx = findInlineCommentIndex(codeLine)
        return if (idx >= 0) codeLine.substring(0, idx) else codeLine
    }

    private fun isApiCandidateLine(summary: FileSummary, activeCode: String): Boolean {
        if (summary.primaryLayer == PrimaryLayer.CONTROLLER_API ||
            summary.primaryLayer == PrimaryLayer.EXTERNAL_MSA_CLIENT
        ) {
            return true
        }
        return activeCode.contains("@FeignClient") ||
            activeCode.contains("RestTemplate") ||
            activeCode.contains("WebClient") ||
            activeCode.contains("RestClient")
    }

    private fun extractPathsFromDiffHeader(header: String): Pair<String, String>? {
        // diff --git a/path/one b/path/two
        val body = header.removePrefix("diff --git ").trim()
        val splitIdx = body.indexOf(" b/")
        if (splitIdx <= 2) return null
        val aPart = body.substring(0, splitIdx).removePrefix("a/").trim('"')
        val bPart = body.substring(splitIdx + 1).removePrefix("b/").trim('"')
        return aPart to bPart
    }

    internal fun extractMethodName(codeLine: String): String? {
        val trimmed = codeLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return null
        }

        MYBATIS_ID_REGEX.find(trimmed)?.let { m ->
            return "${m.groupValues[1]}#${m.groupValues[2]}"
        }

        KOTLIN_FUN_REGEX.find(trimmed)?.let { m ->
            val name = m.groupValues[1]
            if (name !in CONTROL_KEYWORDS) return name
        }

        if (!trimmed.startsWith("@") && !trimmed.contains("=") && !trimmed.endsWith(";")) {
            JAVA_METHOD_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                if (name !in CONTROL_KEYWORDS && name.first().isLowerCase()) {
                    return name
                }
            }
        }
        return null
    }

    private fun extractCommentText(codeLine: String): String? {
        val trimmed = codeLine.trim()
        if (trimmed.isEmpty()) return null

        val extracted = when {
            trimmed.startsWith("//") -> trimmed.removePrefix("//").trim()
            trimmed.startsWith("/*") -> trimmed.removePrefix("/*").removeSuffix("*/").trim('*', ' ')
            trimmed.startsWith("*") && !trimmed.startsWith("*/") -> trimmed.removePrefix("*").trim()
            else -> {
                // Inline comment avoiding URLs like http:// or https://
                val idx = findInlineCommentIndex(trimmed)
                if (idx >= 0) trimmed.substring(idx + 2).trim() else null
            }
        } ?: return null

        // Ignore trivial/noisy comments
        if (extracted.length < 3 || extracted.all { it == '-' || it == '=' || it == '*' || it == '/' }) {
            return null
        }
        return extracted.take(180)
    }

    private fun findInlineCommentIndex(line: String): Int {
        var inString = false
        var prevChar = '\u0000'
        for (i in 0 until line.length - 1) {
            val c = line[i]
            if (c == '"' && prevChar != '\\') {
                inString = !inString
            } else if (!inString && c == '/' && line[i + 1] == '/') {
                if (i == 0 || line[i - 1] != ':') {
                    return i
                }
            }
            prevChar = c
        }
        return -1
    }

    private fun extractApiHints(
        summary: FileSummary,
        codeLine: String,
        lineNumber: Int,
        out: MutableList<ApiEndpointHint>
    ) {
        val kind = if (summary.primaryLayer == PrimaryLayer.CONTROLLER_API) {
            "Internal API"
        } else {
            "External/MSA Call"
        }

        HTTP_MAPPING_REGEX.find(codeLine)?.let { m ->
            val ann = "@${m.groupValues[1]}"
            val args = m.groupValues[2]
            val uris = QUOTED_URI_REGEX.findAll(args).map { it.groupValues[1] }.toList()
            val pathStr = if (uris.isNotEmpty()) uris.joinToString(", ") else "(root)"
            out.add(ApiEndpointHint(summary.fileName, kind, ann, pathStr, lineNumber))
        }

        FEIGN_CLIENT_REGEX.find(codeLine)?.let { m ->
            val args = m.groupValues[1].trim()
            out.add(ApiEndpointHint(summary.fileName, "External/MSA Call", "@FeignClient", args.take(100), lineNumber))
        }

        EXTERNAL_CALL_REGEX.find(codeLine)?.let { m ->
            val uri = m.groupValues[1]
            out.add(ApiEndpointHint(summary.fileName, "External/MSA Call", "HTTP Call", uri, lineNumber))
        }

        DIRECT_HTTP_URL_REGEX.find(codeLine)?.let { m ->
            val url = m.groupValues[1]
            out.add(ApiEndpointHint(summary.fileName, "External/MSA Call", "External URL", url, lineNumber))
        }
    }
}
