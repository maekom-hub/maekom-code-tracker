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
        targetPointer: CodeHunkPointer? = null
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
                    if (targetPointer != null) {
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
