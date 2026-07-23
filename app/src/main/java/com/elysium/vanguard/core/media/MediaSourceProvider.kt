package com.elysium.vanguard.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 118 — the **MediaSourceProvider**, a thin
 * wrapper around the `() -> MediaSource` factory
 * that the [MediaStoreObserver] uses to construct
 * a fresh [MediaSource] on every scan.
 *
 * The provider exists as a class (rather than a
 * raw `() -> MediaSource` Kotlin function type) so
 * Hilt can inject it without the
 * `Function0<? extends MediaSource>` wildcard
 * issue that bites every Hilt + Kotlin
 * lambda-as-binding project. Wrapping the factory
 * in a typed class is the standard Hilt idiom for
 * "I want to inject a lambda" and removes the
 * `@JvmSuppressWildcards` incantation that would
 * otherwise have to live on the constructor
 * parameter + the `@Provides` method.
 *
 * The provider is **process-scoped** (`@Singleton`);
 * the provider is stateless (the factory is the
 * only field). The `invoke()` method returns a
 * fresh [ContentResolverMediaSource] backed by the
 * application [Context]; the production `Cursor`
 * lifecycle is bounded to the call.
 *
 * The class is `open` so the unit tests can
 * subclass it with a stub `invoke()` (the
 * production [Context] is not available on the
 * JVM).
 */
@Singleton
open class MediaSourceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Return a fresh [MediaSource] for a single
     * scan. The observer calls this from
     * [MediaStoreObserver.triggerScan] for every
     * scan.
     */
    open operator fun invoke(): MediaSource =
        ContentResolverMediaSource(context = context)
}
