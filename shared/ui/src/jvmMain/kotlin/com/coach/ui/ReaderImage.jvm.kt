package com.coach.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun decodeReaderImage(bytes: ByteArray): ImageBitmap? =
    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
