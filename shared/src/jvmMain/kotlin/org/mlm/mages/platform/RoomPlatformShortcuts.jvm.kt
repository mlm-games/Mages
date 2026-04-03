package org.mlm.mages.platform

actual object RoomPlatformShortcuts {
    actual fun support(): RoomPlatformShortcutSupport =
        RoomPlatformShortcutSupport()

    actual fun addHomeScreenShortcut(
        roomId: String,
        roomName: String?,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Only on Android")
    )
}
