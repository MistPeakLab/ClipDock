package com.mistpeaklab.clipdock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.mistpeaklab.clipdock.util.ContentKind
import com.mistpeaklab.clipdock.util.ContentRules

/** 应用名（来源应用显示用），带缓存 */
object AppInfo {
    private val labels = HashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg.substringAfterLast('.'))
        }
    }

    /** 常见的密码管理器：在它们里面复制的多半是密码 */
    val PASSWORD_MANAGERS = setOf(
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.onepassword.android", "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
        "com.lastpass.lpandroid", "com.dashlane", "org.keepassdroid", "com.enpass.app", "io.enpass.app", "com.nordpass.android.app",
        "proton.android.pass", "com.google.android.apps.authenticator2",
    )
}

/** 各种内容类型的名字和快捷动作 */
object Kinds {
    fun label(k: ContentKind): String = when (k) {
        ContentKind.URL -> tr("网址", "Link")
        ContentKind.EMAIL -> tr("邮箱", "E-mail")
        ContentKind.PHONE -> tr("电话", "Phone")
        ContentKind.TRACKING_NO -> tr("单号", "Tracking no.")
        ContentKind.COLOR -> tr("颜色", "Colour")
        ContentKind.NUMBER -> tr("数字", "Number")
        ContentKind.ADDRESS -> tr("地址", "Address")
        ContentKind.TEXT -> tr("文字", "Text")
    }

    /** 快捷动作的名字；这种类型没有动作时为 null */
    fun actionLabel(k: ContentKind): String? = when (k) {
        ContentKind.URL -> tr("打开链接", "Open link")
        ContentKind.EMAIL -> tr("写邮件", "Write e-mail")
        ContentKind.PHONE -> tr("拨打电话", "Call")
        ContentKind.ADDRESS -> tr("在地图中查看", "Show on map")
        ContentKind.TRACKING_NO -> tr("搜索这个单号", "Search this number")
        else -> null
    }

    fun run(ctx: Context, k: ContentKind, text: String) {
        val t = text.trim()
        val i = when (k) {
            ContentKind.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(if (t.startsWith("www.", true)) "https://$t" else t))
            ContentKind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$t"))
            ContentKind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + t.filter { it.isDigit() || it == '+' }))
            ContentKind.ADDRESS -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(t)))
            ContentKind.TRACKING_NO -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, t)
            else -> return
        }
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr("没有能打开它的应用", "No app can open this"), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(ctx, tr("没有能打开它的应用", "No app can open this"), Toast.LENGTH_SHORT).show()
        }
    }

    fun color(text: String): Int? = ContentRules.parseColor(text)
}

/** 打开系统设置里的某一页，打不开时退回应用详情页 */
fun Context.openSettings(action: String, withPackage: Boolean = false) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:$packageName")
    try {
        startActivity(i)
    } catch (e: Exception) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
