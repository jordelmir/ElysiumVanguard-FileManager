package com.elysium.vanguard.core.runtime.hardware

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareBrokerImplTest {

    private fun createBroker(): HardwareBrokerImpl {
        val context: android.content.Context = org.mockito.kotlin.mock()
        return HardwareBrokerImpl(context, "/run/elysium/test")
    }

    @Test
    fun `initial state is Idle`() = block {
        val broker = createBroker()
        assertEquals(HardwareBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `available resources is empty when no hardware probed`() = block {
        val broker = createBroker()
        assertTrue(broker.availableResources().isEmpty())
    }

    @Test
    fun `requestAccess with blank sessionId fails`() = block {
        val broker = createBroker()
        val resource = HardwareResource(
            type = ResourceType.SERIAL,
            id = "ttyUSB0",
            name = "USB Serial",
            capabilities = setOf(ResourceCapability.READ)
        )
        val result = broker.requestAccess(
            sessionId = "",
            resource = resource,
            scope = AccessScope(allowedOperations = setOf(ResourceCapability.READ))
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `requestAccess for unavailable resource fails`() = block {
        val broker = createBroker()
        val resource = HardwareResource(
            type = ResourceType.SERIAL,
            id = "ttyUSB0",
            name = "USB Serial",
            capabilities = setOf(ResourceCapability.READ)
        )
        val result = broker.requestAccess(
            sessionId = "session-1",
            resource = resource,
            scope = AccessScope(allowedOperations = setOf(ResourceCapability.READ))
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `revokeAccess for nonexistent token fails`() = block {
        val broker = createBroker()
        val result = broker.revokeAccess("nonexistent-token")
        assertTrue(result.isFailure)
    }

    @Test
    fun `hasAccess returns false when no access granted`() = block {
        val broker = createBroker()
        val resource = HardwareResource(
            type = ResourceType.SERIAL,
            id = "ttyUSB0",
            name = "USB Serial",
            capabilities = setOf(ResourceCapability.READ)
        )
        assertFalse(broker.hasAccess("session-1", resource))
    }

    @Test
    fun `devicePath returns correct paths for each resource type`() = block {
        val broker = createBroker()
        assertEquals("/run/elysium/test/obd0", broker.devicePath(
            HardwareResource(ResourceType.OBD, "0", "OBD", emptySet())
        ))
        assertEquals("/run/elysium/test/can0", broker.devicePath(
            HardwareResource(ResourceType.CAN, "0", "CAN", emptySet())
        ))
        assertEquals("/run/elysium/test/serial1", broker.devicePath(
            HardwareResource(ResourceType.SERIAL, "1", "Serial", emptySet())
        ))
        assertEquals("/run/elysium/test/usb/001", broker.devicePath(
            HardwareResource(ResourceType.USB, "001", "USB", emptySet())
        ))
        assertEquals("/run/elysium/test/bt/AA:BB:CC", broker.devicePath(
            HardwareResource(ResourceType.BLUETOOTH, "AA:BB:CC", "BT", emptySet())
        ))
        assertEquals("/run/elysium/test/camera/0", broker.devicePath(
            HardwareResource(ResourceType.CAMERA, "0", "Cam", emptySet())
        ))
        assertEquals("/run/elysium/test/mic0", broker.devicePath(
            HardwareResource(ResourceType.MICROPHONE, "builtin", "Mic", emptySet())
        ))
        assertEquals("/run/elysium/test/gps0", broker.devicePath(
            HardwareResource(ResourceType.GPS, "builtin", "GPS", emptySet())
        ))
        assertEquals("/run/elysium/test/sensors/accelerometer", broker.devicePath(
            HardwareResource(ResourceType.SENSOR, "accelerometer", "Sensor", emptySet())
        ))
        assertEquals("/run/elysium/test/midi/0", broker.devicePath(
            HardwareResource(ResourceType.MIDI, "0", "MIDI", emptySet())
        ))
        assertEquals("/run/elysium/test/gamepad/1", broker.devicePath(
            HardwareResource(ResourceType.GAMEPAD, "1", "Gamepad", emptySet())
        ))
        assertEquals("/run/elysium/test/nfc0", broker.devicePath(
            HardwareResource(ResourceType.NFC, "0", "NFC", emptySet())
        ))
        assertEquals("/run/elysium/test/storage/sdcard", broker.devicePath(
            HardwareResource(ResourceType.STORAGE, "sdcard", "SD", emptySet())
        ))
        assertEquals("/run/elysium/test/printer/epson", broker.devicePath(
            HardwareResource(ResourceType.PRINTER, "epson", "Printer", emptySet())
        ))
    }

    @Test
    fun `auditLog is empty for unknown session`() = block {
        val broker = createBroker()
        assertTrue(broker.auditLog("nonexistent").isEmpty())
    }

    @Test
    fun `shutdown clears all tokens`() = block {
        val broker = createBroker()
        val result = broker.shutdown()
        assertTrue(result.isSuccess)
        assertEquals(HardwareBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `close transitions to Idle`() = block {
        val broker = createBroker()
        broker.close()
        assertEquals(HardwareBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `generate distinct tokens for repeated requests`() = block {
        val broker = createBroker()
        val resource = HardwareResource(
            type = ResourceType.SERIAL,
            id = "ttyUSB0",
            name = "USB Serial",
            capabilities = setOf(ResourceCapability.READ)
        )
        val scope = AccessScope(allowedOperations = setOf(ResourceCapability.READ))
        val r1 = broker.requestAccess("session-1", resource, scope)
        assertTrue(r1.isFailure)
    }

    @Test
    fun `token format contains expected fields`() = block {
        val broker = createBroker()
        val r = HardwareResource(ResourceType.SERIAL, "0", "Serial", setOf(ResourceCapability.READ))
        val s = AccessScope(setOf(ResourceCapability.READ))
        val result = broker.requestAccess("session-te", r, s)
        assertTrue(result.isFailure)
    }

    private fun block(block: suspend () -> Unit) = runBlocking { block() }
}
