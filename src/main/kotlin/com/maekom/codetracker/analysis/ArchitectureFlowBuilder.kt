package com.maekom.codetracker.analysis

import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.ExecutionFlowStage
import com.maekom.codetracker.model.FlowMergedFile
import com.maekom.codetracker.model.FlowStageGroup
import com.maekom.codetracker.model.PrimaryLayer

/**
 * Zero-latency 5-stage architectural execution flow assembler built solely from
 * Stage 1 in-memory CommitSummary & FileSummary metadata (zero extra git calls).
 * Merges repeatedly modified files across commits and aggregates modification counts + line stats.
 */
object ArchitectureFlowBuilder {

    private val ENTRYPOINT_TOKENS = listOf(
        "controller", "endpoint", "resolver", "batch", "job", "step", "tasklet",
        "scheduler", "listener", "consumer", "webhook"
    )
    private val EXTERNAL_TOKENS = listOf(
        "feign", "client", "webclient", "resttemplate", "restclient", "external",
        "legacy", "adapter", "integration", "kafka", "rabbit", "amqp", "producer", "gateway", "proxy"
    )
    private val PERSISTENCE_TOKENS = listOf(
        "repository", "entity", "mapper", "dao", "querydsl", "mongo", "document", "redis", "cache", "store"
    )
    private val BUSINESS_TOKENS = listOf(
        "service", "usecase", "facade", "manager", "component", "handler", "processor", "domain", "biz"
    )
    private val SCHEMA_CONFIG_MODEL_TOKENS = listOf(
        "dto", "vo", "request", "response", "model", "config", "security", "filter", "interceptor", "migration"
    )

    fun buildExecutionFlow(commits: List<CommitSummary>): List<FlowStageGroup> {
        if (commits.isEmpty()) return emptyList()

        // Merge identical file paths across all commits in the filtered set
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
            val stage = classifyExecutionStage(agg.path, agg.primaryLayer)
            FlowMergedFile(
                path = agg.path,
                fileName = agg.path.substringAfterLast('/'),
                stage = stage,
                primaryLayer = agg.primaryLayer,
                modifyCount = agg.modifyCount,
                totalAddedLines = agg.addedLines,
                totalDeletedLines = agg.deletedLines,
                commits = agg.commits.toList()
            )
        }

        val grouped = mergedFiles.groupBy { it.stage }
        return ExecutionFlowStage.entries
            .sortedBy { it.order }
            .mapNotNull { stage ->
                val filesInStage = grouped[stage] ?: return@mapNotNull null
                val sortedFiles = filesInStage.sortedWith(
                    compareByDescending<FlowMergedFile> { it.modifyCount }
                        .thenByDescending { it.totalAddedLines + it.totalDeletedLines }
                        .thenBy { it.fileName }
                )
                FlowStageGroup(stage = stage, files = sortedFiles)
            }
    }

    internal fun classifyExecutionStage(rawPath: String, primaryLayer: PrimaryLayer): ExecutionFlowStage {
        val normalized = rawPath.replace('\\', '/').lowercase()
        val fileName = normalized.substringAfterLast('/')
        val baseName = fileName.substringBeforeLast('.')

        // Explicit schema/config/sql/docs files -> Stage 5
        if (fileName.endsWith(".sql") ||
            fileName.endsWith(".md") ||
            fileName == "pom.xml" ||
            fileName.startsWith("build.gradle") ||
            fileName.startsWith("settings.gradle") ||
            (fileName.startsWith("application") &&
                (fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".properties")))
        ) {
            return ExecutionFlowStage.STAGE_5_SCHEMA_CONFIG_MODEL
        }

        // MyBatis Mapper XML -> Stage 3
        if (fileName.endsWith("mapper.xml") || (fileName.endsWith(".xml") && (normalized.contains("mapper") || normalized.contains("mybatis")))) {
            return ExecutionFlowStage.STAGE_3_DATA_PERSISTENCE
        }

        // DTO / Request / Response / VO -> Stage 5 (even if primaryLayer was Controller/API)
        if (SCHEMA_CONFIG_MODEL_TOKENS.any { baseName.contains(it) }) {
            return ExecutionFlowStage.STAGE_5_SCHEMA_CONFIG_MODEL
        }

        // Filename-level precision matching across stages 1..4
        if (ENTRYPOINT_TOKENS.any { baseName.contains(it) }) {
            return ExecutionFlowStage.STAGE_1_ENTRYPOINT
        }
        if (EXTERNAL_TOKENS.any { baseName.contains(it) }) {
            return ExecutionFlowStage.STAGE_4_EXTERNAL_INTEGRATION
        }
        if (PERSISTENCE_TOKENS.any { baseName.contains(it) }) {
            return ExecutionFlowStage.STAGE_3_DATA_PERSISTENCE
        }
        if (BUSINESS_TOKENS.any { baseName.contains(it) }) {
            return ExecutionFlowStage.STAGE_2_BUSINESS_LOGIC
        }

        // Path-level & PrimaryLayer fallback
        return when (primaryLayer) {
            PrimaryLayer.CONTROLLER_API -> ExecutionFlowStage.STAGE_1_ENTRYPOINT
            PrimaryLayer.SERVICE -> ExecutionFlowStage.STAGE_2_BUSINESS_LOGIC
            PrimaryLayer.RDB_JPA_MYBATIS,
            PrimaryLayer.NOSQL_MONGO,
            PrimaryLayer.CACHE_REDIS -> ExecutionFlowStage.STAGE_3_DATA_PERSISTENCE
            PrimaryLayer.EXTERNAL_MSA_CLIENT -> ExecutionFlowStage.STAGE_4_EXTERNAL_INTEGRATION
            PrimaryLayer.CONFIG_SECURITY,
            PrimaryLayer.DOCS_MD,
            PrimaryLayer.TEST,
            PrimaryLayer.ETC -> {
                when {
                    ENTRYPOINT_TOKENS.any { normalized.contains("/$it/") } -> ExecutionFlowStage.STAGE_1_ENTRYPOINT
                    EXTERNAL_TOKENS.any { normalized.contains("/$it/") } -> ExecutionFlowStage.STAGE_4_EXTERNAL_INTEGRATION
                    PERSISTENCE_TOKENS.any { normalized.contains("/$it/") } -> ExecutionFlowStage.STAGE_3_DATA_PERSISTENCE
                    BUSINESS_TOKENS.any { normalized.contains("/$it/") } -> ExecutionFlowStage.STAGE_2_BUSINESS_LOGIC
                    else -> ExecutionFlowStage.STAGE_5_SCHEMA_CONFIG_MODEL
                }
            }
        }
    }
}
