#![allow(dead_code)]

use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::Duration,
};

use matrix_sdk::{
    Client as SdkClient, EncryptionState, Room, RoomMemberships, RoomState,
    notification_settings::RoomNotificationMode,
    ruma::{
        EventId, OwnedEventId, OwnedRoomAliasId, OwnedRoomId, OwnedRoomOrAliasId, OwnedServerName,
        OwnedUserId, RoomVersionId, SpaceChildOrder, UserId,
        api::client::{
            directory::get_public_rooms_filtered,
            presence::{get_presence::v3 as get_presence_v3, set_presence::v3 as set_presence_v3},
            receipt::create_receipt::v3::ReceiptType,
            room::{Visibility, upgrade_room::v3 as upgrade_room_v3},
        },
        directory::{Filter, PublicRoomsChunk},
        events::{
            AnyMessageLikeEventContent,
            ignored_user_list::IgnoredUserListEventContent,
            poll::{
                unstable_end::UnstablePollEndEventContent,
                unstable_response::UnstablePollResponseEventContent,
            },
            receipt::ReceiptThread,
            relation::Thread as ThreadRel,
            room::{
                message::{
                    Relation as MsgRelation, RoomMessageEventContent,
                    RoomMessageEventContentWithoutRelation as MsgNoRel,
                },
                name::RoomNameEventContent,
                pinned_events::RoomPinnedEventsEventContent,
                power_levels::UserPowerLevel,
                topic::RoomTopicEventContent,
            },
            space::child::SpaceChildEventContent,
        },
        presence::PresenceState,
        room::{JoinRuleSummary, RoomType},
    },
    send_queue::SendHandle as SdkSendHandle,
};
use matrix_sdk_ui::{
    sync_service::SyncService,
    timeline::{RoomExt as _, Timeline},
};
use tracing::warn;

use crate::errors::{IntoFfi, OptionFfi};
use crate::{
    DirectoryUser, FfiError, FfiRoomNotificationMode, INITIAL_BACK_PAGINATION, KnockRequestSummary,
    LiveLocationBeaconState, MemberSummary, MessageEvent, OwnReceipt, PollDefinition,
    PredecessorRoomInfo, Presence, PresenceInfo, PublicRoom, PublicRoomsPage, ReactionSummary,
    RoomDirectoryVisibility, RoomHistoryVisibility, RoomJoinRule, RoomPowerLevelChanges,
    RoomPowerLevels, RoomPreview, RoomPreviewMembership, RoomProfile, RoomSummary, RoomTags,
    RoomUpgradeLinks, SearchHit, SearchPage, SeenByEntry, SendState, SendUpdate, SpaceChildInfo,
    SpaceHierarchyPage, SpaceInfo, SuccessorRoomInfo, ThreadPage, ThreadSummary, UnreadStats,
    build_unstable_poll_content, map_event_id_via_timeline, map_timeline_event,
    paginate_backwards_visible, timeline_event_filter,
};

#[cfg(not(target_family = "wasm"))]
macro_rules! spawn_detached_core {
    ($fut:expr) => {{
        let _ = tokio::spawn($fut);
    }};
}
#[cfg(target_family = "wasm")]
macro_rules! spawn_detached_core {
    ($fut:expr) => {{
        wasm_bindgen_futures::spawn_local($fut);
    }};
}

#[derive(Clone)]
pub struct TimelineManager {
    pub(crate) client: SdkClient,
    timelines: Arc<Mutex<HashMap<OwnedRoomId, Arc<Timeline>>>>,
    members_fetched: Arc<Mutex<HashSet<OwnedRoomId>>>,
}

impl TimelineManager {
    pub fn new(client: SdkClient) -> Self {
        Self {
            client,
            timelines: Arc::new(Mutex::new(HashMap::new())),
            members_fetched: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    pub fn clear(&self) {
        self.timelines.lock().unwrap().clear();
        self.members_fetched.lock().unwrap().clear();
    }

    pub async fn timeline_for(&self, room_id: &OwnedRoomId) -> Option<Arc<Timeline>> {
        if let Some(tl) = self.timelines.lock().unwrap().get(room_id).cloned() {
            let should_fetch = {
                let mut s = self.members_fetched.lock().unwrap();
                if s.contains(room_id) {
                    false
                } else {
                    s.insert(room_id.clone());
                    true
                }
            };
            if should_fetch {
                let tlc = tl.clone();
                spawn_detached_core!(async move {
                    let _ = tlc.fetch_members().await;
                });
            }
            return Some(tl);
        }

        let room = self.client.get_room(room_id)?;
        let tl = Arc::new(
            room.timeline_builder()
                .event_filter(timeline_event_filter)
                .build()
                .await
                .ok()?,
        );
        let _ = tl.paginate_backwards(INITIAL_BACK_PAGINATION).await;

        {
            let mut s = self.members_fetched.lock().unwrap();
            if !s.contains(room_id) {
                s.insert(room_id.clone());
                let tlc = tl.clone();
                spawn_detached_core!(async move {
                    let _ = tlc.fetch_members().await;
                });
            }
        }

        self.timelines
            .lock()
            .unwrap()
            .insert(room_id.clone(), tl.clone());
        Some(tl)
    }
}

pub struct CoreClient {
    pub sdk: SdkClient,
    pub timeline_mgr: TimelineManager,
    pub sync_service: Arc<Mutex<Option<Arc<SyncService>>>>,
    pub send_handles_by_txn: Arc<Mutex<HashMap<String, SdkSendHandle>>>,
    pub live_location_beacons: Arc<Mutex<HashMap<String, LiveLocationBeaconState>>>,
}

impl CoreClient {
    pub fn new(sdk: SdkClient) -> Self {
        let timeline_mgr = TimelineManager::new(sdk.clone());
        Self {
            sdk,
            timeline_mgr,
            sync_service: Arc::new(Mutex::new(None)),
            send_handles_by_txn: Arc::new(Mutex::new(HashMap::new())),
            live_location_beacons: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub(crate) fn parse_rid(room_id: &str) -> Result<OwnedRoomId, FfiError> {
        OwnedRoomId::try_from(room_id).ffi()
    }

    pub(crate) fn parse_eid(event_id: &str) -> Result<OwnedEventId, FfiError> {
        OwnedEventId::try_from(event_id).ffi()
    }

    pub(crate) fn parse_uid(user_id: &str) -> Result<OwnedUserId, FfiError> {
        user_id.parse::<OwnedUserId>().ffi()
    }

    pub(crate) fn require_room(&self, room_id: &str) -> Result<Room, FfiError> {
        let rid = Self::parse_rid(room_id)?;
        self.sdk.get_room(&rid).or_ffi("room not found")
    }

    pub(crate) async fn require_timeline(&self, room_id: &str) -> Result<Arc<Timeline>, FfiError> {
        self.timeline(room_id)
            .await
            .or_ffi("timeline not available")
    }

    pub fn user_id_str(&self) -> String {
        self.sdk
            .user_id()
            .map(|u| u.to_string())
            .unwrap_or_default()
    }

    pub fn whoami(&self) -> Option<String> {
        self.sdk.user_id().map(|u| u.to_string())
    }

    pub fn is_logged_in(&self) -> bool {
        self.sdk.session_meta().is_some()
    }

    pub fn room(&self, room_id: &str) -> Option<Room> {
        OwnedRoomId::try_from(room_id)
            .ok()
            .and_then(|rid| self.sdk.get_room(&rid))
    }

    pub async fn timeline(&self, room_id: &str) -> Option<Arc<Timeline>> {
        let rid = OwnedRoomId::try_from(room_id).ok()?;
        self.timeline_mgr.timeline_for(&rid).await
    }

    pub async fn ensure_sync_service(&self) {
        if self.sync_service.lock().unwrap().is_some() {
            return;
        }
        if self.sdk.session_meta().is_none() {
            return;
        }
        let builder = SyncService::builder(self.sdk.clone()).with_offline_mode();
        match builder.build().await {
            Ok(svc) => {
                let mut g = self.sync_service.lock().unwrap();
                if g.is_none() {
                    g.replace(svc.into());
                }
            }
            Err(e) => warn!("ensure_sync_service: failed: {e:?}"),
        }
    }

    pub fn resolve_other_user(&self, other_user_id: Option<String>) -> Option<OwnedUserId> {
        other_user_id.and_then(|uid| uid.parse().ok())
    }

    pub async fn rooms(&self) -> Vec<RoomSummary> {
        let mut out = Vec::new();
        for r in self.sdk.joined_rooms() {
            let name = r
                .display_name()
                .await
                .map(|dn| dn.to_string())
                .unwrap_or_else(|_| r.room_id().to_string());
            out.push(RoomSummary {
                id: r.room_id().to_string(),
                name,
            });
        }
        out
    }

    pub async fn send_message(
        &self,
        room_id: String,
        body: String,
        formatted_body: Option<String>,
    ) -> Result<(), FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let content = if let Some(fmt) = formatted_body {
            RoomMessageEventContent::text_html(body, fmt)
        } else {
            RoomMessageEventContent::text_plain(body)
        };
        let handle = tl.send(content.into()).await.ffi()?;
        let items = tl.items().await;
        if let Some(last) = items.last() {
            if let Some(ev) = last.as_event() {
                if ev.event_id().is_none() {
                    if let Some(txn) = ev.transaction_id() {
                        self.send_handles_by_txn
                            .lock()
                            .unwrap()
                            .insert(txn.to_string(), handle);
                    }
                }
            }
        }
        Ok(())
    }

    pub async fn reply(
        &self,
        room_id: String,
        in_reply_to: String,
        body: String,
        formatted_body: Option<String>,
    ) -> Result<(), FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let reply_to =
            EventId::parse(&in_reply_to).map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let content = if let Some(fmt) = formatted_body {
            MsgNoRel::text_html(body, fmt)
        } else {
            MsgNoRel::text_plain(body)
        };
        tl.send_reply(content, reply_to.to_owned()).await.ffi()
    }

    pub async fn edit(
        &self,
        room_id: String,
        target_event_id: String,
        new_body: String,
        formatted_body: Option<String>,
    ) -> Result<(), FfiError> {
        use matrix_sdk::room::edit::EditedContent;
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let eid = EventId::parse(&target_event_id)
            .map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let item = tl
            .item_by_event_id(&eid)
            .await
            .ok_or_else(|| FfiError::Msg("event not found".into()))?;
        let edited = EditedContent::RoomMessage(if let Some(fmt) = formatted_body {
            MsgNoRel::text_html(new_body, fmt)
        } else {
            MsgNoRel::text_plain(new_body)
        });
        tl.edit(&item.identifier(), edited).await.ffi()
    }

    pub async fn redact(
        &self,
        room_id: String,
        event_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let eid =
            EventId::parse(&event_id).map_err(|_| FfiError::Msg("invalid event id".into()))?;
        room.redact(&eid, reason.as_deref(), None)
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn react(
        &self,
        room_id: String,
        event_id: String,
        emoji: String,
    ) -> Result<(), FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let eid =
            EventId::parse(&event_id).map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let item = tl
            .item_by_event_id(&eid)
            .await
            .ok_or_else(|| FfiError::Msg("event not found".into()))?;
        tl.toggle_reaction(&item.identifier(), &emoji)
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn send_thread_text(
        &self,
        room_id: String,
        root_event_id: String,
        body: String,
        reply_to_event_id: Option<String>,
        latest_event_id: Option<String>,
        formatted_body: Option<String>,
    ) -> Result<(), FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let root = OwnedEventId::try_from(root_event_id)
            .map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let mut content = if let Some(fmt) = formatted_body {
            RoomMessageEventContent::text_html(body, fmt)
        } else {
            RoomMessageEventContent::text_plain(body)
        };
        let relation = if let Some(reply_to) = reply_to_event_id {
            if let Ok(eid) = OwnedEventId::try_from(reply_to) {
                MsgRelation::Thread(ThreadRel::reply(root, eid))
            } else {
                MsgRelation::Thread(ThreadRel::without_fallback(root))
            }
        } else if let Some(latest) = latest_event_id {
            if let Ok(eid) = OwnedEventId::try_from(latest) {
                MsgRelation::Thread(ThreadRel::plain(root, eid))
            } else {
                MsgRelation::Thread(ThreadRel::without_fallback(root))
            }
        } else {
            MsgRelation::Thread(ThreadRel::without_fallback(root))
        };
        content.relates_to = Some(relation);
        tl.send(content.into()).await.ffi().map(|_| ())
    }

    pub async fn mark_read(&self, room_id: String) -> Result<(), FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        tl.mark_as_read(ReceiptType::ReadPrivate)
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn mark_read_at(&self, room_id: String, event_id: String) -> Result<(), FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let eid =
            EventId::parse(&event_id).map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.send_single_receipt(
            ReceiptType::ReadPrivate,
            ReceiptThread::Unthreaded,
            eid.to_owned(),
        )
        .await
        .ffi()
    }

    pub async fn mark_fully_read_at(
        &self,
        room_id: String,
        event_id: String,
    ) -> Result<(), FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let eid = OwnedEventId::try_from(event_id.as_str())
            .map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let receipts = matrix_sdk::room::Receipts::new()
            .private_read_receipt(eid.clone())
            .fully_read_marker(eid);
        room.send_multiple_receipts(receipts).await.ffi()
    }

    pub async fn set_mark_unread(&self, room_id: String, unread: bool) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.set_unread_flag(unread).await.ffi()
    }

    pub async fn is_marked_unread(&self, room_id: String) -> Result<Option<bool>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(Some(room.is_marked_unread()))
    }

    pub async fn own_last_read(&self, room_id: String) -> OwnReceipt {
        let empty = OwnReceipt {
            event_id: None,
            ts_ms: None,
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return empty;
        };
        let Some(tl) = self.timeline_mgr.timeline_for(&rid).await else {
            return empty;
        };
        let Some(me) = self.sdk.user_id() else {
            return empty;
        };
        if let Some((eid, receipt)) = tl.latest_user_read_receipt(me).await {
            OwnReceipt {
                event_id: Some(eid.to_string()),
                ts_ms: receipt.ts.map(|t| t.0.into()),
            }
        } else {
            empty
        }
    }

    pub async fn is_event_read_by(
        &self,
        room_id: String,
        event_id: String,
        user_id: String,
    ) -> Result<(), FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let eid =
            EventId::parse(&event_id).map_err(|_| FfiError::Msg("invalid event id".into()))?;
        let uid = user_id
            .parse::<OwnedUserId>()
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        let tl = self
            .timeline_mgr
            .timeline_for(&rid)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let latest = tl
            .latest_user_read_receipt_timeline_event_id(&uid)
            .await
            .ok_or_else(|| FfiError::Msg("no read receipt found".into()))?;
        let items = tl.items().await;
        let mut idx_latest = None;
        let mut idx_mine = None;
        for (i, it) in items.iter().enumerate() {
            if let Some(ev) = it.as_event() {
                if let Some(e) = ev.event_id() {
                    if e == &latest {
                        idx_latest = Some(i);
                    }
                    if e == &eid {
                        idx_mine = Some(i);
                    }
                }
            }
            if idx_latest.is_some() && idx_mine.is_some() {
                break;
            }
        }
        if matches!((idx_mine, idx_latest), (Some(i_m), Some(i_l)) if i_l >= i_m) {
            Ok(())
        } else {
            Err(FfiError::Msg("event not read by user".into()))
        }
    }

    pub async fn paginate_backwards(&self, room_id: String, count: u16) -> Result<bool, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let tl = self
            .timeline_mgr
            .timeline_for(&rid)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        let me = self.user_id_str();
        Ok(paginate_backwards_visible(&tl, &rid, &me, count as usize).await)
    }

    pub async fn paginate_forwards(&self, room_id: String, count: u16) -> Result<bool, FfiError> {
        let tl = self
            .timeline(&room_id)
            .await
            .ok_or_else(|| FfiError::Msg("timeline not found".into()))?;
        tl.paginate_forwards(count).await.ffi()
    }

    pub async fn recent_events(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return vec![];
        };
        let Some(tl) = self.timeline_mgr.timeline_for(&rid).await else {
            return vec![];
        };
        let me = self.user_id_str();
        let (items, _) = tl.subscribe().await;
        let mut out: Vec<MessageEvent> = items
            .iter()
            .rev()
            .filter_map(|it| {
                it.as_event().and_then(|ev| {
                    map_timeline_event(ev, rid.as_str(), Some(&it.unique_id().0.to_string()), &me)
                })
            })
            .take(limit as usize)
            .collect();
        out.reverse();
        out
    }

    pub async fn set_typing(&self, room_id: String, typing: bool) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.typing_notice(typing).await.ffi()
    }

    async fn build_room_profile(&self, room: &Room) -> Result<RoomProfile, FfiError> {
        let rid = room.room_id();
        let name = room
            .display_name()
            .await
            .map(|d| d.to_string())
            .unwrap_or_else(|_| rid.to_string());
        let topic = room.topic();
        let member_count = room.joined_members_count();
        let is_encrypted = matches!(room.encryption_state(), EncryptionState::Encrypted);
        let is_dm = room.is_direct().await.unwrap_or(false);
        let mut avatar_url = room.avatar_url().map(|m| m.to_string());
        let canonical_alias = room.canonical_alias().map(|a| a.to_string());
        let alt_aliases = room.alt_aliases().iter().map(|a| a.to_string()).collect();
        let room_version = room.version().map(|v| v.to_string());

        if avatar_url.is_none() && is_dm {
            if let Some(me) = self.sdk.user_id() {
                let members = room.members(RoomMemberships::ACTIVE).await.ffi()?;
                if let Some(peer) = members.into_iter().find(|m| m.user_id() != me) {
                    avatar_url = peer.avatar_url().map(|mxc| mxc.to_string());
                }
            }
        }

        Ok(RoomProfile {
            room_id: rid.to_string(),
            name,
            topic,
            member_count,
            is_encrypted,
            is_dm,
            avatar_url,
            canonical_alias,
            alt_aliases,
            room_version,
        })
    }

    pub async fn room_profile(&self, room_id: String) -> Result<Option<RoomProfile>, FfiError> {
        let rid = Self::parse_rid(&room_id)?;
        let room = self.sdk.get_room(&rid).or_ffi("room not found")?;
        Ok(Some(self.build_room_profile(&room).await?))
    }

    pub async fn list_members(&self, room_id: String) -> Result<Vec<MemberSummary>, FfiError> {
        let room = self.require_room(&room_id)?;
        let me = self.sdk.user_id();
        let members = room.members(RoomMemberships::ACTIVE).await.ffi()?;
        Ok(members
            .into_iter()
            .map(|m| MemberSummary {
                user_id: m.user_id().to_string(),
                display_name: m.display_name().map(|n| n.to_string()),
                avatar_url: m.avatar_url().map(|u| u.to_string()),
                is_me: me.map(|u| u == m.user_id()).unwrap_or(false),
                membership: m.membership().to_string(),
            })
            .collect())
    }

    pub async fn list_invited(&self) -> Result<Vec<RoomProfile>, FfiError> {
        let mut out = Vec::new();
        for room in self.sdk.invited_rooms() {
            out.push(self.build_room_profile(&room).await?);
        }
        Ok(out)
    }

    pub async fn list_knock_requests(
        &self,
        room_id: String,
    ) -> Result<Vec<KnockRequestSummary>, FfiError> {
        let room = self.require_room(&room_id)?;
        let members = room.members(RoomMemberships::KNOCK).await.ffi()?;
        Ok(members
            .into_iter()
            .filter_map(|member| {
                let event = member.event();
                let event_id = event.event_id()?;
                Some(KnockRequestSummary {
                    event_id: event_id.to_string(),
                    user_id: member.user_id().to_string(),
                    display_name: member.display_name().map(|n| n.to_string()),
                    avatar_url: member.avatar_url().map(|u| u.to_string()),
                    reason: event.reason().map(|r| r.to_string()),
                    ts_ms: event.timestamp().map(u64::from),
                    is_seen: false,
                })
            })
            .collect())
    }

    pub async fn room_unread_stats(
        &self,
        room_id: String,
    ) -> Result<Option<UnreadStats>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(Some(UnreadStats {
            messages: room.num_unread_messages(),
            notifications: room.num_unread_notifications(),
            mentions: room.num_unread_mentions(),
        }))
    }

    pub async fn room_tags(&self, room_id: String) -> Result<Option<RoomTags>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(Some(RoomTags {
            is_favourite: room.is_favourite(),
            is_low_priority: room.is_low_priority(),
        }))
    }

    pub async fn set_room_favourite(&self, room_id: String, fav: bool) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.set_is_favourite(fav, None).await.ffi()
    }

    pub async fn set_room_low_priority(&self, room_id: String, low: bool) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.set_is_low_priority(low, None).await.ffi()
    }

    pub async fn room_notification_mode(
        &self,
        room_id: String,
    ) -> Result<Option<FfiRoomNotificationMode>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let mode = room
            .notification_mode()
            .await
            .ok_or_else(|| FfiError::Msg("notification mode unavailable".into()))?;
        Ok(Some(match mode {
            RoomNotificationMode::AllMessages => FfiRoomNotificationMode::AllMessages,
            RoomNotificationMode::MentionsAndKeywordsOnly => {
                FfiRoomNotificationMode::MentionsAndKeywordsOnly
            }
            RoomNotificationMode::Mute => FfiRoomNotificationMode::Mute,
        }))
    }

    pub async fn set_room_notification_mode(
        &self,
        room_id: String,
        mode: FfiRoomNotificationMode,
    ) -> Result<(), FfiError> {
        let rid = Self::parse_rid(&room_id)?;
        let sdk_mode = match mode {
            FfiRoomNotificationMode::AllMessages => RoomNotificationMode::AllMessages,
            FfiRoomNotificationMode::MentionsAndKeywordsOnly => {
                RoomNotificationMode::MentionsAndKeywordsOnly
            }
            FfiRoomNotificationMode::Mute => RoomNotificationMode::Mute,
        };
        self.sdk
            .notification_settings()
            .await
            .set_room_notification_mode(rid.as_ref(), sdk_mode)
            .await
            .ffi()
    }

    pub async fn get_pinned_events(&self, room_id: String) -> Vec<String> {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return vec![];
        };
        let Some(room) = self.sdk.get_room(&rid) else {
            return vec![];
        };
        room.pinned_event_ids()
            .map(|ids| ids.iter().map(|id| id.to_string()).collect())
            .unwrap_or_default()
    }

    pub async fn set_pinned_events(
        &self,
        room_id: String,
        event_ids: Vec<String>,
    ) -> Result<(), FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let parsed: Vec<_> = event_ids
            .iter()
            .filter_map(|id| EventId::parse(id).ok())
            .collect();
        room.send_state_event(RoomPinnedEventsEventContent::new(parsed))
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn set_room_name(&self, room_id: String, name: String) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.send_state_event(RoomNameEventContent::new(name))
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn set_room_topic(&self, room_id: String, topic: String) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.send_state_event(RoomTopicEventContent::new(topic))
            .await
            .ffi()
            .map(|_| ())
    }

    pub async fn dm_peer_user_id(&self, room_id: String) -> Result<Option<String>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let me = self
            .sdk
            .user_id()
            .ok_or_else(|| FfiError::Msg("not logged in".into()))?;
        let members = room.members(RoomMemberships::ACTIVE).await.ffi()?;
        Ok(members
            .into_iter()
            .find(|m| m.user_id() != me)
            .map(|m| m.user_id().to_string()))
    }

    pub async fn dm_peer_avatar_url(room: &Room, me: Option<&UserId>) -> Option<String> {
        let peer = room
            .direct_targets()
            .iter()
            .filter_map(|t| t.as_user_id())
            .find(|uid| me.map_or(true, |me| *uid != me))
            .map(|uid| uid.to_owned())?;
        let member = room
            .get_member_no_sync(peer.as_ref())
            .await
            .ok()
            .flatten()?;
        member.avatar_url().map(|mxc| mxc.to_string())
    }

    pub async fn ban_user(
        &self,
        room_id: String,
        user_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let uid = OwnedUserId::try_from(user_id.as_str())
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        room.ban_user(uid.as_ref(), reason.as_deref()).await.ffi()
    }

    pub async fn unban_user(
        &self,
        room_id: String,
        user_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let uid = OwnedUserId::try_from(user_id.as_str())
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        room.unban_user(uid.as_ref(), reason.as_deref()).await.ffi()
    }

    pub async fn kick_user(
        &self,
        room_id: String,
        user_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let uid = OwnedUserId::try_from(user_id.as_str())
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        room.kick_user(uid.as_ref(), reason.as_deref()).await.ffi()
    }

    pub async fn invite_user(&self, room_id: String, user_id: String) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let uid = OwnedUserId::try_from(user_id.as_str())
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        room.invite_user_by_id(uid.as_ref()).await.ffi()
    }

    pub async fn accept_invite(&self, room_id: String) -> Result<(), FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        self.sdk.join_room_by_id(&rid).await.ffi().map(|_| ())
    }

    pub async fn leave_room(&self, room_id: String) -> Result<(), FfiError> {
        self.require_room(&room_id)?.leave().await.ffi()
    }

    pub async fn accept_knock_request(
        &self,
        room_id: String,
        user_id: String,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let uid = Self::parse_uid(&user_id)?;
        room.invite_user_by_id(&uid).await.ffi()
    }

    pub async fn decline_knock_request(
        &self,
        room_id: String,
        user_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let uid = Self::parse_uid(&user_id)?;
        room.kick_user(&uid, reason.as_deref()).await.ffi()
    }

    pub async fn enable_room_encryption(&self, room_id: String) -> Result<(), FfiError> {
        let room = self
            .room(&room_id)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        room.enable_encryption().await.ffi()
    }

    pub async fn get_user_power_level(&self, room_id: String, user_id: String) -> i64 {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return -1;
        };
        let Some(room) = self.sdk.get_room(&rid) else {
            return -1;
        };
        let Ok(uid) = UserId::parse(&user_id) else {
            return -1;
        };
        match room.get_user_power_level(&uid).await {
            Ok(UserPowerLevel::Infinite) => i64::MAX,
            Ok(UserPowerLevel::Int(v)) => v.into(),
            _ => -1,
        }
    }

    pub async fn room_power_levels(&self, room_id: String) -> Result<RoomPowerLevels, FfiError> {
        let room = self.require_room(&room_id)?;
        let levels = room.power_levels().await.ffi()?;

        let users: HashMap<String, i64> = levels
            .users
            .iter()
            .map(|(uid, l)| (uid.to_string(), (*l).into()))
            .collect();
        let events: HashMap<String, i64> = levels
            .events
            .iter()
            .map(|(t, l)| (t.to_string(), (*l).into()))
            .collect();
        let state_default: i64 = levels.state_default.into();

        fn el(
            levels: &matrix_sdk::ruma::events::room::power_levels::RoomPowerLevels,
            t: &str,
            default: i64,
        ) -> i64 {
            use matrix_sdk::ruma::events::TimelineEventType;
            levels
                .events
                .get(&TimelineEventType::from(t))
                .map(|&l| l.into())
                .unwrap_or(default)
        }

        Ok(RoomPowerLevels {
            users,
            users_default: levels.users_default.into(),
            events,
            events_default: levels.events_default.into(),
            state_default,
            ban: levels.ban.into(),
            kick: levels.kick.into(),
            redact: levels.redact.into(),
            invite: levels.invite.into(),
            room_name: el(&levels, "m.room.name", state_default),
            room_avatar: el(&levels, "m.room.avatar", state_default),
            room_topic: el(&levels, "m.room.topic", state_default),
            room_canonical_alias: el(&levels, "m.room.canonical_alias", state_default),
            room_history_visibility: el(&levels, "m.room.history_visibility", state_default),
            room_join_rules: el(&levels, "m.room.join_rules", state_default),
            room_power_levels: el(&levels, "m.room.power_levels", state_default),
            space_child: el(&levels, "m.space.child", state_default),
        })
    }

    pub async fn update_power_level_for_user(
        &self,
        room_id: String,
        user_id: String,
        power_level: i64,
    ) -> Result<(), FfiError> {
        use matrix_sdk::ruma::Int;
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return Err(FfiError::Msg("bad room id".into()));
        };
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        let uid = UserId::parse(&user_id).map_err(|e| FfiError::Msg(e.to_string()))?;
        let level =
            Int::new(power_level).ok_or_else(|| FfiError::Msg("invalid power level".into()))?;
        room.update_power_levels(vec![(&uid, level)])
            .await
            .map_err(|e| FfiError::Msg(e.to_string()))?;
        Ok(())
    }

    pub async fn apply_power_level_changes(
        &self,
        room_id: String,
        changes: RoomPowerLevelChanges,
    ) -> Result<(), FfiError> {
        use matrix_sdk::room::power_levels::RoomPowerLevelChanges as SdkChanges;
        let room = self.require_room(&room_id)?;
        let sdk = SdkChanges {
            users_default: changes.users_default,
            events_default: changes.events_default,
            state_default: changes.state_default,
            ban: changes.ban,
            kick: changes.kick,
            redact: changes.redact,
            invite: changes.invite,
            room_name: changes.room_name,
            room_avatar: changes.room_avatar,
            room_topic: changes.room_topic,
            space_child: changes.space_child,
        };
        room.apply_power_level_changes(sdk).await.ffi()
    }

    pub async fn can_user_ban(&self, room_id: String, user_id: String) -> Result<bool, FfiError> {
        let room = self.require_room(&room_id)?;
        let uid = Self::parse_uid(&user_id)?;
        let levels = room.power_levels().await.ffi()?;
        Ok(levels.user_can_ban(&uid))
    }

    pub async fn can_user_invite(
        &self,
        room_id: String,
        user_id: String,
    ) -> Result<bool, FfiError> {
        let room = self.require_room(&room_id)?;
        let uid = Self::parse_uid(&user_id)?;
        let levels = room.power_levels().await.ffi()?;
        Ok(levels.user_can_invite(&uid))
    }

    pub async fn can_user_redact_other(
        &self,
        room_id: String,
        user_id: String,
    ) -> Result<bool, FfiError> {
        let room = self.require_room(&room_id)?;
        let uid = Self::parse_uid(&user_id)?;
        let levels = room.power_levels().await.ffi()?;
        Ok(levels.user_can_redact_event_of_other(&uid))
    }

    pub async fn recover_with_key(&self, recovery_key: String) -> bool {
        self.sdk
            .encryption()
            .recovery()
            .recover(&recovery_key)
            .await
            .is_ok()
    }

    pub async fn backup_exists_on_server(&self, fetch: bool) -> bool {
        let backups = self.sdk.encryption().backups();
        if fetch {
            backups.fetch_exists_on_server().await.unwrap_or(false)
        } else {
            backups.exists_on_server().await.unwrap_or(false)
        }
    }

    pub async fn set_key_backup_enabled(&self, enabled: bool) -> bool {
        let backups = self.sdk.encryption().backups();
        if enabled {
            backups.create().await.is_ok()
        } else {
            backups.disable().await.is_ok()
        }
    }

    pub async fn room_send_queue_set_enabled(&self, room_id: String, enabled: bool) -> bool {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return false;
        };
        let Some(room) = self.sdk.get_room(&rid) else {
            return false;
        };
        room.send_queue().set_enabled(enabled);
        true
    }

    pub async fn reactions_for_event(
        &self,
        room_id: String,
        event_id: String,
    ) -> Vec<ReactionSummary> {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return vec![];
        };
        let Ok(eid) = OwnedEventId::try_from(event_id) else {
            return vec![];
        };
        let Some(tl) = self.timeline_mgr.timeline_for(&rid).await else {
            return vec![];
        };
        let Some(item) = tl.item_by_event_id(&eid).await else {
            return vec![];
        };
        let me = self.sdk.user_id();
        let mut out = Vec::new();
        if let Some(reactions) = item.content().reactions() {
            for (key, by_sender) in reactions.iter() {
                let count = by_sender.len() as u32;
                let mine = me.map(|u| by_sender.contains_key(u)).unwrap_or(false);
                out.push(ReactionSummary {
                    key: key.to_string(),
                    count,
                    mine,
                });
            }
        }
        out
    }

    pub async fn reactions_batch(
        &self,
        room_id: String,
        event_ids: Vec<String>,
    ) -> HashMap<String, Vec<ReactionSummary>> {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return HashMap::new();
        };
        let me = self.sdk.user_id();
        let mut result = HashMap::new();
        for eid_str in event_ids {
            let Ok(eid) = OwnedEventId::try_from(eid_str.clone()) else {
                continue;
            };
            let Some(tl) = self.timeline_mgr.timeline_for(&rid).await else {
                continue;
            };
            let _ = tl.fetch_details_for_event(eid.as_ref()).await;
            let Some(item) = tl.item_by_event_id(&eid).await else {
                continue;
            };
            let mut summaries = Vec::new();
            if let Some(reactions) = item.content().reactions() {
                for (key, senders) in reactions.iter() {
                    let count = senders.len() as u32;
                    let mine = me.map(|u| senders.keys().any(|s| s == u)).unwrap_or(false);
                    summaries.push(ReactionSummary {
                        key: key.clone(),
                        count,
                        mine,
                    });
                }
            }
            if !summaries.is_empty() {
                result.insert(eid_str, summaries);
            }
        }
        result
    }

    pub async fn ignore_user(&self, user_id: String) -> Result<(), FfiError> {
        let uid = Self::parse_uid(&user_id)?;
        self.sdk.account().ignore_user(&uid).await.ffi()
    }

    pub async fn unignore_user(&self, user_id: String) -> Result<(), FfiError> {
        let uid = Self::parse_uid(&user_id)?;
        self.sdk.account().unignore_user(&uid).await.ffi()
    }

    pub async fn ignored_users(&self) -> Result<Vec<String>, FfiError> {
        let raw_opt = self
            .sdk
            .account()
            .account_data::<IgnoredUserListEventContent>()
            .await
            .ffi()?;
        let Some(raw) = raw_opt else {
            return Ok(Vec::new());
        };
        let content = raw.deserialize().ffi()?;
        Ok(content
            .ignored_users
            .keys()
            .map(|u| u.to_string())
            .collect())
    }

    pub async fn is_user_ignored(&self, user_id: String) -> Result<bool, FfiError> {
        let uid = user_id
            .parse::<OwnedUserId>()
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        Ok(self.sdk.is_user_ignored(uid.as_ref()).await)
    }

    pub async fn report_content(
        &self,
        room_id: String,
        event_id: String,
        score: Option<i32>,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        use matrix_sdk::room::ReportedContentScore;
        let room = self.require_room(&room_id)?;
        let eid = Self::parse_eid(&event_id)?;
        let s = score.and_then(|s| ReportedContentScore::try_from(s).ok());
        room.report_content(eid, s, reason).await.map(|_| ()).ffi()
    }

    pub async fn report_room(
        &self,
        room_id: String,
        reason: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        room.report_room(reason.unwrap_or_default())
            .await
            .map(|_| ())
            .ffi()
    }

    pub async fn search_users(
        &self,
        search_term: String,
        limit: u64,
    ) -> Result<Vec<DirectoryUser>, FfiError> {
        let resp = self.sdk.search_users(&search_term, limit).await.ffi()?;
        Ok(resp
            .results
            .into_iter()
            .map(|u| DirectoryUser {
                user_id: u.user_id.to_string(),
                display_name: u.display_name,
                avatar_url: u.avatar_url.map(|mxc| mxc.to_string()),
            })
            .collect())
    }

    pub async fn get_user_profile(&self, user_id: String) -> Result<DirectoryUser, FfiError> {
        use matrix_sdk::ruma::api::client::profile::{AvatarUrl, DisplayName};
        let uid = Self::parse_uid(&user_id)?;
        let profile = self.sdk.account().fetch_user_profile_of(&uid).await.ffi()?;
        let display_name = profile.get_static::<DisplayName>().ffi()?;
        let avatar_url = profile
            .get_static::<AvatarUrl>()
            .ffi()?
            .map(|mxc| mxc.to_string());
        Ok(DirectoryUser {
            user_id,
            display_name,
            avatar_url,
        })
    }

    pub async fn public_rooms(
        &self,
        server: Option<String>,
        search: Option<String>,
        limit: u32,
        since: Option<String>,
    ) -> Result<PublicRoomsPage, FfiError> {
        let server_name: Option<OwnedServerName> = server
            .map(|s| OwnedServerName::try_from(s).ffi())
            .transpose()?;

        let map_chunk = |r: PublicRoomsChunk| PublicRoom {
            room_id: r.room_id.to_string(),
            name: r.name,
            topic: r.topic,
            alias: r.canonical_alias.map(|a| a.to_string()),
            avatar_url: r.avatar_url.map(|mxc| mxc.to_string()),
            member_count: r.num_joined_members.into(),
            world_readable: r.world_readable,
            guest_can_join: r.guest_can_join,
        };

        if let Some(term) = search.filter(|s| !s.trim().is_empty()) {
            let mut req = get_public_rooms_filtered::v3::Request::new();
            let mut f = Filter::new();
            f.generic_search_term = Some(term);
            req.filter = f;
            if let Some(s) = since.as_deref() {
                req.since = Some(s.to_owned());
            }
            if limit > 0 {
                req.limit = Some(limit.into());
            }
            if let Some(ref sn) = server_name {
                req.server = Some(sn.clone());
            }
            let resp = self.sdk.public_rooms_filtered(req).await.ffi()?;
            Ok(PublicRoomsPage {
                rooms: resp.chunk.into_iter().map(map_chunk).collect(),
                next_batch: resp.next_batch,
                prev_batch: resp.prev_batch,
            })
        } else {
            let resp = self
                .sdk
                .public_rooms(Some(limit), since.as_deref(), server_name.as_deref())
                .await
                .ffi()?;
            Ok(PublicRoomsPage {
                rooms: resp.chunk.into_iter().map(map_chunk).collect(),
                next_batch: resp.next_batch,
                prev_batch: resp.prev_batch,
            })
        }
    }

    pub async fn join_by_id_or_alias(&self, id_or_alias: String) -> Result<(), FfiError> {
        let target = OwnedRoomOrAliasId::try_from(id_or_alias).ffi()?;
        self.sdk
            .join_room_by_id_or_alias(&target, &[])
            .await
            .ffi()?;
        Ok(())
    }

    pub async fn room_preview(&self, id_or_alias: String) -> Result<RoomPreview, FfiError> {
        let target = OwnedRoomOrAliasId::try_from(id_or_alias).ffi()?;
        let preview = self.sdk.get_room_preview(&target, vec![]).await.ffi()?;
        let join_rule = preview.join_rule.map(|rule| match rule {
            JoinRuleSummary::Public => RoomJoinRule::Public,
            JoinRuleSummary::Invite => RoomJoinRule::Invite,
            JoinRuleSummary::Knock => RoomJoinRule::Knock,
            JoinRuleSummary::Restricted(_) => RoomJoinRule::Restricted,
            JoinRuleSummary::KnockRestricted(_) => RoomJoinRule::KnockRestricted,
            JoinRuleSummary::Private => RoomJoinRule::Invite,
            JoinRuleSummary::_Custom(_) | _ => RoomJoinRule::Invite,
        });
        let membership = preview.state.map(|state| match state {
            RoomState::Joined => RoomPreviewMembership::Joined,
            RoomState::Invited => RoomPreviewMembership::Invited,
            RoomState::Knocked => RoomPreviewMembership::Knocked,
            RoomState::Left => RoomPreviewMembership::Left,
            RoomState::Banned => RoomPreviewMembership::Banned,
        });
        Ok(RoomPreview {
            room_id: preview.room_id.to_string(),
            canonical_alias: preview.canonical_alias.map(|a| a.to_string()),
            name: preview.name,
            topic: preview.topic,
            avatar_url: preview.avatar_url.map(|m| m.to_string()),
            member_count: preview.num_joined_members,
            world_readable: preview.is_world_readable,
            join_rule,
            membership,
        })
    }

    pub async fn knock(&self, id_or_alias: String) -> Result<(), FfiError> {
        let target = OwnedRoomOrAliasId::try_from(id_or_alias.as_str())
            .map_err(|_| FfiError::Msg("invalid room id or alias".into()))?;
        self.sdk.knock(target, None, vec![]).await.ffi().map(|_| ())
    }

    pub async fn resolve_room_id(&self, id_or_alias: String) -> Result<Option<String>, FfiError> {
        if id_or_alias.starts_with('!') {
            return Ok(Some(id_or_alias));
        }
        if id_or_alias.starts_with('#') {
            let alias = OwnedRoomAliasId::try_from(id_or_alias.as_str())
                .map_err(|_| FfiError::Msg("invalid room alias".into()))?;
            let resp = self.sdk.resolve_room_alias(&alias).await.ffi()?;
            return Ok(Some(resp.room_id.to_string()));
        }
        Ok(None)
    }

    pub async fn ensure_dm(&self, user_id: String) -> Result<String, FfiError> {
        let uid = Self::parse_uid(&user_id)?;
        if let Some(room) = self.sdk.get_dm_room(&uid) {
            return Ok(room.room_id().to_string());
        }
        let room = self.sdk.create_dm(&uid).await.ffi()?;
        Ok(room.room_id().to_string())
    }

    pub async fn create_room(
        &self,
        name: Option<String>,
        topic: Option<String>,
        invitees: Vec<String>,
        is_public: bool,
        room_alias: Option<String>,
    ) -> Result<String, FfiError> {
        use matrix_sdk::ruma::api::client::room::{Visibility, create_room::v3 as cr};
        let mut req = cr::Request::new();
        req.visibility = if is_public {
            Visibility::Public
        } else {
            Visibility::Private
        };
        req.preset = Some(if is_public {
            cr::RoomPreset::PublicChat
        } else {
            cr::RoomPreset::PrivateChat
        });
        if let Some(n) = &name {
            req.name = Some(n.clone());
        }
        if let Some(t) = &topic {
            req.topic = Some(t.clone());
        }
        if let Some(alias) = &room_alias {
            let normalized = if alias.starts_with('#') {
                alias.trim_start_matches('#')
            } else {
                alias
            }
            .split(':')
            .next()
            .unwrap_or(alias)
            .to_string();
            if !normalized.is_empty() {
                req.room_alias_name = Some(normalized);
            }
        }
        if !invitees.is_empty() {
            req.invite = invitees
                .into_iter()
                .map(|u| u.parse())
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e: matrix_sdk::ruma::IdParseError| FfiError::Msg(e.to_string()))?;
        }
        let resp = self.sdk.send(req).await.ffi()?;
        Ok(resp.room_id.to_string())
    }

    pub async fn create_space(
        &self,
        name: String,
        topic: Option<String>,
        is_public: bool,
        invitees: Vec<String>,
    ) -> Result<String, FfiError> {
        use matrix_sdk::ruma::{
            api::client::room::{Visibility, create_room::v3 as cr},
            serde::Raw,
        };
        let mut req = cr::Request::new();
        let mut cc = cr::CreationContent::new();
        cc.room_type = Some(RoomType::Space);
        req.creation_content = Some(Raw::new(&cc).ffi()?);
        req.name = Some(name);
        req.topic = topic;
        req.visibility = if is_public {
            Visibility::Public
        } else {
            Visibility::Private
        };
        req.preset = Some(if is_public {
            cr::RoomPreset::PublicChat
        } else {
            cr::RoomPreset::PrivateChat
        });
        if !invitees.is_empty() {
            req.invite = invitees
                .into_iter()
                .map(|u| u.parse())
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e: matrix_sdk::ruma::IdParseError| FfiError::Msg(e.to_string()))?;
        }
        let resp = self.sdk.send(req).await.ffi()?;
        Ok(resp.room_id.to_string())
    }

    pub async fn room_aliases(&self, room_id: String) -> Vec<String> {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return vec![];
        };
        let Some(room) = self.sdk.get_room(&rid) else {
            return vec![];
        };
        let mut aliases = Vec::new();
        if let Some(c) = room.canonical_alias() {
            aliases.push(c.to_string());
        }
        for alt in room.alt_aliases() {
            let s = alt.to_string();
            if !aliases.contains(&s) {
                aliases.push(s);
            }
        }
        aliases
    }

    pub async fn publish_room_alias(
        &self,
        room_id: String,
        alias: String,
    ) -> Result<bool, FfiError> {
        let room = self.require_room(&room_id)?;
        let alias_id = OwnedRoomAliasId::try_from(alias).ffi()?;
        room.privacy_settings()
            .publish_room_alias_in_room_directory(alias_id.as_ref())
            .await
            .ffi()
    }

    pub async fn unpublish_room_alias(
        &self,
        room_id: String,
        alias: String,
    ) -> Result<bool, FfiError> {
        let room = self.require_room(&room_id)?;
        let alias_id = OwnedRoomAliasId::try_from(alias).ffi()?;
        room.privacy_settings()
            .remove_room_alias_from_room_directory(alias_id.as_ref())
            .await
            .ffi()
    }

    pub async fn set_room_canonical_alias(
        &self,
        room_id: String,
        alias: Option<String>,
        alt_aliases: Vec<String>,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let alias_opt = alias
            .map(|a| OwnedRoomAliasId::try_from(a).ffi())
            .transpose()?;
        let alts: Vec<OwnedRoomAliasId> = alt_aliases
            .into_iter()
            .map(|s| OwnedRoomAliasId::try_from(s).ffi())
            .collect::<Result<_, _>>()?;
        room.privacy_settings()
            .update_canonical_alias(alias_opt, alts)
            .await
            .ffi()
    }

    pub async fn set_room_directory_visibility(
        &self,
        room_id: String,
        visibility: RoomDirectoryVisibility,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let vs = match visibility {
            RoomDirectoryVisibility::Public => Visibility::Public,
            RoomDirectoryVisibility::Private => Visibility::Private,
        };
        room.privacy_settings()
            .update_room_visibility(vs)
            .await
            .ffi()
    }

    pub async fn room_directory_visibility(
        &self,
        room_id: String,
    ) -> Result<RoomDirectoryVisibility, FfiError> {
        let room = self.require_room(&room_id)?;
        let vis = room.privacy_settings().get_room_visibility().await.ffi()?;
        Ok(match vis {
            Visibility::Public => RoomDirectoryVisibility::Public,
            _ => RoomDirectoryVisibility::Private,
        })
    }

    pub async fn room_join_rule(&self, room_id: String) -> Result<RoomJoinRule, FfiError> {
        use matrix_sdk::ruma::events::room::join_rules::JoinRule;
        let room = self.require_room(&room_id)?;
        Ok(match room.join_rule() {
            Some(JoinRule::Public) => RoomJoinRule::Public,
            Some(JoinRule::Invite) => RoomJoinRule::Invite,
            Some(JoinRule::Knock) => RoomJoinRule::Knock,
            Some(JoinRule::Restricted(_)) => RoomJoinRule::Restricted,
            Some(JoinRule::KnockRestricted(_)) => RoomJoinRule::KnockRestricted,
            _ => RoomJoinRule::Invite,
        })
    }

    pub async fn set_room_join_rule(
        &self,
        room_id: String,
        rule: RoomJoinRule,
    ) -> Result<(), FfiError> {
        use matrix_sdk::ruma::events::room::join_rules::{JoinRule, Restricted};
        let room = self.require_room(&room_id)?;
        let jr = match rule {
            RoomJoinRule::Public => JoinRule::Public,
            RoomJoinRule::Invite => JoinRule::Invite,
            RoomJoinRule::Knock => JoinRule::Knock,
            RoomJoinRule::Restricted => JoinRule::Restricted(Restricted::new(vec![])),
            RoomJoinRule::KnockRestricted => JoinRule::KnockRestricted(Restricted::new(vec![])),
        };
        room.privacy_settings().update_join_rule(jr).await.ffi()
    }

    pub async fn room_history_visibility(
        &self,
        room_id: String,
    ) -> Result<RoomHistoryVisibility, FfiError> {
        use matrix_sdk::ruma::events::room::history_visibility::HistoryVisibility;
        let room = self.require_room(&room_id)?;
        Ok(match room.history_visibility_or_default() {
            HistoryVisibility::Invited => RoomHistoryVisibility::Invited,
            HistoryVisibility::Joined => RoomHistoryVisibility::Joined,
            HistoryVisibility::Shared => RoomHistoryVisibility::Shared,
            HistoryVisibility::WorldReadable => RoomHistoryVisibility::WorldReadable,
            _ => RoomHistoryVisibility::Joined,
        })
    }

    pub async fn set_room_history_visibility(
        &self,
        room_id: String,
        visibility: RoomHistoryVisibility,
    ) -> Result<(), FfiError> {
        use matrix_sdk::ruma::events::room::history_visibility::HistoryVisibility;
        let room = self.require_room(&room_id)?;
        let vis = match visibility {
            RoomHistoryVisibility::Invited => HistoryVisibility::Invited,
            RoomHistoryVisibility::Joined => HistoryVisibility::Joined,
            RoomHistoryVisibility::Shared => HistoryVisibility::Shared,
            RoomHistoryVisibility::WorldReadable => HistoryVisibility::WorldReadable,
        };
        room.privacy_settings()
            .update_room_history_visibility(vis)
            .await
            .ffi()
    }

    pub async fn upgrade_room(
        &self,
        room_id: String,
        new_version: String,
    ) -> Result<String, FfiError> {
        let rid = Self::parse_rid(&room_id)?;
        let version = RoomVersionId::try_from(new_version.as_str()).ffi()?;
        let req = upgrade_room_v3::Request::new(rid, version);
        let resp = self.sdk.send(req).await.ffi()?;
        Ok(resp.replacement_room.to_string())
    }

    pub async fn room_upgrade_links(&self, room_id: String) -> Option<RoomUpgradeLinks> {
        let rid = OwnedRoomId::try_from(room_id).ok()?;
        let room = self.sdk.get_room(&rid)?;
        Some(RoomUpgradeLinks {
            is_tombstoned: room.is_tombstoned(),
            successor: room.successor_room().map(Into::into),
            predecessor: room.predecessor_room().map(Into::into),
        })
    }

    pub async fn room_successor(
        &self,
        room_id: String,
    ) -> Result<Option<SuccessorRoomInfo>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(room.successor_room().map(Into::into))
    }

    pub async fn room_predecessor(
        &self,
        room_id: String,
    ) -> Result<Option<PredecessorRoomInfo>, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(room.predecessor_room().map(Into::into))
    }

    pub async fn send_poll_start(
        &self,
        room_id: String,
        def: PollDefinition,
    ) -> Result<String, FfiError> {
        let room = self.require_room(&room_id)?;
        let content = build_unstable_poll_content(&def)?;
        let any = AnyMessageLikeEventContent::UnstablePollStart(content.into());
        let res = room.send(any).await.ffi()?;
        Ok(res.response.event_id.to_string())
    }

    pub async fn send_poll_response(
        &self,
        room_id: String,
        poll_event_id: String,
        answers: Vec<String>,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let eid = Self::parse_eid(&poll_event_id)?;
        let content = UnstablePollResponseEventContent::new(answers, eid.to_owned());
        room.send(AnyMessageLikeEventContent::UnstablePollResponse(content))
            .await
            .map(|_| ())
            .ffi()
    }

    pub async fn send_poll_end(
        &self,
        room_id: String,
        poll_event_id: String,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let eid = Self::parse_eid(&poll_event_id)?;
        let end = UnstablePollEndEventContent::new("Poll ended", eid);
        room.send(AnyMessageLikeEventContent::UnstablePollEnd(end))
            .await
            .map(|_| ())
            .ffi()
    }

    pub async fn set_presence(
        &self,
        state: Presence,
        status_msg: Option<String>,
    ) -> Result<(), FfiError> {
        let me = self.sdk.user_id().or_ffi("No logged-in user")?;
        let presence = match state {
            Presence::Online => PresenceState::Online,
            Presence::Offline => PresenceState::Offline,
            Presence::Unavailable => PresenceState::Unavailable,
        };
        let mut req = set_presence_v3::Request::new(me.to_owned(), presence);
        req.status_msg = status_msg;
        self.sdk
            .send(req)
            .await
            .map(|_: set_presence_v3::Response| ())
            .ffi()
    }

    pub async fn get_presence(&self, user_id: String) -> Result<PresenceInfo, FfiError> {
        let uid = Self::parse_uid(&user_id)?;
        let req = get_presence_v3::Request::new(uid);
        let resp = self.sdk.send(req).await.ffi()?;
        let presence = match resp.presence {
            PresenceState::Online => Presence::Online,
            PresenceState::Offline => Presence::Offline,
            PresenceState::Unavailable => Presence::Unavailable,
            _ => Presence::Offline,
        };
        Ok(PresenceInfo {
            presence,
            status_msg: resp.status_msg,
        })
    }

    pub async fn is_space(&self, room_id: String) -> Result<bool, FfiError> {
        let rid = OwnedRoomId::try_from(room_id.as_str())
            .map_err(|_| FfiError::Msg("invalid room id".into()))?;
        let room = self
            .sdk
            .get_room(&rid)
            .ok_or_else(|| FfiError::Msg("room not found".into()))?;
        Ok(room.is_space())
    }

    pub async fn my_spaces(&self) -> Vec<SpaceInfo> {
        let mut out = Vec::new();
        for room in self.sdk.joined_space_rooms() {
            let rid = room.room_id().to_owned();
            let name = room
                .display_name()
                .await
                .map(|d| d.to_string())
                .unwrap_or_else(|_| rid.to_string());
            out.push(SpaceInfo {
                room_id: rid.to_string(),
                name,
                topic: room.topic(),
                member_count: room.joined_members_count(),
                is_encrypted: matches!(room.encryption_state(), EncryptionState::Encrypted),
                is_public: room.is_public().unwrap_or(false),
                avatar_url: room.avatar_url().map(|mxc| mxc.to_string()),
            });
        }
        out
    }

    pub async fn space_add_child(
        &self,
        space_id: String,
        child_room_id: String,
        order: Option<String>,
        suggested: Option<bool>,
    ) -> Result<(), FfiError> {
        let rid_space = Self::parse_rid(&space_id)?;
        let rid_child = Self::parse_rid(&child_room_id)?;
        let room = self.sdk.get_room(&rid_space).or_ffi("space not found")?;
        let via: Vec<_> = rid_child
            .server_name()
            .map(|s| s.to_owned())
            .into_iter()
            .collect();
        let mut content = SpaceChildEventContent::new(via);
        if let Some(o) = order {
            let ord = <&SpaceChildOrder>::try_from(o.as_str())
                .map_err(|e| FfiError::Msg(format!("Invalid order string: {}", e)))?
                .to_owned();
            content.order = Some(ord);
        }
        content.suggested = suggested.unwrap_or(false);
        room.send_state_event_for_key(&rid_child, content)
            .await
            .map(|_| ())
            .ffi()
    }

    pub async fn space_remove_child(
        &self,
        space_id: String,
        child_room_id: String,
    ) -> Result<(), FfiError> {
        let rid_space = Self::parse_rid(&space_id)?;
        let room = self.sdk.get_room(&rid_space).or_ffi("space not found")?;
        room.send_state_event_raw(
            "m.space.child",
            child_room_id.as_str(),
            serde_json::json!({}),
        )
        .await
        .map(|_| ())
        .ffi()
    }

    pub async fn space_hierarchy(
        &self,
        space_id: String,
        from: Option<String>,
        limit: u32,
        max_depth: Option<u32>,
        suggested_only: bool,
    ) -> Result<SpaceHierarchyPage, FfiError> {
        use matrix_sdk::ruma::api::client::space::get_hierarchy::v1 as sh;
        let rid = Self::parse_rid(&space_id)?;
        let mut req = sh::Request::new(rid);
        req.from = from;
        if limit > 0 {
            req.limit = Some(limit.into());
        }
        req.max_depth = max_depth.map(Into::into);
        req.suggested_only = suggested_only;
        let resp = self.sdk.send(req).await.ffi()?;
        let children = resp
            .rooms
            .into_iter()
            .map(|chunk| {
                let s = chunk.summary;
                let is_space = matches!(s.room_type, Some(RoomType::Space));
                SpaceChildInfo {
                    room_id: s.room_id.to_string(),
                    name: s.name,
                    topic: s.topic,
                    alias: s.canonical_alias.map(|a| a.to_string()),
                    avatar_url: s.avatar_url.map(|m| m.to_string()),
                    is_space,
                    member_count: s.num_joined_members.into(),
                    world_readable: s.world_readable,
                    guest_can_join: s.guest_can_join,
                    suggested: false,
                }
            })
            .collect();
        Ok(SpaceHierarchyPage {
            children,
            next_batch: resp.next_batch,
        })
    }

    pub async fn space_invite_user(
        &self,
        space_id: String,
        user_id: String,
    ) -> Result<(), FfiError> {
        let room = self
            .room(&space_id)
            .ok_or_else(|| FfiError::Msg("space not found".into()))?;
        let uid = OwnedUserId::try_from(user_id.as_str())
            .map_err(|_| FfiError::Msg("invalid user id".into()))?;
        room.invite_user_by_id(&uid).await.ffi()
    }

    pub async fn start_live_location(
        &self,
        room_id: String,
        duration_ms: u64,
        description: Option<String>,
    ) -> Result<(), FfiError> {
        let room = self.require_room(&room_id)?;
        let response = room
            .start_live_location_share(duration_ms, description.clone())
            .await
            .ffi()?;
        self.live_location_beacons.lock().unwrap().insert(
            room_id,
            LiveLocationBeaconState {
                event_id: response.event_id.to_string(),
                duration_ms,
                description,
            },
        );
        Ok(())
    }

    pub async fn stop_live_location(&self, room_id: String) -> Result<(), FfiError> {
        use matrix_sdk::ruma::events::beacon_info::BeaconInfoEventContent;
        let room = self.require_room(&room_id)?;
        let cached = self
            .live_location_beacons
            .lock()
            .unwrap()
            .get(&room_id)
            .cloned();
        let result = if let Some(cached) = cached {
            room.send_state_event_for_key(
                room.own_user_id(),
                BeaconInfoEventContent::new(
                    cached.description,
                    Duration::from_millis(cached.duration_ms),
                    false,
                    None,
                ),
            )
            .await
            .map(|_| ())
            .ffi()
        } else {
            room.stop_live_location_share().await.map(|_| ()).ffi()
        };
        if result.is_ok() {
            self.live_location_beacons.lock().unwrap().remove(&room_id);
        }
        result
    }

    pub async fn send_live_location(
        &self,
        room_id: String,
        geo_uri: String,
    ) -> Result<(), FfiError> {
        use matrix_sdk::ruma::events::beacon::BeaconEventContent;
        let room = self.require_room(&room_id)?;
        let beacon_state = self
            .live_location_beacons
            .lock()
            .unwrap()
            .get(room.room_id().as_str())
            .cloned();
        if let Some(beacon_state) = beacon_state {
            let beacon_event_id = Self::parse_eid(&beacon_state.event_id)?;
            let content = BeaconEventContent::new(beacon_event_id, geo_uri, None);
            room.send(content).await.map(|_| ()).ffi()
        } else {
            room.send_location_beacon(geo_uri).await.map(|_| ()).ffi()
        }
    }

    pub async fn seen_by_for_event(
        &self,
        room_id: String,
        event_id: String,
        limit: u32,
    ) -> Result<Vec<SeenByEntry>, FfiError> {
        use matrix_sdk::ruma::api::client::profile::{AvatarUrl, DisplayName};
        let rid = Self::parse_rid(&room_id)?;
        let eid = Self::parse_eid(&event_id)?;
        let room = self.sdk.get_room(&rid).or_ffi("room not found")?;
        let tl = self
            .timeline_mgr
            .timeline_for(&rid)
            .await
            .or_ffi("timeline not available")?;
        let _ = tl.fetch_members().await;

        let me = self.sdk.user_id().map(|u| u.to_string());
        let mut member_map: HashMap<String, (Option<String>, Option<String>)> = HashMap::new();
        if let Ok(members) = room.members(RoomMemberships::ACTIVE).await {
            for m in members {
                member_map.insert(
                    m.user_id().to_string(),
                    (
                        m.display_name().map(|s| s.to_string()),
                        m.avatar_url().map(|u| u.to_string()),
                    ),
                );
            }
        }

        let (items, _) = tl.subscribe().await;
        let mut target_idx = None;
        for (idx, item) in items.iter().enumerate() {
            if let Some(ev) = item.as_event() {
                if ev.event_id() == Some(eid.as_ref()) {
                    target_idx = Some(idx);
                    break;
                }
            }
        }
        let Some(target_idx) = target_idx else {
            return Ok(vec![]);
        };

        let mut best_ts: HashMap<String, u64> = HashMap::new();
        for item in items.iter().skip(target_idx) {
            let Some(ev) = item.as_event() else { continue };
            for (uid, receipt) in ev.read_receipts().iter() {
                let uid_str = uid.to_string();
                if me.as_deref() == Some(uid_str.as_str()) {
                    continue;
                }
                let ts: u64 = receipt
                    .ts
                    .map(|t| t.0.into())
                    .unwrap_or_else(|| ev.timestamp().0.into());
                best_ts
                    .entry(uid_str)
                    .and_modify(|old| *old = (*old).max(ts))
                    .or_insert(ts);
            }
        }

        let mut out: Vec<SeenByEntry> = best_ts
            .into_iter()
            .map(|(user_id, ts)| {
                let (dn, au) = member_map.get(&user_id).cloned().unwrap_or((None, None));
                SeenByEntry {
                    user_id,
                    display_name: dn,
                    avatar_url: au,
                    ts_ms: Some(ts),
                }
            })
            .collect();

        for entry in out.iter_mut() {
            if entry.avatar_url.is_none() {
                if let Ok(uid) = entry.user_id.parse::<OwnedUserId>() {
                    if let Ok(profile) = self.sdk.account().fetch_user_profile_of(&uid).await {
                        entry.avatar_url = profile
                            .get_static::<AvatarUrl>()
                            .ok()
                            .flatten()
                            .map(|mxc| mxc.to_string());
                        if entry.display_name.is_none() {
                            entry.display_name = profile
                                .get_static::<DisplayName>()
                                .ok()
                                .flatten()
                                .map(|s| s.to_string());
                        }
                    }
                }
            }
        }

        out.sort_by_key(|e| std::cmp::Reverse(e.ts_ms.unwrap_or(0)));
        out.truncate(limit.max(1) as usize);
        Ok(out)
    }

    pub async fn account_management_url(&self) -> Result<Option<String>, FfiError> {
        let metadata = self.sdk.oauth().cached_server_metadata().await.ffi()?;
        Ok(metadata.account_management_uri.map(|u| u.to_string()))
    }

    pub async fn thread_replies(
        &self,
        room_id: String,
        root_event_id: String,
        from: Option<String>,
        limit: u32,
        direction_forward: bool,
    ) -> Result<ThreadPage, FfiError> {
        use matrix_sdk::ruma::{
            api::Direction,
            api::client::relations::get_relating_events_with_rel_type_and_event_type as get_relating,
            events::TimelineEventType, events::relation::RelationType,
        };
        let rid = Self::parse_rid(&room_id)?;
        let root = Self::parse_eid(&root_event_id)?;
        let mut req = get_relating::v1::Request::new(
            rid.clone(),
            root.clone(),
            RelationType::Thread,
            TimelineEventType::RoomMessage,
        );
        if let Some(f) = from.as_deref() {
            req.from = Some(f.to_owned());
        }
        if limit > 0 {
            req.limit = Some(limit.into());
        }
        req.dir = if direction_forward {
            Direction::Forward
        } else {
            Direction::Backward
        };
        let resp = self.sdk.send(req).await.ffi()?;
        let mut out = Vec::new();
        if let Some(root_ev) =
            map_event_id_via_timeline(&self.timeline_mgr, &self.sdk, &rid, &root).await
        {
            out.push(root_ev);
        }
        for raw in resp.chunk.iter() {
            if let Ok(ml) = raw.deserialize() {
                let eid = ml.event_id().to_owned();
                if let Some(mev) =
                    map_event_id_via_timeline(&self.timeline_mgr, &self.sdk, &rid, &eid).await
                {
                    out.push(mev);
                }
            }
        }
        out.sort_by_key(|e| e.timestamp_ms);
        Ok(ThreadPage {
            root_event_id,
            room_id,
            messages: out,
            next_batch: resp.next_batch.clone(),
            prev_batch: resp.prev_batch.clone(),
        })
    }

    pub async fn thread_summary(
        &self,
        room_id: String,
        root_event_id: String,
        per_page: u32,
        max_pages: u32,
    ) -> Result<ThreadSummary, FfiError> {
        use matrix_sdk::ruma::{
            api::Direction,
            api::client::relations::get_relating_events_with_rel_type_and_event_type as get_relating,
            events::TimelineEventType, events::relation::RelationType,
        };
        let rid = Self::parse_rid(&room_id)?;
        let root = Self::parse_eid(&root_event_id)?;
        let mut from: Option<String> = None;
        let mut pages = 0u32;
        let mut count: u64 = 0;
        let mut latest: Option<u64> = None;
        loop {
            pages += 1;
            if pages > max_pages.max(1) {
                break;
            }
            let mut req = get_relating::v1::Request::new(
                rid.clone(),
                root.clone(),
                RelationType::Thread,
                TimelineEventType::RoomMessage,
            );
            req.dir = Direction::Backward;
            if let Some(f) = &from {
                req.from = Some(f.clone());
            }
            if per_page > 0 {
                req.limit = Some(per_page.into());
            }
            let resp = self.sdk.send(req).await.ffi()?;
            for raw in resp.chunk.iter() {
                if let Ok(ml) = raw.deserialize() {
                    let eid = ml.event_id().to_owned();
                    count += 1;
                    if let Some(mev) =
                        map_event_id_via_timeline(&self.timeline_mgr, &self.sdk, &rid, &eid).await
                    {
                        if latest.map_or(true, |l| mev.timestamp_ms > l) {
                            latest = Some(mev.timestamp_ms);
                        }
                    }
                }
            }
            if resp.next_batch.is_none() {
                break;
            }
            from = resp.next_batch;
        }
        Ok(ThreadSummary {
            root_event_id,
            room_id,
            count,
            latest_ts_ms: latest,
        })
    }

    pub async fn send_queue_set_enabled(&self, enabled: bool) -> Result<(), FfiError> {
        self.sdk.send_queue().set_enabled(enabled).await;
        Ok(())
    }

    #[cfg(not(target_family = "wasm"))]
    pub async fn search_room(
        &self,
        room_id: String,
        query: String,
        limit: u32,
        offset: Option<u32>,
    ) -> Result<SearchPage, FfiError> {
        let limit_usize = (limit.max(1)).min(200) as usize;
        let rid = Self::parse_rid(&room_id)?;
        let Some(room) = self.sdk.get_room(&rid) else {
            return Ok(SearchPage {
                hits: vec![],
                next_offset: None,
            });
        };
        let event_ids = room
            .search(query.trim(), limit_usize, offset.map(|o| o as usize))
            .await
            .ffi()?;
        let mut hits = Vec::new();
        for eid in event_ids.iter() {
            if let Some(mev) =
                map_event_id_via_timeline(&self.timeline_mgr, &self.sdk, &rid, eid).await
            {
                hits.push(SearchHit {
                    room_id: rid.to_string(),
                    event_id: mev.event_id.clone(),
                    sender: mev.sender.clone(),
                    body: mev.body.clone(),
                    timestamp_ms: mev.timestamp_ms,
                });
            } else {
                hits.push(SearchHit {
                    room_id: rid.to_string(),
                    event_id: eid.to_string(),
                    sender: "".into(),
                    body: "".into(),
                    timestamp_ms: 0,
                });
            }
        }
        let next_offset = if hits.len() == limit_usize {
            Some(offset.unwrap_or(0).saturating_add(hits.len() as u32))
        } else {
            None
        };
        Ok(SearchPage { hits, next_offset })
    }

    pub async fn typing_stream(
        &self,
        room_id: &OwnedRoomId,
    ) -> Option<impl futures_util::Stream<Item = Vec<String>> + '_> {
        let room = self.sdk.get_room(room_id)?;
        let (_guard, rx) = room.subscribe_to_typing_notifications();
        let stream = async_stream::stream! {
            let mut rx = rx;
            let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
            while let Ok(user_ids) = rx.recv().await {
                let mut names = Vec::new();
                for uid in user_ids {
                    if let Some(n) = cache.get(&uid) { names.push(n.clone()); continue; }
                    let name = match room.get_member(&uid).await {
                        Ok(Some(m)) => m.display_name().map(|s| s.to_string()).unwrap_or_else(|| uid.localpart().to_string()),
                        _ => uid.localpart().to_string(),
                    };
                    cache.insert(uid.clone(), name.clone());
                    names.push(name);
                }
                names.sort(); names.dedup();
                yield names;
            }
        };
        Some(stream)
    }

    pub async fn receipts_changed_stream(
        &self,
        room_id: &OwnedRoomId,
    ) -> Option<impl futures_util::Stream<Item = ()>> {
        let room = self.sdk.get_room(room_id)?;
        let tl = room.timeline().await.ok()?;
        Some(tl.subscribe_own_user_read_receipts_changed().await)
    }
}

pub(crate) fn map_send_queue_update(
    room_id: &str,
    update: matrix_sdk::send_queue::RoomSendQueueUpdate,
    attempts: &mut HashMap<String, u32>,
) -> Option<SendUpdate> {
    use matrix_sdk::send_queue::RoomSendQueueUpdate as U;
    match update {
        U::NewLocalEvent(local) => Some(SendUpdate {
            room_id: room_id.to_string(),
            txn_id: local.transaction_id.to_string(),
            attempts: 0,
            state: SendState::Enqueued,
            event_id: None,
            error: None,
        }),
        U::RetryEvent { transaction_id } => {
            let k = format!("{room_id}|{transaction_id}");
            let n = attempts.entry(k).and_modify(|v| *v += 1).or_insert(1);
            Some(SendUpdate {
                room_id: room_id.to_string(),
                txn_id: transaction_id.to_string(),
                attempts: *n,
                state: SendState::Retrying,
                event_id: None,
                error: None,
            })
        }
        U::SentEvent {
            transaction_id,
            event_id,
        } => {
            attempts.remove(&format!("{room_id}|{transaction_id}"));
            Some(SendUpdate {
                room_id: room_id.to_string(),
                txn_id: transaction_id.to_string(),
                attempts: 0,
                state: SendState::Sent,
                event_id: Some(event_id.to_string()),
                error: None,
            })
        }
        U::SendError {
            transaction_id,
            error,
            is_recoverable,
        } => {
            let k = format!("{room_id}|{transaction_id}");
            let n = attempts.entry(k).and_modify(|v| *v += 1).or_insert(1);
            Some(SendUpdate {
                room_id: room_id.to_string(),
                txn_id: transaction_id.to_string(),
                attempts: *n,
                state: SendState::Failed,
                event_id: None,
                error: Some(format!("{error:?} (recoverable={is_recoverable})")),
            })
        }
        U::CancelledLocalEvent { transaction_id } => {
            attempts.remove(&format!("{room_id}|{transaction_id}"));
            Some(SendUpdate {
                room_id: room_id.to_string(),
                txn_id: transaction_id.to_string(),
                attempts: 0,
                state: SendState::Failed,
                event_id: None,
                error: Some("Cancelled".into()),
            })
        }
        _ => None,
    }
}
