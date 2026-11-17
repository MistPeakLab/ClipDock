package com.mistpeaklab.clipdock

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistpeaklab.clipdock.floatball.FloatService

/**
 * 桌面小部件：最近复制的 / 收藏 / 某个分组，点一条就复制。
 * 列表用 RemoteViews.RemoteCollectionItems（Android 12+，本应用最低 14）。
 * 点条目打开 [WidgetActionActivity]（不显示界面）：普通条目直接写剪贴板；隐藏的先验证身份。
 */
class ClipWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ClipStore.init(ctx)
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) = render(ctx, mgr, id)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = Prefs(ctx)
        ids.forEach { p.dropWidget(it) }
    }

    companion object {
        /** 列表里最多放这么多条 */
        private const val MAX_ROWS = 30

        fun updateAll(ctx: Context) {
            val app = ctx.applicationContext ?: ctx
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(app, ClipWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            ids.forEach { runCatching { render(app, mgr, it) } }
        }

        /** 显示什么：标题和条目 */
        internal fun content(ctx: Context, id: Int): Pair<String, List<ClipItem>> {
            val src = Prefs(ctx).widgetSource(id)
            return when {
                src == "fav" -> tr("收藏", "Favourites") to ClipStore.favorites()
                src.startsWith("g:") -> {
                    val gid = src.substring(2)
                    val g = ClipStore.groups.value.firstOrNull { it.id == gid }
                    if (g == null) tr("最近", "Recent") to ClipStore.history()
                    else g.name to ClipStore.items.value.filter { it.groupId == gid }
                }
                else -> tr("最近", "Recent") to ClipStore.history().sortedByDescending { it.time }
            }
        }

        /** 列表里的一行 */
        internal fun row(ctx: Context, item: ClipItem): RemoteViews {
            val row = RemoteViews(ctx.packageName, R.layout.widget_item)
            row.setTextViewText(R.id.widget_text, if (item.hidden) tr("🔒 已隐藏的内容 · 点按验证后复制", "🔒 Hidden · tap to verify") else item.preview.replace(Regex("\\s*\\n\\s*"), " ⏎ "))
            row.setTextViewText(R.id.widget_meta, if (item.phrase) tr("常用语", "Phrase") else timeLabel(item.time))
            row.setOnClickFillInIntent(R.id.widget_row, Intent().putExtra(WidgetActionActivity.EXTRA_ID, item.id))
            return row
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val pkg = ctx.packageName
            val (title, items) = content(ctx, id)
            val v = RemoteViews(pkg, R.layout.widget_clips)
            v.setTextViewText(R.id.widget_title, tr("随贴 · %s", "ClipDock · %s", title))
            v.setTextViewText(
                R.id.widget_empty,
                if (!ClipStore.loaded.value) tr("正在读取…", "Loading…") else tr("这里还是空的\n复制一些文字后会出现在这里", "Nothing here yet"),
            )
            val b = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            items.take(MAX_ROWS).forEach { item -> b.addItem(item.id.hashCode().toLong(), row(ctx, item)) }
            v.setRemoteAdapter(R.id.widget_list, b.build())
            v.setEmptyView(R.id.widget_list, R.id.widget_empty)
            v.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(ctx, 1, Intent(ctx, WidgetActionActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            v.setOnClickPendingIntent(
                R.id.widget_panel,
                PendingIntent.getActivity(ctx, 2, Intent(ctx, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE),
            )
            v.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE),
            )
            mgr.updateAppWidget(id, v)
        }
    }
}

/**
 * 记录变了以后，刷新桌面小部件和常驻通知。连续的变化（比如一次删十条）合成一次，最多 1.5 秒刷新一次。
 */
object Surfaces {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val run = Runnable {
        val c = app ?: return@Runnable
        runCatching { ClipWidget.updateAll(c) }
        runCatching { FloatService.refresh(c) }
    }

    fun poke(ctx: Context) {
        app = ctx.applicationContext ?: ctx
        main.removeCallbacks(run)
        main.postDelayed(run, 1500)
    }
}

/** 小部件、常驻通知点了某一条：复制它（隐藏的先验证身份）。没有界面 */
class WidgetActionActivity : Activity() {
    companion object {
        const val EXTRA_ID = "item_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(EXTRA_ID)
        ClipStore.init(this)
        ClipStore.awaitLoaded()
        val item = id?.let { i -> ClipStore.items.value.firstOrNull { it.id == i } }
        val app = applicationContext
        when {
            item == null -> toast(tr("这条已经不在了", "That item is gone"))
            item.hidden -> Unlock.run(app) {
                ClipStore.plain(item)?.let { ClipReader.write(app, it); app.toast(tr("已复制", "Copied")) }
            }
            else -> { ClipReader.write(app, item.text); toast(tr("已复制", "Copied")) }
        }
        finish()
    }
}

/** 添加 / 重新设置小部件时选显示什么：最近、收藏、某个分组 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        ClipStore.init(this)
        ClipStore.awaitLoaded()
        val prefs = Prefs(this)
        val options = buildList {
            add("" to tr("最近复制的", "Recent"))
            add("fav" to tr("收藏（常用语 + 收藏的记录）", "Favourites"))
            ClipStore.groups.value.forEach { add("g:" + it.id to tr("分组：%s", "Group: %s", it.name)) }
        }
        val cur = prefs.widgetSource(id)
        setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().clickable { finish() }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(
                        Modifier.padding(24.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = false) {}.padding(vertical = 14.dp),
                    ) {
                        Text(tr("小部件显示", "Widget shows"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            items(options, key = { it.first }) { (k, label) ->
                                Text(label, fontSize = 15.sp, color = if (k == cur) Accent else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable { choose(id, k) }.padding(horizontal = 20.dp, vertical = 13.dp))
                            }
                        }
                        TextButton({ finish() }, Modifier.padding(horizontal = 8.dp)) { Text(tr("取消", "Cancel")) }
                    }
                }
            }
        }
    }

    private fun choose(id: Int, src: String) {
        Prefs(this).setWidgetSource(id, src)
        AppWidgetManager.getInstance(this)?.let { ClipWidget.render(this, it, id) }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
