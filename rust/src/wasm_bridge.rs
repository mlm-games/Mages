use crate::core::{CoreClient, TimelineManager, map_send_queue_update, room_list_membership};
use crate::js_observer_json;
use crate::js_observer_noargs;
use crate::types::*;
use crate::verification_flow::{
    VerifEvent, drive_and_emit, drive_incoming_verification, drive_verification_request,
    error_json,
};
use crate::wasm_delegate;
use crate::wasm_subscribe;
use crate::wasm_unobserve;
use crate::webffi_bool;
use crate::{
    latest_room_event_for, mages_client_metadata,
    strip_matrix_path,
};
use crate::{
    webffi_err, webffi_not_init, webffi_option, webffi_unit, webffi_value,
};

use futures_util::StreamExt;
use futures_util::future::{AbortHandle, Abortable};
use js_sys::{Array, Function};

use matrix_sdk::authentication::oauth::registration::language_tags::LanguageTag;
use matrix_sdk::utils::UrlOrQuery;

use matrix_sdk::ruma::events::room::{MediaSource, message::MessageType};

use matrix_sdk::ruma::events::{
    key::verification::request::ToDeviceKeyVerificationRequestEvent,
    room::message::SyncRoomMessageEvent,
};

use matrix_sdk::ruma::{OwnedEventId, OwnedRoomId};

use matrix_sdk::widget::{
    ClientProperties, Intent as WidgetIntent, VirtualElementCallWidgetConfig,
    VirtualElementCallWidgetProperties, WidgetDriver, WidgetDriverHandle, WidgetSettings,
};

use matrix_sdk::{
    Client as SdkClient,
    attachment::AttachmentConfig,
    authentication::AuthSession,
    encryption::{BackupDownloadStrategy, EncryptionSettings},
    media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings},
};
use mime::Mime;

use matrix_sdk_ui::{
    eyeball_im::Vector,
    notification_client::{NotificationClient, NotificationProcessSetup, NotificationStatus},
    room_list_service::filters,
    sync_service::{State, SyncService},
    timeline::RoomExt,
};

use serde_json;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

use wasm_bindgen::JsValue;
use wasm_bindgen::prelude::*;

use matrix_sdk::authentication::matrix::MatrixSession;
use matrix_sdk::authentication::oauth::{ClientId, OAuthSession, UserSession};
use matrix_sdk::{SessionMeta, SessionTokens};

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}

pub fn to_json<T: serde::Serialize>(v: &T) -> JsValue {
    let serializer = serde_wasm_bindgen::Serializer::new().serialize_maps_as_objects(true);
    v.serialize(&serializer)
        .unwrap_or_else(|e| JsValue::from_str(&format!("{{\"error\":\"{e}\"}}")))
}

fn decode_string_array(value: JsValue, name: &str) -> Result<Vec<String>, String> {
    if !Array::is_array(&value) {
        return Err(format!("{name} must be an array of strings"));
    }

    let array: Array = Array::from(&value);
    array
        .iter()
        .enumerate()
        .map(|(index, value)| {
            value
                .as_string()
                .ok_or_else(|| format!("{name}[{index}] must be a string"))
        })
        .collect()
}

fn decode_u64_array(value: JsValue) -> Result<Vec<u64>, String> {
    if !Array::is_array(&value) {
        return Err("expected an array of numbers".into());
    }

    let array: Array = Array::from(&value);
    array
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let number = value
                .as_f64()
                .ok_or_else(|| format!("range[{index}] must be a number"))?;
            if !number.is_finite() || number < 0.0 || number.fract() != 0.0 {
                return Err(format!("range[{index}] must be a non-negative integer"));
            }
            if number >= u64::MAX as f64 {
                return Err(format!("range[{index}] is too large"));
            }
            Ok(number as u64)
        })
        .collect()
}

#[wasm_bindgen(js_name = base64Encode)]
pub fn base64_encode(data: &[u8]) -> Result<String, JsValue> {
    let window = web_sys::window().ok_or_else(|| JsValue::from_str("no window"))?;
    let binary_string: String = data.iter().map(|&b| b as char).collect();
    window.btoa(&binary_string)
}

fn sniff_image_mime(data: &[u8]) -> &'static str {
    if data.starts_with(&[0x89, b'P', b'N', b'G']) {
        "image/png"
    } else if data.starts_with(b"GIF87a") || data.starts_with(b"GIF89a") {
        "image/gif"
    } else if data.len() >= 12 && data.starts_with(b"RIFF") && &data[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "image/jpeg"
    }
}

async fn media_data_url<F, M>(
    client: &SdkClient,
    req: &MediaRequestParameters,
    mime_of: F,
    label: &str,
) -> Result<String, JsValue>
where
    F: FnOnce(&[u8]) -> M,
    M: Into<String>,
{
    let data = client
        .media()
        .get_media_content(req, true)
        .await
        .map_err(|e| webffi_err(&format!("{label} failed: {e}")))?;
    let b64 = base64_encode(&data).map_err(|e| {
        webffi_err(&format!(
            "base64 encode failed: {:?}",
            e.as_string().unwrap_or_default()
        ))
    })?;
    let mime: String = mime_of(&data).into();
    Ok(format!("data:{mime};base64,{b64}"))
}

fn call_js(f: &Function, arg: JsValue) {
    let _ = f.call1(&JsValue::NULL, &arg);
}
fn call_js0(f: &Function) {
    let _ = f.call0(&JsValue::NULL);
}

fn emit_verif_err(on_event: &Function, message: String) {
    call_js(on_event, JsValue::from_str(&error_json(message)));
}

fn spawn_verif_drive<F, S>(build: F, on_event: Function)
where
    F: std::future::Future<Output = S> + 'static,
    S: futures_util::Stream<Item = VerifEvent> + 'static,
{
    wasm_bindgen_futures::spawn_local(async move {
        let stream = build.await;
        drive_and_emit(stream, |json| {
            call_js(&on_event, JsValue::from_str(json));
        })
        .await;
    });
}

js_observer_json!(JsConnectionObserver: ConnectionObserver::on_connection_change, state: ConnectionState);
js_observer_json!(JsSyncObserver: SyncObserver::on_state, status: SyncStatus);
js_observer_json!(JsSendObserver: SendObserver::on_update, update: SendUpdate);
js_observer_noargs!(JsReceiptsObserver: ReceiptsObserver::on_changed);
js_observer_json!(JsTypingObserver: TypingObserver::on_update, names: Vec<String>);
js_observer_json!(JsCallObserver: CallObserver::on_invite, invite: CallInvite);
js_observer_json!(JsRoomCallStateObserver: RoomCallStateObserver::on_update, state: RoomCallState);
js_observer_json!(JsRoomInfoObserver: RoomInfoObserver::on_update, snapshot: RoomInfoSnapshot);

struct JsCallDeclineObserver(Function);
impl CallDeclineObserver for JsCallDeclineObserver {
    fn on_decline(&self, decliner_user_id: String) {
        call_js(&self.0, JsValue::from_str(&decliner_user_id));
    }
}
unsafe impl Send for JsCallDeclineObserver {}
unsafe impl Sync for JsCallDeclineObserver {}
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

fn wasm_session_key(store_name: &str) -> String {
    format!("mages_session_{store_name}")
}

fn load_wasm_session(store_name: &str) -> Option<SessionInfo> {
    let storage = web_sys::window()?.local_storage().ok()??;
    let raw = storage.get_item(&wasm_session_key(store_name)).ok()??;
    serde_json::from_str(&raw).ok()
}

fn save_wasm_session(store_name: &str, session: &SessionInfo) {
    let Some(storage) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) else {
        return;
    };
    let Ok(raw) = serde_json::to_string(session) else {
        return;
    };
    let _ = storage.set_item(&wasm_session_key(store_name), &raw);
}

fn update_wasm_session(store_name: &str, session: &SessionInfo) {
    save_wasm_session(store_name, session);
}

fn clear_wasm_session(store_name: &str) {
    let Some(storage) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) else {
        return;
    };
    let _ = storage.remove_item(&wasm_session_key(store_name));
}

/// Best-effort deletion of this account's IndexedDB databases.
async fn delete_wasm_databases(store_name: &str) {
    let Some(window) = web_sys::window() else {
        return;
    };
    let Ok(Some(factory)) = window.indexed_db() else {
        return;
    };
    for name in [
        store_name.to_owned(),
        format!("{store_name}::matrix-sdk-state"),
        format!("{store_name}::matrix-sdk-crypto"),
        format!("{store_name}::matrix-sdk-crypto-meta"),
        format!("{store_name}::event_cache"),
        format!("{store_name}::media"),
    ] {
        let _ = factory.delete_database(&name);
    }
}

struct WasmAsyncState {
    core: Rc<CoreClient>,
    store_name: String,
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
    widget_handles: RefCell<HashMap<u64, std::rc::Rc<WidgetDriverHandle>>>,
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
        self.core.ensure_sync_service().await;
        self.core
            .sync_service
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .cloned()
    }

    fn persist_session(&self) {
        match self.client().session() {
            Some(AuthSession::Matrix(sess)) => {
                save_wasm_session(
                    &self.store_name,
                    &SessionInfo {
                        auth_api: "matrix".into(),
                        client_id: None,
                        user_id: sess.meta.user_id.to_string(),
                        device_id: sess.meta.device_id.to_string(),
                        access_token: sess.tokens.access_token,
                        refresh_token: sess.tokens.refresh_token,
                        homeserver: self.client().homeserver().to_string(),
                        is_token_valid: true,
                    },
                );
            }
            Some(AuthSession::OAuth(sess)) => {
                let sess = *sess;
                save_wasm_session(
                    &self.store_name,
                    &SessionInfo {
                        auth_api: "oauth".into(),
                        client_id: Some(sess.client_id.to_string()),
                        user_id: sess.user.meta.user_id.to_string(),
                        device_id: sess.user.meta.device_id.to_string(),
                        access_token: sess.user.tokens.access_token,
                        refresh_token: sess.user.tokens.refresh_token,
                        homeserver: self.client().homeserver().to_string(),
                        is_token_valid: true,
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
            let mut errors_rx = state.client().send_queue().subscribe_errors();
            let mut attempts: HashMap<String, u32> = HashMap::new();
            loop {
                tokio::select! {
                    upd = rx.recv() => {
                        let upd = match upd {
                            Ok(u) => u,
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                                tracing::warn!(skipped, "send-queue updates lagged; retry state may be stale");
                                continue;
                            }
                            Err(e) => {
                                tracing::warn!("send-queue subscription ended: {e:?}");
                                break;
                            }
                        };
                        let rid = upd.room_id.to_string();
                        if let Some(u) = map_send_queue_update(&rid, upd.update, &mut attempts) {
                            state.dispatch_send_update(&u);
                        }
                    }
                    err = errors_rx.recv() => {
                        if let Ok(err) = err {
                            tracing::warn!(
                                "Send queue error for room {} (recoverable={}): {:?}",
                                err.room_id, err.is_recoverable, err.error
                            );
                            state.dispatch_send_update(&SendUpdate {
                                room_id: err.room_id.to_string(),
                                txn_id: String::new(),
                                attempts: 0,
                                state: SendState::Failed,
                                event_id: None,
                                error: Some(format!("Queue disabled: {:?}", err.error)),
                            });
                        }
                    }
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

wasm_delegate! { webffi_bool;
    "isSpace"              => is_space(room_id: String);
    "isUserIgnored"        => is_user_ignored(user_id: String);
}

wasm_delegate! { |b: bool| webffi_bool(Ok::<_, crate::FfiError>(b));
    "retryByTxn"           => retry_by_txn(room_id: String, txn_id: String);
    "cancelByTxn"          => cancel_by_txn(room_id: String, txn_id: String);
}

wasm_delegate! { webffi_unit;
    "markRead"             => mark_read(room_id: String, send_public_receipt: bool);
    "markReadAt"           => mark_read_at(room_id: String, event_id: String, send_public_receipt: bool);
    "markFullyReadAt"      => mark_fully_read_at(room_id: String, event_id: String, send_public_receipt: bool);
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
    "knock"                => knock(id_or_alias: String, via: Vec<String>);
    "react"                => react(room_id: String, event_id: String, emoji: String, shortcode: Option<String>);
    "spaceInviteUser"      => space_invite_user(space_id: String, user_id: String);
    "redact"               => redact(room_id: String, event_id: String, reason: Option<String>);
}

wasm_delegate! { webffi_bool;
    "canUserBan"           => can_user_ban(room_id: String, user_id: String);
    "canUserInvite"        => can_user_invite(room_id: String, user_id: String);
    "canUserRedactOther"   => can_user_redact_other(room_id: String, user_id: String);
    "canSetProfileFields"  => can_set_profile_fields();
}

wasm_delegate! { webffi_unit;
    "ignoreUser"       => ignore_user(user_id: String);
    "unignoreUser"     => unignore_user(user_id: String);
    "leaveRoom"        => leave_room(room_id: String);
    "spaceAddChild"    => space_add_child(space_id: String, child_room_id: String, order: Option<String>, suggested: Option<bool>);
    "spaceRemoveChild" => space_remove_child(space_id: String, child_room_id: String);
    "reportRoom"           => report_room(room_id: String, reason: Option<String>);
    "sendPollEnd"         => send_poll_end(room_id: String, poll_event_id: String);
    "stopLiveLocation"     => stop_live_location(room_id: String);
    "sendLiveLocation"     => send_live_location(room_id: String, geo_uri: String);
    "sendStaticLocation"   => send_static_location(room_id: String, geo_uri: String, body: Option<String>);
    "acceptKnockRequest"   => accept_knock_request(room_id: String, user_id: String);
    "declineKnockRequest"  => decline_knock_request(room_id: String, user_id: String, reason: Option<String>);
    "acceptInvite"         => accept_invite(room_id: String);
    "declineCall"          => decline_call(room_id: String, notification_event_id: String);
    "editCaption"          => edit_caption(room_id: String, target_event_id: String, caption: Option<String>, formatted_caption: Option<String>);
    "setDisplayName"       => set_display_name(name: Option<String>);
    "removeAvatar"         => remove_avatar();
    "setInviteBlocked"     => set_invite_blocked(blocked: bool);
    "setProfileField"      => set_profile_field(name: String, value: String);
    "deleteProfileField"   => delete_profile_field(name: String);
}

wasm_delegate! { |r| to_json(&r);
    "rooms"             => rooms()                                                or Vec::<RoomSummary>::new();
    "recentEvents"      => recent_events(room_id: String, limit: u32)             or Vec::<MessageEvent>::new();
    "ownLastRead"       => own_last_read(room_id: String)                         or OwnReceipt { event_id: None, ts_ms: None };
    "reactionsForEvent" => reactions_for_event(room_id: String, event_id: String)  or Vec::<ReactionSummary>::new();
    "mySpaces"          => my_spaces()                                             or Vec::<SpaceInfo>::new();
}

wasm_delegate! { |r: Result<Vec<SpaceParentInfo>, crate::FfiError>| to_json(&r.unwrap_or_default());
    "roomParentSpaces"  => room_parent_spaces(room_id: String)                     or Ok(Vec::<SpaceParentInfo>::new());
}

wasm_delegate! { |r: Result<Vec<SpaceSection>, crate::FfiError>| to_json(&r.unwrap_or_default());
    "listSections"      => list_sections()                                          or Ok(Vec::<SpaceSection>::new());
}

wasm_delegate! { |r: Result<SpaceSection, crate::FfiError>| to_json(&r.unwrap_or_else(|_| SpaceSection {
        tag: String::new(), name: String::new(), space_id: None,
    }));
    "createSection"     => create_section(name: String, space_id: String)           or Ok(SpaceSection {
        tag: String::new(), name: String::new(), space_id: None,
    });
}

wasm_delegate! { webffi_value;
    "roomPowerLevels"  => room_power_levels(room_id: String);
    "getPresence"      => get_presence(user_id: String);
    "roomPreview"      => room_preview(id_or_alias: String, via: Vec<String>);
    "forwardEvent"     => forward_event(source_room_id: String, event_id: String, target_room_ids: Vec<String>);
    "spaceHierarchy"   => space_hierarchy(space_id: String, from: Option<String>, limit: u32, max_depth: Option<u32>, suggested_only: bool);
    "renameSection"    => rename_section(tag: String, name: String);
    "deleteSection"    => delete_section(tag: String);
    "moveSection"      => move_section(tag: String, index: u32);
    "setRoomSection"   => set_room_section(room_id: String, tag: Option<String>);
    "threadReplies"    => thread_replies(room_id: String, root_event_id: String, from: Option<String>, limit: u32, forward: bool);
    "threadSummary"    => thread_summary(room_id: String, root_event_id: String, per_page: u32, max_pages: u32);
    "listMembers"      => list_members(room_id: String);
    "listBannedMembers" => list_banned_members(room_id: String);
    "listInvited"      => list_invited();
    "ignoredUsers"     => ignored_users();
    "roomJoinRuleAllowList" => room_join_rule_allow_list(room_id: String);
    "listImagePacks"   => list_image_packs(room_id: String);
    "listAllImagePacks" => list_all_image_packs(refresh: bool);
    "sendStickerMxc"   => send_sticker_mxc(room_id: String, mxc_url: String, body: String, info_json: Option<String>, thread_root_event_id: Option<String>);
    "ownProfile"       => own_profile();
    "ownProfileFields" => own_profile_fields();
    "mutualRooms"      => mutual_rooms(user_id: String);
}

wasm_delegate! { webffi_option;
    "roomUnreadStats"  => room_unread_stats(room_id: String);
    "roomCallState"    => room_call_state(room_id: String);
    "roomInfoSnapshot" => room_info_snapshot(room_id: String);
    "roomSuccessor"    => room_successor(room_id: String);
    "roomPredecessor"  => room_predecessor(room_id: String);
    "eventDetails"     => event_details(room_id: String, event_id: String);
    "inviteBlocked"    => invite_blocked();
    "getLinkPreview"   => get_link_preview(url: String);
}

wasm_delegate! { webffi_unit;
    "sendMessage"        => send_message(room_id: String, body: String, formatted_body: Option<String>);
    "reply"              => reply(room_id: String, in_reply_to: String, body: String, formatted_body: Option<String>);
    "edit"               => edit(room_id: String, target_event_id: String, new_body: String, formatted_body: Option<String>);
    "setImagePackEnabled" => set_image_pack_enabled(room_id: String, state_key: String, enabled: bool);
    "removeImagePack"    => remove_image_pack(room_id: String, state_key: String);
    "recordEmojiUse"     => record_emoji_use(emoji: String);
    "isEventReadBy"      => is_event_read_by(room_id: String, event_id: String, user_id: String);
    "sendQueueSetEnabled" => send_queue_set_enabled(enabled: bool);
    "setMarkUnread"      => set_mark_unread(room_id: String, unread: bool);
    "sendThreadText"     => send_thread_text(room_id: String, root_event_id: String, body: String, reply_to_event_id: Option<String>, latest_event_id: Option<String>, formatted_body: Option<String>);
    "setReactionNotificationsEnabled" => set_reaction_notifications_enabled(enabled: bool);
}

wasm_delegate! { webffi_value;
    "dmPeerUserId"       => dm_peer_user_id(room_id: String);
    "ensureDm"           => ensure_dm(user_id: String);
    "ensureDmIfAllowed"  => ensure_dm_if_allowed(room_id: String, user_id: String);
    "resolveRoomId"      => resolve_room_id(id_or_alias: String);
    "mediaPreviewConfig" => media_preview_config();
    "canEditImagePacks"  => can_edit_image_packs(room_id: String);
    "saveImagePack"      => save_image_pack(room_id: String, write_json: String);
    "suggestImageShortcodes" => suggest_image_shortcodes(bases: Vec<String>, taken: Vec<String>);
    "uploadPackImageBytes" => upload_pack_image(bytes: Vec<u8>, mime: String);
    "setAvatarBytes"     => set_avatar(bytes: Vec<u8>, mime: String);
    "uploadBytes"        => upload_bytes(bytes: Vec<u8>, mime: String);
    "recentEmoji"        => recent_emoji();
    "isReactionNotificationsEnabled" => is_reaction_notifications_enabled();
    "markRoomSeenLatest" => mark_room_seen_latest(room_id: String, send_public_receipt: bool);
}

wasm_delegate! { webffi_option;
    "roomTags"           => room_tags(room_id: String);
    "roomNotificationMode" => room_notification_mode(room_id: String);
}

wasm_delegate! { |r| to_json(&r);
    "spaceUnreadCounts"  => space_unread_counts();
}

wasm_delegate! { |r| to_json(&r);
    "reactionsBatch"     => reactions_batch(room_id: String, event_ids: Vec<String>) or HashMap::<String, Vec<ReactionSummary>>::new();
    "roomAliases"        => room_aliases(room_id: String) or Vec::<String>::new();
}

wasm_unobserve! {
    "unobserveTimeline"          => unobserve_timeline(timeline_subs);
    "unobserveTyping"            => unobserve_typing(typing_subs);
    "unobserveRoomCallState"     => unobserve_room_call_state(call_subs);
    "unobserveRoomInfo"          => unobserve_room_info(call_subs);
    "unobserveCallDecline"       => unobserve_call_decline(call_subs);
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
        _proxy: Option<String>,
        enable_share_history_on_invite: Option<bool>,
    ) -> Result<WasmClient, JsValue> {
        let raw = homeserver_url.trim();
        let server_name_or_url = if let Ok(url) = matrix_sdk::reqwest::Url::parse(raw) {
            strip_matrix_path(url).to_string()
        } else {
            raw.to_owned()
        };
        let store_name = account_id
            .as_ref()
            .map(|id| format!("mages_store_{id}"))
            .unwrap_or_else(|| "mages_store".to_owned());
        let use_direct_homeserver_url = load_wasm_session(&store_name).is_some();
        let mut builder = if use_direct_homeserver_url {
            SdkClient::builder().homeserver_url(server_name_or_url)
        } else {
            SdkClient::builder().server_name_or_homeserver_url(server_name_or_url)
        };

        if enable_share_history_on_invite.unwrap_or(true) {
            builder = builder.with_enable_share_history_on_invite(true);
        } else {
            builder = builder.with_enable_share_history_on_invite(false);
        }

        let client = builder
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
        if let Some(mut info) = load_wasm_session(&store_name) {
            if let Ok(user_id) = info.user_id.parse() {
                let meta = SessionMeta {
                    user_id,
                    device_id: info.device_id.clone().into(),
                };
                let tokens = SessionTokens {
                    access_token: info.access_token.clone(),
                    refresh_token: info.refresh_token.clone(),
                };
                if info.auth_api == "oauth" {
                    if info.client_id.is_none() {
                        web_sys::console::warn_1(&JsValue::from_str(&format!(
                            "Stored OAuth session for {} is missing client_id. Removing invalid session and requiring re-login",
                            info.user_id
                        )));
                        clear_wasm_session(&store_name);
                    } else if let Some(cid) = info.client_id.clone() {
                        let result = client
                            .restore_session(OAuthSession {
                                client_id: ClientId::new(cid),
                                user: UserSession { meta, tokens },
                            })
                            .await;
                        if let Err(e) = result {
                            // Mirror native classification: hard auth failures drop
                            // the session instead of retrying a dead token forever.
                            if crate::is_hard_auth_error(&e) {
                                clear_wasm_session(&store_name);
                            }
                        }
                    }
                } else {
                    let result = client.restore_session(MatrixSession { meta, tokens }).await;
                    if let Err(e) = result {
                        if crate::is_hard_auth_error(&e) {
                            info.is_token_valid = false;
                            save_wasm_session(&store_name, &info);
                        }
                    }
                }
            }
        }
        let core = Rc::new(CoreClient::new(client).await);
        let state = Rc::new(WasmAsyncState {
            core,
            store_name,
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
        delete_wasm_databases(&state.store_name).await;
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
                Err(e) => {
                    tracing::warn!("get_login_types failed, assuming no SSO/password flows: {e:?}");
                    (false, false)
                },
            };
        let homeserver_url = state.client().homeserver().to_string();
        to_json(&HomeserverLoginDetails {
            homeserver_url,
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
            Err(e) => {
                tracing::warn!("oauth callback is not a URL, treating as raw query: {e:?}");
                UrlOrQuery::Query(callback_url_or_query)
            }
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
    pub async fn message_action_state(
        &self,
        room_id: String,
        event_id: String,
        sender_user_id: String,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        match s
            .core
            .message_action_state(room_id, event_id, sender_user_id)
            .await
        {
            Ok(state) => to_json(&state),
            _ => JsValue::NULL,
        }
    }

    #[wasm_bindgen(js_name = joinByIdOrAlias)]
    pub async fn join_by_id_or_alias(
        &self,
        id_or_alias: String,
        via: Option<Vec<String>>,
    ) -> Result<(), String> {
        let Some(s) = self.state() else {
            return Err("not initialized".into());
        };
        s.core
            .join_by_id_or_alias(id_or_alias, via.unwrap_or_default())
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

    #[wasm_bindgen(js_name = applySyncPresence)]
    pub async fn apply_sync_presence(&self, presence: String) -> JsValue {
        let p = match presence.as_str() {
            "Online" => Presence::Online,
            "Offline" => Presence::Offline,
            "Unavailable" => Presence::Unavailable,
            _ => return webffi_err("invalid presence state"),
        };
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        s.core.apply_sync_presence(p).await;
        JsValue::UNDEFINED
    }

    #[wasm_bindgen(js_name = setMediaPreviewConfig)]
    pub async fn set_media_preview_config(&self, previews: String) -> JsValue {
        let mode = match previews.as_str() {
            "On" => MediaPreviewMode::On,
            "Private" => MediaPreviewMode::Private,
            "Off" => MediaPreviewMode::Off,
            _ => return webffi_err("invalid media preview mode"),
        };
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_unit(s.core.set_media_preview_config(mode).await)
    }

    #[wasm_bindgen(js_name = startLiveLocation)]
    pub async fn start_live_location(&self, room_id: String, duration_ms: f64) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        webffi_value(
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

            if let Some(svc) = s.ensure_sync_service().await {
                let rls = svc.room_list_service();
                rls.set_room_subscriptions(&[rid.as_ref()]).await;
                let _ = svc.start().await;
            }

            crate::observe::drive_timeline(tl, &rid, &me, obs).await;
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
                                    if u {
                                        let unread_filter: filters::BoxedFilterFn = Box::new(
                                            |room: &RoomListItem| -> bool {
                                                let receipts = room.read_receipts();
                                                receipts.num_unread > 0 || room.is_marked_unread()
                                            },
                                        );
                                        controller.set_filter(Box::new(filters::new_filter_all(vec![Box::new(filters::new_filter_non_left()), unread_filter])));
                                    }
                                    else { controller.set_filter(Box::new(filters::new_filter_non_left())); }
                                }
                                RoomListCmd::UpdateVisibleRange((range, threshold)) => {
                                    let total_items = items.len();
                                    if let Some(&position) = range.last() {
                                        let threshold_idx = total_items.saturating_sub(threshold);
                                        if total_items > 0 && position as usize >= threshold_idx {
                                            let _ = controller.add_one_page();
                                        }
                                    }
                                }
                            }
                        }
                        Some(diffs) = stream.next() => {
                            if CoreClient::apply_vector_diff(&mut items, diffs) {
                                let snapshot = s.core.build_room_list_snapshot(&items).await;
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
            s.core.drive_typing(&rid, obs).await;
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
            s.core.drive_receipts(&rid, obs).await;
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
            s.core.drive_live_location(&rid, obs).await;
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
            s.core.drive_call_inbox(obs).await;
        })
    }

    #[wasm_bindgen(js_name = observeRoomCallState)]
    pub fn observe_room_call_state(&self, room_id: String, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            tracing::warn!("observe_room_call_state: invalid room id");
            return 0.0;
        };
        let obs: Arc<dyn RoomCallStateObserver> = Arc::new(JsRoomCallStateObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, call_subs, async move {
            s.core.drive_room_call_state(&rid, obs).await;
        })
    }

    #[wasm_bindgen(js_name = observeRoomInfo)]
    pub fn observe_room_info(&self, room_id: String, on_update: Function) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            tracing::warn!("observe_room_info: invalid room id");
            return 0.0;
        };
        let obs: Arc<dyn RoomInfoObserver> = Arc::new(JsRoomInfoObserver(on_update));
        let s = state.clone();
        wasm_subscribe!(state, call_subs, async move {
            s.core.drive_room_info(&rid, obs).await;
        })
    }

    #[wasm_bindgen(js_name = observeCallDecline)]
    pub fn observe_call_decline(
        &self,
        room_id: String,
        notification_event_id: String,
        on_decline: Function,
    ) -> f64 {
        let Some(state) = self.state() else {
            return 0.0;
        };
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            tracing::warn!("observe_call_decline: invalid room id");
            return 0.0;
        };
        let Ok(eid) = matrix_sdk::ruma::OwnedEventId::try_from(notification_event_id) else {
            tracing::warn!("observe_call_decline: invalid notification event id");
            return 0.0;
        };
        let obs: Arc<dyn CallDeclineObserver> = Arc::new(JsCallDeclineObserver(on_decline));
        let s = state.clone();
        wasm_subscribe!(state, call_subs, async move {
            s.core.drive_call_decline(&rid, &eid, obs).await;
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
            s.core.start_verification_inbox(&*obs).await;
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
            s.core.drive_recovery_state(obs).await;
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
            s.core.drive_backup_state(obs).await;
        })
    }

    #[wasm_bindgen(js_name = setPinnedEvents)]
    pub async fn set_pinned_events(
        &self,
        room_id: String,
        #[wasm_bindgen(unchecked_param_type = "string[]")] event_ids: JsValue,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let event_ids = match decode_string_array(event_ids, "event_ids") {
            Ok(event_ids) => event_ids,
            Err(error) => return webffi_err(&error),
        };
        webffi_unit(s.core.set_pinned_events(room_id, event_ids).await)
    }

    #[wasm_bindgen(js_name = getPinnedEvents)]
    pub async fn get_pinned_events(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.get_pinned_events(room_id).await {
            Some(ids) => to_json(&ids),
            None => JsValue::NULL,
        }
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
            Err(e) => {
                tracing::warn!("search_users failed: {e:?}");
                to_json(&Vec::<DirectoryUser>::new())
            }
        }
    }

    #[wasm_bindgen(js_name = getUserProfile)]
    pub async fn get_user_profile(&self, user_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.get_user_profile(user_id).await {
            Ok(v) => to_json(&v),
            Err(e) => {
                tracing::warn!("get_user_profile failed: {e:?}");
                JsValue::NULL
            }
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
            Err(e) => {
                tracing::warn!("public_rooms failed: {e:?}");
                JsValue::NULL
            }
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
            Err(e) => {
                tracing::warn!("create_room failed: {e:?}");
                JsValue::NULL
            }
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
            Err(e) => {
                tracing::warn!("create_space failed: {e:?}");
                JsValue::NULL
            }
        }
    }

    #[wasm_bindgen(js_name = roomDirectoryVisibility)]
    pub async fn room_directory_visibility(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_directory_visibility(room_id).await {
            Ok(v) => to_json(&v),
            Err(e) => {
                tracing::warn!("room_directory_visibility failed: {e:?}");
                JsValue::NULL
            }
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

    #[wasm_bindgen(js_name = roomJoinRule)]
    pub async fn room_join_rule(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_join_rule(room_id).await {
            Ok(v) => to_json(&v),
            Err(e) => {
                tracing::warn!("room_join_rule failed: {e:?}");
                JsValue::NULL
            }
        }
    }

    #[wasm_bindgen(js_name = setRoomJoinRule)]
    pub async fn set_room_join_rule(
        &self,
        room_id: String,
        rule: String,
        allowed_room_ids: Option<Vec<String>>,
    ) -> bool {
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
        s.core
            .set_room_join_rule(room_id, r, allowed_room_ids.unwrap_or_default())
            .await
            .is_ok()
    }

    #[wasm_bindgen(js_name = roomHistoryVisibility)]
    pub async fn room_history_visibility(&self, room_id: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.room_history_visibility(room_id).await {
            Ok(v) => to_json(&v),
            Err(e) => {
                tracing::warn!("room_history_visibility failed: {e:?}");
                JsValue::NULL
            }
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
            Err(e) => {
                tracing::warn!("send_poll_start failed: {e:?}");
                JsValue::NULL
            }
        }
    }

    #[wasm_bindgen(js_name = editPoll)]
    pub async fn edit_poll(
        &self,
        room_id: String,
        poll_event_id: String,
        question: String,
        answers: Vec<String>,
        kind: String,
        max_selections: u32,
    ) -> bool {
        let Some(s) = self.state() else {
            return false;
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
        s.core.edit_poll(room_id, poll_event_id, def).await.is_ok()
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

    #[wasm_bindgen(js_name = roomInviter)]
    pub async fn room_inviter(&self, room_id: String) -> Option<String> {
        let Some(s) = self.state() else {
            return None;
        };
        match s.core.room_inviter(room_id).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("room_inviter failed: {e:?}");
                None
            }
        }
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
            Err(e) => {
                tracing::warn!("seen_by_for_event failed: {e:?}");
                to_json(&Vec::<SeenByEntry>::new())
            }
        }
    }

    #[wasm_bindgen(js_name = upgradeRoom)]
    pub async fn upgrade_room(&self, room_id: String, new_version: String) -> JsValue {
        let Some(s) = self.state() else {
            return JsValue::NULL;
        };
        match s.core.upgrade_room(room_id, new_version).await {
            Ok(v) => JsValue::from_str(&v),
            Err(e) => {
                tracing::warn!("upgrade_room failed: {e:?}");
                JsValue::NULL
            }
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
            Err(e) => {
                tracing::warn!("list_knock_requests failed: {e:?}");
                to_json(&Vec::<KnockRequestSummary>::new())
            }
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

    #[wasm_bindgen(js_name = roomListUpdateVisibleRange)]
    pub fn room_list_update_visible_range(
        &self,
        token: f64,
        #[wasm_bindgen(unchecked_param_type = "number[]")] range: JsValue,
        threshold: f64,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let range_vec = match decode_u64_array(range) {
            Ok(range) => range,
            Err(error) => return webffi_err(&error),
        };
        let tx = s.room_list_cmds.borrow().get(&(token as u64)).cloned();
        let Some(tx) = tx else {
            return webffi_err("room list subscription is no longer active");
        };
        match tx.send(RoomListCmd::UpdateVisibleRange((range_vec, threshold as usize))) {
            Ok(()) => to_json(&serde_json::json!({"ok":true})),
            Err(_) => webffi_err("room list subscription is no longer active"),
        }
    }

    #[wasm_bindgen(js_name = subscribeRooms)]
    pub fn subscribe_rooms(
        &self,
        #[wasm_bindgen(unchecked_param_type = "string[]")] room_ids: JsValue,
    ) -> JsValue {
        let Some(s) = self.state() else {
            return webffi_not_init();
        };
        let ids = match decode_string_array(room_ids, "room_ids") {
            Ok(ids) => ids,
            Err(error) => return webffi_err(&error),
        };
        let mut rids = Vec::with_capacity(ids.len());
        for (index, id) in ids.iter().enumerate() {
            match OwnedRoomId::try_from(id.as_str()) {
                Ok(rid) => rids.push(rid),
                Err(error) => {
                    return webffi_err(&format!(
                        "room_ids[{index}] is not a valid room id: {error}"
                    ));
                }
            }
        }
        if rids.is_empty() {
            return to_json(&serde_json::json!({"ok":true}));
        }
        let s = s.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let Some(svc) = s.ensure_sync_service().await else {
                tracing::warn!("subscribe_rooms: sync service is unavailable");
                return;
            };
            svc.start().await;
            let refs: Vec<&matrix_sdk::ruma::RoomId> = rids.iter().map(|r| r.as_ref()).collect();
            svc.room_list_service().set_room_subscriptions(&refs).await;
        });
        to_json(&serde_json::json!({"ok":true}))
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
            s.core.drive_own_receipt(&rid, obs).await;
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

        let setup = match state
            .core
            .sync_service
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .cloned()
        {
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
                    Err(e) => {
                        tracing::warn!(room_id = %rid, event_id = %eid_ref, "notification lookup failed, skipping: {e:?}");
                        continue;
                    }
                };
                let NotificationStatus::Event(item) = status else {
                    continue;
                };
                let eid = eid_ref.to_owned();
                if let Some(rendered) = crate::map_notification_item_to_rendered(&rid, &eid, &item)
                {
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
        formatted_body: Option<String>,
    ) -> JsValue {
        let Some(state) = self.state() else {
            return webffi_not_init();
        };
        let Ok(att): Result<AttachmentInfo, _> = serde_json::from_str(&att_json) else {
            return webffi_err("invalid attachment JSON");
        };
        let result = state
            .core
            .send_existing_attachment(room_id, att, body, formatted_body)
            .await;
        webffi_unit(result)
    }

    #[wasm_bindgen(js_name = sendAttachmentBytes)]
    pub async fn send_attachment_bytes(
        &self,
        room_id: String,
        filename: String,
        mime: String,
        data: Vec<u8>,
        caption: Option<String>,
        formatted_caption: Option<String>,
        reply_to_event_id: Option<String>,
        voice_duration_ms: Option<u64>,
        voice_waveform: Option<Vec<f32>>,
        is_voice: Option<bool>,
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
        let mut config = AttachmentConfig::new();
        if mime_type.type_() == mime::AUDIO
            && (is_voice == Some(true)
                || voice_duration_ms.is_some()
                || voice_waveform.as_ref().is_some_and(|w| !w.is_empty()))
        {
            let base = matrix_sdk::attachment::BaseAudioInfo {
                duration: voice_duration_ms.map(web_time::Duration::from_millis),
                size: matrix_sdk::ruma::UInt::new(data.len() as u64),
                waveform: voice_waveform
                    .map(|w| w.into_iter().map(|v| v.clamp(0.0, 1.0)).collect()),
            };
            if is_voice == Some(true) {
                config =
                    config.info(matrix_sdk::attachment::AttachmentInfo::Voice(base));
            } else {
                config =
                    config.info(matrix_sdk::attachment::AttachmentInfo::Audio(base));
            }
        }
        if let Some(c) = caption {
            let text_content = if let Some(fc) = formatted_caption {
                matrix_sdk::ruma::events::room::message::TextMessageEventContent::html(c, fc)
            } else {
                matrix_sdk::ruma::events::room::message::TextMessageEventContent::plain(c)
            };
            config = config.caption(Some(text_content));
        }
        if let Some(reply_id) = reply_to_event_id {
            if let Ok(eid) = matrix_sdk::ruma::EventId::parse(&reply_id) {
                config = config.reply(Some(matrix_sdk::room::reply::Reply {
                    event_id: eid.to_owned(),
                    enforce_thread: matrix_sdk::room::reply::EnforceThread::Unthreaded,
                    add_mentions: matrix_sdk::ruma::events::room::message::AddMentions::Yes,
                }));
            }
        }
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
        match media_data_url(
            state.client(),
            &req,
            |_| att.mime.unwrap_or_else(|| "application/octet-stream".to_string()),
            "media download",
        )
        .await
        {
            Ok(data_uri) => webffi_value(Ok::<String, String>(data_uri)),
            Err(e) => e,
        }
    }

    #[wasm_bindgen(js_name = getMediaContent)]
    pub async fn get_media_content(&self, info_json: String, _use_thumbnail: bool) -> JsValue {
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
        match media_data_url(
            state.client(),
            &req,
            |_| info.mime.unwrap_or_else(|| "image/png".to_string()),
            "media download",
        )
        .await
        {
            Ok(url) => JsValue::from_str(&url),
            Err(e) => e,
        }
    }

    #[wasm_bindgen(js_name = thumbnailToCache)]
    pub async fn thumbnail_to_cache(
        &self,
        att_json: String,
        width: u32,
        height: u32,
        _use_crop: bool,
        animated: bool,
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
        let settings = MediaThumbnailSettings { animated, ..MediaThumbnailSettings::new(width.into(), height.into()) };
        let req = MediaRequestParameters {
            source,
            format: MediaFormat::Thumbnail(settings),
        };
        match media_data_url(state.client(), &req, sniff_image_mime, "thumbnail fetch").await {
            Ok(url) => JsValue::from_str(&url),
            Err(e) => e,
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
        let settings = MediaThumbnailSettings { animated: true, ..MediaThumbnailSettings::new(width.into(), height.into()) };
        let req = MediaRequestParameters {
            source: MediaSource::Plain(uri),
            format: MediaFormat::Thumbnail(settings),
        };
        match media_data_url(state.client(), &req, sniff_image_mime, "mxc thumbnail").await {
            Ok(url) => JsValue::from_str(&url),
            Err(e) => e,
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
            Err(e) => {
                tracing::warn!("setup_recovery failed: {e:?}");
                JsValue::NULL
            }
        }
    }

    #[wasm_bindgen(js_name = resetRecoveryKey)]
    pub async fn reset_recovery_key(&self) -> JsValue {
        let Some(state) = self.state() else {
            return JsValue::NULL;
        };
        match state.client().encryption().recovery().reset_key().await {
            Ok(key) => JsValue::from_str(&key),
            Err(e) => {
                tracing::warn!("reset_recovery_key failed: {e:?}");
                JsValue::NULL
            }
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
        let is_dm = room.is_dm();
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
        let handle = std::rc::Rc::new(handle);
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
        let handle = state.widget_handles.borrow().get(&sid).cloned();
        if let Some(handle) = handle {
            handle.send(message)
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
        let setup = match state
            .core
            .sync_service
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .cloned()
        {
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

        let (flow_id, request) = match state
            .core
            .start_device_verification(target_device_id)
            .await
        {
            Ok(v) => v,
            Err(e) => {
                emit_verif_err(&on_event, e.to_string());
                return String::new();
            }
        };

        spawn_verif_drive(drive_verification_request(request, true), on_event);
        flow_id
    }

    #[wasm_bindgen(js_name = startUserVerification)]
    pub async fn start_user_verification(&self, user_id: String, on_event: Function) -> String {
        let Some(state) = self.state() else {
            return String::new();
        };

        let (flow_id, request) = match state.core.start_user_verification(user_id).await {
            Ok(v) => v,
            Err(e) => {
                emit_verif_err(&on_event, e.to_string());
                return String::new();
            }
        };

        spawn_verif_drive(drive_verification_request(request, true), on_event);
        flow_id
    }

    #[wasm_bindgen(js_name = confirmSas)]
    pub async fn confirm_sas(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state.core.confirm_sas(flow_id, None).await
    }

    #[wasm_bindgen(js_name = confirmSasWithUser)]
    pub async fn confirm_sas_with_user(&self, flow_id: String, other_user_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state.core.confirm_sas(flow_id, Some(other_user_id)).await
    }

    #[wasm_bindgen(js_name = cancelVerification)]
    pub async fn cancel_verification(&self, flow_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state.core.cancel_verification(flow_id, None).await
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
        state
            .core
            .cancel_verification(flow_id, Some(other_user_id))
            .await
    }

    #[wasm_bindgen(js_name = isUserVerified)]
    pub async fn is_user_verified(&self, user_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state.core.is_user_verified(user_id).await
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
        state
            .core
            .accept_verification_request(flow_id, Some(other_user_id))
            .await
    }

    #[wasm_bindgen(js_name = acceptSas)]
    pub async fn accept_sas(&self, flow_id: String, other_user_id: String) -> bool {
        let Some(state) = self.state() else {
            return false;
        };
        state.core.accept_sas(flow_id, Some(other_user_id)).await
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

        let request = match state
            .core
            .observation_request(flow_id, other_user_id)
            .await
        {
            Ok(req) => req,
            Err(e) => {
                emit_verif_err(&on_event, e.to_string());
                return false;
            }
        };

        spawn_verif_drive(drive_incoming_verification(request), on_event);
        true
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
        webffi_unit(
            s.core
                .set_push_rule_enabled(ffi_kind, rule_id, enabled)
                .await,
        )
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
