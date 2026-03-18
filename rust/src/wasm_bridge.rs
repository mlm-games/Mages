use crate::core::{CoreClient, TimelineManager, map_send_queue_update};
use crate::errors::{IntoFfi, OptionFfi};
use crate::types::*;
use crate::{
    emit_timeline_reset_filled, latest_room_event_for, mages_client_metadata, map_vec_diff,
    missing_reply_event_id, strip_matrix_path,
};

use futures_util::StreamExt;
use futures_util::future::{AbortHandle, Abortable};
use js_int::UInt;
use js_sys::Function;
use matrix_sdk::authentication::oauth::UrlOrQuery;
use matrix_sdk::authentication::oauth::registration::language_tags::LanguageTag;
use matrix_sdk::ruma::events::room::{
    EncryptedFile, ImageInfo, MediaSource,
    message::{
        FileInfo, FileMessageEventContent, ImageMessageEventContent, MessageType,
        Relation as MsgRelation, RoomMessageEventContent, VideoInfo, VideoMessageEventContent,
    },
    name::RoomNameEventContent,
    topic::RoomTopicEventContent,
};
use matrix_sdk::ruma::events::{
    beacon::BeaconEventContent,
    beacon_info::BeaconInfoEventContent,
    key::verification::request::ToDeviceKeyVerificationRequestEvent,
    receipt::SyncReceiptEvent,
    relation::Thread as ThreadRel,
    room::message::{RoomMessageEventContentWithoutRelation as MsgNoRel, SyncRoomMessageEvent},
    space::child::SpaceChildEventContent,
};
use matrix_sdk::ruma::{
    EventId, OwnedDeviceId, OwnedEventId, OwnedRoomAliasId, OwnedRoomId, OwnedRoomOrAliasId,
    OwnedUserId, SpaceChildOrder,
    api::client::presence::{
        get_presence::v3 as get_presence_v3, set_presence::v3 as set_presence_v3,
    },
    api::client::receipt::create_receipt::v3::ReceiptType,
    events::{TimelineEventType, receipt::ReceiptThread, relation::RelationType},
    owned_device_id,
    presence::PresenceState,
    room::JoinRuleSummary,
    room::RoomType,
};
use matrix_sdk::sleep::sleep;
use matrix_sdk::widget::{
    Capabilities, CapabilitiesProvider, ClientProperties, Intent as WidgetIntent,
    VirtualElementCallWidgetConfig, VirtualElementCallWidgetProperties, WidgetDriver,
    WidgetDriverHandle, WidgetSettings,
};
use matrix_sdk::{
    Client as SdkClient, Room, RoomDisplayName, RoomMemberships, RoomState, SessionMeta,
    SessionTokens,
    authentication::{
        AuthSession,
        matrix::MatrixSession,
        oauth::{ClientId, OAuthSession, UserSession},
    },
    encryption::verification::{Verification, VerificationRequest},
    encryption::{BackupDownloadStrategy, EncryptionSettings},
    media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings},
};
use matrix_sdk_ui::{
    eyeball_im::{Vector, VectorDiff},
    notification_client::{NotificationClient, NotificationProcessSetup, NotificationStatus},
    room_list_service::filters,
    sync_service::{State, SyncService},
    timeline::RoomExt,
};
use serde_json;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::rc::Rc;
use std::sync::Arc;
use wasm_bindgen::prelude::*;
use web_time::Duration;

fn to_json<T: serde::Serialize>(v: &T) -> JsValue {
    serde_wasm_bindgen::to_value(v)
        .unwrap_or_else(|e| JsValue::from_str(&format!("{{\"error\":\"{e}\"}}")))
}

#[wasm_bindgen(js_name = base64Encode)]
pub fn base64_encode(data: &[u8]) -> Result<String, JsValue> {
    let window = web_sys::window().ok_or_else(|| JsValue::from_str("no window"))?;
    let binary_string: String = data.iter().map(|&b| b as char).collect();
    window.btoa(&binary_string)
}

fn call_js(f: &Function, arg: JsValue) {
    let _ = f.call1(&JsValue::NULL, &arg);
}
fn call_js0(f: &Function) {
    let _ = f.call0(&JsValue::NULL);
}

macro_rules! wasm_delegate_bool {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> bool {
                    let Some(s) = self.state() else { return false; };
                    s.core.$method($($arg),*).await
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_result_bool {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> bool {
                    let Some(s) = self.state() else { return false; };
                    s.core.$method($($arg),*).await.is_ok()
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) or $default:expr );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return to_json(&$default); };
                    to_json(&s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_result_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return JsValue::NULL; };
                    match s.core.$method($($arg),*).await {
                        Ok(v) => to_json(&v),
                        Err(_) => JsValue::NULL,
                    }
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_option_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return JsValue::NULL; };
                    match s.core.$method($($arg),*).await {
                        Some(v) => to_json(&v),
                        None => JsValue::NULL,
                    }
                }
            )+
        }
    };
}

macro_rules! wasm_unobserve {
    ($( $js_name:literal => $method:ident($field:ident) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub fn $method(&self, sub_id: f64) -> bool {
                    self.state().map(|s| Self::abort_sub(&s.$field, sub_id as u64)).unwrap_or(false)
                }
            )+
        }
    };
}

macro_rules! wasm_subscribe {
    ($state:expr, $field:ident, $body:expr) => {{
        let id = $state.next_sub_id();
        let (ah, ar) = AbortHandle::new_pair();
        $state.$field.borrow_mut().insert(id, ah);
        wasm_bindgen_futures::spawn_local(async move {
            let _ = Abortable::new($body, ar).await;
        });
        id as f64
    }};
}

macro_rules! js_observer_json {
    ($name:ident : $trait:ident :: $method:ident, $arg:ident : $ty:ty) => {
        struct $name(Function);
        impl $trait for $name {
            fn $method(&self, $arg: $ty) {
                call_js(&self.0, to_json(&$arg));
            }
        }
        unsafe impl Send for $name {}
        unsafe impl Sync for $name {}
    };
}

macro_rules! js_observer_noargs {
    ($name:ident : $trait:ident :: $method:ident) => {
        struct $name(Function);
        impl $trait for $name {
            fn $method(&self) {
                call_js0(&self.0);
            }
        }
        unsafe impl Send for $name {}
        unsafe impl Sync for $name {}
    };
}

js_observer_json!(JsConnectionObserver: ConnectionObserver::on_connection_change, state: ConnectionState);
js_observer_json!(JsSyncObserver: SyncObserver::on_state, status: SyncStatus);
js_observer_json!(JsSendObserver: SendObserver::on_update, update: SendUpdate);
js_observer_noargs!(JsReceiptsObserver: ReceiptsObserver::on_changed);
js_observer_json!(JsTypingObserver: TypingObserver::on_update, names: Vec<String>);
js_observer_json!(JsCallObserver: CallObserver::on_invite, invite: CallInvite);
js_observer_json!(JsLiveLocationObserver: LiveLocationObserver::on_update, shares: Vec<LiveLocationShareInfo>);
js_observer_json!(JsCallWidgetObserver: CallWidgetObserver::on_to_widget, message: String);
js_observer_json!(JsRecoveryStateObserver: RecoveryStateObserver::on_update, state: RecoveryState);
js_observer_json!(JsBackupStateObserver: BackupStateObserver::on_update, state: BackupState);

struct JsTimelineObserver(Function, Function);
impl TimelineObserver for JsTimelineObserver {
    fn on_diff(&self, diff: TimelineDiffKind) {
        call_js(&self.0, to_json(&diff));
    }
    fn on_error(&self, message: String) {
        call_js(&self.1, JsValue::from_str(&message));
    }
}
unsafe impl Send for JsTimelineObserver {}
unsafe impl Sync for JsTimelineObserver {}

struct JsRoomListObserver(Function, Function);
impl RoomListObserver for JsRoomListObserver {
    fn on_reset(&self, items: Vec<RoomListEntry>) {
        call_js(&self.0, to_json(&items));
    }
    fn on_update(&self, item: RoomListEntry) {
        call_js(&self.1, to_json(&item));
    }
}
unsafe impl Send for JsRoomListObserver {}
unsafe impl Sync for JsRoomListObserver {}

struct JsVerificationInboxObserver(Function, Function);
impl VerificationInboxObserver for JsVerificationInboxObserver {
    fn on_request(&self, flow_id: String, from_user: String, from_device: String) {
        let payload = serde_json::json!({"flowId": flow_id, "fromUser": from_user, "fromDevice": from_device});
        call_js(&self.0, JsValue::from_str(&payload.to_string()));
    }
    fn on_error(&self, message: String) {
        call_js(&self.1, JsValue::from_str(&message));
    }
}
unsafe impl Send for JsVerificationInboxObserver {}
unsafe impl Sync for JsVerificationInboxObserver {}

struct JsVerificationObserver(Function, Function, Function);
impl VerificationObserver for JsVerificationObserver {
    fn on_phase(&self, flow_id: String, phase: SasPhase) {
        call_js(
            &self.0,
            JsValue::from_str(
                &serde_json::json!({"flowId": flow_id, "phase": format!("{:?}", phase)})
                    .to_string(),
            ),
        );
    }
    fn on_emojis(&self, payload: SasEmojis) {
        call_js(&self.1, to_json(&payload));
    }
    fn on_error(&self, flow_id: String, message: String) {
        call_js(
            &self.2,
            JsValue::from_str(
                &serde_json::json!({"flowId": flow_id, "message": message}).to_string(),
            ),
        );
    }
}
unsafe impl Send for JsVerificationObserver {}
unsafe impl Sync for JsVerificationObserver {}

struct JsRecoveryObserver(Function, Function, Function);
impl RecoveryObserver for JsRecoveryObserver {
    fn on_progress(&self, step: String) {
        call_js(&self.0, JsValue::from_str(&step));
    }
    fn on_done(&self, recovery_key: String) {
        call_js(&self.1, JsValue::from_str(&recovery_key));
    }
    fn on_error(&self, message: String) {
        call_js(&self.2, JsValue::from_str(&message));
    }
}
unsafe impl Send for JsRecoveryObserver {}
unsafe impl Sync for JsRecoveryObserver {}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct WasmSessionInfo {
    #[serde(default = "default_auth_api")]
    auth_api: String,
    #[serde(default)]
    client_id: Option<String>,
    user_id: String,
    device_id: String,
    access_token: String,
    refresh_token: Option<String>,
    homeserver: String,
}
fn default_auth_api() -> String {
    "matrix".to_owned()
}
fn wasm_session_key(store_name: &str) -> String {
    format!("mages_session_{store_name}")
}

fn load_wasm_session(store_name: &str) -> Option<WasmSessionInfo> {
    let storage = web_sys::window()?.local_storage().ok()??;
    let raw = storage.get_item(&wasm_session_key(store_name)).ok()??;
    serde_json::from_str(&raw).ok()
}

fn save_wasm_session(store_name: &str, session: &WasmSessionInfo) {
    let Some(storage) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) else {
        return;
    };
    let Ok(raw) = serde_json::to_string(session) else {
        return;
    };
    let _ = storage.set_item(&wasm_session_key(store_name), &raw);
}

fn clear_wasm_session(store_name: &str) {
    let Some(storage) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) else {
        return;
    };
    let _ = storage.remove_item(&wasm_session_key(store_name));
}

struct WasmAsyncState {
    core: Rc<CoreClient>,
    store_name: String,
    sync_service: RefCell<Option<Arc<SyncService>>>,
    room_list_cache: RefCell<Vec<RoomListEntry>>,
    send_observers: RefCell<HashMap<u64, Function>>,
    send_obs_counter: Cell<u64>,
    send_queue_supervised: Cell<bool>,
    room_list_subs: RefCell<HashMap<u64, AbortHandle>>,
    room_list_cmds: RefCell<HashMap<u64, tokio::sync::mpsc::UnboundedSender<RoomListCmd>>>,
    timeline_subs: RefCell<HashMap<u64, AbortHandle>>,
    connection_subs: RefCell<HashMap<u64, AbortHandle>>,
    typing_subs: RefCell<HashMap<u64, AbortHandle>>,
    receipts_subs: RefCell<HashMap<u64, AbortHandle>>,
    inbox_subs: RefCell<HashMap<u64, AbortHandle>>,
    recovery_state_subs: RefCell<HashMap<u64, AbortHandle>>,
    backup_state_subs: RefCell<HashMap<u64, AbortHandle>>,
    app_in_foreground: Cell<bool>,
    call_subs: RefCell<HashMap<u64, AbortHandle>>,
    live_location_subs: RefCell<HashMap<u64, AbortHandle>>,
    widget_handles: RefCell<HashMap<u64, WidgetDriverHandle>>,
    widget_driver_subs: RefCell<HashMap<u64, AbortHandle>>,
    widget_recv_subs: RefCell<HashMap<u64, AbortHandle>>,
}

impl WasmAsyncState {
    fn client(&self) -> &SdkClient {
        &self.core.sdk
    }
    fn tm(&self) -> &TimelineManager {
        &self.core.timeline_mgr
    }
    fn next_sub_id(&self) -> u64 {
        let next = self.send_obs_counter.get().wrapping_add(1);
        self.send_obs_counter.set(next);
        next
    }

    async fn ensure_sync_service(&self) -> Option<Arc<SyncService>> {
        if let Some(svc) = self.sync_service.borrow().as_ref().cloned() {
            return Some(svc);
        }
        if self.client().session_meta().is_none() {
            return None;
        }
        let svc: Arc<SyncService> = SyncService::builder(self.client().clone())
            .with_offline_mode()
            .build()
            .await
            .ok()?
            .into();
        self.sync_service.borrow_mut().replace(svc.clone());
        Some(svc)
    }

    fn persist_session(&self) {
        match self.client().session() {
            Some(AuthSession::Matrix(sess)) => {
                save_wasm_session(
                    &self.store_name,
                    &WasmSessionInfo {
                        auth_api: "matrix".into(),
                        client_id: None,
                        user_id: sess.meta.user_id.to_string(),
                        device_id: sess.meta.device_id.to_string(),
                        access_token: sess.tokens.access_token,
                        refresh_token: sess.tokens.refresh_token,
                        homeserver: self.client().homeserver().to_string(),
                    },
                );
            }
            Some(AuthSession::OAuth(sess)) => {
                let sess = *sess;
                save_wasm_session(
                    &self.store_name,
                    &WasmSessionInfo {
                        auth_api: "oauth".into(),
                        client_id: Some(sess.client_id.to_string()),
                        user_id: sess.user.meta.user_id.to_string(),
                        device_id: sess.user.meta.device_id.to_string(),
                        access_token: sess.user.tokens.access_token,
                        refresh_token: sess.user.tokens.refresh_token,
                        homeserver: self.client().homeserver().to_string(),
                    },
                );
            }
            None => clear_wasm_session(&self.store_name),
            _ => {}
        }
    }

    fn dispatch_send_update(&self, update: &SendUpdate) {
        for cb in self.send_observers.borrow().values() {
            call_js(cb, to_json(update));
        }
    }

    fn ensure_send_queue_supervision(self: &Rc<Self>) {
        if self.send_queue_supervised.replace(true) {
            return;
        }
        let state = self.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let mut rx = state.client().send_queue().subscribe();
            let mut attempts: HashMap<String, u32> = HashMap::new();
            loop {
                let upd = match rx.recv().await {
                    Ok(u) => u,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                };
                let rid = upd.room_id.to_string();
                if let Some(u) = map_send_queue_update(&rid, upd.update, &mut attempts) {
                    state.dispatch_send_update(&u);
                }
            }
        });
    }
}

#[wasm_bindgen]
pub struct WasmClient {
    async_state: Rc<RefCell<Option<Rc<WasmAsyncState>>>>,
}

impl WasmClient {
    fn abort_sub(map: &RefCell<HashMap<u64, AbortHandle>>, id: u64) -> bool {
        if let Some(h) = map.borrow_mut().remove(&id) {
            h.abort();
            true
        } else {
            false
        }
    }
}

wasm_delegate_bool! {
    "markRead"             => mark_read(room_id: String);
    "markReadAt"           => mark_read_at(room_id: String, event_id: String);
    "markFullyReadAt"      => mark_fully_read_at(room_id: String, event_id: String);
    "setTyping"            => set_typing(room_id: String, typing: bool);
    "setRoomFavourite"     => set_room_favourite(room_id: String, fav: bool);
    "setRoomLowPriority"   => set_room_low_priority(room_id: String, low: bool);
    "setRoomName"          => set_room_name(room_id: String, name: String);
    "setRoomTopic"         => set_room_topic(room_id: String, topic: String);
    "banUser"              => ban_user(room_id: String, user_id: String, reason: Option<String>);
    "unbanUser"            => unban_user(room_id: String, user_id: String, reason: Option<String>);
    "kickUser"             => kick_user(room_id: String, user_id: String, reason: Option<String>);
    "inviteUser"           => invite_user(room_id: String, user_id: String);
    "enableRoomEncryption" => enable_room_encryption(room_id: String);
    "isSpace"              => is_space(room_id: String);
    "knock"                => knock(id_or_alias: String);
    "isUserIgnored"        => is_user_ignored(user_id: String);
    "react"                => react(room_id: String, event_id: String, emoji: String);
    "spaceInviteUser"      => space_invite_user(space_id: String, user_id: String);
    "redact"               => redact(room_id: String, event_id: String, reason: Option<String>);
}

wasm_delegate_result_bool! {
    "ignoreUser"       => ignore_user(user_id: String);
    "unignoreUser"     => unignore_user(user_id: String);
    "leaveRoom"        => leave_room(room_id: String);
    "spaceAddChild"    => space_add_child(space_id: String, child_room_id: String, order: Option<String>, suggested: Option<bool>);
    "spaceRemoveChild" => space_remove_child(space_id: String, child_room_id: String);
}

wasm_delegate_json! {
    "rooms"             => rooms()                                                or Vec::<RoomSummary>::new();
    "recentEvents"      => recent_events(room_id: String, limit: u32)             or Vec::<MessageEvent>::new();
    "ownLastRead"       => own_last_read(room_id: String)                         or OwnReceipt { event_id: None, ts_ms: None };
    "reactionsForEvent" => reactions_for_event(room_id: String, event_id: String)  or Vec::<ReactionSummary>::new();
    "mySpaces"          => my_spaces()                                             or Vec::<SpaceInfo>::new();
}

wasm_delegate_result_json! {
    "roomPowerLevels"  => room_power_levels(room_id: String);
    "getPresence"      => get_presence(user_id: String);
    "roomPreview"      => room_preview(id_or_alias: String);
    "spaceHierarchy"   => space_hierarchy(space_id: String, from: Option<String>, limit: u32, max_depth: Option<u32>, suggested_only: bool);
    "threadReplies"    => thread_replies(room_id: String, root_event_id: String, from: Option<String>, limit: u32, forward: bool);
    "threadSummary"    => thread_summary(room_id: String, root_event_id: String, per_page: u32, max_pages: u32);
    "listMembers"      => list_members(room_id: String);
    "listInvited"      => list_invited();
    "ignoredUsers"     => ignored_users();
}

wasm_delegate_option_json! {
    "roomUnreadStats"  => room_unread_stats(room_id: String);
    "roomSuccessor"    => room_successor(room_id: String);
    "roomPredecessor"  => room_predecessor(room_id: String);
}

wasm_unobserve! {
    "unobserveTimeline"          => unobserve_timeline(timeline_subs);
    "unobserveTyping"            => unobserve_typing(typing_subs);
    "unobserveReceipts"          => unobserve_receipts(receipts_subs);
    "unobserveLiveLocation"      => unobserve_live_location(live_location_subs);
    "stopCallInbox"              => stop_call_inbox(call_subs);
    "unobserveVerificationInbox" => unobserve_verification_inbox(inbox_subs);
    "unobserveRecoveryState"     => unobserve_recovery_state(recovery_state_subs);
    "unobserveBackupState"       => unobserve_backup_state(backup_state_subs);
    "unobserveRoomList"          => unobserve_room_list(room_list_subs);
}

#[wasm_bindgen]
impl WasmClient {
    #[wasm_bindgen(js_name = createAsync)]
    pub async fn create_async(
        homeserver_url: String,
        _base_store_dir: String,
        account_id: Option<String>,
    ) -> Result<WasmClient, JsValue> {
        let normalized = {
            let raw = homeserver_url.trim();
            matrix_sdk::reqwest::Url::parse(raw)
                .or_else(|_| matrix_sdk::reqwest::Url::parse(&format!("https://{raw}")))
                .map(strip_matrix_path)
                .map(|u| u.to_string())
                .unwrap_or_else(|_| raw.to_owned())
        };
        let store_name = account_id
            .as_ref()
            .map(|id| format!("mages_store_{id}"))
            .unwrap_or_else(|| "mages_store".to_owned());
        let client = SdkClient::builder()
            .server_name_or_homeserver_url(normalized)
            .indexeddb_store(&store_name, None)
            .with_encryption_settings(EncryptionSettings {
                auto_enable_cross_signing: true,
                auto_enable_backups: true,
                backup_download_strategy: BackupDownloadStrategy::OneShot,
                ..Default::default()
            })
            .handle_refresh_tokens()
            .build()
            .await
            .map_err(|e| JsValue::from_str(&format!("build failed: {e}")))?;
        if let Some(info) = load_wasm_session(&store_name) {
            if let Ok(user_id) = info.user_id.parse() {
                let meta = SessionMeta {
                    user_id,
                    device_id: info.device_id.into(),
                };
                let tokens = SessionTokens {
                    access_token: info.access_token,
                    refresh_token: info.refresh_token,
                };
                if info.auth_api == "oauth" {
                    if let Some(cid) = info.client_id {
                        let _ = client
                            .restore_session(OAuthSession {
                                client_id: ClientId::new(cid),
                                user: UserSession { meta, tokens },
                            })
                            .await;
                    }
                } else {
                    let _ = client.restore_session(MatrixSession { meta, tokens }).await;
                }
            }
        }
        let core = Rc::new(CoreClient::new(client));
        let state = Rc::new(WasmAsyncState {
            core,
            store_name,
            sync_service: RefCell::new(None),
            room_list_cache: RefCell::new(Vec::new()),
            send_observers: RefCell::new(HashMap::new()),
            send_obs_counter: Cell::new(0),
            send_queue_supervised: Cell::new(false),
            room_list_subs: RefCell::new(HashMap::new()),
            room_list_cmds: RefCell::new(HashMap::new()),
            timeline_subs: RefCell::new(HashMap::new()),
            connection_subs: RefCell::new(HashMap::new()),
            typing_subs: RefCell::new(HashMap::new()),
            receipts_subs: RefCell::new(HashMap::new()),
            inbox_subs: RefCell::new(HashMap::new()),
            recovery_state_subs: RefCell::new(HashMap::new()),
            backup_state_subs: RefCell::new(HashMap::new()),
            app_in_foreground: Cell::new(false),
            call_subs: RefCell::new(HashMap::new()),
            live_location_subs: RefCell::new(HashMap::new()),
            widget_handles: RefCell::new(HashMap::new()),
            widget_driver_subs: RefCell::new(HashMap::new()),
            widget_recv_subs: RefCell::new(HashMap::new()),
        });
        Ok(WasmClient {
            async_state: Rc::new(RefCell::new(Some(state))),
        })
    }

    fn state(&self) -> Option<Rc<WasmAsyncState>> {
        self.async_state.borrow().as_ref().cloned()
    }

    #[wasm_bindgen(js_name = whoami)]
    pub fn whoami(&self) -> Option<String> {
        self.state()?.core.whoami()
    }

    #[wasm_bindgen(js_name = isLoggedIn)]
    pub fn is_logged_in(&self) -> bool {
        self.state().map(|s| s.core.is_logged_in()).unwrap_or(false)
    }

    #[wasm_bindgen(js_name = homeserverUrl)]
    pub fn homeserver_url(&self) -> String {
        self.state()
            .map(|s| s.client().homeserver().to_string())
            .unwrap_or_default()
    }

    #[wasm_bindgen(js_name = loginAsync)]
    pub async fn login_async(
        &self,
        username: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Option<String> {
        let state = self.state()?;
        let mut req = state
            .client()
            .matrix_auth()
            .login_username(username.as_str(), &password);
        if let Some(ref name) = device_display_name {
            req = req.initial_device_display_name(name);
        }
        if let Err(e) = req.send().await {
            return Some(e.to_string());
        }
        state.persist_session();
        state
            .client()
            .encryption()
            .wait_for_e2ee_initialization_tasks()
            .await;
        let _ = state.ensure_sync_service().await;
        let _ = state.client().event_cache().subscribe();
        state.ensure_send_queue_supervision();
        None
    }

    #[wasm_bindgen]
    pub fn logout(&self) -> bool {
        if let Some(state) = self.state() {
            clear_wasm_session(&state.store_name);
        }
        false
    }

    #[wasm_bindgen(js_name = homeserverLoginDetails)]
    pub async fn homeserver_login_details(&self) -> JsValue {
        let Some(state) = self.state() else {
            return JsValue::from_str(
                "{\"supportsOauth\":false,\"supportsSso\":false,\"supportsPassword\":true}",
            );
        };
        let supports_oauth = state.client().oauth().server_metadata().await.is_ok();
        let (supports_sso, supports_password) =
            match state.client().matrix_auth().get_login_types().await {
                Ok(r) => {
                    use matrix_sdk::ruma::api::client::session::get_login_types::v3::LoginType;
                    (
                        r.flows.iter().any(|f| matches!(f, LoginType::Sso(_))),
                        r.flows.iter().any(|f| matches!(f, LoginType::Password(_))),
                    )
                }
                Err(_) => (false, false),
            };
        to_json(&HomeserverLoginDetails {
            supports_oauth,
            supports_sso,
            supports_password,
        })
    }

    #[wasm_bindgen(js_name = loginOauthBrowser)]
    pub async fn login_oauth_browser(
        &self,
        redirect_uri: String,
        _device_name: Option<String>,
    ) -> JsValue {
        let redirect = match matrix_sdk::reqwest::Url::parse(&redirect_uri) {
            Ok(v) => v,
            Err(e) => return to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
        };
        let Some(state) = self.state() else {
            return to_json(&serde_json::json!({"ok":false,"error":"not initialized"}));
        };
        let reg = mages_client_metadata(&redirect).into();
        match state
            .client()
            .oauth()
            .login(redirect, None, Some(reg), None)
            .build()
            .await
        {
            Ok(data) => to_json(&serde_json::json!({"ok":true,"url":data.url.to_string()})),
            Err(e) => to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
        }
    }

    #[wasm_bindgen(js_name = finishLoginFromRedirect)]
    pub async fn finish_login_from_redirect(
        &self,
        callback_url_or_query: String,
        _expected_state: String,
        _expected_issuer: Option<String>,
    ) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let url_or_query = match matrix_sdk::reqwest::Url::parse(&callback_url_or_query) {
            Ok(url) => UrlOrQuery::Url(url),
            Err(_) => UrlOrQuery::Query(callback_url_or_query),
        };
        if state
            .client()
            .oauth()
            .finish_login(url_or_query)
            .await
            .is_err()
        {
            return false;
        }
        state.persist_session();
        state
            .client()
            .encryption()
            .wait_for_e2ee_initialization_tasks()
            .await;
        let _ = state.ensure_sync_service().await;
        let _ = state.client().event_cache().subscribe();
        state.ensure_send_queue_supervision();
        true
    }

    // Methods with wasm-specific signatures (cast, missing optional args)

    #[wasm_bindgen(js_name = sendMessage)]
    pub async fn send_message(&self, room_id: String, body: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.send_message(room_id, body, None).await
    }

    #[wasm_bindgen(js_name = reply)]
    pub async fn reply(&self, room_id: String, in_reply_to: String, body: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.reply(room_id, in_reply_to, body, None).await
    }

    #[wasm_bindgen(js_name = edit)]
    pub async fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.edit(room_id, target_event_id, new_body, None).await
    }

    #[wasm_bindgen(js_name = paginateBackwards)]
    pub async fn paginate_backwards(&self, room_id: String, count: u32) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.paginate_backwards(room_id, count as u16).await
    }

    #[wasm_bindgen(js_name = paginateForwards)]
    pub async fn paginate_forwards(&self, room_id: String, count: u32) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.paginate_forwards(room_id, count as u16).await
    }

    #[wasm_bindgen(js_name = acceptInvite)]
    pub async fn accept_invite(&self, room_id: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return false;
        };
        s.client().join_room_by_id(&rid).await.is_ok()
    }

    #[wasm_bindgen(js_name = dmPeerUserId)]
    pub async fn dm_peer_user_id(&self, room_id: String) -> Option<String> {
        self.state()?.core.dm_peer_user_id(room_id).await
    }

    #[wasm_bindgen(js_name = ensureDm)]
    pub async fn ensure_dm(&self, user_id: String) -> Option<String> {
        self.state()?.core.ensure_dm(user_id).await.ok()
    }

    #[wasm_bindgen(js_name = resolveRoomId)]
    pub async fn resolve_room_id(&self, id_or_alias: String) -> Option<String> {
        self.state()?.core.resolve_room_id(id_or_alias).await
    }

    #[wasm_bindgen(js_name = joinByIdOrAlias)]
    pub async fn join_by_id_or_alias(&self, id_or_alias: String) -> Result<(), String> {
        let Some(s) = self.state() else {
            return Err("not initialized".into());
        };
        s.core
            .join_by_id_or_alias(id_or_alias)
            .await
            .map_err(|e| e.to_string())
    }

    #[wasm_bindgen(js_name = roomProfile)]
    pub async fn room_profile(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_profile(room_id).await {
            Ok(Some(p)) => to_json(&p),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = getUserPowerLevel)]
    pub async fn get_user_power_level(&self, room_id: String, user_id: String) -> f64 {
        let Some(s) = self.state() else {
            return -1.0;
        };
        s.core.get_user_power_level(room_id, user_id).await as f64
    }

    #[wasm_bindgen(js_name = setPresence)]
    pub async fn set_presence(&self, presence: String, status: Option<String>) -> bool {
        let p = match presence.as_str() {
            "Online" => Presence::Online,
            "Offline" => Presence::Offline,
            "Unavailable" => Presence::Unavailable,
            _ => return false,
        };
        let Some(s) = self.state() else {
            return false;
        };
        s.core.set_presence(p, status).await.is_ok()
    }

    #[wasm_bindgen(js_name = isEventReadBy)]
    pub async fn is_event_read_by(
        &self,
        room_id: String,
        event_id: String,
        user_id: String,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.is_event_read_by(room_id, event_id, user_id).await
    }

    #[wasm_bindgen(js_name = startLiveLocation)]
    pub async fn start_live_location(&self, room_id: String, duration_ms: f64) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .start_live_location(room_id, duration_ms as u64, None)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = stopLiveLocation)]
    pub async fn stop_live_location(&self, room_id: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.stop_live_location(room_id).await.is_ok()
    }

    #[wasm_bindgen(js_name = sendLiveLocation)]
    pub async fn send_live_location(&self, room_id: String, geo_uri: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.send_live_location(room_id, geo_uri).await.is_ok()
    }

    #[wasm_bindgen(js_name = enterForeground)]
    pub fn enter_foreground(&self) {
        let Some(state) = self.state() else {
            return;
        };
        state.app_in_foreground.set(true);
        wasm_bindgen_futures::spawn_local(async move {
            let _ = state.client().event_cache().subscribe();
            if let Some(svc) = state.ensure_sync_service().await {
                let _ = svc.start().await;
            }
        });
    }

    #[wasm_bindgen(js_name = enterBackground)]
    pub fn enter_background(&self) {
        let Some(state) = self.state() else {
            return;
        };
        state.app_in_foreground.set(false);
        wasm_bindgen_futures::spawn_local(async move {
            if let Some(svc) = state.ensure_sync_service().await {
                let _ = svc.stop().await;
            }
        });
    }

    #[wasm_bindgen(js_name = startSupervisedSync)]
    pub fn start_supervised_sync(&self, on_state: Function) {
        let Some(state) = self.state() else {
            return;
        };
        wasm_bindgen_futures::spawn_local(async move {
            let Some(svc) = state.ensure_sync_service().await else {
                return;
            };
            call_js(
                &on_state,
                to_json(&SyncStatus {
                    phase: SyncPhase::Idle,
                    message: None,
                }),
            );
            let mut stream = svc.state();
            let _ = svc.start().await;
            while let Some(sync_state) = stream.next().await {
                let mapped = match sync_state {
                    State::Idle => SyncStatus {
                        phase: SyncPhase::Idle,
                        message: None,
                    },
                    State::Running => SyncStatus {
                        phase: SyncPhase::Running,
                        message: None,
                    },
                    State::Offline => SyncStatus {
                        phase: SyncPhase::BackingOff,
                        message: Some("Offline".into()),
                    },
                    State::Terminated => SyncStatus {
                        phase: SyncPhase::Idle,
                        message: Some("Stopped".into()),
                    },
                    State::Error(e) => SyncStatus {
                        phase: SyncPhase::Error,
                        message: Some(format!("{e:?}")),
                    },
                };
                call_js(&on_state, to_json(&mapped));
            }
        });
    }

    #[wasm_bindgen(js_name = observeTimeline)]
    pub fn observe_timeline(&self, room_id: String, on_diff: Function, on_error: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0.0;
        };
        let obs: Arc<dyn TimelineObserver> = Arc::new(JsTimelineObserver(on_diff, on_error));
        let me = state
            .client()
            .user_id()
            .map(|u| u.to_string())
            .unwrap_or_default();
        let mgr = state.tm().clone();
        let s = state.clone();
        wasm_subscribe!(state, timeline_subs, async move {
            let Some(tl) = mgr.timeline_for(&rid).await else {
                return;
            };
            let (_items, mut stream) = tl.subscribe().await;
            emit_timeline_reset_filled(&obs, &tl, &rid, &me).await;
            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    match diff {
                        VectorDiff::Remove { .. }
                        | VectorDiff::PopBack
                        | VectorDiff::PopFront
                        | VectorDiff::Truncate { .. }
                        | VectorDiff::Clear => {
                            emit_timeline_reset_filled(&obs, &tl, &rid, &me).await;
                        }
                        other => {
                            if let Some(mapped) = map_vec_diff(other, &rid, &tl, &me) {
                                let o = obs.clone();
                                let _ = catch_unwind(AssertUnwindSafe(move || o.on_diff(mapped)));
                            }
                        }
                    }
                }
            }
        })
    }

    #[wasm_bindgen(js_name = observeRoomList)]
    pub fn observe_room_list(&self, on_reset: Function, _on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let id = state.next_sub_id();
        let (abort_handle, abort_reg) = AbortHandle::new_pair();
        state.room_list_subs.borrow_mut().insert(id, abort_handle);
        let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<RoomListCmd>();
        state.room_list_cmds.borrow_mut().insert(id, cmd_tx);
        let s = state.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let state_cleanup = s.clone();
            let _ = Abortable::new(async move {
                let Some(svc) = s.ensure_sync_service().await else { return; };
                let rls = svc.room_list_service();
                let Ok(all) = rls.all_rooms().await else { return; };
                let (stream, controller) = all.entries_with_dynamic_adapters(50);
                controller.set_filter(Box::new(filters::new_filter_non_left()));
                use matrix_sdk_ui::room_list_service::RoomListItem;
                tokio::pin!(stream);
                let mut items = Vector::<RoomListItem>::new();
                loop {
                    tokio::select! {
                        Some(cmd) = cmd_rx.recv() => {
                            match cmd {
                                RoomListCmd::SetUnreadOnly(u) => {
                                    if u { controller.set_filter(Box::new(filters::new_filter_all(vec![Box::new(filters::new_filter_non_left()), Box::new(filters::new_filter_unread())]))); }
                                    else { controller.set_filter(Box::new(filters::new_filter_non_left())); }
                                }
                            }
                        }
                        Some(diffs) = stream.next() => {
                            let mut changed = false;
                            for diff in diffs {
                                match diff {
                                    VectorDiff::Reset { values } => { items = values; changed = true; }
                                    VectorDiff::Clear => { items.clear(); changed = true; }
                                    VectorDiff::PushFront { value } => { items.insert(0, value); changed = true; }
                                    VectorDiff::PushBack { value } => { items.push_back(value); changed = true; }
                                    VectorDiff::PopFront => { if !items.is_empty() { items.remove(0); changed = true; } }
                                    VectorDiff::PopBack => { items.pop_back(); changed = true; }
                                    VectorDiff::Insert { index, value } => { if index <= items.len() { items.insert(index, value); changed = true; } }
                                    VectorDiff::Set { index, value } => { if index < items.len() { items[index] = value; changed = true; } }
                                    VectorDiff::Remove { index } => { if index < items.len() { items.remove(index); changed = true; } }
                                    VectorDiff::Truncate { length } => { items.truncate(length); changed = true; }
                                    VectorDiff::Append { values } => { items.append(values); changed = true; }
                                }
                            }
                            if changed {
                                let mut snapshot = Vec::with_capacity(items.len());
                                for item in items.iter() {
                                    let room = &**item;
                                    let is_dm = room.is_direct().await.unwrap_or(false);
                                    let mut avatar_url = room.avatar_url().map(|m| m.to_string());
                                    if avatar_url.is_none() && is_dm { avatar_url = CoreClient::dm_peer_avatar_url(room, s.client().user_id()).await; }
                                    let latest_event = latest_room_event_for(s.tm(), room).await;
                                    snapshot.push(RoomListEntry {
                                        room_id: room.room_id().to_string(),
                                        name: item.cached_display_name().clone().unwrap_or(RoomDisplayName::Named(room.room_id().to_string())).to_string(),
                                        last_ts: room.recency_stamp().map_or(0, Into::into),
                                        notifications: room.num_unread_notifications(), messages: room.num_unread_messages(), mentions: room.num_unread_mentions(),
                                        marked_unread: room.is_marked_unread(), is_favourite: room.is_favourite(), is_low_priority: room.is_low_priority(),
                                        is_invited: matches!(room.state(), RoomState::Invited), avatar_url, is_dm,
                                        is_encrypted: matches!(room.encryption_state(), matrix_sdk::EncryptionState::Encrypted),
                                        member_count: room.joined_members_count().min(u32::MAX as u64) as u32,
                                        topic: room.topic(), latest_event,
                                    });
                                }
                                s.room_list_cache.replace(snapshot.clone());
                                call_js(&on_reset, to_json(&snapshot));
                            }
                        }
                        else => break,
                    }
                }
            }, abort_reg).await;
            state_cleanup.room_list_cmds.borrow_mut().remove(&id);
            state_cleanup.room_list_subs.borrow_mut().remove(&id);
        });
        id as f64
    }

    #[wasm_bindgen(js_name = observeTyping)]
    pub fn observe_typing(&self, room_id: String, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0.0;
        };
        let obs: Arc<dyn TypingObserver> = Arc::new(JsTypingObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, typing_subs, async move {
            let Some(room) = s.client().get_room(&rid) else {
                return;
            };
            let (_guard, mut rx) = room.subscribe_to_typing_notifications();
            let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
            let mut last: Vec<String> = Vec::new();
            while let Ok(uids) = rx.recv().await {
                let mut names: Vec<String> = uids
                    .iter()
                    .map(|uid| {
                        cache
                            .get(uid)
                            .cloned()
                            .unwrap_or_else(|| uid.localpart().to_string())
                    })
                    .collect();
                names.sort();
                names.dedup();
                if names != last {
                    last = names.clone();
                    let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(names)));
                }
            }
        })
    }

    #[wasm_bindgen(js_name = observeReceipts)]
    pub fn observe_receipts(&self, room_id: String, on_changed: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0.0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::new(JsReceiptsObserver(on_changed));
        let s = state.clone();
        wasm_subscribe!(state, receipts_subs, async move {
            let Some(room) = s.client().get_room(&rid) else {
                return;
            };
            let Ok(tl) = room.timeline().await else {
                return;
            };
            let mut stream = tl.subscribe_own_user_read_receipts_changed().await;
            while let Some(()) = stream.next().await {
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
            }
        })
    }

    #[wasm_bindgen(js_name = observeSends)]
    pub fn observe_sends(&self, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let id = state.next_sub_id();
        state.send_observers.borrow_mut().insert(id, on_update);
        state.ensure_send_queue_supervision();
        id as f64
    }

    #[wasm_bindgen(js_name = unobserveSends)]
    pub fn unobserve_sends(&self, id: f64) -> bool {
        self.state()
            .map(|s| s.send_observers.borrow_mut().remove(&(id as u64)).is_some())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = observeLiveLocation)]
    pub fn observe_live_location(&self, room_id: String, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0.0;
        };
        let obs: Arc<dyn LiveLocationObserver> = Arc::new(JsLiveLocationObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, live_location_subs, async move {
            let Some(room) = s.client().get_room(&rid) else {
                return;
            };
            let observable = room.observe_live_location_shares();
            let stream = observable.subscribe();
            use futures_util::pin_mut;
            pin_mut!(stream);
            let mut latest: HashMap<String, LiveLocationShareInfo> = HashMap::new();
            while let Some(event) = stream.next().await {
                let info = LiveLocationShareInfo {
                    user_id: event.user_id.to_string(),
                    geo_uri: event.last_location.location.uri.to_string(),
                    ts_ms: event.last_location.ts.0.into(),
                    is_live: event
                        .beacon_info
                        .as_ref()
                        .map(|i| i.is_live())
                        .unwrap_or(true),
                };
                latest.insert(info.user_id.clone(), info);
                let _ = catch_unwind(AssertUnwindSafe(|| {
                    obs.on_update(latest.values().cloned().collect())
                }));
            }
        })
    }

    #[wasm_bindgen(js_name = startCallInbox)]
    pub fn start_call_inbox(&self, on_invite: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let obs: Arc<dyn CallObserver> = Arc::new(JsCallObserver(on_invite));
        let s = state.clone();
        wasm_subscribe!(state, call_subs, async move {
            use matrix_sdk::ruma::events::call::invite::OriginalSyncCallInviteEvent;
            let handler = s
                .client()
                .observe_events::<OriginalSyncCallInviteEvent, Room>();
            let mut sub = handler.subscribe();
            while let Some((ev, room)) = sub.next().await {
                let invite = CallInvite {
                    room_id: room.room_id().to_string(),
                    sender: ev.sender.to_string(),
                    call_id: ev.content.call_id.to_string(),
                    is_video: ev.content.offer.sdp.contains("m=video"),
                    ts_ms: ev.origin_server_ts.0.into(),
                };
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_invite(invite)));
            }
        })
    }

    #[wasm_bindgen(js_name = startVerificationInbox)]
    pub fn start_verification_inbox(&self, on_request: Function, on_error: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let obs: Arc<dyn VerificationInboxObserver> =
            Arc::new(JsVerificationInboxObserver(on_request, on_error));
        let s = state.clone();
        wasm_subscribe!(state, inbox_subs, async move {
            s.client()
                .encryption()
                .wait_for_e2ee_initialization_tasks()
                .await;
            let _ = s.client().event_cache().subscribe();
            let td = s
                .client()
                .observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
            let mut td_sub = td.subscribe();
            let ir = s.client().observe_events::<SyncRoomMessageEvent, Room>();
            let mut ir_sub = ir.subscribe();
            loop {
                tokio::select! {
                    maybe = td_sub.next() => {
                        if let Some((ev, ())) = maybe {
                            let fid = ev.content.transaction_id.to_string();
                            s.core.inbox.lock().unwrap().insert(fid.clone(), (ev.sender.clone(), ev.content.from_device.clone()));
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_request(fid, ev.sender.to_string(), ev.content.from_device.to_string())));
                        } else { break; }
                    }
                    maybe = ir_sub.next() => {
                        if let Some((ev, _)) = maybe {
                            if let SyncRoomMessageEvent::Original(o) = ev {
                                if let MessageType::VerificationRequest(_) = &o.content.msgtype {
                                    let fid = o.event_id.to_string();
                                    s.core.inbox.lock().unwrap().insert(fid.clone(), (o.sender.clone(), owned_device_id!("inroom")));
                                    let _ = catch_unwind(AssertUnwindSafe(|| obs.on_request(fid, o.sender.to_string(), String::new())));
                                }
                            }
                        } else { break; }
                    }
                }
            }
        })
    }

    #[wasm_bindgen(js_name = observeRecoveryState)]
    pub fn observe_recovery_state(&self, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let obs: Arc<dyn RecoveryStateObserver> = Arc::new(JsRecoveryStateObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, recovery_state_subs, async move {
            let mut stream = s.client().encryption().recovery().state_stream();
            while let Some(v) = stream.next().await {
                let mapped = match v {
                    matrix_sdk::encryption::recovery::RecoveryState::Disabled => {
                        RecoveryState::Disabled
                    }
                    matrix_sdk::encryption::recovery::RecoveryState::Enabled => {
                        RecoveryState::Enabled
                    }
                    matrix_sdk::encryption::recovery::RecoveryState::Incomplete => {
                        RecoveryState::Incomplete
                    }
                    _ => RecoveryState::Unknown,
                };
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(mapped)));
            }
        })
    }

    #[wasm_bindgen(js_name = observeBackupState)]
    pub fn observe_backup_state(&self, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let obs: Arc<dyn BackupStateObserver> = Arc::new(JsBackupStateObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, backup_state_subs, async move {
            let mut stream = s.client().encryption().backups().state_stream();
            while let Some(v) = stream.next().await {
                let mapped = match v {
                    Ok(matrix_sdk::encryption::backups::BackupState::Enabled) => {
                        BackupState::Enabled
                    }
                    Ok(matrix_sdk::encryption::backups::BackupState::Creating) => {
                        BackupState::Creating
                    }
                    Ok(matrix_sdk::encryption::backups::BackupState::Downloading) => {
                        BackupState::Downloading
                    }
                    _ => BackupState::Unknown,
                };
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(mapped)));
            }
        })
    }

    #[wasm_bindgen(js_name = confirmVerification)]
    pub async fn confirm_verification(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let sas = state
            .core
            .verifs
            .lock()
            .unwrap()
            .get(&flow_id)
            .map(|f| f.sas.clone());
        match sas {
            Some(s) => s.confirm().await.is_ok(),
            None => false,
        }
    }

    #[wasm_bindgen(js_name = cancelVerification)]
    pub async fn cancel_verification(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let sas = state
            .core
            .verifs
            .lock()
            .unwrap()
            .get(&flow_id)
            .map(|f| f.sas.clone());
        if let Some(s) = sas {
            return s.cancel().await.is_ok();
        }
        let user = state
            .core
            .inbox
            .lock()
            .unwrap()
            .get(&flow_id)
            .map(|p| p.0.clone())
            .or_else(|| state.client().user_id().map(|u| u.to_owned()));
        let Some(user) = user else {
            return false;
        };
        if let Some(v) = state
            .client()
            .encryption()
            .get_verification(&user, &flow_id)
            .await
        {
            if let Some(sas) = v.sas() {
                return sas.cancel().await.is_ok();
            }
        }
        false
    }

    #[wasm_bindgen(js_name = listMyDevices)]
    pub async fn list_my_devices(&self) -> JsValue {
        let Some(state) = self.state() else {
            return to_json(&Vec::<DeviceSummary>::new());
        };
        let Some(me) = state.client().user_id() else {
            return to_json(&Vec::<DeviceSummary>::new());
        };
        let Ok(devs) = state.client().encryption().get_user_devices(me).await else {
            return to_json(&Vec::<DeviceSummary>::new());
        };
        let items: Vec<DeviceSummary> = devs
            .devices()
            .map(|d| DeviceSummary {
                device_id: d.device_id().to_string(),
                display_name: d.display_name().unwrap_or_default().to_string(),
                ed25519: d.ed25519_key().map(|k| k.to_base64()).unwrap_or_default(),
                is_own: state
                    .client()
                    .device_id()
                    .map(|my| my == d.device_id())
                    .unwrap_or(false),
                verified: d.is_verified(),
            })
            .collect();
        to_json(&items)
    }

    #[wasm_bindgen(js_name = recoverWithKey)]
    pub async fn recover_with_key(&self, recovery_key: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state
            .client()
            .encryption()
            .recovery()
            .recover(&recovery_key)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = backupExistsOnServer)]
    pub async fn backup_exists_on_server(&self, fetch: bool) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let b = state.client().encryption().backups();
        if fetch {
            b.fetch_exists_on_server().await.unwrap_or(false)
        } else {
            b.exists_on_server().await.unwrap_or(false)
        }
    }

    #[wasm_bindgen(js_name = fetchNotification)]
    pub async fn fetch_notification(&self, room_id: String, event_id: String) -> JsValue {
        let Some(state) = self.state() else {
            return JsValue::NULL;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return JsValue::NULL;
        };
        let Ok(eid) = OwnedEventId::try_from(event_id) else {
            return JsValue::NULL;
        };
        let _ = state.ensure_sync_service().await;
        let setup = match state.sync_service.borrow().as_ref().cloned() {
            Some(svc) => NotificationProcessSetup::SingleProcess { sync_service: svc },
            None => NotificationProcessSetup::MultipleProcesses,
        };
        let Ok(nc) = NotificationClient::new(state.client().clone(), setup).await else {
            return JsValue::NULL;
        };
        match nc.get_notification(&rid, &eid).await {
            Ok(NotificationStatus::Event(item)) => {
                match crate::map_notification_item_to_rendered(&rid, &eid, &item) {
                    Some(v) => to_json(&v),
                    None => JsValue::NULL,
                }
            }
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = loadRoomListCache)]
    pub fn load_room_list_cache(&self) -> JsValue {
        self.state()
            .map(|s| to_json(&*s.room_list_cache.borrow()))
            .unwrap_or(to_json(&Vec::<RoomListEntry>::new()))
    }
}
