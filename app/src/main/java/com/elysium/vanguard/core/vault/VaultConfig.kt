package com.elysium.vanguard.core.vault

import android.content.Context
import java.io.File

/**
 * PHASE 2.1 — Vault on-disk layout + configuration.
 *
 * The vault root lives inside the app's private internal storage (no external storage
 * permission needed, automatically excluded from the system gallery / MediaStore).
 * Layout:
 *
 * ```
 * <filesDir>/vault/
 *   meta/              (none today — reserved for future encrypted index)
 *   payloads/
 *     <id>.elyv        (one .elyv container per file)
 * ```
 *
 * Why internal storage and not external:
 * - Survives uninstall as expected (vault contents die with the app — by design).
 * - No SAF gymnastics for the common case.
 * - The Tink master key is already in Android Keystore which is per-app, so combining
 *   both is a defense-in-depth pairing.
 */
data class VaultConfig(
    val rootDir: File,
    val payloadsDir: File
) {
    val isInitialized: Boolean get() = payloadsDir.isDirectory || payloadsDir.mkdirs()

    fun newPayloadFile(id: Long): File = File(payloadsDir, "$id.elyv")

    fun payloadFor(id: Long): File = File(payloadsDir, "$id.elyv")

    fun listPayloads(): List<File> =
        payloadsDir.listFiles { f -> f.isFile && f.name.endsWith(".elyv") }?.toList() ?: emptyList()

    companion object {
        const val VAULT_DIR_NAME = "vault"
        const val PAYLOADS_DIR_NAME = "payloads"

        fun from(context: Context): VaultConfig {
            val root = File(context.filesDir, VAULT_DIR_NAME)
            val payloads = File(root, PAYLOADS_DIR_NAME)
            return VaultConfig(root, payloads)
        }
    }
}