package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 83 (Foundry / AI Operator) — the
 * **Operator Intent**, the typed foundation
 * for the AI as system operator (per
 * `.ai/AGENTS.md` section 14 + the master
 * vision section 8).
 *
 * Per the master vision (section 8):
 * "La IA no era un simple chatbot.
 * Funcionaba como agente de la plataforma:
 * Instalar una distro. Resolver
 * dependencias. Crear un entorno Windows.
 * Seleccionar Wine, Box64, FEX o QEMU.
 * Diagnosticar errores. Interpretar logs.
 * Optimizar flags de compilación. Generar
 * scripts. ..."
 *
 * Per `.ai/AGENTS.md` section 14 (the AI
 * authority boundary):
 *   - AI MAY interpret / propose / draft /
 *     explain.
 *   - AI MAY NOT directly approve safety-
 *     critical, certify regulatory, declare
 *     mechanical compatibility, finalize
 *     financial settlements, determine
 *     legal ownership, modify signed
 *     releases, create verified technical
 *     facts without evidence.
 *
 * The OperatorIntent captures both the AI's
 * intent (what the AI wants to do) + the
 * authority boundary (what the AI is
 * allowed to do). The
 * [OperatorIntentValidator] enforces the
 * boundary: a safe intent (e.g. a
 * diagnostic) is `Allowed`; a sensitive
 * intent (e.g. a financial settlement) is
 * `RequiresApproval`; an intent outside
 * the AI's scope is `Denied`.
 *
 * The intent is **pure-domain** (no I/O,
 * no Android dependencies). The test impl
 * is the `InMemoryOperatorIntentValidator`.
 * The production impl is the same
 * (the validator is stateless + pure;
 * the same impl is used in production).
 */
sealed class OperatorIntent {

    /**
     * The intent's id. The id is a UUID
     * (per the Foundry id convention).
     */
    abstract val intentId: IntentId

    /**
     * The agent that issued the intent.
     * The agent is a [UserId] (a platform
     * user with an `AIAuthor` role per
     * Phase F4).
     */
    abstract val agentId: UserId

    /**
     * A human-readable description of
     * the intent. The description is what
     * the human user sees when reviewing
     * the intent.
     */
    abstract val description: String

    /**
     * Install a Linux distro. The
     * install is a **sensitive**
     * operation (the distro becomes part
     * of the device's storage); the
     * validator may return
     * `RequiresApproval`.
     */
    data class InstallDistro(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val distroId: String,
        val targetWorkspaceId: String,
    ) : OperatorIntent() {
        init {
            require(distroId.isNotBlank()) {
                "OperatorIntent.InstallDistro.distroId must " +
                    "not be blank"
            }
            require(targetWorkspaceId.isNotBlank()) {
                "OperatorIntent.InstallDistro.targetWorkspaceId " +
                    "must not be blank"
            }
        }
    }

    /**
     * Create a workspace. The workspace
     * is a sensitive operation (the
     * workspace consumes disk + memory);
     * the validator may return
     * `RequiresApproval`.
     */
    data class CreateWorkspace(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val workspaceName: String,
        val sandboxProfile: String,
    ) : OperatorIntent() {
        init {
            require(workspaceName.isNotBlank()) {
                "OperatorIntent.CreateWorkspace.workspaceName " +
                    "must not be blank"
            }
            require(sandboxProfile.isNotBlank()) {
                "OperatorIntent.CreateWorkspace.sandboxProfile " +
                    "must not be blank"
            }
        }
    }

    /**
     * Launch a Capsule. The launch is a
     * **sensitive** operation (the
     * process may have network + storage
     * access); the validator may return
     * `RequiresApproval`.
     */
    data class LaunchCapsule(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val capsuleId: String,
        val runtime: String,
    ) : OperatorIntent() {
        init {
            require(capsuleId.isNotBlank()) {
                "OperatorIntent.LaunchCapsule.capsuleId must " +
                    "not be blank"
            }
            require(runtime.isNotBlank()) {
                "OperatorIntent.LaunchCapsule.runtime must " +
                    "not be blank"
            }
        }
    }

    /**
     * Stop a running process. The stop
     * is a **sensitive** operation (the
     * process may have unsaved state);
     * the validator may return
     * `RequiresApproval`.
     */
    data class StopProcess(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val handleId: String,
    ) : OperatorIntent() {
        init {
            require(handleId.isNotBlank()) {
                "OperatorIntent.StopProcess.handleId must " +
                    "not be blank"
            }
        }
    }

    /**
     * Run a diagnostic. The diagnostic
     * is a **safe** operation (it only
     * reads the device's state); the
     * validator returns `Allowed`.
     */
    data class RunDiagnostic(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val diagnosticKind: String,
    ) : OperatorIntent() {
        init {
            require(diagnosticKind.isNotBlank()) {
                "OperatorIntent.RunDiagnostic.diagnosticKind " +
                    "must not be blank"
            }
        }
    }

    /**
     * Generate a script. The script
     * generation is a **safe** operation
     * (it only writes to a draft buffer);
     * the validator returns `Allowed`.
     */
    data class GenerateScript(
        override val intentId: IntentId,
        override val agentId: UserId,
        override val description: String,
        val language: String,
    ) : OperatorIntent() {
        init {
            require(language.isNotBlank()) {
                "OperatorIntent.GenerateScript.language " +
                    "must not be blank"
            }
        }
    }
}

/**
 * The typed id of an operator intent. The
 * id is a UUID (per the Foundry id
 * convention).
 */
@JvmInline
value class IntentId(val value: UUID) {
    companion object {
        fun random(): IntentId = IntentId(UUID.randomUUID())
        fun from(raw: String): Result<IntentId> = try {
            Result.success(IntentId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                OperatorIntentError.InvalidIntentIdFormat(raw, e),
            )
        }
    }
}

/**
 * The typed intent kind. The kind is the
 * **classification** of the intent; a
 * `when` on the kind is **exhaustive**.
 *
 * The kind is used by the authority to
 * enforce the scope (e.g. a `ReadOnly`
 * authority can only issue
 * `RunDiagnostic` + `GenerateScript`
 * intents).
 */
enum class IntentKind(val displayLabel: String) {
    /** Install a Linux distro. */
    INSTALL_DISTRO("Install Distro"),

    /** Create a workspace. */
    CREATE_WORKSPACE("Create Workspace"),

    /** Launch a Capsule. */
    LAUNCH_CAPSULE("Launch Capsule"),

    /** Stop a running process. */
    STOP_PROCESS("Stop Process"),

    /** Run a diagnostic. */
    RUN_DIAGNOSTIC("Run Diagnostic"),

    /** Generate a script. */
    GENERATE_SCRIPT("Generate Script"),
}

/**
 * Helper extension to map an
 * [OperatorIntent] to its [IntentKind].
 */
val OperatorIntent.kind: IntentKind
    get() = when (this) {
        is OperatorIntent.InstallDistro ->
            IntentKind.INSTALL_DISTRO
        is OperatorIntent.CreateWorkspace ->
            IntentKind.CREATE_WORKSPACE
        is OperatorIntent.LaunchCapsule ->
            IntentKind.LAUNCH_CAPSULE
        is OperatorIntent.StopProcess ->
            IntentKind.STOP_PROCESS
        is OperatorIntent.RunDiagnostic ->
            IntentKind.RUN_DIAGNOSTIC
        is OperatorIntent.GenerateScript ->
            IntentKind.GENERATE_SCRIPT
    }

/**
 * The AI agent's authority scope. The
 * scope determines what the AI is
 * allowed to do.
 *
 * Per the master vision (section 8) +
 * `.ai/AGENTS.md` section 14, the AI has
 * a **restricted authority** by default:
 * the AI can read + interpret, but
 * sensitive operations (install, create,
 * launch, stop) require human approval.
 *
 * The scope is a sealed class with 3
 * cases:
 *   - **`Full`** — all operations are
 *     allowed.
 *   - **`Restricted(allowedKinds)`** —
 *     only the specified kinds are
 *     allowed (other kinds are denied or
 *     require approval based on the kind).
 *   - **`ReadOnly`** — only the safe
 *     operations (RunDiagnostic +
 *     GenerateScript) are allowed;
 *     sensitive operations are denied.
 */
sealed class OperatorAuthorityScope {

    /**
     * Full authority. All operations are
     * `Allowed` (no human approval
     * required). Used for **trusted**
     * AI agents (e.g. a human-supervised
     * AI agent for a specific workspace).
     */
    data object Full : OperatorAuthorityScope()

    /**
     * Restricted authority. Only the
     * specified kinds are `Allowed` (no
     * human approval required); other
     * kinds are `RequiresApproval` (the
     * human must approve).
     */
    data class Restricted(
        val allowedKinds: Set<IntentKind>,
    ) : OperatorAuthorityScope() {
        init {
            require(allowedKinds.isNotEmpty()) {
                "OperatorAuthorityScope.Restricted.allowedKinds " +
                    "must not be empty"
            }
        }
    }

    /**
     * Read-only authority. Only the safe
     * operations (RunDiagnostic +
     * GenerateScript) are `Allowed`; all
     * other operations are `Denied`.
     *
     * This is the **default** scope for
     * new AI agents.
     */
    data object ReadOnly : OperatorAuthorityScope()

    /**
     * The kinds the agent can issue
     * without human approval. The set is
     * the `allowedKinds` for
     * `Restricted`; the safe kinds for
     * `ReadOnly`; all kinds for `Full`.
     */
    val autoApprovedKinds: Set<IntentKind>
        get() = when (this) {
            is Full -> IntentKind.values().toSet()
            is Restricted -> allowedKinds
            is ReadOnly -> setOf(
                IntentKind.RUN_DIAGNOSTIC,
                IntentKind.GENERATE_SCRIPT,
            )
        }
}

/**
 * The AI agent's authority. The authority
 * is the **signed record** of the AI
 * agent's scope. The authority has:
 *   - **`agentId`** — the AI agent (a
 *     [UserId] with an `AIAuthor` role).
 *   - **`scope`** — the agent's authority
 *     scope.
 *   - **`issuedBy`** — the human user that
 *     issued the authority (a [UserId]
 *     with a `HumanAgent` role).
 *   - **`signature`** — the authority's
 *     signature.
 */
data class OperatorAuthority(
    val agentId: UserId,
    val scope: OperatorAuthorityScope,
    val issuedBy: UserId,
    val signature: Signature,
) {
    init {
        require(agentId != issuedBy) {
            "OperatorAuthority.agentId and issuedBy must " +
                "not be equal (an AI agent cannot " +
                "self-issue authority)"
        }
    }
}

/**
 * The typed validation result. The result
 * is the validator's output for a given
 * [OperatorIntent] + [OperatorAuthority].
 *
 * The result is a sealed class with 3
 * cases:
 *   - **`Allowed`** — the intent is
 *     allowed; the agent can execute
 *     without human approval.
 *   - **`RequiresApproval(reason)`** —
 *     the intent is allowed but requires
 *     human approval before execution.
 *   - **`Denied(reason)`** — the intent
 *     is denied; the agent cannot
 *     execute the intent.
 */
sealed class ValidationResult {

    /**
     * The intent is allowed. The agent
     * can execute the intent without
     * human approval.
     */
    data object Allowed : ValidationResult()

    /**
     * The intent is allowed but
     * requires human approval before
     * execution. The reason is a
     * human-readable string.
     */
    data class RequiresApproval(
        val reason: String,
    ) : ValidationResult() {
        init {
            require(reason.isNotBlank()) {
                "ValidationResult.RequiresApproval.reason " +
                    "must not be blank"
            }
        }
    }

    /**
     * The intent is denied. The agent
     * cannot execute the intent. The
     * reason is a human-readable string.
     */
    data class Denied(
        val reason: String,
    ) : ValidationResult() {
        init {
            require(reason.isNotBlank()) {
                "ValidationResult.Denied.reason must " +
                    "not be blank"
            }
        }
    }
}

/**
 * The typed validator. The validator
 * checks an [OperatorIntent] against an
 * [OperatorAuthority] + returns a
 * [ValidationResult].
 *
 * The validation rules are:
 *   - If the authority is `Full` →
 *     `Allowed` (any intent).
 *   - If the authority is `Restricted`
 *     AND the kind is in `allowedKinds`
 *     → `Allowed`.
 *   - If the authority is `Restricted`
 *     AND the kind is NOT in
 *     `allowedKinds` →
 *     `RequiresApproval`.
 *   - If the authority is `ReadOnly`
 *     AND the kind is a safe kind
 *     (RunDiagnostic / GenerateScript)
 *     → `Allowed`.
 *   - If the authority is `ReadOnly`
 *     AND the kind is NOT a safe kind
 *     → `Denied`.
 */
sealed class OperatorIntentValidator {

    /**
     * Validate an [OperatorIntent] against
     * an [OperatorAuthority]. Returns a
     * [ValidationResult].
     */
    abstract fun validate(
        intent: OperatorIntent,
        authority: OperatorAuthority,
    ): ValidationResult

    /**
     * Check whether an [OperatorIntent]
     * is allowed for an
     * [OperatorAuthority] (the convenience
     * predicate for the "no human
     * approval required" case).
     */
    fun isAllowed(
        intent: OperatorIntent,
        authority: OperatorAuthority,
    ): Boolean = validate(intent, authority) is ValidationResult.Allowed
}

/**
 * The in-memory [OperatorIntentValidator]
 * for testing + production. The validator
 * is the stateless composition of:
 *   - The validation rules (per
 *     [OperatorAuthorityScope] +
 *     [OperatorIntent.kind]).
 *
 * The validator is **thread-safe** (no
 * mutable fields).
 */
class InMemoryOperatorIntentValidator : OperatorIntentValidator() {

    override fun validate(
        intent: OperatorIntent,
        authority: OperatorAuthority,
    ): ValidationResult {
        val kind = intent.kind
        val autoApprovedKinds = authority.scope.autoApprovedKinds
        return when {
            // Rule 1: If the kind is in the
            // auto-approved kinds, the
            // intent is allowed.
            kind in autoApprovedKinds ->
                ValidationResult.Allowed
            // Rule 2: For ReadOnly, any kind
            // not in the safe set is denied
            // (not just requires approval).
            authority.scope is OperatorAuthorityScope.ReadOnly ->
                ValidationResult.Denied(
                    reason = "authority is ReadOnly; " +
                        "kind $kind is not safe",
                )
            // Rule 3: For Restricted or Full,
            // any kind not in the auto-
            // approved set requires human
            // approval.
            else -> ValidationResult.RequiresApproval(
                reason = "kind $kind is not in the " +
                    "agent's auto-approved kinds",
            )
        }
    }
}

/**
 * The typed error envelope for the
 * operator intent. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the `operator`
 * package because Kotlin sealed classes
 * only permit subclassing in the same
 * package where the base class is
 * declared).
 */
sealed class OperatorIntentError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The intent id string was not a
     * valid UUID. Raised at the boundary
     * (per `.ai/AGENTS.md` 24.1) — never
     * inside the domain.
     */
    data class InvalidIntentIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : OperatorIntentError(
        message = "Invalid UUID format for IntentId: $rawInput",
        code = "INVALID_INTENT_ID_FORMAT",
    )
}
