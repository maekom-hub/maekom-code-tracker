package com.maekom.codetracker.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Pair as IntelliJPair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileChangeStatus
import com.maekom.codetracker.model.FileSummary
import com.maekom.codetracker.model.SideEffectAlert
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Smart code pointer navigator & standard IntelliJ Diff Viewer launcher.
 * Strictly read-only, exception-safe, and uses only standard public IntelliJ Platform APIs.
 */
object CodeNavigator {

    private const val SNIPPET_SEARCH_WINDOW = 50
    private val WHITESPACE_REGEX = Regex("""\s+""")

    /**
     * Right-click action on a Side-Effect Radar item:
     * Searches the current workspace file for `riskCodeSnippet` (whitespace-normalized) first,
     * then enclosing method / MyBatis XML tag ID. If the risky code was already deleted/changed,
     * notifies the user and opens the commit's Before / After Diff viewer scrolled to the exact line.
     */
    fun navigateToRadarInWorkspace(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        file: FileSummary,
        alert: SideEffectAlert
    ) {
        val targetPath = Path.of(repoRootPath, file.path).normalize()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath)

        if (file.status == FileChangeStatus.DELETED || virtualFile == null || !virtualFile.exists() || virtualFile.isDirectory) {
            Messages.showInfoMessage(
                project,
                CodeTrackerI18n.radarCodeMissingInWorkspace(file.fileName),
                "Maekom Code Tracker"
            )
            showCommitFileDiff(project, repoRootPath, commit, file, alert.hunkPointer, alert)
            return
        }

        val lines = readWorkspaceLines(virtualFile, targetPath)
        if (lines.isEmpty()) {
            Messages.showInfoMessage(
                project,
                CodeTrackerI18n.radarCodeMissingInWorkspace(file.fileName),
                "Maekom Code Tracker"
            )
            showCommitFileDiff(project, repoRootPath, commit, file, alert.hunkPointer, alert)
            return
        }

        val anchorZeroBased = (alert.exactRiskNewLine - 1).coerceIn(0, lines.lastIndex)

        // Priority 1: Normalized whitespace search for riskCodeSnippet
        val normalizedSnippet = alert.riskCodeSnippet
            ?.let { WHITESPACE_REGEX.replace(it.trim(), " ") }
            ?.takeIf { it.length >= 4 }
        if (normalizedSnippet != null) {
            val exactMatches = mutableListOf<Int>()
            val containsMatches = mutableListOf<Int>()
            lines.forEachIndexed { idx, line ->
                val normLine = WHITESPACE_REGEX.replace(line.trim(), " ")
                if (normLine == normalizedSnippet) {
                    exactMatches.add(idx)
                } else if (normLine.contains(normalizedSnippet)) {
                    containsMatches.add(idx)
                }
            }
            val hit = exactMatches.minByOrNull { abs(it - anchorZeroBased) }
                ?: containsMatches.minByOrNull { abs(it - anchorZeroBased) }
            if (hit != null) {
                OpenFileDescriptor(project, virtualFile, hit, 0).navigate(true)
                return
            }
        }

        // Priority 2: Enclosing method / MyBatis XML query ID
        val methodLine = findClosestMethodLineZeroBased(
            lines = lines,
            detectedMethodName = alert.hunkPointer.detectedMethodName,
            anchorZeroBased = anchorZeroBased
        )
        if (methodLine != null) {
            OpenFileDescriptor(project, virtualFile, methodLine.coerceIn(0, lines.lastIndex), 0).navigate(true)
            return
        }

        // Neither snippet nor method found in current file -> inform user & open commit Diff
        Messages.showInfoMessage(
            project,
            CodeTrackerI18n.radarCodeMissingInWorkspace(file.fileName),
            "Maekom Code Tracker"
        )
        showCommitFileDiff(project, repoRootPath, commit, file, alert.hunkPointer, alert)
    }

    fun navigateToAbsoluteFileLine(
        project: Project,
        absoluteFilePath: String,
        lineNumber1Based: Int
    ) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absoluteFilePath)
        if (vf == null || !vf.exists() || vf.isDirectory) {
            Messages.showInfoMessage(
                project,
                CodeTrackerI18n.tr(
                    "대상 파일 '$absoluteFilePath'을(를) 찾을 수 없습니다.",
                    "Target file '$absoluteFilePath' could not be found."
                ),
                "Maekom Code Tracker"
            )
            return
        }
        val zeroBasedLine = (lineNumber1Based - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, vf, zeroBasedLine, 0).navigate(true)
    }

    fun navigateToPointer(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        file: FileSummary,
        pointer: CodeHunkPointer?
    ) {
        val targetPath = Path.of(repoRootPath, file.path).normalize()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath)

        if (file.status == FileChangeStatus.DELETED || virtualFile == null || !virtualFile.exists() || virtualFile.isDirectory) {
            val choice = Messages.showYesNoDialog(
                project,
                CodeTrackerI18n.tr(
                    "파일 '${file.path}'은(는) 삭제되었거나 현재 체크아웃된 워크스페이스에 존재하지 않습니다.\n해당 커밋의 Before / After Diff 창으로 확인하시겠습니까?",
                    "File '${file.path}' was deleted or does not exist in the current workspace.\nWould you like to view its Before / After Diff for this commit?"
                ),
                CodeTrackerI18n.tr("Maekom Code Tracker - 파일 없음", "Maekom Code Tracker - File Not Found"),
                CodeTrackerI18n.tr("Diff 보기", "View Diff"),
                CodeTrackerI18n.closeButton(),
                Messages.getInformationIcon()
            )
            if (choice == Messages.YES) {
                showCommitFileDiff(project, repoRootPath, commit, file, pointer)
            }
            return
        }

        val zeroBasedLine = resolveTargetLineZeroBased(virtualFile, targetPath, pointer)
        OpenFileDescriptor(project, virtualFile, zeroBasedLine, 0).navigate(true)
    }

    internal fun resolveTargetLineZeroBased(
        virtualFile: VirtualFile?,
        nioPath: Path,
        pointer: CodeHunkPointer?
    ): Int {
        val lines = readWorkspaceLines(virtualFile, nioPath)
        val fallbackZeroBased = ((pointer?.newStartLine ?: 1) - 1).coerceAtLeast(0)
        if (lines.isEmpty()) {
            return fallbackZeroBased
        }

        val maxIdx = (lines.size - 1).coerceAtLeast(0)
        val clampedFallback = fallbackZeroBased.coerceIn(0, maxIdx)
        if (pointer == null) {
            return clampedFallback
        }

        // Priority 1: Search firstChangedCodeLine within +/- 50 lines of (newStartLine - 1)
        val snippet = pointer.firstChangedCodeLine?.trim()
        if (!snippet.isNullOrBlank()) {
            val startIdx = (clampedFallback - SNIPPET_SEARCH_WINDOW).coerceAtLeast(0)
            val endIdx = (clampedFallback + SNIPPET_SEARCH_WINDOW).coerceAtMost(maxIdx)
            val exactMatches = mutableListOf<Int>()
            val containsMatches = mutableListOf<Int>()

            for (idx in startIdx..endIdx) {
                val candidate = lines[idx].trim()
                if (candidate == snippet) {
                    exactMatches.add(idx)
                } else if (candidate.contains(snippet)) {
                    containsMatches.add(idx)
                }
            }

            exactMatches.minByOrNull { abs(it - clampedFallback) }?.let { return it }
            containsMatches.minByOrNull { abs(it - clampedFallback) }?.let { return it }
        }

        // Priority 2: Search closest method declaration matching detectedMethodName
        val methodLineZeroBased = findClosestMethodLineZeroBased(
            lines = lines,
            detectedMethodName = pointer.detectedMethodName,
            anchorZeroBased = clampedFallback
        )
        if (methodLineZeroBased != null) {
            return methodLineZeroBased.coerceIn(0, maxIdx)
        }

        // Priority 3: Corrected (newStartLine - 1) fallback
        return clampedFallback
    }

    private fun readWorkspaceLines(virtualFile: VirtualFile?, nioPath: Path): List<String> {
        if (virtualFile != null) {
            val doc = runCatching { FileDocumentManager.getInstance().getCachedDocument(virtualFile) }.getOrNull()
            if (doc != null) {
                return doc.text.lines()
            }
        }
        return runCatching { Files.readAllLines(nioPath, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
    }

    private fun findClosestMethodLineZeroBased(
        lines: List<String>,
        detectedMethodName: String?,
        anchorZeroBased: Int
    ): Int? {
        if (lines.isEmpty() || detectedMethodName.isNullOrBlank()) {
            return null
        }

        val candidates = mutableListOf<Int>()
        if (detectedMethodName.contains('#')) {
            val parts = detectedMethodName.split('#', limit = 2)
            val tag = Regex.escape(parts[0])
            val id = Regex.escape(parts[1])
            val xmlRegex = Regex("""<$tag\b[^>]*\bid\s*=\s*["']$id["']""", RegexOption.IGNORE_CASE)
            lines.forEachIndexed { idx, line ->
                if (xmlRegex.containsMatchIn(line)) {
                    candidates.add(idx)
                }
            }
        } else {
            val escaped = Regex.escape(detectedMethodName)
            val methodDeclRegex = Regex("""(?:\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_.]+\.)?|\b)$escaped\s*\(""")
            lines.forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && methodDeclRegex.containsMatchIn(trimmed)) {
                    candidates.add(idx)
                }
            }
        }

        return candidates.minByOrNull { abs(it - anchorZeroBased) }
    }

    fun showCommitFileDiff(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        file: FileSummary,
        targetPointer: CodeHunkPointer? = null,
        radarAlert: SideEffectAlert? = null
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Maekom Code Tracker: Diff 로딩 (${file.fileName})",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val (beforeText, afterText) = try {
                    GitRepositoryService.readBeforeAndAfterForDiff(project, repoRootPath, commit, file)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Diff 내용을 불러오는 중 오류가 발생했습니다:\n${e.message}",
                            "Maekom Code Tracker"
                        )
                    }
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.fileName)
                    val factory = DiffContentFactory.getInstance()
                    val beforeContent = factory.create(project, beforeText, fileType)
                    val afterContent = factory.create(project, afterText, fileType)

                    val beforeLabel = when {
                        file.status == FileChangeStatus.ADDED -> CodeTrackerI18n.tr("Before (신규 파일)", "Before (New File)")
                        commit.isInitialCommit -> CodeTrackerI18n.tr("Before (최초 커밋)", "Before (Initial Commit)")
                        else -> "Before (${commit.shortHash}^1 : ${file.oldPath ?: file.path})"
                    }
                    val afterLabel = when (file.status) {
                        FileChangeStatus.DELETED -> CodeTrackerI18n.tr("After (삭제됨)", "After (Deleted)")
                        else -> "After (${commit.shortHash} : ${file.path})"
                    }
                    val title = "[${commit.shortHash}] ${file.path}"

                    val request = SimpleDiffRequest(
                        title,
                        beforeContent,
                        afterContent,
                        beforeLabel,
                        afterLabel
                    )
                    if (radarAlert != null) {
                        val side = if (radarAlert.preferLeftSide) Side.LEFT else Side.RIGHT
                        val targetLine1Based = if (radarAlert.preferLeftSide) {
                            radarAlert.exactRiskOldLine
                        } else {
                            radarAlert.exactRiskNewLine
                        }
                        request.putUserData(
                            DiffUserDataKeys.SCROLL_TO_LINE,
                            IntelliJPair.create(side, (targetLine1Based - 1).coerceAtLeast(0))
                        )
                    } else if (targetPointer != null) {
                        request.putUserData(
                            DiffUserDataKeys.SCROLL_TO_LINE,
                            IntelliJPair.create(Side.RIGHT, (targetPointer.newStartLine - 1).coerceAtLeast(0))
                        )
                    }
                    DiffManager.getInstance().showDiff(project, request)
                }
            }
        })
    }
}
