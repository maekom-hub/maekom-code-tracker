package com.maekom.codetracker.git

import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class GitCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false
)

/**
 * Strictly read-only local Git command runner.
 * Only allows whitelisted read-only subcommands and enforces UTF-8 / quotepath=false.
 */
object GitCommandRunner {

    private val ALLOWED_SUBCOMMANDS = setOf("log", "show", "branch", "rev-parse", "cat-file")
    private const val DEFAULT_TIMEOUT_SECONDS = 30L
    private const val DEFAULT_MAX_OUTPUT_BYTES = 16 * 1024 * 1024 // 16MB global ceiling

    fun resolveGitExecutable(project: Project?): String {
        if (project != null) {
            val fromIde = runCatching {
                GitExecutableManager.getInstance().getPathToGit(project)
            }.getOrNull()
            if (!fromIde.isNullOrBlank()) {
                return fromIde
            }
        }
        return "git"
    }

    fun runReadOnly(
        project: Project?,
        repoRootPath: String,
        subcommand: String,
        args: List<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): GitCommandOutput {
        if (subcommand !in ALLOWED_SUBCOMMANDS) {
            throw SecurityException("Read-only policy violation: git subcommand '$subcommand' is not allowed.")
        }

        val gitExe = resolveGitExecutable(project)
        val command = mutableListOf(
            gitExe,
            "-c", "i18n.logOutputEncoding=UTF-8",
            "-c", "core.quotepath=false",
            "--no-pager",
            subcommand
        )
        command.addAll(args)

        val workDir = File(repoRootPath)
        if (!workDir.isDirectory) {
            return GitCommandOutput(1, "", "Repository directory not found: $repoRootPath")
        }

        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()

        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        var truncated = false

        val stdoutThread = Thread {
            process.inputStream.use { input ->
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    val remaining = maxOutputBytes - total
                    if (remaining > 0) {
                        val toWrite = minOf(read, remaining)
                        stdoutBuffer.write(chunk, 0, toWrite)
                        total += toWrite
                        if (toWrite < read) {
                            truncated = true
                        }
                    } else {
                        truncated = true
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            process.errorStream.use { input ->
                val chunk = ByteArray(4096)
                var total = 0
                val stderrLimit = 256 * 1024
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    val remaining = stderrLimit - total
                    if (remaining > 0) {
                        val toWrite = minOf(read, remaining)
                        stderrBuffer.write(chunk, 0, toWrite)
                        total += toWrite
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return GitCommandOutput(
                exitCode = -1,
                stdout = stdoutBuffer.toString(StandardCharsets.UTF_8),
                stderr = "Git command timed out after ${timeoutSeconds}s.",
                truncated = truncated
            )
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        return GitCommandOutput(
            exitCode = process.exitValue(),
            stdout = stdoutBuffer.toString(StandardCharsets.UTF_8),
            stderr = stderrBuffer.toString(StandardCharsets.UTF_8),
            truncated = truncated
        )
    }
}
