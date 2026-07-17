package com.elysium.vanguard.foundry.core.ontology.primitives

/**
 * A typed reference to a specific version of the deterministic vehicle
 * compiler. The version is a semver string (e.g. `1.0.0`, `1.0.1-pre`).
 *
 * The compiler version is a compilation input. Two compilations with
 * the same `(definition, catalogRevision, compilerVersion)` MUST
 * produce the same `Compilation.contentHash` — the compiler version
 * is the third of the three deterministic inputs. A compiler version
 * bump is the moment when an old set of `Compilation.contentHash`
 * values become "frozen under compiler 1.0.0" and a new set of
 * `Compilation.contentHash` values become the canonical content
 * under compiler 1.0.1.
 */
@JvmInline
value class CompilerVersion(val value: String) {

    init {
        require(value.isNotBlank()) { "CompilerVersion must not be blank" }
        require(VALID_PATTERN.matches(value)) {
            "CompilerVersion must match semver $VALID_PATTERN, got: $value"
        }
    }

    companion object {
        private val VALID_PATTERN = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z\-]+(?:\.[0-9A-Za-z\-]+)*)?$""")
    }
}
