package com.elysium.vanguard.core.ai

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class AgentGatewayHealth(
    val status: String = "",
    val service: String = "",
    val model: String = ""
)

data class AgentToolCall(
    val callId: String = "",
    val name: String = "",
    val argumentsJson: String = "{}",
    val requiresApproval: Boolean = false
)

data class AgentTurnResponse(
    val responseId: String = "",
    val text: String = "",
    val toolCalls: List<AgentToolCall> = emptyList()
)

private data class AgentTurnRequest(
    val message: String,
    val context: List<String>,
    val safetyIdentifier: String
)

/** Native HTTP client with bounded timeouts and no credential logging. */
@Singleton
class AgentGatewayHttpClient @Inject constructor() {
    private val gson = Gson()

    suspend fun health(endpoint: String): AgentGatewayHealth = withContext(Dispatchers.IO) {
        execute(
            endpoint = endpoint,
            path = "/healthz",
            method = "GET",
            gatewayToken = null,
            requestBody = null,
            responseType = AgentGatewayHealth::class.java
        )
    }

    suspend fun startTurn(
        connection: AgentGatewayConnection,
        message: String,
        context: List<String>,
        safetyIdentifier: String
    ): AgentTurnResponse = withContext(Dispatchers.IO) {
        execute(
            endpoint = connection.endpoint,
            path = "/v1/agent/turn",
            method = "POST",
            gatewayToken = connection.gatewayToken,
            requestBody = gson.toJson(AgentTurnRequest(message, context, safetyIdentifier)),
            responseType = AgentTurnResponse::class.java
        )
    }

    private fun <T> execute(
        endpoint: String,
        path: String,
        method: String,
        gatewayToken: String?,
        requestBody: String?,
        responseType: Class<T>
    ): T {
        val normalized = AgentGatewayEndpointPolicy.normalize(endpoint)
        val connection = (URL("$normalized$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            gatewayToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { stream -> stream.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw AgentGatewayException("Gateway request failed ($code)")
            }
            gson.fromJson(responseText, responseType)
                ?: throw AgentGatewayException("Gateway returned an empty response")
        } catch (error: AgentGatewayException) {
            throw error
        } catch (_: Exception) {
            throw AgentGatewayException("Cannot reach the Command Core gateway")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 45_000
    }
}

class AgentGatewayException(message: String) : Exception(message)
