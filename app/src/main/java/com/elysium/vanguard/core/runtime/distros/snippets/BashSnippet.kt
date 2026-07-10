package com.elysium.vanguard.core.runtime.distros.snippets

import java.util.Locale

/**
 * PHASE 9.6.8 — A reusable shell snippet bundled with the app.
 *
 * Snippets are small shell fragments users can paste into the
 * terminal to do common operations faster. They live in
 * `<filesDir>/distros/snippets/` and the UI exposes them under the
 * "Snippets" tab of the terminal screen (Phase 9.6.8.1).
 *
 * Phase 9.6.8 — first build; intentionally minimal.
 */
data class BashSnippet(
    val id: String,
    /** Display name (what the user sees in the snippets list) */
    val title: String,
    /** Category for grouping; drives the filters in the UI */
    val category: Category,
    /** What's inserted into the terminal when the user picks this snippet */
    val body: String,
    /** Optional human-readable description shown above the body */
    val description: String? = null
) {
    enum class Category(val displayName: String) {
        FILESYSTEM("filesystem"),
        NETWORK("network"),
        PACKAGE("package"),
        SHELL("shell"),
        GIT("git"),
        DOCKER("docker")
    }
}

/**
 * PHASE 9.6.8 — Library of bundled snippets that ship with the app.
 *
 * Today the library is hard-coded; Phase 9.6.8.1 will let users
 * save custom snippets to disk and load them by category.
 */
object BundledSnippetLibrary {

    val ALL: List<BashSnippet> by lazy {
        listOf(
            BashSnippet(
                id = "find-large-files",
                title = "Find files larger than 100 MB",
                category = BashSnippet.Category.FILESYSTEM,
                body = "find / -type f -size +100M 2>/dev/null | head -n 20",
                description = "Show top 20 files over 100 MB under /"
            ),
            BashSnippet(
                id = "du-summary",
                title = "Disk usage by directory under /",
                category = BashSnippet.Category.FILESYSTEM,
                body = "du -h --max-depth=2 / 2>/dev/null | sort -hr | head -n 20"
            ),
            BashSnippet(
                id = "apt-update-and-upgrade",
                title = "apt update + upgrade (Debian/Ubuntu)",
                category = BashSnippet.Category.PACKAGE,
                body = "apt update && apt upgrade -y",
                description = "Refresh the package index and apply upgrades"
            ),
            BashSnippet(
                id = "apk-update-and-upgrade",
                title = "apk update + upgrade (Alpine)",
                category = BashSnippet.Category.PACKAGE,
                body = "apk update && apk upgrade --available"
            ),
            BashSnippet(
                id = "pacman-update-and-upgrade",
                title = "pacman -Syu (Arch)",
                category = BashSnippet.Category.PACKAGE,
                body = "pacman -Syu"
            ),
            BashSnippet(
                id = "top-processes",
                title = "Top 10 processes by memory",
                category = BashSnippet.Category.SHELL,
                body = "ps auxf --sort=-rss | head -n 11"
            ),
            BashSnippet(
                id = "git-status-and-log",
                title = "git status + recent commits",
                category = BashSnippet.Category.GIT,
                body = "git status && git log --oneline --decorate -n 10"
            ),
            BashSnippet(
                id = "ip-quick",
                title = "Quick IP / route summary",
                category = BashSnippet.Category.NETWORK,
                body = "ip -4 addr show && echo --- && ip route show default"
            ),
            BashSnippet(
                id = "shell-prompt-color",
                title = "Set a colored shell prompt (PS1)",
                category = BashSnippet.Category.SHELL,
                body = "export PS1='\\u@\\h:\\w\\$ '",
                description = "Apply to current shell only; permanent via .bashrc"
            ),
            BashSnippet(
                id = "docker-cleanup",
                title = "Docker dangling image / container cleanup",
                category = BashSnippet.Category.DOCKER,
                body = "docker system prune -f"
            )
        )
    }

    /**
     * Group snippets by category for the UI; categories with no
     * snippets are omitted from the result.
     */
    fun grouped(): Map<BashSnippet.Category, List<BashSnippet>> {
        return ALL.groupBy { it.category }
    }

    fun find(id: String): BashSnippet? = ALL.firstOrNull { it.id == id }

    fun filterByCategoryLowercase(name: String): List<BashSnippet> {
        val lower = name.lowercase(Locale.US)
        return ALL.filter { it.category.name.lowercase(Locale.US).contains(lower) }
    }
}

/**
 * PHASE 9.6.8 — Minimal tmate/tmux launcher descriptor.
 *
 * tmate is the "instant terminal sharing" tool: spawn `tmate` and
 * you get an SSH read/write URL you can share with someone. We don't
 * actually exec tmate here (no proot, no bin); the descriptor is the
 * spec for future 9.6.8.1 when we add the wire-up.
 *
 * Phase 9.6.8 — first build; intentionally minimal.
 */
data class TmateLaunchSpec(
    val sessionId: String,
    val sshUrl: String,
    val roUrl: String,
    /** When the session expires, in epoch millis. */
    val expiresAtMs: Long
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs
}
