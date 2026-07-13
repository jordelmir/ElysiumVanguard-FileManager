package com.elysium.vanguard.core.ai

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AgentGatewaySettings(
    val endpoint: String,
    val isConfigured: Boolean
)

/**
 * Stores only the gateway token, never an OpenAI key. The token is encrypted
 * with a distinct Tink keyset protected by Android Keystore so it cannot
 * collide with the vault's encryption domain.
 */
@Singleton
class AgentGatewaySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun current(): AgentGatewaySettings = AgentGatewaySettings(
        endpoint = preferences.getString(KEY_ENDPOINT, AgentGatewayEndpointPolicy.DEFAULT_ENDPOINT)
            ?: AgentGatewayEndpointPolicy.DEFAULT_ENDPOINT,
        isConfigured = decryptToken() != null
    )

    @Synchronized
    fun readConnection(): AgentGatewayConnection? {
        val token = decryptToken() ?: return null
        val endpoint = preferences.getString(KEY_ENDPOINT, AgentGatewayEndpointPolicy.DEFAULT_ENDPOINT)
            ?: AgentGatewayEndpointPolicy.DEFAULT_ENDPOINT
        return AgentGatewayConnection(endpoint = endpoint, gatewayToken = token)
    }

    @Synchronized
    fun save(endpointInput: String, gatewayTokenInput: String) {
        val endpoint = AgentGatewayEndpointPolicy.normalize(endpointInput)
        val token = gatewayTokenInput.trim()
        if (token.isEmpty()) {
            require(decryptToken() != null) { "Gateway token is required for the first configuration" }
            preferences.edit().putString(KEY_ENDPOINT, endpoint).apply()
            return
        }
        require(token.length >= MIN_GATEWAY_TOKEN_LENGTH) {
            "Gateway token must be at least $MIN_GATEWAY_TOKEN_LENGTH characters"
        }
        val encrypted = getAead().encrypt(
            token.toByteArray(StandardCharsets.UTF_8),
            TOKEN_ASSOCIATED_DATA
        )
        preferences.edit()
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_TOKEN).apply()
    }

    /** Opaque per-installation identifier sent to the gateway for abuse controls. */
    @Synchronized
    fun currentSafetyIdentifier(): String {
        val existing = preferences.getString(KEY_SAFETY_IDENTIFIER, null)
        if (!existing.isNullOrBlank()) return existing
        return "android-${UUID.randomUUID()}".also { generated ->
            preferences.edit().putString(KEY_SAFETY_IDENTIFIER, generated).apply()
        }
    }

    private fun decryptToken(): String? {
        val encoded = preferences.getString(KEY_TOKEN, null) ?: return null
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            String(getAead().decrypt(encrypted, TOKEN_ASSOCIATED_DATA), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // A corrupted or invalidated Keystore entry is never reused.
            preferences.edit().remove(KEY_TOKEN).apply()
            null
        }
    }

    private fun getAead(): Aead {
        cachedAead?.let { return it }
        synchronized(this) {
            cachedAead?.let { return it }
            AeadConfig.register()
            val handle: KeysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return handle.getPrimitive(Aead::class.java).also { cachedAead = it }
        }
    }

    private companion object {
        const val PREFERENCES = "elysium_agent_gateway_settings"
        const val KEY_ENDPOINT = "gateway_endpoint"
        const val KEY_TOKEN = "encrypted_gateway_token"
        const val KEY_SAFETY_IDENTIFIER = "safety_identifier"
        const val KEYSET_PREF_FILE = "elysium_agent_gateway_keyset_prefs"
        const val KEYSET_NAME = "elysium_agent_gateway_keyset"
        const val MASTER_KEY_URI = "android-keystore://elysium_agent_gateway_master_key"
        const val MIN_GATEWAY_TOKEN_LENGTH = 24
        val TOKEN_ASSOCIATED_DATA = "elysium-agent-gateway-token:v1".toByteArray(StandardCharsets.UTF_8)

        @Volatile
        var cachedAead: Aead? = null
    }
}

data class AgentGatewayConnection(
    val endpoint: String,
    val gatewayToken: String
)
