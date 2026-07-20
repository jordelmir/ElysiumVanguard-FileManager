package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import java.io.File

/**
 * Phase 93 — the **git clone handler** for `.git`
 * descriptor files.
 *
 * The handler is a thin shell over the existing
 * [ProcessLauncher]: it (1) reads the URL from
 * the first non-blank line of the file, (2)
 * verifies the URL is a valid Git URL, (3)
 * launches `git clone` in the destination
 * directory.
 *
 * The handler is **async** (suspend function).
 * The caller (the [com.elysium.vanguard.features.fileactions.FileActionViewModel])
 * invokes it from a coroutine scope.
 *
 * **Why a `.git` descriptor file?** The vision
 * (Section 1) says "Repositorios Git → clonar,
 * compilar, ejecutar o desplegar". The
 * File Manager's contextual action needs a
 * trigger file. A `.git` file containing the
 * URL is the simplest portable format: copy
 * the file to any directory, long-press →
 * "Clone repo", the file's content becomes
 * the repo URL.
 *
 * **JVM testability**: the handler takes a
 * [GitCloneRunner] interface in its
 * constructor; production uses the real
 * runner; tests use a fake.
 */
class GitCloneHandler(
    private val runner: GitCloneRunner,
) {

    /**
     * Read the URL from the file body (first
     * non-blank, non-comment line) and launch
     * the clone. Comment lines (starting with
     * `#`) are skipped, so a `.git` descriptor
     * can have a header comment followed by the
     * URL on the next line.
     */
    suspend fun clone(action: FileAction.GitClone): GitCloneResult {
        val descriptor = File(action.repoUrl)
        val url = try {
            descriptor.readLines()
                .firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                ?.trim()
        } catch (e: Exception) {
            null
        }
        if (url.isNullOrBlank()) {
            return GitCloneResult.InvalidDescriptor(
                message = "could not read URL from ${descriptor.absolutePath}"
            )
        }
        if (!isValidGitUrl(url)) {
            return GitCloneResult.InvalidDescriptor(
                message = "not a valid Git URL: $url"
            )
        }
        val destinationDir = action.destinationDir
        val destination = File(destinationDir)
        if (!destination.exists()) {
            destination.mkdirs()
        }
        if (!destination.isDirectory) {
            return GitCloneResult.Failure(
                message = "destination is not a directory: $destinationDir"
            )
        }
        return runner.clone(url, destination)
    }

    /**
     * A URL is a valid Git URL if it starts with
     * `https://`, `http://`, `git://`, `ssh://`,
     * or `git@`. The check is deliberately
     * permissive: any URL that the `git` binary
     * would accept is allowed.
     */
    private fun isValidGitUrl(url: String): Boolean {
        return url.startsWith("https://") ||
            url.startsWith("http://") ||
            url.startsWith("git://") ||
            url.startsWith("ssh://") ||
            url.startsWith("git@")
    }
}

/**
 * The runner the handler delegates to.
 * Production wraps the [ProcessLauncher]
 * + `git` binary. Tests pass a fake.
 */
interface GitCloneRunner {
    suspend fun clone(url: String, destination: File): GitCloneResult
}

sealed class GitCloneResult {
    data class Success(
        val url: String,
        val destination: String,
        val exitCode: Int,
    ) : GitCloneResult()

    data class Failure(
        val message: String,
    ) : GitCloneResult()

    data class InvalidDescriptor(
        val message: String,
    ) : GitCloneResult()
}
