package com.getdown.textrator

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade, tap "Textrator", the screen underneath is captured. */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (Engine.isTileActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Engine.onTileClick(this)
    }
}
