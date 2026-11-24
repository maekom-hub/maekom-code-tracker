package com.maekom.codetracker.analysis

import com.maekom.codetracker.model.PrimaryLayer
import com.maekom.codetracker.model.StackTag

/**
 * Dual classification engine:
 * 1) Primary Layer (file path & filename based, preventing Service misclassification)
 * 2) Related Tech Stack Tags (multi-label detection from diff code annotations/types)
 */
object LayerClassifier {

    private val CONTROLLER_KEYWORDS = listOf("controller", "endpoint", "dto", "request", "response", "resolver", "api")
    private val SERVICE_KEYWORDS = listOf("service", "usecase", "manager", "facade")
    private val EXTERNAL_KEYWORDS = listOf("client", "external", "legacy", "adapter", "integration", "feign")
    private val RDB_KEYWORDS = listOf("entity", "repository", "mapper", "dao", "migration")
    private val MONGO_KEYWORDS = listOf("mongo", "document")
    private val REDIS_KEYWORDS = listOf("redis", "cache")
    private val CONFIG_KEYWORDS = listOf("config", "security", "filter", "interceptor")

    fun classifyPrimaryLayer(rawPath: String): PrimaryLayer {
        val normalized = rawPath.replace('\\', '/').trim().lowercase()
        val fileName = normalized.substringAfterLast('/')
        val pathSegments = normalized.split('/').filter { it.isNotBlank() }

        // 1. Docs (.md)
        if (fileName.endsWith(".md")) {
            return PrimaryLayer.DOCS_MD
        }

        // 2. Test path (src/test)
        if (normalized.contains("src/test/") || normalized.startsWith("test/")) {
            return PrimaryLayer.TEST
        }

        // 3. Explicit config/build/SQL/Mapper filenames
        if (isExplicitConfigFile(fileName)) {
            return PrimaryLayer.CONFIG_SECURITY
        }
        if (fileName.endsWith(".sql") || fileName.endsWith("mapper.xml")) {
            return PrimaryLayer.RDB_JPA_MYBATIS
        }

        // 4. Filename-level classification (highest specificity; prevents Service/Repository misclassification)
        val fileBaseName = fileName.substringBeforeLast('.')
        classifyByText(fileBaseName)?.let { return it }

        // 5. Path-segment classification (excluding filename)
        val dirPath = pathSegments.dropLast(1).joinToString("/")
        classifyByText(dirPath)?.let { return it }

        return PrimaryLayer.ETC
    }

    private fun isExplicitConfigFile(fileName: String): Boolean {
        if (fileName == "pom.xml") return true
        if (fileName.startsWith("build.gradle") || fileName.startsWith("settings.gradle")) return true
        if (fileName.startsWith("application") &&
            (fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".properties"))
        ) {
            return true
        }
        return false
    }

    private fun classifyByText(text: String): PrimaryLayer? {
        if (text.isBlank()) return null
        // Check NoSQL/Mongo and Cache/Redis before RDB/Service so MongoRepository or RedisCacheService is accurately classified
        if (MONGO_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.NOSQL_MONGO
        if (REDIS_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.CACHE_REDIS
        // Check External/MSA-Client before Service so ExternalApiService / LegacyOrderClient is classified as External
        if (EXTERNAL_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.EXTERNAL_MSA_CLIENT
        if (CONTROLLER_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.CONTROLLER_API
        if (RDB_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.RDB_JPA_MYBATIS
        if (CONFIG_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.CONFIG_SECURITY
        if (SERVICE_KEYWORDS.any { text.contains(it) }) return PrimaryLayer.SERVICE
        return null
    }

    fun detectStackTags(diffContent: String, filePath: String): Set<StackTag> {
        val tags = linkedSetOf<StackTag>()
        val lowerPath = filePath.replace('\\', '/').lowercase()

        // #JPA/MyBatis
        if (containsAny(
                diffContent,
                "@Entity", "JpaRepository", "@Mapper", "<select", "<update", "<insert", "<delete",
                "EntityManager", "CrudRepository"
            ) || lowerPath.endsWith(".sql") || lowerPath.endsWith("mapper.xml")
        ) {
            tags.add(StackTag.JPA_MYBATIS)
        }

        // #MongoDB
        if (containsAny(
                diffContent,
                "@Document", "MongoRepository", "MongoTemplate", "Criteria", "ReactiveMongoRepository"
            )
        ) {
            tags.add(StackTag.MONGODB)
        }

        // #Redis
        if (containsAny(
                diffContent,
                "@RedisHash", "RedisTemplate", "StringRedisTemplate", "@Cacheable", "@CacheEvict",
                "@CachePut", "ReactiveRedisTemplate"
            )
        ) {
            tags.add(StackTag.REDIS)
        }

        // #MSA/Legacy-API
        if (containsAny(
                diffContent,
                "@FeignClient", "RestTemplate", "WebClient", "RestClient", "@HttpExchange"
            )
        ) {
            tags.add(StackTag.MSA_LEGACY_API)
        }

        return tags
    }

    private fun containsAny(text: String, vararg needles: String): Boolean {
        return needles.any { text.contains(it) }
    }
}
