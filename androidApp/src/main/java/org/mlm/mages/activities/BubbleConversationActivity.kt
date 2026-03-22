package org.mlm.mages.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.push.ConversationShortcutPublisher
import org.mlm.mages.ui.screens.RoomScreen
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.viewmodel.RoomViewModel

class BubbleConversationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID)
        if (roomId.isNullOrBlank()) { finish(); return }

        val notificationId = roomId.hashCode()

        setContent {
            MainTheme {
                val vm: RoomViewModel = koinViewModel { parametersOf(roomId, "") }

                RoomScreen(
                    viewModel = vm,
                    onBack = { finish() },
                    onOpenInfo = { },
                    onNavigateToRoom = { _, _ -> },
                    onNavigateToThread = { _, _, _ -> },
                    onStartCall = { },
                    onStartVoiceCall = { },
                    onOpenForwardPicker = { _, _ -> },
                    isBubbleMode = true,
                )
            }
        }
    }
}