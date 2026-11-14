package com.mistpeaklab.clipdock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Ok = Color(0xFF2FA866)
private val Warn = Color(0xFFE8833A)
private val Bad = Color(0xFFE5484D)

/** 收录诊断：现在的状态 + 最近每次读取剪贴板的来源和结果 */
@Composable
fun DiagPage(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version by Diag.version.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = remember(tick, version, now / 3000) { Diag.status(ctx) }
    val entries = remember(version) { Diag.entries() }
    val samplingLeft = ((Diag.sampleUntil - now) / 1000).coerceAtLeast(0)

    PageColumn {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("← 返回设置") }

        Card {
            Text("现在的状态", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            status.forEach { s ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(s.name, Modifier.width(168.dp), fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Text(
                        s.value, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
                        color = when (s.ok) { true -> Ok; false -> Warn; null -> cs.onSurface },
                    )
                }
            }
        }

        Card {
            Text("怎么用", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "复制了却没记下来时，回到这里看最下面的列表：\n" +
                    "· 复制的那一刻没有出现新的一行：随贴没察觉到这次复制。请点“事件采样 60 秒”，去那个应用里再复制两三次，回来点“复制诊断信息”发给开发者。\n" +
                    "· 出现了“没读到”：察觉到了，但系统没让读。同样把诊断信息发给开发者。\n" +
                    "· 不想折腾：在那个应用里选中文字，在弹出的菜单里点“存到随贴”（可能收在 ⋮ 里），或用“分享”发给随贴。这两种方式不依赖无障碍。",
                fontSize = 13.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    ClipReader.write(ctx, Diag.report(ctx))
                    Toast.makeText(ctx, "诊断信息已复制，可以粘贴到帖子或邮件里", Toast.LENGTH_SHORT).show()
                }) { Text("复制诊断信息") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    enabled = samplingLeft == 0L && ClipService.instance != null,
                    onClick = { Diag.startSampling(60); now = System.currentTimeMillis() },
                ) { Text(if (samplingLeft > 0) "采样中 $samplingLeft 秒" else "事件采样 60 秒") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { Diag.clear() }) { Text("清空") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "诊断信息里没有你复制的内容，只有时间、触发方式、应用包名和结果。" +
                    "做过事件采样时，还会带上采样期间你点过的按钮上的字（8 个字以内）和窗口标题" +
                    (Diag.sampleCount().takeIf { it > 0 && version >= 0 }?.let { "；当前已采到 $it 行。" } ?: "。"),
                fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
            )
        }

        Card {
            Text("最近 ${entries.size} 条（新的在上）", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) Text("还没有记录。去别的应用里复制一段文字再回来看。", fontSize = 13.sp, color = cs.onSurfaceVariant)
            entries.forEach { e ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(Diag.timeLabel(e.time), Modifier.width(108.dp), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(
                            Diag.triggerName(e.trigger) + " → " + Diag.resultName(e.result) + if (e.count > 1) "  ×${e.count}" else "",
                            fontSize = 13.sp, lineHeight = 18.sp,
                            color = when {
                                Diag.isFailure(e.result) -> Bad
                                e.result == Diag.NEW || e.result == Diag.FALLBACK -> Ok
                                else -> cs.onSurface
                            },
                        )
                        val sub = listOfNotNull(e.pkg, e.detail).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content,
    )
}
