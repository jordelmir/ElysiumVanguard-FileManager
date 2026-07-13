package com.elysium.vanguard.core.ai

import java.net.URI

/**
 * Endpoint policy for the Command Core gateway.
 *
 * Remote deployments must use HTTPS. HTTP is deliberately restricted to
 * localhost so development can use `adb reverse` without teaching the APK to
 * send a bearer token across a LAN in clear text.
 */
object AgentGatewayEndpointPolicy {
    const val DEFAULT_ENDPOINT = "http://localhost:8787"

    fun normalize(raw: String): String {
        val input = raw.trim()
        require(input.isNotEmpty()) { "Gateway URL is required" }
        val uri = try {
            URI(input)
        } catch (error: Exception) {
            throw IllegalArgumentException("Gateway URL is invalid", error)
        }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Gateway URL needs a scheme")
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Gateway URL needs a host")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Gateway URL cannot include credentials, a query, or a fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Gateway URL must point to the gateway root"
        }
        require(uri.port in -1..65_535 && uri.port != 0) { "Gateway URL has an invalid port" }
        when (scheme) {
            "https" -> Unit
            "http" -> require(host == "localhost") {
                "HTTP is allowed only for localhost development through adb reverse"
            }
            else -> throw IllegalArgumentException("Gateway URL must use HTTPS or localhost HTTP")
        }
        return uri.toString().trimEnd('/')
    }
}
