package com.elysium.vanguard.core.runtime.market

/**
 * The 12 kinds of item a `MarketListing` can describe. New
 * kinds are ADRs (per `docs/foundry/domain-ownership.md` —
 * the same shape applies to the EV market, which is the
 * catalog side of the same platform).
 */
enum class MarketListingType {
    /** A complete Linux distribution image. */
    DISTRO,

    /** A Linux application package. */
    APP,

    /** A preconfigured Wine profile. */
    WINE_PROFILE,

    /** A container image. */
    CONTAINER,

    /** A development toolchain (compiler + libraries). */
    TOOLCHAIN,

    /** An integrated development environment. */
    IDE,

    /** A server (e.g. nginx, postgres, redis). */
    SERVER,

    /** A project template (scaffolding for a new project). */
    PROJECT_TEMPLATE,

    /** A configuration for a specific game. */
    GAME_CONFIG,

    /** A plugin (extending the host app's functionality). */
    PLUGIN,

    /** An automation (a workflow / a script). */
    AUTOMATION,

    /** An AI agent configuration. */
    AI_AGENT,
}
