package org.mlm.mages.activities

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import co.touchlab.kermit.Logger
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import org.mlm.mages.shared.R
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.R as AppR
import org.mlm.mages.calls.CallEntryPoint
import org.mlm.mages.calls.CallManager
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.formatCallElapsed
import org.mlm.mages.calls.isExpired
import org.mlm.mages.calls.isRingingCall
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomCallState
import org.mlm.mages.push.AndroidNotificationHelper
import org.mlm.mages.push.CallTelecomBridge
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.components.core.Avatar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateToWithDecay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.start_call
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class CallSwipeValue { Center, Answer, Decline }

private data class IncomingCallUiState(
    val roomId: String,
    val roomName: String,
    val callerName: String,
    val callerAvatarPath: String?,
    val eventId: String?,
    val isVoiceOnly: Boolean,
    val isDm: Boolean,
    val expiresAtMs: Long = 0L,
    val callerUserId: String? = null,
)

class IncomingCallActivity : ComponentActivity() {

    private val service: MatrixService by inject()
    private val incomingCalls: IncomingCallTracker by inject()
    private val callManager: CallManager by inject()
    private val settingsRepository: SettingsRepository<AppSettings> by inject()

    private var pendingAccept: (() -> Unit)? = null
    private var pendingUnlock: (() -> Unit)? = null
    private var accepting = false

    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            runPendingUnlock()
        } else {
            pendingUnlock = null
        }
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            pendingAccept?.invoke()
        } else {
            Toast.makeText(
                this,
                getString(AppR.string.mic_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
        pendingAccept = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextCompat.getContextForLanguage(newBase))
    }

    private var uiState by mutableStateOf<IncomingCallUiState?>(null)

    private var expiryHandler: Handler? = null
    private var expiryRunnable: Runnable? = null
    private var dismissedReceiver: BroadcastReceiver? = null
    private var callStateToken: ULong? = null
    private var declineToken: ULong? = null
    private var watchdogJobs = mutableListOf<kotlinx.coroutines.Job>()
    @Volatile private var remoteFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyIntent(intent)
        startRemoteHangupWatchdogs()

        if (intent?.getBooleanExtra(EXTRA_ANSWER_IMMEDIATELY, false) == true) {
            window.decorView.post {
                uiState?.let { acceptCall(it.roomId, it.eventId, it.isVoiceOnly) }
            }
        }

        setContent {
            MainTheme {
                val state = uiState
                if (state == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    IncomingCallScreen(
                        roomName = state.roomName,
                        callerName = state.callerName,
                        callerAvatarPath = state.callerAvatarPath,
                        isVoiceOnly = state.isVoiceOnly,
                        callerUserId = state.callerUserId,
                        onAccept = {
                            acceptCall(
                                roomId = state.roomId,
                                eventId = state.eventId,
                                isVoiceOnly = state.isVoiceOnly
                            )
                        },
                        onDecline = {
                            declineCall(state.roomId)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        remoteFinished = false
        applyIntent(intent)
        startRemoteHangupWatchdogs()
        if (intent.getBooleanExtra(EXTRA_ANSWER_IMMEDIATELY, false)) {
            window.decorView.post {
                uiState?.let { acceptCall(it.roomId, it.eventId, it.isVoiceOnly) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recheckStillRinging()
    }

    private fun applyIntent(intent: Intent?) {
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID) ?: run {
            finish()
            return
        }

        val newState = IncomingCallUiState(
            roomId = roomId,
            roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Unknown",
            callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown",
            callerAvatarPath = intent.getStringExtra(EXTRA_CALLER_AVATAR),
            eventId = intent.getStringExtra(EXTRA_EVENT_ID),
            isVoiceOnly = intent.getBooleanExtra(EXTRA_IS_VOICE_ONLY, false),
            isDm = intent.getBooleanExtra(EXTRA_IS_DM, false),
            expiresAtMs = intent.getLongExtra(EXTRA_EXPIRES_AT_MS, 0L),
            callerUserId = intent.getStringExtra("caller_user_id"),
        )

        uiState = newState
    }

    private fun acceptCall(roomId: String, eventId: String?, isVoiceOnly: Boolean) {
        if (accepting || isFinishing || isDestroyed) return
        lifecycleScope.launch {
            val settings = runCatching { settingsRepository.flow.first() }
                .getOrDefault(AppSettings())
            val requiresUnlock = isDeviceLocked() &&
                (!settings.answerCallsWhenLocked || missingPermissions(isVoiceOnly).isNotEmpty())
            if (requiresUnlock) {
                requestUnlock { continueAccept(roomId, eventId, isVoiceOnly, settings) }
            } else {
                continueAccept(roomId, eventId, isVoiceOnly, settings)
            }
        }
    }

    private fun missingPermissions(isVoiceOnly: Boolean): List<String> = buildList {
        if (ContextCompat.checkSelfPermission(
                this@IncomingCallActivity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (!isVoiceOnly && ContextCompat.checkSelfPermission(
                this@IncomingCallActivity,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.CAMERA)
        }
    }

    private fun continueAccept(
        roomId: String,
        eventId: String?,
        isVoiceOnly: Boolean,
        settings: AppSettings,
    ) {
        if (accepting) return

        val needed = missingPermissions(isVoiceOnly)
        if (needed.isEmpty()) {
            joinCall(roomId, eventId, isVoiceOnly, settings)
        } else {
            pendingAccept = { joinCall(roomId, eventId, isVoiceOnly, settings) }
            callPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun isDeviceLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true

    private fun requestUnlock(onUnlocked: () -> Unit) {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceLocked) {
            pendingUnlock = null
            onUnlocked()
            return
        }

        pendingUnlock = onUnlocked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = runPendingUnlock()
                    override fun onDismissCancelled() { pendingUnlock = null }
                    override fun onDismissError() { pendingUnlock = null }
                }
            )
        } else {
            val intent = runCatching {
                keyguard.createConfirmDeviceCredentialIntent(null, null)
            }.getOrNull()
            if (intent == null) {
                runPendingUnlock()
            } else {
                runCatching { credentialLauncher.launch(intent) }
                    .onFailure { pendingUnlock = null }
            }
        }
    }

    private fun runPendingUnlock() {
        val action = pendingUnlock ?: return
        pendingUnlock = null
        action()
    }

    private fun joinCall(
        roomId: String,
        eventId: String?,
        isVoiceOnly: Boolean,
        settings: AppSettings,
    ) {
        if (accepting) return
        accepting = true
        lifecycleScope.launch {
            AndroidNotificationHelper.dismissCallUi(
                this@IncomingCallActivity, roomId, eventId, silent = true, retireTelecom = false
            )

            Logger.i("Accept pressed; joining call directly roomId=$roomId eventId=$eventId")
            val started = CallEntryPoint.start(
                context = this@IncomingCallActivity,
                service = service,
                callManager = callManager,
                settings = settings,
                roomId = roomId,
                roomName = uiState?.roomName,
                isVoiceOnly = isVoiceOnly,
                incoming = true,
            )
            if (started) finish() else accepting = false
        }
    }

    private fun declineCall(roomId: String) {
        Logger.i("onDecline roomId=$roomId")
        lifecycleScope.launch {
            val state = uiState
            if (state?.eventId != null) {
                runCatching { service.initFromDisk() }
                val port = service.portOrNull
                if (port != null && service.isLoggedIn()) {
                    runCatching { port.declineCall(roomId, state.eventId) }
                }
            }
            AndroidNotificationHelper.dismissCallUi(
                this@IncomingCallActivity, roomId, state?.eventId, silent = true
            )
            finish()
        }
    }

    private fun startRemoteHangupWatchdogs() {
        val state = uiState ?: return
        stopRemoteHangupWatchdogs()

        dismissedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != CallTelecomBridge.ACTION_CALL_DISMISSED) return
                val goneRoom = intent.getStringExtra(CallTelecomBridge.EXTRA_ROOM_ID)
                if (goneRoom != state.roomId) return
                val goneEvent = intent.getStringExtra(CallTelecomBridge.EXTRA_EVENT_ID)
                if (goneEvent != null && state.eventId != null && goneEvent != state.eventId) return
                val silent = intent.getBooleanExtra(CallTelecomBridge.EXTRA_SILENT, false)
                finishAfterRemoteCancel(silent = silent)
            }
        }.also {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(it, IntentFilter(CallTelecomBridge.ACTION_CALL_DISMISSED), RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(it, IntentFilter(CallTelecomBridge.ACTION_CALL_DISMISSED))
                }
            }
        }

        val expiryMs = state.expiresAtMs
        if (expiryMs > 0L) {
            val handler = Handler(Looper.getMainLooper())
            val waitMs = (expiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val runnable = Runnable { finishAfterRemoteCancel(silent = false) }
            expiryHandler = handler
            expiryRunnable = runnable
            handler.postDelayed(runnable, waitMs)
        }

        watchdogJobs += lifecycleScope.launch {
            incomingCalls.dismissed.collect { gone ->
                val s = uiState ?: return@collect
                if (gone.roomId == s.roomId &&
                    (s.eventId == null || gone.eventId == s.eventId)
                ) {
                    finishAfterRemoteCancel(silent = true)
                }
            }
        }

        watchdogJobs += lifecycleScope.launch {
            runCatching { service.initFromDisk() }
            val port = service.portOrNull
            if (port == null || !service.isLoggedIn()) {
                recheckStillRinging()
                return@launch
            }
            runCatching { service.startSupervisedSync() }

            val roomId = state.roomId
            val eventId = state.eventId
            val me = runCatching { port.whoami() }.getOrNull()

            val snapshot = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var first: RoomCallState? = null
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val token = port.observeRoomCallState(
                        roomId,
                        object : MatrixPort.RoomCallStateObserver {
                            override fun onUpdate(state: RoomCallState) {
                                first = state
                                latch.countDown()
                            }
                        }
                    )
                    try {
                        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
                    } finally {
                        runCatching { port.unobserveRoomCallState(token) }
                    }
                    first
                }
            }.getOrNull()
            if (snapshot != null) {
                if (me != null && snapshot.activeParticipants.contains(me)) {
                    finishAfterRemoteCancel(silent = true)
                    return@launch
                }
                if (!snapshot.hasActiveCall) {
                    kotlinx.coroutines.delay(org.mlm.mages.calls.CALL_END_GRACE_MS)
                    val cur = uiState
                    if (cur == null || cur.roomId != roomId || callManager.isInCall(roomId)) {
                        return@launch
                    }
                    recheckStillRinging()
                }
            }

            callStateToken = runCatching {
                port.observeRoomCallState(
                    roomId,
                    object : MatrixPort.RoomCallStateObserver {
                        override fun onUpdate(callState: RoomCallState) {
                            lifecycleScope.launch {
                                val s = uiState ?: return@launch
                                if (s.roomId != roomId) return@launch
                                if (callManager.isInCall(roomId)) {
                                    finishAfterRemoteCancel(silent = true)
                                } else if (!callState.hasActiveCall) {
                                    kotlinx.coroutines.delay(org.mlm.mages.calls.CALL_END_GRACE_MS)
                                    val cur = uiState ?: return@launch
                                    if (cur.roomId == roomId && !callManager.isInCall(roomId)) {
                                        finishAfterRemoteCancel(silent = false)
                                    }
                                }
                            }
                        }
                    }
                )
            }.getOrNull()

            if (eventId != null) {
                declineToken = runCatching {
                    port.observeCallDecline(
                        roomId,
                        eventId,
                        object : MatrixPort.CallDeclineObserver {
                            override fun onDecline(declinerUserId: String) {
                                lifecycleScope.launch {
                                    val silent = me != null && declinerUserId == me
                                    finishAfterRemoteCancel(silent = silent)
                                }
                            }
                        }
                    )
                }.getOrNull()
            }

            recheckStillRinging()
        }
    }

    private fun recheckStillRinging() {
        val state = uiState ?: return
        if (callManager.isInCall(state.roomId)) {
            finishAfterRemoteCancel(silent = true)
            return
        }
        val eventId = state.eventId ?: return
        lifecycleScope.launch {
            runCatching { service.initFromDisk() }
            val port = service.portOrNull ?: return@launch
            val rendered = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    port.fetchNotification(state.roomId, eventId)
                }
            }.getOrNull()
            val stale = rendered != null &&
                (!rendered.kind.isRingingCall() || rendered.isExpired())
            if (stale) finishAfterRemoteCancel(silent = false)
        }
    }

    private fun stopRemoteHangupWatchdogs() {
        watchdogJobs.forEach { runCatching { it.cancel() } }
        watchdogJobs.clear()
        expiryRunnable?.let { r -> expiryHandler?.removeCallbacks(r) }
        expiryHandler = null
        expiryRunnable = null
        dismissedReceiver?.let { runCatching { unregisterReceiver(it) } }
        dismissedReceiver = null
        val port = service.portOrNull
        callStateToken?.let { token -> runCatching { port?.unobserveRoomCallState(token) } }
        callStateToken = null
        val eventId = uiState?.eventId
        if (eventId != null) {
            declineToken?.let { token -> runCatching { port?.unobserveCallDecline(token) } }
        }
        declineToken = null
    }

    private fun finishAfterRemoteCancel(silent: Boolean) {
        if (remoteFinished || isFinishing || isDestroyed) return
        remoteFinished = true
        val state = uiState
        lifecycleScope.launch {
            if (state != null) {
                AndroidNotificationHelper.cancelCallNotification(this@IncomingCallActivity, state.roomId)
                if (!silent) {
                    val joined = callManager.isInCall(state.roomId)
                    if (!joined) {
                        AndroidNotificationHelper.showMissedCallNotification(
                            this@IncomingCallActivity,
                            state.roomId,
                            state.callerName,
                            state.roomName,
                            state.isVoiceOnly,
                        )
                    }
                }
                runCatching {
                    incomingCalls.dismiss(state.roomId, state.eventId)
                }
            }
            stopRemoteHangupWatchdogs()
            finish()
        }
    }

    override fun onDestroy() {
        stopRemoteHangupWatchdogs()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ANSWER_IMMEDIATELY = "answer_immediately"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_AVATAR = "caller_avatar_path"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_IS_VOICE_ONLY = "is_voice_only"
        const val EXTRA_IS_DM = "is_dm"
        const val EXTRA_EXPIRES_AT_MS = "expires_at_ms"

        fun createIntent(
            context: Context,
            roomId: String,
            roomName: String,
            callerName: String,
            callerAvatarUrl: String?,
            eventId: String?,
            isVoiceOnly: Boolean = false,
            isDm: Boolean = false,
            expiresAtMs: Long = 0L,
        ): Intent {
            return Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, roomName)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_AVATAR, callerAvatarUrl)
                putExtra(EXTRA_EVENT_ID, eventId)
                putExtra(EXTRA_IS_VOICE_ONLY, isVoiceOnly)
                putExtra(EXTRA_IS_DM, isDm)
                putExtra(EXTRA_EXPIRES_AT_MS, expiresAtMs)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(
    roomName: String,
    callerName: String,
    callerAvatarPath: String?,
    isVoiceOnly: Boolean = false,
    callerUserId: String? = null,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val talkBackOn = rememberTalkBackOn()
    var handlingAction by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = !handlingAction) {
        handlingAction = true
        onDecline()
    }

    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            TopBanner(isVoiceOnly)

            CallerHero(
                callerName = callerName,
                roomName = roomName,
                callerAvatarPath = callerAvatarPath,
                callerUserId = callerUserId
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (talkBackOn) {
                    BigButtonsRow(
                        enabled = !handlingAction,
                        isVoiceOnly = isVoiceOnly,
                        onDecline = {
                            handlingAction = true
                            onDecline()
                        },
                        onAccept = {
                            handlingAction = true
                            onAccept()
                        }
                    )
                } else {
                    SwipeToAnswerOrDecline(
                        enabled = !handlingAction,
                        callerName = callerName,
                        onAnswer = {
                            handlingAction = true
                            onAccept()
                        },
                        onDecline = {
                            handlingAction = true
                            onDecline()
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))
            }
        }

        AnimatedVisibility(
            visible = handlingAction,
            enter = fadeIn(tween(200)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                ConnectingOverlay()
            }
        }
    }
}
@Composable
private fun TopBanner(isVoiceOnly: Boolean = false) {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }
    val timerText = remember(elapsedSeconds) {
        formatCallElapsed(elapsedSeconds * 1000L)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AssistChip(
            onClick = {},
            label = { Text(stringResource(if (isVoiceOnly) R.string.incoming_voice_call else R.string.incoming_call_title), fontWeight = FontWeight.SemiBold) },
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        if (isVoiceOnly) R.drawable.outline_phone_callback_24
                        else R.drawable.outline_hangout_video_24
                    ),
                    contentDescription = null
                )
            }
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ringing_for, timerText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CallerHero(
    callerName: String,
    roomName: String,
    callerAvatarPath: String?,
    callerUserId: String? = null
) {
    val scheme = MaterialTheme.colorScheme

    val t = rememberInfiniteTransition(label = "avatar")
    val wobble by t.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wobble"
    )
    val breathe by t.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val ring1 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2400, easing = LinearOutSlowInEasing), RepeatMode.Restart
        ), label = "ring1"
    )
    val ring2 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2400, 800, easing = LinearOutSlowInEasing), RepeatMode.Restart
        ), label = "ring2"
    )
    val ring3 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2400, 1600, easing = LinearOutSlowInEasing), RepeatMode.Restart
        ), label = "ring3"
    )

    val avatarShape = RoundedCornerShape(
        topStart = 44.dp,
        topEnd = 26.dp,
        bottomEnd = 46.dp,
        bottomStart = 22.dp
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(contentAlignment = Alignment.Center) {
            listOf(ring1, ring2, ring3).forEach { progress ->
                val ringScale = 1f + progress * 0.6f
                val ringAlpha = (1f - progress).coerceIn(0f, 0.35f)
                Box(
                    modifier = Modifier
                        .size(182.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .size(182.dp)
                    .rotate(wobble)
                    .scale(breathe)
                    .clip(RoundedCornerShape(64.dp, 28.dp, 72.dp, 32.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.25f),
                                scheme.tertiary.copy(alpha = 0.14f),
                                scheme.secondary.copy(alpha = 0.18f),
                            )
                        )
                    )
                    .alpha(0.95f)
            )

            Avatar(
                name = callerName,
                avatarPath = callerAvatarPath,
                size = 132.dp,
                shape = avatarShape,
                colorKey = callerUserId,
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                modifier = Modifier
                    .rotate(wobble * 0.6f)
                    .scale(breathe)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = callerName,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = scheme.onSurface
        )

        if (roomName.isNotBlank() && roomName != callerName) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = roomName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BigButtonsRow(
    enabled: Boolean,
    isVoiceOnly: Boolean = false,
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FilledTonalButton(
            onClick = onDecline,
            enabled = enabled,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer
            ),
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.outline_call_end_24),
                contentDescription = null
            )
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.action_decline), fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onAccept,
            enabled = enabled,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF22C55E),
                contentColor = Color(0xFF06210F)
            ),
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
        ) {
            Icon(
                painter = painterResource(
                    if (isVoiceOnly) R.drawable.outline_phone_callback_24
                    else R.drawable.outline_video_call_24
                ),
                contentDescription = stringResource(R.string.pick_call)
            )
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.action_answer), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectingOverlay() {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.padding(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularWavyProgressIndicator(modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.connecting),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SwipeToAnswerOrDecline(
    enabled: Boolean,
    callerName: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val answerColor = Color(0xFF22C55E)
    val declineColor = scheme.error

    val knobSize = 66.dp
    val knobSizePx = with(density) { knobSize.toPx() }

    val state = rememberSaveable(saver = AnchoredDraggableState.Saver()) {
        AnchoredDraggableState(CallSwipeValue.Center)
    }

    var swipeRangePx by remember { mutableFloatStateOf(1f) }

    val settleFraction = 0.82f

    val flingBehavior = remember(state, settleFraction) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val range = swipeRangePx
                if (range <= 1f) return 0f

                val off = state.offset.takeUnless { it.isNaN() } ?: 0f
                val frac = (off / range).coerceIn(-1f, 1f)

                val target = when {
                    frac >= settleFraction -> CallSwipeValue.Answer
                    frac <= -settleFraction -> CallSwipeValue.Decline
                    else -> CallSwipeValue.Center
                }

                state.animateToWithDecay(
                    targetValue = target,
                    velocity = 0f,
                    snapAnimationSpec = spring(dampingRatio = 0.65f, stiffness = 380f)
                )
                return 0f
            }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }
            .distinctUntilChanged()
            .filter { it != CallSwipeValue.Center }
            .collect { v ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                when (v) {
                    CallSwipeValue.Answer -> onAnswer()
                    CallSwipeValue.Decline -> onDecline()
                    else -> Unit
                }
            }
    }

    val rawOffset = state.offset.takeUnless { it.isNaN() } ?: 0f
    val progress = (abs(rawOffset) / swipeRangePx).coerceIn(0f, 1f)
    val isAnswerDirection = rawOffset > 0f

    val hintAlpha by animateFloatAsState(
        targetValue = 1f - (progress * 1.1f).coerceIn(0f, 1f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "hintAlpha"
    )

    val knobScale by animateFloatAsState(
        targetValue = 1f + 0.06f * progress,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "knobScale"
    )

    val knobTint by remember {
        derivedStateOf {
            when {
                rawOffset > 0f -> lerp(scheme.surface, answerColor, progress.coerceIn(0f, 1f))
                rawOffset < 0f -> lerp(scheme.surface, declineColor, progress.coerceIn(0f, 1f))
                else -> scheme.surface
            }
        }
    }

    val knobIconTint by remember {
        derivedStateOf {
            when {
                progress > 0.5f && isAnswerDirection -> Color(0xFF06210F)
                progress > 0.5f && !isAnswerDirection -> scheme.onError
                else -> scheme.onSurface
            }
        }
    }

    val infTransition = rememberInfiniteTransition(label = "arrows")
    val arrowBounce by infTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "arrowBounce"
    )

    val trackCornerPx = with(density) { 30.dp.toPx() }
    val trailColor = if (isAnswerDirection) answerColor else declineColor
    val answerLabel = stringResource(R.string.action_answer)
    val declineLabel = stringResource(R.string.action_decline)
    val sliderDescription = stringResource(R.string.call_slider_for, callerName)

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .height(86.dp)
            .onSizeChanged { size ->
                val widthPx = size.width.toFloat()
                val range = ((widthPx - knobSizePx) / 2f).coerceAtLeast(1f)
                swipeRangePx = range

                state.updateAnchors(
                    DraggableAnchors {
                        CallSwipeValue.Decline at -range
                        CallSwipeValue.Center at 0f
                        CallSwipeValue.Answer at range
                    }
                )
            }
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        declineColor.copy(alpha = 0.16f),
                        scheme.surfaceContainerHigh,
                        answerColor.copy(alpha = 0.16f),
                    )
                )
            )
            .semantics(mergeDescendants = true) {
                contentDescription = sliderDescription
                customActions = listOf(
                    CustomAccessibilityAction(answerLabel) { onAnswer(); true },
                    CustomAccessibilityAction(declineLabel) { onDecline(); true }
                )
            },
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = scheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (progress > 0.01f) {
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val trailWidth = abs(rawOffset)
                        val trailLeft = if (isAnswerDirection) centerX else centerX - trailWidth
                        drawRoundRect(
                            color = trailColor.copy(alpha = 0.18f * progress),
                            topLeft = Offset(trailLeft, 0f),
                            size = Size(trailWidth, size.height),
                            cornerRadius = CornerRadius(trackCornerPx)
                        )
                    }
                }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    flingBehavior = flingBehavior
                )
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(hintAlpha),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_chevron_left_24),
                        contentDescription = null,
                        tint = declineColor.copy(alpha = 0.7f),
                        modifier = Modifier.graphicsLayer {
                            translationX = -arrowBounce
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        declineLabel,
                        color = scheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        answerLabel,
                        color = scheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.outline_chevron_right_24),
                        contentDescription = null,
                        tint = answerColor.copy(alpha = 0.7f),
                        modifier = Modifier.graphicsLayer {
                            translationX = arrowBounce
                        }
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                color = knobTint,
                border = if (progress > 0.15f) BorderStroke(
                    2.dp,
                    trailColor.copy(alpha = (progress * 0.5f).coerceAtMost(0.4f))
                ) else null,
                modifier = Modifier
                    .size(knobSize)
                    .scale(knobScale)
                    .offset { IntOffset(rawOffset.roundToInt(), 0) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_phone_in_talk_24),
                        contentDescription = null,
                        tint = knobIconTint
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val mgr = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    var talkBackOn by remember { mutableStateOf(mgr.isTouchExplorationEnabled) }

    DisposableEffect(mgr) {
        val listener =
            AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
                talkBackOn = enabled
            }
        mgr.addTouchExplorationStateChangeListener(listener)
        onDispose { mgr.removeTouchExplorationStateChangeListener(listener) }
    }

    return talkBackOn
}
