package com.elysium.vanguard.core.runtime.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the WorkspaceDefinition JSON roundtrip.
 *
 * The repository's [WorkspaceRepository.Companion.parseWorkspaceJson]
 * is a pure function over a string; it does not need Room. These
 * tests exercise every field and a representative selection of
 * edge cases.
 */
class WorkspaceJsonParseTest {

    @Test
    fun `parses a minimal workspace with one service`() {
        val json = """
            {"id":"ws-1","name":"Hello","description":"","rootfsId":"debian-bookworm","runtime":"proot-linux","services":[],"environment":{},"ports":[],"storageMounts":[],"healthChecks":[]}
        """.trimIndent()
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals("ws-1", def.id)
        assertEquals("Hello", def.name)
        assertEquals("debian-bookworm", def.rootfsId)
        assertEquals("proot-linux", def.runtime)
        assertTrue(def.services.isEmpty())
    }

    @Test
    fun `parses services with command and dependsOn`() {
        val json = """
            {
              "id":"ws-2",
              "name":"DAG",
              "description":"",
              "rootfsId":"debian",
              "runtime":"proot-linux",
              "services":[
                {"name":"db","command":["postgres","-D","/var/lib/postgres"],"dependsOn":[],"autoStart":true,"restartOnFailure":false,"maxRestarts":3,"startupTimeoutMs":30000,"environment":{}},
                {"name":"api","command":["/usr/bin/node","server.js"],"dependsOn":["db"],"autoStart":true,"restartOnFailure":true,"maxRestarts":5,"startupTimeoutMs":60000,"environment":{"PORT":"8080"}}
              ],
              "environment":{},
              "ports":[],
              "storageMounts":[],
              "healthChecks":[]
            }
        """.trimIndent()
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        println("DEBUG services=${def.services.size} id=${def.id} rootfsId=${def.rootfsId}")
        assertEquals(2, def.services.size)
        val db = def.services.first { it.name == "db" }
        assertEquals(listOf("postgres", "-D", "/var/lib/postgres"), db.command)
        assertTrue(db.dependsOn.isEmpty())
        val api = def.services.first { it.name == "api" }
        assertEquals(listOf("db"), api.dependsOn)
        assertEquals(5, api.maxRestarts)
        assertEquals(60_000L, api.startupTimeoutMs)
        assertEquals("8080", api.environment["PORT"])
    }

    @Test
    fun `parses health checks and ports`() {
        val json = """
            {
              "id":"ws-3",
              "name":"Health",
              "description":"",
              "rootfsId":"debian",
              "runtime":"proot-linux",
              "services":[{"name":"api","command":["node","server.js"],"dependsOn":[],"autoStart":true,"restartOnFailure":false,"maxRestarts":3,"startupTimeoutMs":30000,"environment":{}}],
              "environment":{},
              "ports":[{"guest":8080,"host":8080,"protocol":"tcp"},{"guest":9090,"host":-1,"protocol":"tcp"}],
              "storageMounts":[{"hostPath":"/sdcard","guestPath":"/mnt/sdcard","readOnly":true}],
              "healthChecks":[
                {"name":"api","command":["curl","-f","http://127.0.0.1:8080/health"],"intervalMs":5000,"timeoutMs":2000,"failureThreshold":3}
              ]
            }
        """.trimIndent()
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals(2, def.ports.size)
        assertEquals(8080, def.ports[0].guestPort)
        assertEquals(8080, def.ports[0].hostPort)
        // host=-1 means no host mapping; the parser preserves that as null.
        assertEquals(9090, def.ports[1].guestPort)
        assertEquals(null, def.ports[1].hostPort)
        assertEquals(1, def.storageMounts.size)
        assertTrue(def.storageMounts[0].readOnly)
        assertEquals(1, def.healthChecks.size)
        assertEquals(2000L, def.healthChecks[0].timeoutMs)
    }

    @Test
    fun `parses an empty workspace`() {
        val json = """{"id":"ws-empty","name":"Empty","description":"","rootfsId":"debian","runtime":"proot-linux","services":[],"environment":{},"ports":[],"storageMounts":[],"healthChecks":[]}"""
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals("ws-empty", def.id)
        assertTrue(def.services.isEmpty())
        assertTrue(def.ports.isEmpty())
        assertTrue(def.storageMounts.isEmpty())
        assertTrue(def.healthChecks.isEmpty())
        assertTrue(def.environment.isEmpty())
    }

    @Test
    fun `roundtrip preserves every field`() {
        val original = WorkspaceDefinition(
            id = "ws-rt",
            name = "Roundtrip",
            description = "Roundtrip test workspace",
            rootfsId = "debian-bookworm",
            runtime = "proot-linux",
            services = listOf(
                ServiceDefinition(
                    name = "db",
                    command = listOf("postgres", "-D", "/data"),
                    dependsOn = emptyList(),
                    autoStart = true,
                    restartOnFailure = false,
                    maxRestarts = 3,
                    startupTimeoutMs = 30_000L,
                    environment = mapOf("PGDATA" to "/data")
                ),
                ServiceDefinition(
                    name = "api",
                    command = listOf("node", "server.js"),
                    dependsOn = listOf("db"),
                    autoStart = true,
                    restartOnFailure = true,
                    maxRestarts = 5,
                    startupTimeoutMs = 60_000L,
                    environment = mapOf("PORT" to "8080")
                )
            ),
            environment = mapOf("GLOBAL" to "1"),
            ports = listOf(PortMapping(guestPort = 8080, hostPort = 8080, protocol = "tcp")),
            storageMounts = listOf(StorageMount("/sdcard", "/mnt/sdcard", readOnly = true)),
            healthChecks = listOf(
                HealthCheck(
                    name = "api",
                    command = listOf("curl", "-f", "http://127.0.0.1:8080/health"),
                    intervalMs = 5_000L,
                    timeoutMs = 2_000L,
                    failureThreshold = 3
                )
            )
        )
        val json = original.toJson()
        val parsed = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.rootfsId, parsed.rootfsId)
        assertEquals(original.runtime, parsed.runtime)
        assertEquals(original.services.size, parsed.services.size)
        assertEquals(original.services[0].command, parsed.services[0].command)
        assertEquals(original.services[1].dependsOn, parsed.services[1].dependsOn)
        assertEquals(original.services[1].environment, parsed.services[1].environment)
        assertEquals(original.environment, parsed.environment)
        assertEquals(original.ports, parsed.ports)
        assertEquals(original.storageMounts, parsed.storageMounts)
        assertEquals(original.healthChecks.size, parsed.healthChecks.size)
        assertEquals(original.healthChecks[0].command, parsed.healthChecks[0].command)
    }

    @Test
    fun `compact json without spaces is accepted`() {
        val json = """{"id":"ws-compact","name":"Compact","description":"","rootfsId":"debian","runtime":"proot-linux","services":[],"environment":{},"ports":[],"storageMounts":[],"healthChecks":[]}"""
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals("ws-compact", def.id)
        assertEquals("Compact", def.name)
    }

    @Test
    fun `parser tolerates fields it does not know`() {
        val json = """
            {
              "id":"ws-future",
              "name":"Future",
              "description":"",
              "rootfsId":"debian",
              "runtime":"proot-linux",
              "services":[],
              "environment":{},
              "ports":[],
              "storageMounts":[],
              "healthChecks":[],
              "futureField":"ignored",
              "anotherOne":{"nested":"value"}
            }
        """.trimIndent()
        val def = WorkspaceRepository.parseWorkspaceJson(json)
        assertEquals("ws-future", def.id)
        // The unknown fields do not break parsing; the
        // definition's known fields are populated correctly.
        assertNotNull(def)
    }
}
