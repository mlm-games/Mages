package org.mlm.mages.activities

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.koin.android.ext.android.inject
import org.mlm.mages.calls.CallManager
import org.mlm.mages.ui.GlobalCallOverlay
import org.mlm.mages.ui.theme.MainTheme

class CallActivity : ComponentActivity() {

    private val callManager: CallManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        callManager.setExternalHost(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        setContent {
            val call by callManager.call.collectAsState()
            LaunchedEffect(call) {
                if (call == null) finish()
            }
            MainTheme {
                GlobalCallOverlay(
                    callManager,
                    Modifier.fillMaxSize(),
                    onMinimize = { enterCallPip(fallbackToBack = true) }
                )
            }
        }
    }

    private fun enterCallPip(fallbackToBack: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (fallbackToBack) moveTaskToBack(true)
            return
        }
        callManager.setMinimized(false)
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(220, 140))
                    .build()
            )
        }.onFailure {
            if (fallbackToBack) moveTaskToBack(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (runCatching { callManager.isInCall() }.getOrDefault(false)) {
            enterCallPip()
        }
    }

    override fun onDestroy() {
        callManager.setExternalHost(false)
        super.onDestroy()
    }
}
