package com.maekom.codetracker.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

sealed class UpdateCheckResult(val currentVersion: String) {
    class UpdateAvailable(
        currentVersion: String,
        val latestVersion: String,
        val releasePageUrl: String,
        val directDownloadUrl: String
    ) : UpdateCheckResult(currentVersion)

    class UpToDate(
        currentVersion: String,
        val latestVersion: String
    ) : UpdateCheckResult(currentVersion)

    class NoReleaseYet(
        currentVersion: String,
        val message: String = "아직 GitHub에 등록된 공식 릴리즈가 없습니다."
    ) : UpdateCheckResult(currentVersion)

    class Error(
        currentVersion: String,
        val message: String
    ) : UpdateCheckResult(currentVersion)
}

/**
 * Manual on-demand GitHub Release update checker.
 * Invoked ONLY when the user explicitly clicks the [업데이트 확인] button.
 * Uses JDK built-in java.net.http.HttpClient with zero external HTTP libraries and zero telemetry.
 */
object PluginUpdateChecker {

    private const val PLUGIN_ID = "com.maekom.codetracker"
    private const val FALLBACK_VERSION = "0.1.1"
    private const val REPO_SLUG = "maekom-hub/maekom-code-tracker"
    private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$REPO_SLUG/releases/latest"
    private const val USER_AGENT = "Maekom-Code-Tracker"
    private const val TIMEOUT_SECONDS = 5L

    private val TAG_NAME_REGEX = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val HTML_URL_REGEX = Regex(""""html_url"\s*:\s*"([^"]+)"""")

    fun getCurrentVersion(): String {
        return runCatching {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK_VERSION
    }

    fun buildDirectDownloadUrl(version: String): String {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        return "https://github.com/$REPO_SLUG/releases/download/v$clean/maekom-code-tracker-$clean.zip"
    }

    fun checkLatestRelease(): UpdateCheckResult {
        val currentVersion = getCurrentVersion()
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_API_URL))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parseSuccessResponse(currentVersion, response.body())
                404 -> UpdateCheckResult.NoReleaseYet(
                    currentVersion = currentVersion,
                    message = "아직 GitHub에 등록된 공식 릴리즈가 없습니다."
                )
                403, 429 -> UpdateCheckResult.Error(
                    currentVersion = currentVersion,
                    message = "GitHub API 요청 한도 초과 또는 접근이 제한되었습니다 (HTTP ${response.statusCode()}). 잠시 후 다시 시도해 주세요."
                )
                else -> UpdateCheckResult.Error(
                    currentVersion = currentVersion,
                    message = "최신 버전 정보를 조회하지 못했습니다 (HTTP ${response.statusCode()})."
                )
            }
        } catch (_: HttpTimeoutException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "업데이트 확인 요청이 시간 초과(${TIMEOUT_SECONDS}초)되었습니다. 네트워크 연결을 확인해 주세요."
            )
        } catch (_: UnknownHostException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "오프라인 상태이거나 GitHub 서버에 연결할 수 없습니다. (로컬 오프라인 분석 기능은 정상 사용 가능합니다.)"
            )
        } catch (_: ConnectException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "GitHub 서버에 연결할 수 없습니다. 사내 방화벽 또는 오프라인 환경을 확인해 주세요."
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "업데이트 확인 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    internal fun parseSuccessResponse(currentVersion: String, body: String): UpdateCheckResult {
        val rawTag = TAG_NAME_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
        if (rawTag.isNullOrBlank()) {
            return UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "GitHub 릴리즈 응답에서 버전 정보(tag_name)를 확인할 수 없습니다."
            )
        }

        val latestVersion = rawTag.removePrefix("v").removePrefix("V")
        val releasePageUrl = HTML_URL_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.startsWith("https://") }
            ?: "https://github.com/$REPO_SLUG/releases/tag/v$latestVersion"

        return if (compareSemanticVersions(currentVersion, latestVersion) < 0) {
            UpdateCheckResult.UpdateAvailable(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releasePageUrl = releasePageUrl,
                directDownloadUrl = buildDirectDownloadUrl(latestVersion)
            )
        } else {
            UpdateCheckResult.UpToDate(
                currentVersion = currentVersion,
                latestVersion = latestVersion
            )
        }
    }

    /**
     * Compares two dot-delimited semantic versions using integer arrays (e.g. "0.1.1" vs "0.1.10").
     * Returns negative if [current] < [latest], 0 if equal, positive if [current] > [latest].
     */
    internal fun compareSemanticVersions(current: String, latest: String): Int {
        val currParts = parseVersionParts(current)
        val latestParts = parseVersionParts(latest)
        val maxLen = maxOf(currParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (c != l) return c.compareTo(l)
        }
        return 0
    }

    private fun parseVersionParts(version: String): IntArray {
        val core = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (core.isBlank()) return intArrayOf(0)
        return core.split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            .toIntArray()
    }
}
