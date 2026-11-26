package com.coach.ui.pageturn

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/*
 * 卷页的几何：把翻起的那张纸当成沿一条折线对折。
 *
 * 坐标系是「翻起页」自己的：原点在书脊上沿，x 向外（书口方向），页宽 w、页高 h。
 * 往后翻和往前翻用同一套几何：往前翻 = 当前右页（单页时是当前页）从书口卷向书脊；
 * 往回翻 = 上一张的同一过程倒着播，所以页角 P 从 (-w, cy) 走回 (w, cy)。
 *
 * 页角原位 C = (w, cy)，cy 是上沿 0 或下沿 h。被拖着的页角在 P，折线是 C、P 连线的中垂线。
 * 折线靠 C 的一侧（removed）被掀起，露出下一页；掀起的部分翻过来落在折线另一侧（flap），
 * 显示的是这张纸的背面。
 */

/** 页角 P 走到哪了，换成「卷过去多少」0..1：P 在书口 = 0，P 翻到书脊另一侧 -w = 1。 */
fun curlProgressAt(px: Float, w: Float): Float =
    if (w <= 0f) 0f else ((w - px) / (2f * w)).coerceIn(0f, 1f)

/** 拖动时的翻页进度：往前翻就是 [curlProgressAt]，往回翻是它倒过来。 */
fun curlDragProgress(px: Float, w: Float, forward: Boolean): Float {
    val p = curlProgressAt(px, w)
    return if (forward) p else 1f - p
}

/**
 * 纸钉在书脊上，页角不能被拉得离书脊比纸还远：
 * 到同一条边上的书脊端点不超过页宽，到对角的书脊端点不超过对角线。
 */
fun constrainCurlPoint(p: Offset, w: Float, h: Float, top: Boolean): Offset {
    val cy = if (top) 0f else h
    var q = p
    val near = Offset(0f, cy)
    val dn = (q - near).getDistance()
    if (dn > w && dn > 0f) q = near + (q - near) * (w / dn)
    val far = Offset(0f, h - cy)
    val diag = hypot(w, h)
    val df = (q - far).getDistance()
    if (df > diag && df > 0f) q = far + (q - far) * (diag / df)
    return q
}

/**
 * 进度 p（0..1，书口 -> 翻过书脊）对应的页角位置（像素，翻起页坐标）。
 * [anchor] 是手拖着时页角所在（按 w、h 归一）；松手后的动画从它出发走到终点或退回原位，
 * 中途不跳。没有 anchor（键盘、点页边、滚轮）时走一条从下角微微抬起再落下的弧线。
 */
fun curlPointAt(p: Float, w: Float, h: Float, top: Boolean, anchor: Offset? = null): Offset {
    val t = p.coerceIn(0f, 1f)
    val cy = if (top) 0f else h
    val start = Offset(w, cy)
    val end = Offset(-w, cy)
    val raw = if (anchor == null) {
        val lift = h * CURL_LIFT * sin(PI.toFloat() * t)
        Offset(w - 2f * w * t, if (top) cy + lift else cy - lift)
    } else {
        val a = Offset(anchor.x * w, anchor.y * h)
        val pa = curlProgressAt(a.x, w)
        when {
            t >= pa -> if (pa >= 1f) a else lerpOffset(a, end, (t - pa) / (1f - pa))
            pa <= 0f -> a
            else -> lerpOffset(start, a, t / pa)
        }
    }
    return constrainCurlPoint(raw, w, h, top)
}

/** 一次对折的结果：front 留在原处的部分，removed 被掀起的部分，flap 掀起后翻过来所在的位置。 */
class CurlFold(
    val front: List<Offset>,
    val removed: List<Offset>,
    val flap: List<Offset>,
    /** 折线上一点（C、P 中点）。 */
    val mid: Offset,
    /** 折线法向，单位向量，指向页角原位 C。 */
    val normal: Offset,
    /** 折线到页角原位的距离，像素；0 = 还没掀起。 */
    val depth: Float,
)

fun curlFold(pointer: Offset, w: Float, h: Float, top: Boolean): CurlFold {
    val c = Offset(w, if (top) 0f else h)
    val rect = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
    val d = c - pointer
    val len = d.getDistance()
    if (len < 0.5f) return CurlFold(rect, emptyList(), emptyList(), c, Offset(1f, 0f), 0f)
    val n = d / len
    val m = (c + pointer) / 2f
    val removed = clipHalfPlane(rect, m, n, keepTowardNormal = true)
    return CurlFold(
        front = clipHalfPlane(rect, m, n, keepTowardNormal = false),
        removed = removed,
        flap = removed.map { reflectAcross(it, m, n) },
        mid = m, normal = n, depth = len / 2f,
    )
}

/** 凸多边形被直线切一刀，留下法向一侧（或另一侧）。 */
fun clipHalfPlane(poly: List<Offset>, m: Offset, n: Offset, keepTowardNormal: Boolean): List<Offset> {
    if (poly.isEmpty()) return poly
    val s = if (keepTowardNormal) 1f else -1f
    fun side(p: Offset) = s * ((p.x - m.x) * n.x + (p.y - m.y) * n.y)
    val out = ArrayList<Offset>(poly.size + 2)
    for (i in poly.indices) {
        val cur = poly[i]
        val prev = poly[(i + poly.size - 1) % poly.size]
        val fc = side(cur)
        val fp = side(prev)
        if (fc >= 0f) {
            if (fp < 0f) out += lerpOffset(prev, cur, fp / (fp - fc))
            out += cur
        } else if (fp >= 0f) {
            out += lerpOffset(prev, cur, fp / (fp - fc))
        }
    }
    return out
}

fun reflectAcross(p: Offset, m: Offset, n: Offset): Offset {
    val k = 2f * ((p.x - m.x) * n.x + (p.y - m.y) * n.y)
    return Offset(p.x - k * n.x, p.y - k * n.y)
}

/**
 * 掀起部分背面内容的变换：页内坐标 -> 屏幕（翻起页坐标）。
 * [otherPage] = true 时背面是另一页（双页对开时下一张的左页），先左右翻再沿折线镜像，
 * 两次反射合起来是刚体转动，字是正的；false 时背面是同一张纸的反面（单页），只沿折线镜像，字是反的。
 */
fun curlBackMatrix(m: Offset, n: Offset, w: Float, otherPage: Boolean): Matrix {
    // 沿折线反射：X' = A·X + t，A = I - 2nnᵀ，t = 2(m·n)n
    val a00 = 1f - 2f * n.x * n.x
    val a01 = -2f * n.x * n.y
    val a11 = 1f - 2f * n.y * n.y
    val mn = 2f * (m.x * n.x + m.y * n.y)
    val tx = mn * n.x
    val ty = mn * n.y
    // 先左右翻 Q -> (w - Q.x, Q.y)，再反射：线性部分 A 的第一列取反，平移多出 A·(w, 0)
    return if (otherPage) matrixOf(-a00, a01, -a01, a11, tx + a00 * w, ty + a01 * w)
    else matrixOf(a00, a01, a01, a11, tx, ty)
}

/** x' = s00·x + s01·y + tx，y' = s10·x + s11·y + ty。 */
private fun matrixOf(s00: Float, s01: Float, s10: Float, s11: Float, tx: Float, ty: Float): Matrix {
    val mx = Matrix()
    mx.values[0] = s00; mx.values[4] = s01; mx.values[12] = tx
    mx.values[1] = s10; mx.values[5] = s11; mx.values[13] = ty
    return mx
}

private fun lerpOffset(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

/** 自动翻页时页角抬起的高度，占页高的比例。 */
private const val CURL_LIFT = 0.10f
