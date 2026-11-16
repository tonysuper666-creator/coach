package com.coach.desktop.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*

internal fun androidx.compose.ui.input.key.KeyEvent.toReaderInput(): Pair<KeyInput, Int?>? {
    if (type != KeyEventType.KeyDown) return null
    val k = when (key) {
        Key.DirectionLeft -> ReaderKey.Left
        Key.DirectionRight -> ReaderKey.Right
        Key.DirectionUp -> ReaderKey.Up
        Key.DirectionDown -> ReaderKey.Down
        Key.PageUp -> ReaderKey.PageUp
        Key.PageDown -> ReaderKey.PageDown
        Key.MoveHome -> ReaderKey.Home
        Key.MoveEnd -> ReaderKey.End
        Key.Spacebar -> ReaderKey.Space
        Key.Tab -> ReaderKey.Tab
        Key.Escape -> ReaderKey.Escape
        Key.Enter, Key.NumPadEnter -> ReaderKey.Enter
        Key.Back -> ReaderKey.Back
        Key.N -> ReaderKey.NewNote
        else -> ReaderKey.Other
    }
    val digit = when (key) {
        Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; else -> null
    }
    return KeyInput(k, isShiftPressed, isCtrlPressed || isMetaPressed, isAltPressed) to digit
}
