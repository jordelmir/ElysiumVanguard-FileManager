package com.elysium.vanguard.core.fileactions

import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.windows.WindowsVmSpec
import com.elysium.vanguard.core.runtime.windows.WindowsVmState

/**
 * Phase 94 — the [FileActionEnvironment]
 * interface.
 *
 * The view model needs three pieces of
 * information from the platform's runtime:
 *
 * - the list of installed Linux distros
 * - the list of known Windows VM specs
 * - the state of a given VM (is it running?)
 *
 * The runtime exposes this data through
 * concrete `DistroManager` / `WindowsVmManager`
 * classes, but those classes are heavy
 * (large constructors, abstract methods,
 * final classes — `DistroManager` is
 * `final`). Defining a narrow
 * [FileActionEnvironment] interface in the
 * file-actions package lets the view model
 * depend on the values it actually reads,
 * not on the entire manager. Tests pass a
 * fake environment; production wires the
 * real one via the [com.elysium.vanguard.core.fileactions.FileActionModule].
 *
 * **Why not just use the managers?**
 *
 * - The `DistroManager` constructor is
 *   heavy (`baseDir`, `storageProvider`,
 *   `distroResolver`, `prootTemplate`, etc.).
 *   Tests would need to stub 5+ parameters.
 *
 * - The `WindowsVmManager` is a final class
 *   with a complex backend interface. The
 *   test stub would be 50+ lines.
 *
 * - The view model only reads 3 things
 *   (`listInstalled` + `listSpecs` +
 *   `getState`). A narrow interface is the
 *   right abstraction.
 */
interface FileActionEnvironment {
    fun installedDistros(): List<DistroInstallation>
    fun windowsVmSpecs(): List<WindowsVmSpec>
    fun windowsVmState(vmId: String): WindowsVmState
}
