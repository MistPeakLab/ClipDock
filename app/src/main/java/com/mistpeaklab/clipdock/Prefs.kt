package com.mistpeaklab.clipdock

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/** 快捷键：一个主键 + 修饰键掩码 */
data class Hotkey(val keyCode: Int, val mods: Int) {
    fun matches(e: KeyEvent): Boolean = e.keyCode == keyCode && modsOf(e) == mods

    fun label(): String {
        val parts = mutableListOf<String>()
        if (mods and META != 0) parts += "Win"
        if (mods and CTRL != 0) parts += "Ctrl"
 if (mods and ALT != 0) parts += "Alt"
 if (mods and SHIFT != 0) parts += "Shift"
        parts += KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
 return parts.joinToString(" + ")
    }

    companion object {
 const val CTRL = 1
 const val ALT = 2
        const val SHIFT = 4
 const val META = 8

        fun modsOf(e: KeyEvent): Int {
     var m = 0
            if (e.isCtrlPressed) m = m or CTRL
     if (e.isAltPressed) m = m or ALT
     if (e.isShiftPressed) m = m or SHIFT
     if (e.isMetaPressed) m = m or META
     return m
        }

        fun isModifierKey(code: Int) = KeyEvent.isModifierKey(code) ||
     code == KeyEvent.KEYCODE_FUNCTION || code == KeyEvent.KEYCODE_CAPS_LOCK
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hotkey: Hotkey
        get() = Hotkey(sp.getInt("hk_key", KeyEvent.KEYCODE_V), sp.getInt("hk_mods", Hotkey.META))
        set(v) = sp.edit().putInt("hk_key", v.keyCode).putInt("hk_mods", v.mods).apply()

    var hotkeyEnabled by bool("hk_enabled", true)

    // 关闭方式
    var closeOnSelect by bool("close_select", true)
    var closeOnEsc by bool("close_esc", true)
    var closeOnOutside by bool("close_outside", true)
    var closeOnHotkey by bool("close_hotkey", true)
    var closeOnAppSwitch by bool("close_app_switch", false)

    // 粘贴
    var autoPaste by bool("auto_paste", true)

    /** 粘贴时卡片飞向光标的动画 */
    var pasteAnim by bool("paste_anim", true)

    /** 粘贴前清理格式（零宽字符、行尾空白、多余空行、首尾空白） */
    var cleanText by bool("clean_text", false)

    /** 多选合并分隔：0 换行 1 空一行 2 空格 3 直接连接 */
    var mergeMode: Int
        get() = sp.getInt("merge_mode", 0)
        set(v) = sp.edit().putInt("merge_mode", v).apply()

    /** 面板上次停留的标签：0 最近 1 常用语 */
    var panelTab: Int
 get() = sp.getInt("panel_tab", 0)
        set(v) = sp.edit().putInt("panel_tab", v).apply()

    /** 点桌面图标时直接唤起悬浮面板（否则打开主界面） */
    var iconOpensPanel by bool("icon_opens_panel", false)

    // 记录
    var maxItems: Int
        get() = sp.getInt("max_items", 200)
        set(v) = sp.edit().putInt("max_items", v).apply()

    // 面板位置（-1 表示用默认位置）
    var panelX: Int
 get() = sp.getInt("panel_x", -1)
        set(v) = sp.edit().putInt("panel_x", v).apply()
    var panelY: Int
        get() = sp.getInt("panel_y", -1)
 set(v) = sp.edit().putInt("panel_y", v).apply()

    fun resetPanelPosition() = sp.edit().remove("panel_x").remove("panel_y").remove("panel_w").remove("panel_h").apply()

    // 面板尺寸（dp）
    var panelW: Int
        get() = sp.getInt("panel_w", 340)
        set(v) = sp.edit().putInt("panel_w", v).apply()
    var panelH: Int
        get() = sp.getInt("panel_h", 420)
 set(v) = sp.edit().putInt("panel_h", v).apply()

    /** 面板每条最多显示几行 */
    var previewLines: Int
 get() = sp.getInt("preview_lines", 2)
 set(v) = sp.edit().putInt("preview_lines", v).apply()

    /** 主题：0 跟随系统 1 浅色 2 深色 */
    var theme: Int
 get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) =
     sp.edit().putBoolean(key, value).apply()
    }
}
