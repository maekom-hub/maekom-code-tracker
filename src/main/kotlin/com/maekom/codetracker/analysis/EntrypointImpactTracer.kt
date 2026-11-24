package com.maekom.codetracker.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.maekom.codetracker.model.CodeHunkPointer
import com.maekom.codetracker.model.EntrypointKind
import com.maekom.codetracker.model.EntrypointTarget
import com.maekom.codetracker.model.EntrypointTraceResult
import com.maekom.codetracker.model.FileDiffDetail
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Community-Edition-compatible IntelliJ Java PSI & Reference Graph upward caller tracer.
 * Traces modified Java/Kotlin methods and MyBatis Mapper XML SQL IDs upward through
 * Spring Interface -> Impl bridges (`findDeepestSuperMethods()`) to identify impacted
 * Web API Controllers, Batch Jobs/Steps/Tasklets, Schedulers, and Event/MQ Listeners.
 */
object EntrypointImpactTracer {

    private const val MAX_DEPTH = 6
    private const val MAX_VISITED_METHODS = 150
    private const val MAX_REFS_PER_TARGET = 40

    private val MAPPER_NAMESPACE_REGEX = Regex("""<mapper\b[^>]*\bnamespace\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MAPPER_SQL_ID_REGEX = Regex("""<(select|insert|update|delete)\b[^>]*\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val QUOTED_STRING_REGEX = Regex(""""([^"]*)"""")

    private val CONTROLLER_ANNOTATIONS = setOf("RestController", "Controller")
    private val HTTP_MAPPING_MAP = mapOf(
        "GetMapping" to "GET",
        "PostMapping" to "POST",
        "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE",
        "PatchMapping" to "PATCH",
        "RequestMapping" to "REQUEST"
    )
    private val SCHEDULER_ANNOTATIONS = setOf("Scheduled")
    private val LISTENER_ANNOTATIONS = setOf("EventListener", "TransactionalEventListener", "KafkaListener", "RabbitListener")
    private val BATCH_SCOPE_ANNOTATIONS = setOf("JobScope", "StepScope")
    private val BATCH_SUPER_NAMES = setOf(
        "Job", "Step", "Tasklet", "ItemReader", "ItemProcessor", "ItemWriter",
        "AbstractItemCountingItemStreamItemReader", "ChunkOrientedTasklet"
    )

    // Cache: (projectHash::commitHash::filePath::methodKey) -> EntrypointTraceResult
    private val methodTraceCache = ConcurrentHashMap<String, EntrypointTraceResult>()

    fun traceCommitEntrypoints(
        project: Project,
        repoRootPath: String,
        commitHash: String,
        fileDetails: List<FileDiffDetail>
    ): EntrypointTraceResult {
        if (project.isDisposed) return EntrypointTraceResult()
        if (DumbService.getInstance(project).isDumb) {
            return EntrypointTraceResult(isDumbMode = true)
        }

        val allEntrypoints = linkedMapOf<String, EntrypointTarget>()
        var anyTruncated = false

        for (fd in fileDetails) {
            val normalizedPath = fd.summary.path.replace('\\', '/')
            if (isExcludedFromTrace(normalizedPath) || fd.skippedLargeOrBinary || fd.onlyImportOrWhitespaceChanges) {
                continue
            }
            if (fd.hunks.isEmpty()) continue

            for (hunk in fd.hunks) {
                val methodKey = hunk.detectedMethodName ?: "line:${hunk.newStartLine}"
                val cacheKey = "${project.locationHash}::$commitHash::$normalizedPath::$methodKey"

                val cached = methodTraceCache[cacheKey]
                val traceRes = if (cached != null && !cached.isDumbMode) {
                    cached
                } else {
                    val computed = traceSingleHunkSafe(project, repoRootPath, fd, hunk)
                    if (!computed.isDumbMode) {
                        methodTraceCache[cacheKey] = computed
                    }
                    computed
                }

                if (traceRes.isDumbMode) {
                    return EntrypointTraceResult(isDumbMode = true)
                }
                if (traceRes.truncatedByLimit) {
                    anyTruncated = true
                }
                for (ep in traceRes.entrypoints) {
                    val dedupKey = "${ep.kind}|${ep.displayLabel}|${ep.filePath}|${ep.lineNumber1Based}"
                    val existing = allEntrypoints[dedupKey]
                    if (existing == null || ep.hopDepth < existing.hopDepth) {
                        allEntrypoints[dedupKey] = ep
                    }
                }
            }
        }

        val sorted = allEntrypoints.values
            .sortedWith(compareBy<EntrypointTarget> { it.kind.ordinal }.thenBy { it.hopDepth }.thenBy { it.displayLabel })
            .take(25)
        return EntrypointTraceResult(
            entrypoints = sorted,
            isDumbMode = false,
            truncatedByLimit = anyTruncated
        )
    }

    private fun isExcludedFromTrace(normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()
        if (lower.contains("src/test/") || lower.startsWith("test/")) return true
        return !(lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".xml"))
    }

    private fun traceSingleHunkSafe(
        project: Project,
        repoRootPath: String,
        fd: FileDiffDetail,
        hunk: CodeHunkPointer
    ): EntrypointTraceResult {
        return try {
            ApplicationManager.getApplication().runReadAction<EntrypointTraceResult> {
                if (project.isDisposed || DumbService.getInstance(project).isDumb) {
                    return@runReadAction EntrypointTraceResult(isDumbMode = true)
                }
                val scope = GlobalSearchScope.projectScope(project)
                val seedMethods = resolveSeedMethods(project, repoRootPath, fd, hunk, scope)
                if (seedMethods.isEmpty()) {
                    return@runReadAction EntrypointTraceResult()
                }
                bfsTraceUpward(seedMethods, scope)
            }
        } catch (_: IndexNotReadyException) {
            EntrypointTraceResult(isDumbMode = true)
        } catch (_: Exception) {
            EntrypointTraceResult()
        }
    }

    private fun resolveSeedMethods(
        project: Project,
        repoRootPath: String,
        fd: FileDiffDetail,
        hunk: CodeHunkPointer,
        scope: GlobalSearchScope
    ): List<PsiMethod> {
        val relPath = fd.summary.path.replace('\\', '/')
        val lower = relPath.lowercase()

        // Case 1: MyBatis XML (*Mapper.xml or XML with <mapper namespace="...">)
        if (lower.endsWith(".xml")) {
            return resolveMyBatisMapperMethods(project, repoRootPath, relPath, hunk, scope)
        }

        // Case 2: Java / Kotlin source file via PsiClassOwner or PsiShortNamesCache
        val nioPath = Path.of(repoRootPath, relPath).normalize()
        val vf = LocalFileSystem.getInstance().findFileByNioFile(nioPath)
        val psiFile = if (vf != null && vf.isValid) PsiManager.getInstance(project).findFile(vf) else null

        val classes = mutableListOf<PsiClass>()
        if (psiFile is PsiClassOwner) {
            classes.addAll(psiFile.classes)
        }
        if (classes.isEmpty()) {
            val simpleName = fd.summary.fileName.substringBeforeLast('.')
            if (simpleName.isNotBlank()) {
                val candidates = PsiShortNamesCache.getInstance(project).getClassesByName(simpleName, scope)
                val pathMatched = candidates.filter { cls ->
                    val clsPath = cls.containingFile?.virtualFile?.path?.replace('\\', '/') ?: ""
                    clsPath.endsWith(relPath)
                }
                classes.addAll(if (pathMatched.isNotEmpty()) pathMatched else candidates.take(2))
            }
        }

        val seeds = linkedSetOf<PsiMethod>()
        val targetMethodName = hunk.detectedMethodName?.substringAfter('#')?.substringBefore('(')?.trim()

        if (!targetMethodName.isNullOrBlank()) {
            for (cls in classes) {
                cls.findMethodsByName(targetMethodName, false).forEach { seeds.add(it) }
            }
        }

        // Fallback: locate enclosing PsiMethod at hunk's newStartLine inside the PSI file
        if (seeds.isEmpty() && psiFile != null) {
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            if (doc != null && doc.lineCount > 0) {
                val zeroLine = (hunk.newStartLine - 1).coerceIn(0, doc.lineCount - 1)
                val offset = doc.getLineStartOffset(zeroLine)
                val elem = psiFile.findElementAt(offset)
                val enclosingMethod = PsiTreeUtil.getParentOfType(elem, PsiMethod::class.java, false)
                if (enclosingMethod != null) {
                    seeds.add(enclosingMethod)
                }
            }
        }

        return seeds.toList()
    }

    private fun resolveMyBatisMapperMethods(
        project: Project,
        repoRootPath: String,
        relPath: String,
        hunk: CodeHunkPointer,
        scope: GlobalSearchScope
    ): List<PsiMethod> {
        val nioPath = Path.of(repoRootPath, relPath).normalize()
        val xmlLines = runCatching { Files.readAllLines(nioPath, StandardCharsets.UTF_8) }.getOrNull() ?: return emptyList()
        if (xmlLines.isEmpty()) return emptyList()

        val fullText = xmlLines.joinToString("\n")
        val namespace = MAPPER_NAMESPACE_REGEX.find(fullText)?.groupValues?.getOrNull(1)?.trim() ?: return emptyList()

        val sqlIds = linkedSetOf<String>()
        // 1) Method id already extracted from hunk (e.g. "select#findActiveUsim")
        hunk.detectedMethodName?.let { raw ->
            val id = if (raw.contains('#')) raw.substringAfter('#').trim() else raw.trim()
            if (id.isNotBlank()) sqlIds.add(id)
        }
        // 2) Scan upward from hunk.newStartLine to locate enclosing <select|insert|update|delete id="...">
        if (sqlIds.isEmpty()) {
            val startIdx = (hunk.newStartLine - 1).coerceIn(0, xmlLines.lastIndex)
            val scanMin = (startIdx - 60).coerceAtLeast(0)
            for (i in startIdx downTo scanMin) {
                val match = MAPPER_SQL_ID_REGEX.find(xmlLines[i])
                if (match != null) {
                    sqlIds.add(match.groupValues[2].trim())
                    break
                }
            }
        }
        if (sqlIds.isEmpty()) return emptyList()

        val mapperClass = JavaPsiFacade.getInstance(project).findClass(namespace, scope) ?: return emptyList()
        val result = mutableListOf<PsiMethod>()
        for (sqlId in sqlIds) {
            result.addAll(mapperClass.findMethodsByName(sqlId, true))
        }
        return result
    }

    private fun bfsTraceUpward(
        seedMethods: List<PsiMethod>,
        scope: GlobalSearchScope
    ): EntrypointTraceResult {
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<PsiMethod, Int>>()
        val foundEntrypoints = linkedMapOf<String, EntrypointTarget>()
        var truncated = false

        for (seed in seedMethods) {
            val key = canonicalMethodKey(seed)
            if (visited.add(key)) {
                queue.add(seed to 0)
            }
        }

        while (queue.isNotEmpty()) {
            val (currentMethod, depth) = queue.removeFirst()

            // Identify if currentMethod is itself an API / Batch / Scheduler / Listener entrypoint
            detectEntrypoint(currentMethod, depth)?.let { target ->
                val epKey = "${target.kind}|${target.displayLabel}|${target.filePath}|${target.lineNumber1Based}"
                val prev = foundEntrypoints[epKey]
                if (prev == null || target.hopDepth < prev.hopDepth) {
                    foundEntrypoints[epKey] = target
                }
            }

            if (depth >= MAX_DEPTH) {
                truncated = true
                continue
            }
            if (visited.size >= MAX_VISITED_METHODS) {
                truncated = true
                break
            }

            // Spring Interface -> Impl bridge: include both this method AND deepest super/interface methods
            val searchTargets = linkedSetOf<PsiMethod>()
            searchTargets.add(currentMethod)
            runCatching {
                currentMethod.findDeepestSuperMethods().forEach { searchTargets.add(it) }
                currentMethod.findSuperMethods().forEach { searchTargets.add(it) }
            }

            for (target in searchTargets) {
                if (visited.size >= MAX_VISITED_METHODS) {
                    truncated = true
                    break
                }
                var refCount = 0
                ReferencesSearch.search(target, scope).forEach(Processor { ref ->
                    if (refCount >= MAX_REFS_PER_TARGET || visited.size >= MAX_VISITED_METHODS) {
                        truncated = true
                        return@Processor false
                    }
                    refCount++

                    val refElem = ref.element
                    val refPath = refElem.containingFile?.virtualFile?.path?.replace('\\', '/')?.lowercase() ?: ""
                    if (refPath.contains("/src/test/") || refPath.startsWith("test/")) {
                        return@Processor true
                    }

                    val callerMethod = PsiTreeUtil.getParentOfType(refElem, PsiMethod::class.java, false)
                        ?: return@Processor true
                    val callerKey = canonicalMethodKey(callerMethod)
                    if (visited.add(callerKey)) {
                        queue.add(callerMethod to (depth + 1))
                    }
                    true
                })
            }
        }

        return EntrypointTraceResult(
            entrypoints = foundEntrypoints.values.toList(),
            isDumbMode = false,
            truncatedByLimit = truncated
        )
    }

    private fun detectEntrypoint(method: PsiMethod, hopDepth: Int): EntrypointTarget? {
        val cls = method.containingClass ?: return null
        val vf = cls.containingFile?.virtualFile ?: return null
        val filePath = vf.path
        val line1Based = resolveLineNumber1Based(method)
        val className = cls.name ?: "UnknownClass"
        val methodName = method.name

        // 1) Web API Controller check
        val isController = hasAnyAnnotation(cls.annotations, CONTROLLER_ANNOTATIONS)
        if (isController) {
            val mappingInfo = extractHttpMapping(cls, method)
            if (mappingInfo != null) {
                val (httpVerb, uri) = mappingInfo
                return EntrypointTarget(
                    kind = EntrypointKind.API,
                    displayLabel = "${EntrypointKind.API.badge} $httpVerb $uri",
                    filePath = filePath,
                    lineNumber1Based = line1Based,
                    className = className,
                    methodName = methodName,
                    hopDepth = hopDepth
                )
            }
        }

        // 2) Scheduler (@Scheduled)
        if (hasAnyAnnotation(method.annotations, SCHEDULER_ANNOTATIONS)) {
            return EntrypointTarget(
                kind = EntrypointKind.SCHEDULER,
                displayLabel = "${EntrypointKind.SCHEDULER.badge} $className.$methodName",
                filePath = filePath,
                lineNumber1Based = line1Based,
                className = className,
                methodName = methodName,
                hopDepth = hopDepth
            )
        }

        // 3) Event / Kafka / Rabbit Listener
        if (hasAnyAnnotation(method.annotations, LISTENER_ANNOTATIONS) ||
            hasAnyAnnotation(cls.annotations, LISTENER_ANNOTATIONS)
        ) {
            return EntrypointTarget(
                kind = EntrypointKind.LISTENER,
                displayLabel = "${EntrypointKind.LISTENER.badge} $className.$methodName",
                filePath = filePath,
                lineNumber1Based = line1Based,
                className = className,
                methodName = methodName,
                hopDepth = hopDepth
            )
        }

        // 4) Spring Batch (@JobScope, @StepScope, or Job/Step/Tasklet/ItemReader/ItemProcessor/ItemWriter)
        val isBatchScope = hasAnyAnnotation(method.annotations, BATCH_SCOPE_ANNOTATIONS) ||
            hasAnyAnnotation(cls.annotations, BATCH_SCOPE_ANNOTATIONS)
        val isBatchType = isBatchComponentClass(cls) || isBatchBeanReturnType(method)
        if (isBatchScope || isBatchType) {
            return EntrypointTarget(
                kind = EntrypointKind.BATCH,
                displayLabel = "${EntrypointKind.BATCH.badge} $className.$methodName",
                filePath = filePath,
                lineNumber1Based = line1Based,
                className = className,
                methodName = methodName,
                hopDepth = hopDepth
            )
        }

        return null
    }

    private fun extractHttpMapping(cls: PsiClass, method: PsiMethod): Pair<String, String>? {
        val classPrefix = findAnnotationBySimpleNames(cls.annotations, setOf("RequestMapping"))
            ?.let { extractFirstPathFromAnnotation(it) }
            .orEmpty()

        for (ann in method.annotations) {
            val simpleName = annotationSimpleName(ann) ?: continue
            val defaultVerb = HTTP_MAPPING_MAP[simpleName] ?: continue
            val verb = if (simpleName == "RequestMapping") {
                extractRequestMethodVerb(ann) ?: defaultVerb
            } else {
                defaultVerb
            }
            val methodPath = extractFirstPathFromAnnotation(ann).orEmpty()
            val fullUri = combineUriPaths(classPrefix, methodPath)
            return verb to fullUri
        }
        return null
    }

    private fun extractRequestMethodVerb(ann: PsiAnnotation): String? {
        val methodAttr = ann.findAttributeValue("method")?.text ?: return null
        return when {
            methodAttr.contains("GET") -> "GET"
            methodAttr.contains("POST") -> "POST"
            methodAttr.contains("PUT") -> "PUT"
            methodAttr.contains("DELETE") -> "DELETE"
            methodAttr.contains("PATCH") -> "PATCH"
            else -> null
        }
    }

    private fun extractFirstPathFromAnnotation(ann: PsiAnnotation): String? {
        val valueAttr = ann.findAttributeValue("value") ?: ann.findAttributeValue("path") ?: return null
        return extractStringLiteral(valueAttr)
    }

    private fun extractStringLiteral(value: PsiAnnotationMemberValue): String? {
        if (value is PsiLiteralExpression) {
            return value.value as? String
        }
        if (value is PsiArrayInitializerMemberValue) {
            for (init in value.initializers) {
                extractStringLiteral(init)?.let { return it }
            }
        }
        val text = value.text ?: return null
        return QUOTED_STRING_REGEX.find(text)?.groupValues?.getOrNull(1)
    }

    internal fun combineUriPaths(prefix: String, suffix: String): String {
        val p = prefix.trim().trimEnd('/')
        val s = suffix.trim().trimStart('/')
        val joined = when {
            p.isEmpty() && s.isEmpty() -> "/"
            p.isEmpty() -> "/$s"
            s.isEmpty() -> if (p.startsWith("/")) p else "/$p"
            else -> "${if (p.startsWith("/")) p else "/$p"}/$s"
        }
        return joined.replace(Regex("/+"), "/")
    }

    private fun isBatchComponentClass(cls: PsiClass): Boolean {
        for (superType in cls.superTypes) {
            val presentable = superType.presentableText.substringBefore('<').trim()
            if (presentable in BATCH_SUPER_NAMES) return true
        }
        for (superCls in cls.supers) {
            if (superCls.name in BATCH_SUPER_NAMES) return true
        }
        return false
    }

    private fun isBatchBeanReturnType(method: PsiMethod): Boolean {
        val isBean = hasAnyAnnotation(method.annotations, setOf("Bean"))
        if (!isBean) return false
        val retType = method.returnType?.presentableText?.substringBefore('<')?.trim() ?: return false
        return retType in BATCH_SUPER_NAMES
    }

    private fun hasAnyAnnotation(annotations: Array<PsiAnnotation>, names: Set<String>): Boolean {
        return findAnnotationBySimpleNames(annotations, names) != null
    }

    private fun findAnnotationBySimpleNames(annotations: Array<PsiAnnotation>, names: Set<String>): PsiAnnotation? {
        for (ann in annotations) {
            val simple = annotationSimpleName(ann) ?: continue
            if (simple in names) return ann
        }
        return null
    }

    private fun annotationSimpleName(ann: PsiAnnotation): String? {
        val qn = ann.qualifiedName
        if (!qn.isNullOrBlank()) return qn.substringAfterLast('.')
        return ann.nameReferenceElement?.referenceName
    }

    private fun canonicalMethodKey(method: PsiMethod): String {
        val clsName = method.containingClass?.qualifiedName ?: method.containingClass?.name ?: "anon"
        return "$clsName#${method.name}(${method.parameterList.parametersCount})"
    }

    private fun resolveLineNumber1Based(method: PsiMethod): Int {
        val file = method.containingFile ?: return 1
        val doc = PsiDocumentManager.getInstance(method.project).getDocument(file) ?: return 1
        val offset = (method.nameIdentifier?.textOffset ?: method.textOffset).coerceIn(0, doc.textLength)
        return doc.getLineNumber(offset) + 1
    }
}
