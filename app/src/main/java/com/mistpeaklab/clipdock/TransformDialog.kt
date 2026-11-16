package com.mistpeaklab.clipdock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mistpeaklab.clipdock.util.TextTransforms
import com.mistpeaklab.clipdock.util.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 变换后复制（0.6.1 重做）
 *
 * 设计要点：
 * - 19 种变换分成 5 组，每组一个图标；每种变换一张小卡片：图标 + 名称 + 一行迷你示例。
 * - 打开时先在“这段文字”上把每种变换都试一遍：没效果的变灰、标“无变化”；提取类直接显示找到几个。
 *   用户不用挨个点就知道哪些有用。
 * - 下方常驻对照预览：原文 / 变换后；提取类的结果显示成一个个小块。
 * - 平板是居中的大弹窗（4 列），手机是贴底的弹层（2 列）。键盘：Tab 切换分组，←→ 换一种，Enter 复制。
 */

internal enum class TGroup(val zh: String, val en: String, val icon: Int) {
    CLEAN("清理", "Clean up", R.drawable.ic_gen_tg_clean),
    CASE("大小写", "Case", R.drawable.ic_gen_tg_case),
    CJK("中英排版", "CJK & width", R.drawable.ic_gen_tg_cjk),
    EXTRACT("提取", "Extract", R.drawable.ic_gen_tg_extract),
    LINES("按行处理", "Lines", R.drawable.ic_gen_tg_lines);

    val label get() = tr(zh, en)
}

/** 每种变换：所在分组、图标、迷你示例 */
internal class TSpec(val t: Transform, val group: TGroup, val icon: Int, val example: String)

internal val SPECS = listOf(
    TSpec(Transform.TRIM, TGroup.CLEAN, R.drawable.ic_gen_tf_trim, "␣ab␣ → ab"),
    TSpec(Transform.JOIN_LINES, TGroup.CLEAN, R.drawable.ic_gen_tf_join_lines, "a⏎b → ab"),
    TSpec(Transform.REMOVE_BLANK_LINES, TGroup.CLEAN, R.drawable.ic_gen_tf_blank_lines, "a⏎⏎b → a⏎b"),
    TSpec(Transform.TRIM_LINES, TGroup.CLEAN, R.drawable.ic_gen_tf_trim_lines, "␣a⏎␣b → a⏎b"),
    TSpec(Transform.STRIP_MARKDOWN, TGroup.CLEAN, R.drawable.ic_gen_note, "**粗体** → 粗体"),
    TSpec(Transform.STRIP_TRACKING, TGroup.CLEAN, R.drawable.ic_gen_tf_strip_tracking, "?utm_…= → 删掉"),
    TSpec(Transform.UPPERCASE, TGroup.CASE, R.drawable.ic_gen_tf_upper, "ab → AB"),
    TSpec(Transform.LOWERCASE, TGroup.CASE, R.drawable.ic_gen_tf_lower, "AB → ab"),
    TSpec(Transform.CAPITALIZE, TGroup.CASE, R.drawable.ic_gen_tf_capitalize, "hi you → Hi You"),
    TSpec(Transform.TO_HALF_WIDTH, TGroup.CJK, R.drawable.ic_gen_tf_half_width, "ＡＢ１ → AB1"),
    TSpec(Transform.TO_FULL_WIDTH_PUNCT, TGroup.CJK, R.drawable.ic_gen_tf_full_punct, "好,的 → 好，的"),
    TSpec(Transform.ADD_CJK_SPACE, TGroup.CJK, R.drawable.ic_gen_tf_add_space, "按Win键 → 按 Win 键"),
    TSpec(Transform.REMOVE_CJK_SPACE, TGroup.CJK, R.drawable.ic_gen_tf_remove_space, "按 Win 键 → 按Win键"),
    TSpec(Transform.EXTRACT_NUMBERS, TGroup.EXTRACT, R.drawable.ic_gen_tf_numbers, "单号 SF1428 → 1428"),
    TSpec(Transform.EXTRACT_URLS, TGroup.EXTRACT, R.drawable.ic_gen_tf_urls, "见 a.cn/x → a.cn/x"),
    TSpec(Transform.EXTRACT_EMAILS, TGroup.EXTRACT, R.drawable.ic_gen_tf_emails, "联系 x@y.cn → x@y.cn"),
    TSpec(Transform.DEDUPE_LINES, TGroup.LINES, R.drawable.ic_gen_tf_dedupe, "a⏎a⏎b → a⏎b"),
    TSpec(Transform.SORT_LINES, TGroup.LINES, R.drawable.ic_gen_tf_sort, "b⏎a → a⏎b"),
    TSpec(Transform.QUOTE, TGroup.LINES, R.drawable.ic_gen_tf_quote, "ab → > ab"),
).let { list -> list + Transform.entries.filter { t -> list.none { it.t == t } }.map { TSpec(it, TGroup.CLEAN, R.drawable.ic_gen_transform, "") } }

internal val EXTRACTS = setOf(Transform.EXTRACT_NUMBERS, Transform.EXTRACT_URLS, Transform.EXTRACT_EMAILS)

/** 试算时只用前这么多字：卡片上的“有没有效果”只是提示，真正复制时用全文 */
internal const val PROBE_CHARS = 20_000

internal fun badgeOf(t: Transform, src: String, out: String?): Pair<String, Boolean>? = when {
    out == null -> null
    out == src || (t in EXTRACTS && out.isBlank()) -> tr("无变化", "No change") to false
    t in EXTRACTS -> tr("找到 %d 个", "%d found", out.lines().count { it.isNotBlank() }) to true
    else -> tr("会改动", "Changes") to true
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransformDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val phone = LocalConfiguration.current.screenWidthDp < 600
    val probe = remember(text) { text.take(PROBE_CHARS) }

    // 每种变换在这段文字上的试算结果（后台算，算完卡片上才出现徽标）
    var results by remember(text) { mutableStateOf<Map<Transform, String>>(emptyMap()) }
    LaunchedEffect(text) {
        results = withContext(Dispatchers.Default) {
            SPECS.associate { it.t to runCatching { TextTransforms.apply(it.t, probe) }.getOrDefault(probe) }
        }
    }
    fun useful(t: Transform) = badgeOf(t, probe, results[t])?.second ?: true

    var group by remember { mutableStateOf(TGroup.CLEAN) }
    var chosen by remember { mutableStateOf<Transform?>(null) }
    // 算完以后：默认停在第一个“有效果”的分组和变换上
    LaunchedEffect(results) {
        if (results.isEmpty() || chosen != null) return@LaunchedEffect
        val first = SPECS.firstOrNull { useful(it.t) }
        if (first != null) { group = first.group; chosen = first.t }
    }
    val inGroup = SPECS.filter { it.group == group }
    // 预览用试算结果，复制时对全文重新算
    val preview = chosen?.let { results[it] }

    fun finalText(): String? = chosen?.let { t -> runCatching { TextTransforms.apply(t, text) }.getOrNull() }
    fun copy() { finalText()?.takeIf { it.isNotEmpty() }?.let { ClipReader.write(ctx, it); ctx.toast(tr("已复制", "Copied")); onDismiss() } }
    fun save() {
        finalText()?.takeIf { it.isNotBlank() }?.let {
            ClipStore.add(it, Prefs(ctx).maxItems, fromClipboard = false)
            ctx.toast(tr("已存为新的一条", "Saved as a new item")); onDismiss()
        }
    }

    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val keys = Modifier.focusRequester(fr).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.Tab -> {
                val g = TGroup.entries; val i = g.indexOf(group)
                group = g[(i + if (e.isShiftPressed) g.size - 1 else 1) % g.size]
                chosen = SPECS.firstOrNull { it.group == group && useful(it.t) }?.t ?: SPECS.first { it.group == group }.t
                true
            }
            Key.DirectionRight, Key.DirectionLeft -> {
                val i = inGroup.indexOfFirst { it.t == chosen }
                val d = if (e.key == Key.DirectionRight) 1 else -1
                chosen = inGroup[((if (i < 0) 0 else i + d) + inGroup.size) % inGroup.size].t
                true
            }
            Key.Enter -> { copy(); true }
            Key.Escape -> { onDismiss(); true }
            else -> false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .padding(if (phone) 0.dp else 24.dp),
            contentAlignment = if (phone) Alignment.BottomCenter else Alignment.Center,
        ) {
            val shape = if (phone) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(28.dp)
            Column(
                keys.widthIn(max = 880.dp).fillMaxWidth().heightIn(max = if (phone) 720.dp else 680.dp)
                    .shadow(24.dp, shape).clip(shape).background(cs.surface)
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(start = 24.dp, end = 24.dp, top = if (phone) 10.dp else 22.dp, bottom = 20.dp),
            ) {
                if (phone) Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(cs.outlineVariant))
                // 标题
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("变换后复制", "Transform and copy"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(tr("只改要贴出去的内容，原记录不动", "Only what gets pasted changes; the original stays"),
                            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceVariant).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_gen_close), tr("关闭", "Close"), Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp))
                // 分组
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TGroup.entries.forEach { g ->
                        val on = g == group
                        val dim = results.isNotEmpty() && SPECS.none { it.group == g && useful(it.t) }
                        Row(
                            Modifier.height(40.dp).clip(CircleShape)
                                .background(if (on) Accent else cs.surfaceVariant)
                                .clickable { group = g; chosen = SPECS.firstOrNull { it.group == g && useful(it.t) }?.t ?: SPECS.first { it.group == g }.t }
                                .padding(horizontal = 14.dp)
                                .alpha(if (dim && !on) 0.55f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(g.icon), null, Modifier.size(18.dp), tint = if (on) Color.White else cs.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(g.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) Color.White else cs.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // 变换卡片
                val cols = if (phone) 2 else 4
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    inGroup.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { spec ->
                                TransformCard(spec, badgeOf(spec.t, probe, results[spec.t]), spec.t == chosen, Modifier.weight(1f).fillMaxHeight()) {
                                    if (spec.t == chosen) copy() else chosen = spec.t
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    // 预览
                    Preview(probe, chosen, preview)
                }
                Spacer(Modifier.height(16.dp))
                // 底部
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!phone) {
                        Text(
                            tr("灰色的变换对这段文字没有效果 · Tab 切换分组 · Enter 复制", "Greyed-out ones won't change this text · Tab: group · Enter: copy"),
                            fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                    }
                    val ok = preview != null && preview.isNotBlank()
                    PillButton(tr("存为一条", "Save"), primary = false, enabled = ok, modifier = if (phone) Modifier.weight(1f) else Modifier) { save() }
                    Spacer(Modifier.width(10.dp))
                    PillButton(tr("复制", "Copy"), primary = true, enabled = ok, modifier = if (phone) Modifier.weight(1f) else Modifier) { copy() }
                }
            }
        }
    }
}

@Composable
private fun TransformCard(spec: TSpec, badge: Pair<String, Boolean>?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val off = badge?.second == false
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.06f) else cs.surface)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) Accent else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .alpha(if (off && !selected) 0.45f else 1f)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (off) cs.surfaceVariant else Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(spec.icon), null, Modifier.size(20.dp), tint = if (off) cs.onSurfaceVariant else Accent) }
            Spacer(Modifier.weight(1f))
            if (badge != null) {
                val good = badge.second
                Text(
                    badge.first, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = if (good) Okay else cs.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).background(if (good) Okay.copy(alpha = 0.14f) else cs.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(spec.t.label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (spec.example.isNotEmpty()) {
            Text(spec.example, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(src: String, t: Transform?, out: String?) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row {
            Text(tr("原文", "Before"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.width(60.dp).padding(top = 1.dp))
            Text(src.take(400), fontSize = 13.5.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Text(tr("变换后", "After"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.width(60.dp).padding(top = 3.dp))
            when {
                out == null -> Text(if (t == null) tr("选一种变换", "Pick a transform") else "…", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                out.isBlank() -> Text(tr("（结果是空的）", "(empty result)"), fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                t in EXTRACTS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    out.lines().filter { it.isNotBlank() }.take(40).forEach { s ->
                        Text(s, fontSize = 13.5.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Accent.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                else -> SelectionContainer {
                    Text(out.take(2000), fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun PillButton(label: String, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(46.dp).clip(CircleShape)
            .then(if (primary) Modifier.background(if (enabled) Accent else Accent.copy(alpha = 0.4f)) else Modifier.border(1.5.dp, cs.outlineVariant, CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else if (enabled) cs.onSurface else cs.onSurfaceVariant)
    }
}
