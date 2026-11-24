package com.maekom.codetracker.analysis

import com.intellij.openapi.project.Project
import com.maekom.codetracker.git.GitRepositoryService
import com.maekom.codetracker.model.CheckBadge
import com.maekom.codetracker.model.CommitDetail
import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.FileDiffDetail
import com.maekom.codetracker.model.PrimaryLayer
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
        val enrichedFileDetails = MarkdownContextMatcher.attachHunkMarkdownNotes(
            fileDetails = fileDetails,
            loadedSections = loadedMdSections
        )

        // 3. Collect newly added comments & API hints
        val addedComments = enrichedFileDetails.flatMap { fd ->
            fd.addedComments.map { comment -> fd.summary.fileName to comment }
        }.take(30)

        val apiHints = enrichedFileDetails.flatMap { it.apiHints }
        val allStackTags = enrichedFileDetails.flatMap { it.stackTags }.toCollection(linkedSetOf())

        // 4. Detect architecture check badges
        val badges = detectCheckBadges(enrichedFileDetails, allStackTags)

        // 5. Build 1-line cross-layer flow summary
        val flowSummary = buildCrossLayerFlowSummary(commit, enrichedFileDetails, allStackTags)

        return CommitDetail(
            summary = commit,
            fileDetails = enrichedFileDetails,
            issueTags = issueTags,
            allStackTags = allStackTags,
            checkBadges = badges,
            crossLayerFlowSummary = flowSummary,
            matchedMarkdownSections = matchedMd,
            apiHints = apiHints,
            addedComments = addedComments
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

    private fun buildCrossLayerFlowSummary(
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
            return "흐름 요약: 변경 파일 없음"
        }

        // Highlight architectural layers cleanly; append related stack tags
        val coreLayers = orderedLayers.filter { it != PrimaryLayer.TEST && it != PrimaryLayer.ETC }
            .ifEmpty { orderedLayers }
        val layerChain = coreLayers.joinToString(" → ") { it.displayName }

        return if (allStackTags.isNotEmpty()) {
            val tagPart = allStackTags.joinToString(", ") { it.label }
            "흐름 요약: $layerChain → [$tagPart] 연동 수정"
        } else {
            "흐름 요약: $layerChain 수정"
        }
    }
}
