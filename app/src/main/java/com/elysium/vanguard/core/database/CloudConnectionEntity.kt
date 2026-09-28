package com.elysium.vanguard.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.elysium.vanguard.core.cloud.CloudConnection
import com.elysium.vanguard.core.cloud.CloudConnectionConfig
import com.elysium.vanguard.core.cloud.CloudCredentials
import com.elysium.vanguard.core.cloud.CloudProvider

/**
 * Room entity for cloud connection persistence.
 */
@Entity(tableName = "cloud_connections")
data class CloudConnectionEntity(
    @PrimaryKey val id: String,
    val provider: String,  // Store as string (provider name)
    val credentialsJson: String,  // Store as JSON string
    val configJson: String,  // Store as JSON string
    val createdAt: Long,
    val lastUsedAt: Long
) {
    fun toCloudConnection(): CloudConnection {
        val gson = Gson()
        val provider = CloudProvider.valueOf(provider)
        val credentials = gson.fromJson(credentialsJson, CloudCredentials::class.java)
        val config = gson.fromJson(configJson, CloudConnectionConfig::class.java)
        return CloudConnection(
            id = id,
            provider = provider,
            credentials = credentials,
            config = config,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt
        )
    }

    companion object {
        fun fromCloudConnection(connection: CloudConnection): CloudConnectionEntity {
            val gson = Gson()
            return CloudConnectionEntity(
                id = connection.id,
                provider = connection.provider.name,
                credentialsJson = gson.toJson(connection.credentials),
                configJson = gson.toJson(connection.config),
                createdAt = connection.createdAt,
                lastUsedAt = connection.lastUsedAt
            )
        }
    }
}