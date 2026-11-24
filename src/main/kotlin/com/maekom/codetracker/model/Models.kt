package com.maekom.codetracker.model

import com.maekom.codetracker.i18n.CodeTrackerI18n
import java.time.LocalDate
import java.time.LocalDateTime

enum class PrimaryLayer(
    val displayName: String,
    val badgeText: String,
    val flowOrder: Int,
    val filterable: Boolean = true
) {
    CONTROLLER_API("Controller/API", "[Controller/API]", 1, true),
    SERVICE("Service", "[Service]", 2, true),
    EXTERNAL_MSA_CLIENT("External/MSA-Client", "[External/MSA-Client]", 3, true),
    RDB_JPA_MYBATIS("RDB/JPA-MyBatis", "[RDB/JPA-MyBatis]", 4, true),
    NOSQL_MONGO("NoSQL/Mongo", "[NoSQL/Mongo]", 5, true),
    CACHE_REDIS("Cache/Redis", "[Cache/Redis]", 6, true),
    CONFIG_SECURITY("Config/Security", "[Config/Security]", 7, true),
    DOCS_MD("Docs(.md)", "[Docs(.md)]", 8, true),
    TEST("Test", "[Test]", 9, false),
    ETC("Etc", "[Etc]", 10, false);

    override fun toString(): String = displayName
}

enum class StackTag(val label: String, val relatedLayer: PrimaryLayer) {
    JPA_MYBATIS("#JPA/MyBatis", PrimaryLayer.RDB_JPA_MYBATIS),
    MONGODB("#MongoDB", PrimaryLayer.NOSQL_MONGO),
    REDIS("#Redis", PrimaryLayer.CACHE_REDIS),
    MSA_LEGACY_API("#MSA/Legacy-API", PrimaryLayer.EXTERNAL_MSA_CLIENT);

    override fun toString(): String = label
}

enum class CheckBadge(
    val label: String,
    val labelEn: String,
    val description: String,
    val descriptionEn: String,
    val severity: BadgeSeverity
) {
    TRANSACTION_BOUNDARY(
        "[트랜잭션 경계 변경]",
        "[Transaction Boundary Changed]",
        "@Transactional 추가/수정/옵션 변경이 감지되었습니다.",
        "@Transactional addition, modification, or option change detected.",
        BadgeSeverity.HIGH
    ),
    EXTERNAL_API_AND_DB_MIXED(
        "[외부 API + DB 혼합 주의]",
        "[External API + DB Mixed Caution]",
        "동일 커밋 내 Service/RDB 변경과 외부 MSA/레거시 API 호출이 함께 수정되었습니다.",
        "Service/RDB changes and external MSA/legacy API calls were modified in the same commit.",
        BadgeSeverity.HIGH
    ),
    REDIS_CACHE_KEY_CHANGE(
        "[Redis 캐시/키 변경]",
        "[Redis Cache/Key Changed]",
        "@Cacheable/@CacheEvict 또는 RedisTemplate 키·TTL 조작 변경이 감지되었습니다.",
        "@Cacheable/@CacheEvict or RedisTemplate key/TTL manipulation detected.",
        BadgeSeverity.MEDIUM
    ),
    MONGO_SCHEMA_QUERY_CHANGE(
        "[MongoDB 스키마/쿼리 변경]",
        "[MongoDB Schema/Query Changed]",
        "@Document, MongoTemplate, Criteria 관련 변경이 감지되었습니다.",
        "@Document, MongoTemplate, or Criteria changes detected.",
        BadgeSeverity.MEDIUM
    ),
    MYBATIS_SQL_QUERY_CHANGE(
        "[MyBatis/SQL 쿼리 변경]",
        "[MyBatis/SQL Query Changed]",
        "SQL 스크립트 또는 MyBatis XML 매퍼 변경이 감지되었습니다.",
        "SQL script or MyBatis XML mapper changes detected.",
        BadgeSeverity.MEDIUM
    ),
    API_SPEC_CHANGE(
        "[API 스펙 변경]",
        "[API Spec Changed]",
        "Controller 또는 Feign/Client 엔드포인트·DTO 변경이 감지되었습니다.",
        "Controller or Feign/Client endpoint/DTO changes detected.",
        BadgeSeverity.INFO
    );

    fun localizedLabel(): String = CodeTrackerI18n.tr(label, labelEn)
    fun localizedDescription(): String = CodeTrackerI18n.tr(description, descriptionEn)
}

enum class BadgeSeverity {
    HIGH,
    MEDIUM,
    INFO
}

enum class FileChangeStatus(val code: String, val label: String, val labelEn: String) {
    ADDED("A", "추가", "Added"),
    MODIFIED("M", "수정", "Modified"),
    DELETED("D", "삭제", "Deleted"),
    RENAMED("R", "이름변경", "Renamed"),
    COPIED("C", "복사", "Copied"),
    TYPE_CHANGED("T", "타입변경", "Type Changed"),
    UNKNOWN("?", "변경", "Changed");

    fun localizedLabel(): String = CodeTrackerI18n.tr(label, labelEn)

    companion object {
        fun fromGitCode(raw: String): FileChangeStatus {
            val c = raw.trim().firstOrNull()?.uppercaseChar() ?: return UNKNOWN
            return when (c) {
                'A' -> ADDED
                'M' -> MODIFIED
                'D' -> DELETED
                'R' -> RENAMED
                'C' -> COPIED
                'T' -> TYPE_CHANGED
                else -> UNKNOWN
            }
        }
    }
}

data class RepoOption(
    val displayName: String,
    val rootPath: String,
    val moduleSubPath: String? = null,
    val repoName: String = displayName
) {
    fun localizedDisplayName(): String = if (moduleSubPath.isNullOrBlank()) {
        "$repoName (${CodeTrackerI18n.allOption()})"
    } else {
        "$repoName / $moduleSubPath"
    }

    override fun toString(): String = localizedDisplayName()
}

data class LayerFilterOption(
    val label: String,
    val layer: PrimaryLayer?
) {
    override fun toString(): String = if (layer == null) CodeTrackerI18n.allOption() else label

    companion object {
        val ALL = LayerFilterOption("전체", null)

        fun defaultOptions(): List<LayerFilterOption> = listOf(ALL) +
            PrimaryLayer.entries.filter { it.filterable }.map { LayerFilterOption(it.displayName, it) }
    }
}

data class AnalysisFilter(
    val repo: RepoOption,
    val branch: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val author: String?,
    val keyword: String,
    val layerFilter: PrimaryLayer?,
    val excludeMerges: Boolean
)

data class FileSummary(
    val path: String,
    val oldPath: String? = null,
    val status: FileChangeStatus,
    val primaryLayer: PrimaryLayer,
    val addedLines: Int = 0,
    val deletedLines: Int = 0
) {
    val fileName: String
        get() = path.substringAfterLast('/')
}

data class CommitSummary(
    val hash: String,
    val shortHash: String,
    val parentHashes: List<String>,
    val authorName: String,
    val authorEmail: String,
    val timestamp: LocalDateTime,
    val epochSeconds: Long,
    val subject: String,
    val body: String,
    val files: List<FileSummary>,
    val primaryLayers: List<PrimaryLayer>,
    val hasMarkdownChanges: Boolean
) {
    val isInitialCommit: Boolean
        get() = parentHashes.isEmpty()
}

data class MarkdownFileRef(
    val commitHash: String,
    val filePath: String,
    val commitSubject: String,
    val timestamp: LocalDateTime
)

data class WorkEpisode(
    val id: String,
    val date: LocalDate,
    val authorName: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val commits: List<CommitSummary>,
    val markdownFiles: List<MarkdownFileRef>
) {
    val hasMarkdownDocs: Boolean
        get() = markdownFiles.isNotEmpty()
}

data class MarkdownSection(
    val filePath: String,
    val commitHash: String,
    val heading: String,
    val level: Int,
    val bodyLines: List<String>,
    val checkedItems: List<String>,
    val matchedKeywords: List<String>,
    val score: Int
)

enum class EntrypointKind(val badge: String) {
    API("[API]"),
    BATCH("[BATCH]"),
    SCHEDULER("[SCHED]"),
    LISTENER("[EVENT]")
}

data class EntrypointTarget(
    val kind: EntrypointKind,
    val displayLabel: String,
    val filePath: String,
    val lineNumber1Based: Int,
    val className: String,
    val methodName: String,
    val hopDepth: Int,
    val impactReasonKo: String = "",
    val impactReasonEn: String = "",
    val callChain: List<String> = emptyList()
) {
    fun localizedImpactReason(): String = CodeTrackerI18n.tr(impactReasonKo, impactReasonEn)
}

data class EntrypointTraceResult(
    val entrypoints: List<EntrypointTarget> = emptyList(),
    val isDumbMode: Boolean = false,
    val truncatedByLimit: Boolean = false
)

enum class RadarSeverity {
    HIGH,
    WARN;

    fun localizedBadge(): String = when (this) {
        HIGH -> CodeTrackerI18n.radarBadgeHigh()
        WARN -> CodeTrackerI18n.radarBadgeWarn()
    }
}

enum class SideEffectRule(val severity: RadarSeverity) {
    TRANSACTION_BOUNDARY_CHANGE(RadarSeverity.HIGH),
    TRANSACTION_SELF_INVOCATION(RadarSeverity.HIGH),
    MYBATIS_BULK_UPDATE_DELETE_RISK(RadarSeverity.HIGH),
    EXTERNAL_SPEC_OR_RESILIENCE_RISK(RadarSeverity.WARN);

    fun localizedTitle(): String = when (this) {
        TRANSACTION_BOUNDARY_CHANGE -> CodeTrackerI18n.radarRuleTxBoundary()
        TRANSACTION_SELF_INVOCATION -> CodeTrackerI18n.radarRuleTxSelfInvocation()
        MYBATIS_BULK_UPDATE_DELETE_RISK -> CodeTrackerI18n.radarRuleMyBatisBulk()
        EXTERNAL_SPEC_OR_RESILIENCE_RISK -> CodeTrackerI18n.radarRuleExternalSpec()
    }
}

data class SideEffectAlert(
    val severity: RadarSeverity,
    val rule: SideEffectRule,
    val filePath: String,
    val fileName: String,
    val hunkPointer: CodeHunkPointer,
    val detailKo: String,
    val detailEn: String,
    val commitHash: String? = null
) {
    fun localizedDetail(): String = CodeTrackerI18n.tr(detailKo, detailEn)
}

enum class ExecutionFlowStage(val order: Int) {
    STAGE_1_ENTRY_CONFIG(1),
    STAGE_2_API_PRESENTATION(2),
    STAGE_3_BUSINESS_LOGIC(3),
    STAGE_4_DATA_ACCESS(4),
    STAGE_5_SCHEMA_MODEL(5),
    STAGE_6_TEST_DOCS(6);

    fun localizedTitle(): String = when (this) {
        STAGE_1_ENTRY_CONFIG -> CodeTrackerI18n.flowStage1()
        STAGE_2_API_PRESENTATION -> CodeTrackerI18n.flowStage2()
        STAGE_3_BUSINESS_LOGIC -> CodeTrackerI18n.flowStage3()
        STAGE_4_DATA_ACCESS -> CodeTrackerI18n.flowStage4()
        STAGE_5_SCHEMA_MODEL -> CodeTrackerI18n.flowStage5()
        STAGE_6_TEST_DOCS -> CodeTrackerI18n.flowStage6()
    }
}

enum class ExecutionFlowSubCategory(
    val stage: ExecutionFlowStage,
    val order: Int,
    val displayLabel: String
) {
    // Stage 1: Entry / Config
    CONFIG_PROPERTIES(ExecutionFlowStage.STAGE_1_ENTRY_CONFIG, 1, "Config / Properties"),
    FILTER_INTERCEPTOR_AOP(ExecutionFlowStage.STAGE_1_ENTRY_CONFIG, 2, "Filter / Interceptor / AOP"),
    APPLICATION(ExecutionFlowStage.STAGE_1_ENTRY_CONFIG, 3, "Application"),

    // Stage 2: API / Presentation
    CONTROLLER_ENDPOINT(ExecutionFlowStage.STAGE_2_API_PRESENTATION, 1, "Controller / Endpoint"),
    HANDLER_RESOLVER(ExecutionFlowStage.STAGE_2_API_PRESENTATION, 2, "Handler / Resolver"),

    // Stage 3: Business Logic
    SERVICE_INTERFACE(ExecutionFlowStage.STAGE_3_BUSINESS_LOGIC, 1, "Service Interface"),
    SERVICE_IMPL(ExecutionFlowStage.STAGE_3_BUSINESS_LOGIC, 2, "Service Implementation (*Impl)"),
    COMPONENT_MANAGER(ExecutionFlowStage.STAGE_3_BUSINESS_LOGIC, 3, "Component / Manager"),

    // Stage 4: Data Access
    REPOSITORY_DAO(ExecutionFlowStage.STAGE_4_DATA_ACCESS, 1, "Repository / DAO"),
    MAPPER_QUERY(ExecutionFlowStage.STAGE_4_DATA_ACCESS, 2, "Mapper / Query"),

    // Stage 5: Schema / Model
    REQUEST(ExecutionFlowStage.STAGE_5_SCHEMA_MODEL, 1, "Request"),
    RESPONSE(ExecutionFlowStage.STAGE_5_SCHEMA_MODEL, 2, "Response"),
    ENTITY_DOMAIN(ExecutionFlowStage.STAGE_5_SCHEMA_MODEL, 3, "Entity / Domain"),
    COMMON_DTO_VO(ExecutionFlowStage.STAGE_5_SCHEMA_MODEL, 4, "Common DTO / VO"),

    // Stage 6 (Last): Tests & Docs
    TEST_CODE(ExecutionFlowStage.STAGE_6_TEST_DOCS, 1, "Test Code"),
    DOCUMENTATION(ExecutionFlowStage.STAGE_6_TEST_DOCS, 2, "Documentation (.md)");
}

data class FlowMergedFile(
    val path: String,
    val fileName: String,
    val stage: ExecutionFlowStage,
    val subCategory: ExecutionFlowSubCategory,
    val primaryLayer: PrimaryLayer,
    val modifyCount: Int,
    val totalAddedLines: Int,
    val totalDeletedLines: Int,
    val commits: List<CommitSummary>
)

data class FlowSubGroup(
    val subCategory: ExecutionFlowSubCategory,
    val files: List<FlowMergedFile>
)

data class FlowStageGroup(
    val stage: ExecutionFlowStage,
    val subGroups: List<FlowSubGroup>
) {
    val totalFileCount: Int
        get() = subGroups.sumOf { it.files.size }
}

data class MergedFileCommitSection(
    val commit: CommitSummary,
    val fileDetail: FileDiffDetail
)

data class MergedFileAnalysisDetail(
    val mergedFile: FlowMergedFile,
    val commitSections: List<MergedFileCommitSection>,
    val entrypointTrace: EntrypointTraceResult,
    val sideEffectAlerts: List<SideEffectAlert>,
    val allStackTags: Set<StackTag>,
    val checkBadges: List<CheckBadge>
)

data class CodeHunkPointer(
    val filePath: String,
    val newStartLine: Int,
    val newLineCount: Int,
    val oldStartLine: Int,
    val oldLineCount: Int,
    val headerContext: String,
    val detectedMethodName: String?,
    val displayContext: String,
    val addedLines: Int,
    val deletedLines: Int,
    val firstChangedCodeLine: String? = null,
    val matchedMdShortNote: String? = null,
    val matchedMdFullNote: String? = null,
    val rawDiffLines: List<String> = emptyList(),
    val highestRadarSeverity: RadarSeverity? = null
)

data class ApiEndpointHint(
    val sourceFile: String,
    val kind: String,
    val methodOrAnnotation: String,
    val pathOrUri: String,
    val lineNumber: Int
)

data class FileDiffDetail(
    val summary: FileSummary,
    val stackTags: Set<StackTag>,
    val hunks: List<CodeHunkPointer>,
    val addedLinesTotal: Int,
    val deletedLinesTotal: Int,
    val addedComments: List<String>,
    val apiHints: List<ApiEndpointHint>,
    val hasTransactionalChange: Boolean,
    val skippedLargeOrBinary: Boolean = false,
    val skipReason: String? = null,
    val onlyImportOrWhitespaceChanges: Boolean = false
)

data class CommitDetail(
    val summary: CommitSummary,
    val fileDetails: List<FileDiffDetail>,
    val issueTags: List<String>,
    val allStackTags: Set<StackTag>,
    val checkBadges: List<CheckBadge>,
    val crossLayerFlowSummary: String,
    val matchedMarkdownSections: List<MarkdownSection>,
    val apiHints: List<ApiEndpointHint>,
    val addedComments: List<Pair<String, String>>,
    val entrypointTrace: EntrypointTraceResult = EntrypointTraceResult(),
    val sideEffectAlerts: List<SideEffectAlert> = emptyList(),
    val analyzedAt: LocalDateTime = LocalDateTime.now()
)

data class AnalysisResult(
    val filter: AnalysisFilter,
    val commits: List<CommitSummary>,
    val episodes: List<WorkEpisode>,
    val layerCounts: Map<PrimaryLayer, Int>,
    val totalModifiedFiles: Int,
    val truncatedToLimit: Boolean
)

data class FileHistoryCommitEntry(
    val commit: CommitSummary,
    val status: FileChangeStatus,
    val pathAtCommit: String,
    val oldPathAtCommit: String? = null,
    val versionGroupLabel: String
) {
    fun toFileSummary(): FileSummary = FileSummary(
        path = pathAtCommit,
        oldPath = oldPathAtCommit,
        status = status,
        primaryLayer = commit.files.firstOrNull { it.path == pathAtCommit }?.primaryLayer
            ?: commit.primaryLayers.firstOrNull()
            ?: PrimaryLayer.ETC
    )
}

data class FileHistoryVersionGroup(
    val groupLabel: String,
    val isTagGroup: Boolean,
    val entries: List<FileHistoryCommitEntry>
)

data class FileHistoryResult(
    val targetFilePath: String,
    val branch: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val hasTagsInRepo: Boolean,
    val groups: List<FileHistoryVersionGroup>,
    val totalCommits: Int
)
