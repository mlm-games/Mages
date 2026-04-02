use crate::core::{CoreClient, TimelineManager, map_send_queue_update, room_list_membership};
use crate::js_observer_json;
use crate::js_observer_noargs;
use crate::types::*;
use crate::verification_flow::{
    VerifEvent, drive_incoming_verification, drive_verification_request,
};
use crate::wasm_delegate_bool;
use crate::wasm_delegate_json;
use crate::wasm_delegate_result_bool;
use crate::wasm_delegate_result_json;
use crate::webffi_bool;

use crate::wasm_delegate_option_json;
use crate::wasm_subscribe;
use crate::wasm_unobserve;
use crate::{
    emit_timeline_reset_filled, latest_room_event_for, mages_client_metadata, map_vec_diff,
    strip_matrix_path,
};
use crate::{webffi_err, webffi_not_init, webffi_option, webffi_unit, webffi_value};

use futures_util::StreamExt;
use futures_util::future::{AbortHandle, Abortable};
use js_sys::Function;

use matrix_sdk::utils::UrlOrQuery;
use matrix_sdk::authentication::oauth::registration::language_tags::LanguageTag;

use matrix_sdk::ruma::events::room::{
    ImageInfo, MediaSource,
    message::{
        FileMessageEventContent, ImageMessageEventContent, MessageType, VideoInfo,
        VideoMessageEventContent,
    },
};

use matrix_sdk::ruma::events::{
    key::verification::request::ToDeviceKeyVerificationRequestEvent, receipt::SyncReceiptEvent,
    room::message::SyncRoomMessageEvent,
};

use matrix_sdk::ruma::{OwnedDeviceId, OwnedEventId, OwnedRoomId, OwnedUserId};

use matrix_sdk::sleep::sleep;

use matrix_sdk::widget::{
    ClientProperties, Intent as WidgetIntent, VirtualElementCallWidgetConfig,
    VirtualElementCallWidgetProperties, WidgetDriver, WidgetDriverHandle, WidgetSettings,
};

use matrix_sdk::{
    Client as SdkClient, Room, RoomDisplayName, RoomState,
    attachment::AttachmentConfig,
    authentication::AuthSession,
    encryption::{BackupDownloadStrategy, EncryptionSettings},
    media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings},
};
use mime::Mime;

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

use wasm_bindgen::JsValue;
use wasm_bindgen::prelude::*;

use web_time::Duration;

use matrix_sdk::authentication::matrix::MatrixSession;
use matrix_sdk::authentication::oauth::{ClientId, OAuthSession, UserSession};
use matrix_sdk::{SessionMeta, SessionTokens};

use matrix_sdk::encryption::verification::Verification;

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}

pub fn to_json<T: serde::Serialize>(v: &T) -> JsValue {
    let serializer = serde_wasm_bindgen::Serializer::new().serialize_maps_as_objects(true);
    v.serialize(&serializer)
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

struct JsWidgetObserver(Function);
impl JsWidgetObserver {
    fn new(f: Function) -> Self {
        Self(f)
    }
    fn on_to_widget(&self, msg: &str) {
        let _ = self.0.call1(&JsValue::NULL, &JsValue::from_str(msg));
    }
}
unsafe impl Send for JsWidgetObserver {}
unsafe impl Sync for JsWidgetObserver {}

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
    verification_subs: RefCell<HashMap<u64, AbortHandle>>,
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

    async fn finish_authenticated_setup(self: &Rc<Self>) {
        self.core.finish_authenticated_setup_common().await;
        self.ensure_send_queue_supervision();
        self.persist_session();
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
    "isSpace"              => is_space(room_id: String);
    "isUserIgnored"        => is_user_ignored(user_id: String);
}

wasm_delegate_result_bool! {
    "markRead"             => mark_read(room_id: String);
    "markReadAt"           => mark_read_at(room_id: String, event_id: String);
    "markFullyReadAt"      => mark_fully_read_at(room_id: String, event_id: String);
    "markRoomSeenLatest"   => mark_room_seen_latest(room_id: String, send_public_receipt: bool);
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
    "knock"                => knock(id_or_alias: String);
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
    "reportRoom"           => report_room(room_id: String, reason: Option<String>);
    "sendPollEnd"         => send_poll_end(room_id: String, poll_event_id: String);
    "stopLiveLocation"     => stop_live_location(room_id: String);
    "sendLiveLocation"     => send_live_location(room_id: String, geo_uri: String);
    "acceptKnockRequest"   => accept_knock_request(room_id: String, user_id: String);
    "declineKnockRequest"  => decline_knock_request(room_id: String, user_id: String, reason: Option<String>);
    "acceptInvite"         => accept_invite(room_id: String);
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
                    if info.client_id.is_none() {
                        web_sys::console::warn_1(
                            &JsValue::from_str(&format!(
                                "Stored OAuth session for {} is missing client_id. Removing invalid session and requiring re-login",
                                info.user_id
                            )),
                        );
                        clear_wasm_session(&store_name);
                    } else if let Some(cid) = info.client_id {
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
            verification_subs: RefCell::new(HashMap::new()),
        });

        {
            let weak_state = Rc::downgrade(&state);
            let mut rx = state.core.sdk.subscribe_to_session_changes();
            wasm_bindgen_futures::spawn_local(async move {
                while let Ok(change) = rx.recv().await {
                    let Some(s) = weak_state.upgrade() else {
                        break;
                    };
                    match change {
                        matrix_sdk::SessionChange::TokensRefreshed => {
                            s.persist_session();
                        }
                        matrix_sdk::SessionChange::UnknownToken(info) => {
                            if !info.soft_logout {
                                clear_wasm_session(&s.store_name);
                            }
                        }
                    }
                }
            });
        }

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
        self.login_password_async_impl(
            PasswordLoginKind::Username,
            username,
            password,
            None,
            device_display_name,
        )
        .await
    }

    #[wasm_bindgen(js_name = loginEmail)]
    pub async fn login_email_async(
        &self,
        email: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Option<String> {
        self.login_password_async_impl(
            PasswordLoginKind::Email,
            email,
            password,
            None,
            device_display_name,
        )
        .await
    }

    #[wasm_bindgen(js_name = loginPhone)]
    pub async fn login_phone_async(
        &self,
        country: String,
        phone: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Option<String> {
        self.login_password_async_impl(
            PasswordLoginKind::Phone,
            phone,
            password,
            Some(country),
            device_display_name,
        )
        .await
    }

    async fn login_password_async_impl(
        &self,
        kind: PasswordLoginKind,
        identifier: String,
        password: String,
        country: Option<String>,
        device_display_name: Option<String>,
    ) -> Option<String> {
        let Some(state) = self.state() else {
            return Some("not initialized".to_string());
        };

        if let Err(e) = state
            .core
            .login_password(kind, identifier, password, country, device_display_name)
            .await
        {
            return Some(e.to_string());
        }

        state.finish_authenticated_setup().await;
        None
    }

    #[wasm_bindgen]
    pub async fn logout(&self) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_err("not initialized");
        };
        let result = state.client().matrix_auth().logout().await;
        clear_wasm_session(&state.store_name);
        webffi_unit(result.map(|_| ()))
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
    ) -> JsValue {
        let Some(state) = self.state() else {
            return to_json(&serde_json::json!({"ok": false, "error": "not initialized"}));
        };
        let url_or_query = match matrix_sdk::reqwest::Url::parse(&callback_url_or_query) {
            Ok(url) => UrlOrQuery::Url(url),
            Err(_) => UrlOrQuery::Query(callback_url_or_query),
        };
        match state.client().oauth().finish_login(url_or_query).await {
            Ok(_) => {
                state.finish_authenticated_setup().await;
                to_json(&serde_json::json!({"ok": true}))
            }
            Err(e) => to_json(&serde_json::json!({"ok": false, "error": e.to_string()})),
        }
    }

    // Methods with wasm-specific signatures (cast, missing optional args)

    #[wasm_bindgen(js_name = sendMessage)]
    pub async fn send_message(&self, room_id: String, body: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.send_message(room_id, body, None).await)
    }

    #[wasm_bindgen(js_name = reply)]
    pub async fn reply(&self, room_id: String, in_reply_to: String, body: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.reply(room_id, in_reply_to, body, None).await)
    }

    #[wasm_bindgen(js_name = edit)]
    pub async fn edit(
        &self,
        room_id: String,
        target_event_id: String,
        new_body: String,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.edit(room_id, target_event_id, new_body, None).await)
    }

    #[wasm_bindgen(js_name = paginateBackwards)]
    pub async fn paginate_backwards(&self, room_id: String, count: u32) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.paginate_backwards(room_id, count as u16).await)
    }

    #[wasm_bindgen(js_name = paginateForwards)]
    pub async fn paginate_forwards(&self, room_id: String, count: u32) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.paginate_forwards(room_id, count as u16).await)
    }

    #[wasm_bindgen(js_name = dmPeerUserId)]
    pub async fn dm_peer_user_id(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.dm_peer_user_id(room_id).await)
    }

    #[wasm_bindgen(js_name = accountManagementUrl)]
    pub async fn account_management_url(&self) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.account_management_url().await {
            Ok(Some(url)) => JsValue::from_str(&url),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = ensureDm)]
    pub async fn ensure_dm(&self, user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.ensure_dm(user_id).await)
    }

    #[wasm_bindgen(js_name = ensureDmIfAllowed)]
    pub async fn ensure_dm_if_allowed(&self, room_id: String, user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.ensure_dm_if_allowed(room_id, user_id).await)
    }

    #[wasm_bindgen(js_name = roomActionState)]
    pub async fn room_action_state(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        match s.core.room_action_state(room_id).await {
            Ok(state) => to_json(&state),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = memberActionState)]
    pub async fn member_action_state(&self, room_id: String, user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        match s.core.member_action_state(room_id, user_id).await {
            Ok(state) => to_json(&state),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = messageActionState)]
    pub async fn message_action_state(&self, room_id: String, event_id: String, sender_user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        match s.core.message_action_state(room_id, event_id, sender_user_id).await {
            Ok(state) => to_json(&state),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = resolveRoomId)]
    pub async fn resolve_room_id(&self, id_or_alias: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.resolve_room_id(id_or_alias).await)
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
    pub async fn set_presence(&self, presence: String, status: Option<String>) -> JsValue {
        let p = match presence.as_str() {
            "Online" => Presence::Online,
            "Offline" => Presence::Offline,
            "Unavailable" => Presence::Unavailable,
            _ => return webffi_err("invalid presence state"),
        };
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.set_presence(p, status).await)
    }

    #[wasm_bindgen(js_name = isEventReadBy)]
    pub async fn is_event_read_by(
        &self,
        room_id: String,
        event_id: String,
        user_id: String,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.is_event_read_by(room_id, event_id, user_id).await)
    }

    #[wasm_bindgen(js_name = startLiveLocation)]
    pub async fn start_live_location(&self, room_id: String, duration_ms: f64) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(
            s.core
                .start_live_location(room_id, duration_ms as u64, None)
                .await,
        )
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
                                    let latest_event = latest_room_event_for(room, s.tm()).await;
                                    let membership = room_list_membership(room);
                                    snapshot.push(RoomListEntry {
                                        room_id: room.room_id().to_string(),
                                        name: item.cached_display_name().clone().unwrap_or(RoomDisplayName::Named(room.room_id().to_string())).to_string(),
                                        last_ts: room.recency_stamp().map_or(0, Into::into),
                                        notifications: room.num_unread_notifications(), messages: room.num_unread_messages(), mentions: room.num_unread_mentions(),
                                        marked_unread: room.is_marked_unread(), is_favourite: room.is_favourite(), is_low_priority: room.is_low_priority(),
                                        is_invited: matches!(room.state(), RoomState::Invited), membership, avatar_url, is_dm,
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
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_request(fid, ev.sender.to_string(), ev.content.from_device.to_string())));
                        } else { break; }
                    }
                    maybe = ir_sub.next() => {
                        if let Some((ev, _)) = maybe {
                            if let SyncRoomMessageEvent::Original(o) = ev {
                                if let MessageType::VerificationRequest(_) = &o.content.msgtype {
                                    let fid = o.event_id.to_string();
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

    #[wasm_bindgen(js_name = sendQueueSetEnabled)]
    pub async fn send_queue_set_enabled(&self, enabled: bool) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.send_queue_set_enabled(enabled).await)
    }

    #[wasm_bindgen(js_name = setMarkUnread)]
    pub async fn set_mark_unread(&self, room_id: String, unread: bool) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.set_mark_unread(room_id, unread).await)
    }

    #[wasm_bindgen(js_name = setPinnedEvents)]
    pub async fn set_pinned_events(&self, room_id: String, event_ids: Vec<String>) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.set_pinned_events(room_id, event_ids).await)
    }

    #[wasm_bindgen(js_name = roomTags)]
    pub async fn room_tags(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_option(s.core.room_tags(room_id).await)
    }

    #[wasm_bindgen(js_name = getPinnedEvents)]
    pub async fn get_pinned_events(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&Vec::<String>::new());
        };
        to_json(&s.core.get_pinned_events(room_id).await)
    }

    #[wasm_bindgen(js_name = roomNotificationMode)]
    pub async fn room_notification_mode(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_option(s.core.room_notification_mode(room_id).await)
    }

    #[wasm_bindgen(js_name = setRoomNotificationMode)]
    pub async fn set_room_notification_mode(&self, room_id: String, mode: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let m = match mode.as_str() {
            "AllMessages" => FfiRoomNotificationMode::AllMessages,
            "MentionsAndKeywordsOnly" => FfiRoomNotificationMode::MentionsAndKeywordsOnly,
            "Mute" => FfiRoomNotificationMode::Mute,
            _ => return false,
        };
        s.core.set_room_notification_mode(room_id, m).await.is_ok()
    }

    #[wasm_bindgen(js_name = searchUsers)]
    pub async fn search_users(&self, search_term: String, limit: u32) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&Vec::<DirectoryUser>::new());
        };
        match s.core.search_users(search_term, limit as u64).await {
            Ok(v) => to_json(&v),
            Err(_) => to_json(&Vec::<DirectoryUser>::new()),
        }
    }

    #[wasm_bindgen(js_name = getUserProfile)]
    pub async fn get_user_profile(&self, user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.get_user_profile(user_id).await {
            Ok(v) => to_json(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = publicRooms)]
    pub async fn public_rooms(
        &self,
        server: Option<String>,
        search: Option<String>,
        limit: u32,
        since: Option<String>,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.public_rooms(server, search, limit, since).await {
            Ok(v) => to_json(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = createRoom)]
    pub async fn create_room(
        &self,
        name: Option<String>,
        topic: Option<String>,
        invitees: Vec<String>,
        is_public: bool,
        room_alias: Option<String>,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s
            .core
            .create_room(name, topic, invitees, is_public, room_alias)
            .await
        {
            Ok(v) => JsValue::from_str(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = createSpace)]
    pub async fn create_space(
        &self,
        name: String,
        topic: Option<String>,
        is_public: bool,
        invitees: Vec<String>,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.create_space(name, topic, is_public, invitees).await {
            Ok(v) => JsValue::from_str(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = sendThreadText)]
    pub async fn send_thread_text(
        &self,
        room_id: String,
        root_event_id: String,
        body: String,
        reply_to_event_id: Option<String>,
        latest_event_id: Option<String>,
        formatted_body: Option<String>,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(
            s.core
                .send_thread_text(
                    room_id,
                    root_event_id,
                    body,
                    reply_to_event_id,
                    latest_event_id,
                    formatted_body,
                )
                .await,
        )
    }

    #[wasm_bindgen(js_name = reactionsBatch)]
    pub async fn reactions_batch(&self, room_id: String, event_ids: Vec<String>) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&HashMap::<String, Vec<ReactionSummary>>::new());
        };
        to_json(&s.core.reactions_batch(room_id, event_ids).await)
    }

    #[wasm_bindgen(js_name = canUserBan)]
    pub async fn can_user_ban(&self, room_id: String, user_id: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core.can_user_ban(room_id, user_id).await.unwrap_or(false)
    }

    #[wasm_bindgen(js_name = canUserInvite)]
    pub async fn can_user_invite(&self, room_id: String, user_id: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .can_user_invite(room_id, user_id)
            .await
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = canUserRedactOther)]
    pub async fn can_user_redact_other(&self, room_id: String, user_id: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .can_user_redact_other(room_id, user_id)
            .await
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = roomDirectoryVisibility)]
    pub async fn room_directory_visibility(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_directory_visibility(room_id).await {
            Ok(v) => to_json(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = setRoomDirectoryVisibility)]
    pub async fn set_room_directory_visibility(&self, room_id: String, visibility: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let v = match visibility.as_str() {
            "Public" => RoomDirectoryVisibility::Public,
            _ => RoomDirectoryVisibility::Private,
        };
        s.core
            .set_room_directory_visibility(room_id, v)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = publishRoomAlias)]
    pub async fn publish_room_alias(&self, room_id: String, alias: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .publish_room_alias(room_id, alias)
            .await
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = unpublishRoomAlias)]
    pub async fn unpublish_room_alias(&self, room_id: String, alias: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .unpublish_room_alias(room_id, alias)
            .await
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = setRoomCanonicalAlias)]
    pub async fn set_room_canonical_alias(
        &self,
        room_id: String,
        alias: Option<String>,
        alt_aliases: Vec<String>,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .set_room_canonical_alias(room_id, alias, alt_aliases)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = roomAliases)]
    pub async fn room_aliases(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&Vec::<String>::new());
        };
        to_json(&s.core.room_aliases(room_id).await)
    }

    #[wasm_bindgen(js_name = roomJoinRule)]
    pub async fn room_join_rule(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_join_rule(room_id).await {
            Ok(v) => to_json(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = setRoomJoinRule)]
    pub async fn set_room_join_rule(&self, room_id: String, rule: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let r = match rule.as_str() {
            "Public" => RoomJoinRule::Public,
            "Invite" => RoomJoinRule::Invite,
            "Knock" => RoomJoinRule::Knock,
            "Restricted" => RoomJoinRule::Restricted,
            "KnockRestricted" => RoomJoinRule::KnockRestricted,
            _ => return false,
        };
        s.core.set_room_join_rule(room_id, r).await.is_ok()
    }

    #[wasm_bindgen(js_name = roomHistoryVisibility)]
    pub async fn room_history_visibility(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_history_visibility(room_id).await {
            Ok(v) => to_json(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = setRoomHistoryVisibility)]
    pub async fn set_room_history_visibility(&self, room_id: String, visibility: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let v = match visibility.as_str() {
            "Invited" => RoomHistoryVisibility::Invited,
            "Joined" => RoomHistoryVisibility::Joined,
            "Shared" => RoomHistoryVisibility::Shared,
            "WorldReadable" => RoomHistoryVisibility::WorldReadable,
            _ => return false,
        };
        s.core.set_room_history_visibility(room_id, v).await.is_ok()
    }

    #[wasm_bindgen(js_name = updatePowerLevelForUser)]
    pub async fn update_power_level_for_user(
        &self,
        room_id: String,
        user_id: String,
        power_level: f64,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .update_power_level_for_user(room_id, user_id, power_level as i64)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = applyPowerLevelChanges)]
    pub async fn apply_power_level_changes(&self, room_id: String, changes_json: String) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        let Ok(changes) = serde_json::from_str::<RoomPowerLevelChanges>(&changes_json) else {
            return false;
        };
        s.core
            .apply_power_level_changes(room_id, changes)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = reportContent)]
    pub async fn report_content(
        &self,
        room_id: String,
        event_id: String,
        score: Option<i32>,
        reason: Option<String>,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .report_content(room_id, event_id, score, reason)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = sendPollStart)]
    pub async fn send_poll_start(
        &self,
        room_id: String,
        question: String,
        answers: Vec<String>,
        kind: String,
        max_selections: u32,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        let pk = match kind.as_str() {
            "Undisclosed" => PollKind::Undisclosed,
            _ => PollKind::Disclosed,
        };
        let def = PollDefinition {
            question,
            answers,
            kind: pk,
            max_selections,
        };
        match s.core.send_poll_start(room_id, def).await {
            Ok(v) => JsValue::from_str(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = sendPollResponse)]
    pub async fn send_poll_response(
        &self,
        room_id: String,
        poll_event_id: String,
        answers: Vec<String>,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
        };
        s.core
            .send_poll_response(room_id, poll_event_id, answers)
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = seenByForEvent)]
    pub async fn seen_by_for_event(
        &self,
        room_id: String,
        event_id: String,
        limit: u32,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&Vec::<SeenByEntry>::new());
        };
        match s.core.seen_by_for_event(room_id, event_id, limit).await {
            Ok(v) => to_json(&v),
            Err(_) => to_json(&Vec::<SeenByEntry>::new()),
        }
    }

    #[wasm_bindgen(js_name = upgradeRoom)]
    pub async fn upgrade_room(&self, room_id: String, new_version: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.upgrade_room(room_id, new_version).await {
            Ok(v) => JsValue::from_str(&v),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = roomUpgradeLinks)]
    pub async fn room_upgrade_links(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_upgrade_links(room_id).await {
            Some(v) => to_json(&v),
            None => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = listKnockRequests)]
    pub async fn list_knock_requests(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return to_json(&Vec::<KnockRequestSummary>::new());
        };
        match s.core.list_knock_requests(room_id).await {
            Ok(v) => to_json(&v),
            Err(_) => to_json(&Vec::<KnockRequestSummary>::new()),
        }
    }

    #[wasm_bindgen(js_name = roomListSetUnreadOnly)]
    pub fn room_list_set_unread_only(&self, token: f64, unread_only: bool) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        if let Some(tx) = state.room_list_cmds.borrow().get(&(token as u64)).cloned() {
            tx.send(RoomListCmd::SetUnreadOnly(unread_only)).is_ok()
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = observeOwnReceipt)]
    pub fn observe_own_receipt(&self, room_id: String, on_changed: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0.0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::new(JsReceiptsObserver(on_changed));
        let s = state.clone();
        wasm_subscribe!(state, receipts_subs, async move {
            let stream = s
                .client()
                .observe_room_events::<SyncReceiptEvent, matrix_sdk::room::Room>(&rid);
            let mut sub = stream.subscribe();
            while let Some((_ev, _room)) = sub.next().await {
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
            }
        })
    }

    #[wasm_bindgen(js_name = fetchNotificationsSince)]
    pub async fn fetch_notifications_since(
        &self,
        since_ts_ms: f64,
        max_rooms: u32,
        max_events: u32,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return to_json(&Vec::<RenderedNotification>::new());
        };

        let since_ts_ms = since_ts_ms as u64;

        let client = state.client();

        let setup = match state.sync_service.borrow().as_ref().cloned() {
            Some(svc) => NotificationProcessSetup::SingleProcess { sync_service: svc },
            None => NotificationProcessSetup::MultipleProcesses,
        };

        let Ok(nc) = NotificationClient::new(client.clone(), setup).await else {
            return to_json(&Vec::<RenderedNotification>::new());
        };

        let mut out = Vec::new();
        let mut rooms_checked = 0u32;

        for room in client.joined_rooms() {
            if rooms_checked >= max_rooms {
                break;
            }
            rooms_checked += 1;

            let rid = room.room_id().to_owned();
            let Ok(tl) = room.timeline().await else {
                continue;
            };
            let (items, _stream) = tl.subscribe().await;
            for it in items.iter().rev() {
                let Some(ev) = it.as_event() else { continue };
                let ts: u64 = ev.timestamp().0.into();
                if ts <= since_ts_ms {
                    break;
                }
                let Some(eid_ref) = ev.event_id() else {
                    continue;
                };
                let status = match nc.get_notification(&rid, eid_ref).await {
                    Ok(s) => s,
                    Err(_) => continue,
                };
                let NotificationStatus::Event(item) = status else {
                    continue;
                };
                let eid = eid_ref.to_owned();
                if let Some(rendered) = crate::map_notification_item_to_rendered(&rid, &eid, &item) {
                    out.push(rendered);
                    if out.len() as u32 >= max_events {
                        return to_json(&out);
                    }
                }
            }
        }
        to_json(&out)
    }

    #[wasm_bindgen(js_name = sendExistingAttachment)]
    pub async fn send_existing_attachment(
        &self,
        room_id: String,
        att_json: String,
        body: Option<String>,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_not_init();
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return webffi_err("invalid room_id");
        };
        let Some(room) = state.client().get_room(&rid) else {
            return webffi_err("room not found");
        };
        let Ok(att): Result<AttachmentInfo, _> = serde_json::from_str(&att_json) else {
            return webffi_err("invalid attachment JSON");
        };
        let default_caption = match att.kind {
            AttachmentKind::Image => "Image",
            AttachmentKind::Video => "Video",
            AttachmentKind::File => "File",
        };
        let caption = body.unwrap_or_else(|| default_caption.to_string());
        let media_source = if let Some(enc) = att.encrypted.as_ref() {
            let Ok(ef): Result<matrix_sdk::ruma::events::room::EncryptedFile, _> =
                serde_json::from_str(&enc.json)
            else {
                return webffi_err("invalid encrypted file JSON");
            };
            MediaSource::Encrypted(Box::new(ef))
        } else {
            MediaSource::Plain(att.mxc_uri.clone().into())
        };
        let msgtype = match att.kind {
            AttachmentKind::Image => {
                let mut info = ImageInfo::new();
                info.mimetype = att.mime.clone();
                info.size = att.size_bytes.and_then(matrix_sdk::ruma::UInt::new);
                info.width = att.width.map(matrix_sdk::ruma::UInt::from);
                info.height = att.height.map(matrix_sdk::ruma::UInt::from);
                let mut img = ImageMessageEventContent::new(caption.clone(), media_source);
                img.info = Some(Box::new(info));
                MessageType::Image(img)
            }
            AttachmentKind::Video => {
                let mut info = VideoInfo::new();
                info.mimetype = att.mime.clone();
                info.size = att.size_bytes.and_then(matrix_sdk::ruma::UInt::new);
                info.width = att.width.map(matrix_sdk::ruma::UInt::from);
                info.height = att.height.map(matrix_sdk::ruma::UInt::from);
                info.duration = att.duration_ms.map(Duration::from_millis);
                let mut vid = VideoMessageEventContent::new(caption.clone(), media_source);
                vid.info = Some(Box::new(info));
                MessageType::Video(vid)
            }
            AttachmentKind::File => {
                let mut info = matrix_sdk::ruma::events::room::message::FileInfo::new();
                info.mimetype = att.mime.clone();
                info.size = att.size_bytes.and_then(matrix_sdk::ruma::UInt::new);
                let mut file = FileMessageEventContent::new(caption.clone(), media_source);
                file.info = Some(Box::new(info));
                MessageType::File(file)
            }
        };
        let content =
            matrix_sdk::ruma::events::room::message::RoomMessageEventContent::new(msgtype);
        let result = room.send(content).await.map(|_| ());
        webffi_unit(result)
    }

    #[wasm_bindgen(js_name = sendAttachmentBytes)]
    pub async fn send_attachment_bytes(
        &self,
        room_id: String,
        filename: String,
        mime: String,
        data: Vec<u8>,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_not_init();
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return webffi_err("invalid room_id");
        };
        let Some(room) = state.client().get_room(&rid) else {
            return webffi_err("room not found");
        };
        let mime_type: Mime = mime.parse().unwrap_or(mime::APPLICATION_OCTET_STREAM);
        let config = AttachmentConfig::new();
        let result = room
            .send_attachment(&filename, &mime_type, data, config)
            .await
            .map(|_| ());
        webffi_unit(result)
    }

    #[wasm_bindgen(js_name = downloadAttachmentToCacheFile)]
    pub async fn download_attachment_to_cache_file(
        &self,
        att_json: String,
        _filename_hint: Option<String>,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_not_init();
        };
        let Ok(att): Result<AttachmentInfo, _> = serde_json::from_str(&att_json) else {
            return webffi_err("invalid attachment JSON");
        };
        let source = if let Some(enc) = att.encrypted.as_ref() {
            let Ok(ef): Result<matrix_sdk::ruma::events::room::EncryptedFile, _> =
                serde_json::from_str(&enc.json)
            else {
                return webffi_err("invalid encrypted file JSON");
            };
            MediaSource::Encrypted(Box::new(ef))
        } else {
            MediaSource::Plain(att.mxc_uri.clone().into())
        };
        let req = MediaRequestParameters {
            source,
            format: MediaFormat::File,
        };
        match state.client().media().get_media_content(&req, true).await {
            Ok(data) => {
                let b64 = match base64_encode(&data) {
                    Ok(s) => s,
                    Err(e) => {
                        return webffi_err(&format!(
                            "base64 encode failed: {:?}",
                            e.as_string().unwrap_or_default()
                        ));
                    }
                };
                let mime = att
                    .mime
                    .unwrap_or_else(|| "application/octet-stream".to_string());
                let data_uri = format!("data:{};base64,{}", mime, b64);
                webffi_value(Ok::<String, String>(data_uri))
            }
            Err(e) => webffi_err(&format!("media download failed: {}", e)),
        }
    }

    #[wasm_bindgen(js_name = getMediaContent)]
    pub async fn get_media_content(
        &self,
        info_json: String,
        _use_thumbnail: bool,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_not_init();
        };
        let Ok(info): Result<StickerInfo, _> = serde_json::from_str(&info_json) else {
            return webffi_err("invalid sticker JSON");
        };
        let mxc_uri = info.mxc_uri;
        if mxc_uri.is_empty() {
            return webffi_err("missing mxc_uri");
        }
        let source = if let Some(enc) = info.encrypted.as_ref() {
            let Ok(ef): Result<matrix_sdk::ruma::events::room::EncryptedFile, _> =
                serde_json::from_str(&enc.json)
            else {
                return webffi_err("invalid encrypted file JSON");
            };
            MediaSource::Encrypted(Box::new(ef))
        } else {
            MediaSource::Plain(mxc_uri.into())
        };
        let req = MediaRequestParameters {
            source,
            format: MediaFormat::File,
        };
        match state.client().media().get_media_content(&req, true).await {
            Ok(data) => {
                let b64 = match base64_encode(&data) {
                    Ok(s) => s,
                    Err(_) => return webffi_err("base64 encode failed"),
                };
                let mime = info.mime.unwrap_or_else(|| "image/png".to_string());
                JsValue::from_str(&format!("data:{};base64,{}", mime, b64))
            }
            Err(e) => webffi_err(&format!("media download failed: {}", e)),
        }
    }

    #[wasm_bindgen(js_name = thumbnailToCache)]
    pub async fn thumbnail_to_cache(
        &self,
        att_json: String,
        width: u32,
        height: u32,
        _use_crop: bool,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_err("not initialized");
        };
        let Ok(att): Result<AttachmentInfo, _> = serde_json::from_str(&att_json) else {
            return webffi_err("invalid attachment JSON");
        };
        let (mxc_uri, thumbnail_mxc) = if let Some(thumb) = att.thumbnail_mxc_uri.as_ref() {
            (thumb.clone(), true)
        } else {
            (att.mxc_uri.clone(), false)
        };
        let source = if let Some(enc) = if thumbnail_mxc {
            att.thumbnail_encrypted.as_ref()
        } else {
            att.encrypted.as_ref()
        } {
            let Ok(ef): Result<matrix_sdk::ruma::events::room::EncryptedFile, _> =
                serde_json::from_str(&enc.json)
            else {
                return webffi_err("invalid encrypted file JSON");
            };
            MediaSource::Encrypted(Box::new(ef))
        } else {
            MediaSource::Plain(mxc_uri.into())
        };
        let settings = MediaThumbnailSettings::new(width.into(), height.into());
        let req = MediaRequestParameters {
            source,
            format: MediaFormat::Thumbnail(settings),
        };
        match state.client().media().get_media_content(&req, true).await {
            Ok(data) => {
                let b64 = match base64_encode(&data) {
                    Ok(s) => s,
                    Err(_) => return webffi_err("base64 encode failed"),
                };
                let mime = if data.starts_with(&[0x89, b'P', b'N', b'G']) {
                    "image/png"
                } else {
                    "image/jpeg"
                };
                JsValue::from_str(&format!("data:{};base64,{}", mime, b64))
            }
            Err(e) => webffi_err(&format!("thumbnail fetch failed: {}", e)),
        }
    }

    #[wasm_bindgen(js_name = mxcThumbnailToCache)]
    pub async fn mxc_thumbnail_to_cache(
        &self,
        mxc_uri: String,
        width: u32,
        height: u32,
        _crop: bool,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_err("not initialized");
        };
        let uri = matrix_sdk::ruma::OwnedMxcUri::from(mxc_uri);
        let settings = MediaThumbnailSettings::new(width.into(), height.into());
        let req = MediaRequestParameters {
            source: MediaSource::Plain(uri),
            format: MediaFormat::Thumbnail(settings),
        };
        match state.client().media().get_media_content(&req, true).await {
            Ok(data) => {
                let b64 = match base64_encode(&data) {
                    Ok(s) => s,
                    Err(_) => return webffi_err("base64 encode failed"),
                };
                let mime = if data.starts_with(&[0x89, b'P', b'N', b'G']) {
                    "image/png"
                } else {
                    "image/jpeg"
                };
                JsValue::from_str(&format!("data:{};base64,{}", mime, b64))
            }
            Err(e) => webffi_err(&format!("mxc thumbnail failed: {}", e)),
        }
    }

    #[wasm_bindgen(js_name = searchRoom)]
    pub async fn search_room(
        &self,
        _room_id: String,
        _query: String,
        _limit: u32,
        _offset: Option<u32>,
    ) -> JsValue {
        // search_room not supported on wasm
        to_json(&SearchPage {
            hits: vec![],
            next_offset: None,
        })
    }

    #[wasm_bindgen(js_name = setupRecovery)]
    pub async fn setup_recovery(&self) -> JsValue {
        let Some(state) = self.state() else {
            return JsValue::NULL;
        };
        match state.client().encryption().recovery().enable().await {
            Ok(key) => JsValue::from_str(&key),
            Err(_) => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = setKeyBackupEnabled)]
    pub async fn set_key_backup_enabled(&self, enabled: bool) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let backups = state.client().encryption().backups();
        if enabled {
            backups.create().await.is_ok()
        } else {
            backups.disable().await.is_ok()
        }
    }

    #[wasm_bindgen(js_name = startElementCall)]
    pub async fn start_element_call(
        &self,
        room_id: String,
        element_call_url: Option<String>,
        parent_url: Option<String>,
        intent: String,
        language_tag: Option<String>,
        theme: Option<String>,
        observer: JsValue,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return JsValue::NULL;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id.as_str()) else {
            return JsValue::NULL;
        };
        let Some(room) = state.client().get_room(&rid) else {
            return JsValue::NULL;
        };
        let session_id = state.next_sub_id();
        let lang = language_tag
            .as_deref()
            .and_then(|s| LanguageTag::parse(s).ok());
        let Some(element_call_url) = element_call_url else {
            return JsValue::NULL;
        };
        let resolved_parent = parent_url.unwrap_or_else(|| element_call_url.clone());
        let props = VirtualElementCallWidgetProperties {
            element_call_url,
            parent_url: Some(resolved_parent.clone()),
            widget_id: format!("mages-ecall-{}", session_id),
            ..VirtualElementCallWidgetProperties::default()
        };
        let is_dm = room.is_direct().await.unwrap_or(false);
        let widget_intent = match (intent.as_str(), is_dm) {
            ("StartCall", true) => WidgetIntent::StartCallDm,
            ("JoinExisting", true) => WidgetIntent::JoinExistingDm,
            ("StartCall", false) => WidgetIntent::StartCall,
            ("JoinExisting", false) => WidgetIntent::JoinExisting,
            ("StartCallVoiceDm", _) => WidgetIntent::StartCallDmVoice,
            ("JoinExistingVoiceDm", _) => WidgetIntent::JoinExistingDmVoice,
            _ => WidgetIntent::JoinExisting,
        };
        let config = VirtualElementCallWidgetConfig {
            controlled_audio_devices: None,
            preload: Some(false),
            app_prompt: Some(false),
            confine_to_room: Some(true),
            hide_screensharing: Some(false),
            intent: Some(widget_intent),
            ..VirtualElementCallWidgetConfig::default()
        };
        let Ok(settings) = WidgetSettings::new_virtual_element_call_widget(props, config) else {
            return JsValue::NULL;
        };
        let client_props = ClientProperties::new("org.mlm.mages", lang, theme);
        let Ok(url) = settings.generate_webview_url(&room, client_props).await else {
            return JsValue::NULL;
        };
        let widget_base_url = settings.base_url().map(|u| u.to_string());
        let (driver, handle) = WidgetDriver::new(settings);
        let handle_for_recv = handle.clone();
        state.widget_handles.borrow_mut().insert(session_id, handle);

        let cap_provider = crate::ElementCallCapabilitiesProvider {};
        let client2 = state.client().clone();
        let room_str = room_id.clone();
        let (ah, ar) = AbortHandle::new_pair();
        state.widget_driver_subs.borrow_mut().insert(session_id, ah);
        wasm_bindgen_futures::spawn_local(async move {
            let _ = Abortable::new(
                async move {
                    if let Ok(rid) = OwnedRoomId::try_from(room_str.as_str()) {
                        if let Some(room) = client2.get_room(&rid) {
                            let _ = driver.run(room, cap_provider).await;
                        }
                    }
                },
                ar,
            )
            .await;
        });

        if let Some(obs_fn) = observer.dyn_into::<Function>().ok() {
            let widget_obs = JsWidgetObserver::new(obs_fn);
            let (ah_recv, ar_recv) = AbortHandle::new_pair();
            state
                .widget_driver_subs
                .borrow_mut()
                .insert(session_id + 1, ah_recv);
            wasm_bindgen_futures::spawn_local(async move {
                let _ = Abortable::new(
                    async move {
                        while let Some(msg) = handle_for_recv.recv().await {
                            widget_obs.on_to_widget(&msg);
                        }
                    },
                    ar_recv,
                )
                .await;
            });
        }

        to_json(&CallSessionInfo {
            session_id,
            widget_url: url.to_string(),
            widget_base_url,
            parent_url: Some(resolved_parent),
        })
    }

    #[wasm_bindgen(js_name = callWidgetFromWebview)]
    pub fn call_widget_from_webview(&self, session_id: f64, message: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let sid = session_id as u64;
        if let Some(handle) = state.widget_handles.borrow().get(&sid).cloned() {
            wasm_bindgen_futures::spawn_local(async move {
                let _ = handle.send(message).await;
            });
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = stopElementCall)]
    pub fn stop_element_call(&self, session_id: f64) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        let sid = session_id as u64;
        let mut any = false;
        if let Some(h) = state.widget_driver_subs.borrow_mut().remove(&sid) {
            h.abort();
            any = true;
        }
        if let Some(h) = state.widget_recv_subs.borrow_mut().remove(&sid) {
            h.abort();
            any = true;
        }
        state.widget_handles.borrow_mut().remove(&sid);
        any
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

    #[wasm_bindgen(js_name = startDeviceVerification)]
    pub async fn start_device_verification(
        &self,
        target_device_id: String,
        on_event: Function,
    ) -> String {
        let Some(state) = self.state() else {
            return String::new();
        };

        let me = match state.client().user_id() {
            Some(u) => u,
            None => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "No user session".into(),
                    }),
                );
                return String::new();
            }
        };

        let device_id = OwnedDeviceId::from(target_device_id);
        let device = match state.client().encryption().get_device(me, &device_id).await {
            Ok(Some(d)) => d,
            Ok(None) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "Device not found".into(),
                    }),
                );
                return String::new();
            }
            Err(e) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: format!("Failed to get device: {e}"),
                    }),
                );
                return String::new();
            }
        };

        let request = match device.request_verification().await {
            Ok(r) => r,
            Err(e) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: format!("Request verification failed: {e}"),
                    }),
                );
                return String::new();
            }
        };

        let flow_id = request.flow_id().to_owned();

        let client = state.client().clone();
        let fid = flow_id.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let stream = drive_verification_request(request, true).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                let js_val = JsValue::from_str(&json);
                call_js(&on_event, js_val);
                if matches!(event, VerifEvent::Done)
                    || matches!(event, VerifEvent::Cancelled { .. })
                {
                    break;
                }
            }
        });

        flow_id
    }

    #[wasm_bindgen(js_name = startUserVerification)]
    pub async fn start_user_verification(&self, user_id: String, on_event: Function) -> String {
        let Some(state) = self.state() else {
            return String::new();
        };

        let uid = match user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "Invalid user ID".into(),
                    }),
                );
                return String::new();
            }
        };

        let identity = match state.client().encryption().get_user_identity(&uid).await {
            Ok(Some(i)) => i,
            _ => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "User identity not found".into(),
                    }),
                );
                return String::new();
            }
        };

        let request = match identity.request_verification().await {
            Ok(r) => r,
            Err(e) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: format!("Request verification failed: {e}"),
                    }),
                );
                return String::new();
            }
        };

        let flow_id = request.flow_id().to_owned();

        let client = state.client().clone();
        let fid = flow_id.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let stream = drive_verification_request(request, true).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                let js_val = JsValue::from_str(&json);
                call_js(&on_event, js_val);
                if matches!(event, VerifEvent::Done)
                    || matches!(event, VerifEvent::Cancelled { .. })
                {
                    break;
                }
            }
        });

        flow_id
    }

    #[wasm_bindgen(js_name = confirmSas)]
    pub async fn confirm_sas(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let me = match state.client().user_id() {
            Some(u) => u,
            None => return false,
        };

        if let Some(Verification::SasV1(sas)) = state
            .client()
            .encryption()
            .get_verification(me, &flow_id)
            .await
        {
            sas.confirm().await.is_ok()
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = cancelVerification)]
    pub async fn cancel_verification(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let me = match state.client().user_id() {
            Some(u) => u,
            None => return false,
        };

        if let Some(v) = state
            .client()
            .encryption()
            .get_verification(me, &flow_id)
            .await
        {
            match v {
                Verification::SasV1(sas) => sas.cancel().await.is_ok(),
                _ => false,
            }
        } else if let Some(req) = state
            .client()
            .encryption()
            .get_verification_request(me, &flow_id)
            .await
        {
            req.cancel().await.is_ok()
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = cancelVerificationWithUser)]
    pub async fn cancel_verification_with_user(
        &self,
        flow_id: String,
        other_user_id: String,
    ) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let user = match other_user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => return false,
        };

        if let Some(v) = state
            .client()
            .encryption()
            .get_verification(&user, &flow_id)
            .await
        {
            match v {
                Verification::SasV1(sas) => sas.cancel().await.is_ok(),
                _ => false,
            }
        } else if let Some(req) = state
            .client()
            .encryption()
            .get_verification_request(&user, &flow_id)
            .await
        {
            req.cancel().await.is_ok()
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = acceptVerificationRequest)]
    pub async fn accept_verification_request(
        &self,
        flow_id: String,
        other_user_id: String,
    ) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let uid = match other_user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => return false,
        };

        for _ in 0..30 {
            if let Some(req) = state
                .client()
                .encryption()
                .get_verification_request(&uid, &flow_id)
                .await
            {
                return req.accept().await.is_ok();
            }
            sleep(Duration::from_millis(200)).await;
        }
        false
    }

    #[wasm_bindgen(js_name = acceptSas)]
    pub async fn accept_sas(&self, flow_id: String, other_user_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let uid = match other_user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => return false,
        };

        for _ in 0..30 {
            if let Some(verification) = state
                .client()
                .encryption()
                .get_verification(&uid, &flow_id)
                .await
            {
                if let Some(sas) = verification.sas() {
                    return sas.accept().await.is_ok();
                }
            }
            sleep(Duration::from_millis(200)).await;
        }
        false
    }

    #[wasm_bindgen(js_name = acceptAndObserveVerification)]
    pub async fn accept_and_observe_verification(
        &self,
        flow_id: String,
        other_user_id: String,
        on_event: Function,
    ) -> bool {
        let Some(state) = self.state() else {
            return false;
        };

        let uid = match other_user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "Invalid user ID".into(),
                    }),
                );
                return false;
            }
        };

        let request = match state
            .client()
            .encryption()
            .get_verification_request(&uid, &flow_id)
            .await
        {
            Some(req) => req,
            None => {
                call_js(
                    &on_event,
                    to_json(&VerifEvent::Error {
                        message: "Verification request not found".into(),
                    }),
                );
                return false;
            }
        };

        wasm_bindgen_futures::spawn_local(async move {
            let stream = drive_incoming_verification(request).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                call_js(&on_event, JsValue::from_str(&json));
                if matches!(event, VerifEvent::Done)
                    || matches!(event, VerifEvent::Cancelled { .. })
                {
                    break;
                }
            }
        });

        true
    }

    #[wasm_bindgen(js_name = isReactionNotificationsEnabled)]
    pub async fn is_reaction_notifications_enabled(&self) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(s.core.is_reaction_notifications_enabled().await)
    }

    #[wasm_bindgen(js_name = setReactionNotificationsEnabled)]
    pub async fn set_reaction_notifications_enabled(&self, enabled: bool) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.set_reaction_notifications_enabled(enabled).await)
    }

    #[wasm_bindgen(js_name = isPushRuleEnabled)]
    pub async fn is_push_rule_enabled(&self, kind: String, rule_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let ffi_kind = match kind.as_str() {
            "Override" => FfiPushRuleKind::Override,
            "Underride" => FfiPushRuleKind::Underride,
            "Sender" => FfiPushRuleKind::Sender,
            "Room" => FfiPushRuleKind::Room,
            "Content" => FfiPushRuleKind::Content,
            _ => return webffi_err("invalid push rule kind"),
        };
        webffi_value(s.core.is_push_rule_enabled(ffi_kind, rule_id).await)
    }

    #[wasm_bindgen(js_name = setPushRuleEnabled)]
    pub async fn set_push_rule_enabled(
        &self,
        kind: String,
        rule_id: String,
        enabled: bool,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let ffi_kind = match kind.as_str() {
            "Override" => FfiPushRuleKind::Override,
            "Underride" => FfiPushRuleKind::Underride,
            "Sender" => FfiPushRuleKind::Sender,
            "Room" => FfiPushRuleKind::Room,
            "Content" => FfiPushRuleKind::Content,
            _ => return webffi_err("invalid push rule kind"),
        };
        webffi_unit(s.core.set_push_rule_enabled(ffi_kind, rule_id, enabled).await)
    }

    #[wasm_bindgen(js_name = getDefaultRoomNotificationMode)]
    pub async fn get_default_room_notification_mode(
        &self,
        is_encrypted: bool,
        is_one_to_one: bool,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(
            s.core
                .get_default_room_notification_mode(is_encrypted, is_one_to_one)
                .await,
        )
    }

    #[wasm_bindgen(js_name = setDefaultRoomNotificationMode)]
    pub async fn set_default_room_notification_mode(
        &self,
        is_encrypted: bool,
        is_one_to_one: bool,
        mode: String,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let m = match mode.as_str() {
            "AllMessages" => FfiRoomNotificationMode::AllMessages,
            "MentionsAndKeywordsOnly" => FfiRoomNotificationMode::MentionsAndKeywordsOnly,
            "Mute" => FfiRoomNotificationMode::Mute,
            _ => return webffi_err("invalid notification mode"),
        };
        webffi_unit(
            s.core
                .set_default_room_notification_mode(is_encrypted, is_one_to_one, m)
                .await,
        )
    }
}
