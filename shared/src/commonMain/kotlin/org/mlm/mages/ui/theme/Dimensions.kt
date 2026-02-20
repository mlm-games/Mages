package org.mlm.mages.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Sizes {
    val avatarSmall = 36.dp
    val avatarMedium = 48.dp
    val avatarLarge = 64.dp
    val iconSmall = 16.dp
    val iconMedium = 24.dp
    val iconLarge = 40.dp
    val touchTarget = 48.dp
}

object Durations {
    const val short = 150
    const val medium = 300
    const val long = 500
    const val draftSaveDebounce = 1000L
    const val typingTimeout = 4000L
}

object Limits {
    const val previewCharsLong = 150   // message action sheet, pinned messages sheet
    const val previewCharsMedium = 100 // room list preview, discover topic
    const val previewCharsShort = 80   // reply banner, pinned message banner
    const val unreadBadgeCap = 99      // "99+" display cap on unread badges
}
