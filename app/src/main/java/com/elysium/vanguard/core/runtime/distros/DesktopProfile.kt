package com.elysium.vanguard.core.runtime.distros

enum class DesktopProfile(
    val id: String,
    val displayName: String,
    val packages: List<String>,
    val vncCommand: String,
    val vncPort: Int,
    val startCommand: List<String>,
    val sessionType: String
) {
    XFCE(
        id = "xfce",
        displayName = "XFCE 4.18",
        packages = listOf(
            "xfce4", "xfce4-terminal", "xfce4-screenshooter",
            "tigervnc-standalone-server", "tigervnc-common",
            "xorg-xserver-core", "x11-utils", "xfonts-base",
            "dbus-x11", "dbus", "python3", "policykit-1"
        ),
        vncCommand = "vncserver -geometry %dx%d -depth 24 -localhost :1",
        vncPort = 5901,
        startCommand = listOf("vncserver", "-geometry", "1280x720", "-depth", "24", "-localhost", ":1"),
        sessionType = "x11"
    ),
    LXQT(
        id = "lxqt",
        displayName = "LXQt 2.0",
        packages = listOf(
            "lxqt-core", "lxqt-terminal", "qterminal",
            "tigervnc-standalone-server", "xorg-server",
            "x11-utils", "dbus-x11", "policykit-1"
        ),
        vncCommand = "vncserver -geometry %dx%d -depth 24 -localhost :2",
        vncPort = 5902,
        startCommand = listOf("vncserver", "-geometry", "1280x720", "-depth", "24", "-localhost", ":2"),
        sessionType = "x11"
    ),
    TTY(
        id = "tty",
        displayName = "Terminal Only (No Desktop)",
        packages = emptyList(),
        vncCommand = "",
        vncPort = -1,
        startCommand = emptyList(),
        sessionType = "tty"
    )
}
