package com.maekom.codetracker.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.RadarSeverity
import com.maekom.codetracker.model.SideEffectAlert
import com.maekom.codetracker.model.SideEffectRule
import com.maekom.codetracker.model.StackTag
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Production Side-Effect Risk Radar cross-analyzing Unified Diff hunks and Java PSI context.
 * Tracks exact (runningOldLine, runningNewLine) per hunk line and binds commitHash, filePath,
 * preferLeftSide, and riskCodeSnippet for pinpoint Before/After Diff scrolling.
 * Strictly excludes `src/test/` paths.
 */
object SideEffectRadarAnalyzer {

    private val TX_OPTION_REGEX = Regex("""(readOnly|propagation|isolation|rollbackFor|noRollbackFor)\s*=""")
    private val MYBATIS_UPDATE_DELETE_OPEN_REGEX = Regex("""<(update|delete)\b""", RegexOption.IGNORE_CASE)
    private val MYBATIS_UPDATE_DELETE_CLOSE_REGEX = Regex("""</(update|delete)\s*>""", RegexOption.IGNORE_CASE)
    private val SQL_WHERE_REGEX = Regex("""(<\s*/?\s*where\b|\bwhere\b)""", RegexOption.IGNORE_CASE)

    private val DTO_NAME_TOKENS = listOf("dto", "request", "response", "req", "res", "vo")
    private val JAVA_FIELD_DECL_REGEX = Regex(
        """^\s*(?:private|protected|public)\s+(?:final\s+)?([A-Za-z0-9_$.<>\[\]?,\s]+?)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:=|;).*$"""
    )
    private val KOTLIN_PROP_DECL_REGEX = Regex(
        """^\s*(?:private|protected|public|internal)?\s*(?:val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*([^=,\)]+)"""
    )

    private val RESILIENCE_CONFIG_REGEX = Regex(
        """(connect-?timeout|read-?timeout|response-?timeout|write-?timeout|socket-?timeout|timeout|retry|max-?attempts|backoff|feign\.client\.config)""",
        RegexOption.IGNORE_CASE
    )
    private val EMPTY_CATCH_SINGLE_LINE_REGEX = Regex("""\bcatch\s*\([^)]*\)\s*\{\s*\}""")
    private val CATCH_OPEN_REGEX = Regex("""\bcatch\s*\([^)]*\)\s*\{\s*$""")

    private data class IndexedDiffLine(
        val prefix: Char,
        val content: String,
        val oldLine: Int,
        val newLine: Int
    )

    fun analyzeFiles(
        project: Project,
        repoRootPath: String,
        fileDetails: List<FileDiffDetail>,
        commitHash: String
    ): Pair<List<FileDiffDetail>, List<SideEffectAlert>> {
        val allAlerts = mutableListOf<SideEffectAlert>()
        val updatedDetails = fileDetails.map { fd ->
            val normalizedPath = fd.summary.path.replace('\\', '/')
            if (isTestPath(normalizedPath) || fd.skippedLargeOrBinary || fd.onlyImportOrWhitespaceChanges || fd.hunks.isEmpty()) {
                return@map fd
            }

            val fileAlerts = mutableListOf<SideEffectAlert>()
            val hunkAlertMap = mutableMapOf<Int, SideEffectAlert>()

            val txInfo = if (normalizedPath.endsWith(".java", true) || normalizedPath.endsWith(".kt", true)) {
                inspectTransactionalMethods(project, repoRootPath, normalizedPath)
            } else {
                null
            }
            val xmlLines = if (normalizedPath.endsWith(".xml", true)) {
                runCatching { Files.readAllLines(Path.of(repoRootPath, normalizedPath).normalize(), StandardCharsets.UTF_8) }
                    .getOrNull()
            } else {
                null
            }

            for (hunk in fd.hunks) {
                val indexedLines = indexHunkLines(hunk)
                val hunkAlerts = mutableListOf<SideEffectAlert>()

                // Rule 1 [HIGH]: Transaction boundary change
                detectTransactionBoundaryChange(fd, hunk, indexedLines, commitHash)?.let { hunkAlerts.add(it) }

                // Rule 2 [HIGH]: Transaction Self-Invocation
                if (txInfo != null && txInfo.transactionalMethods.isNotEmpty()) {
                    detectTransactionSelfInvocation(project, repoRootPath, normalizedPath, fd, hunk, indexedLines, txInfo, commitHash)
                        ?.let { hunkAlerts.add(it) }
                }

                // Rule 3 [HIGH]: MyBatis / DB bulk update/delete WHERE risk
                if (normalizedPath.endsWith(".xml", true)) {
                    detectMyBatisBulkRisk(fd, hunk, indexedLines, xmlLines, commitHash)?.let { hunkAlerts.add(it) }
                }

                // Rule 4 [WARN]: DTO breaking change / External timeout-retry / Empty catch
                detectExternalAndSpecRisks(fd, hunk, indexedLines, commitHash).forEach { hunkAlerts.add(it) }

                if (hunkAlerts.isNotEmpty()) {
                    fileAlerts.addAll(hunkAlerts)
                    val primary = hunkAlerts.minByOrNull { it.severity.ordinal }!!
                    hunkAlertMap[hunk.newStartLine] = primary
                }
            }

            allAlerts.addAll(fileAlerts)
            if (hunkAlertMap.isEmpty()) {
                fd
            } else {
                fd.copy(
                    hunks = fd.hunks.map { h ->
                        val alert = hunkAlertMap[h.newStartLine]
                        if (alert != null) {
                            h.copy(highestRadarSeverity = alert.severity, primaryRadarAlert = alert)
                        } else {
                            h
                        }
                    }
                )
            }
        }

        val sortedAlerts = allAlerts
            .distinctBy { "${it.commitHash}|${it.rule}|${it.filePath}|${it.exactRiskNewLine}|${it.exactRiskOldLine}|${it.detailKo}" }
            .sortedWith(
                compareBy<SideEffectAlert> { it.severity.ordinal }
                    .thenBy { it.fileName }
                    .thenBy { it.displayLineNumber() }
            )

        return updatedDetails to sortedAlerts
    }

    private fun indexHunkLines(hunk: CodeHunkPointer): List<IndexedDiffLine> {
        val result = ArrayList<IndexedDiffLine>(hunk.rawDiffLines.size)
        var runningOld = hunk.hunkHeaderOldStartLine.coerceAtLeast(1)
        var runningNew = hunk.hunkHeaderNewStartLine.coerceAtLeast(1)

        for (raw in hunk.rawDiffLines) {
            when {
                raw.startsWith("+") && !raw.startsWith("+++") -> {
                    result.add(IndexedDiffLine('+', raw.substring(1), runningOld, runningNew))
                    runningNew++
                }
                raw.startsWith("-") && !raw.startsWith("---") -> {
                    result.add(IndexedDiffLine('-', raw.substring(1), runningOld, runningNew))
                    runningOld++
                }
                raw.startsWith(" ") -> {
                    result.add(IndexedDiffLine(' ', raw.substring(1), runningOld, runningNew))
                    runningOld++
                    runningNew++
                }
            }
        }
        return result
    }

    private fun isTestPath(normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()
        return lower.contains("src/test/") || lower.contains("/test/") || lower.startsWith("test/")
    }

    private fun detectTransactionBoundaryChange(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        indexedLines: List<IndexedDiffLine>,
        commitHash: String
    ): SideEffectAlert? {
        val addedTx = indexedLines.filter {
            it.prefix == '+' && UnifiedDiffParser.isSubstantiveCodeLine(it.content) && it.content.contains("@Transactional")
        }
        val removedTx = indexedLines.filter {
            it.prefix == '-' && UnifiedDiffParser.isSubstantiveCodeLine(it.content) && it.content.contains("@Transactional")
        }
        if (addedTx.isEmpty() && removedTx.isEmpty()) return null

        val preferLeft: Boolean
        val exactNew: Int
        val exactOld: Int
        val snippet: String
        val detailPair: Pair<String, String>

        when {
            addedTx.isNotEmpty() && removedTx.isNotEmpty() -> {
                val firstAdd = addedTx.first()
                preferLeft = false
                exactNew = firstAdd.newLine.coerceAtLeast(1)
                exactOld = removedTx.first().oldLine.coerceAtLeast(1)
                snippet = firstAdd.content.trim()
                val hasOptionChange = addedTx.any { TX_OPTION_REGEX.containsMatchIn(it.content) } ||
                    removedTx.any { TX_OPTION_REGEX.containsMatchIn(it.content) }
                detailPair = if (hasOptionChange) {
                    "@Transactional 옵션(readOnly/propagation/isolation/rollbackFor) 변경 감지" to
                        "@Transactional attributes (readOnly/propagation/isolation/rollbackFor) modified"
                } else {
                    "@Transactional 선언 변경 감지" to "@Transactional declaration modified"
                }
            }
            addedTx.isNotEmpty() -> {
                val firstAdd = addedTx.first()
                preferLeft = false
                exactNew = firstAdd.newLine.coerceAtLeast(1)
                exactOld = firstAdd.oldLine.coerceAtLeast(1)
                snippet = firstAdd.content.trim()
                detailPair = "@Transactional 어노테이션 신규 추가" to "@Transactional annotation added"
            }
            else -> {
                val firstRem = removedTx.first()
                preferLeft = true
                exactOld = firstRem.oldLine.coerceAtLeast(1)
                exactNew = firstRem.newLine.coerceAtLeast(1)
                snippet = firstRem.content.trim()
                detailPair = "@Transactional 어노테이션 삭제됨 (롤백 경계 해제 주의)" to "@Transactional annotation removed (rollback boundary dropped)"
            }
        }

        return SideEffectAlert(
            severity = RadarSeverity.HIGH,
            rule = SideEffectRule.TRANSACTION_BOUNDARY_CHANGE,
            commitHash = commitHash,
            filePath = fd.summary.path,
            fileName = fd.summary.fileName,
            hunkPointer = hunk,
            exactRiskNewLine = exactNew,
            exactRiskOldLine = exactOld,
            preferLeftSide = preferLeft,
            riskCodeSnippet = snippet.ifBlank { null },
            detailKo = detailPair.first,
            detailEn = detailPair.second
        )
    }

    private data class ClassTransactionalInfo(
        val classHasTransactional: Boolean,
        val transactionalMethods: Set<String>
    )

    private fun inspectTransactionalMethods(
        project: Project,
        repoRootPath: String,
        relPath: String
    ): ClassTransactionalInfo? {
        if (project.isDisposed || DumbService.getInstance(project).isDumb) {
            return null
        }
        return try {
            ApplicationManager.getApplication().runReadAction<ClassTransactionalInfo?> {
                val vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(repoRootPath, relPath).normalize())
                    ?: return@runReadAction null
                val psiFile = PsiManager.getInstance(project).findFile(vf) as? PsiClassOwner ?: return@runReadAction null
                var classTx = false
                val txMethods = linkedSetOf<String>()
                for (cls in psiFile.classes) {
                    val clsAnnotated = cls.annotations.any {
                        it.qualifiedName?.endsWith("Transactional") == true || it.nameReferenceElement?.referenceName == "Transactional"
                    }
                    if (clsAnnotated) classTx = true
                    for (m in cls.methods) {
                        val mAnnotated = m.annotations.any {
                            it.qualifiedName?.endsWith("Transactional") == true || it.nameReferenceElement?.referenceName == "Transactional"
                        }
                        if (mAnnotated) {
                            txMethods.add(m.name)
                        }
                    }
                }
                ClassTransactionalInfo(classTx, txMethods)
            }
        } catch (_: IndexNotReadyException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectTransactionSelfInvocation(
        project: Project,
        repoRootPath: String,
        relPath: String,
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        indexedLines: List<IndexedDiffLine>,
        txInfo: ClassTransactionalInfo,
        commitHash: String
    ): SideEffectAlert? {
        if (txInfo.classHasTransactional) return null

        val enclosingMethodName = resolveEnclosingMethodName(project, repoRootPath, relPath, hunk)
        if (enclosingMethodName != null && enclosingMethodName in txInfo.transactionalMethods) {
            return null
        }

        val addedLines = indexedLines.filter { it.prefix == '+' && UnifiedDiffParser.isSubstantiveCodeLine(it.content) }

        for (txMethod in txInfo.transactionalMethods) {
            if (txMethod == enclosingMethodName) continue
            val selfCallRegex = Regex("""(?:\bthis\s*\.\s*|(?<![A-Za-z0-9_.\s]\.)(?<!\bfun\s)(?<!\bvoid\s))$txMethod\s*\(""")
            for (line in addedLines) {
                val trimmed = line.content.trim()
                if (trimmed.startsWith("public ") || trimmed.startsWith("private ") || trimmed.startsWith("protected ") || trimmed.startsWith("fun ")) {
                    continue
                }
                if (selfCallRegex.containsMatchIn(trimmed) &&
                    !Regex("""[A-Za-z0-9_]+\s*\.\s*$txMethod\s*\(""").containsMatchIn(trimmed.replace("this.", ""))
                ) {
                    val callerDesc = enclosingMethodName ?: hunk.displayContext
                    return SideEffectAlert(
                        severity = RadarSeverity.HIGH,
                        rule = SideEffectRule.TRANSACTION_SELF_INVOCATION,
                        commitHash = commitHash,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        exactRiskNewLine = line.newLine.coerceAtLeast(1),
                        exactRiskOldLine = line.oldLine.coerceAtLeast(1),
                        preferLeftSide = false,
                        riskCodeSnippet = trimmed,
                        detailKo = "비-트랜잭션 메서드($callerDesc)에서 동일 클래스 @Transactional 메서드 '$txMethod()' 내부 호출 감지 (프록시 우회 위험)",
                        detailEn = "Non-transactional method ($callerDesc) invokes same-class @Transactional method '$txMethod()' (AOP proxy bypass risk)"
                    )
                }
            }
        }
        return null
    }

    private fun resolveEnclosingMethodName(
        project: Project,
        repoRootPath: String,
        relPath: String,
        hunk: CodeHunkPointer
    ): String? {
        if (!project.isDisposed && !DumbService.getInstance(project).isDumb) {
            val fromPsi = runCatching {
                ApplicationManager.getApplication().runReadAction<String?> {
                    val vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(repoRootPath, relPath).normalize())
                        ?: return@runReadAction null
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@runReadAction null
                    val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction null
                    if (doc.lineCount == 0) return@runReadAction null
                    val zeroLine = (hunk.newStartLine - 1).coerceIn(0, doc.lineCount - 1)
                    val elem = psiFile.findElementAt(doc.getLineStartOffset(zeroLine))
                    PsiTreeUtil.getParentOfType(elem, PsiMethod::class.java, false)?.name
                }
            }.getOrNull()
            if (!fromPsi.isNullOrBlank()) return fromPsi
        }
        return hunk.detectedMethodName?.substringAfter('#')?.substringBefore('(')?.trim()
    }

    private fun detectMyBatisBulkRisk(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        indexedLines: List<IndexedDiffLine>,
        xmlLines: List<String>?,
        commitHash: String
    ): SideEffectAlert? {
        val removedWhere = indexedLines.firstOrNull { it.prefix == '-' && SQL_WHERE_REGEX.containsMatchIn(it.content) }
        val addedWhere = indexedLines.firstOrNull { it.prefix == '+' && SQL_WHERE_REGEX.containsMatchIn(it.content) }
        val hitLine = removedWhere ?: addedWhere ?: return null

        if (!isHunkInsideUpdateOrDelete(hunk, xmlLines)) return null

        val preferLeft = removedWhere != null
        return SideEffectAlert(
            severity = RadarSeverity.HIGH,
            rule = SideEffectRule.MYBATIS_BULK_UPDATE_DELETE_RISK,
            commitHash = commitHash,
            filePath = fd.summary.path,
            fileName = fd.summary.fileName,
            hunkPointer = hunk,
            exactRiskNewLine = hitLine.newLine.coerceAtLeast(1),
            exactRiskOldLine = hitLine.oldLine.coerceAtLeast(1),
            preferLeftSide = preferLeft,
            riskCodeSnippet = hitLine.content.trim().ifBlank { hunk.detectedMethodName },
            detailKo = "<update>/<delete> 구문 내 WHERE / <where> 조건절 수정·삭제 감지 (전체 테이블 갱신·삭제 주의)",
            detailEn = "WHERE / <where> clause modified or removed inside <update>/<delete> statement (full-table impact risk)"
        )
    }

    private fun isHunkInsideUpdateOrDelete(hunk: CodeHunkPointer, xmlLines: List<String>?): Boolean {
        if (MYBATIS_UPDATE_DELETE_OPEN_REGEX.containsMatchIn(hunk.headerContext)) return true
        val detected = hunk.detectedMethodName?.lowercase().orEmpty()
        if (detected.startsWith("update#") || detected.startsWith("delete#")) return true
        if (hunk.rawDiffLines.any { MYBATIS_UPDATE_DELETE_OPEN_REGEX.containsMatchIn(it) }) return true

        if (!xmlLines.isNullOrEmpty()) {
            val startIdx = (hunk.newStartLine - 1).coerceIn(0, xmlLines.lastIndex)
            val minIdx = (startIdx - 80).coerceAtLeast(0)
            for (i in startIdx downTo minIdx) {
                val line = xmlLines[i]
                if (MYBATIS_UPDATE_DELETE_CLOSE_REGEX.containsMatchIn(line)) return false
                if (MYBATIS_UPDATE_DELETE_OPEN_REGEX.containsMatchIn(line)) return true
            }
        }
        return false
    }

    private data class FieldSignatureLine(val type: String, val line: IndexedDiffLine)

    private fun detectExternalAndSpecRisks(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        indexedLines: List<IndexedDiffLine>,
        commitHash: String
    ): List<SideEffectAlert> {
        val alerts = mutableListOf<SideEffectAlert>()
        val lowerName = fd.summary.fileName.lowercase()
        val lowerPath = fd.summary.path.replace('\\', '/').lowercase()

        // 4A: DTO / Request / Response existing field deletion or type change
        val isDtoFile = DTO_NAME_TOKENS.any { lowerName.contains(it) || lowerPath.contains("/$it/") }
        if (isDtoFile) {
            val removedFields = extractFieldSignatures(indexedLines.filter { it.prefix == '-' })
            val addedFields = extractFieldSignatures(indexedLines.filter { it.prefix == '+' })

            val brokenDescriptions = mutableListOf<String>()
            var firstHitLine: IndexedDiffLine? = null
            var preferLeft = true

            for ((fieldName, oldSig) in removedFields) {
                val newSig = addedFields[fieldName]
                if (newSig == null) {
                    brokenDescriptions.add("$fieldName (삭제/removed)")
                    if (firstHitLine == null) {
                        firstHitLine = oldSig.line
                        preferLeft = true
                    }
                } else if (newSig.type != oldSig.type) {
                    brokenDescriptions.add("$fieldName (${oldSig.type} -> ${newSig.type})")
                    if (firstHitLine == null) {
                        firstHitLine = newSig.line
                        preferLeft = false
                    }
                }
            }
            if (brokenDescriptions.isNotEmpty() && firstHitLine != null) {
                val sample = brokenDescriptions.take(3).joinToString(", ")
                alerts.add(
                    SideEffectAlert(
                        severity = RadarSeverity.WARN,
                        rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                        commitHash = commitHash,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        exactRiskNewLine = firstHitLine.newLine.coerceAtLeast(1),
                        exactRiskOldLine = firstHitLine.oldLine.coerceAtLeast(1),
                        preferLeftSide = preferLeft,
                        riskCodeSnippet = firstHitLine.content.trim().ifBlank { null },
                        detailKo = "DTO/API 스펙 필드 삭제 또는 타입 변경 감지: $sample",
                        detailEn = "DTO/API spec field removed or type changed: $sample"
                    )
                )
            }
        }

        // 4B: External client timeout / retry config change
        val isExternalOrConfig = fd.summary.primaryLayer == PrimaryLayer.EXTERNAL_MSA_CLIENT ||
            fd.summary.primaryLayer == PrimaryLayer.CONFIG_SECURITY ||
            StackTag.MSA_LEGACY_API in fd.stackTags ||
            lowerName.startsWith("application")
        if (isExternalOrConfig) {
            val addedResilience = indexedLines.firstOrNull {
                it.prefix == '+' && UnifiedDiffParser.isSubstantiveCodeLine(it.content) && RESILIENCE_CONFIG_REGEX.containsMatchIn(it.content)
            }
            val removedResilience = indexedLines.firstOrNull {
                it.prefix == '-' && UnifiedDiffParser.isSubstantiveCodeLine(it.content) && RESILIENCE_CONFIG_REGEX.containsMatchIn(it.content)
            }
            val hit = addedResilience ?: removedResilience
            if (hit != null) {
                alerts.add(
                    SideEffectAlert(
                        severity = RadarSeverity.WARN,
                        rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                        commitHash = commitHash,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        exactRiskNewLine = hit.newLine.coerceAtLeast(1),
                        exactRiskOldLine = hit.oldLine.coerceAtLeast(1),
                        preferLeftSide = addedResilience == null,
                        riskCodeSnippet = hit.content.trim().ifBlank { null },
                        detailKo = "외부 API 연동 timeout / retry 설정 변경 감지 (장애 전파·스레드 풀 고갈 주의)",
                        detailEn = "External API timeout/retry settings modified (check cascading failure / thread pool impact)"
                    )
                )
            }
        }

        // 4C: Empty catch block added
        val addedIndexed = indexedLines.filter { it.prefix == '+' }
        findEmptyCatchLine(addedIndexed)?.let { catchLine ->
            alerts.add(
                SideEffectAlert(
                    severity = RadarSeverity.WARN,
                    rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                    commitHash = commitHash,
                    filePath = fd.summary.path,
                    fileName = fd.summary.fileName,
                    hunkPointer = hunk,
                    exactRiskNewLine = catchLine.newLine.coerceAtLeast(1),
                    exactRiskOldLine = catchLine.oldLine.coerceAtLeast(1),
                    preferLeftSide = false,
                    riskCodeSnippet = catchLine.content.trim().ifBlank { null },
                    detailKo = "빈 catch 블록 추가 감지 (예외 삼킴으로 장애 원인 추적 불가 위험)",
                    detailEn = "Empty catch block added (swallowing exceptions may hide production failures)"
                )
            )
        }

        return alerts
    }

    private fun extractFieldSignatures(diffLines: List<IndexedDiffLine>): Map<String, FieldSignatureLine> {
        val result = linkedMapOf<String, FieldSignatureLine>()
        for (line in diffLines) {
            val code = line.content
            if (!UnifiedDiffParser.isSubstantiveCodeLine(code)) continue
            JAVA_FIELD_DECL_REGEX.matchEntire(code)?.let { m ->
                val type = m.groupValues[1].trim()
                val name = m.groupValues[2].trim()
                if (!type.contains("(")) {
                    result[name] = FieldSignatureLine(type, line)
                }
            }
            KOTLIN_PROP_DECL_REGEX.find(code)?.let { m ->
                val name = m.groupValues[1].trim()
                val type = m.groupValues[2].trim()
                result[name] = FieldSignatureLine(type, line)
            }
        }
        return result
    }

    private fun findEmptyCatchLine(addedLines: List<IndexedDiffLine>): IndexedDiffLine? {
        for (i in addedLines.indices) {
            val trimmed = addedLines[i].content.trim()
            if (EMPTY_CATCH_SINGLE_LINE_REGEX.containsMatchIn(trimmed)) {
                return addedLines[i]
            }
            if (CATCH_OPEN_REGEX.containsMatchIn(trimmed)) {
                var j = i + 1
                while (j < addedLines.size) {
                    val nextTrimmed = addedLines[j].content.trim()
                    if (nextTrimmed.isEmpty() || nextTrimmed.startsWith("//") || nextTrimmed.startsWith("/*") || nextTrimmed.startsWith("*")) {
                        j++
                        continue
                    }
                    if (nextTrimmed == "}" || nextTrimmed.startsWith("}")) {
                        return addedLines[i]
                    }
                    break
                }
            }
        }
        return null
    }
}
