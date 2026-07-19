package com.elysium.vanguard.core.runtime.agent

import com.elysium.vanguard.core.runtime.build.LocalBuildRunner
import com.elysium.vanguard.core.runtime.build.ToolchainRegistry
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 73 — the Hilt module for the rule-based
 * Vanguard AI.
 *
 * Until Phase 73 the rule-based agent was a
 * pure-domain artifact (a parser + an executor
 * with a typed `AgentCollaborators` interface)
 * with no DI wiring. The
 * [com.elysium.vanguard.features.agent.LocalAgentViewModel]
 * (Phase 73) consumes this module to obtain
 * production `PlanExecutor` + `NaturalLanguageParser`
 * + `RealAgentCollaborators` instances.
 *
 * The module deliberately does NOT provide the
 * HTTP-gateway Command Core's
 * [com.elysium.vanguard.core.ai.AgentGatewayHttpClient]
 * / [com.elysium.vanguard.core.ai.AgentLocalToolExecutor]
 * — those are wired by the gateway's own
 * [com.elysium.vanguard.core.ai.AgentGatewayModule]
 * (a separate Hilt graph, scoped to the
 * `command_core` route in `MainActivity`). The
 * two systems coexist; the user picks which
 * agent to talk to.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    /**
     * The rule-based [NaturalLanguageParser].
     * The parser is stateless + pure-domain: same
     * input, same parsed plan. A `@Singleton` is
     * safe.
     */
    @Provides
    @Singleton
    fun provideNaturalLanguageParser(): NaturalLanguageParser = NaturalLanguageParser()

    /**
     * The rule-based [PlanExecutor]. The executor
     * holds no state of its own (the per-plan
     * state lives in the [AgentPlan] the caller
     * passes to `execute`); the @Singleton scope
     * is purely a Hilt lifetime choice — multiple
     * callers can share the instance.
     *
     * The event bus is injected so the executor
     * can publish `AgentActionStartedEvent` /
     * `AgentActionCompletedEvent` /
     * `AgentActionFailedEvent` / etc. on the
     * shared runtime bus. The UI subscribes to
     * these events for the agent's audit log.
     */
    @Provides
    @Singleton
    fun providePlanExecutor(
        collaborators: AgentCollaborators,
        eventBus: RuntimeEventBus,
    ): PlanExecutor = PlanExecutor(
        collaborators = collaborators,
        eventBus = eventBus,
    )

    /**
     * The [LocalBuildRunner] the agent's
     * `runBuild` collaborator delegates to.
     * The runner is the production seam for
     * "spawn a build process"; the
     * `RuntimeModule` already provides the
     * [ProcessLauncher] the runner needs.
     */
    @Provides
    @Singleton
    fun provideLocalBuildRunner(
        processLauncher: ProcessLauncher
    ): LocalBuildRunner = LocalBuildRunner(processLauncher)

    /**
     * The [ToolchainRegistry] the agent's
     * `runBuild` collaborator delegates to.
     * The registry probes the device for which
     * toolchains (rustc, cargo, gradle, node,
     * etc.) are installed. Phase 73 ships an
     * empty registry — a follow-up phase wires
     * the real detector that walks the device's
     * PATH and filesystem.
     */
    @Provides
    @Singleton
    fun provideToolchainRegistry(): ToolchainRegistry = ToolchainRegistry()
}

/**
 * Phase 73 — the `@Binds` module that maps the
 * [AgentCollaborators] interface to its
 * production [RealAgentCollaborators]
 * implementation. The interface was Phase 57's
 * seam (so the executor + parser could be
 * unit-tested with a hand-rolled fake). The
 * production binding is the Phase 73 step that
 * turns the rule-based agent from a parser +
 * tests into an agent that **operates the
 * platform**.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentCollaboratorsModule {

    @Binds
    @Singleton
    abstract fun bindAgentCollaborators(
        realAgentCollaborators: RealAgentCollaborators
    ): AgentCollaborators
}
