package com.maekom.codetracker.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.maekom.codetracker.git.GitRepositoryService
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
                "파일 '${file.path}'은(는) 삭제되었거나 현재 체크아웃된 워크스페이스에 존재하지 않습니다.\n해당 커밋의 Before / After Diff 창으로 확인하시겠습니까?",
                "Maekom Code Tracker - 파일 없음",
                "Diff 보기",
                "닫기",
                Messages.getInformationIcon()
            )
            if (choice == Messages.YES) {
                showCommitFileDiff(project, repoRootPath, commit, file)
            }
            return
        }

        val lines = readWorkspaceLines(virtualFile.path, targetPath)
        val fallbackLine1Based = (pointer?.newStartLine ?: 1).coerceAtLeast(1)
        val resolvedLine1Based = resolveTargetLine(
            lines = lines,
            detectedMethodName = pointer?.detectedMethodName,
            fallbackLine1Based = fallbackLine1Based
        )

        val maxLineIndex = (lines.size - 1).coerceAtLeast(0)
        val zeroBasedLine = (resolvedLine1Based - 1).coerceIn(0, maxLineIndex)

        OpenFileDescriptor(project, virtualFile, zeroBasedLine, 0).navigate(true)
    }

    private fun readWorkspaceLines(virtualFilePath: String, nioPath: Path): List<String> {
        val vf = LocalFileSystem.getInstance().findFileByPath(virtualFilePath)
        if (vf != null) {
            val doc = runCatching { FileDocumentManager.getInstance().getCachedDocument(vf) }.getOrNull()
            if (doc != null) {
                return doc.text.lines()
            }
        }
        return runCatching { Files.readAllLines(nioPath, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
    }

    internal fun resolveTargetLine(
        lines: List<String>,
        detectedMethodName: String?,
        fallbackLine1Based: Int
    ): Int {
        if (lines.isEmpty() || detectedMethodName.isNullOrBlank()) {
            return fallbackLine1Based
        }

        val candidates = mutableListOf<Int>()
        if (detectedMethodName.contains('#')) {
            // MyBatis XML query id: select#findOrders
            val parts = detectedMethodName.split('#', limit = 2)
            val tag = Regex.escape(parts[0])
            val id = Regex.escape(parts[1])
            val xmlRegex = Regex("""<$tag\b[^>]*\bid\s*=\s*["']$id["']""", RegexOption.IGNORE_CASE)
            lines.forEachIndexed { idx, line ->
                if (xmlRegex.containsMatchIn(line)) {
                    candidates.add(idx + 1)
                }
            }
        } else {
            val escaped = Regex.escape(detectedMethodName)
            val methodDeclRegex = Regex("""(?:\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_.]+\.)?|\b)$escaped\s*\(""")
            lines.forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && methodDeclRegex.containsMatchIn(trimmed)) {
                    candidates.add(idx + 1)
                }
            }
        }

        if (candidates.isEmpty()) {
            return fallbackLine1Based
        }

        // Prefer the matching declaration closest to the hunk's newStartLine
        return candidates.minByOrNull { abs(it - fallbackLine1Based) } ?: fallbackLine1Based
    }

    fun showCommitFileDiff(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        file: FileSummary
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
                        file.status == FileChangeStatus.ADDED -> "Before (신규 파일)"
                        commit.isInitialCommit -> "Before (최초 커밋)"
                        else -> "Before (${commit.shortHash}^1 : ${file.oldPath ?: file.path})"
                    }
                    val afterLabel = when (file.status) {
                        FileChangeStatus.DELETED -> "After (삭제됨)"
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
                    DiffManager.getInstance().showDiff(project, request)
                }
            }
        })
    }
}
