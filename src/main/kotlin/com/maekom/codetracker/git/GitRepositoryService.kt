package com.maekom.codetracker.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.maekom.codetracker.analysis.EpisodeGrouper
import com.maekom.codetracker.analysis.LayerClassifier
import com.maekom.codetracker.model.AnalysisFilter
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileChangeStatus
import com.maekom.codetracker.model.FileHistoryCommitEntry
import com.maekom.codetracker.model.FileHistoryResult
import com.maekom.codetracker.model.FileHistoryVersionGroup
import com.maekom.codetracker.model.FileSummary
import com.maekom.codetracker.model.RepoOption
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only Git repository discovery, branch/author listing, Phase 1 log collection,
 * Phase 2 single-commit diff retrieval, and revision file reader.
 */
object GitRepositoryService {

    const val MAX_COMMITS = 500
    const val MAX_BLOB_BYTES = 1_048_576 // 1MB

    private const val RECORD_SEP = "\u001E"
    private const val FIELD_SEP = "\u001F"
    private const val HEADER_END = "\u001D"

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun discoverRepositories(project: Project): List<RepoOption> {
        val repoRoots = linkedMapOf<String, String>() // canonicalPath -> displayName

        runCatching {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            for (vcsRoot in vcsManager.allVcsRoots) {
                val rootPath = vcsRoot.path?.path ?: continue
                val dir = File(rootPath)
                if (dir.isDirectory && isGitRoot(dir)) {
                    repoRoots.putIfAbsent(dir.canonicalPath, dir.name)
                }
            }
        }

        // Fallback discovery if Git4Idea hasn't registered repos yet
        val basePath = project.basePath
        if (repoRoots.isEmpty() && !basePath.isNullOrBlank()) {
            val baseDir = File(basePath)
            if (isGitRoot(baseDir)) {
                repoRoots.putIfAbsent(baseDir.canonicalPath, baseDir.name)
            } else if (baseDir.isDirectory) {
                baseDir.listFiles()?.filter { it.isDirectory && isGitRoot(it) }?.sortedBy { it.name }?.forEach {
                    repoRoots.putIfAbsent(it.canonicalPath, it.name)
                }
            }
        }

        val options = mutableListOf<RepoOption>()
        for ((rootPath, repoName) in repoRoots) {
            options.add(RepoOption(displayName = "$repoName (전체)", rootPath = rootPath, moduleSubPath = null))
            val subModules = discoverSubModules(File(rootPath))
            for (subModule in subModules) {
                options.add(
                    RepoOption(
                        displayName = "$repoName / $subModule",
                        rootPath = rootPath,
                        moduleSubPath = subModule
                    )
                )
            }
        }
        return options
    }

    private fun isGitRoot(dir: File): Boolean {
        val dotGit = File(dir, ".git")
        return dotGit.exists()
    }

    private fun discoverSubModules(repoRoot: File): List<String> {
        val children = repoRoot.listFiles() ?: return emptyList()
        return children
            .filter { dir ->
                dir.isDirectory &&
                    !dir.name.startsWith(".") &&
                    dir.name !in setOf("build", "out", "target", "node_modules", "gradle") &&
                    (File(dir, "build.gradle").isFile ||
                        File(dir, "build.gradle.kts").isFile ||
                        File(dir, "pom.xml").isFile)
            }
            .map { it.name }
            .sorted()
    }

    fun listBranches(project: Project, repoRootPath: String): List<String> {
        val result = linkedSetOf("HEAD")

        val localOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "branch",
            args = listOf("--format=%(refname:short)")
        )
        if (localOut.exitCode == 0) {
            localOut.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("(") }
                .forEach { result.add(it) }
        }

        val remoteOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "branch",
            args = listOf("-r", "--format=%(refname:short)")
        )
        if (remoteOut.exitCode == 0) {
            remoteOut.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "origin/HEAD" && !it.endsWith("/HEAD") }
                .forEach { result.add(it) }
        }

        return result.toList()
    }

    fun loadRecentAuthors(
        project: Project,
        repoRootPath: String,
        branch: String,
        startDate: LocalDate,
        endDate: LocalDate,
        moduleSubPath: String? = null
    ): List<String> {
        val args = mutableListOf(
            branch.ifBlank { "HEAD" },
            "-n", MAX_COMMITS.toString(),
            "--since=${startDate.format(DATE_FMT)} 00:00:00",
            "--until=${endDate.format(DATE_FMT)} 23:59:59",
            "--format=%an"
        )
        if (!moduleSubPath.isNullOrBlank()) {
            args.add("--")
            args.add(moduleSubPath)
        }

        val out = GitCommandRunner.runReadOnly(project, repoRootPath, "log", args)
        if (out.exitCode != 0) return emptyList()

        return out.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    fun listAuthors(
        project: Project,
        repo: RepoOption,
        branch: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<String> = loadRecentAuthors(
        project = project,
        repoRootPath = repo.rootPath,
        branch = branch,
        startDate = startDate,
        endDate = endDate,
        moduleSubPath = repo.moduleSubPath
    )

    /**
     * Phase 1: Lightweight commit + changed file status collection (up to 500 commits).
     */
    fun fetchCommitSummaries(
        project: Project,
        filter: AnalysisFilter
    ): Pair<List<CommitSummary>, Boolean> {
        val prettyFormat = "${RECORD_SEP}%H${FIELD_SEP}%h${FIELD_SEP}%P${FIELD_SEP}%an${FIELD_SEP}%ae${FIELD_SEP}%at${FIELD_SEP}%s${FIELD_SEP}%B${HEADER_END}"

        val args = mutableListOf(
            filter.branch.ifBlank { "HEAD" },
            "-n", (MAX_COMMITS + 1).toString(),
            "--since=${filter.startDate.format(DATE_FMT)} 00:00:00",
            "--until=${filter.endDate.format(DATE_FMT)} 23:59:59",
            "--find-renames",
            "--name-status",
            "--pretty=format:$prettyFormat"
        )
        if (filter.excludeMerges) {
            args.add("--no-merges")
        }
        if (!filter.author.isNullOrBlank()) {
            args.add("--author=${filter.author}")
        }
        if (!filter.repo.moduleSubPath.isNullOrBlank()) {
            args.add("--")
            args.add(filter.repo.moduleSubPath)
        }

        val output = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = filter.repo.rootPath,
            subcommand = "log",
            args = args
        )

        if (output.exitCode != 0) {
            // Unborn branch or invalid ref yields non-zero; return empty safely
            if (output.stderr.contains("does not have any commits yet", ignoreCase = true) ||
                output.stderr.contains("unknown revision", ignoreCase = true)
            ) {
                return emptyList<CommitSummary>() to false
            }
            throw IllegalStateException("Git log 조회 실패: ${output.stderr.trim()}")
        }

        val parsed = parseCommitLogOutput(output.stdout)
        val truncated = parsed.size > MAX_COMMITS
        val limited = if (truncated) parsed.take(MAX_COMMITS) else parsed
        return limited to truncated
    }

    internal fun parseCommitLogOutput(raw: String): List<CommitSummary> {
        if (raw.isBlank()) return emptyList()
        val zone = ZoneId.systemDefault()
        val records = raw.split(RECORD_SEP).filter { it.isNotBlank() }
        val commits = mutableListOf<CommitSummary>()

        for (record in records) {
            val endIdx = record.indexOf(HEADER_END)
            if (endIdx < 0) continue

            val headerPart = record.substring(0, endIdx)
            val filesPart = record.substring(endIdx + HEADER_END.length)
            val fields = headerPart.split(FIELD_SEP)
            if (fields.size < 8) continue

            val hash = fields[0].trim()
            val shortHash = fields[1].trim()
            val parents = fields[2].trim().split(' ').filter { it.isNotBlank() }
            val authorName = fields[3].trim()
            val authorEmail = fields[4].trim()
            val epochSeconds = fields[5].trim().toLongOrNull() ?: 0L
            val subject = fields[6].trim()
            val body = fields.subList(7, fields.size).joinToString(FIELD_SEP).trim()
            val timestamp = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)

            val fileSummaries = parseNameStatusLines(filesPart)
            val primaryLayers = fileSummaries
                .map { it.primaryLayer }
                .distinct()
                .sortedBy { it.flowOrder }
            val hasMd = fileSummaries.any { it.path.endsWith(".md", ignoreCase = true) }

            commits.add(
                CommitSummary(
                    hash = hash,
                    shortHash = shortHash,
                    parentHashes = parents,
                    authorName = authorName,
                    authorEmail = authorEmail,
                    timestamp = timestamp,
                    epochSeconds = epochSeconds,
                    subject = subject,
                    body = body,
                    files = fileSummaries,
                    primaryLayers = primaryLayers,
                    hasMarkdownChanges = hasMd
                )
            )
        }
        return commits
    }

    private fun parseNameStatusLines(filesPart: String): List<FileSummary> {
        val result = mutableListOf<FileSummary>()
        for (rawLine in filesPart.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue

            val status = FileChangeStatus.fromGitCode(parts[0])
            val oldPath: String?
            val newPath: String
            if ((status == FileChangeStatus.RENAMED || status == FileChangeStatus.COPIED) && parts.size >= 3) {
                oldPath = parts[1].trim()
                newPath = parts[2].trim()
            } else {
                oldPath = null
                newPath = parts[1].trim()
            }

            if (newPath.isBlank()) continue
            result.add(
                FileSummary(
                    path = newPath,
                    oldPath = oldPath,
                    status = status,
                    primaryLayer = LayerClassifier.classifyPrimaryLayer(newPath)
                )
            )
        }
        return result
    }

    /**
     * Phase 2: On-demand unified diff retrieval for a single commit.
     */
    fun fetchCommitShowDiff(project: Project, repoRootPath: String, commitHash: String): String {
        val output = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "show",
            args = listOf(
                "--find-renames",
                "--unified=3",
                "--format=",
                commitHash
            ),
            maxOutputBytes = 12 * 1024 * 1024
        )
        if (output.exitCode != 0) {
            throw IllegalStateException("Git show 조회 실패 ($commitHash): ${output.stderr.trim()}")
        }
        return output.stdout
    }

    /**
     * On-demand unified diff retrieval for a single file at a specific commit.
     */
    fun fetchSingleFileShowDiff(
        project: Project,
        repoRootPath: String,
        commitHash: String,
        pathAtCommit: String
    ): String {
        val output = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "show",
            args = listOf(
                "--find-renames",
                "--unified=3",
                "--format=",
                commitHash,
                "--",
                pathAtCommit
            ),
            maxOutputBytes = 4 * 1024 * 1024
        )
        if (output.exitCode != 0) {
            throw IllegalStateException("Git show 파일 Diff 조회 실패 ($commitHash -- $pathAtCommit): ${output.stderr.trim()}")
        }
        return output.stdout
    }

    /**
     * Single-pass optimized file history collection with release version/tag grouping.
     * Follows renames (--follow --find-renames --name-status) and maps each commit to
     * the earliest tag containing it without running per-commit `git tag --contains`.
     * Falls back to date/work-episode grouping if the repository has no Git tags.
     */
    fun collectFileHistoryWithVersions(
        project: Project,
        repoRootPath: String,
        branch: String,
        startDate: LocalDate,
        endDate: LocalDate,
        filePath: String,
        noMerges: Boolean
    ): FileHistoryResult {
        val resolvedBranch = branch.ifBlank { "HEAD" }
        val prettyFormat = "${RECORD_SEP}%H${FIELD_SEP}%h${FIELD_SEP}%P${FIELD_SEP}%an${FIELD_SEP}%ae${FIELD_SEP}%at${FIELD_SEP}%s${FIELD_SEP}%B${HEADER_END}"

        val args = mutableListOf(
            resolvedBranch,
            "-n", MAX_COMMITS.toString(),
            "--follow",
            "--find-renames",
            "--name-status",
            "--since=${startDate.format(DATE_FMT)} 00:00:00",
            "--until=${endDate.format(DATE_FMT)} 23:59:59",
            "--pretty=format:$prettyFormat"
        )
        if (noMerges) {
            args.add("--no-merges")
        }
        args.add("--")
        args.add(filePath)

        val logOutput = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "log",
            args = args
        )
        if (logOutput.exitCode != 0) {
            if (logOutput.stderr.contains("does not have any commits yet", ignoreCase = true) ||
                logOutput.stderr.contains("unknown revision", ignoreCase = true)
            ) {
                return FileHistoryResult(filePath, resolvedBranch, startDate, endDate, false, emptyList(), 0)
            }
            throw IllegalStateException("파일 수정 이력 조회 실패: ${logOutput.stderr.trim()}")
        }

        val commits = parseCommitLogOutput(logOutput.stdout)
        if (commits.isEmpty()) {
            return FileHistoryResult(filePath, resolvedBranch, startDate, endDate, false, emptyList(), 0)
        }

        // Check if repository has any tags in 1 lightweight call
        val showRefOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "show-ref",
            args = listOf("--tags"),
            timeoutSeconds = 10
        )
        val hasTagsInRepo = showRefOut.exitCode == 0 && showRefOut.stdout.isNotBlank()

        if (hasTagsInRepo) {
            val targetHashes = commits.map { it.hash }.toSet()
            val commitToTag = resolveFirstContainingTags(
                project = project,
                repoRootPath = repoRootPath,
                branch = resolvedBranch,
                targetHashes = targetHashes
            )

            // Preserve branch newest-to-oldest group order
            val groupedMap = linkedMapOf<String, MutableList<FileHistoryCommitEntry>>()
            for (commit in commits) {
                val fileAtCommit = commit.files.firstOrNull()
                val pathAtCommit = fileAtCommit?.path ?: filePath
                val status = fileAtCommit?.status ?: FileChangeStatus.MODIFIED
                val oldPath = fileAtCommit?.oldPath
                val tagLabel = commitToTag[commit.hash] ?: "Unreleased (HEAD)"

                groupedMap.computeIfAbsent(tagLabel) { mutableListOf() }.add(
                    FileHistoryCommitEntry(
                        commit = commit,
                        status = status,
                        pathAtCommit = pathAtCommit,
                        oldPathAtCommit = oldPath,
                        versionGroupLabel = tagLabel
                    )
                )
            }

            val groups = groupedMap.map { (label, entries) ->
                FileHistoryVersionGroup(
                    groupLabel = label,
                    isTagGroup = true,
                    entries = entries
                )
            }
            return FileHistoryResult(
                targetFilePath = filePath,
                branch = resolvedBranch,
                startDate = startDate,
                endDate = endDate,
                hasTagsInRepo = true,
                groups = groups,
                totalCommits = commits.size
            )
        } else {
            // No tags in repo: group by Date / Work Episode automatically
            val episodes = EpisodeGrouper.groupIntoEpisodes(commits)
            val groups = episodes.map { ep ->
                val label = "${ep.date} | ${ep.authorName}"
                val entries = ep.commits.reversed().map { commit ->
                    val fileAtCommit = commit.files.firstOrNull()
                    FileHistoryCommitEntry(
                        commit = commit,
                        status = fileAtCommit?.status ?: FileChangeStatus.MODIFIED,
                        pathAtCommit = fileAtCommit?.path ?: filePath,
                        oldPathAtCommit = fileAtCommit?.oldPath,
                        versionGroupLabel = label
                    )
                }
                FileHistoryVersionGroup(
                    groupLabel = label,
                    isTagGroup = false,
                    entries = entries
                )
            }
            return FileHistoryResult(
                targetFilePath = filePath,
                branch = resolvedBranch,
                startDate = startDate,
                endDate = endDate,
                hasTagsInRepo = false,
                groups = groups,
                totalCommits = commits.size
            )
        }
    }

    /**
     * Resolves the earliest release tag covering each target commit on [branch] in a single
     * lightweight `git log --decorate=full` walk (plus `--simplify-by-decoration` validation).
     */
    private fun resolveFirstContainingTags(
        project: Project,
        repoRootPath: String,
        branch: String,
        targetHashes: Set<String>
    ): Map<String, String> {
        // Fast check whether branch history has any tags at all via --simplify-by-decoration
        val decoCheck = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "log",
            args = listOf(
                branch,
                "--simplify-by-decoration",
                "--decorate=full",
                "--pretty=format:%H%x1f%D"
            ),
            timeoutSeconds = 15
        )
        if (decoCheck.exitCode != 0 || !decoCheck.stdout.contains("tag: refs/tags/")) {
            return targetHashes.associateWith { "Unreleased (HEAD)" }
        }

        // Single pass walking branch commits newest -> oldest to propagate the first tag covering each commit
        val walkOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "log",
            args = listOf(
                branch,
                "-n", "5000",
                "--decorate=full",
                "--pretty=format:%H%x1f%D"
            ),
            timeoutSeconds = 20
        )
        if (walkOut.exitCode != 0) {
            return targetHashes.associateWith { "Unreleased (HEAD)" }
        }

        val result = linkedMapOf<String, String>()
        var currentCoveringTag = "Unreleased (HEAD)"

        for (line in walkOut.stdout.lineSequence()) {
            if (line.isBlank()) continue
            val sepIdx = line.indexOf('\u001F')
            val hash = if (sepIdx >= 0) line.substring(0, sepIdx).trim() else line.trim()
            val deco = if (sepIdx >= 0) line.substring(sepIdx + 1).trim() else ""

            if (deco.isNotEmpty()) {
                val tagOnCommit = extractPrimaryTagName(deco)
                if (tagOnCommit != null) {
                    currentCoveringTag = tagOnCommit
                }
            }

            if (hash in targetHashes) {
                result[hash] = currentCoveringTag
                if (result.size >= targetHashes.size) {
                    break
                }
            }
        }
        return result
    }

    private fun extractPrimaryTagName(decoration: String): String? {
        val tags = decoration.split(',')
            .map { it.trim() }
            .filter { it.startsWith("tag: refs/tags/") }
            .map { it.removePrefix("tag: refs/tags/").trim() }
            .filter { it.isNotEmpty() }
        if (tags.isEmpty()) return null
        return tags.first()
    }

    /**
     * Read file contents at a specific git revision safely (returns "" if missing or > 1MB).
     */
    fun readFileAtRevision(
        project: Project,
        repoRootPath: String,
        revisionSpec: String,
        maxBytes: Int = MAX_BLOB_BYTES
    ): String {
        val sizeOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "cat-file",
            args = listOf("-s", revisionSpec),
            timeoutSeconds = 10
        )
        if (sizeOut.exitCode != 0) {
            return ""
        }
        val blobSize = sizeOut.stdout.trim().toLongOrNull() ?: 0L
        if (blobSize > maxBytes) {
            return ""
        }

        val contentOut = GitCommandRunner.runReadOnly(
            project = project,
            repoRootPath = repoRootPath,
            subcommand = "show",
            args = listOf(revisionSpec),
            timeoutSeconds = 15,
            maxOutputBytes = maxBytes
        )
        return if (contentOut.exitCode == 0) contentOut.stdout else ""
    }

    /**
     * Read Before & After content safely for standard Diff Viewer.
     * Handles ADDED files and Initial Commits (no parent) safely by returning "".
     */
    fun readBeforeAndAfterForDiff(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        file: FileSummary
    ): Pair<String, String> {
        val beforeText = if (file.status == FileChangeStatus.ADDED || commit.isInitialCommit) {
            ""
        } else {
            val beforePath = file.oldPath ?: file.path
            readFileAtRevision(project, repoRootPath, "${commit.hash}^1:$beforePath", maxBytes = 2 * MAX_BLOB_BYTES)
        }

        val afterText = if (file.status == FileChangeStatus.DELETED) {
            ""
        } else {
            readFileAtRevision(project, repoRootPath, "${commit.hash}:${file.path}", maxBytes = 2 * MAX_BLOB_BYTES)
        }

        return beforeText to afterText
    }
}
