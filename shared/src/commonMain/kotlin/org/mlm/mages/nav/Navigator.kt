package org.mlm.mages.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.scene.Scene
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
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
    @Serializable data object Invites : Route
    @Serializable data class MediaGallery(val roomId: String) : Route
    @Serializable
    data class ForwardPicker(
        val roomId: String,
        val eventIds: List<String>
    ) : Route
    @Serializable data class RoomInfo(val roomId: String) : Route
    @Serializable data class Thread(val roomId: String, val rootEventId: String, val roomName: String) : Route

    @Serializable data object Spaces : Route
    @Serializable data class SpaceDetail(val spaceId: String, val spaceName: String) : Route
    @Serializable data class SpaceSettings(val spaceId: String) : Route

    @Serializable data object Search : Route
}

fun <T : NavKey> NavBackStack<T>.replaceTop(key: T) {
    if (isEmpty()) add(key) else set(lastIndex, key)
}

fun <T : NavKey> NavBackStack<T>.popUntil(predicate: (T) -> Boolean) {
    while (size > 1 && !predicate(this[lastIndex])) {
        removeAt(lastIndex)
    }
}

data class DeepLinkAction(
    val roomId: String,
    val eventId: String? = null,
    val joinCall: Boolean = false
)

// Deep links: push Room keys when links arrive
@Composable
fun BindDeepLinks(
    backStack: NavBackStack<NavKey>,
    deepLinks: Flow<DeepLinkAction>?,
    callManager: CallManager,
    widgetTheme: String,
    languageTag: String,
    elementCallUrl: String?,
    parentCallUrl: String?
) {
    LaunchedEffect(deepLinks) {
        deepLinks?.collectLatest { action ->
            backStack.add(Route.Room(
                roomId = action.roomId,
                name = action.roomId, // Will be updated by RoomViewModel
                eventId = action.eventId
            ))

            if (action.joinCall && !callManager.isInCall(action.roomId)) {
                // Retry in case room isn't available yet right after restore.
                repeat(10) { attempt ->
                    val ok = runCatching {
                        callManager.startOrJoinCall(
                            roomId = action.roomId,
                            roomName = action.roomId,
                            intent = CallIntent.JoinExisting,
                            elementCallUrl = elementCallUrl,
                            parentUrl = parentCallUrl,
                            languageTag = languageTag,
                            theme = widgetTheme
                        )
                    }.getOrDefault(false)

                    if (ok) return@collectLatest

                    // backoff
                    delay(400L + attempt * 150L)
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
        subclass(Route.Invites::class, Route.Invites.serializer())
        subclass(Route.RoomInfo::class, Route.RoomInfo.serializer())
        subclass(Route.Thread::class, Route.Thread.serializer())
        subclass(Route.Spaces::class, Route.Spaces.serializer())
        subclass(Route.SpaceDetail::class, Route.SpaceDetail.serializer())
        subclass(Route.SpaceSettings::class, Route.SpaceSettings.serializer())
        subclass(Route.MediaGallery::class, Route.MediaGallery.serializer())
        subclass(Route.Search::class, Route.Search.serializer())
        subclass(Route.ForwardPicker::class, Route.ForwardPicker.serializer())
    }
}

val navSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = routeSerializersModule
}