package com.mistpeaklab.clipdock

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 通知栏快捷开关：点一下唤起 / 关闭面板 */
class PanelTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
     state = if (PanelHost.canShow(this@PanelTileService)) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
     updateTile()
 }
    }

    override fun onClick() {
 // 经由一个无界面 Activity 唤起：它在前台的一瞬间能读到当前剪贴板，并顺便收起通知栏
 val i = Intent(this, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
