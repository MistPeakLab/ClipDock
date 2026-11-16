package com.mistpeaklab.clipdock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistpeaklab.clipdock.data.ItemKind
import com.mistpeaklab.clipdock.data.ItemRepo
import com.mistpeaklab.clipdock.data.RestoreResult
import com.mistpeaklab.clipdock.data.TrashRow

/** 回收站：删除的、超出上限被淘汰的记录，保留 [ItemRepo.TRASH_DAYS] 天 */
@Composable
fun TrashPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var rows by remember { mutableStateOf<List<TrashRow>?>(null) } // null = 还在读
    var confirmEmpty by remember { mutableStateOf(false) }
    val items by ClipStore.items.collectAsStateWithLifecycle()
    fun reload() { ClipStore.trash { rows = it } }
    // 列表一有变化（删除、淘汰、恢复）就重新读一次
    LaunchedEffect(items) { reload() }
    val now = remember(rows) { System.currentTimeMillis() }
    val list = rows

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        val canEmpty = !list.isNullOrEmpty()
        SubHeader(tr("回收站", "Trash"), onBack, R.drawable.ic_gen_trash,
            tr("删除的记录、超出条数上限的旧记录先放在这里，%d 天后自动清理。", "Deleted items and old items over the limit stay here for %d days.", ItemRepo.TRASH_DAYS)) {
            if (canEmpty) PillButton(tr("清空回收站", "Empty trash"), primary = false) { confirmEmpty = true }
        }
        Spacer(Modifier.height(12.dp))
        when {
            list == null -> Text(tr("正在读取…", "Loading…"), color = cs.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            list.isEmpty() -> Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_empty), null, Modifier.size(72.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.45f))
                Spacer(Modifier.height(10.dp))
                Text(tr("回收站是空的", "The trash is empty"), color = cs.onSurfaceVariant)
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.widthIn(max = 900.dp),
            ) {
                items(list, key = { it.id }) { r ->
                    TrashCard(
                        r, now,
                        onRestore = {
                            ClipStore.restore(r.id) { res ->
                                val msg = when (res) {
                                    is RestoreResult.Ok -> tr("已恢复", "Restored")
                                    RestoreResult.Duplicate -> tr("列表里已经有同样的内容，这条不用恢复", "The same content is already in the list")
                                    RestoreResult.Gone -> tr("这条已经不在回收站里了", "This item is no longer in the trash")
                                }
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                reload()
                            }
                        },
                        onPurge = { ClipStore.purge(listOf(r.id)) { reload() } },
                    )
                }
            }
        }
    } }

    if (confirmEmpty && list != null) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text(tr("清空回收站？", "Empty the trash?")) },
        text = { Text(tr("里面的 %d 条内容会被彻底删除，不能再恢复。", "%d items will be deleted permanently.", list.size)) },
        confirmButton = {
            TextButton({
                confirmEmpty = false
                ClipStore.purge(list.map { it.id }) {
                    reload()
                    Toast.makeText(ctx, tr("回收站已清空", "Trash emptied"), Toast.LENGTH_SHORT).show()
                }
            }) { Text(tr("清空", "Empty"), color = Danger) }
        },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun TrashCard(r: TrashRow, now: Long, onRestore: () -> Unit, onPurge: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val left = (ItemRepo.TRASH_DAYS - (now - r.whenMs) / 86_400_000L).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Text(
            if (r.hidden) tr("🔒 已隐藏的内容", "🔒 Hidden item") else r.preview.ifEmpty { tr("（空白）", "(blank)") },
            fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, color = cs.onSurface, modifier = Modifier.padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(
                    if (r.kind == ItemKind.PHRASE) tr("常用语", "Phrase") else tr("记录", "Item"),
                    tr("%d 字", "%d chars", r.chars),
                    if (r.evicted) tr("超出上限", "Over the limit") else tr("已删除", "Deleted"),
                    timeLabel(r.whenMs),
                    if (left > 0) tr("还剩 %d 天", "%d days left", left) else tr("即将清理", "Removing soon"),
                ).joinToString(" · "),
                fontSize = 11.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text(tr("恢复", "Restore")) }
            TextButton(onClick = onPurge) { Text(tr("彻底删除", "Delete"), color = Danger) }
        }
    }
}
