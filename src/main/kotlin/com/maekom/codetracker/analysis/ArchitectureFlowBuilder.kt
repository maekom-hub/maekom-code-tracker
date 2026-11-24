package com.maekom.codetracker.analysis

import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.ExecutionFlowStage
import com.maekom.codetracker.model.ExecutionFlowSubCategory
import com.maekom.codetracker.model.FlowMergedFile
import com.maekom.codetracker.model.FlowStageGroup
import com.maekom.codetracker.model.FlowSubGroup
import com.maekom.codetracker.model.PrimaryLayer

/**
 * Zero-latency 6-stage + 2-level sub-depth architectural execution flow assembler built solely
 * from Stage 1 in-memory CommitSummary & FileSummary metadata.
 *
 * Priority rules:
 * 1) Test code (src/test/, /test/) and documentation (.md, .adoc, .rst, .txt docs) are isolated
 *    first into the final stage (STAGE_6_TEST_DOCS).
 * 2) Class/filename suffix rules take strict priority over package names (e.g. `domain`, `model`)
 *    so `*Repository`/`*Mapper`/`*Dao`, `*Service`/`*ServiceImpl`/`*Manager`/`*UseCase`, and
 *    `*Controller`/`*RestController`/`*Router`/`*Handler` are never misclassified into Schema/Model.
 * 3) Every stage organizes files into kind-based sub-depth categories (`Stage -> SubCategory -> Files`).
 */
object ArchitectureFlowBuilder {

    private val DOC_EXTENSIONS = setOf(".md", ".mdx", ".adoc", ".rst")

    fun buildExecutionFlow(commits: List<CommitSummary>): List<FlowStageGroup> {
        if (commits.isEmpty()) return emptyList()

        data class MutableAgg(
            val path: String,
            val primaryLayer: PrimaryLayer,
            var modifyCount: Int = 0,
            var addedLines: Int = 0,
            var deletedLines: Int = 0,
            val commits: MutableList<CommitSummary> = mutableListOf()
        )

        val aggByPath = linkedMapOf<String, MutableAgg>()
        for (commit in commits.sortedByDescending { it.epochSeconds }) {
            for (file in commit.files) {
                val agg = aggByPath.computeIfAbsent(file.path) {
                    MutableAgg(path = file.path, primaryLayer = file.primaryLayer)
                }
                agg.modifyCount++
                agg.addedLines += file.addedLines
                agg.deletedLines += file.deletedLines
                if (agg.commits.none { it.hash == commit.hash }) {
                    agg.commits.add(commit)
                }
            }
        }

        val mergedFiles = aggByPath.values.map { agg ->
            val subCategory = classifySubCategory(agg.path, agg.primaryLayer)
            FlowMergedFile(
                path = agg.path,
                fileName = agg.path.substringAfterLast('/'),
                stage = subCategory.stage,
                subCategory = subCategory,
                primaryLayer = agg.primaryLayer,
                modifyCount = agg.modifyCount,
                totalAddedLines = agg.addedLines,
                totalDeletedLines = agg.deletedLines,
                commits = agg.commits.toList()
            )
        }

        val byStage = mergedFiles.groupBy { it.stage }
        return ExecutionFlowStage.entries
            .sortedBy { it.order }
            .mapNotNull { stage ->
                val stageFiles = byStage[stage] ?: return@mapNotNull null
                val bySub = stageFiles.groupBy { it.subCategory }
                val subGroups = ExecutionFlowSubCategory.entries
                    .filter { it.stage == stage }
                    .sortedBy { it.order }
                    .mapNotNull { sub ->
                        val files = bySub[sub] ?: return@mapNotNull null
                        val sorted = files.sortedWith(
                            compareByDescending<FlowMergedFile> { it.modifyCount }
                                .thenByDescending { it.totalAddedLines + it.totalDeletedLines }
                                .thenBy { it.fileName }
                        )
                        FlowSubGroup(subCategory = sub, files = sorted)
                    }
                if (subGroups.isEmpty()) null else FlowStageGroup(stage = stage, subGroups = subGroups)
            }
    }

    internal fun classifySubCategory(rawPath: String, primaryLayer: PrimaryLayer): ExecutionFlowSubCategory {
        val normalized = rawPath.replace('\\', '/').trim()
        val lowerPath = normalized.lowercase()
        val fileName = normalized.substringAfterLast('/')
        val lowerFileName = fileName.lowercase()
        val baseName = fileName.substringBeforeLast('.')
        val lowerBase = baseName.lowercase()

        // =========================================================================
        // PRIORITY 1: Isolate Test Code & Documentation into Final Stage (Stage 6)
        // =========================================================================
        if (isTestPath(lowerPath, lowerBase)) {
            return ExecutionFlowSubCategory.TEST_CODE
        }
        if (isDocumentationFile(lowerPath, lowerFileName)) {
            return ExecutionFlowSubCategory.DOCUMENTATION
        }

        // =========================================================================
        // PRIORITY 2: Strict Class/Filename Suffix Rules (overrides `domain`/`model` package names)
        // =========================================================================

        // 2-A. Data Access (Stage 4): *Repository, *Mapper, *Dao, *Query, *.sql, *Mapper.xml
        if (lowerFileName.endsWith(".sql") ||
            lowerFileName.endsWith("mapper.xml") ||
            (lowerFileName.endsWith(".xml") && (lowerPath.contains("mapper") || lowerPath.contains("mybatis") || lowerPath.contains("query")))
        ) {
            return ExecutionFlowSubCategory.MAPPER_QUERY
        }
        if (endsWithAny(lowerBase, "repository", "repositoryimpl", "repositorycustom", "dao", "daoimpl")) {
            return ExecutionFlowSubCategory.REPOSITORY_DAO
        }
        if (endsWithAny(lowerBase, "mapper", "mapperimpl", "querydsl", "query", "queryrepository")) {
            return ExecutionFlowSubCategory.MAPPER_QUERY
        }

        // 2-B. Business Logic (Stage 3): *Service, *ServiceImpl, *Manager, *UseCase
        if (endsWithAny(lowerBase, "serviceimpl", "usecaseimpl", "facadeimpl", "managerimpl")) {
            return ExecutionFlowSubCategory.SERVICE_IMPL
        }
        if (endsWithAny(lowerBase, "service", "usecase")) {
            return ExecutionFlowSubCategory.SERVICE_INTERFACE
        }
        if (endsWithAny(
                lowerBase,
                "manager", "component", "facade", "client", "feignclient", "feign",
                "adapter", "gateway", "integration", "processor", "helper", "executor"
            )
        ) {
            return ExecutionFlowSubCategory.COMPONENT_MANAGER
        }

        // 2-C. API / Presentation (Stage 2): *Controller, *RestController, *Router, *Handler, *Resolver, *Endpoint
        if (endsWithAny(lowerBase, "restcontroller", "controller", "endpoint", "resource", "api")) {
            return ExecutionFlowSubCategory.CONTROLLER_ENDPOINT
        }
        if (endsWithAny(lowerBase, "router", "handler", "resolver", "listener", "consumer", "scheduler", "job", "step", "tasklet")) {
            return ExecutionFlowSubCategory.HANDLER_RESOLVER
        }

        // 2-D. Entry / Config (Stage 1): Application, Filter/Interceptor/AOP, Config/Properties
        if (endsWithAny(lowerBase, "application", "bootstrap", "starter") &&
            (lowerFileName.endsWith(".java") || lowerFileName.endsWith(".kt"))
        ) {
            return ExecutionFlowSubCategory.APPLICATION
        }
        if (endsWithAny(lowerBase, "filter", "interceptor", "aspect", "advice", "aop")) {
            return ExecutionFlowSubCategory.FILTER_INTERCEPTOR_AOP
        }
        if (isConfigOrPropertiesFile(lowerFileName, lowerBase)) {
            return ExecutionFlowSubCategory.CONFIG_PROPERTIES
        }

        // 2-E. Schema / Model (Stage 5): Request, Response, Entity/Domain, Common DTO/VO
        if (endsWithAny(lowerBase, "request", "req", "requestdto", "reqdto", "command", "param", "params")) {
            return ExecutionFlowSubCategory.REQUEST
        }
        if (endsWithAny(lowerBase, "response", "res", "responsedto", "resdto", "resultdto")) {
            return ExecutionFlowSubCategory.RESPONSE
        }
        if (endsWithAny(lowerBase, "entity", "document", "domain", "model", "table", "schema")) {
            return ExecutionFlowSubCategory.ENTITY_DOMAIN
        }
        if (endsWithAny(lowerBase, "dto", "vo", "payload", "event", "message", "projection")) {
            return ExecutionFlowSubCategory.COMMON_DTO_VO
        }

        // =========================================================================
        // PRIORITY 3: Package/Path & PrimaryLayer Fallback
        // =========================================================================
        val dirPath = lowerPath.substringBeforeLast('/', "")
        return when {
            containsSegment(dirPath, "filter", "interceptor", "aop", "aspect", "security") ->
                ExecutionFlowSubCategory.FILTER_INTERCEPTOR_AOP
            containsSegment(dirPath, "config", "configuration", "properties") ->
                ExecutionFlowSubCategory.CONFIG_PROPERTIES
            containsSegment(dirPath, "controller", "endpoint", "api") ->
                ExecutionFlowSubCategory.CONTROLLER_ENDPOINT
            containsSegment(dirPath, "handler", "resolver", "router", "listener", "scheduler", "batch") ->
                ExecutionFlowSubCategory.HANDLER_RESOLVER
            containsSegment(dirPath, "repository", "dao") ->
                ExecutionFlowSubCategory.REPOSITORY_DAO
            containsSegment(dirPath, "mapper", "mybatis", "query", "querydsl") ->
                ExecutionFlowSubCategory.MAPPER_QUERY
            containsSegment(dirPath, "service", "usecase") ->
                ExecutionFlowSubCategory.COMPONENT_MANAGER
            containsSegment(dirPath, "client", "feign", "external", "legacy", "adapter", "integration") ->
                ExecutionFlowSubCategory.COMPONENT_MANAGER
            containsSegment(dirPath, "request", "req") ->
                ExecutionFlowSubCategory.REQUEST
            containsSegment(dirPath, "response", "res") ->
                ExecutionFlowSubCategory.RESPONSE
            containsSegment(dirPath, "entity", "document", "domain", "model") ->
                ExecutionFlowSubCategory.ENTITY_DOMAIN
            containsSegment(dirPath, "dto", "vo") ->
                ExecutionFlowSubCategory.COMMON_DTO_VO
            primaryLayer == PrimaryLayer.CONFIG_SECURITY -> ExecutionFlowSubCategory.CONFIG_PROPERTIES
            primaryLayer == PrimaryLayer.CONTROLLER_API -> ExecutionFlowSubCategory.CONTROLLER_ENDPOINT
            primaryLayer == PrimaryLayer.SERVICE -> ExecutionFlowSubCategory.COMPONENT_MANAGER
            primaryLayer == PrimaryLayer.EXTERNAL_MSA_CLIENT -> ExecutionFlowSubCategory.COMPONENT_MANAGER
            primaryLayer == PrimaryLayer.RDB_JPA_MYBATIS ||
                primaryLayer == PrimaryLayer.NOSQL_MONGO ||
                primaryLayer == PrimaryLayer.CACHE_REDIS -> ExecutionFlowSubCategory.REPOSITORY_DAO
            else -> ExecutionFlowSubCategory.COMMON_DTO_VO
        }
    }

    private fun isTestPath(lowerPath: String, lowerBase: String): Boolean {
        if (lowerPath.contains("src/test/") ||
            lowerPath.contains("/test/") ||
            lowerPath.startsWith("test/") ||
            lowerPath.contains("/tests/") ||
            lowerPath.startsWith("tests/")
        ) {
            return true
        }
        return endsWithAny(lowerBase, "test", "tests", "spec", "it")
    }

    private fun isDocumentationFile(lowerPath: String, lowerFileName: String): Boolean {
        if (DOC_EXTENSIONS.any { lowerFileName.endsWith(it) }) return true
        if (lowerFileName.startsWith("readme") || lowerFileName.startsWith("changelog") || lowerFileName.startsWith("license")) {
            return true
        }
        if (lowerFileName.endsWith(".txt") && (lowerPath.contains("docs/") || lowerPath.contains("/doc/"))) {
            return true
        }
        return false
    }

    private fun isConfigOrPropertiesFile(lowerFileName: String, lowerBase: String): Boolean {
        if (lowerFileName == "pom.xml" ||
            lowerFileName.startsWith("build.gradle") ||
            lowerFileName.startsWith("settings.gradle") ||
            lowerFileName == "gradle.properties" ||
            lowerFileName.endsWith(".yml") ||
            lowerFileName.endsWith(".yaml") ||
            lowerFileName.endsWith(".properties")
        ) {
            return true
        }
        return endsWithAny(lowerBase, "config", "configuration", "properties", "props", "settings")
    }

    private fun endsWithAny(text: String, vararg suffixes: String): Boolean {
        return suffixes.any { text.endsWith(it) }
    }

    private fun containsSegment(dirPath: String, vararg segments: String): Boolean {
        if (dirPath.isBlank()) return false
        val tokens = dirPath.split('/')
        return segments.any { seg -> tokens.any { it == seg } }
    }
}
