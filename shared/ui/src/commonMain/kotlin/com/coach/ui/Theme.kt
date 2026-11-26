package com.coach.ui

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import coach.design.tokens.ReadingSizes
import com.coach.ui.v7.*

val LocalV7TextScale = staticCompositionLocalOf<MutableState<Float>> { mutableStateOf(1f) }

@Composable
fun CoachTheme(dark: Boolean = isSystemInDarkTheme(), initialTextScale: Float = 1f, content: @Composable () -> Unit) {
    val fonts = rememberV7Fonts()
    val reduced = rememberReducedMotion()
    val textScale = rememberSaveable { mutableStateOf(initialTextScale) }
    val scale = textScale.value
    val typography = remember(fonts, dark, scale) {
        val leading = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val reading = TextStyle(lineHeightStyle = leading, fontFamily = fonts.reading, fontSize = ReadingSizes.body * scale,
            lineHeight = 25.sp * scale,
            fontWeight = if (dark) CoachV7.Weight.bodyDark else CoachV7.Weight.body)
        val ui = TextStyle(lineHeightStyle = leading, fontFamily = fonts.ui, fontSize = ReadingSizes.ui * scale, lineHeight = 18.sp * scale)
        val cap = ui.copy(fontSize = ReadingSizes.caption * scale, lineHeight = 16.sp * scale, fontWeight = CoachV7.Weight.cap, letterSpacing = CoachV7.Tracking.cap)
        val title = reading.copy(fontSize = ReadingSizes.section * scale, lineHeight = 29.sp * scale,
            fontWeight = CoachV7.Weight.title, letterSpacing = CoachV7.Tracking.title)
        Typography(displayLarge = title, displayMedium = title, displaySmall = title,
            headlineLarge = title.copy(fontSize = ReadingSizes.week * scale, lineHeight = 34.sp * scale), headlineMedium = title, headlineSmall = title,
            titleLarge = title,
            titleMedium = reading.copy(fontSize = ReadingSizes.stem * scale, lineHeight = 25.sp * scale, fontWeight = CoachV7.Weight.stem),
            titleSmall = ui.copy(fontSize = ReadingSizes.body * scale, lineHeight = 23.sp * scale, fontWeight = CoachV7.Weight.uiStrong),
            bodyLarge = reading, bodyMedium = reading,
            bodySmall = ui, labelLarge = ui, labelMedium = cap, labelSmall = cap)
    }
    CompositionLocalProvider(LocalV7Fonts provides fonts, LocalV7ReducedMotion provides reduced, LocalV7TextScale provides textScale) {
        com.coach.ui.theme.CoachTheme(dark) {
            MaterialTheme(motionScheme = FiniteMotion, typography = typography, shapes = Shapes(
                extraSmall = RoundedCornerShape(CoachV7.Radius.s), small = RoundedCornerShape(CoachV7.Radius.btn),
                medium = RoundedCornerShape(CoachV7.Radius.m), large = RoundedCornerShape(CoachV7.Radius.sheet),
                extraLarge = RoundedCornerShape(CoachV7.Radius.sheet)), content = content)
        }
    }
}

@Composable fun successColor(): Color = com.coach.ui.theme.CoachTheme.colors.successFg

/**
 * Material3 floats an outlined label by lerping [Typography.bodyLarge] into [Typography.bodySmall].
 * A NaN sample becomes a NaN line height, and [androidx.compose.ui.text.ParagraphStyle] then throws
 * "lineHeight can't be negative (NaN)", which aborts the frame and leaves the field stuck.
 * The spatial spring can emit that sample after a bad frame time; keep the spring, drop the sample.
 */
private object FiniteMotion : MotionScheme {
    private val base = MotionScheme.standard()
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = base.defaultSpatialSpec<T>().dropNaN()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec<T>().dropNaN()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec<T>().dropNaN()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec<T>().dropNaN()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec<T>().dropNaN()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec<T>().dropNaN()
}

private fun <T> FiniteAnimationSpec<T>.dropNaN(): FiniteAnimationSpec<T> = object : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> {
        val wrapped = this@dropNaN.vectorize(converter)
        return object : VectorizedFiniteAnimationSpec<V> {
            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
                wrapped.getDurationNanos(initialValue, targetValue, initialVelocity)

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                wrapped.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    .let { finiteComponents(it, targetValue) }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                wrapped.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity).let { zeroNaN(it) }
        }
    }
}

private fun <V : AnimationVector> finiteComponents(value: V, fallback: V): V {
    fun pick(sample: Float, fallback: Float) = if (sample.isFinite()) sample else fallback
    @Suppress("UNCHECKED_CAST")
    return when (value) {
        is AnimationVector1D -> {
            val fb = fallback as AnimationVector1D
            if (value.value.isFinite()) value else AnimationVector1D(fb.value) as V
        }
        is AnimationVector2D -> {
            val fb = fallback as AnimationVector2D
            if (value.v1.isFinite() && value.v2.isFinite()) value
            else AnimationVector2D(pick(value.v1, fb.v1), pick(value.v2, fb.v2)) as V
        }
        is AnimationVector3D -> {
            val fb = fallback as AnimationVector3D
            if (value.v1.isFinite() && value.v2.isFinite() && value.v3.isFinite()) value
            else AnimationVector3D(pick(value.v1, fb.v1), pick(value.v2, fb.v2), pick(value.v3, fb.v3)) as V
        }
        is AnimationVector4D -> {
            val fb = fallback as AnimationVector4D
            if (value.v1.isFinite() && value.v2.isFinite() && value.v3.isFinite() && value.v4.isFinite()) value
            else AnimationVector4D(pick(value.v1, fb.v1), pick(value.v2, fb.v2), pick(value.v3, fb.v3), pick(value.v4, fb.v4)) as V
        }
    }
}

private fun <V : AnimationVector> zeroNaN(value: V): V {
    fun z(sample: Float) = if (sample.isFinite()) sample else 0f
    @Suppress("UNCHECKED_CAST")
    return when (value) {
        is AnimationVector1D -> if (value.value.isFinite()) value else AnimationVector1D(0f) as V
        is AnimationVector2D -> if (value.v1.isFinite() && value.v2.isFinite()) value else AnimationVector2D(z(value.v1), z(value.v2)) as V
        is AnimationVector3D -> if (value.v1.isFinite() && value.v2.isFinite() && value.v3.isFinite()) value
            else AnimationVector3D(z(value.v1), z(value.v2), z(value.v3)) as V
        is AnimationVector4D -> if (value.v1.isFinite() && value.v2.isFinite() && value.v3.isFinite() && value.v4.isFinite()) value
            else AnimationVector4D(z(value.v1), z(value.v2), z(value.v3), z(value.v4)) as V
    }
}
