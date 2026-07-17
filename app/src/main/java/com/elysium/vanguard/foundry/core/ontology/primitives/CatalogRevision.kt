package com.elysium.vanguard.foundry.core.ontology.primitives

/**
 * A typed reference to a specific revision of the parts catalog (the
 * canonical source of OEM part data + engineering references). The
 * revision is a semver string per the platform's content-addressing
 * convention (e.g. `2026.07`, `2026.07.1`, `2026.08-pre`).
 *
 * The catalog revision is a compilation input. Two compilations with
 * the same `(definition, catalogRevision, compilerVersion)` MUST
 * produce the same `Compilation.contentHash` — the catalog revision
 * is the second of the three deterministic inputs.
 */
@JvmInline
value class CatalogRevision(val value: String) {

    init {
        require(value.isNotBlank()) { "CatalogRevision must not be blank" }
        require(VALID_PATTERN.matches(value)) {
            "CatalogRevision must match $VALID_PATTERN, got: $value"
        }
    }

    companion object {
        private val VALID_PATTERN = Regex("""^\d{4}\.\d{2}(?:[.-][0-9A-Za-z\-]+)?$""")
    }
}
