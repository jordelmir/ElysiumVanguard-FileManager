package com.elysium.vanguard.core.runtime.hardware.broker

/**
 * Phase 18 — the host hardware classes a guest can request.
 *
 * Master order §18 says: "the runtime mediates access to host
 * hardware (USB, Bluetooth, NFC, camera, microphone, location,
 * sensors)". Each class is a *bucket* of related device
 * features; the runtime brokers per-class decisions and
 * leaves the platform integration to the
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcer]
 * (Phase 19).
 *
 * Why an enum and not a sealed class: the master order lists
 * the classes explicitly. Adding a new class is a code change
 * (with a default policy in the
 * [com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker]
 * and a corresponding platform enforcer entry), not a data
 * change. A sealed class would be the right shape if the
 * catalog could ship new hardware classes dynamically, but
 * the master order says "first-class, registered at build
 * time".
 */
enum class HardwareClass {
    /** USB host / device controllers, including OTG and HID. */
    USB,

    /** Bluetooth classic + BLE radios, paired devices, GATT. */
    BLUETOOTH,

    /** NFC reader/writer, peer-to-peer, HCE. */
    NFC,

    /** Camera (front + back). */
    CAMERA,

    /** Microphone (input side of the audio chain). */
    MICROPHONE,

    /** GPS / network location. */
    LOCATION,

    /** Accelerometer, gyroscope, magnetometer, barometer, etc. */
    SENSORS
}
