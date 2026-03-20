use matrix_sdk::{PredecessorRoom, SuccessorRoom};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::ops::{Deref, DerefMut};
use uniffi::{Enum, Record, export};

use crate::RT;

pub(crate) const MIN_VISIBLE_AFTER_RESET: usize = 20;
pub(crate) const BACKFILL_CHUNK: u16 = 20;
pub(crate) const MAX_BACKFILL_ROUNDS: u8 = 8;
pub(crate) const INITIAL_BACK_PAGINATION: u16 = 20;

#[derive(Debug, thiserror::Error, Serialize, uniffi::Error)]
pub enum FfiError {
    #[error("{0}")]
    Msg(String),
}

impl From<matrix_sdk::Error> for FfiError {
    fn from(e: matrix_sdk::Error) -> Self {
        FfiError::Msg(format!("matrix_sdk error: {e:?}"))
    }
}
impl From<matrix_sdk_ui::notification_client::Error> for FfiError {
    fn from(e: matrix_sdk_ui::notification_client::Error) -> Self {
        FfiError::Msg(format!("NotificationClient error: {e:?}"))
    }
}
impl From<std::io::Error> for FfiError {
    fn from(e: std::io::Error) -> Self {
        FfiError::Msg(format!("io error: {e:?}"))
    }
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct LiveLocationEvent {
    pub user_id: String,
    pub geo_uri: String,
    pub ts_ms: u64,
    pub is_live: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct MessageEvent {
    pub item_id: String,
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    pub sender_display_name: Option<String>,
    pub sender_avatar_url: Option<String>,
    pub body: String,
    pub formatted_body: Option<String>,
    pub timestamp_ms: u64,
    pub send_state: Option<SendState>,
    pub txn_id: Option<String>,
    pub reply_to_event_id: Option<String>,
    pub reply_to_sender: Option<String>,
    pub reply_to_sender_display_name: Option<String>,
    pub reply_to_body: Option<String>,
    pub attachment: Option<AttachmentInfo>,
    pub thread_root_event_id: Option<String>,
    pub is_edited: bool,
    pub poll_data: Option<PollData>,
    pub reactions: Vec<ReactionSummary>,
    pub event_type: EventType,
    pub live_location: Option<LiveLocationEvent>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Record)]
pub struct SeenByEntry {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    pub ts_ms: Option<u64>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct EncFile {
    pub url: String,
    pub json: String,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct AttachmentInfo {
    pub kind: AttachmentKind,
    pub mxc_uri: String,
    pub mime: Option<String>,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u64>,
    pub thumbnail_mxc_uri: Option<String>,
    pub encrypted: Option<EncFile>,
    pub thumbnail_encrypted: Option<EncFile>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct DeviceSummary {
    pub device_id: String,
    pub display_name: String,
    pub ed25519: String,
    pub is_own: bool,
    pub verified: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SyncStatus {
    pub phase: SyncPhase,
    pub message: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct CallInvite {
    pub room_id: String,
    pub sender: String,
    pub call_id: String,
    pub is_video: bool,
    pub ts_ms: u64,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct DownloadResult {
    pub path: String,
    pub bytes: u64,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RenderedNotification {
    pub room_id: String,
    pub event_id: String,
    pub room_name: String,
    pub sender: String,
    pub sender_user_id: String,
    pub body: String,
    pub is_noisy: bool,
    pub has_mention: bool,
    pub ts_ms: u64,
    pub is_dm: bool,
    pub kind: NotificationKind,
    pub expires_at_ms: Option<u64>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct UnreadStats {
    pub messages: u64,
    pub notifications: u64,
    pub mentions: u64,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomProfile {
    pub room_id: String,
    pub name: String,
    pub topic: Option<String>,
    pub member_count: u64,
    pub is_encrypted: bool,
    pub is_dm: bool,
    pub avatar_url: Option<String>,
    pub canonical_alias: Option<String>,
    pub alt_aliases: Vec<String>,
    pub room_version: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Record)]
pub struct MemberSummary {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    pub is_me: bool,
    pub membership: String,
}

#[derive(Clone, Debug, Record, Serialize, Deserialize)]
pub struct KnockRequestSummary {
    pub event_id: String,
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    pub reason: Option<String>,
    pub ts_ms: Option<u64>,
    pub is_seen: bool,
}

#[derive(Serialize, Deserialize, uniffi::Record)]
pub struct RoomTags {
    pub is_favourite: bool,
    pub is_low_priority: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ThreadPage {
    pub root_event_id: String,
    pub room_id: String,
    pub messages: Vec<MessageEvent>,
    pub next_batch: Option<String>,
    pub prev_batch: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ThreadSummary {
    pub root_event_id: String,
    pub room_id: String,
    pub count: u64,
    pub latest_ts_ms: Option<u64>,
}

#[derive(Serialize, Deserialize, Record, Clone)]
pub struct OwnReceipt {
    pub event_id: Option<String>,
    pub ts_ms: Option<u64>,
}

#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct LatestRoomEvent {
    pub event_id: String,
    pub sender: String,
    pub body: Option<String>,
    pub msgtype: Option<String>,
    pub event_type: String,
    pub timestamp: i64,
    pub is_redacted: bool,
    pub is_encrypted: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct RoomListEntry {
    pub room_id: String,
    pub name: String,
    pub last_ts: u64,
    pub notifications: u64,
    pub messages: u64,
    pub mentions: u64,
    pub marked_unread: bool,
    pub is_favourite: bool,
    pub is_low_priority: bool,
    pub is_invited: bool,
    pub avatar_url: Option<String>,
    pub is_dm: bool,
    pub is_encrypted: bool,
    pub member_count: u32,
    pub topic: Option<String>,
    pub latest_event: Option<LatestRoomEvent>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct DirectoryUser {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PublicRoom {
    pub room_id: String,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub alias: Option<String>,
    pub avatar_url: Option<String>,
    pub member_count: u64,
    pub world_readable: bool,
    pub guest_can_join: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PublicRoomsPage {
    pub rooms: Vec<PublicRoom>,
    pub next_batch: Option<String>,
    pub prev_batch: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct InviteSummary {
    pub room_id: String,
    pub name: String,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ReactionSummary {
    pub key: String,
    pub count: u32,
    pub mine: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceInfo {
    pub room_id: String,
    pub name: String,
    pub topic: Option<String>,
    pub member_count: u64,
    pub is_encrypted: bool,
    pub is_public: bool,
    pub avatar_url: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceChildInfo {
    pub room_id: String,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub alias: Option<String>,
    pub avatar_url: Option<String>,
    pub is_space: bool,
    pub member_count: u64,
    pub world_readable: bool,
    pub guest_can_join: bool,
    pub suggested: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceHierarchyPage {
    pub children: Vec<SpaceChildInfo>,
    pub next_batch: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PollOption {
    pub id: String,
    pub text: String,
    pub votes: u32,
    pub is_selected: bool,
    pub is_winner: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PollData {
    pub question: String,
    pub kind: PollKind,
    pub max_selections: u32,
    pub options: Vec<PollOption>,
    pub votes: HashMap<String, u32>,
    pub my_selections: Vec<String>,
    pub total_votes: u32,
    pub is_ended: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PollDefinition {
    pub question: String,
    pub answers: Vec<String>,
    pub kind: PollKind,
    pub max_selections: u32,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SearchHit {
    pub room_id: String,
    pub event_id: String,
    pub sender: String,
    pub body: String,
    pub timestamp_ms: u64,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SearchPage {
    pub hits: Vec<SearchHit>,
    pub next_offset: Option<u32>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct LiveLocationShareInfo {
    pub user_id: String,
    pub geo_uri: String,
    pub ts_ms: u64,
    pub is_live: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomPreview {
    pub room_id: String,
    pub canonical_alias: Option<String>,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub avatar_url: Option<String>,
    pub member_count: u64,
    pub world_readable: Option<bool>,
    pub join_rule: Option<RoomJoinRule>,
    pub membership: Option<RoomPreviewMembership>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomPowerLevels {
    pub users: HashMap<String, i64>,
    pub users_default: i64,
    pub events: HashMap<String, i64>,
    pub events_default: i64,
    pub state_default: i64,
    pub ban: i64,
    pub kick: i64,
    pub redact: i64,
    pub invite: i64,
    pub room_name: i64,
    pub room_avatar: i64,
    pub room_topic: i64,
    pub room_canonical_alias: i64,
    pub room_history_visibility: i64,
    pub room_join_rules: i64,
    pub room_power_levels: i64,
    pub space_child: i64,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomPowerLevelChanges {
    pub users_default: Option<i64>,
    pub events_default: Option<i64>,
    pub state_default: Option<i64>,
    pub ban: Option<i64>,
    pub kick: Option<i64>,
    pub redact: Option<i64>,
    pub invite: Option<i64>,
    pub room_name: Option<i64>,
    pub room_avatar: Option<i64>,
    pub room_topic: Option<i64>,
    pub space_child: Option<i64>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SuccessorRoomInfo {
    pub room_id: String,
    pub reason: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PredecessorRoomInfo {
    pub room_id: String,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RoomUpgradeLinks {
    pub is_tombstoned: bool,
    pub successor: Option<SuccessorRoomInfo>,
    pub predecessor: Option<PredecessorRoomInfo>,
}

#[derive(Clone, Record, Serialize, Deserialize)]
pub struct HomeserverLoginDetails {
    pub supports_oauth: bool,
    pub supports_sso: bool,
    pub supports_password: bool,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct CallSessionInfo {
    pub session_id: u64,
    pub widget_url: String,
    pub widget_base_url: Option<String>,
    pub parent_url: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SendUpdate {
    pub room_id: String,
    pub txn_id: String,
    pub attempts: u32,
    pub state: SendState,
    pub event_id: Option<String>,
    pub error: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct PresenceInfo {
    pub presence: Presence,
    pub status_msg: Option<String>,
}

#[derive(Clone, Copy, Serialize, Deserialize, Enum)]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Syncing,
    Reconnecting { attempt: u32, next_retry_secs: u32 },
}

#[derive(Clone, Serialize, Deserialize, uniffi::Enum)]
pub enum EventType {
    Message,
    MembershipChange,
    ProfileChange,
    RoomName,
    RoomTopic,
    RoomAvatar,
    RoomEncryption,
    RoomPinnedEvents,
    RoomPowerLevels,
    RoomCanonicalAlias,
    OtherState,
    CallInvite,
    CallNotification,
    Poll,
    Sticker,
    LiveLocation,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum AttachmentKind {
    Image,
    Video,
    File,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum SyncPhase {
    Idle,
    Running,
    BackingOff,
    Error,
}

#[derive(Clone, Copy, PartialEq, Serialize, Deserialize, Enum)]
pub enum RecoveryState {
    Disabled,
    Enabled,
    Incomplete,
    Unknown,
}

#[derive(Clone, Copy, PartialEq, Serialize, Deserialize, Enum)]
pub enum BackupState {
    Unknown,
    Creating,
    Enabling,
    Resuming,
    Enabled,
    Downloading,
    Disabling,
}

#[derive(Clone, Serialize, Deserialize, uniffi::Enum)]
pub enum NotificationKind {
    Message,
    CallRing,
    CallNotify,
    CallInvite,
    Invite,
    StateEvent,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum FfiRoomNotificationMode {
    AllMessages,
    MentionsAndKeywordsOnly,
    Mute,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum TimelineDiffKind {
    Append {
        values: Vec<MessageEvent>,
    },
    PushBack {
        value: MessageEvent,
    },
    PushFront {
        value: MessageEvent,
    },
    PopBack,
    PopFront,
    Truncate {
        length: u32,
    },
    Clear,
    Reset {
        values: Vec<MessageEvent>,
    },
    UpdateByItemId {
        item_id: String,
        value: MessageEvent,
    },
    RemoveByItemId {
        item_id: String,
    },
    UpsertByItemId {
        item_id: String,
        value: MessageEvent,
    },
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum PollKind {
    Disclosed,
    Undisclosed,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum Presence {
    Online,
    Offline,
    Unavailable,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomDirectoryVisibility {
    Public,
    Private,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomJoinRule {
    Public,
    Invite,
    Knock,
    Restricted,
    KnockRestricted,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomPreviewMembership {
    Joined,
    Invited,
    Knocked,
    Left,
    Banned,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomHistoryVisibility {
    Invited,
    Joined,
    Shared,
    WorldReadable,
}

#[derive(Clone, Copy, Serialize, Deserialize, Enum)]
pub enum ElementCallIntent {
    StartCall,
    JoinExisting,
    StartCallVoiceDm,
    JoinExistingVoiceDm,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum SendState {
    Enqueued,
    Sending,
    Sent,
    Retrying,
    Failed,
}

#[export(callback_interface)]
pub trait ConnectionObserver: Send + Sync {
    fn on_connection_change(&self, state: ConnectionState);
}

#[export(callback_interface)]
pub trait SyncObserver: Send + Sync {
    fn on_state(&self, status: SyncStatus);
}

#[export(callback_interface)]
pub trait TypingObserver: Send + Sync {
    fn on_update(&self, names: Vec<String>);
}

#[export(callback_interface)]
pub trait ReceiptsObserver: Send + Sync {
    fn on_changed(&self);
}

#[export(callback_interface)]
pub trait CallObserver: Send + Sync {
    fn on_invite(&self, invite: CallInvite);
}

#[export(callback_interface)]
pub trait RecoveryObserver: Send + Sync {
    fn on_progress(&self, step: String);
    fn on_done(&self, recovery_key: String);
    fn on_error(&self, message: String);
}

#[export(callback_interface)]
pub trait RecoveryStateObserver: Send + Sync {
    fn on_update(&self, state: RecoveryState);
}

#[export(callback_interface)]
pub trait BackupStateObserver: Send + Sync {
    fn on_update(&self, state: BackupState);
}

#[export(callback_interface)]
pub trait ProgressObserver: Send + Sync {
    fn on_progress(&self, sent: u64, total: Option<u64>);
}

#[export(callback_interface)]
pub trait RoomListObserver: Send + Sync {
    fn on_reset(&self, items: Vec<RoomListEntry>);
    fn on_update(&self, item: RoomListEntry);
}

#[uniffi::export(callback_interface)]
pub trait TimelineObserver: Send + Sync {
    fn on_diff(&self, diff: TimelineDiffKind);
    fn on_error(&self, message: String);
}

#[export(callback_interface)]
pub trait VerificationInboxObserver: Send + Sync {
    fn on_request(&self, flow_id: String, from_user: String, from_device: String);
    fn on_error(&self, message: String);
}

#[export(callback_interface)]
pub trait VerifEventListener: Send + Sync {
    fn on_event(&self, event_json: String);
}

#[export(callback_interface)]
pub trait SendObserver: Send + Sync {
    fn on_update(&self, update: SendUpdate);
}

#[export(callback_interface)]
pub trait LiveLocationObserver: Send + Sync {
    fn on_update(&self, shares: Vec<LiveLocationShareInfo>);
}

#[export(callback_interface)]
pub trait CallWidgetObserver: Send + Sync {
    fn on_to_widget(&self, message: String);
}

#[export(callback_interface)]
pub trait UrlOpener: Send + Sync {
    fn open(&self, url: String) -> bool;
}

impl std::str::FromStr for RecoveryState {
    type Err = ();
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(match s {
            "Disabled" => RecoveryState::Disabled,
            "Enabled" => RecoveryState::Enabled,
            "Incomplete" => RecoveryState::Incomplete,
            _ => RecoveryState::Unknown,
        })
    }
}

impl std::fmt::Display for RecoveryState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RecoveryState::Disabled => write!(f, "Disabled"),
            RecoveryState::Enabled => write!(f, "Enabled"),
            RecoveryState::Incomplete => write!(f, "Incomplete"),
            RecoveryState::Unknown => write!(f, "Unknown"),
        }
    }
}

impl From<SuccessorRoom> for SuccessorRoomInfo {
    fn from(v: SuccessorRoom) -> Self {
        SuccessorRoomInfo {
            room_id: v.room_id.to_string(),
            reason: v.reason,
        }
    }
}

impl From<PredecessorRoom> for PredecessorRoomInfo {
    fn from(v: PredecessorRoom) -> Self {
        PredecessorRoomInfo {
            room_id: v.room_id.to_string(),
        }
    }
}

#[derive(Clone)]
pub struct LiveLocationBeaconState {
    pub event_id: String,
    pub duration_ms: u64,
    pub description: Option<String>,
}

pub(crate) enum RoomListCmd {
    SetUnreadOnly(bool),
}

fn default_auth_api() -> String {
    "matrix".to_owned()
}

#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct SessionInfo {
    pub user_id: String,
    pub device_id: String,
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub homeserver: String,
    #[serde(default = "default_auth_api")]
    pub auth_api: String,
    #[serde(default)]
    pub client_id: Option<String>,
}

pub struct TokioDrop<T>(Option<T>);

impl<T> TokioDrop<T> {
    pub fn new(val: T) -> Self {
        Self(Some(val))
    }
}

impl<T> Deref for TokioDrop<T> {
    type Target = T;
    fn deref(&self) -> &Self::Target {
        self.0.as_ref().expect("TokioDrop accessed after drop")
    }
}

impl<T> DerefMut for TokioDrop<T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.0.as_mut().expect("TokioDrop accessed after drop")
    }
}

impl<T> Drop for TokioDrop<T> {
    fn drop(&mut self) {
        // 1. Enter the runtime context safely
        let _guard = RT.enter();
        // 2. Take the value out of the Option, forcing it to drop immediately
        // while the _guard is still alive.
        drop(self.0.take());
    }
}
