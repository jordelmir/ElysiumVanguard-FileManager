package com.elysium.vanguard.core.runtime.distros.ssh

import java.util.Locale

/**
 * PHASE 9.6.6 — X11 display metadata.
 *
 * When the SSH client requests X11 forwarding, the server opens an
 * xauth cookie and tells the X client which display to use. The
 * client side records the DISPLAY string plus the xauth cookie for
 * forwarding back when the embedded VNC server is offline.
 *
 * We don't actually open a TCP listener yet; 9.6.6.2 is the real
 * X11-forward wire-up. This data class is the seam.
 *
 * Phase 9.6.6 — first build; intentionally minimal.
 */
data class X11Display(
    /** `$DISPLAY`-formatted string, e.g. "localhost:10.0" */
    val displayString: String,
    /** Auth cookie (hex). Stored in `$XAUTHORITY` / passed to xauth add. */
    val authCookie: String,
    /** Forwarding port to use when an X client connects to $DISPLAY. */
    val forwardingPort: Int
) {
    val hostPart: String get() = displayString.substringBefore(":")
    val displayNumber: Int get() =
        displayString.substringAfter(":").substringBefore(".")
            .toIntOrNull() ?: 0

    companion object {
        /**
         * Default display we'd hand to a session that has X11
         * forwarding enabled. The X server we're going to run will
         * bind this port once the VNC/embedded-X server lands.
         */
        val LOCAL_DEFAULT = X11Display(
            displayString = "localhost:10.0",
            authCookie = "0".repeat(32),
            forwardingPort = 6010
        )
    }
}

/**
 * PHASE 9.6.6 — X11 forwarder descriptor.
 *
 * The xauth cookie is fed into the SSH session's `xauth add` request
 * so that X clients rendered inside the proot'd distro can connect
 * back to our embedded X server (future Phase 9.6.5 + 9.6.6 + 9.6.7
 * chain).
 *
 * Phase 9.6.6 — first build; intentionally minimal.
 */
data class X11Forwarding(
    val sessionId: String,
    val display: X11Display,
    val cookieHex: String
) {
    val wireFormat: String
        get() = "MIT-MAGIC-COOKIE-1\t$cookieHex"
    val debugTag: String
        get() = "${sessionId.lowercase(Locale.US)}@${display.hostPart}:${display.displayNumber}"
}
