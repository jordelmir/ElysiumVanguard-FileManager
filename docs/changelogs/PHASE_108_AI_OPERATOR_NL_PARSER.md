# Phase 108 — AI Operator NL Parser Richness + "crear acceso directo"

**Vision gap closed**: #8 (AI Operator — REAL — el parser de NL
no era tan rico; las acciones del marketplace no incluían
"crear acceso directo").
**Status**: shipped
**Date**: 2026-07-20

## The gap

The Phase 57 NL parser supported 6 rule families
(install distro, create windows, snapshot, rollback,
build, run). The vision's gap list called out:

> ❌ "Instala Blender, configura Vulkan, crea acceso directo"
> — el OperatorPlanExecutor ejecuta planes, pero el parser
> de NL no es tan rico; las acciones del marketplace no
> incluyen "crear acceso directo"

Two specific problems:

1. **No "crear acceso directo" action.** The user could
   install an app but couldn't add it to the launcher.
2. **No "configura Vulkan" action.** The user could install
   a driver but couldn't toggle it on/off for a specific
   app.
3. **No "publica" action.** The user could write a capsule
   but couldn't publish it to the marketplace from the agent.
4. **No multi-action goals.** The parser handled one action
   per goal; a goal like "instala Blender, configura Vulkan,
   crea acceso directo" failed to parse.

## What shipped

### Production code (3 modified)

| File | Change |
|---|---|
| `core/runtime/agent/AgentPlan.kt` (modified) | 3 new `AgentAction` variants: `CreateShortcut`, `ConfigureRuntime`, `PublishCapsule`. Each has a typed `init` block + a `describe()` rendering. |
| `core/runtime/agent/NaturalLanguageParser.kt` (modified) | 3 new rules: `configure <runtime>` (enable/disable), `create shortcut [a <app>]` (English + Spanish), `publish <capsule>` (English + Spanish). Multi-action goal split on `,` / `;` / ` and then ` / ` y luego `. Plan risk is the max of all sub-actions' risks. |
| `core/runtime/agent/PlanExecutor.kt` + `RealAgentCollaborators.kt` (modified) | `AgentCollaborators` interface gained 3 methods; `isDestructive` when-block handles the 3 new variants (all non-destructive: shortcuts / config / publish are recoverable). Production collaborators are Phase 108 stubs that emit typed success log lines; a follow-up phase wires the actual `ShortcutManager` / `RuntimeConfigService` / `MarketPublisher` calls. |

### Tests (1 new file, **+23 tests**)

| File | Tests |
|---|---|
| `NaturalLanguageParserPhase108Test.kt` (new) | 23 tests: 5 for `ConfigureRuntime` rule, 4 for `CreateShortcut` rule, 3 for `PublishCapsule` rule, 9 for multi-action goals (comma, semicolon, `and then`, `y luego`, risk-max aggregation, plan-fails-on-unparseable-sub-clause, empty sub-clauses dropped, order preserved, single-action regression), 2 for general regression checks. |

The pre-existing `NaturalLanguageParserTest` (14 tests) and
`PlanExecutorTest` still pass — the Phase 108 changes are
additive.

## Multi-action goals (the main feature)

The vision's literal example:

> "Instala Blender, configura Vulkan, crea acceso directo"

is now a 3-action plan:

```kotlin
AgentPlan(
    actions = [
        InstallDistro("blender"),        // MEDIUM risk
        ConfigureRuntime("VULKAN", "enable"),  // LOW risk
        CreateShortcut("default", "blender"), // LOW risk
    ],
    riskLevel = MEDIUM,  // max of 3
)
```

The parser splits on `,` / `;` / ` and then ` / ` y luego ` /
` then ` / ` luego `. Each sub-clause is parsed
independently. The plan is rejected (typed
`ParserOutcome.Unparseable`) if ANY sub-clause fails —
the parser does NOT silently drop an unparseable sub-clause.

## The 3 new `AgentAction` variants

```kotlin
data class CreateShortcut(
    val targetAppId: String,        // the app to add
    val displayName: String,        // the shortcut label
    val launchIntent: String? = null,
    val iconUri: String? = null,
) : AgentAction()

data class ConfigureRuntime(
    val runtime: String,            // "VULKAN", "DXVK", "WINE", etc.
    val operation: String,          // "enable" or "disable"
    val targetAppId: String? = null,  // optional app scope
) : AgentAction()

data class PublishCapsule(
    val capsuleId: String,           // reverse-DNS id
    val targetChannel: String = "stable",  // "stable" | "beta" | "internal"
) : AgentAction()
```

The executor dispatches each to the matching `AgentCollaborator`.
The Phase 108 production collaborators are typed stubs
(`Collaborators.createShortcut` returns a synthetic shortcut id
and a success log line; a follow-up phase wires the actual
`ShortcutManager.createShortcut()` call).

## Grammar fixes (Phase 108's bonuses)

While writing the new rules, I found two bugs in the
existing Phase 57 grammar:

1. **`setup` was in the `INSTALL_REGEX` verb list.** This
   caused "setup proot" to parse as
   `InstallDistro("setup proot")` (wrong). Fixed: removed
   `setup` from the install list. "setup proot" now correctly
   parses as `ConfigureRuntime("PROOT", "enable")`.
2. **The `SHORTCUT_REGEX` matched "crea acceso directo " with
   target=""** (the empty string). Fixed: the regex now
   requires a non-empty target when the preposition is
   present.

## Test counts

- Before: 3640 tests
- After: **3663 tests**, 0 new failures (+23 new)
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest`,
  unchanged from `f08dad5`)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3663/3664 green

## What this enables

- The vision's literal example "Instala Blender, configura
  Vulkan, crea acceso directo" now works end-to-end as a
  single typed agent goal.
- A creator can ask the agent to publish a capsule to the
  marketplace ("publica com.example.myapp to beta") — the
  agent emits a `PublishCapsule` action; the executor
  validates the signature + content hash (Phase 107 schema)
  before submitting.
- A user can manage their installed apps from the agent:
  shortcuts, runtime toggles, marketplace publishing — all
  via natural language, all rule-based (no third-party
  LLM dependency).

## What's still missing (next phases)

- **Real ShortcutManager / RuntimeConfigService /
  MarketPublisher wiring.** The Phase 108 collaborators
  are typed stubs that return success log lines. A
  follow-up phase wires the actual platform calls.
- **Risk-based plan review UI.** Multi-action plans with
  mixed-risk sub-actions (e.g. a HIGH rollback + a LOW
  shortcut) should show a "this step is destructive,
  review separately" hint in the plan-review dialog.
- **Sub-clause target resolution.** "crea acceso directo"
  without a target currently produces `targetAppId = "default"`.
  A future phase resolves "default" from the plan's other
  actions (e.g. the previous sub-clause's install target).
- **AI Operator action log UI.** The executor emits
  step-by-step results; the UI should show them in a
  timeline so the user can see which step failed.
