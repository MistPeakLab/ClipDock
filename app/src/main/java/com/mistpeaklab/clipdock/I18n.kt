package com.mistpeaklab.clipdock

import android.content.Context
import java.util.Locale

/**
 * 界面语言。中文和英文直接写在用到的地方：`tr("已恢复", "Restored")`，
 * 带参数的用 String.format 的写法：`tr("已选 %d 条", "%d selected", n)`。
 *
 * 没有用安卓的 strings.xml：悬浮面板、无障碍服务、数据层都要用文字，它们手里不一定有界面的 Context；
 * 两种语言写在一起，改一句话时也不会漏掉另一种。
 * 只有系统要直接读的几个名字（应用名、快捷方式、无障碍说明）放在 res/values 里。
 */
object L {
    /** 当前是否显示中文 */
    @Volatile var zh = true
        private set

    /** language：0 跟随系统，1 中文，2 English */
    fun init(ctx: Context, language: Int = Prefs(ctx).language) {
        zh = when (language) {
            1 -> true
            2 -> false
            else -> ctx.resources.configuration.locales[0].language == Locale.CHINESE.language
        }
    }
}

fun tr(zh: String, en: String): String = if (L.zh) zh else en

fun tr(zh: String, en: String, vararg args: Any?): String = String.format(Locale.ROOT, if (L.zh) zh else en, *args)
