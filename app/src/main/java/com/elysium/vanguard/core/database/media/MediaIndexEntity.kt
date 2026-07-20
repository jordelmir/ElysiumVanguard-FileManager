package com.elysium.vanguard.core.database.media

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 93 — the **Media Index Entity**, the
 * persistent row in the Elysium media index
 * database.
 *
 * Per the master vision's "MEDIA VAULT" +
 * "AUDIO HUB" portal items: the user wants
 * the platform to **scan sounds and images**,
 * save them locally, and **only add NEW
 * items** on future scans (incremental
 * indexing). The persistent index is the
 * single source of truth for "what is on the
 * device"; future scans compare against the
 * index and add only the diff.
 *
 * The index entry has:
 *   - **`mediaId`** — the **stable, typed id**
 *     (the `MediaStore.MediaColumns._ID`).
 *     This is the primary key; the same media
 *     file always has the same id on the
 *     device (MediaStore assigns ids on
 *     insert; an id is stable until the file
 *     is deleted).
 *   - **`uri`** — the canonical `content://`
 *     URI (the consumer's stable handle).
 *   - **`mediaType`** — the typed kind
 *     (IMAGE / VIDEO / AUDIO). The index
 *     spans all media types so the
 *     `getByType` query is the canonical
 *     "show me only images" / "show me only
 *     audio" primitive.
 *   - **`displayName`** — the file's
 *     display name (e.g. `IMG_20240101.jpg`).
 *   - **`relativePath`** — the device-side
 *     relative path (e.g.
 *     `Pictures/Vacation/`). Used to group
 *     items by folder (the "albums" view in
 *     the MEDIA VAULT card).
 *   - **`sizeBytes`** — the file size in
 *     bytes. Used to detect size changes
 *     (a file may be replaced in place).
 *   - **`dateModifiedMs`** — the file's
 *     last-modified timestamp. Used to
 *     detect content changes.
 *   - **`contentHash`** — the SHA-256 of the
 *     file's first 4 KiB + the file size (a
 *     fast fingerprint; not a cryptographic
 *     hash). Used to detect content changes
 *     that don't change the mtime (e.g. a
 *     file replaced with the same mtime but
 *     different content). Null when the file
 *     was discovered but not yet hashed.
 *   - **`discoveredAtMs`** — the timestamp
 *     the indexer first saw the file. The
 *     field is the canonical "first scan"
 *     timestamp.
 *   - **`lastSeenAtMs`** — the timestamp
 *     the indexer most recently saw the
 *     file. The field is the canonical
 *     "last scan" timestamp (used to detect
 *     deletions: a file with
 *     `lastSeenAtMs < currentScanMs - X`
 *     is considered gone).
 *   - **`isFavorite`** — the user's
 *     favorite flag (the user can mark
 *     files as favorites from the UI; the
 *     favorite flag persists across
 *     rescans).
 *
 * The entity is **immutable from the
 * domain's perspective** (the indexer
 * inserts + updates the row; the consumer
 * reads). A new media item is a new row
 * (different `mediaId`); an updated media
 * item is the same row with a new
 * `lastSeenAtMs` + a possibly updated
 * `dateModifiedMs` + `sizeBytes` +
 * `contentHash`.
 */
@Entity(
    tableName = "media_index",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["media_type"]),
        Index(value = ["relative_path"]),
    ],
)
data class MediaIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "media_type")
    val mediaType: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "date_modified_ms")
    val dateModifiedMs: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,
    @ColumnInfo(name = "discovered_at_ms")
    val discoveredAtMs: Long,
    @ColumnInfo(name = "last_seen_at_ms")
    val lastSeenAtMs: Long,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
) {
    init {
        require(uri.isNotBlank()) {
            "MediaIndexEntity.uri must not be blank"
        }
        require(displayName.isNotBlank()) {
            "MediaIndexEntity.displayName must not be blank"
        }
        require(sizeBytes >= 0) {
            "MediaIndexEntity.sizeBytes must be >= 0, " +
                "got $sizeBytes"
        }
        require(discoveredAtMs > 0) {
            "MediaIndexEntity.discoveredAtMs must be > 0, " +
                "got $discoveredAtMs"
        }
        require(lastSeenAtMs >= discoveredAtMs) {
            "MediaIndexEntity.lastSeenAtMs " +
                "($lastSeenAtMs) must be >= " +
                "discoveredAtMs ($discoveredAtMs)"
        }
    }
}

/**
 * The typed media kind. The value is the
 * canonical enum for the index. The value
 * is stored as a string in the database
 * (Room's default enum mapping is by name;
 * the index uses the `name` for
 * compatibility with the existing media
 * queries).
 */
enum class MediaType {
    /** A still image (JPEG, PNG, WebP, etc.). */
    IMAGE,

    /** A video (MP4, WebM, etc.). */
    VIDEO,

    /** An audio file (MP3, OGG, FLAC, etc.). */
    AUDIO,

    /** An unknown / unsupported media type. */
    UNKNOWN,
    ;
}
