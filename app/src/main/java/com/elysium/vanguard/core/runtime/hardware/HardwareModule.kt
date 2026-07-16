package com.elysium.vanguard.core.runtime.hardware

import android.content.Context
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAuditLog
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker
import com.elysium.vanguard.core.runtime.hardware.enforcer.AndroidHardwareEnforcer
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcementService
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcer
import com.elysium.vanguard.core.runtime.hardware.provider.AndroidPlatformHardwareProvider
import com.elysium.vanguard.core.runtime.hardware.provider.PlatformHardwareProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 21 — Hilt module for the hardware access path.
 *
 * Wires three singletons into the runtime's DI graph:
 *
 *   - [PlatformHardwareProvider] — the production
 *     [AndroidPlatformHardwareProvider] (wraps the
 *     Android platform managers). Tests inject a fake
 *     via a Hilt test module.
 *   - [HardwareEnforcer] — the
 *     [AndroidHardwareEnforcer] that uses the provider
 *     and implements the seam from Phase 19.
 *   - [HardwareEnforcementService] — the user-facing
 *     entry point that asks the broker, then dispatches
 *     to the enforcer.
 *
 * The broker + audit log are also singletons here; the
 * service is the only public seam. The audit log survives
 * the service so the observability layer (Phase 25) can
 * drain it without holding a service reference.
 */
@Module
@InstallIn(SingletonComponent::class)
object HardwareModule {

    @Provides
    @Singleton
    fun providePlatformHardwareProvider(
        @ApplicationContext context: Context
    ): PlatformHardwareProvider = AndroidPlatformHardwareProvider(context)

    @Provides
    @Singleton
    fun provideHardwareEnforcer(
        provider: PlatformHardwareProvider
    ): HardwareEnforcer = AndroidHardwareEnforcer(provider)

    @Provides
    @Singleton
    fun provideHardwareBroker(): HardwareBroker = HardwareBroker()

    @Provides
    @Singleton
    fun provideHardwareAuditLog(): HardwareAuditLog = HardwareAuditLog()

    @Provides
    @Singleton
    fun provideHardwareEnforcementService(
        broker: HardwareBroker,
        enforcer: HardwareEnforcer,
        audit: HardwareAuditLog
    ): HardwareEnforcementService = HardwareEnforcementService(
        broker = broker,
        enforcer = enforcer,
        audit = audit
    )
}
