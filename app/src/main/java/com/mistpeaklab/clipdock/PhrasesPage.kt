package com.mistpeaklab.clipdock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 常用语：手动维护，永不过期，不受“清空”影响；可编辑、可排序 */
@Composable
fun PhrasesPage(all: List<ClipItem>, query: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<ClipItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = if (query.isBlank()) all else all.filter { it.text.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
 Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
     Text(
  "邮箱、地址、固定回复……放在这里永不过期。悬浮面板按 Tab 切到“常用语”即可快速选用。",
  fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
     )
     Spacer(Modifier.width(12.dp))
     Button(onClick = { adding = true }) {
  Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp)); Text("新增常用语")
     }
 }
 if (list.isEmpty()) {
     Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
  Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(64.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.5f))
  Spacer(Modifier.height(12.dp))
  Text(if (query.isNotBlank()) "没有找到包含“$query”的常用语" else "还没有常用语。点右上角新增，或在记录卡片上点 ＋ 加入",
      color = cs.onSurfaceVariant)
     }
     return@Column
 }
 LazyColumn(
     verticalArrangement = Arrangement.spacedBy(8.dp),
     contentPadding = PaddingValues(bottom = 24.dp),
     modifier = Modifier.widthIn(max = 900.dp),
 ) {
     itemsIndexed(list, key = { _, it -> it.id }) { i, p ->
  Row(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface)
   .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
   .clickable {
       ClipReader.write(ctx, TextTools.prepare(Prefs(ctx), p.text))
       Toast.makeText(ctx, "已复制，按 Ctrl+V 粘贴", Toast.LENGTH_SHORT).show()
   }
   .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
      Text("${i + 1}", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(24.dp))
      Text(p.text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 3,
   overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(8.dp))
      if (query.isBlank()) {
   Act(R.drawable.ic_up, "上移", i > 0) { ClipStore.movePhrase(p.id, -1) }
   Act(R.drawable.ic_down, "下移", i < list.lastIndex) { ClipStore.movePhrase(p.id, 1) }
      }
      Act(R.drawable.ic_edit, "编辑", true) { editing = p }
      Act(R.drawable.ic_delete, "删除", true) { ClipStore.delete(p.id) }
  }
     }
 }
    }

    if (adding || editing != null) {
 PhraseDialog(editing?.text ?: "", onDismiss = { adding = false; editing = null }) { text ->
     val e = editing
     if (e != null) ClipStore.updateText(e.id, text)
     else if (!ClipStore.addPhrase(text)) Toast.makeText(ctx, "常用语里已有这条", Toast.LENGTH_SHORT).show()
     adding = false; editing = null
 }
    }
}

@Composable
private fun Act(res: Int, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
 Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
 contentAlignment = Alignment.Center,
    ) {
 Icon(painterResource(res), desc, Modifier.size(18.dp),
     tint = if (enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.25f))
    }
}

@Composable
private fun PhraseDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
 onDismissRequest = onDismiss,
 title = { Text(if (initial.isEmpty()) "新增常用语" else "编辑常用语") },
 text = {
     OutlinedTextField(
  text, { text = it }, minLines = 3, maxLines = 8,
  placeholder = { Text("例如：常用邮箱、收货地址、固定回复") },
  modifier = Modifier.fillMaxWidth(),
     )
 },
 confirmButton = { TextButton({ onSave(text) }, enabled = text.isNotBlank()) { Text("保存") } },
 dismissButton = { TextButton(onDismiss) { Text("取消", color = Color.Gray) } },
    )
}
