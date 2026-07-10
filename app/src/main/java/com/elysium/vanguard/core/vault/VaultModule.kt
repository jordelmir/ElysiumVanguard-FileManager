package com.elysium.vanguard.core.vault

import android.content.Context
import com.elysium.vanguard.core.database.VaultDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 2.1 — Vault Hilt module.
 *
 * Wires the vault's collaborating pieces as application-scoped singletons. The
 * VaultKeyManager -> Aead -> VaultCrypto -> VaultRepository chain is built here
 * so callers can simply @Inject VaultRepository without thinking about the
 * Android Keystore / Tink plumbing.
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun provideVaultConfig(@ApplicationContext context: Context): VaultConfig =
        VaultConfig.from(context)

    @Provides
    @Singleton
    fun provideVaultCrypto(@ApplicationContext context: Context): VaultCrypto =
        VaultCrypto(VaultKeyManager.getMasterAead(context))

    @Provides
    @Singleton
    fun provideSecureDelete(): SecureDelete = SecureDelete()

    @Provides
    @Singleton
    fun provideVaultRepository(
        @ApplicationContext context: Context,
        config: VaultConfig,
        crypto: VaultCrypto,
        dao: VaultDao,
        secureDelete: SecureDelete
    ): VaultRepository = VaultRepository(context, config, crypto, dao, secureDelete)
}