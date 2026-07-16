package com.elysium.vanguard.core.runtime.hardware.broker

/**
 * Phase 18 — the action a guest wants to perform on a piece
 * of host hardware.
 *
 * The broker does not need every possible operation
 * (`usb.controlTransfer(0x21, ...)` etc.) — it needs a
 * coarse label of *what kind* of access the request is.
 * The platform enforcer translates the label into the
 * specific API call.
 *
 * Why an enum: the master order §18 enumerates the access
 * shapes the runtime gates on. Adding a new shape is a code
 * change (with a broker branch and a platform enforcer
 * entry), not a data change.
 */
enum class HardwareAction {
    /** Enumerate / list devices. e.g. `BluetoothAdapter.getBondedDevices()`. */
    LIST,

    /** Read state or data. e.g. `SensorManager.registerListener`. */
    READ,

    /** Modify state. e.g. pair a Bluetooth device, set a sensor sampling rate. */
    WRITE,

    /** Open a long-lived connection / handle. e.g. `UsbManager.openDevice`. */
    CONNECT
}
