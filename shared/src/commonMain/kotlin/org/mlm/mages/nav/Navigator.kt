package org.mlm.mages.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.scene.Scene
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.mlm.mages.MatrixService
import org.mlm.mages.calls.CallManager
import org.mlm.mages.matrix.CallIntent

const val NAV_ANIM_DURATION = 450

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Login : Route
    @Serializable data object Rooms : Route
    @Serializable data class Room(val roomId: String, val name: String, val eventId: String? = null) : Route
    @Serializable data object Security : Route
    @Serializable data object Discover : Route
    @Serializable data class MediaGallery(val roomId: String) : Route
    @Serializable
    data class ForwardPicker(
        val roomId: String,
        val eventIds: List<String>
    ) : Route
    @Serializable data class RoomInfo(val roomId: String) : Route
    @Serializable data class ImagePackEditor(val roomId: String) : Route
    @Serializable data class Thread(val roomId: String, val rootEventId: String, val roomName: String, val focusedEventId: String? = null) : Route

    @Serializable data object Spaces : Route
    @Serializable data class SpaceDetail(val spaceId: String, val spaceName: String) : Route
    @Serializable data class SpaceSettings(val spaceId: String) : Route

    @Serializable data object Search : Route

    @Serializable data object NotificationRules : Route
    @Serializable data object MediaCache : Route
    @Serializable data object Bookmarks : Route
    @Serializable data object Wallpaper : Route
    @Serializable data object MessageMenu : Route
    @Serializable data object QuickReactions : Route
    @Serializable data class RoomWallpaper(val roomId: String) : Route
}

fun <T : NavKey> NavBackStack<T>.replaceTop(key: T) {
    if (isEmpty()) add(key) else set(lastIndex, key)
}

fun <T : NavKey> NavBackStack<T>.popUntil(predicate: (T) -> Boolean) {
    while (size > 1 && !predicate(this[lastIndex])) {
        removeAt(lastIndex)
    }
}

// A link switches the open conversation rather than stacking one on top of another, so back
// leaves the chat instead of walking into the room it was opened from. Whatever the chat was
// reached through (room list, search, a space) is kept as the new bottom of the stack.
fun <T : NavKey> NavBackStack<T>.openRoom(key: T) {
    val chatIndex = indexOfLast { it is Route.Room }
    if (chatIndex < 0) {
        add(key)
        return
    }
    while (size > chatIndex + 1) {
        removeAt(lastIndex)
    }
    set(chatIndex, key)
}

data class DeepLinkAction(
    val roomId: String,
    val eventId: String? = null,
    val threadId: String? = null,
    val joinCall: Boolean = false,
    val voiceOnly: Boolean = false,
    val incoming: Boolean = false
)

// Deep links: push Room keys when links arrive
@Composable
fun BindDeepLinks(
    backStack: NavBackStack<NavKey>,
    deepLinks: Flow<DeepLinkAction>?,
    callManager: CallManager,
    service: MatrixService,
    widgetTheme: String,
    languageTag: String,
    elementCallUrl: String?,
    parentCallUrl: String?,
    onRequestVideoCallPermissions: ((() -> Unit) -> Unit)? = null,
    onRequestVoiceCallPermissions: ((() -> Unit) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(deepLinks) {
        deepLinks?.collectLatest { action ->
            val top = backStack.lastOrNull()
            val threadId = action.threadId
            if (threadId != null) {
                if (top is Route.Thread &&
                    top.roomId == action.roomId &&
                    top.rootEventId == threadId
                ) {
                    if (action.eventId != null) {
                        backStack.replaceTop(top.copy(focusedEventId = action.eventId))
                    }
                } else {
                    val threadRoute = Route.Thread(
                        roomId = action.roomId,
                        rootEventId = threadId,
                        roomName = action.roomId,
                        focusedEventId = action.eventId
                    )
                    if (top is Route.Room && top.roomId == action.roomId) {
                        backStack.add(threadRoute)
                    } else {
                        backStack.openRoom(threadRoute)
                    }
                }
            } else {
                val alreadyThere = top is Route.Room && top.roomId == action.roomId
                if (alreadyThere) {
                    if (action.eventId != null) {
                        backStack.replaceTop(Route.Room(
                            roomId = action.roomId,
                            name = action.roomId,
                            eventId = action.eventId
                        ))
                    }
                } else {
                    backStack.openRoom(Route.Room(
                        roomId = action.roomId,
                        name = action.roomId, // Will be updated by RoomViewModel
                        eventId = action.eventId
                    ))
                }
            }

            val callIntent = if (action.voiceOnly) CallIntent.JoinExistingVoiceDm
            else CallIntent.JoinExisting

            if (action.joinCall && !callManager.isInCall(action.roomId)) {
                val joinCallAction: () -> Unit = {
                    coroutineScope.launch {
                        val roomName = runCatching {
                            service.portOrNull?.roomProfile(action.roomId)?.name
                        }.getOrDefault(null) ?: action.roomId

                        repeat(10) { attempt ->
                            val ok = runCatching {
                                callManager.startOrJoinCall(
                                    roomId = action.roomId,
                                    roomName = roomName,
                                    intent = callIntent,
                                    elementCallUrl = elementCallUrl,
                                    parentUrl = parentCallUrl,
                                    languageTag = languageTag,
                                    theme = widgetTheme,
                                    incoming = action.incoming
                                )
                            }.getOrDefault(false)

                            if (ok) return@launch

                            delay(400L + attempt * 150L)
                        }
                    }
                }

                val requestPermissions =
                    if (action.voiceOnly) onRequestVoiceCallPermissions
                    else onRequestVideoCallPermissions

                if (requestPermissions != null) {
                    requestPermissions.invoke(joinCallAction)
                } else {
                    joinCallAction()
                }
            }
        }
    }
}

/**
 * Per-entry metadata for Login to force fade animations for forward/pop/predictive-pop.
 * This avoids trying to read private NavEntry.key and keeps the exact fade you had.
 */
fun loginEntryFadeMetadata(): Map<String, Any> {
    val fade: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = {
        fadeIn(tween(NAV_ANIM_DURATION)) togetherWith fadeOut(tween(NAV_ANIM_DURATION))
    }
    return NavDisplay.transitionSpec { fade() } +
            NavDisplay.popTransitionSpec { fade() } +
            NavDisplay.predictivePopTransitionSpec { _ -> fade() }
}

val routeSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Route.Login::class, Route.Login.serializer())
        subclass(Route.Rooms::class, Route.Rooms.serializer())
        subclass(Route.Room::class, Route.Room.serializer())
        subclass(Route.Security::class, Route.Security.serializer())
        subclass(Route.Discover::class, Route.Discover.serializer())
        subclass(Route.RoomInfo::class, Route.RoomInfo.serializer())
        subclass(Route.Thread::class, Route.Thread.serializer())
        subclass(Route.Spaces::class, Route.Spaces.serializer())
        subclass(Route.SpaceDetail::class, Route.SpaceDetail.serializer())
        subclass(Route.SpaceSettings::class, Route.SpaceSettings.serializer())
        subclass(Route.MediaGallery::class, Route.MediaGallery.serializer())
        subclass(Route.Search::class, Route.Search.serializer())
        subclass(Route.ForwardPicker::class, Route.ForwardPicker.serializer())
        subclass(Route.NotificationRules::class, Route.NotificationRules.serializer())
        subclass(Route.MediaCache::class, Route.MediaCache.serializer())
        subclass(Route.MessageMenu::class, Route.MessageMenu.serializer())
        subclass(Route.QuickReactions::class, Route.QuickReactions.serializer())
    }
}

val navSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = routeSerializersModule
}