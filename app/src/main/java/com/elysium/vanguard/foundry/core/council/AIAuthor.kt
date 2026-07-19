package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.util.UUID

/**
 * Phase F4 first half (G5, I-4.1) — the **AI Author**,
 * the typed identity of an agent that writes proposals
 * to the AI council.
 *
 * Per `.ai/AGENTS.md` section 21 (Completion Standard)
 * + skill 05 (AI Orchestration), every proposal has a
 * typed author. The author is one of two cases:
 *
 *   - **ModelAgent** — an AI model (a large language
 *     model, a fine-tuned model, etc.). The author
 *     carries the model's id + version + the role the
 *     model is playing.
 *
 *   - **HumanAgent** — a human expert. The author
 *     carries the human's employee id + the role the
 *     human is playing. A human is treated as an
 *     "author" because the human can also write
 *     proposals (e.g. a domain expert drafts a proposal
 *     before the council deliberates).
 *
 * The author is a **sealed class** because the platform
 * must distinguish between AI-generated proposals and
 * human-generated proposals (per the AI authority
 * boundary in `.ai/AGENTS.md` section 21.3: AI may
 * interpret/propose/draft/explain; AI may NOT directly
 * approve safety-critical, certify regulatory, declare
 * mechanical compatibility, finalize financial
 * settlements, determine legal ownership, modify signed
 * releases, or create verified technical facts without
 * evidence).
 *
 * The author is **immutable** (a sealed class with
 * `val` fields; no setters). A new author is a new
 * value. The author's lifecycle (a model's version
 * bump, a human's role change) is a new `AIAuthor`
 * value.
 */
sealed class AIAuthor {

    /**
     * The author's unique id. Every author has a
     * stable id (a UUID) — the id is the join key
     * the council uses to aggregate proposals.
     */
    abstract val authorId: AIAuthorId

    /**
     * The author's role. The role is the typed
     * "hat" the author is wearing when writing
     * a proposal (a domain expert reviewing a
     * powertrain spec, a compliance reviewer
     * reviewing emissions, etc.).
     */
    abstract val role: AIAuthorRole

    /**
     * An AI model (a large language model, a
     * fine-tuned model, a heuristic engine).
     * The model writes proposals on behalf of
     * the platform; the proposals are typed
     * (not free-form text) and carry the
     * model's id + version for audit.
     */
    data class ModelAgent(
        override val authorId: AIAuthorId,
        override val role: AIAuthorRole,
        val modelId: String,
        val modelVersion: String,
    ) : AIAuthor() {
        init {
            require(modelId.isNotBlank()) {
                "AIAuthor.ModelAgent.modelId must not be blank"
            }
            require(modelVersion.isNotBlank()) {
                "AIAuthor.ModelAgent.modelVersion must not be blank"
            }
        }

        /**
         * The model identifier. The string is
         * the `modelId:version` form (e.g.
         * `"gpt-4:0613"` or `"claude-opus-4:1"`).
         */
        val modelIdentifier: String
            get() = "$modelId:$modelVersion"
    }

    /**
     * A human expert. The human writes
     * proposals on behalf of the platform;
     * the proposals are typed + signed (per
     * the human review flow, see [HumanReview]).
     */
    data class HumanAgent(
        override val authorId: AIAuthorId,
        override val role: AIAuthorRole,
        val employeeId: String,
    ) : AIAuthor() {
        init {
            require(employeeId.isNotBlank()) {
                "AIAuthor.HumanAgent.employeeId must not be blank"
            }
        }
    }
}

/**
 * The typed id of an AI author. The id is a UUID
 * (per the Foundry id convention; see
 * `com.elysium.vanguard.foundry.core.ontology.ids`).
 */
@JvmInline
value class AIAuthorId(val value: UUID) {
    companion object {
        fun random(): AIAuthorId = AIAuthorId(UUID.randomUUID())
        fun from(raw: String): Result<AIAuthorId> = try {
            Result.success(AIAuthorId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("AIAuthorId", raw, e))
        }
    }
}

/**
 * The role an AI author is playing. The role is
 * the typed "hat" the agent is wearing; a single
 * author can play multiple roles (a model might
 * play `DOMAIN_EXPERT` for one proposal and
 * `SAFETY_ANALYST` for another).
 *
 * The roles are derived from the Foundry
 * "multiple reviewers" pattern (per `.ai/AGENTS.md`
 * section 21 + skill 05). The role determines the
 * agent's perspective on the proposal — a
 * `COMPLIANCE_REVIEWER` cares about regulatory
 * compliance; a `SAFETY_ANALYST` cares about
 * physical safety; a `PERFORMANCE_ENGINEER`
 * cares about performance; a `COST_ANALYST`
 * cares about cost.
 */
enum class AIAuthorRole(val displayLabel: String) {
    /** A domain expert for the program/vehicle
     *  type. The expert understands the vehicle's
     *  powertrain, body, driveline, etc. */
    DOMAIN_EXPERT("Domain Expert"),

    /** A regulatory/compliance reviewer. The
     *  reviewer understands the regulatory
     *  requirements (emissions, safety standards,
     *  market access). */
    COMPLIANCE_REVIEWER("Compliance Reviewer"),

    /** A safety analyst. The analyst understands
     *  the physical safety implications (crash
     *  safety, brake performance, occupant
     *  protection). */
    SAFETY_ANALYST("Safety Analyst"),

    /** A performance engineer. The engineer
     *  understands the performance characteristics
     *  (power, torque, fuel economy, NVH). */
    PERFORMANCE_ENGINEER("Performance Engineer"),

    /** A cost analyst. The analyst understands
     *  the cost implications (BOM cost, tooling
     *  cost, lifecycle cost). */
    COST_ANALYST("Cost Analyst"),

    /** A general reviewer (no specific role). The
     *  reviewer provides a holistic assessment
     *  without a specific lens. */
    GENERAL_REVIEWER("General Reviewer"),
}
