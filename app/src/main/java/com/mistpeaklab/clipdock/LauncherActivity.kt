package com.mistpeaklab.clipdock

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 桌面图标：默认打开主界面；设置里可改为“直接唤起悬浮面板” */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 if (Prefs(this).iconOpensPanel && PanelHost.canShow(this)) {
     ClipReader.capture(this, "FOREGROUND") // 此刻本应用在前台，可以直接读到当前剪贴板
     PanelHost.toggle(this)
 } else {
     startActivity(Intent(this, MainActivity::class.java))
 }
        finish()
    }
}

/** 桌面长按快捷方式“悬浮面板” */
class PanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 if (PanelHost.canShow(this)) {
     ClipReader.capture(this, "FOREGROUND")
     PanelHost.toggle(this)
 } else {
     startActivity(Intent(this, MainActivity::class.java))
 }
        finish()
    }
}
