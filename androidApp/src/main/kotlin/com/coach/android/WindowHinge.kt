package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.coach.ui.adaptive.Hinge
import com.coach.ui.adaptive.HingeAxis
import kotlinx.coroutines.flow.collect

/**
 * 折痕来自窗口本身（WindowManager），不按机型猜：竖折痕是「书」（左右两半），
 * 半折横放是桌面姿态（上下两半）。坐标是窗口 dp，布局规则再用它排版；
 * 铰链位置为 0 或没有 `FoldingFeature` 时返回 null，界面按普通窗口排。
 */
@Composable
internal fun rememberWindowHinge(): Hinge? {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val hinge by produceState<Hinge?>(initialValue = null) {
        val activity = context as? ComponentActivity ?: return@produceState
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            // 验收要看「系统到底报了什么」：logcat 里这行的 bounds 就是折痕回执。
            android.util.Log.i("CoachHinge", "features=" + info.displayFeatures.joinToString { "${it.javaClass.simpleName}(${it.bounds})" } +
                " fold=" + feature?.let { "state=${it.state} orientation=${it.orientation} bounds=${it.bounds}" })
            value = when {
                feature == null -> null
                feature.orientation == FoldingFeature.Orientation.VERTICAL ->
                    Hinge(atDp = feature.bounds.left / density, widthDp = feature.bounds.width() / density)
                // 只有真半折才上下分区：摊平横放时折痕仍是横的，但那时是一整块屏幕。
                feature.state == FoldingFeature.State.HALF_OPENED ->
                    Hinge(atDp = feature.bounds.top / density, widthDp = feature.bounds.height() / density, axis = HingeAxis.Horizontal)
                else -> null
            }
        }
    }
    return hinge
}

/**
 * 验收专用：`--ei hingeAtDp 352 --es hingeAxis horizontal|vertical` 直接给一条折痕。
 * 有些模拟器镜像只切姿态、不向应用报 `FoldingFeature`（logcat `CoachHinge` 能看到），
 * 这条注入让同一套布局规则在没有折痕回执的机器上也能截图和录屏；正式构建不认这个参数。
 */
internal fun androidx.activity.ComponentActivity.acceptanceHinge(): Hinge? {
    if (!com.coach.android.BuildConfig.DEBUG) return null
    val at = intent.getIntExtra("hingeAtDp", -1)
    if (at <= 0) return null
    val axis = if (intent.getStringExtra("hingeAxis") == "horizontal") HingeAxis.Horizontal else HingeAxis.Vertical
    return Hinge(atDp = at.toFloat(), axis = axis)
}
