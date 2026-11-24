package com.maekom.codetracker.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.UpdateSettings
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
        val latestVersion: String
    ) : UpdateCheckResult(currentVersion)

    class UpToDate(
        currentVersion: String,
        val latestVersion: String
    ) : UpdateCheckResult(currentVersion)

    class RepoNotPublishedYet(
        currentVersion: String,
        val message: String = "Custom Plugin Repository가 등록되었습니다. 릴리즈 배포 후 Plugins 창에서 바로 업데이트할 수 있습니다."
    ) : UpdateCheckResult(currentVersion)

    class Error(
        currentVersion: String,
        val message: String
    ) : UpdateCheckResult(currentVersion)
}

/**
 * Manual on-demand Custom Plugin Repository (updatePlugins.xml) update checker.
 * Invoked ONLY when the user explicitly clicks the [업데이트 확인] button in the ToolWindow title bar.
 * Registers the Custom Plugin Repository host in IntelliJ UpdateSettings and fetches updatePlugins.xml
 * using JDK built-in java.net.http.HttpClient with zero external HTTP libraries and zero telemetry.
 */
object PluginUpdateChecker {

    private const val PLUGIN_ID = "com.maekom.codetracker"
    private const val FALLBACK_VERSION = "0.1.6"
    const val CUSTOM_REPO_URL =
        "https://github.com/maekom-hub/maekom-code-tracker/releases/latest/download/updatePlugins.xml"
    private const val USER_AGENT = "Maekom-Code-Tracker"
    private const val TIMEOUT_SECONDS = 5L

    // Matches version="..." specifically inside <plugin ...> element (never <?xml version="1.0"?>)
    private val PLUGIN_VERSION_REGEX = Regex("""<plugin\b[^>]*\bversion\s*=\s*"([^"]+)"""")

    fun getCurrentVersion(): String {
        return runCatching {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK_VERSION
    }

    /**
     * Ensures the fixed Custom Plugin Repository URL is registered in IntelliJ's storedPluginHosts
     * so IntelliJ's built-in Plugins UI can detect and apply one-click updates.
     */
    fun ensureCustomPluginRepoRegistered(): Boolean {
        return runCatching {
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (!hosts.contains(CUSTOM_REPO_URL)) {
                hosts.add(CUSTOM_REPO_URL)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun checkLatestRelease(): UpdateCheckResult {
        val currentVersion = getCurrentVersion()
        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(CUSTOM_REPO_URL))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parseUpdatePluginsXml(currentVersion, response.body())
                404 -> UpdateCheckResult.RepoNotPublishedYet(
                    currentVersion = currentVersion,
                    message = "Custom Plugin Repository가 등록되었습니다. 릴리즈 배포 후 Plugins 창에서 바로 업데이트할 수 있습니다."
                )
                else -> UpdateCheckResult.Error(
                    currentVersion = currentVersion,
                    message = "최신 업데이트 메타데이터(updatePlugins.xml)를 조회하지 못했습니다 (HTTP ${response.statusCode()})."
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

    internal fun parseUpdatePluginsXml(currentVersion: String, xmlBody: String): UpdateCheckResult {
        val rawVersion = PLUGIN_VERSION_REGEX.find(xmlBody)?.groupValues?.getOrNull(1)?.trim()
        if (rawVersion.isNullOrBlank()) {
            return UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = "updatePlugins.xml 응답에서 플러그인 버전(version) 정보를 찾을 수 없습니다."
            )
        }

        val latestVersion = rawVersion.removePrefix("v").removePrefix("V")
        return if (compareSemanticVersions(currentVersion, latestVersion) < 0) {
            UpdateCheckResult.UpdateAvailable(
                currentVersion = currentVersion,
                latestVersion = latestVersion
            )
        } else {
            UpdateCheckResult.UpToDate(
                currentVersion = currentVersion,
                latestVersion = latestVersion
            )
        }
    }

    /**
     * Compares two dot-delimited semantic versions using integer arrays (e.g. "0.1.3" vs "0.1.10").
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
