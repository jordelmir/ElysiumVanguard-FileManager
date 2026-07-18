package com.elysium.vanguard.foundry.persistence

import androidx.room.TypeConverter
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import java.util.UUID

/**
 * Room `TypeConverters` for the Foundry domain
 * primitives.
 *
 * Per `.ai/AGENTS.md` 24.1: the database
 * stores the **canonical string** of every
 * value class; the value class is reconstructed
 * on read. This keeps the database schema
 * readable (every column is a primitive SQL
 * type) + keeps the schema-version upgrades
 * simple (a new converter is a non-breaking
 * change).
 *
 * The converters are TOTAL: every value is
 * mapped to a non-null column. The
 * `FoundryError` envelope (per
 * `.ai/STANDARDS.md` 7) is not stored; the
 * typed error is reconstructed in the
 * repository layer if needed.
 */
class Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun contentHashToString(value: ContentHash?): String? = value?.value

    @TypeConverter
    fun stringToContentHash(value: String?): ContentHash? =
        value?.let { ContentHash(it) }

    @TypeConverter
    fun timestampToLong(value: Timestamp?): Long? = value?.epochMs

    @TypeConverter
    fun longToTimestamp(value: Long?): Timestamp? = value?.let(::Timestamp)

    @TypeConverter
    fun signatureToString(value: Signature?): String? = value?.value

    @TypeConverter
    fun stringToSignature(value: String?): Signature? =
        value?.let(::Signature)

    @TypeConverter
    fun stringListToString(value: List<String>?): String? =
        value?.joinToString(separator = "\u001F") // unit separator (non-printable)

    @TypeConverter
    fun stringToStringList(value: String?): List<String>? =
        value?.split("\u001F")?.filter { it.isNotEmpty() }
}
