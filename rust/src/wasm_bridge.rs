//! wasm_bridge.rs — wasm-bindgen wrapper around the mages_ffi Client.
//!
//! Exposes a high-level `WasmClient` class to JavaScript with wasm-bindgen
//! values so Kotlin/Wasm can call into the Matrix SDK via
//! `@JsModule("./mages_ffi_wasm.js")` declarations.
use crate::CallSessionInfo;
use crate::Direction;
use crate::HomeserverLoginDetails;
use crate::OriginalSyncCallInviteEvent;
use crate::RsMode;
use crate::VerifMap;
use crate::owned_device_id;
use crate::{
    AttachmentInfo, BackupState, BackupStateObserver, CallInvite, CallObserver, CallWidgetObserver,
    Client, ConnectionObserver, ConnectionState, ElementCallIntent, FfiRoomNotificationMode,
    LiveLocationObserver, MessageEvent, Presence, ReceiptsObserver, RecoveryObserver,
    RecoveryState, RecoveryStateObserver, RoomDirectoryVisibility, RoomHistoryVisibility,
    RoomJoinRule, RoomListCmd, RoomListEntry, RoomListObserver, RoomPowerLevelChanges, RoomSummary,
    SasEmojis, SasPhase, SendObserver, SendState, SendUpdate, SpaceChildInfo, SpaceHierarchyPage,
    SpaceInfo, SyncObserver, SyncPhase, SyncStatus, TimelineDiffKind, TimelineManager,
    TimelineObserver, TypingObserver, Url, UrlOrQuery, VerificationInboxObserver,
    VerificationObserver, emit_timeline_reset_filled, latest_room_event_for, map_vec_diff,
    missing_reply_event_id, timeline_event_filter,
};
use crate::{RenderedNotification, RoomPreview, RoomPreviewMembership};
use futures_util::StreamExt;
use futures_util::future::{AbortHandle, Abortable};
use js_int::UInt;
use js_sys::Function;
use matrix_sdk::RoomMemberships;
use matrix_sdk::attachment::AttachmentConfig;
use matrix_sdk::authentication::oauth::registration::language_tags::LanguageTag;
use matrix_sdk::encryption::verification::{Verification, VerificationRequest};
use matrix_sdk::room::Receipts;
use matrix_sdk::ruma::events::reaction::ReactionEventContent;
use matrix_sdk::ruma::{
    OwnedDeviceId, OwnedUserId,
    events::{
        key::verification::request::ToDeviceKeyVerificationRequestEvent,
        room::message::{MessageType, SyncRoomMessageEvent},
    },
};
use matrix_sdk::ruma::{
    OwnedRoomOrAliasId,
    api::client::presence::{
        get_presence::v3 as get_presence_v3, set_presence::v3 as set_presence_v3,
    },
    events::receipt::SyncReceiptEvent,
    presence::PresenceState,
    room::JoinRuleSummary,
};
use matrix_sdk::ruma::{
    SpaceChildOrder,
    api::client::relations::get_relating_events_with_rel_type_and_event_type as get_relating,
    events::{
        TimelineEventType,
        beacon::BeaconEventContent,
        beacon_info::BeaconInfoEventContent,
        relation::{Annotation, RelationType, Thread as ThreadRel},
        room::message::{Relation as MsgRelation, RoomMessageEventContent},
    },
    room::RoomType,
};
use matrix_sdk::ruma::{
    api::client::receipt::create_receipt::v3::ReceiptType,
    events::{
        receipt::ReceiptThread,
        room::message::{
            FileInfo, FileMessageEventContent, ImageMessageEventContent, VideoInfo,
            VideoMessageEventContent,
        },
        room::{EncryptedFile, ImageInfo, MediaSource},
    },
};
use matrix_sdk::sleep::sleep;
use matrix_sdk::widget::{
    Capabilities, CapabilitiesProvider, ClientProperties, Intent as WidgetIntent, WidgetDriver,
    WidgetDriverHandle, WidgetSettings,
};
use matrix_sdk::widget::{VirtualElementCallWidgetConfig, VirtualElementCallWidgetProperties};
use matrix_sdk::{
    Client as SdkClient, Room, RoomDisplayName, RoomState, SessionMeta, SessionTokens,
    authentication::{
        AuthSession,
        matrix::MatrixSession,
        oauth::{ClientId, OAuthSession, UserSession},
    },
    encryption::{BackupDownloadStrategy, EncryptionSettings},
    ruma::{OwnedRoomId, UserId},
};
use matrix_sdk_ui::notification_client::{
    NotificationClient, NotificationProcessSetup, NotificationStatus,
};
use matrix_sdk_ui::timeline::RoomExt;
use matrix_sdk_ui::{
    eyeball_im::{Vector, VectorDiff},
    room_list_service::{self, filters},
    sync_service::{self, State, SyncService},
};
use serde_json;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::rc::Rc;
use std::sync::Arc;
use uuid::Uuid;
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use web_time::{Duration, Instant};

/// Helper: convert any serde value into a native JS value.
fn to_json<T: serde::Serialize>(v: &T) -> JsValue {
    match serde_wasm_bindgen::to_value(v) {
        Ok(value) => value,
        Err(e) => JsValue::from_str(&format!("{{\"error\":\"{}\"}}", e)),
    }
}

#[wasm_bindgen]
pub fn base64_encode(data: &[u8]) -> Result<String, JsValue> {
    let window = web_sys::window().ok_or_else(|| JsValue::from_str("no window"))?;

    let binary_string: String = data.iter().map(|&b| b as char).collect();

    window.btoa(&binary_string)
}

/// Helper: call a JS function with one argument.
fn call_js(f: &Function, arg: JsValue) {
    let _ = f.call1(&JsValue::NULL, &arg);
}

/// Helper: call a JS function with no arguments.
fn call_js0(f: &Function) {
    let _ = f.call0(&JsValue::NULL);
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

macro_rules! wasm_delegate_bool {
    ($($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> bool {
                    self.with_client(|c| c.$name($($arg),*))
                        .unwrap_or(false)
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_json {
    ($($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> JsValue {
                    let value = self.with_client(|c| c.$name($($arg),*)).unwrap_or_default();
                    to_json(&value)
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_bool_result {
    ($($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> bool {
                    self.with_client(|c| c.$name($($arg),*).unwrap_or(false))
                        .unwrap_or(false)
                }
            )+
        }
    };
}

macro_rules! wasm_delegate_json_result_default {
    ($($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> JsValue {
                    let value = self
                        .with_client(|c| c.$name($($arg),*).unwrap_or_default())
                        .unwrap_or_default();
                    to_json(&value)
                }
            )+
        }
    };
}

#[cfg(target_family = "wasm")]
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct WasmSessionInfo {
    #[serde(default = "default_wasm_auth_api")]
    auth_api: String,
    #[serde(default)]
    client_id: Option<String>,
    user_id: String,
    device_id: String,
    access_token: String,
    refresh_token: Option<String>,
    homeserver: String,
}

#[cfg(target_family = "wasm")]
fn default_wasm_auth_api() -> String {
    "matrix".to_owned()
}

#[cfg(target_family = "wasm")]
fn wasm_session_key(store_name: &str) -> String {
    format!("mages_session_{store_name}")
}

#[cfg(target_family = "wasm")]
fn load_wasm_session(store_name: &str) -> Option<WasmSessionInfo> {
    let window = web_sys::window()?;
    let storage = window.local_storage().ok()??;
    let raw = storage.get_item(&wasm_session_key(store_name)).ok()??;
    serde_json::from_str(&raw).ok()
}

#[cfg(target_family = "wasm")]
fn save_wasm_session(store_name: &str, session: &WasmSessionInfo) {
    let Some(window) = web_sys::window() else {
        return;
    };
    let Ok(Some(storage)) = window.local_storage() else {
        return;
    };
    let Ok(raw) = serde_json::to_string(session) else {
        return;
    };
    let _ = storage.set_item(&wasm_session_key(store_name), &raw);
}

#[cfg(target_family = "wasm")]
fn clear_wasm_session(store_name: &str) {
    let Some(window) = web_sys::window() else {
        return;
    };
    let Ok(Some(storage)) = window.local_storage() else {
        return;
    };
    let _ = storage.remove_item(&wasm_session_key(store_name));
}

async fn wasm_login(
    state: Rc<WasmAsyncState>,
    username: String,
    password: String,
    device_display_name: Option<String>,
) -> Result<(), String> {
    let mut req = state
        .client
        .matrix_auth()
        .login_username(username.as_str(), &password);
    if let Some(name) = device_display_name.as_ref() {
        req = req.initial_device_display_name(name);
    }

    req.send().await.map_err(|e| e.to_string())?;
    state.persist_session();

    state
        .client
        .encryption()
        .wait_for_e2ee_initialization_tasks()
        .await;

    let _ = state.ensure_sync_service().await;
    let _ = state.client.event_cache().subscribe();
    state.ensure_send_queue_supervision();

    Ok(())
}

js_observer_json!(JsConnectionObserver: ConnectionObserver::on_connection_change, state: ConnectionState);
js_observer_json!(JsSyncObserver: SyncObserver::on_state, status: SyncStatus);

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

js_observer_json!(JsSendObserver: SendObserver::on_update, update: SendUpdate);
js_observer_noargs!(JsReceiptsObserver: ReceiptsObserver::on_changed);
js_observer_json!(JsTypingObserver: TypingObserver::on_update, names: Vec<String>);

struct JsVerificationInboxObserver(Function, Function);
impl VerificationInboxObserver for JsVerificationInboxObserver {
    fn on_request(&self, flow_id: String, from_user: String, from_device: String) {
        let payload = serde_json::json!({
            "flowId": flow_id,
            "fromUser": from_user,
            "fromDevice": from_device
        });
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
        let payload = serde_json::json!({"flowId": flow_id, "phase": format!("{:?}", phase)});
        call_js(&self.0, JsValue::from_str(&payload.to_string()));
    }
    fn on_emojis(&self, payload: SasEmojis) {
        call_js(&self.1, to_json(&payload));
    }
    fn on_error(&self, flow_id: String, message: String) {
        let payload = serde_json::json!({"flowId": flow_id, "message": message});
        call_js(&self.2, JsValue::from_str(&payload.to_string()));
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

js_observer_json!(JsCallObserver: CallObserver::on_invite, invite: CallInvite);
js_observer_json!(JsLiveLocationObserver: LiveLocationObserver::on_update, shares: Vec<crate::LiveLocationShareInfo>);
js_observer_json!(JsCallWidgetObserver: CallWidgetObserver::on_to_widget, message: String);
js_observer_json!(JsRecoveryStateObserver: RecoveryStateObserver::on_update, state: RecoveryState);
js_observer_json!(JsBackupStateObserver: BackupStateObserver::on_update, state: BackupState);

struct WasmAsyncState {
    client: SdkClient,
    store_name: String,
    timeline_mgr: TimelineManager,
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
    inbox: RefCell<HashMap<String, (OwnedUserId, OwnedDeviceId)>>,
    verifs: VerifMap,
    app_in_foreground: Cell<bool>,
    call_subs: RefCell<HashMap<u64, AbortHandle>>,
    live_location_subs: RefCell<HashMap<u64, AbortHandle>>,
    live_location_beacons: RefCell<HashMap<String, crate::LiveLocationBeaconState>>,
    widget_handles: RefCell<HashMap<u64, WidgetDriverHandle>>,
    widget_driver_subs: RefCell<HashMap<u64, AbortHandle>>,
    widget_recv_subs: RefCell<HashMap<u64, AbortHandle>>,
}

impl WasmAsyncState {
    fn next_sub_id(&self) -> u64 {
        let next = self.send_obs_counter.get().wrapping_add(1);
        self.send_obs_counter.set(next);
        next
    }

    async fn ensure_sync_service(&self) -> Option<Arc<SyncService>> {
        if let Some(svc) = self.sync_service.borrow().as_ref().cloned() {
            return Some(svc);
        }

        if self.client.session_meta().is_none() {
            return None;
        }

        let builder = SyncService::builder(self.client.clone()).with_offline_mode();
        let service: Arc<SyncService> = builder.build().await.ok()?.into();
        self.sync_service.borrow_mut().replace(service.clone());
        Some(service)
    }

    async fn joined_rooms(&self) -> Vec<RoomSummary> {
        let mut out = Vec::new();
        for room in self.client.joined_rooms() {
            let name = room
                .display_name()
                .await
                .map(|dn| dn.to_string())
                .unwrap_or_else(|_| room.room_id().to_string());
            out.push(RoomSummary {
                id: room.room_id().to_string(),
                name,
            });
        }
        out
    }

    async fn room_list_entries(&self) -> Vec<RoomListEntry> {
        let mut out = Vec::new();
        for room in self.client.joined_rooms() {
            let name = room
                .display_name()
                .await
                .map(|dn| dn.to_string())
                .unwrap_or_else(|_| room.room_id().to_string());

            out.push(RoomListEntry {
                room_id: room.room_id().to_string(),
                name,
                last_ts: 0,
                notifications: 0,
                messages: 0,
                mentions: 0,
                marked_unread: false,
                is_favourite: false,
                is_low_priority: false,
                is_invited: false,
                avatar_url: None,
                is_dm: false,
                is_encrypted: false,
                member_count: 0,
                topic: None,
                latest_event: None,
            });
        }
        out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        self.room_list_cache.replace(out.clone());
        out
    }

    async fn recent_events(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return vec![];
        };

        let Some(timeline) = self.timeline_mgr.timeline_for(&room_id).await else {
            return vec![];
        };

        let me = self
            .client
            .user_id()
            .map(|u| u.to_string())
            .unwrap_or_default();

        let (items, _stream) = timeline.subscribe().await;
        let mut out: Vec<MessageEvent> = items
            .iter()
            .rev()
            .filter_map(|it| {
                it.as_event().and_then(|ev| {
                    crate::map_timeline_event(
                        ev,
                        room_id.as_str(),
                        Some(&it.unique_id().0.to_string()),
                        &me,
                    )
                })
            })
            .take(limit as usize)
            .collect();
        out.reverse();
        out
    }

    async fn send_message(&self, room_id: String, body: String) -> bool {
        use matrix_sdk::ruma::events::room::message::RoomMessageEventContent as Msg;

        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return false;
        };
        let Some(timeline) = self.timeline_mgr.timeline_for(&room_id).await else {
            return false;
        };

        timeline.send(Msg::text_plain(body).into()).await.is_ok()
    }

    fn persist_session(&self) {
        match self.client.session() {
            Some(AuthSession::Matrix(sess)) => {
                let info = WasmSessionInfo {
                    auth_api: "matrix".to_owned(),
                    client_id: None,
                    user_id: sess.meta.user_id.to_string(),
                    device_id: sess.meta.device_id.to_string(),
                    access_token: sess.tokens.access_token,
                    refresh_token: sess.tokens.refresh_token,
                    homeserver: self.client.homeserver().to_string(),
                };

                save_wasm_session(&self.store_name, &info);
            }

            Some(AuthSession::OAuth(sess)) => {
                let sess = *sess;
                let info = WasmSessionInfo {
                    auth_api: "oauth".to_owned(),
                    client_id: Some(sess.client_id.to_string()),
                    user_id: sess.user.meta.user_id.to_string(),
                    device_id: sess.user.meta.device_id.to_string(),
                    access_token: sess.user.tokens.access_token,
                    refresh_token: sess.user.tokens.refresh_token,
                    homeserver: self.client.homeserver().to_string(),
                };

                save_wasm_session(&self.store_name, &info);
            }

            None => clear_wasm_session(&self.store_name),

            _ => {}
        }
    }

    fn dispatch_send_update(&self, update: &SendUpdate) {
        let callbacks: Vec<Function> = self.send_observers.borrow().values().cloned().collect();
        for callback in callbacks {
            call_js(&callback, to_json(update));
        }
    }

    fn ensure_send_queue_supervision(self: &Rc<Self>) {
        if self.send_queue_supervised.replace(true) {
            return;
        }

        let state_updates = self.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let mut rx = state_updates.client.send_queue().subscribe();
            let mut attempts: HashMap<String, u32> = HashMap::new();

            loop {
                let upd = match rx.recv().await {
                    Ok(u) => u,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                };

                let room_id_str = upd.room_id.to_string();

                use matrix_sdk::send_queue::RoomSendQueueUpdate as U;
                let mapped = match upd.update {
                    U::NewLocalEvent(local) => {
                        let key = format!("{room_id_str}|{}", local.transaction_id);
                        attempts.entry(key).or_insert(0);

                        Some(SendUpdate {
                            room_id: room_id_str,
                            txn_id: local.transaction_id.to_string(),
                            attempts: 0,
                            state: SendState::Enqueued,
                            event_id: None,
                            error: None,
                        })
                    }
                    U::RetryEvent { transaction_id } => {
                        let key = format!("{room_id_str}|{transaction_id}");
                        let n = attempts.entry(key).and_modify(|v| *v += 1).or_insert(1);

                        Some(SendUpdate {
                            room_id: room_id_str,
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
                        let key = format!("{room_id_str}|{transaction_id}");
                        let n = attempts.remove(&key).unwrap_or(0);

                        Some(SendUpdate {
                            room_id: room_id_str,
                            txn_id: transaction_id.to_string(),
                            attempts: n,
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
                        let key = format!("{room_id_str}|{transaction_id}");
                        let n = attempts.entry(key).and_modify(|v| *v += 1).or_insert(1);

                        Some(SendUpdate {
                            room_id: room_id_str,
                            txn_id: transaction_id.to_string(),
                            attempts: *n,
                            state: SendState::Failed,
                            event_id: None,
                            error: Some(format!("{:?} (recoverable={})", error, is_recoverable)),
                        })
                    }
                    U::CancelledLocalEvent { transaction_id } => {
                        let key = format!("{room_id_str}|{transaction_id}");
                        attempts.remove(&key);

                        Some(SendUpdate {
                            room_id: room_id_str,
                            txn_id: transaction_id.to_string(),
                            attempts: 0,
                            state: SendState::Failed,
                            event_id: None,
                            error: Some("Cancelled before sending".into()),
                        })
                    }
                    U::ReplacedLocalEvent { .. } => None,
                    U::MediaUpload { .. } => None,
                };

                if let Some(update) = mapped {
                    state_updates.dispatch_send_update(&update);
                }
            }
        });

        let state_errors = self.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let mut rx = state_errors.client.send_queue().subscribe_errors();

            loop {
                let err = match rx.recv().await {
                    Ok(e) => e,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                };

                let update = SendUpdate {
                    room_id: err.room_id.to_string(),
                    txn_id: String::new(),
                    attempts: 0,
                    state: SendState::Failed,
                    event_id: None,
                    error: Some(format!(
                        "Room send queue disabled (recoverable={}): {:?}",
                        err.is_recoverable, err.error
                    )),
                };
                state_errors.dispatch_send_update(&update);
            }
        });
    }
}

#[wasm_bindgen]
pub struct WasmClient {
    inner: Rc<RefCell<Option<Client>>>,
    async_state: Rc<RefCell<Option<Rc<WasmAsyncState>>>>,
}

#[wasm_bindgen]
impl WasmClient {
    #[wasm_bindgen(constructor)]
    pub fn new(
        homeserver_url: String,
        base_store_dir: String,
        account_id: Option<String>,
    ) -> Result<WasmClient, JsValue> {
        let client = Client::new(homeserver_url, base_store_dir, account_id)
            .map_err(|e| JsValue::from_str(&format!("{:?}", e)))?;
        Ok(WasmClient {
            inner: Rc::new(RefCell::new(Some(client))),
            async_state: Rc::new(RefCell::new(None)),
        })
    }

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
                .map(crate::strip_matrix_path)
                .map(|u| u.to_string())
                .unwrap_or_else(|_| raw.to_owned())
        };

        let store_name = account_id
            .clone()
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
            .map_err(|e| JsValue::from_str(&format!("failed to build client: {e}")))?;

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
                    if let Some(client_id) = info.client_id {
                        let session = OAuthSession {
                            client_id: ClientId::new(client_id),
                            user: UserSession { meta, tokens },
                        };
                        let _ = client.restore_session(session).await;
                    }
                } else {
                    let session = MatrixSession { meta, tokens };
                    let _ = client.restore_session(session).await;
                }
            }
        }

        let state = Rc::new(WasmAsyncState {
            store_name,
            timeline_mgr: TimelineManager::new(client.clone()),
            client,
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
            inbox: RefCell::new(HashMap::new()),
            verifs: Arc::new(std::sync::Mutex::new(HashMap::new())),
            app_in_foreground: Cell::new(false),
            call_subs: RefCell::new(HashMap::new()),
            live_location_subs: RefCell::new(HashMap::new()),
            live_location_beacons: RefCell::new(HashMap::new()),
            widget_handles: RefCell::new(HashMap::new()),
            widget_driver_subs: RefCell::new(HashMap::new()),
            widget_recv_subs: RefCell::new(HashMap::new()),
        });

        Ok(WasmClient {
            inner: Rc::new(RefCell::new(None)),
            async_state: Rc::new(RefCell::new(Some(state))),
        })
    }

    fn with_client<T, F: FnOnce(&Client) -> T>(&self, f: F) -> Result<T, JsValue> {
        let b = self.inner.borrow();
        let c = b
            .as_ref()
            .ok_or_else(|| JsValue::from_str("client closed"))?;
        Ok(f(c))
    }

    fn with_async_state<T, F: FnOnce(&WasmAsyncState) -> T>(&self, f: F) -> Result<T, JsValue> {
        let b = self.async_state.borrow();
        let s = b
            .as_ref()
            .ok_or_else(|| JsValue::from_str("async wasm client not initialized"))?;
        Ok(f(s))
    }

    fn abort_sub(map: &RefCell<HashMap<u64, AbortHandle>>, id: u64) -> bool {
        if let Some(handle) = map.borrow_mut().remove(&id) {
            handle.abort();
            true
        } else {
            false
        }
    }

    fn resolve_other_user_for_flow_web(
        state: &WasmAsyncState,
        flow_id: &str,
        other_user_id: Option<String>,
    ) -> Option<OwnedUserId> {
        if let Some(uid) = other_user_id {
            uid.parse::<OwnedUserId>().ok()
        } else {
            state.inbox.borrow().get(flow_id).map(|p| p.0.clone())
        }
    }

    fn wait_and_start_sas_web(
        state: Rc<WasmAsyncState>,
        flow_id: String,
        req: VerificationRequest,
        obs: Arc<dyn VerificationObserver>,
    ) {
        let verifs = state.verifs.clone();

        wasm_bindgen_futures::spawn_local(async move {
            use matrix_sdk::encryption::verification::{Verification, VerificationRequestState};

            obs.on_phase(flow_id.clone(), SasPhase::Requested);

            let we_started = req.we_started();
            let mut started_sas = false;

            let mut changes = req.changes();
            let mut next_state: Option<VerificationRequestState> = Some(req.state());

            let mut remaining_ticks: u32 = 120 * 4;

            loop {
                if remaining_ticks == 0 {
                    obs.on_error(
                        flow_id.clone(),
                        "Verification timed out waiting for SAS".into(),
                    );
                    break;
                }

                let state_now = match next_state.take() {
                    Some(s) => s,
                    None => match changes.next().await {
                        Some(s) => s,
                        None => {
                            obs.on_error(
                                flow_id.clone(),
                                "Verification request stream ended".into(),
                            );
                            break;
                        }
                    },
                };

                match state_now {
                    VerificationRequestState::Cancelled(info) => {
                        obs.on_phase(flow_id.clone(), SasPhase::Cancelled);
                        obs.on_error(flow_id.clone(), info.reason().to_owned());
                        break;
                    }

                    VerificationRequestState::Done => {
                        obs.on_phase(flow_id.clone(), SasPhase::Done);
                        break;
                    }

                    VerificationRequestState::Created { .. }
                    | VerificationRequestState::Requested { .. } => {
                        remaining_ticks = remaining_ticks.saturating_sub(1);
                        sleep(std::time::Duration::from_millis(250)).await;
                    }

                    VerificationRequestState::Ready { .. } => {
                        if we_started && !started_sas {
                            started_sas = true;

                            match req.start_sas().await {
                                Ok(Some(sas)) => {
                                    crate::attach_sas_stream(
                                        verifs.clone(),
                                        flow_id.clone(),
                                        sas,
                                        obs.clone(),
                                    )
                                    .await;
                                    break;
                                }
                                Ok(None) => {
                                    started_sas = false;
                                    remaining_ticks = remaining_ticks.saturating_sub(1);
                                    sleep(std::time::Duration::from_millis(250)).await;
                                }
                                Err(e) => {
                                    obs.on_error(flow_id.clone(), format!("start_sas failed: {e}"));
                                    started_sas = false;
                                    break;
                                }
                            }
                        } else {
                            remaining_ticks = remaining_ticks.saturating_sub(1);
                            sleep(std::time::Duration::from_millis(250)).await;
                        }
                    }

                    VerificationRequestState::Transitioned { verification, .. } => {
                        match verification {
                            Verification::SasV1(sas) => {
                                crate::attach_sas_stream(
                                    verifs.clone(),
                                    flow_id.clone(),
                                    sas,
                                    obs.clone(),
                                )
                                .await;
                                break;
                            }
                            _ => {
                                obs.on_error(
                                    flow_id.clone(),
                                    "Verification transitioned to a non-SAS method".into(),
                                );
                                break;
                            }
                        }
                    }
                }
            }
        });
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = whoami)]
    pub fn whoami(&self) -> Option<String> {
        if let Ok(value) = self.with_async_state(|s| s.client.user_id().map(|u| u.to_string())) {
            return value;
        }
        self.with_client(|c| c.whoami()).ok().flatten()
    }

    #[wasm_bindgen]
    pub fn account_management_url(&self) -> Option<String> {
        self.with_client(|c| c.account_management_url())
            .ok()
            .flatten()
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = isLoggedIn)]
    pub fn is_logged_in(&self) -> bool {
        if let Ok(value) = self.with_async_state(|s| s.client.session_meta().is_some()) {
            return value;
        }
        self.with_client(|c| c.is_logged_in()).unwrap_or(false)
    }

    #[wasm_bindgen(js_name = loginAsync)]
    pub async fn login_async(
        &self,
        username: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Option<String> {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            return wasm_login(state, username, password, device_display_name)
                .await
                .err();
        }

        self.with_client(|c| c.login(username, password, device_display_name))
            .ok()
            .and_then(|r| r.err().map(|e| format!("{:?}", e)))
    }

    #[wasm_bindgen]
    pub fn logout(&self) -> bool {
        if self.async_state.borrow().is_some() {
            if let Some(state) = self.async_state.borrow().as_ref().cloned() {
                clear_wasm_session(&state.store_name);
            }
            return false;
        }
        self.with_client(|c| c.logout()).unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn shutdown(&self) {
        if let Ok(b) = self.inner.try_borrow() {
            if let Some(c) = b.as_ref() {
                c.shutdown();
            }
        }
    }

    #[wasm_bindgen]
    pub fn recovery_state(&self) -> String {
        "Unknown".to_owned()
    }

    #[wasm_bindgen(js_name = setupRecovery)]
    pub fn setup_recovery(
        &self,
        on_progress: Function,
        on_done: Function,
        on_error: Function,
    ) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn RecoveryObserver> =
                Arc::new(JsRecoveryObserver(on_progress, on_done, on_error));
            let id = state.next_sub_id();
            let client = state.client.clone();

            wasm_bindgen_futures::spawn_local(async move {
                let recovery = client.encryption().recovery();

                let _ = client.refresh_access_token().await;

                let current = recovery.state();
                let is_first_time = matches!(
                    current,
                    matrix_sdk::encryption::recovery::RecoveryState::Disabled
                );

                let _ = catch_unwind(AssertUnwindSafe(|| {
                    obs.on_progress(if is_first_time {
                        "Starting recovery setup...".into()
                    } else {
                        "Regenerating recovery key...".into()
                    });
                }));

                let result = if is_first_time {
                    let enable = recovery.enable();
                    let mut progress = enable.subscribe_to_progress();

                    let obs_clone = obs.clone();
                    let progress_task = wasm_bindgen_futures::spawn_local(async move {
                        while let Some(Ok(p)) = progress.next().await {
                            let step = match p {
                                matrix_sdk::encryption::recovery::EnableProgress::Starting => "Starting...",
                                matrix_sdk::encryption::recovery::EnableProgress::CreatingBackup => "Creating backup...",
                                matrix_sdk::encryption::recovery::EnableProgress::CreatingRecoveryKey => "Generating recovery key...",
                                matrix_sdk::encryption::recovery::EnableProgress::BackingUp(_) => "Uploading keys...",
                                matrix_sdk::encryption::recovery::EnableProgress::Done { .. } => "Done",
                                _ => "In progress...",
                            };
                            let _ = catch_unwind(AssertUnwindSafe(|| {
                                obs_clone.on_progress(step.to_string())
                            }));
                        }
                    });

                    let res = enable.await;
                    let _ = progress_task;
                    res
                } else {
                    recovery.reset_key().await
                };

                match result {
                    Ok(key) => {
                        let _ = catch_unwind(AssertUnwindSafe(|| obs.on_done(key)));
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        let friendly = if msg.contains("Invalid refresh token")
                            || msg.contains("UnknownToken")
                        {
                            "Session expired. Please log out and log in again to continue."
                        } else if msg.contains("backup already exists")
                            || msg.contains("BackupExists")
                        {
                            "Recovery is already set up. Use 'Change recovery key' instead."
                        } else {
                            &msg
                        };
                        let _ =
                            catch_unwind(AssertUnwindSafe(|| obs.on_error(friendly.to_string())));
                    }
                }
            });

            return id as f64;
        }

        let obs = Box::new(JsRecoveryObserver(on_progress, on_done, on_error));
        self.with_client(|c| c.setup_recovery(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen(js_name = recoverWithKey)]
    pub async fn recover_with_key(&self, recovery_key: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return state
                .client
                .encryption()
                .recovery()
                .recover(&recovery_key)
                .await
                .is_ok();
        }

        self.with_client(|c| c.recover_with_key(recovery_key))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeRecoveryState)]
    pub fn observe_recovery_state(&self, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn RecoveryStateObserver> = Arc::new(JsRecoveryStateObserver(on_update));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state
                .recovery_state_subs
                .borrow_mut()
                .insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let mut stream = state.client.encryption().recovery().state_stream();
                        while let Some(value) = stream.next().await {
                            let mapped = match value {
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
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.recovery_state_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsRecoveryStateObserver(on_update));
        self.with_client(|c| c.observe_recovery_state(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveRecoveryState)]
    pub fn unobserve_recovery_state(&self, id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.recovery_state_subs, id as u64);
        }

        self.with_client(|c| c.unobserve_recovery_state(id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeBackupState)]
    pub fn observe_backup_state(&self, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn BackupStateObserver> = Arc::new(JsBackupStateObserver(on_update));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state
                .backup_state_subs
                .borrow_mut()
                .insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let mut stream = state.client.encryption().backups().state_stream();
                        while let Some(value) = stream.next().await {
                            let mapped = match value {
                                Ok(matrix_sdk::encryption::backups::BackupState::Unknown) => {
                                    BackupState::Unknown
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Creating) => {
                                    BackupState::Creating
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Enabling) => {
                                    BackupState::Enabling
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Resuming) => {
                                    BackupState::Resuming
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Enabled) => {
                                    BackupState::Enabled
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Downloading) => {
                                    BackupState::Downloading
                                }
                                Ok(matrix_sdk::encryption::backups::BackupState::Disabling) => {
                                    BackupState::Disabling
                                }
                                Err(_) => BackupState::Unknown,
                            };
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(mapped)));
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.backup_state_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsBackupStateObserver(on_update));
        self.with_client(|c| c.observe_backup_state(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveBackupState)]
    pub fn unobserve_backup_state(&self, id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.backup_state_subs, id as u64);
        }

        self.with_client(|c| c.unobserve_backup_state(id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = rooms)]
    pub async fn rooms(&self) -> JsValue {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            return to_json(&state.joined_rooms().await);
        }
        let v = self.with_client(|c| c.rooms()).unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    pub fn load_room_list_cache(&self) -> JsValue {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            return to_json(&*state.room_list_cache.borrow());
        }
        let v = self
            .with_client(|c| c.load_room_list_cache())
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen(js_name = recentEvents)]
    pub async fn recent_events(&self, room_id: String, limit: u32) -> JsValue {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            return to_json(&state.recent_events(room_id, limit).await);
        }
        let v = self
            .with_client(|c| c.recent_events(room_id, limit))
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = sendQueueSetEnabled)]
    pub fn send_queue_set_enabled(&self, enabled: bool) -> bool {
        self.with_client(|c| c.send_queue_set_enabled(enabled))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn room_send_queue_set_enabled(&self, room_id: String, enabled: bool) -> bool {
        self.with_client(|c| c.room_send_queue_set_enabled(room_id, enabled))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn enqueue_text(&self, room_id: String, body: String, txn_id: Option<String>) -> String {
        self.with_client(|c| c.enqueue_text(room_id, body, txn_id))
            .unwrap_or_default()
    }

    #[wasm_bindgen]
    pub fn retry_by_txn(&self, room_id: String, txn_id: String) -> bool {
        self.with_client(|c| c.retry_by_txn(room_id, txn_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeSends)]
    pub fn observe_sends(&self, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let id = state.next_sub_id();
            state.send_observers.borrow_mut().insert(id, on_update);
            state.ensure_send_queue_supervision();
            return id as f64;
        }
        let obs = Box::new(JsSendObserver(on_update));
        self.with_client(|c| c.observe_sends(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveSends)]
    pub fn unobserve_sends(&self, id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return state
                .send_observers
                .borrow_mut()
                .remove(&(id as u64))
                .is_some();
        }
        self.with_client(|c| c.unobserve_sends(id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = sendMessage)]
    pub async fn send_message(&self, room_id: String, body: String) -> bool {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            return state.send_message(room_id, body).await;
        }
        self.with_client(|c| c.send_message(room_id, body, None))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = reply)]
    pub async fn reply(&self, room_id: String, in_reply_to: String, body: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };
            let Ok(reply_to) = matrix_sdk::ruma::EventId::parse(&in_reply_to) else {
                return false;
            };

            let content = MsgNoRel::text_plain(body);
            return tl.send_reply(content, reply_to.to_owned()).await.is_ok();
        }

        self.with_client(|c| c.reply(room_id, in_reply_to, body, None))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = edit)]
    pub async fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use matrix_sdk::room::edit::EditedContent;
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::EventId::parse(&target_event_id) else {
                return false;
            };
            let Some(item) = tl.item_by_event_id(&eid).await else {
                return false;
            };

            let item_id = item.identifier();
            let edited = EditedContent::RoomMessage(MsgNoRel::text_plain(new_body));

            return tl.edit(&item_id, edited).await.is_ok();
        }

        self.with_client(|c| c.edit(room_id, target_event_id, new_body, None))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = redact)]
    pub async fn redact(&self, room_id: String, event_id: String, reason: Option<String>) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::EventId::parse(&event_id) else {
                return false;
            };

            return room.redact(&eid, reason.as_deref(), None).await.is_ok();
        }

        self.with_client(|c| c.redact(room_id, event_id, reason))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn send_thread_text(
        &self,
        room_id: String,
        root_event_id: String,
        body: String,
        reply_to_event_id: Option<String>,
        latest_event_id: Option<String>,
        formatted_body: Option<String>,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(root) = matrix_sdk::ruma::OwnedEventId::try_from(root_event_id) else {
                return false;
            };
            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };

            let mut content: RoomMessageEventContent = if let Some(formatted) = formatted_body {
                RoomMessageEventContent::text_html(body, formatted)
            } else {
                RoomMessageEventContent::text_plain(body)
            };

            let relation = if let Some(reply_to) = reply_to_event_id {
                if let Ok(eid) = matrix_sdk::ruma::OwnedEventId::try_from(reply_to) {
                    MsgRelation::Thread(ThreadRel::reply(root, eid))
                } else {
                    MsgRelation::Thread(ThreadRel::without_fallback(root))
                }
            } else if let Some(latest) = latest_event_id {
                if let Ok(eid) = matrix_sdk::ruma::OwnedEventId::try_from(latest) {
                    MsgRelation::Thread(ThreadRel::plain(root, eid))
                } else {
                    MsgRelation::Thread(ThreadRel::without_fallback(root))
                }
            } else {
                MsgRelation::Thread(ThreadRel::without_fallback(root))
            };

            content.relates_to = Some(relation);
            return tl.send(content.into()).await.is_ok();
        }

        self.with_client(|c| {
            c.send_thread_text(
                room_id,
                root_event_id,
                body,
                reply_to_event_id,
                latest_event_id,
                formatted_body,
            )
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn send_existing_attachment(
        &self,
        room_id: String,
        attachment_json: String,
        body: Option<String>,
    ) -> bool {
        let att: AttachmentInfo = match serde_json::from_str(&attachment_json) {
            Ok(a) => a,
            Err(_) => return false,
        };

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            let default_caption = match att.kind {
                crate::AttachmentKind::Image => "Image",
                crate::AttachmentKind::Video => "Video",
                crate::AttachmentKind::File => "File",
            };
            let caption = body.unwrap_or_else(|| default_caption.to_string());

            let media_source = if let Some(enc) = att.encrypted.as_ref() {
                let ef: EncryptedFile = match serde_json::from_str(&enc.json) {
                    Ok(f) => f,
                    Err(e) => {
                        eprintln!("send_existing_attachment: enc parse error: {e}");
                        return false;
                    }
                };
                MediaSource::Encrypted(Box::new(ef))
            } else {
                MediaSource::Plain(att.mxc_uri.clone().into())
            };

            let msgtype = match att.kind {
                crate::AttachmentKind::Image => {
                    let mut info = ImageInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);
                    info.width = att.width.map(UInt::from);
                    info.height = att.height.map(UInt::from);

                    let mut img = ImageMessageEventContent::new(caption.clone(), media_source);
                    img.info = Some(Box::new(info));
                    matrix_sdk::ruma::events::room::message::MessageType::Image(img)
                }

                crate::AttachmentKind::Video => {
                    let mut info = VideoInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);
                    info.width = att.width.map(UInt::from);
                    info.height = att.height.map(UInt::from);
                    info.duration = att
                        .duration_ms
                        .map(|ms| std::time::Duration::from_millis(ms));

                    let mut vid = VideoMessageEventContent::new(caption.clone(), media_source);
                    vid.info = Some(Box::new(info));
                    matrix_sdk::ruma::events::room::message::MessageType::Video(vid)
                }

                crate::AttachmentKind::File => {
                    let mut info = FileInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);

                    let mut file = FileMessageEventContent::new(caption.clone(), media_source);
                    file.info = Some(Box::new(info));
                    matrix_sdk::ruma::events::room::message::MessageType::File(file)
                }
            };

            let content = RoomMessageEventContent::new(msgtype);
            return room.send(content).await.is_ok();
        }

        self.with_client(|c| c.send_existing_attachment(room_id, att, body, None))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn send_attachment_bytes(
        &self,
        room_id: String,
        filename: String,
        mime: String,
        data: js_sys::Uint8Array,
    ) -> bool {
        let bytes = data.to_vec();

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };
            let Ok(content_type) = mime.parse::<mime::Mime>() else {
                return false;
            };

            return room
                .send_attachment(filename, &content_type, bytes, AttachmentConfig::new())
                .await
                .is_ok();
        }

        self.with_client(|c| c.send_attachment_bytes(room_id, filename, mime, bytes, None))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn download_attachment_to_cache_file(
        &self,
        info_json: String,
        filename_hint: Option<String>,
    ) -> JsValue {
        let info: AttachmentInfo = match serde_json::from_str(&info_json) {
            Ok(a) => a,
            Err(e) => return JsValue::from_str(&format!("{{\"error\":\"{}\"}}", e)),
        };
        let result = self.with_client(|c| c.download_attachment_to_cache_file(info, filename_hint));
        match result {
            Ok(Ok(r)) => JsValue::from_str(
                &serde_json::json!({"path": r.path, "bytes": r.bytes}).to_string(),
            ),
            Ok(Err(e)) => JsValue::from_str(&format!("{{\"error\":\"{:?}\"}}", e)),
            Err(e) => JsValue::from_str(&format!("{{\"error\":\"{:?}\"}}", e)),
        }
    }

    #[wasm_bindgen]
    pub async fn thumbnail_to_cache(
        &self,
        info_json: String,
        width: u32,
        height: u32,
        crop: bool,
    ) -> String {
        let info: AttachmentInfo = match serde_json::from_str(&info_json) {
            Ok(a) => a,
            Err(_) => return String::new(),
        };

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use js_int::UInt;
            use matrix_sdk::media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings};
            use matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method;
            use matrix_sdk::ruma::events::room::MediaSource;

            let (source, format) = if let Some(enc) = info.thumbnail_encrypted.as_ref() {
                let ef: matrix_sdk::ruma::events::room::EncryptedFile =
                    match serde_json::from_str(&enc.json) {
                        Ok(f) => f,
                        Err(_) => return String::new(),
                    };
                (MediaSource::Encrypted(Box::new(ef)), MediaFormat::File)
            } else if let Some(mxc) = info.thumbnail_mxc_uri.as_ref() {
                (MediaSource::Plain(mxc.clone().into()), MediaFormat::File)
            } else if let Some(enc) = info.encrypted.as_ref() {
                let ef: matrix_sdk::ruma::events::room::EncryptedFile =
                    match serde_json::from_str(&enc.json) {
                        Ok(f) => f,
                        Err(_) => return String::new(),
                    };
                (MediaSource::Encrypted(Box::new(ef)), MediaFormat::File)
            } else {
                let method = if crop { Method::Crop } else { Method::Scale };
                let settings = MediaThumbnailSettings::with_method(
                    method,
                    UInt::from(width),
                    UInt::from(height),
                );
                (
                    MediaSource::Plain(info.mxc_uri.clone().into()),
                    MediaFormat::Thumbnail(settings),
                )
            };

            let req = MediaRequestParameters { source, format };
            let bytes = match state.client.media().get_media_content(&req, true).await {
                Ok(b) => b,
                Err(e) => return format!("error: {:?}", e),
            };

            let ext = if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                "png"
            } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
                "jpg"
            } else if bytes.starts_with(b"GIF8") {
                "gif"
            } else {
                "img"
            };

            let key = format!("{}|{}x{}|{}", info.mxc_uri, width, height, crop)
                .chars()
                .map(|c| c as u8)
                .fold(0u64, |acc, b| acc.wrapping_mul(31).wrapping_add(b as u64));

            let out = format!("mxc_thumb_{key}.{ext}");

            if let Ok(base64) = base64_encode(&bytes) {
                let data_url = format!("data:image/{ext};base64,{base64}");
                return data_url;
            }
            return format!("error: base64 encode failed");
        }

        match self.with_client(|c| c.thumbnail_to_cache(info, width, height, crop)) {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => format!("error: {:?}", e),
            Err(e) => format!("client error: {:?}", e),
        }
    }

    #[wasm_bindgen]
    pub async fn mxc_thumbnail_to_cache(
        &self,
        mxc_uri: String,
        width: u32,
        height: u32,
        crop: bool,
    ) -> String {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use js_int::UInt;
            use matrix_sdk::media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings};
            use matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method;
            use matrix_sdk::ruma::events::room::MediaSource;

            let method = if crop { Method::Crop } else { Method::Scale };
            let settings = MediaThumbnailSettings::with_method(
                method,
                UInt::from(width.max(1)),
                UInt::from(height.max(1)),
            );

            let req = MediaRequestParameters {
                source: MediaSource::Plain(mxc_uri.clone().into()),
                format: MediaFormat::Thumbnail(settings),
            };

            let bytes = match state.client.media().get_media_content(&req, true).await {
                Ok(b) => b,
                Err(e) => return format!("error: {:?}", e),
            };

            let ext = if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                "png"
            } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
                "jpg"
            } else if bytes.starts_with(b"GIF8") {
                "gif"
            } else {
                "img"
            };

            if let Ok(base64) = base64_encode(&bytes) {
                return format!("data:image/{ext};base64,{base64}");
            }
            return format!("error: base64 encode failed");
        }

        match self.with_client(|c| c.mxc_thumbnail_to_cache(mxc_uri, width, height, crop)) {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => format!("error: {:?}", e),
            Err(e) => format!("client error: {:?}", e),
        }
    }

    #[wasm_bindgen]
    pub async fn own_last_read(&self, room_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return to_json(&crate::OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                });
            };

            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return to_json(&crate::OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                });
            };

            let Some(me) = state.client.user_id() else {
                return to_json(&crate::OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                });
            };

            let receipt = if let Some((eid, receipt)) = tl.latest_user_read_receipt(me).await {
                crate::OwnReceipt {
                    event_id: Some(eid.to_string()),
                    ts_ms: receipt.ts.map(|t| t.0.into()),
                }
            } else {
                crate::OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                }
            };

            return to_json(&receipt);
        }

        let r = self
            .with_client(|c| c.own_last_read(room_id))
            .unwrap_or(crate::OwnReceipt {
                event_id: None,
                ts_ms: None,
            });
        to_json(&r)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeTyping)]
    pub fn observe_typing(&self, room_id: String, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return 0.0;
            };
            let obs: Arc<dyn TypingObserver> = Arc::new(JsTypingObserver(on_update));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.typing_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let Some(room) = state.client.get_room(&rid) else {
                            return;
                        };

                        let (_guard, mut rx) = room.subscribe_to_typing_notifications();
                        let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
                        let mut last: Vec<String> = Vec::new();

                        while let Ok(user_ids) = rx.recv().await {
                            let mut names = Vec::with_capacity(user_ids.len());

                            for uid in user_ids {
                                if let Some(n) = cache.get(&uid) {
                                    names.push(n.clone());
                                    continue;
                                }

                                let name = match room.get_member(&uid).await {
                                    Ok(Some(m)) => m
                                        .display_name()
                                        .map(|s| s.to_string())
                                        .unwrap_or_else(|| uid.localpart().to_string()),
                                    _ => uid.localpart().to_string(),
                                };

                                cache.insert(uid.clone(), name.clone());
                                names.push(name);
                            }

                            names.sort();
                            names.dedup();

                            if names != last {
                                last = names.clone();
                                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(names)));
                            }
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.typing_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsTypingObserver(on_update));
        self.with_client(|c| c.observe_typing(room_id, obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    pub fn unobserve_typing(&self, sub_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.typing_subs, sub_id as u64);
        }

        self.with_client(|c| c.unobserve_typing(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeReceipts)]
    pub fn observe_receipts(&self, room_id: String, on_changed: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return 0.0;
            };
            let obs: Arc<dyn ReceiptsObserver> = Arc::new(JsReceiptsObserver(on_changed));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.receipts_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let Some(room) = state.client.get_room(&rid) else {
                            return;
                        };
                        let Ok(tl) = room.timeline().await else {
                            return;
                        };
                        let mut stream = tl.subscribe_own_user_read_receipts_changed().await;

                        while let Some(()) = stream.next().await {
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.receipts_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsReceiptsObserver(on_changed));
        self.with_client(|c| c.observe_receipts(room_id, obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveReceipts)]
    pub fn unobserve_receipts(&self, sub_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.receipts_subs, sub_id as u64);
        }

        self.with_client(|c| c.unobserve_receipts(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn observe_own_receipt(&self, room_id: String, on_changed: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return 0.0;
            };
            let obs: Arc<dyn ReceiptsObserver> = Arc::new(JsReceiptsObserver(on_changed));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.receipts_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let stream = state
                            .client
                            .observe_room_events::<SyncReceiptEvent, Room>(&rid);
                        let mut sub = stream.subscribe();

                        while let Some((_ev, _room)) = sub.next().await {
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.receipts_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsReceiptsObserver(on_changed));
        self.with_client(|c| c.observe_own_receipt(room_id, obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeTimeline)]
    pub fn observe_timeline(&self, room_id: String, on_diff: Function, on_error: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return 0.0;
            };

            let observer: Arc<dyn TimelineObserver> =
                Arc::new(JsTimelineObserver(on_diff, on_error));
            let me = state
                .client
                .user_id()
                .map(|u| u.to_string())
                .unwrap_or_default();
            let mgr = state.timeline_mgr.clone();
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.timeline_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_for_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let Some(timeline) = mgr.timeline_for(&room_id).await else {
                            return;
                        };

                        let (items, mut stream) = timeline.subscribe().await;
                        emit_timeline_reset_filled(&observer, &timeline, &room_id, &me).await;

                        for item in items.iter() {
                            if let Some(event) = item.as_event() {
                                if let Some(event_id) = missing_reply_event_id(event) {
                                    let timeline_clone = timeline.clone();
                                    wasm_bindgen_futures::spawn_local(async move {
                                        let _ = timeline_clone
                                            .fetch_details_for_event(event_id.as_ref())
                                            .await;
                                    });
                                }
                            }
                        }

                        while let Some(diffs) = stream.next().await {
                            for diff in diffs {
                                match diff {
                                    VectorDiff::Remove { .. }
                                    | VectorDiff::PopBack
                                    | VectorDiff::PopFront
                                    | VectorDiff::Truncate { .. }
                                    | VectorDiff::Clear => {
                                        emit_timeline_reset_filled(
                                            &observer, &timeline, &room_id, &me,
                                        )
                                        .await;
                                    }
                                    other => {
                                        if let Some(mapped) =
                                            map_vec_diff(other, &room_id, &timeline, &me)
                                        {
                                            let observer_clone = observer.clone();
                                            let _ = catch_unwind(AssertUnwindSafe(move || {
                                                observer_clone.on_diff(mapped)
                                            }));
                                        }
                                    }
                                }
                            }
                        }
                    },
                    abort_reg,
                )
                .await;

                state_for_cleanup.timeline_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }
        let obs = Box::new(JsTimelineObserver(on_diff, on_error));
        self.with_client(|c| c.observe_timeline(room_id, obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveTimeline)]
    pub fn unobserve_timeline(&self, sub_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            if let Some(handle) = state.timeline_subs.borrow_mut().remove(&(sub_id as u64)) {
                handle.abort();
                return true;
            }
            return false;
        }
        self.with_client(|c| c.unobserve_timeline(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn monitor_connection(&self, on_change: Function) -> f64 {
        if self.async_state.borrow().is_some() {
            let _ = on_change;
            return 0.0;
        }
        let obs = Box::new(JsConnectionObserver(on_change));
        self.with_client(|c| c.monitor_connection(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    pub fn unobserve_connection(&self, sub_id: f64) -> bool {
        if self.async_state.borrow().is_some() {
            let _ = sub_id;
            return false;
        }
        self.with_client(|c| c.unobserve_connection(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = startSupervisedSync)]
    pub fn start_supervised_sync(&self, on_state: Function) {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let on_state_clone = on_state.clone();
            wasm_bindgen_futures::spawn_local(async move {
                if let Some(sync_service) = state.ensure_sync_service().await {
                    call_js(
                        &on_state_clone,
                        to_json(&SyncStatus {
                            phase: SyncPhase::Idle,
                            message: None,
                        }),
                    );

                    let mut stream = sync_service.state();
                    let _ = sync_service.start().await;

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
                                message: Some("Offline (auto-retrying)".into()),
                            },
                            State::Terminated => SyncStatus {
                                phase: SyncPhase::Idle,
                                message: Some("Sync stopped".into()),
                            },
                            State::Error(err) => SyncStatus {
                                phase: SyncPhase::Error,
                                message: Some(format!("Sync error: {err:?}")),
                            },
                        };
                        call_js(&on_state_clone, to_json(&mapped));
                    }
                }
            });
            return;
        }
        let obs = Box::new(JsSyncObserver(on_state));
        let _ = self.with_client(|c| c.start_supervised_sync(obs));
    }

    #[wasm_bindgen]
    pub fn enter_foreground(&self) {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            state.app_in_foreground.set(true);
            wasm_bindgen_futures::spawn_local(async move {
                let _ = state.client.event_cache().subscribe();
                if let Some(sync_service) = state.ensure_sync_service().await {
                    let _ = sync_service.start().await;
                }
            });
            return;
        }
        let _ = self.with_client(|c| c.enter_foreground());
    }

    #[wasm_bindgen]
    pub fn enter_background(&self) {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            state.app_in_foreground.set(false);
            wasm_bindgen_futures::spawn_local(async move {
                if let Some(sync_service) = state.ensure_sync_service().await {
                    let _ = sync_service.stop().await;
                }
            });
            return;
        }
        let _ = self.with_client(|c| c.enter_background());
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = observeRoomList)]
    pub fn observe_room_list(&self, on_reset: Function, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.room_list_subs.borrow_mut().insert(id, abort_handle);
            wasm_bindgen_futures::spawn_local(async move {
                let state_for_loop = state.clone();
                let state_for_cleanup = state.clone();
                let on_reset_clone = on_reset.clone();

                let _ = Abortable::new(async move {
                    let Some(sync_service) = state_for_loop.ensure_sync_service().await else {
                        return;
                    };

                    let room_list_service = sync_service.room_list_service();
                    let Ok(all) = room_list_service.all_rooms().await else {
                        return;
                    };

                    let (stream, controller) = all.entries_with_dynamic_adapters(50);
                    controller.set_filter(Box::new(filters::new_filter_non_left()));
                    let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<RoomListCmd>();
                    state_for_loop.room_list_cmds.borrow_mut().insert(id, cmd_tx);

                    use matrix_sdk_ui::room_list_service::RoomListItem;
                    tokio::pin!(stream);
                    let mut items = Vector::<RoomListItem>::new();

                    loop {
                        tokio::select! {
                            Some(cmd) = cmd_rx.recv() => {
                                match cmd {
                                    RoomListCmd::SetUnreadOnly(unread_only) => {
                                        if unread_only {
                                            controller.set_filter(Box::new(filters::new_filter_all(vec![
                                                Box::new(filters::new_filter_non_left()),
                                                Box::new(filters::new_filter_unread()),
                                            ])));
                                        } else {
                                            controller.set_filter(Box::new(filters::new_filter_non_left()));
                                        }
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
                                    let mut snapshot: Vec<RoomListEntry> = Vec::with_capacity(items.len());

                                    for item in items.iter() {
                                        let room = &**item;
                                        let notifications = room.num_unread_notifications();
                                        let messages = room.num_unread_messages();
                                        let mentions = room.num_unread_mentions();
                                        let marked_unread = room.is_marked_unread();
                                        let is_favourite = room.is_favourite();
                                        let is_low_priority = room.is_low_priority();
                                        let last_ts: u64 = room.recency_stamp().map_or(0, Into::into);
                                        let is_dm: bool = room.is_direct().await.unwrap_or(false);

                                        let mut avatar_url = room.avatar_url().map(|mxc: _| mxc.to_string());
                                        if avatar_url.is_none() && is_dm {
                                            avatar_url = crate::Client::dm_peer_avatar_url(room, state_for_loop.client.user_id()).await;
                                        }

                                        let is_encrypted = matches!(
                                            room.encryption_state(),
                                            matrix_sdk::EncryptionState::Encrypted
                                        );
                                        let member_count_u64 = room.joined_members_count();
                                        let member_count = member_count_u64.min(u32::MAX as u64) as u32;
                                        let topic = room.topic();
                                        let is_invited = matches!(room.state(), RoomState::Invited);

                                        let latest_event =
                                            latest_room_event_for(&state_for_loop.timeline_mgr, room).await;

                                        snapshot.push(RoomListEntry {
                                            room_id: room.room_id().to_string(),
                                            name: item.cached_display_name()
                                                .clone()
                                                .unwrap_or(RoomDisplayName::Named(room.room_id().to_string()))
                                                .to_string(),
                                            last_ts,
                                            notifications,
                                            messages,
                                            mentions,
                                            marked_unread,
                                            is_favourite,
                                            is_low_priority,
                                            is_invited,
                                            avatar_url,
                                            is_dm,
                                            is_encrypted,
                                            member_count,
                                            topic,
                                            latest_event,
                                        });
                                    }

                                    state_for_loop.room_list_cache.replace(snapshot.clone());
                                    call_js(&on_reset_clone, to_json(&snapshot));
                                }
                            }
                            else => break,
                        }
                    }
                }, abort_reg).await;

                state_for_cleanup.room_list_cmds.borrow_mut().remove(&id);
                state_for_cleanup.room_list_subs.borrow_mut().remove(&id);
            });

            let _ = on_update;
            return id as f64;
        }
        let obs = Box::new(JsRoomListObserver(on_reset, on_update));
        self.with_client(|c| c.observe_room_list(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveRoomList)]
    pub fn unobserve_room_list(&self, token: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            state.room_list_cmds.borrow_mut().remove(&(token as u64));
            return state
                .room_list_subs
                .borrow_mut()
                .remove(&(token as u64))
                .is_some();
        }
        self.with_client(|c| c.unobserve_room_list(token as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn room_list_set_unread_only(&self, token: f64, unread_only: bool) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let _ = state;
            let _ = token;
            let _ = unread_only;
            return true;
        }
        self.with_client(|c| c.room_list_set_unread_only(token as u64, unread_only))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = leaveRoom)]
    pub fn leave_room(&self, room_id: String) -> bool {
        self.with_client(|c| c.leave_room(room_id).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = roomProfile)]
    pub async fn room_profile(&self, room_id: String) -> JsValue {
        let async_state = self.async_state.borrow().as_ref().cloned();
        if let Some(state) = async_state {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return JsValue::NULL;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return JsValue::NULL;
            };

            let name = room
                .display_name()
                .await
                .map(|d| d.to_string())
                .unwrap_or_else(|_| rid.to_string());

            let topic = room.topic();
            let member_count = room.joined_members_count();
            let is_encrypted = matches!(
                room.encryption_state(),
                matrix_sdk::EncryptionState::Encrypted
            );
            let is_dm = room.is_direct().await.unwrap_or(false);
            let mut avatar_url = room.avatar_url().map(|m| m.to_string());
            let canonical_alias = room.canonical_alias().map(|a| a.to_string());
            let alt_aliases: Vec<String> =
                room.alt_aliases().iter().map(|a| a.to_string()).collect();
            let room_version = room.version().map(|v| v.to_string());

            if avatar_url.is_none() && is_dm {
                if let Some(me) = state.client.user_id() {
                    if let Ok(members) = room.members(matrix_sdk::RoomMemberships::JOIN).await {
                        for m in members {
                            if m.user_id() != me {
                                avatar_url = m.avatar_url().map(|a| a.to_string());
                                break;
                            }
                        }
                    }
                }
            }

            let profile = crate::RoomProfile {
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
            };
            return to_json(&profile);
        }

        let v = self
            .with_client(|c| c.room_profile(room_id).ok().flatten())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = createRoom)]
    pub fn create_room(
        &self,
        name: Option<String>,
        topic: Option<String>,
        invitees: Vec<String>,
        is_public: bool,
        room_alias: Option<String>,
    ) -> Option<String> {
        self.with_client(|c| {
            c.create_room(name, topic, invitees, is_public, room_alias)
                .ok()
        })
        .ok()
        .flatten()
    }

    #[wasm_bindgen(js_name = getPinnedEvents)]
    pub fn get_pinned_events(&self, room_id: String) -> JsValue {
        let v = self
            .with_client(|c| c.get_pinned_events(room_id))
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen(js_name = setPinnedEvents)]
    pub fn set_pinned_events(&self, room_id: String, event_ids: Vec<String>) -> bool {
        self.with_client(|c| c.set_pinned_events(room_id, event_ids))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = roomNotificationMode)]
    pub async fn room_notification_mode(&self, room_id: String) -> Option<String> {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return None;
            };

            let mode = room.notification_mode().await?;
            return Some(
                match mode {
                    RsMode::AllMessages => "AllMessages",
                    RsMode::MentionsAndKeywordsOnly => "MentionsAndKeywordsOnly",
                    RsMode::Mute => "Mute",
                }
                .to_owned(),
            );
        }

        let m = self
            .with_client(|c| c.room_notification_mode(room_id))
            .ok()
            .flatten()?;
        Some(
            match m {
                FfiRoomNotificationMode::AllMessages => "AllMessages",
                FfiRoomNotificationMode::MentionsAndKeywordsOnly => "MentionsAndKeywordsOnly",
                FfiRoomNotificationMode::Mute => "Mute",
            }
            .to_owned(),
        )
    }

    #[wasm_bindgen]
    pub async fn set_room_notification_mode(&self, room_id: String, mode: String) -> bool {
        let sdk_mode = match mode.as_str() {
            "AllMessages" => RsMode::AllMessages,
            "MentionsAndKeywordsOnly" => RsMode::MentionsAndKeywordsOnly,
            "Mute" => RsMode::Mute,
            _ => return false,
        };

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let settings = state.client.notification_settings().await;
            return settings
                .set_room_notification_mode(rid.as_ref(), sdk_mode)
                .await
                .is_ok();
        }

        let ffi_mode = match sdk_mode {
            RsMode::AllMessages => FfiRoomNotificationMode::AllMessages,
            RsMode::MentionsAndKeywordsOnly => FfiRoomNotificationMode::MentionsAndKeywordsOnly,
            RsMode::Mute => FfiRoomNotificationMode::Mute,
        };

        self.with_client(|c| c.set_room_notification_mode(room_id, ffi_mode).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = getUserPowerLevel)]
    pub async fn get_user_power_level(&self, room_id: String, user_id: String) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return -1.0;
            };
            let Ok(uid) = user_id.parse::<matrix_sdk::ruma::OwnedUserId>() else {
                return -1.0;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return -1.0;
            };

            return match room.get_user_power_level(uid.as_ref()).await {
                Ok(level) => match level {
                    matrix_sdk::ruma::events::room::power_levels::UserPowerLevel::Infinite => {
                        i64::MAX as f64
                    }
                    matrix_sdk::ruma::events::room::power_levels::UserPowerLevel::Int(v) => {
                        i64::from(v) as f64
                    }
                    _ => -1.0,
                },
                Err(_) => -1.0,
            };
        }

        self.with_client(|c| c.get_user_power_level(room_id, user_id) as f64)
            .unwrap_or(-1.0)
    }

    #[wasm_bindgen(js_name = roomPowerLevels)]
    pub fn room_power_levels(&self, room_id: String) -> JsValue {
        let v = self
            .with_client(|c| c.room_power_levels(room_id).ok())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub fn apply_power_level_changes(&self, room_id: String, changes_json: String) -> bool {
        let changes: RoomPowerLevelChanges = match serde_json::from_str(&changes_json) {
            Ok(c) => c,
            Err(_) => return false,
        };
        self.with_client(|c| c.apply_power_level_changes(room_id, changes).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn update_power_level_for_user(
        &self,
        room_id: String,
        user_id: String,
        power_level: f64,
    ) -> bool {
        self.with_client(|c| {
            c.update_power_level_for_user(room_id, user_id, power_level as i64)
                .is_ok()
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn report_content(
        &self,
        room_id: String,
        event_id: String,
        score: Option<i32>,
        reason: Option<String>,
    ) -> bool {
        self.with_client(|c| c.report_content(room_id, event_id, score, reason).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn report_room(&self, room_id: String, reason: Option<String>) -> bool {
        self.with_client(|c| c.report_room(room_id, reason).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn fetch_notification(&self, room_id: String, event_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let rid = match matrix_sdk::ruma::OwnedRoomId::try_from(room_id) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };
            let eid = match matrix_sdk::ruma::OwnedEventId::try_from(event_id) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

            let _ = state.ensure_sync_service().await;

            let process_setup = {
                if let Some(sync) = state.sync_service.borrow().as_ref().cloned() {
                    NotificationProcessSetup::SingleProcess { sync_service: sync }
                } else {
                    NotificationProcessSetup::MultipleProcesses
                }
            };

            let Ok(nc) = NotificationClient::new(state.client.clone(), process_setup).await else {
                return JsValue::NULL;
            };

            let Ok(status) = nc.get_notification(&rid, &eid).await else {
                return JsValue::NULL;
            };

            return match status {
                NotificationStatus::Event(item) => {
                    match crate::map_notification_item_to_rendered(&rid, &eid, &item) {
                        Some(v) => to_json(&v),
                        None => JsValue::NULL,
                    }
                }
                NotificationStatus::EventFilteredOut
                | NotificationStatus::EventNotFound
                | NotificationStatus::EventRedacted => JsValue::NULL,
            };
        }

        let v = self
            .with_client(|c| c.fetch_notification(room_id, event_id).ok().flatten())
            .ok()
            .flatten();
        match v {
            Some(n) => to_json(&n),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn fetch_notifications_since(
        &self,
        since_ms: f64,
        max_rooms: u32,
        max_events: u32,
    ) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let _ = state.ensure_sync_service().await;

            let process_setup = {
                if let Some(sync) = state.sync_service.borrow().as_ref().cloned() {
                    NotificationProcessSetup::SingleProcess { sync_service: sync }
                } else {
                    NotificationProcessSetup::MultipleProcesses
                }
            };

            let Ok(nc) = NotificationClient::new(state.client.clone(), process_setup).await else {
                return to_json(&Vec::<RenderedNotification>::new());
            };

            let mut out = Vec::new();

            for room in state
                .client
                .joined_rooms()
                .into_iter()
                .take(max_rooms as usize)
            {
                let rid = room.room_id().to_owned();

                let Ok(tl) = room.timeline().await else {
                    continue;
                };
                let (items, _stream) = tl.subscribe().await;

                for it in items.iter().rev() {
                    let Some(ev) = it.as_event() else { continue };

                    let ts: u64 = ev.timestamp().0.into();
                    if ts <= since_ms as u64 {
                        break;
                    }

                    let Some(eid_ref) = ev.event_id() else {
                        continue;
                    };

                    let Ok(status) = nc.get_notification(&rid, eid_ref).await else {
                        continue;
                    };

                    let NotificationStatus::Event(item) = status else {
                        continue;
                    };

                    let eid = eid_ref.to_owned();

                    if let Some(rendered) =
                        crate::map_notification_item_to_rendered(&rid, &eid, &item)
                    {
                        out.push(rendered);
                        if out.len() as u32 >= max_events {
                            return to_json(&out);
                        }
                    }
                }
            }

            return to_json(&out);
        }

        let v = match self
            .with_client(|c| c.fetch_notifications_since(since_ms as u64, max_rooms, max_events))
        {
            Ok(Ok(items)) => items,
            _ => Vec::new(),
        };
        to_json(&v)
    }

    #[wasm_bindgen]
    pub fn room_unread_stats(&self, room_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return JsValue::NULL;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return JsValue::NULL;
            };
            return to_json(&crate::UnreadStats {
                messages: room.num_unread_messages(),
                notifications: room.num_unread_notifications(),
                mentions: room.num_unread_mentions(),
            });
        }

        let v = self
            .with_client(|c| c.room_unread_stats(room_id))
            .ok()
            .flatten();
        match v {
            Some(s) => to_json(&s),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = roomTags)]
    pub fn room_tags(&self, room_id: String) -> JsValue {
        let tags = self.with_client(|c| c.room_tags(room_id)).ok().flatten();
        match tags {
            Some(t) => {
                let j = serde_json::json!({"favourite": t.is_favourite, "low_priority": t.is_low_priority});
                JsValue::from_str(&j.to_string())
            }
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = reactionsBatch)]
    pub fn reactions_batch(&self, room_id: String, event_ids_json: String) -> JsValue {
        let event_ids: Vec<String> = match serde_json::from_str(&event_ids_json) {
            Ok(v) => v,
            Err(_) => return JsValue::from_str("{}"),
        };
        let v = self
            .with_client(|c| c.reactions_batch(room_id, event_ids))
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    pub fn seen_by_for_event(&self, room_id: String, event_id: String, limit: u32) -> JsValue {
        if self.async_state.borrow().is_some() {
            return to_json(&Vec::<crate::SeenByEntry>::new());
        }

        let v = self.with_client(|c| c.seen_by_for_event(room_id, event_id, limit));
        match v {
            Ok(items) => to_json(&items),
            Err(_) => to_json(&Vec::<crate::SeenByEntry>::new()), // TODO: fix it later, hide the spam for now
        }
    }

    #[wasm_bindgen(js_name = dmPeerUserId)]
    pub async fn dm_peer_user_id(&self, room_id: String) -> Option<String> {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return None;
            };
            let Some(me) = state.client.user_id() else {
                return None;
            };

            if let Ok(members) = room.members(matrix_sdk::RoomMemberships::ACTIVE).await {
                for m in members {
                    if m.user_id() != me {
                        return Some(m.user_id().to_string());
                    }
                }
            }
            return None;
        }

        self.with_client(|c| c.dm_peer_user_id(room_id))
            .ok()
            .flatten()
    }

    #[wasm_bindgen]
    pub fn ensure_dm(&self, user_id: String) -> Option<String> {
        self.with_client(|c| c.ensure_dm(user_id).ok())
            .ok()
            .flatten()
    }

    #[wasm_bindgen(js_name = searchUsers)]
    pub fn search_users(&self, term: String, limit: u32) -> JsValue {
        let v = self
            .with_client(|c| c.search_users(term, limit as u64).unwrap_or_default())
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen(js_name = getUserProfile)]
    pub fn get_user_profile(&self, user_id: String) -> JsValue {
        let v = self
            .with_client(|c| c.get_user_profile(user_id).ok())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = publicRooms)]
    pub fn public_rooms(
        &self,
        server: Option<String>,
        search: Option<String>,
        limit: u32,
        since: Option<String>,
    ) -> JsValue {
        let v = self
            .with_client(|c| c.public_rooms(server, search, limit, since).ok())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::from_str("{\"rooms\":[],\"nextBatch\":null,\"prevBatch\":null}"),
        }
    }

    #[wasm_bindgen(js_name = joinByIdOrAlias)]
    pub fn join_by_id_or_alias(&self, id_or_alias: String) -> Result<(), String> {
        self.with_client(|c| c.join_by_id_or_alias(id_or_alias))
            .map_err(|e| e.as_string().unwrap_or_else(|| format!("{e:?}")))?
            .map_err(|e| e.to_string())
    }

    #[wasm_bindgen(js_name = resolveRoomId)]
    pub fn resolve_room_id(&self, id_or_alias: String) -> Option<String> {
        self.with_client(|c| c.resolve_room_id(id_or_alias).ok())
            .ok()
            .flatten()
    }

    #[wasm_bindgen]
    pub async fn thread_replies(
        &self,
        room_id: String,
        root_event_id: String,
        from: Option<String>,
        limit: u32,
        forward: bool,
    ) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let rid = match matrix_sdk::ruma::OwnedRoomId::try_from(room_id.clone()) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };
            let root = match matrix_sdk::ruma::OwnedEventId::try_from(root_event_id.clone()) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

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
            req.dir = if forward {
                Direction::Forward
            } else {
                Direction::Backward
            };

            let Ok(resp) = state.client.send(req).await else {
                return JsValue::NULL;
            };

            let mut out: Vec<MessageEvent> = Vec::new();

            if let Some(root_ev) =
                crate::map_event_id_via_timeline(&state.timeline_mgr, &state.client, &rid, &root)
                    .await
            {
                out.push(root_ev);
            }

            for raw in resp.chunk.iter() {
                if let Ok(ml) = raw.deserialize() {
                    let eid = ml.event_id().to_owned();
                    if let Some(mev) = crate::map_event_id_via_timeline(
                        &state.timeline_mgr,
                        &state.client,
                        &rid,
                        &eid,
                    )
                    .await
                    {
                        out.push(mev);
                    }
                }
            }

            out.sort_by_key(|e| e.timestamp_ms);

            return to_json(&crate::ThreadPage {
                root_event_id,
                room_id,
                messages: out,
                next_batch: resp.next_batch.clone(),
                prev_batch: resp.prev_batch.clone(),
            });
        }

        let v = self
            .with_client(|c| {
                c.thread_replies(room_id, root_event_id, from, limit, forward)
                    .ok()
            })
            .ok()
            .flatten();

        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn thread_summary(
        &self,
        room_id: String,
        root_event_id: String,
        per_page: u32,
        max_pages: u32,
    ) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let rid = match matrix_sdk::ruma::OwnedRoomId::try_from(room_id.clone()) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };
            let root = match matrix_sdk::ruma::OwnedEventId::try_from(root_event_id.clone()) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

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

                let Ok(resp) = state.client.send(req).await else {
                    return JsValue::NULL;
                };

                for raw in resp.chunk.iter() {
                    if let Ok(ml) = raw.deserialize() {
                        let eid = ml.event_id().to_owned();
                        count += 1;
                        if let Some(mev) = crate::map_event_id_via_timeline(
                            &state.timeline_mgr,
                            &state.client,
                            &rid,
                            &eid,
                        )
                        .await
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

            return to_json(&crate::ThreadSummary {
                root_event_id,
                room_id,
                count,
                latest_ts_ms: latest,
            });
        }

        let v = self.with_client(|c| c.thread_summary(room_id, root_event_id, per_page, max_pages));
        match v {
            Ok(s) => to_json(&s),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub fn send_poll_start(
        &self,
        room_id: String,
        question: String,
        answers_json: String,
        kind: String,
        max_selections: u32,
    ) -> bool {
        let answers: Vec<String> = serde_json::from_str(&answers_json).unwrap_or_default();
        let poll_kind = if kind == "Undisclosed" {
            crate::PollKind::Undisclosed
        } else {
            crate::PollKind::Disclosed
        };
        let def = crate::PollDefinition {
            question,
            answers,
            kind: poll_kind,
            max_selections,
        };
        self.with_client(|c| c.send_poll_start(room_id, def).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn send_poll_response(
        &self,
        room_id: String,
        poll_event_id: String,
        answers_json: String,
    ) -> bool {
        let answers: Vec<String> = serde_json::from_str(&answers_json).unwrap_or_default();
        self.with_client(|c| {
            c.send_poll_response(room_id, poll_event_id, answers)
                .is_ok()
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn send_poll_end(&self, room_id: String, poll_event_id: String) -> bool {
        self.with_client(|c| c.send_poll_end(room_id, poll_event_id).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = startVerificationInbox)]
    pub fn start_verification_inbox(&self, on_request: Function, on_error: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn VerificationInboxObserver> =
                Arc::new(JsVerificationInboxObserver(on_request, on_error));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.inbox_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(async move {
                    state
                        .client
                        .encryption()
                        .wait_for_e2ee_initialization_tasks()
                        .await;

                    let _ = state.client.event_cache().subscribe();

                    let td_handler =
                        state.client.observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
                    let mut td_sub = td_handler.subscribe();

                    let ir_handler = state.client.observe_events::<SyncRoomMessageEvent, Room>();
                    let mut ir_sub = ir_handler.subscribe();

                    loop {
                        tokio::select! {
                            maybe = td_sub.next() => {
                                if let Some((ev, ())) = maybe {
                                    let flow_id = ev.content.transaction_id.to_string();
                                    let from_user = ev.sender.to_string();
                                    let from_device = ev.content.from_device.to_string();

                                    state.inbox.borrow_mut().insert(
                                        flow_id.clone(),
                                        (ev.sender, ev.content.from_device.clone()),
                                    );

                                    let _ = catch_unwind(AssertUnwindSafe(|| {
                                        obs.on_request(flow_id, from_user, from_device);
                                    }));
                                } else {
                                    break;
                                }
                            }

                            maybe = ir_sub.next() => {
                                if let Some((ev, _room)) = maybe {
                                    if let SyncRoomMessageEvent::Original(o) = ev {
                                        if let MessageType::VerificationRequest(_) = &o.content.msgtype {
                                            let flow_id = o.event_id.to_string();
                                            let from_user = o.sender.to_string();

                                            state.inbox.borrow_mut().insert(
                                                flow_id.clone(),
                                                (o.sender.clone(), owned_device_id!("inroom")),
                                            );

                                            let _ = catch_unwind(AssertUnwindSafe(|| {
                                                obs.on_request(flow_id, from_user, String::new());
                                            }));
                                        }
                                    }
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                }, abort_reg).await;

                state_cleanup.inbox_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsVerificationInboxObserver(on_request, on_error));
        self.with_client(|c| c.start_verification_inbox(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unobserveVerificationInbox)]
    pub fn unobserve_verification_inbox(&self, sub_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.inbox_subs, sub_id as u64);
        }

        self.with_client(|c| c.unobserve_verification_inbox(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = startSelfSas)]
    pub async fn start_self_sas(
        &self,
        target_device_id: String,
        on_phase: Function,
        on_emojis: Function,
        on_error: Function,
    ) -> String {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn VerificationObserver> =
                Arc::new(JsVerificationObserver(on_phase, on_emojis, on_error));

            let Some(me) = state.client.user_id() else {
                obs.on_error("".into(), "No user".into());
                return "".into();
            };

            state
                .client
                .encryption()
                .wait_for_e2ee_initialization_tasks()
                .await;

            let Ok(devices) = state.client.encryption().get_user_devices(me).await else {
                obs.on_error("".into(), "Devices unavailable".into());
                return "".into();
            };

            let Some(dev) = devices
                .devices()
                .find(|d| d.device_id().as_str() == target_device_id)
            else {
                obs.on_error("".into(), "Device not found".into());
                return "".into();
            };

            match dev.request_verification().await {
                Ok(req) => {
                    let flow_id = req.flow_id().to_string();
                    Self::wait_and_start_sas_web(state.clone(), flow_id.clone(), req, obs.clone());
                    flow_id
                }
                Err(e) => {
                    obs.on_error("".into(), e.to_string());
                    "".into()
                }
            }
        } else {
            let obs = Box::new(JsVerificationObserver(on_phase, on_emojis, on_error));
            self.with_client(|c| c.start_self_sas(target_device_id, obs))
                .unwrap_or_default()
        }
    }

    #[wasm_bindgen(js_name = startUserSas)]
    pub async fn start_user_sas(
        &self,
        user_id: String,
        on_phase: Function,
        on_emojis: Function,
        on_error: Function,
    ) -> String {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn VerificationObserver> =
                Arc::new(JsVerificationObserver(on_phase, on_emojis, on_error));

            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                obs.on_error("".into(), "Bad user id".into());
                return "".into();
            };

            state
                .client
                .encryption()
                .wait_for_e2ee_initialization_tasks()
                .await;

            match state.client.encryption().request_user_identity(&uid).await {
                Ok(Some(identity)) => match identity.request_verification().await {
                    Ok(req) => {
                        let flow_id = req.flow_id().to_string();
                        Self::wait_and_start_sas_web(
                            state.clone(),
                            flow_id.clone(),
                            req,
                            obs.clone(),
                        );
                        flow_id
                    }
                    Err(e) => {
                        obs.on_error("".into(), e.to_string());
                        "".into()
                    }
                },
                Ok(None) => {
                    obs.on_error("".into(), "User has no cross-signing identity".into());
                    "".into()
                }
                Err(e) => {
                    obs.on_error("".into(), format!("Identity fetch failed: {e}"));
                    "".into()
                }
            }
        } else {
            let obs = Box::new(JsVerificationObserver(on_phase, on_emojis, on_error));
            self.with_client(|c| c.start_user_sas(user_id, obs))
                .unwrap_or_default()
        }
    }

    #[wasm_bindgen(js_name = acceptVerificationRequest)]
    pub async fn accept_verification_request(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
        on_phase: Function,
        on_emojis: Function,
        on_error: Function,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn VerificationObserver> =
                Arc::new(JsVerificationObserver(on_phase, on_emojis, on_error));

            let Some(user) =
                Self::resolve_other_user_for_flow_web(state.as_ref(), &flow_id, other_user_id)
            else {
                return false;
            };

            if let Some(req) = state
                .client
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                if req.accept().await.is_err() {
                    return false;
                }
                Self::wait_and_start_sas_web(state.clone(), flow_id.clone(), req, obs.clone());
                true
            } else {
                false
            }
        } else {
            let obs = Box::new(JsVerificationObserver(on_phase, on_emojis, on_error));
            self.with_client(|c| c.accept_verification_request(flow_id, other_user_id, obs))
                .unwrap_or(false)
        }
    }

    #[wasm_bindgen(js_name = acceptSas)]
    pub async fn accept_sas(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
        on_phase: Function,
        on_emojis: Function,
        on_error: Function,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn VerificationObserver> =
                Arc::new(JsVerificationObserver(on_phase, on_emojis, on_error));

            let Some(user) =
                Self::resolve_other_user_for_flow_web(state.as_ref(), &flow_id, other_user_id)
            else {
                return false;
            };

            if let Some(f) = state.verifs.lock().unwrap().get(&flow_id) {
                return f.sas.accept().await.is_ok();
            }

            let Some(verification) = state
                .client
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            else {
                return false;
            };

            for _ in 0..25 {
                if let Some(sas) = verification.clone().sas() {
                    let sas_for_stream = sas.clone();
                    let flow_for_stream = flow_id.clone();
                    let obs_for_stream = obs.clone();
                    let verifs_for_stream = state.verifs.clone();

                    wasm_bindgen_futures::spawn_local(async move {
                        crate::attach_sas_stream(
                            verifs_for_stream,
                            flow_for_stream,
                            sas_for_stream,
                            obs_for_stream,
                        )
                        .await;
                    });

                    return sas.accept().await.is_ok();
                }

                gloo_timers::future::sleep(Duration::from_millis(120)).await;
            }

            false
        } else {
            let obs = Box::new(JsVerificationObserver(on_phase, on_emojis, on_error));
            self.with_client(|c| c.accept_sas(flow_id, other_user_id, obs))
                .unwrap_or(false)
        }
    }

    #[wasm_bindgen(js_name = confirmVerification)]
    pub async fn confirm_verification(&self, flow_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let sas = {
                state
                    .verifs
                    .lock()
                    .unwrap()
                    .get(&flow_id)
                    .map(|f| f.sas.clone())
            };
            return match sas {
                Some(sas) => sas.confirm().await.is_ok(),
                None => false,
            };
        }

        self.with_client(|c| c.confirm_verification(flow_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = cancelVerification)]
    pub async fn cancel_verification(&self, flow_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let sas = {
                state
                    .verifs
                    .lock()
                    .unwrap()
                    .get(&flow_id)
                    .map(|f| f.sas.clone())
            };

            if let Some(sas) = sas {
                return sas.cancel().await.is_ok();
            }

            let user = state
                .inbox
                .borrow()
                .get(&flow_id)
                .map(|p| p.0.clone())
                .or_else(|| state.client.user_id().map(|u| u.to_owned()));

            let Some(user) = user else { return false };

            if let Some(v) = state
                .client
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                if let Some(sas) = v.sas() {
                    return sas.cancel().await.is_ok();
                }
            }

            return false;
        }

        self.with_client(|c| c.cancel_verification(flow_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = cancelVerificationRequest)]
    pub async fn cancel_verification_request(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let user = if let Some(uid) = other_user_id {
                match uid.parse::<OwnedUserId>() {
                    Ok(u) => u,
                    Err(_) => return false,
                }
            } else if let Some((u, _)) = state.inbox.borrow().get(&flow_id).cloned() {
                u
            } else {
                return false;
            };

            if let Some(req) = state
                .client
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                return req.cancel().await.is_ok();
            }

            if let Some(v) = state
                .client
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                if let Some(sas) = v.sas() {
                    return sas.cancel().await.is_ok();
                }
            }

            return false;
        }

        self.with_client(|c| c.cancel_verification_request(flow_id, other_user_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn check_verification_request(&self, user_id: String, flow_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                return false;
            };
            return state
                .client
                .encryption()
                .get_verification_request(&uid, &flow_id)
                .await
                .is_some();
        }

        self.with_client(|c| c.check_verification_request(user_id, flow_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = listMyDevices)]
    pub async fn list_my_devices(&self) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Some(me) = state.client.user_id() else {
                return to_json(&Vec::<crate::DeviceSummary>::new());
            };

            let Ok(user_devs) = state.client.encryption().get_user_devices(me).await else {
                return to_json(&Vec::<crate::DeviceSummary>::new());
            };

            let items: Vec<crate::DeviceSummary> = user_devs
                .devices()
                .map(|dev| {
                    let ed25519 = dev.ed25519_key().map(|k| k.to_base64()).unwrap_or_default();
                    let is_own = state
                        .client
                        .device_id()
                        .map(|my| my == dev.device_id())
                        .unwrap_or(false);

                    crate::DeviceSummary {
                        device_id: dev.device_id().to_string(),
                        display_name: dev.display_name().unwrap_or_default().to_string(),
                        ed25519,
                        is_own,
                        verified: dev.is_verified(),
                    }
                })
                .collect();

            return to_json(&items);
        }

        let v = self
            .with_client(|c| c.list_my_devices())
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen(js_name = backupExistsOnServer)]
    pub async fn backup_exists_on_server(&self, fetch: bool) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let backups = state.client.encryption().backups();
            return if fetch {
                backups.fetch_exists_on_server().await.unwrap_or(false)
            } else {
                backups.exists_on_server().await.unwrap_or(false)
            };
        }

        self.with_client(|c| c.backup_exists_on_server(fetch))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setKeyBackupEnabled)]
    pub async fn set_key_backup_enabled(&self, enabled: bool) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let backups = state.client.encryption().backups();
            return if enabled {
                backups.create().await.is_ok()
            } else {
                backups.disable().await.is_ok()
            };
        }

        self.with_client(|c| c.set_key_backup_enabled(enabled))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setPresence)]
    pub async fn set_presence(&self, presence: String, status: Option<String>) -> bool {
        let p = match presence.as_str() {
            "Online" => Presence::Online,
            "Offline" => Presence::Offline,
            "Unavailable" => Presence::Unavailable,
            _ => return false,
        };

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Some(me) = state.client.user_id() else {
                return false;
            };

            let presence_state = match p {
                Presence::Online => PresenceState::Online,
                Presence::Offline => PresenceState::Offline,
                Presence::Unavailable => PresenceState::Unavailable,
            };

            let mut req = set_presence_v3::Request::new(me.to_owned(), presence_state);
            req.status_msg = status;

            return state.client.send(req).await.is_ok();
        }

        self.with_client(|c| c.set_presence(p, status).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = getPresence)]
    pub async fn get_presence(&self, user_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let uid = match user_id.parse::<OwnedUserId>() {
                Ok(u) => u,
                Err(_) => return JsValue::NULL,
            };

            let req = get_presence_v3::Request::new(uid);
            let Ok(resp) = state.client.send(req).await else {
                return JsValue::NULL;
            };

            let presence = match resp.presence {
                PresenceState::Online => Presence::Online,
                PresenceState::Offline => Presence::Offline,
                PresenceState::Unavailable => Presence::Unavailable,
                _ => Presence::Offline,
            };

            return to_json(&crate::PresenceInfo {
                presence,
                status_msg: resp.status_msg,
            });
        }

        let v = self
            .with_client(|c| c.get_presence(user_id).ok())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn room_preview(&self, id_or_alias: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let target = match OwnedRoomOrAliasId::try_from(id_or_alias) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

            let Ok(preview) = state.client.get_room_preview(&target, vec![]).await else {
                return JsValue::NULL;
            };

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

            return to_json(&RoomPreview {
                room_id: preview.room_id.to_string(),
                canonical_alias: preview.canonical_alias.map(|a| a.to_string()),
                name: preview.name,
                topic: preview.topic,
                avatar_url: preview.avatar_url.map(|m| m.to_string()),
                member_count: preview.num_joined_members,
                world_readable: preview.is_world_readable,
                join_rule,
                membership,
            });
        }

        match self.with_client(|c| c.room_preview(id_or_alias)) {
            Ok(Ok(v)) => to_json(&v),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn knock(&self, id_or_alias: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(target) = OwnedRoomOrAliasId::try_from(id_or_alias) else {
                return false;
            };
            return state.client.knock(target, None, vec![]).await.is_ok();
        }

        self.with_client(|c| c.knock(id_or_alias)).unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = ignoreUser)]
    pub fn ignore_user(&self, user_id: String) -> bool {
        self.with_client(|c| c.ignore_user(user_id).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = unignoreUser)]
    pub fn unignore_user(&self, user_id: String) -> bool {
        self.with_client(|c| c.unignore_user(user_id).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn ignored_users(&self) -> JsValue {
        let v = self
            .with_client(|c| c.ignored_users().unwrap_or_default())
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    pub fn is_user_ignored(&self, user_id: String) -> bool {
        self.with_client(|c| c.is_user_ignored(user_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn publish_room_alias(&self, room_id: String, alias: String) -> bool {
        self.with_client(|c| c.publish_room_alias(room_id, alias).ok().unwrap_or(false))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn unpublish_room_alias(&self, room_id: String, alias: String) -> bool {
        self.with_client(|c| c.unpublish_room_alias(room_id, alias).ok().unwrap_or(false))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn set_room_canonical_alias(
        &self,
        room_id: String,
        alias: Option<String>,
        alt_aliases_json: String,
    ) -> bool {
        let alt_aliases: Vec<String> = serde_json::from_str(&alt_aliases_json).unwrap_or_default();
        self.with_client(|c| {
            c.set_room_canonical_alias(room_id, alias, alt_aliases)
                .is_ok()
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn room_aliases(&self, room_id: String) -> JsValue {
        let v = self
            .with_client(|c| c.room_aliases(room_id))
            .unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    pub fn room_join_rule(&self, room_id: String) -> Option<String> {
        let r = self
            .with_client(|c| c.room_join_rule(room_id).ok())
            .ok()
            .flatten()?;
        Some(
            match r {
                RoomJoinRule::Public => "Public",
                RoomJoinRule::Invite => "Invite",
                RoomJoinRule::Knock => "Knock",
                RoomJoinRule::Restricted => "Restricted",
                RoomJoinRule::KnockRestricted => "KnockRestricted",
            }
            .to_owned(),
        )
    }

    #[wasm_bindgen]
    pub fn set_room_join_rule(&self, room_id: String, rule: String) -> bool {
        let r = match rule.as_str() {
            "Public" => RoomJoinRule::Public,
            "Invite" => RoomJoinRule::Invite,
            "Knock" => RoomJoinRule::Knock,
            "Restricted" => RoomJoinRule::Restricted,
            "KnockRestricted" => RoomJoinRule::KnockRestricted,
            _ => return false,
        };
        self.with_client(|c| c.set_room_join_rule(room_id, r).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn room_history_visibility(&self, room_id: String) -> Option<String> {
        let r = self
            .with_client(|c| c.room_history_visibility(room_id).ok())
            .ok()
            .flatten()?;
        Some(
            match r {
                RoomHistoryVisibility::Invited => "Invited",
                RoomHistoryVisibility::Joined => "Joined",
                RoomHistoryVisibility::Shared => "Shared",
                RoomHistoryVisibility::WorldReadable => "WorldReadable",
            }
            .to_owned(),
        )
    }

    #[wasm_bindgen]
    pub fn set_room_history_visibility(&self, room_id: String, visibility: String) -> bool {
        let v = match visibility.as_str() {
            "Invited" => RoomHistoryVisibility::Invited,
            "Joined" => RoomHistoryVisibility::Joined,
            "Shared" => RoomHistoryVisibility::Shared,
            "WorldReadable" => RoomHistoryVisibility::WorldReadable,
            _ => return false,
        };
        self.with_client(|c| c.set_room_history_visibility(room_id, v).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn room_directory_visibility(&self, room_id: String) -> Option<String> {
        let r = self
            .with_client(|c| c.room_directory_visibility(room_id).ok())
            .ok()
            .flatten()?;
        Some(
            match r {
                RoomDirectoryVisibility::Public => "Public",
                RoomDirectoryVisibility::Private => "Private",
            }
            .to_owned(),
        )
    }

    #[wasm_bindgen]
    pub fn set_room_directory_visibility(&self, room_id: String, visibility: String) -> bool {
        let v = match visibility.as_str() {
            "Public" => RoomDirectoryVisibility::Public,
            "Private" => RoomDirectoryVisibility::Private,
            _ => return false,
        };
        self.with_client(|c| c.set_room_directory_visibility(room_id, v).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn room_successor(&self, room_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return JsValue::NULL;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return JsValue::NULL;
            };
            return match room.successor_room() {
                Some(s) => to_json(&crate::SuccessorRoomInfo::from(s)),
                None => JsValue::NULL,
            };
        }

        let v = self
            .with_client(|c| c.room_successor(room_id))
            .ok()
            .flatten();
        match v {
            Some(s) => to_json(&s),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn room_predecessor(&self, room_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return JsValue::NULL;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return JsValue::NULL;
            };
            return match room.predecessor_room() {
                Some(p) => to_json(&crate::PredecessorRoomInfo::from(p)),
                None => JsValue::NULL,
            };
        }

        let v = self
            .with_client(|c| c.room_predecessor(room_id))
            .ok()
            .flatten();
        match v {
            Some(s) => to_json(&s),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub async fn start_live_location(&self, room_id: String, duration_ms: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            match room
                .start_live_location_share(duration_ms as u64, None)
                .await
            {
                Ok(response) => {
                    state.live_location_beacons.borrow_mut().insert(
                        room_id,
                        crate::LiveLocationBeaconState {
                            event_id: response.event_id.to_string(),
                            duration_ms: duration_ms as u64,
                            description: None,
                        },
                    );
                    true
                }
                Err(_) => false,
            }
        } else {
            self.with_client(|c| {
                c.start_live_location(room_id, duration_ms as u64, None)
                    .is_ok()
            })
            .unwrap_or(false)
        }
    }

    #[wasm_bindgen]
    pub async fn stop_live_location(&self, room_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use matrix_sdk::ruma::events::beacon_info::BeaconInfoEventContent;

            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            let cached = state.live_location_beacons.borrow().get(&room_id).cloned();

            let result = if let Some(cached) = cached {
                room.send_state_event_for_key(
                    room.own_user_id(),
                    BeaconInfoEventContent::new(
                        cached.description,
                        std::time::Duration::from_millis(cached.duration_ms),
                        false,
                        None,
                    ),
                )
                .await
                .is_ok()
            } else {
                room.stop_live_location_share().await.is_ok()
            };

            if result {
                state.live_location_beacons.borrow_mut().remove(&room_id);
            }

            result
        } else {
            self.with_client(|c| c.stop_live_location(room_id).is_ok())
                .unwrap_or(false)
        }
    }

    #[wasm_bindgen]
    pub async fn send_live_location(&self, room_id: String, geo_uri: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            use matrix_sdk::ruma::{EventId, events::beacon::BeaconEventContent};

            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            let beacon_state = state.live_location_beacons.borrow().get(&room_id).cloned();

            if let Some(beacon_state) = beacon_state {
                let Ok(beacon_event_id) = EventId::parse(&beacon_state.event_id) else {
                    return false;
                };
                let content = BeaconEventContent::new(beacon_event_id, geo_uri, None);
                room.send(content).await.is_ok()
            } else {
                room.send_location_beacon(geo_uri).await.is_ok()
            }
        } else {
            self.with_client(|c| c.send_live_location(room_id, geo_uri).is_ok())
                .unwrap_or(false)
        }
    }

    #[wasm_bindgen]
    pub fn observe_live_location(&self, room_id: String, on_update: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return 0.0;
            };
            let obs: Arc<dyn LiveLocationObserver> = Arc::new(JsLiveLocationObserver(on_update));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state
                .live_location_subs
                .borrow_mut()
                .insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let Some(room) = state.client.get_room(&rid) else {
                            return;
                        };

                        let observable = room.observe_live_location_shares();
                        let stream = observable.subscribe();

                        use futures_util::{StreamExt, pin_mut};
                        pin_mut!(stream);

                        let mut latest_shares: HashMap<String, crate::LiveLocationShareInfo> =
                            HashMap::new();

                        while let Some(event) = stream.next().await {
                            let info = crate::LiveLocationShareInfo {
                                user_id: event.user_id.to_string(),
                                geo_uri: event.last_location.location.uri.to_string(),
                                ts_ms: event.last_location.ts.0.into(),
                                is_live: event
                                    .beacon_info
                                    .as_ref()
                                    .map(|info| info.is_live())
                                    .unwrap_or(true),
                            };

                            latest_shares.insert(info.user_id.clone(), info);
                            let snapshot = latest_shares.values().cloned().collect();

                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(snapshot)));
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.live_location_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsLiveLocationObserver(on_update));
        self.with_client(|c| c.observe_live_location(room_id, obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    pub fn unobserve_live_location(&self, sub_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.live_location_subs, sub_id as u64);
        }

        self.with_client(|c| c.unobserve_live_location(sub_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn start_call_inbox(&self, on_invite: Function) -> f64 {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let obs: Arc<dyn CallObserver> = Arc::new(JsCallObserver(on_invite));
            let id = state.next_sub_id();
            let (abort_handle, abort_reg) = AbortHandle::new_pair();
            state.call_subs.borrow_mut().insert(id, abort_handle);

            wasm_bindgen_futures::spawn_local(async move {
                let state_cleanup = state.clone();
                let _ = Abortable::new(
                    async move {
                        let handler = state
                            .client
                            .observe_events::<OriginalSyncCallInviteEvent, Room>();
                        let mut sub = handler.subscribe();

                        while let Some((ev, room)) = sub.next().await {
                            let call_id = ev.content.call_id.to_string();
                            let is_video = ev.content.offer.sdp.contains("m=video");
                            let ts: u64 = ev.origin_server_ts.0.into();

                            let invite = crate::CallInvite {
                                room_id: room.room_id().to_string(),
                                sender: ev.sender.to_string(),
                                call_id,
                                is_video,
                                ts_ms: ts,
                            };

                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_invite(invite)));
                        }
                    },
                    abort_reg,
                )
                .await;

                state_cleanup.call_subs.borrow_mut().remove(&id);
            });

            return id as f64;
        }

        let obs = Box::new(JsCallObserver(on_invite));
        self.with_client(|c| c.start_call_inbox(obs) as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen]
    pub fn stop_call_inbox(&self, token: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return Self::abort_sub(&state.call_subs, token as u64);
        }

        self.with_client(|c| c.stop_call_inbox(token as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn start_element_call(
        &self,
        room_id: String,
        intent: String,
        element_call_url: Option<String>,
        parent_url: Option<String>,
        language_tag: Option<String>,
        theme: Option<String>,
        on_to_widget: Function,
    ) -> JsValue {
        let i = match intent.as_str() {
            "StartCall" => ElementCallIntent::StartCall,
            "JoinExisting" => ElementCallIntent::JoinExisting,
            "StartCallVoiceDm" => ElementCallIntent::StartCallVoiceDm,
            "JoinExistingVoiceDm" => ElementCallIntent::JoinExistingVoiceDm,
            _ => ElementCallIntent::JoinExisting,
        };

        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let session_id = state.next_sub_id();
            let obs: Arc<dyn CallWidgetObserver> = Arc::new(JsCallWidgetObserver(on_to_widget));

            let lang = language_tag
                .as_deref()
                .and_then(|s| LanguageTag::parse(s).ok());

            let Ok(rid) = OwnedRoomId::try_from(room_id.as_str()) else {
                return JsValue::NULL;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return JsValue::NULL;
            };

            let resolved_parent = parent_url.unwrap_or_else(|| {
                "https://appassets.androidplatform.net/assets/element-call/index.html".to_owned()
            });

            let props = VirtualElementCallWidgetProperties {
                element_call_url: element_call_url.unwrap_or_else(|| {
                    "https://appassets.androidplatform.net/element-call/index.html".to_owned()
                }),
                parent_url: Some(resolved_parent.clone()),
                widget_id: format!("mages-ecall-{}", session_id),
                ..VirtualElementCallWidgetProperties::default()
            };

            let is_dm = room.is_direct().await.unwrap_or(false);

            let widget_intent = match (i, is_dm) {
                (ElementCallIntent::StartCall, true) => WidgetIntent::StartCallDm,
                (ElementCallIntent::JoinExisting, true) => WidgetIntent::JoinExistingDm,
                (ElementCallIntent::StartCall, false) => WidgetIntent::StartCall,
                (ElementCallIntent::JoinExisting, false) => WidgetIntent::JoinExisting,
                (ElementCallIntent::StartCallVoiceDm, _) => WidgetIntent::StartCallDmVoice,
                (ElementCallIntent::JoinExistingVoiceDm, _) => WidgetIntent::JoinExistingDmVoice,
            };

            let config = VirtualElementCallWidgetConfig {
                controlled_audio_devices: Some(true),
                preload: Some(false),
                app_prompt: Some(false),
                confine_to_room: Some(true),
                hide_screensharing: Some(false),
                intent: Some(widget_intent),
                ..VirtualElementCallWidgetConfig::default()
            };

            let settings = match WidgetSettings::new_virtual_element_call_widget(props, config) {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

            let client_props = ClientProperties::new("org.mlm.mages", lang, theme);
            let url = match settings.generate_webview_url(&room, client_props).await {
                Ok(v) => v,
                Err(_) => return JsValue::NULL,
            };

            let widget_base_url = settings.base_url().map(|u| u.to_string());
            let (driver, handle) = WidgetDriver::new(settings);
            let cap_provider = WasmElementCallCapabilitiesProvider {};

            state
                .widget_handles
                .borrow_mut()
                .insert(session_id, handle.clone());

            let (recv_abort, recv_reg) = AbortHandle::new_pair();
            state
                .widget_recv_subs
                .borrow_mut()
                .insert(session_id, recv_abort);

            let obs_recv = obs.clone();
            let handle_recv = handle.clone();
            wasm_bindgen_futures::spawn_local(async move {
                let _ = Abortable::new(
                    async move {
                        while let Some(msg) = handle_recv.recv().await {
                            let _ = catch_unwind(AssertUnwindSafe(|| {
                                obs_recv.on_to_widget(msg);
                            }));
                        }
                    },
                    recv_reg,
                )
                .await;
            });

            let (driver_abort, driver_reg) = AbortHandle::new_pair();
            state
                .widget_driver_subs
                .borrow_mut()
                .insert(session_id, driver_abort);

            let room_driver = room.clone();
            wasm_bindgen_futures::spawn_local(async move {
                let _ = Abortable::new(
                    async move {
                        let _ = driver.run(room_driver, cap_provider).await;
                    },
                    driver_reg,
                )
                .await;
            });

            return to_json(&CallSessionInfo {
                session_id,
                widget_url: url.to_string(),
                widget_base_url,
                parent_url: Some(resolved_parent),
            });
        }

        let obs = Box::new(JsCallWidgetObserver(on_to_widget));
        let v = self
            .with_client(|c| {
                c.start_element_call(
                    room_id,
                    element_call_url,
                    parent_url,
                    i,
                    obs,
                    language_tag,
                    theme,
                )
                .ok()
            })
            .ok()
            .flatten();

        match v {
            Some(s) => to_json(&s),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen]
    pub fn call_widget_from_webview(&self, session_id: f64, message: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            if let Some(handle) = state
                .widget_handles
                .borrow()
                .get(&(session_id as u64))
                .cloned()
            {
                wasm_bindgen_futures::spawn_local(async move {
                    let _ = handle.send(message).await;
                });
                return true;
            }
            return false;
        }

        self.with_client(|c| c.call_widget_from_webview(session_id as u64, message))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn stop_element_call(&self, session_id: f64) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let sid = session_id as u64;
            let mut any = false;

            if let Some(handle) = state.widget_driver_subs.borrow_mut().remove(&sid) {
                handle.abort();
                any = true;
            }
            if let Some(handle) = state.widget_recv_subs.borrow_mut().remove(&sid) {
                handle.abort();
                any = true;
            }
            state.widget_handles.borrow_mut().remove(&sid);
            return any;
        }

        self.with_client(|c| c.stop_element_call(session_id as u64))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn search_room(
        &self,
        room_id: String,
        query: String,
        limit: u32,
        offset: Option<u32>,
    ) -> JsValue {
        let v = self
            .with_client(|c| c.search_room(room_id, query, limit, offset).ok())
            .ok()
            .flatten();
        match v {
            Some(p) => to_json(&p),
            None => JsValue::from_str("{\"hits\":[],\"nextOffset\":null}"),
        }
    }

    #[wasm_bindgen(js_name = isEventReadBy)]
    pub async fn is_event_read_by(
        &self,
        room_id: String,
        event_id: String,
        user_id: String,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::OwnedEventId::try_from(event_id.as_str()) else {
                return false;
            };
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                return false;
            };

            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };

            let Some(latest) = tl.latest_user_read_receipt_timeline_event_id(&uid).await else {
                return false;
            };

            let items = tl.items().await;
            let mut idx_latest = None;
            let mut idx_target = None;

            for (i, it) in items.iter().enumerate() {
                if let Some(ev) = it.as_event() {
                    if let Some(found) = ev.event_id() {
                        if found == &latest {
                            idx_latest = Some(i);
                        }
                        if found == &eid {
                            idx_target = Some(i);
                        }
                    }
                }
                if idx_latest.is_some() && idx_target.is_some() {
                    break;
                }
            }

            return matches!((idx_target, idx_latest), (Some(i_t), Some(i_l)) if i_l >= i_t);
        }

        self.with_client(|c| c.is_event_read_by(room_id, event_id, user_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn register_unifiedpush(
        &self,
        app_id: String,
        push_key: String,
        gateway_url: String,
        device_name: String,
        lang: String,
        profile_tag: Option<String>,
    ) -> bool {
        self.with_client(|c| {
            c.register_unifiedpush(
                app_id,
                push_key,
                gateway_url,
                device_name,
                lang,
                profile_tag,
            )
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn unregister_unifiedpush(&self, app_id: String, pushkey: String) -> bool {
        self.with_client(|c| c.unregister_unifiedpush(app_id, pushkey))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub fn encryption_catchup_once(&self) -> bool {
        false
    }

    #[wasm_bindgen(js_name = loginSsoLoopbackAvailable)]
    pub async fn login_sso_loopback_available(&self) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let details = homeserver_login_details_from_client(&state.client).await;
            return details.supports_sso;
        }
        self.with_client(|c| c.homeserver_login_details())
            .map(|d| d.supports_sso)
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = loginOauthLoopbackAvailable)]
    pub async fn login_oauth_loopback_available(&self) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let details = homeserver_login_details_from_client(&state.client).await;
            return details.supports_oauth;
        }
        self.with_client(|c| c.homeserver_login_details())
            .map(|d| d.supports_oauth)
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn login_sso_browser(
        &self,
        _redirect_uri: String,
        _device_name: Option<String>,
    ) -> JsValue {
        to_json(&serde_json::json!({
            "ok": false,
            "error": "SSO is not supported on web"
        }))
    }

    #[wasm_bindgen(js_name = loginOauthBrowser)]
    pub async fn login_oauth_browser(
        &self,
        redirect_uri: String,
        _device_name: Option<String>,
    ) -> JsValue {
        let redirect = match Url::parse(&redirect_uri) {
            Ok(v) => v,
            Err(e) => {
                return to_json(&serde_json::json!({
                    "ok": false,
                    "error": format!("invalid redirect URI: {e}")
                }));
            }
        };

        let Some(state) = self.async_state.borrow().as_ref().cloned() else {
            return to_json(&serde_json::json!({
                "ok": false,
                "error": "async wasm client not initialized"
            }));
        };

        let registration_data = crate::mages_client_metadata(&redirect).into();

        let result = state
            .client
            .oauth()
            .login(redirect, None, Some(registration_data), None)
            .build()
            .await;

        match result {
            Ok(auth_data) => to_json(&serde_json::json!({
                "ok": true,
                "url": auth_data.url.to_string()
            })),
            Err(e) => to_json(&serde_json::json!({
                "ok": false,
                "error": e.to_string()
            })),
        }
    }

    #[wasm_bindgen(js_name = finishLoginFromRedirect)]
    pub async fn finish_login_from_redirect(
        &self,
        callback_url_or_query: String,
        _expected_state: String,
        _expected_issuer: Option<String>,
    ) -> bool {
        let Some(state) = self.async_state.borrow().as_ref().cloned() else {
            return false;
        };

        let url_or_query = match Url::parse(&callback_url_or_query) {
            Ok(url) => UrlOrQuery::Url(url),
            Err(_) => UrlOrQuery::Query(callback_url_or_query),
        };

        if state.client.oauth().finish_login(url_or_query).await.is_err() {
            return false;
        }

        state.persist_session();

        state.client.encryption().wait_for_e2ee_initialization_tasks().await;
        let _ = state.ensure_sync_service().await;
        let _ = state.client.event_cache().subscribe();
        state.ensure_send_queue_supervision();

        true
    }

    #[wasm_bindgen(js_name = homeserverLoginDetails)]
    pub async fn homeserver_login_details(&self) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            return to_json(&homeserver_login_details_from_client(&state.client).await);
        }
        let details = self.with_client(|c| c.homeserver_login_details()).ok();
        match details {
            Some(d) => to_json(&d),
            None => JsValue::from_str(
                "{\"supportsOauth\":false,\"supportsSso\":false,\"supportsPassword\":true}",
            ),
        }
    }
}

async fn homeserver_login_details_from_client(client: &SdkClient) -> HomeserverLoginDetails {
    let supports_oauth = client.oauth().server_metadata().await.is_ok();

    let (supports_sso, supports_password) = match client.matrix_auth().get_login_types().await {
        Ok(response) => {
            use matrix_sdk::ruma::api::client::session::get_login_types::v3::LoginType;

            let supports_sso = response
                .flows
                .iter()
                .any(|f| matches!(f, LoginType::Sso(_)));
            let supports_password = response
                .flows
                .iter()
                .any(|f| matches!(f, LoginType::Password(_)));

            (supports_sso, supports_password)
        }
        Err(_) => (false, false),
    };

    HomeserverLoginDetails {
        supports_oauth,
        supports_sso,
        supports_password,
    }
}

wasm_delegate_bool! {
    is_space(room_id: String);
}

#[wasm_bindgen]
impl WasmClient {
    #[wasm_bindgen(js_name = enableRoomEncryption)]
    pub fn enable_room_encryption(&self, room_id: String) -> bool {
        self.with_client(|c| c.enable_room_encryption(room_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = banUser)]
    pub fn ban_user(&self, room_id: String, user_id: String, reason: Option<String>) -> bool {
        self.with_client(|c| c.ban_user(room_id, user_id, reason))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = unbanUser)]
    pub fn unban_user(&self, room_id: String, user_id: String, reason: Option<String>) -> bool {
        self.with_client(|c| c.unban_user(room_id, user_id, reason))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = kickUser)]
    pub fn kick_user(&self, room_id: String, user_id: String, reason: Option<String>) -> bool {
        self.with_client(|c| c.kick_user(room_id, user_id, reason))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = inviteUser)]
    pub fn invite_user(&self, room_id: String, user_id: String) -> bool {
        self.with_client(|c| c.invite_user(room_id, user_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = acceptInvite)]
    pub fn accept_invite(&self, room_id: String) -> bool {
        self.with_client(|c| c.accept_invite(room_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setRoomName)]
    pub fn set_room_name(&self, room_id: String, name: String) -> bool {
        self.with_client(|c| c.set_room_name(room_id, name))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setRoomTopic)]
    pub fn set_room_topic(&self, room_id: String, topic: String) -> bool {
        self.with_client(|c| c.set_room_topic(room_id, topic))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setRoomFavourite)]
    pub fn set_room_favourite(&self, room_id: String, favourite: bool) -> bool {
        self.with_client(|c| c.set_room_favourite(room_id, favourite))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setRoomLowPriority)]
    pub fn set_room_low_priority(&self, room_id: String, low_priority: bool) -> bool {
        self.with_client(|c| c.set_room_low_priority(room_id, low_priority))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = reactionsForEvent)]
    pub fn reactions_for_event(&self, room_id: String, event_id: String) -> JsValue {
        let value = self
            .with_client(|c| c.reactions_for_event(room_id, event_id))
            .unwrap_or_default();
        to_json(&value)
    }

    #[wasm_bindgen(js_name = react)]
    pub async fn react(&self, room_id: String, event_id: String, emoji: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::EventId::parse(&event_id) else {
                return false;
            };
            let Some(item) = tl.item_by_event_id(&eid).await else {
                return false;
            };

            let item_id = item.identifier();
            return tl.toggle_reaction(&item_id, &emoji).await.is_ok();
        }

        self.with_client(|c| c.react(room_id, event_id, emoji))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = markRead)]
    pub async fn mark_read(&self, room_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };

            return tl.mark_as_read(ReceiptType::ReadPrivate).await.is_ok();
        }

        self.with_client(|c| c.mark_read(room_id)).unwrap_or(false)
    }

    #[wasm_bindgen(js_name = markReadAt)]
    pub async fn mark_read_at(&self, room_id: String, event_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::EventId::parse(&event_id) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            return room
                .send_single_receipt(
                    ReceiptType::ReadPrivate,
                    ReceiptThread::Unthreaded,
                    eid.to_owned(),
                )
                .await
                .is_ok();
        }

        self.with_client(|c| c.mark_read_at(room_id, event_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = markFullyReadAt)]
    pub async fn mark_fully_read_at(&self, room_id: String, event_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Ok(eid) = matrix_sdk::ruma::OwnedEventId::try_from(event_id) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            let receipts = matrix_sdk::room::Receipts::new()
                .private_read_receipt(eid.clone())
                .fully_read_marker(eid);

            return room.send_multiple_receipts(receipts).await.is_ok();
        }

        self.with_client(|c| c.mark_fully_read_at(room_id, event_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setTyping)]
    pub async fn set_typing(&self, room_id: String, typing: bool) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };

            return room.typing_notice(typing).await.is_ok();
        }

        self.with_client(|c| c.set_typing(room_id, typing))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn space_invite_user(&self, space_id: String, user_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(space_id.clone()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid) else {
                return false;
            };
            let Ok(uid) = matrix_sdk::ruma::OwnedUserId::try_from(user_id) else {
                return false;
            };

            return room.invite_user_by_id(&uid).await.is_ok();
        }

        self.with_client(|c| c.space_invite_user(space_id, user_id))
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn my_spaces(&self) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let mut out = Vec::new();

            for room in state.client.joined_space_rooms() {
                let rid = room.room_id().to_owned();
                let name = room
                    .display_name()
                    .await
                    .map(|d| d.to_string())
                    .unwrap_or_else(|_| rid.to_string());
                let topic = room.topic();
                let member_count = room.joined_members_count();
                let is_encrypted = matches!(
                    room.encryption_state(),
                    matrix_sdk::EncryptionState::Encrypted
                );
                let is_public = room.is_public().unwrap_or(false);

                out.push(crate::SpaceInfo {
                    room_id: rid.to_string(),
                    name,
                    topic,
                    member_count,
                    is_encrypted,
                    is_public,
                    avatar_url: room.avatar_url().map(|mxc| mxc.to_string()),
                });
            }

            return to_json(&out);
        }

        let v = self.with_client(|c| c.my_spaces()).unwrap_or_default();
        to_json(&v)
    }

    #[wasm_bindgen]
    pub async fn create_space(
        &self,
        name: String,
        topic: Option<String>,
        is_public: bool,
        invitees: Vec<String>,
    ) -> Option<String> {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let mut req = matrix_sdk::ruma::api::client::room::create_room::v3::Request::new();

            let mut cc =
                matrix_sdk::ruma::api::client::room::create_room::v3::CreationContent::new();
            cc.room_type = Some(RoomType::Space);
            req.creation_content = Some(matrix_sdk::ruma::serde::Raw::new(&cc).ok()?);

            req.name = Some(name);
            req.topic = topic;
            req.visibility = if is_public {
                matrix_sdk::ruma::api::client::room::Visibility::Public
            } else {
                matrix_sdk::ruma::api::client::room::Visibility::Private
            };
            req.preset = Some(if is_public {
                matrix_sdk::ruma::api::client::room::create_room::v3::RoomPreset::PublicChat
            } else {
                matrix_sdk::ruma::api::client::room::create_room::v3::RoomPreset::PrivateChat
            });

            if !invitees.is_empty() {
                let parsed = invitees
                    .into_iter()
                    .map(|u| u.parse())
                    .collect::<Result<Vec<_>, _>>()
                    .ok()?;
                req.invite = parsed;
            }

            let resp = state.client.send(req).await.ok()?;
            return Some(resp.room_id.to_string());
        }

        self.with_client(|c| c.create_space(name, topic, is_public, invitees).ok())
            .ok()
            .flatten()
    }

    #[wasm_bindgen]
    pub async fn space_add_child(
        &self,
        space_id: String,
        child_room_id: String,
        order: Option<String>,
        suggested: Option<bool>,
    ) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid_space) = matrix_sdk::ruma::OwnedRoomId::try_from(space_id.as_str()) else {
                return false;
            };
            let Ok(rid_child) = matrix_sdk::ruma::OwnedRoomId::try_from(child_room_id.as_str())
            else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid_space) else {
                return false;
            };

            let via: Vec<_> = rid_child
                .server_name()
                .map(|s| s.to_owned())
                .into_iter()
                .collect();

            let mut content =
                matrix_sdk::ruma::events::space::child::SpaceChildEventContent::new(via);

            if let Some(o) = order {
                let Ok(ord) = <&SpaceChildOrder>::try_from(o.as_str()) else {
                    return false;
                };
                content.order = Some(ord.to_owned());
            }

            content.suggested = suggested.unwrap_or(false);

            return room
                .send_state_event_for_key(&rid_child, content)
                .await
                .is_ok();
        }

        self.with_client(|c| {
            c.space_add_child(space_id, child_room_id, order, suggested)
                .is_ok()
        })
        .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn space_remove_child(&self, space_id: String, child_room_id: String) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid_space) = matrix_sdk::ruma::OwnedRoomId::try_from(space_id.as_str()) else {
                return false;
            };
            let Some(room) = state.client.get_room(&rid_space) else {
                return false;
            };

            return room
                .send_state_event_raw(
                    "m.space.child",
                    child_room_id.as_str(),
                    serde_json::json!({}),
                )
                .await
                .is_ok();
        }

        self.with_client(|c| c.space_remove_child(space_id, child_room_id).is_ok())
            .unwrap_or(false)
    }

    #[wasm_bindgen]
    pub async fn space_hierarchy(
        &self,
        space_id: String,
        from: Option<String>,
        limit: u32,
        max_depth: Option<u32>,
        suggested_only: bool,
    ) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid_space) = matrix_sdk::ruma::OwnedRoomId::try_from(space_id.as_str()) else {
                return JsValue::NULL;
            };

            let mut req =
                matrix_sdk::ruma::api::client::space::get_hierarchy::v1::Request::new(rid_space);
            req.from = from;
            if limit > 0 {
                req.limit = Some(limit.into());
            }
            req.max_depth = max_depth.map(Into::into);
            req.suggested_only = suggested_only;

            let Ok(resp) = state.client.send(req).await else {
                return JsValue::NULL;
            };

            let children = resp
                .rooms
                .into_iter()
                .map(|chunk| {
                    let s = chunk.summary;
                    let is_space = matches!(s.room_type, Some(RoomType::Space));

                    crate::SpaceChildInfo {
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

            return to_json(&crate::SpaceHierarchyPage {
                children,
                next_batch: resp.next_batch,
            });
        }

        let v = self
            .with_client(|c| {
                c.space_hierarchy(space_id, from, limit, max_depth, suggested_only)
                    .ok()
            })
            .ok()
            .flatten();

        match v {
            Some(p) => to_json(&p),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = listMembers)]
    pub async fn list_members(&self, room_id: String) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return to_json(&Vec::<crate::MemberSummary>::new());
            };
            if let Some(room) = state.client.get_room(&rid) {
                let me = state.client.user_id();
                if let Ok(members) = room.members(matrix_sdk::RoomMemberships::ACTIVE).await {
                    let summaries: Vec<crate::MemberSummary> = members
                        .into_iter()
                        .map(|m| crate::MemberSummary {
                            user_id: m.user_id().to_string(),
                            display_name: m.display_name().map(|n| n.to_string()),
                            avatar_url: m.avatar_url().map(|u| u.to_string()),
                            is_me: me.map(|u| u == m.user_id()).unwrap_or(false),
                            membership: m.membership().to_string(),
                        })
                        .collect();
                    return to_json(&summaries);
                }
            }
            return to_json(&Vec::<crate::MemberSummary>::new());
        }
        let value = self
            .with_client(|c| c.list_members(room_id).unwrap_or_default())
            .unwrap_or_default();
        to_json(&value)
    }

    #[wasm_bindgen]
    #[wasm_bindgen(js_name = listInvited)]
    pub async fn list_invited(&self) -> JsValue {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let invites = state.client.invited_rooms();
            let mut profiles = Vec::<crate::RoomProfile>::with_capacity(invites.len());

            for invite in invites {
                let rid = invite.room_id().to_owned();
                let name = invite
                    .display_name()
                    .await
                    .map(|n| n.to_string())
                    .unwrap_or_else(|_| rid.to_string());

                let avatar_url = invite.avatar_url().map(|a| a.to_string());
                let is_dm = invite.is_direct().await.unwrap_or(false);
                let is_encrypted = matches!(
                    invite.encryption_state(),
                    matrix_sdk::EncryptionState::Encrypted
                );

                profiles.push(crate::RoomProfile {
                    room_id: rid.to_string(),
                    name,
                    topic: invite.topic(),
                    member_count: invite.active_members_count(),
                    is_encrypted,
                    is_dm,
                    avatar_url,
                    canonical_alias: invite.canonical_alias().map(|a| a.to_string()),
                    alt_aliases: invite.alt_aliases().iter().map(|a| a.to_string()).collect(),
                    room_version: invite.version().map(|v| v.to_string()),
                });
            }

            return to_json(&profiles);
        }

        let value = self
            .with_client(|c| c.list_invited().unwrap_or_default())
            .unwrap_or_default();
        to_json(&value)
    }

    #[wasm_bindgen(js_name = paginateBackwards)]
    pub async fn paginate_backwards(&self, room_id: String, count: u32) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };

            let me = state
                .client
                .user_id()
                .map(|u| u.to_string())
                .unwrap_or_default();

            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };

            return crate::paginate_backwards_visible(&tl, &rid, &me, count as usize).await;
        }

        self.with_client(|c| c.paginate_backwards(room_id, count as u16))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = paginateForwards)]
    pub async fn paginate_forwards(&self, room_id: String, count: u32) -> bool {
        if let Some(state) = self.async_state.borrow().as_ref().cloned() {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };

            let Some(tl) = state.timeline_mgr.timeline_for(&rid).await else {
                return false;
            };

            return tl.paginate_forwards(count as u16).await.unwrap_or(false);
        }

        self.with_client(|c| c.paginate_forwards(room_id, count as u16))
            .unwrap_or(false)
    }
}

#[derive(Clone)]
struct WasmElementCallCapabilitiesProvider {}

impl CapabilitiesProvider for WasmElementCallCapabilitiesProvider {
    fn acquire_capabilities(
        &self,
        requested: Capabilities,
    ) -> impl futures_util::Future<Output = Capabilities> + Send {
        async move { requested }
    }
}
