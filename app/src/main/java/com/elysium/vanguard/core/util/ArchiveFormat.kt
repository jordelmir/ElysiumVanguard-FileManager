package com.elysium.vanguard.core.util

/**
 * PHASE 10.3 — every archive format the app can read or write.
 *
 * Aligned with what ZArchiver supports minus RAR (RAR5 needs a native
 * lib that's not worth shipping for the marginal user who actually
 * cares — we surface a clean error if a .rar is opened).
 *
 * `extensions` are lowercased and may include a leading dot. The first
 * extension is the canonical one used when the user picks a format in
 * the compress sheet.
 */
enum class ArchiveFormat(
    val displayName: String,
    val canCreate: Boolean,
    val canExtract: Boolean,
    val supportsPassword: Boolean,
    val multiFile: Boolean,
    val extensions: List<String>
) {
    ZIP(
        displayName = "ZIP",
        canCreate = true,
        canExtract = true,
        // ZipCrypto only — strong enough for the casual case, weak
        // against a determined attacker. We document the limitation
        // in the compress sheet so the user isn't surprised.
        supportsPassword = true,
        multiFile = true,
        extensions = listOf("zip")
    ),
    SEVEN_Z(
        displayName = "7Z",
        canCreate = true,
        canExtract = true,
        // AES-256. The real deal — same crypto 7-Zip uses natively.
        supportsPassword = true,
        multiFile = true,
        extensions = listOf("7z")
    ),
    TAR(
        displayName = "TAR",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = true,
        extensions = listOf("tar")
    ),
    TAR_GZ(
        displayName = "TAR.GZ",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = true,
        extensions = listOf("tar.gz", "tgz")
    ),
    TAR_BZ2(
        displayName = "TAR.BZ2",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = true,
        extensions = listOf("tar.bz2", "tbz2")
    ),
    TAR_XZ(
        displayName = "TAR.XZ",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = true,
        extensions = listOf("tar.xz", "txz")
    ),
    TAR_ZST(
        displayName = "TAR.ZST",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = true,
        extensions = listOf("tar.zst", "tzst")
    ),
    GZIP(
        displayName = "GZIP",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        // GZIP is a single-stream compressor, not an archiver. We
        // create a `.gz` next to the source file and the user can
        // rename the inner to remove the gzip wrapper.
        multiFile = false,
        extensions = listOf("gz")
    ),
    BZIP2(
        displayName = "BZIP2",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = false,
        extensions = listOf("bz2")
    ),
    XZ(
        displayName = "XZ",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = false,
        extensions = listOf("xz")
    ),
    ZSTANDARD(
        displayName = "ZST",
        canCreate = true,
        canExtract = true,
        supportsPassword = false,
        multiFile = false,
        extensions = listOf("zst", "zstd")
    );

    /** The canonical extension (with leading dot) for "save as" dialogs. */
    val canonicalExtension: String get() = extensions.first()

    companion object {
        /** All formats, in the order they appear in the format picker. */
        val all: List<ArchiveFormat> = entries.toList()

        /** Formats the user can pick when creating a new archive. */
        val creatable: List<ArchiveFormat> = all.filter { it.canCreate }

        /** Formats the app can extract. */
        val extractable: List<ArchiveFormat> = all.filter { it.canExtract }

        /**
         * Auto-detect the archive format from a file path. Checks the
         * filename against the registered extension list, preferring
         * LONGER matches first (so `.tar.gz` wins over `.gz`). The
         * magic-byte probe lives in `CompressionEngine.detectByMagic`
         * for cases where the user renamed the file or the extension
         * is wrong.
         */
        fun fromPath(path: String): ArchiveFormat? {
            val name = path.lowercase()
            val allExt = entries.flatMap { fmt ->
                fmt.extensions.map { ext -> fmt to ext }
            }.sortedByDescending { it.second.length }
            for ((fmt, ext) in allExt) {
                if (name.endsWith(".$ext")) return fmt
            }
            return null
        }
    }
}
