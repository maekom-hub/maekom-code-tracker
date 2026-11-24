package com.maekom.codetracker.analysis

import com.intellij.openapi.project.Project
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.i18n.CodeTrackerI18n
import com.maekom.codetracker.model.CheckBadge
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.EntrypointTarget
import com.maekom.codetracker.model.EntrypointTraceResult
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.FileHistoryCommitEntry
import com.maekom.codetracker.model.FlowMergedFile
import com.maekom.codetracker.model.MergedFileAnalysisDetail
import com.maekom.codetracker.model.MergedFileCommitSection
import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.SideEffectAlert
import com.maekom.codetracker.model.StackTag
import com.maekom.codetracker.model.WorkEpisode

/**
 * 5-layer local evidence engine for change rationale ("Why") & architecture check badges:
 * 1) Full commit message (%B) & issue tags ([JIRA-...], #123)
 * 2) Matched .md context sections from same / immediately preceding episode
 * 3) Newly added (+) code comments
 * 4) 6 architecture check point badges
 * 5) One-line cross-layer flow summary
 */
object RationaleAndCheckEngine {

    private val JIRA_TAG_REGEX = Regex("""\[?([A-Z][A-Z0-9]{1,15}-\d+)]?""")
    private val HASH_ISSUE_REGEX = Regex("""(?<![A-Za-z0-9_])#(\d{1,7})\b""")

    fun analyzeCommitDetail(
        project: Project,
        repoRootPath: String,
        commit: CommitSummary,
        allEpisodes: List<WorkEpisode>
    ): CommitDetail {
        val rawDiff = GitRepositoryService.fetchCommitShowDiff(project, repoRootPath, commit.hash)
        val fileDetails = UnifiedDiffParser.parseDiff(rawDiff, commit.files)

        // 1. Issue tags from commit body & subject
        val issueTags = extractIssueTags("${commit.subject}\n${commit.body}")

        // 2. Load available .md sections from same / immediately preceding episode,
        //    match commit-level context AND attach precision Hunk-level .md notes
        val (currentEp, prevEp) = EpisodeGrouper.findEpisodeAndPrevious(allEpisodes, commit.hash)
        val loadedMdSections = MarkdownContextMatcher.loadAvailableMarkdownSections(
            project = project,
            repoRootPath = repoRootPath,
            currentEpisode = currentEp,
            previousEpisode = prevEp
        )
        val matchedMd = MarkdownContextMatcher.matchContextForCommit(
            commit = commit,
            fileDetails = fileDetails,
            loadedSections = loadedMdSections
        )
        val enrichedWithMd = MarkdownContextMatcher.attachHunkMarkdownNotes(
            fileDetails = fileDetails,
            loadedSections = loadedMdSections
        )

        // 2b. Side-Effect Risk Radar (cross-analyzes diff hunks + Java PSI context, skips src/test/)
        val (radarEnrichedFiles, sideEffectAlerts) = SideEffectRadarAnalyzer.analyzeFiles(
            project = project,
            repoRootPath = repoRootPath,
            fileDetails = enrichedWithMd,
            commitHash = commit.hash
        )

        // 2c. PSI Entrypoint Impact Tracing (API / Batch / Scheduler / Listener)
        val entrypointTrace = EntrypointImpactTracer.traceCommitEntrypoints(
            project = project,
            repoRootPath = repoRootPath,
            commitHash = commit.hash,
            fileDetails = radarEnrichedFiles
        )

        // 3. Collect newly added comments & API hints
        val addedComments = radarEnrichedFiles.flatMap { fd ->
            fd.addedComments.map { comment -> fd.summary.fileName to comment }
        }.take(30)

        val apiHints = radarEnrichedFiles.flatMap { it.apiHints }
        val allStackTags = radarEnrichedFiles.flatMap { it.stackTags }.toCollection(linkedSetOf())

        // 4. Detect architecture check badges
        val badges = detectCheckBadges(radarEnrichedFiles, allStackTags)

        // 5. Build 1-line cross-layer flow summary
        val flowSummary = buildCrossLayerFlowSummary(commit, radarEnrichedFiles, allStackTags)

        return CommitDetail(
            summary = commit,
            fileDetails = radarEnrichedFiles,
            issueTags = issueTags,
            allStackTags = allStackTags,
            checkBadges = badges,
            crossLayerFlowSummary = flowSummary,
            matchedMarkdownSections = matchedMd,
            apiHints = apiHints,
            addedComments = addedComments,
            entrypointTrace = entrypointTrace,
            sideEffectAlerts = sideEffectAlerts
        )
    }

    /**
     * On-demand aggregated analysis for a merged file node in the 5-stage Execution Flow View.
     * Loads up to the 10 most recent commits touching [mergedFile] and combines hunks,
     * entrypoints, and side-effect alerts cleanly.
     */
    fun analyzeMergedFlowFile(
        project: Project,
        repoRootPath: String,
        mergedFile: FlowMergedFile
    ): MergedFileAnalysisDetail {
        val sections = mutableListOf<MergedFileCommitSection>()
        val combinedAlerts = mutableListOf<SideEffectAlert>()
        val combinedEntrypoints = linkedMapOf<String, EntrypointTarget>()
        var isDumbMode = false
        var truncated = false

        for (commit in mergedFile.commits.take(10)) {
            val fileSummary = commit.files.firstOrNull { it.path == mergedFile.path } ?: continue
            val rawDiff = runCatching {
                GitRepositoryService.fetchSingleFileShowDiff(project, repoRootPath, commit.hash, fileSummary.path)
            }.getOrDefault("")
            val parsed = UnifiedDiffParser.parseDiff(rawDiff, listOf(fileSummary))
            val (radarFiles, alerts) = SideEffectRadarAnalyzer.analyzeFiles(
                project = project,
                repoRootPath = repoRootPath,
                fileDetails = parsed,
                commitHash = commit.hash
            )
            val fd = radarFiles.firstOrNull() ?: continue
            sections.add(MergedFileCommitSection(commit = commit, fileDetail = fd))
            combinedAlerts.addAll(alerts)

            val epRes = EntrypointImpactTracer.traceCommitEntrypoints(
                project = project,
                repoRootPath = repoRootPath,
                commitHash = commit.hash,
                fileDetails = listOf(fd)
            )
            if (epRes.isDumbMode) isDumbMode = true
            if (epRes.truncatedByLimit) truncated = true
            for (ep in epRes.entrypoints) {
                val key = "${ep.kind}|${ep.displayLabel}|${ep.filePath}|${ep.lineNumber1Based}"
                combinedEntrypoints.putIfAbsent(key, ep)
            }
        }

        val allFileDetails = sections.map { it.fileDetail }
        val allStackTags = allFileDetails.flatMap { it.stackTags }.toCollection(linkedSetOf())
        val checkBadges = detectCheckBadges(allFileDetails, allStackTags)

        return MergedFileAnalysisDetail(
            mergedFile = mergedFile,
            commitSections = sections,
            entrypointTrace = EntrypointTraceResult(
                entrypoints = combinedEntrypoints.values.toList(),
                isDumbMode = isDumbMode,
                truncatedByLimit = truncated
            ),
            sideEffectAlerts = combinedAlerts
                .distinctBy { "${it.rule}|${it.filePath}|${it.hunkPointer.newStartLine}|${it.detailKo}" }
                .sortedWith(compareBy<SideEffectAlert> { it.severity.ordinal }.thenBy { it.hunkPointer.newStartLine }),
            allStackTags = allStackTags,
            checkBadges = checkBadges
        )
    }

    /**
     * On-demand single-file commit analysis for FileHistoryDialog.
     * Parses `git show --unified=3 <hash> -- <pathAtCommit>` cleanly.
     */
    fun analyzeSingleFileCommitDetail(
        project: Project,
        repoRootPath: String,
        entry: FileHistoryCommitEntry
    ): CommitDetail {
        val fileSummary = entry.toFileSummary()
        val rawDiff = GitRepositoryService.fetchSingleFileShowDiff(
            project = project,
            repoRootPath = repoRootPath,
            commitHash = entry.commit.hash,
            pathAtCommit = entry.pathAtCommit
        )
        val parsedDetails = UnifiedDiffParser.parseDiff(rawDiff, listOf(fileSummary))
        val (fileDetails, sideEffectAlerts) = SideEffectRadarAnalyzer.analyzeFiles(
            project = project,
            repoRootPath = repoRootPath,
            fileDetails = parsedDetails,
            commitHash = entry.commit.hash
        )
        val entrypointTrace = EntrypointImpactTracer.traceCommitEntrypoints(
            project = project,
            repoRootPath = repoRootPath,
            commitHash = entry.commit.hash,
            fileDetails = fileDetails
        )
        val issueTags = extractIssueTags("${entry.commit.subject}\n${entry.commit.body}")
        val addedComments = fileDetails.flatMap { fd ->
            fd.addedComments.map { comment -> fd.summary.fileName to comment }
        }.take(20)
        val apiHints = fileDetails.flatMap { it.apiHints }
        val allStackTags = fileDetails.flatMap { it.stackTags }.toCollection(linkedSetOf())
        val badges = detectCheckBadges(fileDetails, allStackTags)
        val flowSummary = buildCrossLayerFlowSummary(entry.commit, fileDetails, allStackTags)

        return CommitDetail(
            summary = entry.commit,
            fileDetails = fileDetails,
            issueTags = issueTags,
            allStackTags = allStackTags,
            checkBadges = badges,
            crossLayerFlowSummary = flowSummary,
            matchedMarkdownSections = emptyList(),
            apiHints = apiHints,
            addedComments = addedComments,
            entrypointTrace = entrypointTrace,
            sideEffectAlerts = sideEffectAlerts
        )
    }

    internal fun extractIssueTags(text: String): List<String> {
        val tags = linkedSetOf<String>()
        JIRA_TAG_REGEX.findAll(text).forEach { m ->
            tags.add("[${m.groupValues[1]}]")
        }
        HASH_ISSUE_REGEX.findAll(text).forEach { m ->
            tags.add("#${m.groupValues[1]}")
        }
        return tags.toList()
    }

    private fun detectCheckBadges(
        fileDetails: List<FileDiffDetail>,
        allStackTags: Set<StackTag>
    ): List<CheckBadge> {
        val badges = linkedSetOf<CheckBadge>()
        // Exclude pure import/whitespace-only files from architecture check badges to prevent false positives
        val substantiveFiles = fileDetails.filterNot { it.onlyImportOrWhitespaceChanges }
        val layers = substantiveFiles.map { it.summary.primaryLayer }.toSet()

        // [트랜잭션 경계 변경]
        if (substantiveFiles.any { it.hasTransactionalChange }) {
            badges.add(CheckBadge.TRANSACTION_BOUNDARY)
        }

        // [외부 API + DB 혼합 주의]
        val touchesDbOrService = PrimaryLayer.SERVICE in layers ||
            PrimaryLayer.RDB_JPA_MYBATIS in layers ||
            StackTag.JPA_MYBATIS in allStackTags
        val touchesExternal = PrimaryLayer.EXTERNAL_MSA_CLIENT in layers ||
            StackTag.MSA_LEGACY_API in allStackTags
        if (touchesDbOrService && touchesExternal) {
            badges.add(CheckBadge.EXTERNAL_API_AND_DB_MIXED)
        }

        // [Redis 캐시/키 변경]
        if (PrimaryLayer.CACHE_REDIS in layers || StackTag.REDIS in allStackTags) {
            badges.add(CheckBadge.REDIS_CACHE_KEY_CHANGE)
        }

        // [MongoDB 스키마/쿼리 변경]
        if (PrimaryLayer.NOSQL_MONGO in layers || StackTag.MONGODB in allStackTags) {
            badges.add(CheckBadge.MONGO_SCHEMA_QUERY_CHANGE)
        }

        // [MyBatis/SQL 쿼리 변경]
        val hasSqlOrMapperXml = substantiveFiles.any { fd ->
            val lower = fd.summary.path.lowercase()
            lower.endsWith(".sql") ||
                lower.endsWith("mapper.xml") ||
                (lower.endsWith(".xml") && (lower.contains("mapper") || lower.contains("mybatis") || StackTag.JPA_MYBATIS in fd.stackTags))
        }
        if (hasSqlOrMapperXml) {
            badges.add(CheckBadge.MYBATIS_SQL_QUERY_CHANGE)
        }

        // [API 스펙 변경]
        val hasApiSpecChange = PrimaryLayer.CONTROLLER_API in layers ||
            substantiveFiles.any { it.apiHints.isNotEmpty() } ||
            substantiveFiles.any { fd ->
                val lowerName = fd.summary.fileName.lowercase()
                fd.summary.primaryLayer == PrimaryLayer.EXTERNAL_MSA_CLIENT &&
                    (lowerName.contains("feign") || lowerName.contains("dto") || lowerName.contains("request") || lowerName.contains("response"))
            }
        if (hasApiSpecChange) {
            badges.add(CheckBadge.API_SPEC_CHANGE)
        }

        return badges.toList()
    }

    fun buildCrossLayerFlowSummary(
        commit: CommitSummary,
        fileDetails: List<FileDiffDetail>,
        allStackTags: Set<StackTag>
    ): String {
        val orderedLayers = fileDetails
            .map { it.summary.primaryLayer }
            .ifEmpty { commit.primaryLayers }
            .distinct()
            .sortedBy { it.flowOrder }

        if (orderedLayers.isEmpty()) {
            return CodeTrackerI18n.tr("흐름 요약: 변경 파일 없음", "Flow Summary: No changed files")
        }

        // Highlight architectural layers cleanly; append related stack tags
        val coreLayers = orderedLayers.filter { it != PrimaryLayer.TEST && it != PrimaryLayer.ETC }
            .ifEmpty { orderedLayers }
        val layerChain = coreLayers.joinToString(" → ") { it.displayName }

        return if (allStackTags.isNotEmpty()) {
            val tagPart = allStackTags.joinToString(", ") { it.label }
            CodeTrackerI18n.tr(
                "흐름 요약: $layerChain → [$tagPart] 연동 수정",
                "Flow Summary: $layerChain → [$tagPart] integration updated"
            )
        } else {
            CodeTrackerI18n.tr(
                "흐름 요약: $layerChain 수정",
                "Flow Summary: $layerChain updated"
            )
        }
    }
}
