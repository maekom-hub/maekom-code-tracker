package com.maekom.codetracker.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.maekom.codetracker.i18n.CodeTrackerI18n
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
        val message: String = CodeTrackerI18n.updateRepoRegisteredMessage()
    ) : UpdateCheckResult(currentVersion)

    class Error(
        currentVersion: String,
        val message: String
    ) : UpdateCheckResult(currentVersion)
}

/**
 * Manual on-demand Custom Plugin Repository (updatePlugins.xml) update checker.
 * Invoked ONLY when the user explicitly clicks the update check button in the ToolWindow title bar.
 * Registers the Custom Plugin Repository host in IntelliJ UpdateSettings and fetches updatePlugins.xml
 * using JDK built-in java.net.http.HttpClient with zero external HTTP libraries and zero telemetry.
 */
object PluginUpdateChecker {

    private const val PLUGIN_ID = "com.maekom.codetracker"
    private const val FALLBACK_VERSION = "0.1.8"
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
                    message = CodeTrackerI18n.updateRepoRegisteredMessage()
                )
                else -> UpdateCheckResult.Error(
                    currentVersion = currentVersion,
                    message = CodeTrackerI18n.updateHttpErrorMessage(response.statusCode())
                )
            }
        } catch (_: HttpTimeoutException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = CodeTrackerI18n.updateTimeoutMessage(TIMEOUT_SECONDS)
            )
        } catch (_: UnknownHostException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = CodeTrackerI18n.updateOfflineMessage()
            )
        } catch (_: ConnectException) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = CodeTrackerI18n.updateConnectFailedMessage()
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = CodeTrackerI18n.updateUnexpectedErrorMessage(e.message ?: e.javaClass.simpleName)
            )
        }
    }

    internal fun parseUpdatePluginsXml(currentVersion: String, xmlBody: String): UpdateCheckResult {
        val rawVersion = PLUGIN_VERSION_REGEX.find(xmlBody)?.groupValues?.getOrNull(1)?.trim()
        if (rawVersion.isNullOrBlank()) {
            return UpdateCheckResult.Error(
                currentVersion = currentVersion,
                message = CodeTrackerI18n.updateXmlParseErrorMessage()
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
