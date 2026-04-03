package org.mlm.mages.platform

data class RoomPlatformShortcutSupport(
    val homeScreenShortcut: Boolean = false,
)

expect object RoomPlatformShortcuts {
    fun support(): RoomPlatformShortcutSupport

    fun addHomeScreenShortcut(
        roomId: String,
        roomName: String?,
    ): Result<Unit>
}
