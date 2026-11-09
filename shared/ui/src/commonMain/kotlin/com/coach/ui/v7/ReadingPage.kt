package com.coach.ui.v7

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coach.design.tokens.CoachV7
import com.coach.client.*
import com.coach.ui.*
import com.coach.ui.theme.CoachTheme

/** Forward alignment followed by a reverse pass keeps the last note within the paper. */
fun balancedMarginOffsets(desired: List<Int>, heights: List<Int>, gap: Int, available: Int,
    header: Int = 0, minimum: Int = 0, gapAfter: List<Int> = emptyList()): List<Int> {
    var cursor = header
    val ys = heights.mapIndexed { i, height ->
        val y = maxOf(cursor, desired.getOrNull(i) ?: cursor)
        cursor = y + height + (gapAfter.getOrNull(i) ?: gap)
        y
    }.toMutableList()
    var bottom = available
    for (i in heights.indices.reversed()) {
        ys[i] = minOf(ys[i], bottom - heights[i]).coerceAtLeast(if (minimum > 0) minimum else Int.MIN_VALUE)
        bottom = ys[i] - (gapAfter.getOrNull(i - 1) ?: gap)
    }
    val shift = -(ys.firstOrNull() ?: 0).coerceAtMost(0)
    return ys.map { it + shift }
}

@Composable
internal fun Modifier.railAnchor(key: String): Modifier {
    val anchors = LocalMarginAnchors.current
    return onGloballyPositioned { anchors?.positions?.set(key, it.positionInRoot().y) }
}

@Composable
internal fun AlignedRail(keys: List<String?>, gap: Dp = 12.dp, finalGap: Dp = gap, content: @Composable () -> Unit) {
    val anchors = LocalMarginAnchors.current
    var origin by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot().y }) {
        val viewportHeight = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("页边", style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.8.sp), color = CoachTheme.colors.fgSecondary)
            Layout(content = content, modifier = Modifier.fillMaxWidth().testTag("v7-aligned-rail")) { measurables, constraints ->
                val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
                val gaps = children.indices.map { i -> (if (i == children.lastIndex - 1) finalGap else gap).roundToPx() }
                val minimumHeight = children.sumOf { it.height } + gaps.dropLast(1).sum() + 17.dp.roundToPx()
                val viewport = viewportHeight.roundToPx()
                val height = if (minimumHeight <= viewport + gap.roundToPx()) viewport else minimumHeight
                val desired = children.indices.map { i -> keys.getOrNull(i)?.let { anchors?.positions?.get(it) }?.let { (it - origin - 4.dp.toPx()).toInt() } ?: 0 }
                val ys = balancedMarginOffsets(desired, children.map { it.height }, gap.roundToPx(), height,
                    header = 28.8.dp.roundToPx(), minimum = 17.dp.roundToPx(), gapAfter = gaps)
                layout(constraints.maxWidth, height) { children.forEachIndexed { i, child -> child.placeRelative(0, ys[i]) } }
            }
        }
    }
}


@Composable
internal fun NoteCard(label: String, text: String, personal: Boolean = false) {
    val c = CoachTheme.colors
    PaperCard(contentSpacing = 4.dp) {
        Text(buildAnnotatedString {
            append(label)
            label.indexOf('★').takeIf { it >= 0 }?.let { addStyle(SpanStyle(color = c.dangerFg), it, it + 1) }
        }, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 19.8.sp), color = c.fgSecondary)
        Text(text, style = if (personal) MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalV7Fonts.current.note, fontSize = 16.sp, lineHeight = 25.6.sp) else MaterialTheme.typography.bodyMedium,
            color = if (personal) c.fgSecondary else c.fgPrimary)
    }
}

@Composable
internal fun SourceThumbnail(details: V7PageDetails, onOpen: () -> Unit) {
    PaperCard(contentSpacing = 8.dp) {
        Text(details.sourceLabel, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 19.8.sp), color = CoachTheme.colors.fgSecondary)
        if (details.sourceSvg.isNotBlank()) FigureSvg(details.sourceSvg, Modifier.clip(RoundedCornerShape(6.dp)).testTag("v7-source-thumbnail"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(details.thumbnailCaption.ifBlank { details.sourceCaption }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("打开原页 →", Modifier.clickable(onClick = onOpen), style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.successFg)
        }
    }
}

@Composable
private fun SelectionMenu(onAsk: () -> Unit) {
    val c = CoachTheme.colors
    Column(Modifier.fillMaxWidth().paperShadow("menu", c.dark).clip(RoundedCornerShape(10.dp))
        .background(if (c.dark) CoachV7.Dark.menuBg else CoachV7.Light.menuBg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("选中这一句", style = MaterialTheme.typography.labelMedium, color = c.fgSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("● 划线", Modifier.clickable(onClick = onAsk).padding(vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            Text("一句批注", Modifier.clickable(onClick = onAsk).padding(vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider(color = c.borderSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("让 AI 再讲一遍", Modifier.clickable(onClick = onAsk).padding(vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            Text("举个例子", Modifier.clickable(onClick = onAsk).padding(vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider(color = c.borderSubtle)
        Text("挂到 Obsidian ↗", Modifier.clickable(onClick = onAsk).padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = c.fgSecondary)
    }

}

@Composable
private fun MarginQuestion(q: V7MarginQuestion, onQuiz: () -> Unit) {
    PaperCard(contentSpacing = 0.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("这一节 · 第 1 题 / ${q.count}", style = MaterialTheme.typography.labelMedium)
            Text("不计时", style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(q.stem, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        q.options.forEachIndexed { i, option ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (i == q.answer) CoachTheme.colors.successBg else if (CoachTheme.colors.dark) CoachV7.Dark.optBg else Color.White)
                .clickable(onClick = onQuiz).padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(('A' + i).toString(), style = MaterialTheme.typography.labelMedium)
                Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp))
                if (i == q.answer) Text("✓", color = CoachTheme.colors.successFg, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(q.explanation, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(q.source, style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.successFg)
            Text("第 2 题 →", Modifier.clickable(onClick = onQuiz), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MarkedParagraph(raw: String, d: V7PageDetails) {
    val c = CoachTheme.colors
    val plain = raw.replace("**", "").replace(Regex("\\[\\^([0-9]+)]"), "$1")
    val b = AnnotatedString.Builder(plain)
    plain.forEachIndexed { i, ch -> if (ch == '★') b.addStyle(SpanStyle(color = c.dangerFg, fontSize = .62.em, baselineShift = BaselineShift.Superscript), i, i + 1) }
    Regex("[¹²³]").findAll(plain).forEach { b.addStyle(SpanStyle(color = c.fgSecondary, fontFamily = LocalV7Fonts.current.numbers), it.range.first, it.range.last + 1) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(b.toAnnotatedString(), Modifier.fillMaxWidth().drawWithContent {
        fun range(needle: String, draw: (Int, TextLayoutResult) -> Unit) {
            val start = if (needle.isBlank()) -1 else plain.indexOf(needle)
            if (start >= 0) layout?.let { result -> (start until start + needle.length).forEach { draw(it, result) } }
        }
        range(d.highlight) { i, result -> val box = result.getBoundingBox(i); drawRect(if (c.dark) CoachV7.Dark.hl else CoachV7.Light.hl, Offset(box.left, box.center.y), androidx.compose.ui.geometry.Size(box.width, box.height / 2)) }
        range(d.selection) { i, result -> val box = result.getBoundingBox(i); drawRect(if (c.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg, box.topLeft, box.size); drawLine(c.dangerFg, Offset(box.left, box.bottom - 1.dp.toPx()), Offset(box.right, box.bottom - 1.dp.toPx()), 2.dp.toPx()) }
        drawContent()
        range(d.emphasis) { i, result -> val box = result.getBoundingBox(i); drawCircle(c.fgSecondary, 1.dp.toPx(), Offset(box.center.x, result.getLineBaseline(result.getLineForOffset(i)) + 5.dp.toPx())) }
    }, style = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Justify), onTextLayout = { layout = it })
}

@Composable
internal fun ReadingPage(c: CoachController, kp: KpRow, view: CourseView, text: String, focus: Boolean, onFocus: () -> Unit,
    onBack: (() -> Unit)?, onAsk: () -> Unit, onQuiz: () -> Unit, onSource: () -> Unit, weekActions: WeekActions?) {
    val d = LocalV7PageDetails.current
    val railAnchors = remember(kp.id) { MarginAnchors() }
    val scoped = view.projection.studyScope?.week
    val week = scoped?.takeIf { n -> view.weekKps(n).any { it.id == kp.id } }
        ?: view.weeks.firstOrNull { n -> view.weekKps(n).any { it.id == kp.id } }
    val points = week?.let(view::weekKps).orEmpty().ifEmpty { listOf(kp) }
    val index = points.indexOfFirst { it.id == kp.id }.coerceAtLeast(0)
    fun label(at: Int) = points.getOrNull(at)?.let { "${week?.let { n -> "$n." }.orEmpty()}${at + 1} ${splitTitle(it.title).second}" }.orEmpty()
    CompositionLocalProvider(LocalMarginAnchors provides railAnchors) {
    Desk(crumb = d.course.ifBlank { kp.module.ifBlank { c.courseId } } + week?.let { " · 第 $it 周" }.orEmpty(), focus = focus, onFocus = onFocus,
        status = d.readStatus.ifBlank { "上次读到" }, compactStatus = "${index + 1} / ${points.size}", onBack = onBack, backLabel = "← 本周",
        directory = { WeekDirectory(view, week, weekActions?.let { it::select }) },
        margin = {
            if (d.lecture.isNotBlank() || d.personalNote.isNotBlank()) AlignedRail(listOf("p0", "p1", "p1", "p3", "end")) {
                if (d.lecture.isNotBlank()) NoteCard("★  1  ${d.lectureLabel}", d.lecture)
                if (d.selection.isNotBlank()) SelectionMenu(onAsk)
                if (d.sourceLabel.isNotBlank()) PaperCard(contentSpacing = 4.dp) {
                    Text("2  ${d.sourceLabel}", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.sourceCaption, style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
                        Text("打开原页 →", Modifier.clickable(onClick = onSource), style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.successFg)
                    }
                }
                if (d.personalNote.isNotBlank()) NoteCard("3  我的批注", d.personalNote, personal = true)
                d.question?.let { MarginQuestion(it, onQuiz) }
            } else {
                val notes = footnotes(text)
                AlignedRail(notes.map { "reading:${kp.id}:${it.id}" } + listOf(null)) {
                    notes.forEach { NoteCard("${it.number}  ${it.label}", it.text) }
                    if (kp.reference.isNotBlank()) NoteCard("出处", kp.reference)
                }
            }
        }, footer = {
            Text(label(index - 1).let { if (it.isNotBlank()) "‹  $it" else "" }, Modifier.weight(1f).clickable { points.getOrNull(index - 1)?.let { weekActions?.onOpen(it.id) } }, style = MaterialTheme.typography.labelLarge)
            Text("${index + 1} / ${points.size}", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
            Text(label(index + 1).let { if (it.isNotBlank()) "$it  ›" else "" }, Modifier.weight(1f).clickable { points.getOrNull(index + 1)?.let { weekActions?.onOpen(it.id) } }, textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        }) {
        val m = LocalDeskMetrics.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(Modifier.widthIn(max = CoachV7.Layout.colw + m.horizontalPadding * 2).fillMaxSize().align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(horizontal = m.horizontalPadding).padding(top = m.topPadding, bottom = 100.dp)) {
                PageHeader("本周要点 · ${index + 1} / ${points.size}", splitTitle(kp.title).second, d.deck, d.meta.ifBlank { kp.reference })
                if (d.highlight.isNotBlank() || d.selection.isNotBlank()) {
                    withoutFootnoteDefinitions(text).trim().split(Regex("\\n\\s*\\n")).forEachIndexed { i, paragraph ->
                        Box(Modifier.railAnchor("p$i")) { MarkedParagraph(paragraph.replace('\n', ' '), d) }
                        Spacer(Modifier.height(15.3.dp))
                        if (!m.inner && !focus && i == 0 && d.lecture.isNotBlank()) { NoteCard("★ ${d.lectureLabel}", d.lecture); Spacer(Modifier.height(15.3.dp)) }
                        if (!m.inner && !focus && i == 2 && d.personalNote.isNotBlank()) { NoteCard("我的批注", d.personalNote, true); Spacer(Modifier.height(15.3.dp)) }
                    }
                } else Markdown(text, true, "reading:${kp.id}", c.math)
                Spacer(Modifier.height(8.7.dp))
                Row(Modifier.widthIn(max = CoachV7.Layout.colw * (CoachV7.Size.ui.value / CoachV7.Size.body.value)).fillMaxWidth().railAnchor("end"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (d.question != null && m.inner && !focus) "读完这一节了。第 1 题就在右边页边，想做再做。" else "读完这一节了。追问…", Modifier.clickable(onClick = onAsk).testTag("v7-reading-ask"), style = MaterialTheme.typography.labelLarge, color = CoachTheme.colors.fgSecondary)
                    Text("整节 ${d.question?.count ?: ""} 题 →", Modifier.clickable(onClick = onQuiz).testTag("v7-reading-quiz"), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (!m.inner && !focus) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (d.selection.isNotBlank()) 120.dp else 40.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, CoachTheme.colors.surface1))))
            if (!m.inner && !focus && d.selection.isNotBlank()) Row(Modifier.align(Alignment.BottomCenter)
                .padding(bottom = if (m.window == DeskWindow.OuterLandscape) 16.dp else 12.dp)
                .width(minOf(640.dp, maxWidth - if (m.window == DeskWindow.OuterLandscape) 48.dp else 32.dp)).height(52.dp)
                .paperShadow("menu", CoachTheme.colors.dark).clip(RoundedCornerShape(10.dp)).background(if (CoachTheme.colors.dark) CoachV7.Dark.menuBg else Color.White).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("● 划线", "一句批注", "再讲一遍", "举个例子", "挂到 Obsidian").forEachIndexed { i, label ->
                    if (i == 2 || i == 4) Box(Modifier.width(1.dp).height(18.dp).background(CoachTheme.colors.borderSubtle))
                    Text(label, Modifier.clickable(onClick = onAsk), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
}
