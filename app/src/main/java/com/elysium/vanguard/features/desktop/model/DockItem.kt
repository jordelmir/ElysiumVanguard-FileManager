package com.elysium.vanguard.features.desktop.model

/**
 * A single item in the dock. The dock is the
 * taskbar at the bottom of the desktop; each item
 * is either a **pinned app** (always visible) or
 * a **running window** (visible while the window
 * is open).
 *
 * Phase 1 treats both kinds uniformly; Phase 2
 * may differentiate the rendering (a pinned app
 * shows the icon only; a running window shows
 * the icon + a "running" indicator).
 */
data class DockItem(
    val iconKey: String,
    val label: String,
    val kind: DockItemKind,
    val windowId: String?,
) {
    init {
        require(iconKey.isNotBlank()) { "DockItem iconKey must not be blank" }
        require(label.isNotBlank()) { "DockItem label must not be blank" }
        if (kind == DockItemKind.RUNNING_WINDOW) {
            require(windowId != null) {
                "RUNNING_WINDOW DockItem must have a non-null windowId"
            }
        } else {
            require(windowId == null) {
                "PINNED_APP DockItem must have a null windowId"
            }
        }
    }
}

enum class DockItemKind {
    /** A pinned app (always visible in the dock). */
    PINNED_APP,

    /** A running window (visible while the window is open). */
    RUNNING_WINDOW,
}
