package com.mistpeaklab.clipdock

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) } // 改设置后触发重组
    fun upd() { v++ }
    var recording by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    key(v) {
 PageColumn {
     Group("唤起") {
  Toggle("启用快捷键唤起", "需要增强模式（无障碍服务）", prefs.hotkeyEnabled) { prefs.hotkeyEnabled = it; upd() }
  Clickable("唤起快捷键", "Win + V 若被系统占用，可换成 Ctrl + Shift + V 或 Alt + V", prefs.hotkey.label()) {
      recording = true
  }
  Toggle("点桌面图标直接唤起悬浮面板", "关闭时打开主界面。长按桌面图标也能选“悬浮面板”", prefs.iconOpensPanel) {
      prefs.iconOpensPanel = it; upd()
  }
     }
     Group("关闭方式") {
  Toggle("选中一条后自动收起", null, prefs.closeOnSelect) { prefs.closeOnSelect = it; upd() }
  Toggle("按 Esc 关闭", null, prefs.closeOnEsc) { prefs.closeOnEsc = it; upd() }
  Toggle("点面板外空白处关闭", null, prefs.closeOnOutside) { prefs.closeOnOutside = it; upd() }
  Toggle("再按一次唤起快捷键关闭", null, prefs.closeOnHotkey) { prefs.closeOnHotkey = it; upd() }
  Toggle("切换到其他应用时关闭", null, prefs.closeOnAppSwitch) { prefs.closeOnAppSwitch = it; upd() }
  Hint("全部关掉后，只能点面板右上角的 × 关闭。")
     }
     Group("悬浮面板") {
  Choice("每条显示行数", listOf(1, 2, 3, 4).map { "$it 行" }, prefs.previewLines - 1) { prefs.previewLines = it + 1; upd() }
  Clickable("面板大小与位置", "拖动面板右下角可缩放，拖动标题栏可移动", "恢复默认") {
      prefs.resetPanelPosition(); Toast.makeText(ctx, "已恢复默认大小和位置", Toast.LENGTH_SHORT).show()
  }
  Choice("主题", listOf("跟随系统", "浅色", "深色"), prefs.theme) { prefs.theme = it; upd() }
     }
     Group("粘贴") {
  Toggle("Enter / 点击后直接粘贴到输入框", "需要增强模式。关闭时 Enter 和点击只复制；Ctrl+V 与“粘贴”按钮始终直接粘贴，Ctrl+C 始终只复制", prefs.autoPaste) { prefs.autoPaste = it; upd() }
         Toggle("粘贴前清理格式", "去掉零宽字符、行尾空格、多余空行和首尾空白；不改动原记录", prefs.cleanText) { prefs.cleanText = it; upd() }
  Toggle("粘贴动画", "直接粘贴时，选中的卡片飞向输入框光标；多选会先叠成一摞再飞过去", prefs.pasteAnim) { prefs.pasteAnim = it; upd() }
         Choice("多条合并时的分隔", listOf("换行", "空一行", "空格", "直接连接"), prefs.mergeMode) { prefs.mergeMode = it; upd() }
     }
     Group("记录") {
  val opts = listOf(50, 100, 200, 500, 1000)
  Choice("最多保留条数（不含置顶）", opts.map { "$it" }, opts.indexOf(prefs.maxItems).coerceAtLeast(0)) {
      prefs.maxItems = opts[it]; ClipStore.applyLimit(opts[it]); upd()
  }
  Clickable("清空全部记录", "包括置顶内容，不可恢复；常用语保留", "清空") { confirmClear = true }
     }
     Group("面板快捷键") {
  Hint("直接打字：搜索　Tab：切换 最近 / 常用语　↑ ↓：选择　Enter：粘贴　1–9：快选（未搜索时）\nCtrl+A：全选　Ctrl+点击：加选 / 取消　Shift+点击 或 Shift+↑↓：连续选择　长按：加选\nCtrl+V：粘贴到输入框（多条自动合并）　Ctrl+C：只复制　Ctrl+P：置顶　Delete：删除\nEsc：取消选择 → 清空搜索 → 关闭")
     }
     Text("随贴 ClipDock " + remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } + " · MIT 开源 · 本地存储，不联网", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(vertical = 8.dp))
 }
    }

    if (recording) HotkeyDialog(onDismiss = { recording = false }) { h ->
 prefs.hotkey = h; recording = false; upd()
 Toast.makeText(ctx, "已设置为 ${h.label()}", Toast.LENGTH_SHORT).show()
    }
    if (confirmClear) AlertDialog(
 onDismissRequest = { confirmClear = false },
 title = { Text("清空全部记录？") },
 text = { Text("置顶的内容也会被删除，且不可恢复。") },
 confirmButton = { TextButton({ ClipStore.clearAll(); confirmClear = false }) { Text("清空", color = Color(0xFFE5484D)) } },
 dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } },
    )
}

@Composable
private fun HotkeyDialog(onDismiss: () -> Unit, onSet: (Hotkey) -> Unit) {
    var tip by remember { mutableStateOf("请在实体键盘上按下组合键\n至少包含 Win / Ctrl / Alt 之一") }
    val fr = remember { FocusRequester() }
    DisposableEffect(Unit) {
 ClipService.recordingHotkey = true
 onDispose { ClipService.recordingHotkey = false }
    }
    Dialog(onDismissRequest = onDismiss) {
 Column(
     Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp)
  .focusRequester(fr).focusable()
  .onPreviewKeyEvent { ke ->
      val e = ke.nativeKeyEvent
      if (e.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
      if (Hotkey.isModifierKey(e.keyCode)) return@onPreviewKeyEvent true
      val mods = Hotkey.modsOf(e)
      if (mods and (Hotkey.CTRL or Hotkey.ALT or Hotkey.META) == 0) {
   if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK) { onDismiss(); return@onPreviewKeyEvent true }
   tip = "需要包含 Win / Ctrl / Alt，请重新按"
   return@onPreviewKeyEvent true
      }
      onSet(Hotkey(e.keyCode, mods)); true
  },
     horizontalAlignment = Alignment.CenterHorizontally,
 ) {
     Text("设置唤起快捷键", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
     Spacer(Modifier.height(20.dp))
     Text(tip, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
     Spacer(Modifier.height(20.dp))
     Row {
  TextButton({ onSet(Hotkey(KeyEvent.KEYCODE_V, Hotkey.META)) }) { Text("恢复默认 Win + V") }
  Spacer(Modifier.width(8.dp))
  TextButton(onDismiss) { Text("取消") }
     }
 }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
}

// ---------------- 组件 ----------------

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
 Text(title, fontSize = 13.sp, color = Color(0xFF4C8DF6), fontWeight = FontWeight.SemiBold,
     modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp))
 Column(
     Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
  .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(vertical = 4.dp),
     content = content,
 )
    }
}

@Composable
private fun RowBase(title: String, sub: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
 Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
     .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
 Column(Modifier.weight(1f)) {
     Text(title, fontSize = 15.sp, color = cs.onSurface)
     if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp)
 }
 Spacer(Modifier.width(12.dp))
 trailing()
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) =
    RowBase(title, sub, { onChange(!value) }) { Switch(value, onChange) }

@Composable
private fun Clickable(title: String, sub: String?, value: String, onClick: () -> Unit) =
    RowBase(title, sub, onClick) {
 Text(value, fontSize = 13.sp, color = Color(0xFF4C8DF6), modifier = Modifier.clip(RoundedCornerShape(8.dp))
     .background(Color(0x1A4C8DF6)).padding(horizontal = 10.dp, vertical = 5.dp))
    }

@Composable
private fun Choice(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    RowBase(title, null, null) {
 Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
     options.forEachIndexed { i, o ->
  val sel = i == selected
  Text(o, fontSize = 13.sp,
      color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.clip(RoundedCornerShape(8.dp))
   .background(if (sel) Color(0xFF4C8DF6) else Color.Transparent)
   .clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 5.dp))
     }
 }
    }

@Composable
private fun Hint(t: String) =
    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
 modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
