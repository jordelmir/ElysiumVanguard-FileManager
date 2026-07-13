package com.elysium.vanguard.core.ai

/** Pure validation and command templates for approval-gated Linux actions. */
object AgentToolArgumentPolicy {
    private val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9+._:@-]{0,119}")
    private const val MAX_PATCH_CHARS = 100_000

    fun installScript(manager: String, packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "Package name contains unsupported characters" }
        val quoted = shellQuote(packageName)
        return when (manager) {
            "apt" -> "DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y -- $quoted"
            "apk" -> "apk add --no-cache -- $quoted"
            "pacman" -> "pacman -Sy --noconfirm --needed -- $quoted"
            else -> throw IllegalArgumentException("Unsupported package manager")
        }
    }

    fun validateUnifiedPatch(patch: String): String {
        require(patch.isNotBlank() && patch.length <= MAX_PATCH_CHARS) { "Patch must be 1..$MAX_PATCH_CHARS characters" }
        require(!patch.contains('\u0000')) { "Patch cannot contain NUL bytes" }
        require(
            patch.startsWith("diff --git ") ||
                patch.startsWith("--- ") ||
                patch.startsWith("*** ")
        ) { "Patch must begin with a unified-diff header" }
        return patch
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
