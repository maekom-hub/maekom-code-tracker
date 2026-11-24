package com.maekom.codetracker.model

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

enum class CheckBadge(val label: String, val description: String, val severity: BadgeSeverity) {
    TRANSACTION_BOUNDARY(
        "[트랜잭션 경계 변경]",
        "@Transactional 추가/수정/옵션 변경이 감지되었습니다.",
        BadgeSeverity.HIGH
    ),
    EXTERNAL_API_AND_DB_MIXED(
        "[외부 API + DB 혼합 주의]",
        "동일 커밋 내 Service/RDB 변경과 외부 MSA/레거시 API 호출이 함께 수정되었습니다.",
        BadgeSeverity.HIGH
    ),
    REDIS_CACHE_KEY_CHANGE(
        "[Redis 캐시/키 변경]",
        "@Cacheable/@CacheEvict 또는 RedisTemplate 키·TTL 조작 변경이 감지되었습니다.",
        BadgeSeverity.MEDIUM
    ),
    MONGO_SCHEMA_QUERY_CHANGE(
        "[MongoDB 스키마/쿼리 변경]",
        "@Document, MongoTemplate, Criteria 관련 변경이 감지되었습니다.",
        BadgeSeverity.MEDIUM
    ),
    MYBATIS_SQL_QUERY_CHANGE(
        "[MyBatis/SQL 쿼리 변경]",
        "SQL 스크립트 또는 MyBatis XML 매퍼 변경이 감지되었습니다.",
        BadgeSeverity.MEDIUM
    ),
    API_SPEC_CHANGE(
        "[API 스펙 변경]",
        "Controller 또는 Feign/Client 엔드포인트·DTO 변경이 감지되었습니다.",
        BadgeSeverity.INFO
    )
}

enum class BadgeSeverity {
    HIGH,
    MEDIUM,
    INFO
}

enum class FileChangeStatus(val code: String, val label: String) {
    ADDED("A", "추가"),
    MODIFIED("M", "수정"),
    DELETED("D", "삭제"),
    RENAMED("R", "이름변경"),
    COPIED("C", "복사"),
    TYPE_CHANGED("T", "타입변경"),
    UNKNOWN("?", "변경");

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
    val moduleSubPath: String? = null
) {
    override fun toString(): String = displayName
}

data class LayerFilterOption(
    val label: String,
    val layer: PrimaryLayer?
) {
    override fun toString(): String = label

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
    val primaryLayer: PrimaryLayer
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
    val matchedMdFullNote: String? = null
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
