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
 * Strictly excludes `src/test/` paths to prevent test-code false positives.
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

    fun analyzeFiles(
        project: Project,
        repoRootPath: String,
        fileDetails: List<FileDiffDetail>,
        commitHash: String? = null
    ): Pair<List<FileDiffDetail>, List<SideEffectAlert>> {
        val allAlerts = mutableListOf<SideEffectAlert>()
        val updatedDetails = fileDetails.map { fd ->
            val normalizedPath = fd.summary.path.replace('\\', '/')
            if (isTestPath(normalizedPath) || fd.skippedLargeOrBinary || fd.onlyImportOrWhitespaceChanges || fd.hunks.isEmpty()) {
                return@map fd
            }

            val fileAlerts = mutableListOf<SideEffectAlert>()
            val hunkSeverityMap = mutableMapOf<Int, RadarSeverity>()

            // Preload PSI / text info for Self-Invocation & MyBatis context
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
                val hunkAlerts = mutableListOf<SideEffectAlert>()

                // Rule 1 [HIGH]: Transaction boundary change
                detectTransactionBoundaryChange(fd, hunk, commitHash)?.let { hunkAlerts.add(it) }

                // Rule 2 [HIGH]: Transaction Self-Invocation
                if (txInfo != null && txInfo.transactionalMethods.isNotEmpty()) {
                    detectTransactionSelfInvocation(project, repoRootPath, normalizedPath, fd, hunk, txInfo, commitHash)
                        ?.let { hunkAlerts.add(it) }
                }

                // Rule 3 [HIGH]: MyBatis / DB bulk update/delete WHERE risk
                if (normalizedPath.endsWith(".xml", true)) {
                    detectMyBatisBulkRisk(fd, hunk, xmlLines, commitHash)?.let { hunkAlerts.add(it) }
                }

                // Rule 4 [WARN]: DTO breaking change / External timeout-retry / Empty catch
                detectExternalAndSpecRisks(fd, hunk, commitHash).forEach { hunkAlerts.add(it) }

                if (hunkAlerts.isNotEmpty()) {
                    fileAlerts.addAll(hunkAlerts)
                    val highest = if (hunkAlerts.any { it.severity == RadarSeverity.HIGH }) {
                        RadarSeverity.HIGH
                    } else {
                        RadarSeverity.WARN
                    }
                    hunkSeverityMap[hunk.newStartLine] = highest
                }
            }

            allAlerts.addAll(fileAlerts)
            if (hunkSeverityMap.isEmpty()) {
                fd
            } else {
                fd.copy(
                    hunks = fd.hunks.map { h ->
                        val sev = hunkSeverityMap[h.newStartLine]
                        if (sev != null) h.copy(highestRadarSeverity = sev) else h
                    }
                )
            }
        }

        val sortedAlerts = allAlerts
            .distinctBy { "${it.rule}|${it.filePath}|${it.hunkPointer.newStartLine}|${it.detailKo}" }
            .sortedWith(compareBy<SideEffectAlert> { it.severity.ordinal }.thenBy { it.fileName }.thenBy { it.hunkPointer.newStartLine })

        return updatedDetails to sortedAlerts
    }

    private fun isTestPath(normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()
        return lower.contains("src/test/") || lower.startsWith("test/")
    }

    private fun detectTransactionBoundaryChange(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        commitHash: String?
    ): SideEffectAlert? {
        val addedTx = hunk.rawDiffLines.filter {
            it.startsWith("+") && !it.startsWith("+++") &&
                UnifiedDiffParser.isSubstantiveCodeLine(it.substring(1)) &&
                it.substring(1).contains("@Transactional")
        }
        val removedTx = hunk.rawDiffLines.filter {
            it.startsWith("-") && !it.startsWith("---") &&
                UnifiedDiffParser.isSubstantiveCodeLine(it.substring(1)) &&
                it.substring(1).contains("@Transactional")
        }
        if (addedTx.isEmpty() && removedTx.isEmpty()) return null

        val detailPair = when {
            addedTx.isNotEmpty() && removedTx.isNotEmpty() -> {
                val hasOptionChange = addedTx.any { TX_OPTION_REGEX.containsMatchIn(it) } ||
                    removedTx.any { TX_OPTION_REGEX.containsMatchIn(it) }
                if (hasOptionChange) {
                    "@Transactional 옵션(readOnly/propagation/isolation/rollbackFor) 변경 감지" to
                        "@Transactional attributes (readOnly/propagation/isolation/rollbackFor) modified"
                } else {
                    "@Transactional 선언 변경 감지" to "@Transactional declaration modified"
                }
            }
            addedTx.isNotEmpty() -> "@Transactional 어노테이션 신규 추가" to "@Transactional annotation added"
            else -> "@Transactional 어노테이션 삭제됨 (롤백 경계 해제 주의)" to "@Transactional annotation removed (rollback boundary dropped)"
        }

        return SideEffectAlert(
            severity = RadarSeverity.HIGH,
            rule = SideEffectRule.TRANSACTION_BOUNDARY_CHANGE,
            filePath = fd.summary.path,
            fileName = fd.summary.fileName,
            hunkPointer = hunk,
            detailKo = detailPair.first,
            detailEn = detailPair.second,
            commitHash = commitHash
        )
    }

    private data class ClassTransactionalInfo(
        val classHasTransactional: Boolean,
        val transactionalMethods: Set<String>,
        val nonTransactionalMethods: Set<String>
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
                val nonTxMethods = linkedSetOf<String>()
                for (cls in psiFile.classes) {
                    val clsAnnotated = cls.annotations.any { it.qualifiedName?.endsWith("Transactional") == true || it.nameReferenceElement?.referenceName == "Transactional" }
                    if (clsAnnotated) classTx = true
                    for (m in cls.methods) {
                        val mAnnotated = m.annotations.any { it.qualifiedName?.endsWith("Transactional") == true || it.nameReferenceElement?.referenceName == "Transactional" }
                        if (mAnnotated) {
                            txMethods.add(m.name)
                        } else if (!clsAnnotated) {
                            nonTxMethods.add(m.name)
                        }
                    }
                }
                ClassTransactionalInfo(classTx, txMethods, nonTxMethods)
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
        txInfo: ClassTransactionalInfo,
        commitHash: String?
    ): SideEffectAlert? {
        if (txInfo.classHasTransactional) return null

        val enclosingMethodName = resolveEnclosingMethodName(project, repoRootPath, relPath, hunk)
        // If enclosing method itself is @Transactional, skip
        if (enclosingMethodName != null && enclosingMethodName in txInfo.transactionalMethods) {
            return null
        }

        val addedLines = hunk.rawDiffLines
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .map { it.substring(1) }
            .filter { UnifiedDiffParser.isSubstantiveCodeLine(it) }

        for (txMethod in txInfo.transactionalMethods) {
            if (txMethod == enclosingMethodName) continue
            // Match `this.txMethod(` or unqualified `txMethod(` (not `otherBean.txMethod(` or declaration `fun txMethod(`)
            val selfCallRegex = Regex("""(?:\bthis\s*\.\s*|(?<![A-Za-z0-9_.\s]\.)(?<!\bfun\s)(?<!\bvoid\s))$txMethod\s*\(""")
            for (code in addedLines) {
                val trimmed = code.trim()
                if (trimmed.startsWith("public ") || trimmed.startsWith("private ") || trimmed.startsWith("protected ") || trimmed.startsWith("fun ")) {
                    continue
                }
                if (selfCallRegex.containsMatchIn(trimmed) && !Regex("""[A-Za-z0-9_]+\s*\.\s*$txMethod\s*\(""").containsMatchIn(trimmed.replace("this.", ""))) {
                    val callerDesc = enclosingMethodName ?: hunk.displayContext
                    return SideEffectAlert(
                        severity = RadarSeverity.HIGH,
                        rule = SideEffectRule.TRANSACTION_SELF_INVOCATION,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        detailKo = "비-트랜잭션 메서드($callerDesc)에서 동일 클래스 @Transactional 메서드 '$txMethod()' 내부 호출 감지 (프록시 우회 위험)",
                        detailEn = "Non-transactional method ($callerDesc) invokes same-class @Transactional method '$txMethod()' (AOP proxy bypass risk)",
                        commitHash = commitHash
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
        xmlLines: List<String>?,
        commitHash: String?
    ): SideEffectAlert? {
        val changedLines = hunk.rawDiffLines.filter {
            (it.startsWith("+") && !it.startsWith("+++")) ||
                (it.startsWith("-") && !it.startsWith("---"))
        }
        val touchesWhere = changedLines.any { SQL_WHERE_REGEX.containsMatchIn(it.substring(1)) }
        if (!touchesWhere) return null

        val inUpdateOrDelete = isHunkInsideUpdateOrDelete(hunk, xmlLines)
        if (!inUpdateOrDelete) return null

        return SideEffectAlert(
            severity = RadarSeverity.HIGH,
            rule = SideEffectRule.MYBATIS_BULK_UPDATE_DELETE_RISK,
            filePath = fd.summary.path,
            fileName = fd.summary.fileName,
            hunkPointer = hunk,
            detailKo = "<update>/<delete> 구문 내 WHERE / <where> 조건절 수정·삭제 감지 (전체 테이블 갱신·삭제 주의)",
            detailEn = "WHERE / <where> clause modified or removed inside <update>/<delete> statement (full-table impact risk)",
            commitHash = commitHash
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

    private fun detectExternalAndSpecRisks(
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        commitHash: String?
    ): List<SideEffectAlert> {
        val alerts = mutableListOf<SideEffectAlert>()
        val lowerName = fd.summary.fileName.lowercase()
        val lowerPath = fd.summary.path.replace('\\', '/').lowercase()

        // 4A: DTO / Request / Response existing field deletion or type change
        val isDtoFile = DTO_NAME_TOKENS.any { lowerName.contains(it) || lowerPath.contains("/$it/") }
        if (isDtoFile) {
            val removedFields = extractFieldSignatures(hunk.rawDiffLines.filter { it.startsWith("-") && !it.startsWith("---") })
            val addedFields = extractFieldSignatures(hunk.rawDiffLines.filter { it.startsWith("+") && !it.startsWith("+++") })

            val brokenFields = mutableListOf<String>()
            for ((fieldName, oldType) in removedFields) {
                val newType = addedFields[fieldName]
                if (newType == null) {
                    brokenFields.add("$fieldName (삭제/removed)")
                } else if (newType != oldType) {
                    brokenFields.add("$fieldName ($oldType -> $newType)")
                }
            }
            if (brokenFields.isNotEmpty()) {
                val sample = brokenFields.take(3).joinToString(", ")
                alerts.add(
                    SideEffectAlert(
                        severity = RadarSeverity.WARN,
                        rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        detailKo = "DTO/API 스펙 필드 삭제 또는 타입 변경 감지: $sample",
                        detailEn = "DTO/API spec field removed or type changed: $sample",
                        commitHash = commitHash
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
            val changedResilience = hunk.rawDiffLines.any { line ->
                ((line.startsWith("+") && !line.startsWith("+++")) || (line.startsWith("-") && !line.startsWith("---"))) &&
                    UnifiedDiffParser.isSubstantiveCodeLine(line.substring(1)) &&
                    RESILIENCE_CONFIG_REGEX.containsMatchIn(line.substring(1))
            }
            if (changedResilience) {
                alerts.add(
                    SideEffectAlert(
                        severity = RadarSeverity.WARN,
                        rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                        filePath = fd.summary.path,
                        fileName = fd.summary.fileName,
                        hunkPointer = hunk,
                        detailKo = "외부 API 연동 timeout / retry 설정 변경 감지 (장애 전파·스레드 풀 고갈 주의)",
                        detailEn = "External API timeout/retry settings modified (check cascading failure / thread pool impact)",
                        commitHash = commitHash
                    )
                )
            }
        }

        // 4C: Empty catch block added
        val addedRaw = hunk.rawDiffLines.filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.substring(1) }
        if (hasEmptyCatchBlock(addedRaw)) {
            alerts.add(
                SideEffectAlert(
                    severity = RadarSeverity.WARN,
                    rule = SideEffectRule.EXTERNAL_SPEC_OR_RESILIENCE_RISK,
                    filePath = fd.summary.path,
                    fileName = fd.summary.fileName,
                    hunkPointer = hunk,
                    detailKo = "빈 catch 블록 추가 감지 (예외 삼킴으로 장애 원인 추적 불가 위험)",
                    detailEn = "Empty catch block added (swallowing exceptions may hide production failures)",
                    commitHash = commitHash
                )
            )
        }

        return alerts
    }

    private fun extractFieldSignatures(diffLines: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (line in diffLines) {
            val code = line.substring(1)
            if (!UnifiedDiffParser.isSubstantiveCodeLine(code)) continue
            JAVA_FIELD_DECL_REGEX.matchEntire(code)?.let { m ->
                val type = m.groupValues[1].trim()
                val name = m.groupValues[2].trim()
                if (!type.contains("(")) {
                    result[name] = type
                }
            }
            KOTLIN_PROP_DECL_REGEX.find(code)?.let { m ->
                val name = m.groupValues[1].trim()
                val type = m.groupValues[2].trim()
                result[name] = type
            }
        }
        return result
    }

    private fun hasEmptyCatchBlock(addedLines: List<String>): Boolean {
        for (i in addedLines.indices) {
            val trimmed = addedLines[i].trim()
            if (EMPTY_CATCH_SINGLE_LINE_REGEX.containsMatchIn(trimmed)) {
                return true
            }
            if (CATCH_OPEN_REGEX.containsMatchIn(trimmed)) {
                var j = i + 1
                while (j < addedLines.size) {
                    val nextTrimmed = addedLines[j].trim()
                    if (nextTrimmed.isEmpty() || nextTrimmed.startsWith("//") || nextTrimmed.startsWith("/*") || nextTrimmed.startsWith("*")) {
                        j++
                        continue
                    }
                    if (nextTrimmed == "}" || nextTrimmed.startsWith("}")) {
                        return true
                    }
                    break
                }
            }
        }
        return false
    }
}
