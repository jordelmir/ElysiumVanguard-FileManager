package com.elysium.vanguard.core.linux

/**
 * Phase 73 third half (I-73.3.2) — the **Elysium Rootfs Layout**.
 *
 * Per the FHS (Filesystem Hierarchy Standard)
 * with Elysium-specific additions. The layout is
 * the **canonical filesystem shape** the
 * orchestrator expects on every Elysium Linux
 * install.
 *
 * The layout is **typed**: every path is a
 * [ElysiumRootfsPath] (a value class that
 * validates absolute paths + rejects
 * path-traversal attempts). The layout is
 * **read-only**: a fresh layout is constructed
 * with a custom root; the default layout is the
 * FHS root `/`.
 *
 * The layout exposes **factory methods** for the
 * paths that depend on runtime state:
 *   - `runtimeLayerPath(layerId, version)` — the
 *     install path for a runtime layer.
 *   - `packageInstallPath(packageName)` — the
 *     install path for a package.
 *   - `workspacePath(workspaceName)` — the
 *     user-visible path for a workspace.
 *
 * The FHS directories included in the layout
 * (and their Elysium-specific descendants):
 *
 *   /                          (root)
 *   ├── /bin -> /usr/bin       (essential binaries)
 *   ├── /etc                   (system config)
 *   │   └── /etc/elysium       (Elysium config)
 *   │       ├── elysium-linux.conf
 *   │       ├── runtime/       (runtime layer configs)
 *   │       └── package-sources.list
 *   ├── /lib -> /usr/lib       (essential libraries)
 *   ├── /opt                   (third-party software)
 *   │   └── /opt/elysium/packages   (Elysium packages)
 *   ├── /usr                   (user programs)
 *   │   ├── /usr/bin
 *   │   ├── /usr/lib
 *   │   │   └── /usr/lib/elysium
 *   │   │       └── /usr/lib/elysium/runtime   (runtime layers)
 *   │   │           ├── native/<ver>/
 *   │   │           ├── mesa-turnip/<ver>/
 *   │   │           ├── box64/<ver>/
 *   │   │           ├── fex/<ver>/
 *   │   │           └── wine/<ver>/
 *   │   └── /usr/share
 *   ├── /var                   (variable data)
 *   │   ├── /var/cache
 *   │   ├── /var/lib
 *   │   │   └── /var/lib/elysium
 *   │   │       ├── catalog/    (the layer catalog)
 *   │   │       ├── packages/   (the package database)
 *   │   │       └── state/      (runtime state)
 *   │   └── /var/log
 *   │       └── /var/log/elysium
 *   │           ├── pm.log
 *   │           ├── orchestrator.log
 *   │           └── audit.log
 *   └── /workspaces             (per-app reproducible workspaces)
 *       ├── blender-linux/
 *       ├── pycharm/
 *       └── ...
 */
data class ElysiumRootfsLayout(
    /** The rootfs root. Every other path is
     *  relative to this path. */
    val rootPath: ElysiumRootfsPath,

    /** `/bin` — essential binaries. */
    val binPath: ElysiumRootfsPath,

    /** `/etc` — system config. */
    val etcPath: ElysiumRootfsPath,

    /** `/etc/elysium` — Elysium-specific config. */
    val etcElysiumPath: ElysiumRootfsPath,

    /** `/etc/elysium/runtime` — runtime layer configs. */
    val etcElysiumRuntimePath: ElysiumRootfsPath,

    /** `/opt` — third-party software. */
    val optPath: ElysiumRootfsPath,

    /** `/opt/elysium/packages` — Elysium package install dir. */
    val optElysiumPackagesPath: ElysiumRootfsPath,

    /** `/usr` — user programs. */
    val usrPath: ElysiumRootfsPath,

    /** `/usr/bin` — user binaries. */
    val usrBinPath: ElysiumRootfsPath,

    /** `/usr/lib` — libraries. */
    val usrLibPath: ElysiumRootfsPath,

    /** `/usr/lib/elysium` — Elysium-specific libraries. */
    val usrLibElysiumPath: ElysiumRootfsPath,

    /** `/usr/lib/elysium/runtime` — runtime layer install dir. */
    val usrLibElysiumRuntimePath: ElysiumRootfsPath,

    /** `/var` — variable data. */
    val varPath: ElysiumRootfsPath,

    /** `/var/cache` — cached files. */
    val varCachePath: ElysiumRootfsPath,

    /** `/var/lib` — state information. */
    val varLibPath: ElysiumRootfsPath,

    /** `/var/lib/elysium` — Elysium-specific state. */
    val varLibElysiumPath: ElysiumRootfsPath,

    /** `/var/lib/elysium/catalog` — the layer catalog. */
    val varLibElysiumCatalogPath: ElysiumRootfsPath,

    /** `/var/lib/elysium/packages` — the package database. */
    val varLibElysiumPackagesPath: ElysiumRootfsPath,

    /** `/var/lib/elysium/state` — runtime state. */
    val varLibElysiumStatePath: ElysiumRootfsPath,

    /** `/var/log` — log files. */
    val varLogPath: ElysiumRootfsPath,

    /** `/var/log/elysium` — Elysium-specific logs. */
    val varLogElysiumPath: ElysiumRootfsPath,

    /** `/var/log/elysium/pm` — package manager logs. */
    val varLogElysiumPackageManagerPath: ElysiumRootfsPath,

    /** `/var/log/elysium/orchestrator` — orchestrator logs. */
    val varLogElysiumOrchestratorPath: ElysiumRootfsPath,

    /** `/var/log/elysium/audit` — security audit log. */
    val varLogElysiumAuditPath: ElysiumRootfsPath,

    /** `/workspaces` — per-app reproducible workspaces. */
    val workspacesPath: ElysiumRootfsPath,
) {
    init {
        // Every path is under the root.
        for (path in listOf(
            binPath, etcPath, etcElysiumPath, etcElysiumRuntimePath,
            optPath, optElysiumPackagesPath, usrPath, usrBinPath, usrLibPath,
            usrLibElysiumPath, usrLibElysiumRuntimePath, varPath, varCachePath,
            varLibPath, varLibElysiumPath, varLibElysiumCatalogPath,
            varLibElysiumPackagesPath, varLibElysiumStatePath, varLogPath,
            varLogElysiumPath, varLogElysiumPackageManagerPath,
            varLogElysiumOrchestratorPath, varLogElysiumAuditPath,
            workspacesPath,
        )) {
            require(path.value == "/" || path.value.startsWith(rootPath.value)) {
                "every Elysium rootfs path must be under the root " +
                    "(${rootPath.value}), got: ${path.value}"
            }
        }
    }

    /**
     * The runtime layer install path for a
     * specific layer id + version. The path is
     * the canonical install location for the
     * layer's files.
     *
     * Example: `runtimeLayerPath(BOX64, "0.3.2")`
     * returns `/usr/lib/elysium/runtime/box64/0.3.2`.
     */
    fun runtimeLayerPath(
        layerId: ElysiumRuntimeLayerId,
        version: ElysiumPackageVersion,
    ): ElysiumRootfsPath =
        usrLibElysiumRuntimePath.join("${layerId.value}/${version.canonical}")

    /**
     * The package install path for a specific
     * package. The path is the canonical install
     * location for the package's files.
     *
     * Example: `packageInstallPath("com.elysium.runtime.python")`
     * returns `/opt/elysium/packages/com.elysium.runtime.python`.
     */
    fun packageInstallPath(packageName: String): ElysiumRootfsPath =
        optElysiumPackagesPath.join(packageName)

    /**
     * The workspace path for a specific
     * workspace name. The path is the
     * user-visible workspace directory.
     *
     * Example: `workspacePath("blender-linux")`
     * returns `/workspaces/blender-linux`.
     */
    fun workspacePath(workspaceName: String): ElysiumRootfsPath =
        workspacesPath.join(workspaceName)

    /**
     * The default layout — the FHS root `/` with
     * the standard Elysium-specific descendants.
     */
    companion object {
        /**
         * The default rootfs layout. The default
         * uses the canonical FHS root (`/`); a
         * custom layout (e.g. for a chroot test)
         * can be constructed with a different
         * `rootPath`.
         */
        val DEFAULT: ElysiumRootfsLayout = ElysiumRootfsLayout(
            rootPath = ElysiumRootfsPath("/"),
            binPath = ElysiumRootfsPath("/bin"),
            etcPath = ElysiumRootfsPath("/etc"),
            etcElysiumPath = ElysiumRootfsPath("/etc/elysium"),
            etcElysiumRuntimePath = ElysiumRootfsPath("/etc/elysium/runtime"),
            optPath = ElysiumRootfsPath("/opt"),
            optElysiumPackagesPath = ElysiumRootfsPath("/opt/elysium/packages"),
            usrPath = ElysiumRootfsPath("/usr"),
            usrBinPath = ElysiumRootfsPath("/usr/bin"),
            usrLibPath = ElysiumRootfsPath("/usr/lib"),
            usrLibElysiumPath = ElysiumRootfsPath("/usr/lib/elysium"),
            usrLibElysiumRuntimePath = ElysiumRootfsPath("/usr/lib/elysium/runtime"),
            varPath = ElysiumRootfsPath("/var"),
            varCachePath = ElysiumRootfsPath("/var/cache"),
            varLibPath = ElysiumRootfsPath("/var/lib"),
            varLibElysiumPath = ElysiumRootfsPath("/var/lib/elysium"),
            varLibElysiumCatalogPath = ElysiumRootfsPath("/var/lib/elysium/catalog"),
            varLibElysiumPackagesPath = ElysiumRootfsPath("/var/lib/elysium/packages"),
            varLibElysiumStatePath = ElysiumRootfsPath("/var/lib/elysium/state"),
            varLogPath = ElysiumRootfsPath("/var/log"),
            varLogElysiumPath = ElysiumRootfsPath("/var/log/elysium"),
            varLogElysiumPackageManagerPath = ElysiumRootfsPath("/var/log/elysium/pm"),
            varLogElysiumOrchestratorPath = ElysiumRootfsPath("/var/log/elysium/orchestrator"),
            varLogElysiumAuditPath = ElysiumRootfsPath("/var/log/elysium/audit"),
            workspacesPath = ElysiumRootfsPath("/workspaces"),
        )
    }
}

/**
 * A typed absolute path inside the Elysium
 * rootfs. The path is a value class that
 * validates:
 *   - The path is non-blank.
 *   - The path is absolute (starts with `/`).
 *   - The path does NOT contain `..` (path
 *     traversal is rejected).
 *   - The path does NOT contain `//` (double
 *     slashes are a smell).
 *
 * The path is **immutable** (a data class; no
 * setters). A new path is a new value. The path's
 * lifecycle (a file create, a directory move) is
 * a new `ElysiumRootfsPath` value.
 *
 * The path is **total** for the standard FHS
 * paths (the FHS root is the only acceptable
 * "root" path; every other path is under the
 * FHS root).
 */
data class ElysiumRootfsPath(val value: String) {
    init {
        require(value.isNotBlank()) {
            "ElysiumRootfsPath.value must not be blank"
        }
        require(value.startsWith("/")) {
            "ElysiumRootfsPath.value must be absolute, got: $value"
        }
        require(!value.contains("..")) {
            "ElysiumRootfsPath.value must not contain '..' " +
                "(path traversal rejected), got: $value"
        }
        require(!value.contains("//")) {
            "ElysiumRootfsPath.value must not contain '//', got: $value"
        }
    }

    /**
     * Join a relative path to this absolute path.
     * The relative path MUST NOT be absolute; the
     * caller is responsible for using relative
     * segments only.
     */
    fun join(relative: String): ElysiumRootfsPath {
        require(relative.isNotBlank()) {
            "relative path must not be blank"
        }
        require(!relative.startsWith("/")) {
            "relative path must not be absolute, got: $relative"
        }
        require(!relative.contains("..")) {
            "relative path must not contain '..', got: $relative"
        }
        val separator = if (value.endsWith("/")) "" else "/"
        return ElysiumRootfsPath("$value$separator${relative.trimStart('/')}")
    }

    /**
     * The parent path. The parent of `/` is `/`
     * (the FHS root has no parent). The parent of
     * `/usr` is `/`. The parent of `/usr/bin` is
     * `/usr`.
     */
    val parent: ElysiumRootfsPath
        get() {
            if (value == "/") return this
            val idx = value.lastIndexOf('/')
            return if (idx == 0) ElysiumRootfsPath("/")
            else ElysiumRootfsPath(value.substring(0, idx))
        }

    /**
     * The path segments. The segments are the
     * path components between `/` separators.
     * The root path `/` has no segments.
     */
    val segments: List<String>
        get() = if (value == "/") emptyList()
        else value.trimStart('/').split("/")

    /**
     * The path relative to a base. The base MUST
     * be an ancestor of this path; otherwise the
     * function throws.
     */
    fun relativeTo(base: ElysiumRootfsPath): String {
        require(value == base.value || value.startsWith("${base.value}/")) {
            "this path ($value) is not under the base (${base.value})"
        }
        if (value == base.value) return "."
        return value.removePrefix("${base.value}/")
    }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = value
}
