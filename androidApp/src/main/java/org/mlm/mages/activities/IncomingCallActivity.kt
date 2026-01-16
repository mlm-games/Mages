package org.mlm.mages.activities

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.mlm.mages.MainActivity
import org.mlm.mages.calls.CallManager
import org.mlm.mages.matrix.CallIntent
import org.mlm.mages.push.AndroidNotificationHelper
import org.mlm.mages.ui.theme.MainTheme
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateToWithDecay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class CallSwipeValue { Center, Answer, Decline }

class IncomingCallActivity : ComponentActivity() {

    private val callManager: CallManager by inject()
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: run {
            finish()
            return
        }
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Unknown"
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val callerAvatarUrl = intent.getStringExtra(EXTRA_CALLER_AVATAR)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)

        startRinging()

        setContent {
            MainTheme(darkTheme = true) {
                IncomingCallScreen(
                    roomName = roomName,
                    callerName = callerName,
                    callerAvatarUrl = callerAvatarUrl,
                    onAccept = {
                        stopRinging()
                        acceptCall(roomId, roomName, eventId)
                    },
                    onDecline = {
                        stopRinging()
                        declineCall(roomId)
                    }
                )
            }
        }
    }

    private fun startRinging() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 500, 1000, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_RINGTONE)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }

        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, ringtoneUri)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRinging() {
        vibrator?.cancel()
        ringtone?.stop()
    }

    private fun acceptCall(roomId: String, roomName: String, eventId: String?) {
        lifecycleScope.launch {
            AndroidNotificationHelper.cancelCallNotification(this@IncomingCallActivity, roomId)

            val success = callManager.startOrJoinCall(
                roomId = roomId,
                roomName = roomName,
                intent = CallIntent.JoinExisting,
                elementCallUrl = null,
                parentUrl = null,
                languageTag = Locale.getDefault().toLanguageTag(),
                theme = "dark"
            )

            if (success) {
                val intent = Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    data = Uri.Builder()
                        .scheme("mages")
                        .authority("room")
                        .appendQueryParameter("id", roomId)
                        .appendQueryParameter("join_call", "1")
                        .build()
                }
                startActivity(intent)
            }

            finish()
        }
    }

    private fun declineCall(roomId: String) {
        AndroidNotificationHelper.cancelCallNotification(this, roomId)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_AVATAR = "caller_avatar"
        const val EXTRA_EVENT_ID = "event_id"

        fun createIntent(
            context: Context,
            roomId: String,
            roomName: String,
            callerName: String,
            callerAvatarUrl: String?,
            eventId: String?
        ): Intent {
            return Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, roomName)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_AVATAR, callerAvatarUrl)
                putExtra(EXTRA_EVENT_ID, eventId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
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
    callerAvatarUrl: String?,
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

    val bgTransition = rememberInfiniteTransition(label = "bg")
    val bgShift by bgTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgShift"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.25f),
                        scheme.tertiary.copy(alpha = 0.12f),
                        scheme.surface
                    ),
                    center = androidx.compose.ui.geometry.Offset(
                        x = 0.2f + 0.6f * bgShift,
                        y = 0.15f + 0.35f * (1f - bgShift)
                    )
                )
            )
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            TopBannerExpressive()

            CallerHeroExpressive(
                callerName = callerName,
                roomName = roomName,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
//                    ElevatedSuggestionChip(
//                        onClick = { /* TODO: quick reply */ },
//                        label = { Text("Message") },
//                        icon = {
//                            Icon(
//                                painter = painterResource(id = R.drawable.outline_business_messages_24),
//                                contentDescription = null
//                            )
//                        }
//                    )
//                    ElevatedFilterChip(
//                        selected = false,
//                        onClick = { /* TODO: silence ring */ },
//                        label = { Text("Mute") },
//                        leadingIcon = {
//                            Icon(
//                                painter = painterResource(id = R.drawable.outline_music_off_24),
//                                contentDescription = null
//                            )
//                        }
//                    )
                }

                if (talkBackOn) {
                    BigButtonsRowExpressive(
                        enabled = !handlingAction,
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
                    SwipeToAnswerOrDeclineExpressive(
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
//                    Spacer(Modifier.height(10.dp))
//                    Text(
//                        text = "Swipe right to answer • left to decline",
//                        style = MaterialTheme.typography.bodyMedium,
//                        color = scheme.onSurfaceVariant
//                    )
                }

                Spacer(Modifier.height(6.dp))
            }
        }

        AnimatedVisibility(
            visible = handlingAction,
            modifier = Modifier.align(Alignment.Center)
        ) {
            ConnectingOverlayExpressive()
        }
    }
}

@Composable
private fun TopBannerExpressive() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        AssistChip(
            onClick = { /* maybe go to room? */ },
            label = { Text("Incoming call", fontWeight = FontWeight.SemiBold) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.outline_phone_callback_24),
                    contentDescription = null
                )
            }
        )
    }
}

@Composable
private fun CallerHeroExpressive(
    callerName: String,
    roomName: String
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

            Surface(
                shape = avatarShape,
                tonalElevation = 10.dp,
                shadowElevation = 14.dp,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier
                    .size(132.dp)
                    .rotate(wobble * 0.6f)
                    .scale(breathe)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = callerName.trim().take(2).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = scheme.onSurface
                    )
                }
            }
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
private fun BigButtonsRowExpressive(
    enabled: Boolean,
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
            Text("Decline", fontWeight = FontWeight.SemiBold)
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
                painter = painterResource(id = R.drawable.outline_video_call_24),
                contentDescription = null
            )
            Spacer(Modifier.size(10.dp))
            Text("Answer", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectingOverlayExpressive() {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.padding(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Text("Connecting…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SwipeToAnswerOrDeclineExpressive(
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
                contentDescription = "Incoming call slider for $callerName"
                customActions = listOf(
                    CustomAccessibilityAction("Answer") { onAnswer(); true },
                    CustomAccessibilityAction("Decline") { onDecline(); true }
                )
            },
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = scheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                        tint = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Decline", color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Answer", color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.outline_chevron_right_24),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                color = scheme.surface,
                modifier = Modifier
                    .size(knobSize)
                    .scale(knobScale)
                    .offset { IntOffset(rawOffset.roundToInt(), 0) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_phone_in_talk_24),
                        contentDescription = null,
                        tint = scheme.onSurface
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
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            talkBackOn = enabled
        }
        mgr.addTouchExplorationStateChangeListener(listener)
        onDispose { mgr.removeTouchExplorationStateChangeListener(listener) }
    }

    return talkBackOn
}