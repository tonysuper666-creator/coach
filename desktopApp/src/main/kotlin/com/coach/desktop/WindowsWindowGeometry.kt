package com.coach.desktop

import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit

/** AWT screen bounds and insets are already logical units, including on HiDPI Windows. */
internal fun windowsInitialWindowBounds(
    screen: Rectangle,
    insets: Insets,
): Rectangle {
    val work = Rectangle(
        screen.x + insets.left, screen.y + insets.top,
        (screen.width - insets.left - insets.right).coerceAtLeast(1),
        (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
    // Keep a normal, movable window, including its decorations, inside the taskbar work area.
    val width = minOf(1440, (work.width - 48).coerceAtLeast(1))
    val height = minOf(900, (work.height - 48).coerceAtLeast(1))
    return Rectangle(work.x + (work.width - width) / 2, work.y + (work.height - height) / 2, width, height)
}

internal fun windowsInitialWindowBounds(): Rectangle {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration
    return windowsInitialWindowBounds(configuration.bounds, Toolkit.getDefaultToolkit().getScreenInsets(configuration))
}
