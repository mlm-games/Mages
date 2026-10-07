use matrix_sdk::{PredecessorRoom, SuccessorRoom};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::ops::{Deref, DerefMut};
use uniffi::{Enum, Record, export};

use crate::RT;

#[derive(Debug, thiserror::Error, Serialize, uniffi::Error)]
pub enum FfiError {
    #[error("{0}")]
    Msg(String),
    #[error("Beacon is not live")]
    NotLive,
    #[error("Existing beacon information not found")]
    BeaconNotFound,
    #[error("TLS is unavailable on this device: {0}")]
    TlsUnavailable(String),
    #[error("Location permission denied")]
    LocationPermissionDenied,
    #[error("The invite was blocked")]
    InviteBlocked,
    #[error("The homeserver reported that an account limit was exceeded")]
    UserLimitExceeded,
}

/// MSC4335 surfaces `M_USER_LIMIT_EXCEEDED` as a typed error so callers can explain the
/// limit; every other SDK failure keeps the raw text it had before.
fn sdk_error(
    kind: Option<&matrix_sdk::ruma::api::error::ErrorKind>,
    raw: &dyn std::fmt::Debug,
) -> FfiError {
    use matrix_sdk::ruma::api::error::ErrorKind;

    if matches!(kind, Some(ErrorKind::UserLimitExceeded(_))) {
        FfiError::UserLimitExceeded
    } else {
        FfiError::Msg(format!("matrix_sdk error: {raw:?}"))
    }
}

impl From<matrix_sdk::Error> for FfiError {
    fn from(e: matrix_sdk::Error) -> Self {
        sdk_error(e.client_api_error_kind(), &e)
    }
}
impl From<matrix_sdk::HttpError> for FfiError {
    fn from(e: matrix_sdk::HttpError) -> Self {
        sdk_error(e.client_api_error_kind(), &e)
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
    pub beacon_info_event_id: String,
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
    /// MSC3952 `m.mentions.user_ids`, which is the only trustworthy record of who a
    /// message mentions: the anchors in `formatted_body` are written by the sender.
    pub mentioned_user_ids: Vec<String>,
    /// MSC3952 `m.mentions.room`, set when the sender wrote `@room`.
    pub mentions_room: bool,
    pub timestamp_ms: u64,
    pub send_state: Option<SendState>,
    pub txn_id: Option<String>,
    pub reply_to_event_id: Option<String>,
    pub reply_to_sender: Option<String>,
    pub reply_to_sender_display_name: Option<String>,
    pub reply_to_body: Option<String>,
    pub reply_preview: Option<ReplyPreview>,
    pub attachment: Option<AttachmentInfo>,
    pub sticker: Option<StickerInfo>,
    pub thread_root_event_id: Option<String>,
    pub is_edited: bool,
    pub poll_data: Option<PollData>,
    pub reactions: Vec<ReactionSummary>,
    pub event_type: EventType,
    pub is_redacted: bool,
    pub state_event_type: Option<String>,
    pub live_location: Option<LiveLocationEvent>,
    pub raw_json: Option<String>,
    pub shield: Option<MessageShield>,
    pub send_failure: Option<SendFailureReason>,
    pub utd: Option<UtdInfo>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Enum)]
pub enum ShieldLevel {
    Red,
    Grey,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Enum)]
pub enum ShieldCode {
    AuthenticityNotGuaranteed,
    UnknownDevice,
    UnsignedDevice,
    UnverifiedIdentity,
    VerificationViolation,
    MismatchedSender,
    SentInClear,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, Record)]
pub struct MessageShield {
    pub level: ShieldLevel,
    pub code: ShieldCode,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Enum)]
pub enum SendFailureReason {
    UnknownDevice,
    UnverifiedDevice,
    UserIdentityMismatch,
    UnableToDecrypt,
    Unknown,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, Record)]
pub struct UtdInfo {
    pub algorithm_known: bool,
    pub is_megolm: bool,
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
    pub file_name: Option<String>,
    pub mime: Option<String>,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u64>,
    pub thumbnail_mxc_uri: Option<String>,
    pub encrypted: Option<EncFile>,
    pub thumbnail_encrypted: Option<EncFile>,
    pub waveform: Option<Vec<f32>>,
    pub is_voice: Option<bool>,
    pub blurhash: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SendAttachmentRequest {
    pub room_id: String,
    pub path: String,
    pub mime: String,
    pub filename: Option<String>,
    pub caption: Option<String>,
    pub formatted_caption: Option<String>,
    pub reply_to_event_id: Option<String>,
    pub voice_duration_ms: Option<u64>,
    pub voice_waveform: Option<Vec<f32>>,
    pub is_voice: Option<bool>,
    pub txn_id: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct StickerInfo {
    pub mxc_uri: String,
    pub mime: Option<String>,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub thumbnail_mxc_uri: Option<String>,
    pub encrypted: Option<EncFile>,
    pub thumbnail_encrypted: Option<EncFile>,
    pub is_animated: Option<bool>,
    pub blurhash: Option<String>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Enum)]
pub enum ReplyPreviewKind {
    Text,
    Image,
    Video,
    Audio,
    Voice,
    File,
    Sticker,
    Poll,
    Call,
    VoiceCall,
    VideoCall,
    Location,
    LiveLocation,
    Redacted,
    Encrypted,
    Unsupported,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ReplyPreview {
    pub kind: ReplyPreviewKind,
    pub text: Option<String>,
    pub attachment: Option<AttachmentInfo>,
    pub sticker: Option<StickerInfo>,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Enum)]
pub enum NotificationContentKind {
    Text,
    Media,
    Sticker,
    Poll,
    Location,
    Reaction,
    Call,
    Invite,
    Unknown,
}

/// Wire format for a classified event, deliberately flat.
///
/// A tagged enum would need its variant names to match the Kotlin side's
/// `@SerialName` values, and a mismatch there decodes to null on web with no
/// error. A flat record only has to agree on field names, which
/// `ignoreUnknownKeys` tolerates.
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct NotificationContent {
    pub kind: NotificationContentKind,
    pub body: String,
    pub formatted_body: Option<String>,
    pub attachment_kind: Option<AttachmentKind>,
    pub file_name: Option<String>,
    pub mxc_uri: Option<String>,
    pub thumbnail_mxc_uri: Option<String>,
    pub encrypted: Option<EncFile>,
    pub thumbnail_encrypted: Option<EncFile>,
    pub mime: Option<String>,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u64>,
    pub is_voice: Option<bool>,
    pub question: Option<String>,
    pub is_end: Option<bool>,
    pub geo_uri: Option<String>,
    pub is_live: Option<bool>,
    pub reaction_key: Option<String>,
    pub is_invite: Option<bool>,
}

impl NotificationContent {
    pub fn text(body: String, formatted_body: Option<String>) -> Self {
        Self {
            kind: NotificationContentKind::Text,
            body,
            formatted_body,
            attachment_kind: None,
            file_name: None,
            mxc_uri: None,
            thumbnail_mxc_uri: None,
            encrypted: None,
            thumbnail_encrypted: None,
            mime: None,
            size_bytes: None,
            width: None,
            height: None,
            duration_ms: None,
            is_voice: None,
            question: None,
            is_end: None,
            geo_uri: None,
            is_live: None,
            reaction_key: None,
            is_invite: None,
        }
    }

    pub fn media(attachment: &AttachmentInfo, body: String) -> Self {
        Self {
            kind: NotificationContentKind::Media,
            body,
            formatted_body: None,
            attachment_kind: Some(attachment.kind.clone()),
            file_name: attachment.file_name.clone(),
            mxc_uri: Some(attachment.mxc_uri.clone()),
            thumbnail_mxc_uri: attachment.thumbnail_mxc_uri.clone(),
            encrypted: attachment.encrypted.clone(),
            thumbnail_encrypted: attachment.thumbnail_encrypted.clone(),
            mime: attachment.mime.clone(),
            size_bytes: attachment.size_bytes,
            width: attachment.width,
            height: attachment.height,
            duration_ms: attachment.duration_ms,
            is_voice: attachment.is_voice,
            question: None,
            is_end: None,
            geo_uri: None,
            is_live: None,
            reaction_key: None,
            is_invite: None,
        }
    }

    pub fn sticker(sticker: &StickerInfo) -> Self {
        Self {
            kind: NotificationContentKind::Sticker,
            body: String::new(),
            formatted_body: None,
            attachment_kind: Some(AttachmentKind::Image),
            file_name: None,
            mxc_uri: Some(sticker.mxc_uri.clone()),
            thumbnail_mxc_uri: sticker.thumbnail_mxc_uri.clone(),
            encrypted: sticker.encrypted.clone(),
            thumbnail_encrypted: sticker.thumbnail_encrypted.clone(),
            mime: sticker.mime.clone(),
            size_bytes: sticker.size_bytes,
            width: sticker.width,
            height: sticker.height,
            duration_ms: None,
            is_voice: None,
            question: None,
            is_end: None,
            geo_uri: None,
            is_live: None,
            reaction_key: None,
            is_invite: None,
        }
    }

    pub fn poll(question: String, is_end: bool) -> Self {
        Self { kind: NotificationContentKind::Poll, question: Some(question), is_end: Some(is_end), ..Self::text(String::new(), None) }
    }

    pub fn location(geo_uri: String, is_live: bool) -> Self {
        Self { kind: NotificationContentKind::Location, geo_uri: Some(geo_uri), is_live: Some(is_live), ..Self::text(String::new(), None) }
    }

    pub fn reaction(key: String) -> Self {
        Self { kind: NotificationContentKind::Reaction, reaction_key: Some(key), ..Self::text(String::new(), None) }
    }

    pub fn call(invite: bool) -> Self {
        Self { kind: NotificationContentKind::Call, is_invite: Some(invite), ..Self::text(String::new(), None) }
    }

    pub fn invite() -> Self {
        Self { kind: NotificationContentKind::Invite, ..Self::text(String::new(), None) }
    }

    pub fn unknown() -> Self {
        Self { kind: NotificationContentKind::Unknown, ..Self::text(String::new(), None) }
    }
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RenderedNotification {
    pub room_id: String,
    pub event_id: String,
    pub room_name: String,
    pub sender: String,
    pub sender_user_id: String,
    pub content: NotificationContent,
    pub is_noisy: bool,
    pub has_mention: bool,
    pub ts_ms: u64,
    pub is_dm: bool,
    pub kind: NotificationKind,
    pub expires_at_ms: Option<u64>,
    pub sender_avatar_url: Option<String>,
    pub room_avatar_url: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct UnreadStats {
    pub messages: u64,
    pub notifications: u64,
    pub mentions: u64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
pub struct RoomProfile {
    pub room_id: String,
    pub name: String,
    pub topic: Option<String>,
    pub member_count: u64,
    pub is_encrypted: bool,
    pub is_dm: bool,
    pub is_public: bool,
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

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize, Enum)]
pub enum ActionPresentation {
    Hidden,
    Disabled,
    Enabled,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
pub struct ActionAvailability {
    pub presentation: ActionPresentation,
    pub reason: Option<String>,
}

impl ActionAvailability {
    pub fn hidden() -> Self {
        Self {
            presentation: ActionPresentation::Hidden,
            reason: None,
        }
    }

    pub fn disabled(reason: impl Into<String>) -> Self {
        Self {
            presentation: ActionPresentation::Disabled,
            reason: Some(reason.into()),
        }
    }

    pub fn enabled() -> Self {
        Self {
            presentation: ActionPresentation::Enabled,
            reason: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
pub struct RoomActionState {
    pub room_id: String,
    pub voice_call: ActionAvailability,
    pub video_call: ActionAvailability,
    pub send_message: ActionAvailability,
    pub send_reaction: ActionAvailability,
    pub edit_name: ActionAvailability,
    pub edit_topic: ActionAvailability,
    pub invite: ActionAvailability,
    pub manage_settings: ActionAvailability,
    pub space_child: ActionAvailability,
    pub redact_others: ActionAvailability,
    pub pin: ActionAvailability,
}

#[derive(Clone, Debug, Serialize, Deserialize, Record)]
pub struct MemberActionState {
    pub room_id: String,
    pub user_id: String,
    pub direct_message: ActionAvailability,
    pub kick: ActionAvailability,
    pub ban: ActionAvailability,
    pub unban: ActionAvailability,
}

#[derive(Clone, Debug, Serialize, Deserialize, Record)]
pub struct MessageActionState {
    pub room_id: String,
    pub event_id: String,
    pub edit: ActionAvailability,
    pub delete: ActionAvailability,
    pub pin: ActionAvailability,
    pub unpin: ActionAvailability,
    pub react: ActionAvailability,
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

#[derive(Clone, Copy, Serialize, Deserialize, Enum)]
pub enum MediaClearScope {
    All,
    Temporary,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct MediaCacheOverview {
    pub total_bytes: u64,
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
    pub membership: RoomListMembership,
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
pub struct OwnProfile {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    pub can_change_display_name: bool,
    pub can_change_avatar: bool,
}

/// One MSC4133 extended profile field, with its value as plain text.
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ProfileField {
    pub name: String,
    pub value: String,
}

/// MSC2666: rooms the local user and another user are both joined to.
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct MutualRooms {
    pub count: u64,
    pub room_ids: Vec<String>,
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
    #[serde(default)]
    pub user_ids: Vec<String>,
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
    pub canonical_alias: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ForwardResult {
    pub sent: Vec<String>,
    pub failed: Vec<String>,
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
    /// Our own membership, absent when we have no local state for the child.
    pub membership: Option<RoomListMembership>,
    pub section_tag: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceSection {
    pub tag: String,
    pub name: String,
    pub space_id: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceHierarchyPage {
    pub children: Vec<SpaceChildInfo>,
    pub next_batch: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceParentInfo {
    pub space_id: String,
    pub name: Option<String>,
    pub avatar_url: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, Record)]
pub struct SpaceUnread {
    pub space_id: String,
    pub unread_messages: u64,
    pub unread_notifications: u64,
}

/// One image inside an image pack (spec v1.19). `info_json` stays an opaque JSON
/// string so it can be handed back verbatim when sending, instead of being
/// re-derived from a download.
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ImagePackImageEntry {
    pub shortcode: String,
    pub mxc_url: String,
    pub body: Option<String>,
    pub info_json: Option<String>,
    pub thumbnail_mxc_uri: Option<String>,
    pub is_animated: Option<bool>,
}

/// An `m.room.image_pack` visible from some room, with its images resolved.
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct ImagePackSummary {
    /// Stable identity of a pack: `"<room_id>\u{1f}<state_key>"`.
    pub pack_id: String,
    pub source_room: String,
    /// Display name of `source_room`, when the room store knows it, so a
    /// cross-room pack list can label its source without a second lookup.
    pub source_room_name: Option<String>,
    /// `m.room.image_pack` state key; empty string is a valid key per the spec.
    pub state_key: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    /// Empty means both stickers and emoticons, per the spec's default.
    pub usage: Vec<String>,
    pub attribution: Option<String>,
    /// True when the user has this pack enabled globally through
    /// `m.image_pack.rooms`, which is what makes it reachable outside its
    /// source room.
    pub is_global: bool,
    pub images: Vec<ImagePackImageEntry>,
}

/// One entry of `m.recent_emoji` (spec v1.18).
#[derive(Clone, Serialize, Deserialize, Record)]
pub struct RecentEmojiEntry {
    pub emoji: String,
    pub total: u64,
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
    pub beacon_info_event_id: String,
    pub end_timestamp_ms: u64,
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

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
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
    pub beacon: i64,
    pub beacon_info: i64,
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
    pub beacon: Option<i64>,
    pub beacon_info: Option<i64>,
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
    pub homeserver_url: String,
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

#[derive(Clone, Serialize, Deserialize, Enum)]
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
    Location,
    Unsupported,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum AttachmentKind {
    Image,
    Video,
    Audio,
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

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum NotificationKind {
    Message,
    Reaction,
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

#[derive(Clone, Debug, Serialize, Deserialize, Enum)]
pub enum FfiPushRuleKind {
    Override,
    Underride,
    Sender,
    Room,
    Content,
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

#[derive(Clone, Copy, Serialize, Deserialize, Enum, PartialEq, Eq)]
pub enum MediaPreviewMode {
    On,
    Private,
    Off,
}

/// OpenGraph fields the card renders; `og:image` is an MXC URI here, not an HTTP URL.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, Record)]
pub struct LinkPreview {
    pub url: String,
    pub title: Option<String>,
    pub description: Option<String>,
    pub site_name: Option<String>,
    pub image_mxc_uri: Option<String>,
    pub image_mime_type: Option<String>,
    pub image_alt: Option<String>,
    pub image_width: Option<u32>,
    pub image_height: Option<u32>,
    pub image_size_bytes: Option<u64>,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomDirectoryVisibility {
    Public,
    Private,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Enum)]
pub enum RoomJoinRule {
    Public,
    Invite,
    Knock,
    Restricted,
    KnockRestricted,
}

#[derive(Clone, Copy, Serialize, Deserialize, Enum)]
pub enum PasswordLoginKind {
    Username,
    Email,
    Phone,
}

#[derive(Clone, Serialize, Deserialize, Enum)]
pub enum RoomPreviewMembership {
    Joined,
    Invited,
    Knocked,
    Left,
    Banned,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Enum)]
pub enum RoomHistoryVisibility {
    Invited,
    Joined,
    Shared,
    WorldReadable,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Enum)]
pub enum RoomListMembership {
    Joined,
    Invited,
    Left,
    Knocked,
    Banned,
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

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
pub struct RoomCallState {
    pub has_active_call: bool,
    pub active_participants: Vec<String>,
}

#[export(callback_interface)]
pub trait RoomCallStateObserver: Send + Sync {
    fn on_update(&self, state: RoomCallState);
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Record)]
pub struct RoomInfoSnapshot {
    pub room_id: String,
    pub profile: RoomProfile,
    pub power_levels: RoomPowerLevels,
    pub action_state: RoomActionState,
    pub call_state: RoomCallState,
    pub membership: RoomListMembership,
    pub join_rule: Option<RoomJoinRule>,
    pub history_visibility: Option<RoomHistoryVisibility>,
    pub pinned_event_ids: Option<Vec<String>>,
}

#[export(callback_interface)]
pub trait RoomInfoObserver: Send + Sync {
    fn on_update(&self, snapshot: RoomInfoSnapshot);
}

#[export(callback_interface)]
pub trait CallDeclineObserver: Send + Sync {
    fn on_decline(&self, decliner_user_id: String);
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

#[derive(Clone, Record)]
pub struct BeaconInfoUpdate {
    pub room_id: String,
    pub event_id: String,
    pub live: bool,
}

#[export(callback_interface)]
pub trait BeaconInfoListener: Send + Sync {
    fn on_update(&self, update: BeaconInfoUpdate);
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

pub(crate) enum RoomListCmd {
    SetUnreadOnly(bool),
    UpdateVisibleRange((Vec<u64>, usize)),
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
    #[serde(default = "default_token_valid")]
    pub is_token_valid: bool,
}

fn default_token_valid() -> bool {
    true
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
