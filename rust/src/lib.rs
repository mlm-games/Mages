#![recursion_limit = "256"]

use futures_util::StreamExt;
use js_int::UInt;
use matrix_sdk::RoomState;
use matrix_sdk::utils::UrlOrQuery;

use matrix_sdk::authentication::oauth::registration::language_tags::LanguageTag;
use matrix_sdk::authentication::oauth::registration::{
    ApplicationType, ClientMetadata, Localized, OAuthGrantType,
};
use matrix_sdk::reqwest::Url;
use matrix_sdk::ruma::events::{AnySyncMessageLikeEvent, AnySyncTimelineEvent};
use matrix_sdk::ruma::room_version_rules::RoomVersionRules;
use matrix_sdk::ruma::serde::Raw;
#[cfg(not(target_family = "wasm"))]
use matrix_sdk::search_index::SearchIndexStoreKind;
use matrix_sdk::send_queue::SendHandle;
use matrix_sdk::sleep::sleep;
#[cfg(not(target_family = "wasm"))]
use matrix_sdk::utils::local_server::LocalServerBuilder;
#[cfg(not(target_family = "wasm"))]
use matrix_sdk::utils::local_server::LocalServerIpAddress;
use matrix_sdk::{
    RoomDisplayName,
    ruma::events::room::{
        ImageInfo,
        message::{
            FileMessageEventContent, ImageMessageEventContent, VideoInfo, VideoMessageEventContent,
        },
    },
    widget::{VirtualElementCallWidgetConfig, VirtualElementCallWidgetProperties},
};
use matrix_sdk_ui::notification_client::NotificationItem;
use matrix_sdk_ui::timeline::default_event_filter;
use matrix_sdk_ui::{
    eyeball_im::Vector,
    timeline::{TimelineDetails, TimelineEventItemId},
};
use mime::Mime;
use once_cell::sync::Lazy;
use std::sync::atomic::AtomicBool;
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;
use tracing::{info, warn};
use uniffi::{Object, export, setup_scaffolding};

mod core;
mod errors;
mod macros;
mod platform;
mod types;
mod verification_flow;
#[cfg(target_family = "wasm")]
mod wasm_bridge;

pub use core::{CoreClient, TimelineManager};
pub use types::*;

use errors::{IntoFfi, OptionFfi, ffi_err};
use macros::*;

use matrix_sdk::{
    Client as SdkClient, Room, SessionMeta, SessionTokens,
    authentication::matrix::MatrixSession,
    authentication::oauth::{ClientId, OAuthSession, UserSession},
    media::{MediaFormat, MediaRequestParameters},
    ruma::{
        api::client::push::{Pusher, PusherIds, PusherInit, PusherKind},
        events::room::{EncryptedFile, MediaSource},
        push::HttpPusherData,
    },
};
use matrix_sdk::{
    encryption::BackupDownloadStrategy,
    ruma::{
        OwnedDeviceId, OwnedRoomId, OwnedUserId, events::call::invite::OriginalSyncCallInviteEvent,
        events::receipt::SyncReceiptEvent,
    },
};
use matrix_sdk::{
    encryption::{EncryptionSettings, verification::Verification},
    ruma::{
        self,
        events::{
            key::verification::request::ToDeviceKeyVerificationRequestEvent,
            room::message::{MessageType, SyncRoomMessageEvent},
        },
    },
};
use matrix_sdk_ui::{
    eyeball_im::VectorDiff,
    notification_client::{
        NotificationClient, NotificationEvent, NotificationProcessSetup, NotificationStatus,
    },
    room_list_service::filters,
    sync_service::State,
    timeline::{
        EventSendState, EventTimelineItem, MsgLikeContent, MsgLikeKind, RoomExt as _, Timeline,
        TimelineItem, TimelineItemContent,
    },
};

use ruma::events::room::message::RoomMessageEventContent;

use matrix_sdk::ruma::events::poll::{
    start::PollKind as RumaPollKind,
    unstable_start::{
        NewUnstablePollStartEventContent, UnstablePollAnswer, UnstablePollAnswers,
        UnstablePollStartContentBlock,
    },
};
use matrix_sdk::widget::{
    Capabilities, CapabilitiesProvider, ClientProperties, Intent as WidgetIntent, WidgetDriver,
    WidgetDriverHandle, WidgetSettings,
};
use std::panic::{AssertUnwindSafe, catch_unwind};

setup_scaffolding!();

#[cfg(not(target_arch = "wasm32"))]
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
});

#[cfg(target_arch = "wasm32")]
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
});

delegate_unit_result! {
    send_queue_set_enabled(enabled: bool);
    set_typing(room_id: String, typing: bool);
    send_message(room_id: String, body: String, formatted_body: Option<String>);
    reply(room_id: String, in_reply_to: String, body: String, formatted_body: Option<String>);
    edit(room_id: String, target_event_id: String, new_body: String, formatted_body: Option<String>);
    redact(room_id: String, event_id: String, reason: Option<String>);
    react(room_id: String, event_id: String, emoji: String);
    send_thread_text(room_id: String, root_event_id: String, body: String,
                     reply_to_event_id: Option<String>, latest_event_id: Option<String>,
                     formatted_body: Option<String>);
    mark_read(room_id: String);
    mark_read_at(room_id: String, event_id: String);
    mark_fully_read_at(room_id: String, event_id: String);
    set_mark_unread(room_id: String, unread: bool);
    ban_user(room_id: String, user_id: String, reason: Option<String>);
    unban_user(room_id: String, user_id: String, reason: Option<String>);
    kick_user(room_id: String, user_id: String, reason: Option<String>);
    invite_user(room_id: String, user_id: String);
    accept_invite(room_id: String);
    enable_room_encryption(room_id: String);
    set_room_name(room_id: String, name: String);
    set_room_topic(room_id: String, topic: String);
    set_pinned_events(room_id: String, event_ids: Vec<String>);
    set_room_favourite(room_id: String, fav: bool);
    set_room_low_priority(room_id: String, low: bool);
    is_event_read_by(room_id: String, event_id: String, user_id: String);
    knock(id_or_alias: String);
    space_invite_user(space_id: String, user_id: String);
    leave_room(room_id: String);
    set_room_notification_mode(room_id: String, mode: FfiRoomNotificationMode);
    set_room_canonical_alias(room_id: String, alias: Option<String>, alt_aliases: Vec<String>);
    set_room_directory_visibility(room_id: String, visibility: RoomDirectoryVisibility);
    set_room_join_rule(room_id: String, rule: RoomJoinRule);
    set_room_history_visibility(room_id: String, visibility: RoomHistoryVisibility);
    apply_power_level_changes(room_id: String, changes: RoomPowerLevelChanges);
    update_power_level_for_user(room_id: String, user_id: String, power_level: i64);
    ignore_user(user_id: String);
    unignore_user(user_id: String);
    report_content(room_id: String, event_id: String, score: Option<i32>, reason: Option<String>);
    report_room(room_id: String, reason: Option<String>);
    send_poll_response(room_id: String, poll_event_id: String, answers: Vec<String>);
    send_poll_end(room_id: String, poll_event_id: String);
    space_add_child(space_id: String, child_room_id: String, order: Option<String>, suggested: Option<bool>);
    space_remove_child(space_id: String, child_room_id: String);
    start_live_location(room_id: String, duration_ms: u64, description: Option<String>);
    stop_live_location(room_id: String);
    send_live_location(room_id: String, geo_uri: String);
    set_presence(state: Presence, status_msg: Option<String>);
    accept_knock_request(room_id: String, user_id: String);
    decline_knock_request(room_id: String, user_id: String, reason: Option<String>);
}

delegate_result! { bool; is_user_ignored(user_id: String); is_space(room_id: String);
    paginate_backwards(room_id: String, count: u16);
    paginate_forwards(room_id: String, count: u16);
}

delegate_result! { Vec<MemberSummary>; list_members(room_id: String); }
delegate_result! { Vec<RoomProfile>; list_invited(); }
delegate_result! { Vec<String>; ignored_users(); }
delegate_result! { Vec<DirectoryUser>; search_users(search_term: String, limit: u64); }
delegate_result! { DirectoryUser; get_user_profile(user_id: String); }
delegate_result! { PublicRoomsPage; public_rooms(server: Option<String>, search: Option<String>, limit: u32, since: Option<String>); }
delegate_result! { RoomPowerLevels; room_power_levels(room_id: String); }
delegate_result! { RoomDirectoryVisibility; room_directory_visibility(room_id: String); }
delegate_result! { RoomJoinRule; room_join_rule(room_id: String); }
delegate_result! { RoomHistoryVisibility; room_history_visibility(room_id: String); }
delegate_result! { Vec<SeenByEntry>; seen_by_for_event(room_id: String, event_id: String, limit: u32); }
delegate_result! { String; upgrade_room(room_id: String, new_version: String); ensure_dm(user_id: String); }
delegate_result! { Vec<KnockRequestSummary>; list_knock_requests(room_id: String); }
delegate_result! { bool; can_user_ban(room_id: String, user_id: String); can_user_invite(room_id: String, user_id: String); can_user_redact_other(room_id: String, user_id: String); }

delegate_option! { FfiRoomNotificationMode; room_notification_mode(room_id: String); }
delegate_option! { UnreadStats; room_unread_stats(room_id: String); }
delegate_option! { RoomTags; room_tags(room_id: String); }
delegate_option! { String; dm_peer_user_id(room_id: String); resolve_room_id(id_or_alias: String); account_management_url(); }
delegate_option! { SuccessorRoomInfo; room_successor(room_id: String); }
delegate_option! { PredecessorRoomInfo; room_predecessor(room_id: String); }
delegate_option! { bool; is_marked_unread(room_id: String); }

delegate_plain! { Vec<MessageEvent>; recent_events(room_id: String, limit: u32); }
delegate_plain! { Vec<String>; get_pinned_events(room_id: String); room_aliases(room_id: String); }
delegate_plain! { i64; get_user_power_level(room_id: String, user_id: String); }
delegate_plain! { OwnReceipt; own_last_read(room_id: String); }
delegate_plain! { HashMap<String, Vec<ReactionSummary>>; reactions_batch(room_id: String, event_ids: Vec<String>); }
delegate_plain! { Vec<ReactionSummary>; reactions_for_event(room_id: String, event_id: String); }
delegate_plain! { Vec<SpaceInfo>; my_spaces(); }
delegate_plain! { Vec<RoomSummary>; rooms(); }

#[derive(Object)]
pub struct Client {
    core: TokioDrop<Arc<CoreClient>>,
    store_dir: PathBuf,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
    send_observers: Arc<Mutex<HashMap<u64, Arc<dyn SendObserver>>>>,
    send_obs_counter: AtomicU64,
    send_tx: tokio::sync::mpsc::UnboundedSender<SendUpdate>,
    subs_counter: AtomicU64,
    timeline_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    typing_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    connection_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    inbox_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    receipts_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    room_list_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    room_list_cmds: Mutex<HashMap<u64, tokio::sync::mpsc::UnboundedSender<RoomListCmd>>>,
    send_handles_by_txn: Arc<Mutex<HashMap<String, SendHandle>>>,
    send_queue_supervised: AtomicBool,
    call_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    live_location_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    recovery_state_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    backup_state_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    pub app_in_foreground: Arc<AtomicBool>,
    widget_handles: Mutex<HashMap<u64, WidgetDriverHandle>>,
    widget_driver_tasks: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    widget_recv_tasks: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
}

fn mages_client_metadata(redirect_uri: &Url) -> Raw<ClientMetadata> {
    let is_localhost = matches!(
        redirect_uri.host_str(),
        Some("localhost") | Some("127.0.0.1") | Some("[::1]")
    );

    let application_type = if is_localhost {
        ApplicationType::Native
    } else {
        ApplicationType::Web
    };
    let client_uri = Localized::new(
        if is_localhost {
            Url::parse("https://github.com/mlm-games/mages").unwrap()
        } else {
            Url::parse(&redirect_uri.origin().ascii_serialization()).unwrap()
        },
        [],
    );

    let metadata = ClientMetadata {
        client_name: Some(Localized::new("Mages".to_owned(), [])),
        policy_uri: Some(client_uri.clone()),
        tos_uri: Some(client_uri.clone()),
        ..ClientMetadata::new(
            application_type,
            vec![OAuthGrantType::AuthorizationCode {
                redirect_uris: vec![redirect_uri.clone()],
            }],
            client_uri,
        )
    };
    Raw::new(&metadata).expect("Couldn't serialize client metadata")
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

fn strip_matrix_path(mut u: Url) -> Url {
    if let Some(idx) = u.path().find("/_matrix/") {
        let new_path = u.path()[..idx].to_string();
        u.set_path(&new_path);
        u.set_query(None);
        u.set_fragment(None);
    }
    u
}

fn cache_dir(dir: &PathBuf) -> PathBuf {
    dir.join("media_cache")
}

#[export]
impl Client {
    #[uniffi::constructor]
    pub fn new(
        homeserver_url: String,
        base_store_dir: String,
        account_id: Option<String>,
    ) -> Result<Self, FfiError> {
        platform::init_tracing();

        let normalized = {
            let raw = homeserver_url.trim();
            Url::parse(raw)
                .or_else(|_| Url::parse(&format!("https://{raw}")))
                .map(strip_matrix_path)
                .map(|u| u.to_string())
                .unwrap_or_else(|_| raw.to_owned())
        };

        let store_dir_path = if let Some(ref id) = account_id {
            std::path::PathBuf::from(&base_store_dir)
                .join("accounts")
                .join(id)
        } else {
            std::path::PathBuf::from(&base_store_dir)
        };

        #[cfg(not(target_family = "wasm"))]
        let _ = std::fs::create_dir_all(&store_dir_path);

        let inner = RT
            .block_on(async {
                #[cfg(target_arch = "wasm32")]
                let client = SdkClient::builder()
                    .server_name_or_homeserver_url(normalized)
                    .indexeddb_store("mages_store", None)
                    .with_encryption_settings(EncryptionSettings {
                        auto_enable_cross_signing: true,
                        auto_enable_backups: true,
                        backup_download_strategy: BackupDownloadStrategy::OneShot,
                        ..Default::default()
                    })
                    .handle_refresh_tokens()
                    .build()
                    .await;

                #[cfg(not(target_arch = "wasm32"))]
                let client = {
                    let idx = platform::search_index_config(&store_dir_path)
                        .expect("native builds require search index config");
                    SdkClient::builder()
                        .server_name_or_homeserver_url(normalized)
                        .sqlite_store(&store_dir_path, None)
                        .search_index_store(SearchIndexStoreKind::EncryptedDirectory(
                            idx.dir, idx.key,
                        ))
                        .with_encryption_settings(EncryptionSettings {
                            auto_enable_cross_signing: true,
                            auto_enable_backups: true,
                            backup_download_strategy: BackupDownloadStrategy::OneShot,
                            ..Default::default()
                        })
                        .handle_refresh_tokens()
                        .build()
                        .await
                };
                client
            })
            .map_err(|e| FfiError::Msg(format!("failed to build client: {e}")))?;

        let core = Arc::new(CoreClient::new(inner.clone()));
        let (send_tx, mut send_rx) = tokio::sync::mpsc::unbounded_channel::<SendUpdate>();

        let this = Self {
            core: TokioDrop::new(core.clone()),
            store_dir: store_dir_path,
            guards: Mutex::new(vec![]),
            send_observers: Arc::new(Mutex::new(HashMap::new())),
            send_obs_counter: AtomicU64::new(0),
            send_tx,
            subs_counter: AtomicU64::new(0),
            timeline_subs: Mutex::new(HashMap::new()),
            typing_subs: Mutex::new(HashMap::new()),
            connection_subs: Mutex::new(HashMap::new()),
            inbox_subs: Mutex::new(HashMap::new()),
            receipts_subs: Mutex::new(HashMap::new()),
            room_list_subs: Mutex::new(HashMap::new()),
            room_list_cmds: Mutex::new(HashMap::new()),
            send_handles_by_txn: core.send_handles_by_txn.clone(),
            send_queue_supervised: AtomicBool::new(false),
            call_subs: Mutex::new(HashMap::new()),
            live_location_subs: Mutex::new(HashMap::new()),
            recovery_state_subs: Mutex::new(HashMap::new()),
            backup_state_subs: Mutex::new(HashMap::new()),
            widget_handles: Mutex::new(HashMap::new()),
            widget_driver_tasks: Mutex::new(HashMap::new()),
            widget_recv_tasks: Mutex::new(HashMap::new()),
            app_in_foreground: Arc::new(AtomicBool::new(false)),
        };

        // send observer fan-out task
        {
            let observers = this.send_observers.clone();
            let h = spawn_task!(async move {
                while let Some(upd) = send_rx.recv().await {
                    let list: Vec<Arc<dyn SendObserver>> = {
                        let guard = observers.lock().expect("send_observers");
                        guard.values().cloned().collect()
                    };
                    for obs in list {
                        let upd_clone = upd.clone();
                        let _ = catch_unwind(AssertUnwindSafe(move || obs.on_update(upd_clone)));
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        // Session persistence - listen for token refreshes via subscribe_to_session_changes
        {
            let session_path = this.store_dir.clone();
            let sdk = this.core.sdk.clone();
            let h = spawn_task!(async move {
                let mut rx = sdk.subscribe_to_session_changes();
                loop {
                    match rx.recv().await {
                        Ok(matrix_sdk::SessionChange::TokensRefreshed) => {
                            platform::build_and_persist_session(&sdk, &session_path).await;
                        }
                        Ok(matrix_sdk::SessionChange::UnknownToken(info)) => {
                            if !info.soft_logout {
                                platform::remove_session_file(&session_path);
                                platform::reset_store_dir(&session_path);
                            }
                        }
                        Err(_) => break,
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        // Restore session
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            if let Some(info) = platform::load_session(&this.store_dir).await {
                info!("Loading session for {} (auth: {})", info.user_id, info.auth_api);
                if let Ok(user_id) = info.user_id.parse::<OwnedUserId>() {
                    let meta = SessionMeta {
                        user_id,
                        device_id: info.device_id.into(),
                    };
                    let tokens = SessionTokens {
                        access_token: info.access_token,
                        refresh_token: info.refresh_token,
                    };
                    let result = if info.auth_api == "oauth" {
                        if info.client_id.is_none() {
                            warn!(
                                "Stored OAuth session for {} is missing client_id; removing invalid session file and requiring re-login",
                                info.user_id
                            );
                            platform::remove_session_file(&this.store_dir);
                            None
                        } else if let Some(cid) = &info.client_id {
                            Some(
                                this.core
                                    .sdk
                                    .restore_session(OAuthSession {
                                        client_id: ClientId::new(cid.clone()),
                                        user: UserSession { meta, tokens },
                                    })
                                    .await,
                            )
                        } else {
                            None
                        }
                    } else {
                        Some(
                            this.core
                                .sdk
                                .restore_session(MatrixSession { meta, tokens })
                                .await,
                        )
                    };

                    match result {
                        Some(Ok(())) => {
                            info!("restore_session succeeded");
                            this.core
                                .sdk
                                .encryption()
                                .wait_for_e2ee_initialization_tasks()
                                .await;

                            this.core.ensure_sync_service().await;

                            if let Err(e) = this.core.sdk.event_cache().subscribe() {
                                warn!("event_cache.subscribe() failed after login: {e:?}");
                            }

                            this.ensure_send_queue_supervision();

                            this.core
                                .sdk
                                .send_queue()
                                .respawn_tasks_for_rooms_with_unsent_requests()
                                .await;
                        }
                        Some(Err(e)) => {
                            warn!(
                                "restore_session failed, resetting local store but preserving session: {e:?}"
                            );
                            platform::reset_store_dir(&this.store_dir);
                        }
                        None => {}
                    }
                }
            } else {
                info!("No usable session found, starting fresh");
            }
        });

        // Room key decryption retry task
        {
            let sdk = this.core.sdk.clone();
            let mgr = this.core.timeline_mgr.clone();
            let h = spawn_task!(async move {
                if let Some(mut stream) = sdk.encryption().room_keys_received_stream().await {
                    while let Some(batch) = stream.next().await {
                        let Ok(infos) = batch else { continue };
                        let mut by_room: HashMap<OwnedRoomId, Vec<String>> = HashMap::new();
                        for info in infos {
                            by_room
                                .entry(info.room_id.clone())
                                .or_default()
                                .push(info.session_id.clone());
                        }
                        for (rid, sessions) in by_room {
                            if let Some(tl) = mgr.timeline_for(&rid).await {
                                tl.retry_decryption(sessions).await;
                            }
                        }
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        Ok(this)
    }

    async fn finish_login_setup(&self) {
        self.core
            .sdk
            .encryption()
            .wait_for_e2ee_initialization_tasks()
            .await;

        self.core.ensure_sync_service().await;

        if let Err(e) = self.core.sdk.event_cache().subscribe() {
            warn!("event_cache.subscribe() failed after login: {e:?}");
        }

        self.ensure_send_queue_supervision();

        self.core
            .sdk
            .send_queue()
            .respawn_tasks_for_rooms_with_unsent_requests()
            .await;

        Self::persist_current_session(self).await;
    }

    pub fn room_profile(&self, room_id: String) -> Result<Option<RoomProfile>, FfiError> {
        RT.block_on(self.core.room_profile(room_id))
    }

    pub fn send_poll_start(
        &self,
        room_id: String,
        def: PollDefinition,
    ) -> Result<String, FfiError> {
        RT.block_on(self.core.send_poll_start(room_id, def))
    }

    pub fn create_room(
        &self,
        name: Option<String>,
        topic: Option<String>,
        invitees: Vec<String>,
        is_public: bool,
        room_alias: Option<String>,
    ) -> Result<String, FfiError> {
        RT.block_on(
            self.core
                .create_room(name, topic, invitees, is_public, room_alias),
        )
    }

    pub fn create_space(
        &self,
        name: String,
        topic: Option<String>,
        is_public: bool,
        invitees: Vec<String>,
    ) -> Result<String, FfiError> {
        RT.block_on(self.core.create_space(name, topic, is_public, invitees))
    }

    pub fn room_preview(&self, id_or_alias: String) -> Result<RoomPreview, FfiError> {
        RT.block_on(self.core.room_preview(id_or_alias))
    }

    pub fn space_hierarchy(
        &self,
        space_id: String,
        from: Option<String>,
        limit: u32,
        max_depth: Option<u32>,
        suggested_only: bool,
    ) -> Result<SpaceHierarchyPage, FfiError> {
        RT.block_on(
            self.core
                .space_hierarchy(space_id, from, limit, max_depth, suggested_only),
        )
    }

    pub fn thread_replies(
        &self,
        room_id: String,
        root_event_id: String,
        from: Option<String>,
        limit: u32,
        direction_forward: bool,
    ) -> Result<ThreadPage, FfiError> {
        RT.block_on(self.core.thread_replies(
            room_id,
            root_event_id,
            from,
            limit,
            direction_forward,
        ))
    }

    pub fn thread_summary(
        &self,
        room_id: String,
        root_event_id: String,
        per_page: u32,
        max_pages: u32,
    ) -> Result<ThreadSummary, FfiError> {
        RT.block_on(
            self.core
                .thread_summary(room_id, root_event_id, per_page, max_pages),
        )
    }

    pub fn get_presence(&self, user_id: String) -> Result<PresenceInfo, FfiError> {
        RT.block_on(self.core.get_presence(user_id))
    }

    pub fn publish_room_alias(&self, room_id: String, alias: String) -> Result<bool, FfiError> {
        RT.block_on(self.core.publish_room_alias(room_id, alias))
    }

    pub fn unpublish_room_alias(&self, room_id: String, alias: String) -> Result<bool, FfiError> {
        RT.block_on(self.core.unpublish_room_alias(room_id, alias))
    }

    pub fn room_upgrade_links(&self, room_id: String) -> Option<RoomUpgradeLinks> {
        RT.block_on(self.core.room_upgrade_links(room_id))
    }

    pub fn join_by_id_or_alias(&self, id_or_alias: String) -> Result<(), FfiError> {
        RT.block_on(self.core.join_by_id_or_alias(id_or_alias))
    }

    pub fn search_room(
        &self,
        room_id: String,
        query: String,
        limit: u32,
        offset: Option<u32>,
    ) -> Result<SearchPage, FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg("search_room: not supported on web".into()));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(self.core.search_room(room_id, query, limit, offset))
    }

    pub fn whoami(&self) -> Option<String> {
        self.core.whoami()
    }
    pub fn is_logged_in(&self) -> bool {
        self.core.is_logged_in()
    }

    fn next_sub_id(&self) -> u64 {
        self.subs_counter
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1)
    }

    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) -> u64 {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TypingObserver> = Arc::from(observer);
        let core = self.core.clone();
        sub_manager!(self, typing_subs, async move {
            let Some(stream) = core.typing_stream(&rid).await else {
                return;
            };
            tokio::pin!(stream);
            let mut last: Vec<String> = Vec::new();
            while let Some(names) = stream.next().await {
                if names != last {
                    last = names.clone();
                    let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(names)));
                }
            }
        })
    }

    pub fn unobserve_typing(&self, sub_id: u64) -> bool {
        unsub!(self, typing_subs, sub_id)
    }

    pub fn observe_receipts(&self, room_id: String, observer: Box<dyn ReceiptsObserver>) -> u64 {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::from(observer);
        let core = self.core.clone();
        sub_manager!(self, receipts_subs, async move {
            let Some(mut stream) = core.receipts_changed_stream(&rid).await else {
                return;
            };
            while let Some(()) = stream.next().await {
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
            }
        })
    }

    pub fn unobserve_receipts(&self, sub_id: u64) -> bool {
        unsub!(self, receipts_subs, sub_id)
    }

    pub fn observe_own_receipt(&self, room_id: String, observer: Box<dyn ReceiptsObserver>) -> u64 {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::from(observer);
        let sdk = self.core.sdk.clone();
        sub_manager!(self, receipts_subs, async move {
            let stream = sdk.observe_room_events::<SyncReceiptEvent, matrix_sdk::room::Room>(&rid);
            let mut sub = stream.subscribe();
            while let Some((_ev, _room)) = sub.next().await {
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
            }
        })
    }

    pub fn observe_timeline(&self, room_id: String, observer: Box<dyn TimelineObserver>) -> u64 {
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TimelineObserver> = Arc::from(observer);
        let me = self
            .core
            .sdk
            .user_id()
            .map(|u| u.to_string())
            .unwrap_or_default();
        let mgr = self.core.timeline_mgr.clone();
        sub_manager!(self, timeline_subs, async move {
            let Some(tl) = mgr.timeline_for(&room_id).await else {
                return;
            };
            let (items, mut stream) = tl.subscribe().await;
            emit_timeline_reset_filled(&obs, &tl, &room_id, &me).await;
            for it in items.iter() {
                if let Some(ev) = it.as_event() {
                    if let Some(eid) = missing_reply_event_id(ev) {
                        let tlc = tl.clone();
                        spawn_detached!(async move {
                            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
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
                            emit_timeline_reset_filled(&obs, &tl, &room_id, &me).await;
                        }
                        other => {
                            if let Some(mapped) = map_vec_diff(other, &room_id, &tl, &me) {
                                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_diff(mapped)));
                            }
                        }
                    }
                }
            }
        })
    }

    pub fn unobserve_timeline(&self, sub_id: u64) -> bool {
        unsub!(self, timeline_subs, sub_id)
    }

    pub fn observe_room_list(&self, observer: Box<dyn RoomListObserver>) -> u64 {
        let obs: Arc<dyn RoomListObserver> = Arc::from(observer);
        let core = self.core.clone();
        let store_dir = self.store_dir.clone();
        let id = self.next_sub_id();
        let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<RoomListCmd>();
        self.room_list_cmds.lock().unwrap().insert(id, cmd_tx);

        let h = spawn_task!(async move {
            core.ensure_sync_service().await;
            let svc = loop {
                if let Some(s) = core.sync_service.lock().unwrap().as_ref().cloned() {
                    break s;
                }
                sleep(Duration::from_millis(200)).await;
            };
            let rls = svc.room_list_service();
            let Ok(all) = rls.all_rooms().await else {
                return;
            };
            let (stream, controller) = all.entries_with_dynamic_adapters(50);
            tokio::pin!(stream);
            controller.set_filter(Box::new(filters::new_filter_non_left()));
            use matrix_sdk_ui::room_list_service::RoomListItem;
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
                                VectorDiff::Reset { values }  => { items = values;   changed = true; }
                                VectorDiff::Clear             => { items.clear();     changed = true; }
                                VectorDiff::PushFront { value } => { items.insert(0, value); changed = true; }
                                VectorDiff::PushBack  { value } => { items.push_back(value); changed = true; }
                                VectorDiff::PopFront => { if !items.is_empty() { items.remove(0); changed = true; } }
                                VectorDiff::PopBack  => { items.pop_back(); changed = true; }
                                VectorDiff::Insert { index, value } => { if index <= items.len() { items.insert(index, value); changed = true; } }
                                VectorDiff::Set    { index, value } => { if index < items.len()  { items[index] = value;       changed = true; } }
                                VectorDiff::Remove { index }        => { if index < items.len()  { items.remove(index);        changed = true; } }
                                VectorDiff::Truncate { length }     => { items.truncate(length);  changed = true; }
                                VectorDiff::Append   { values }     => { items.append(values);    changed = true; }
                            }
                        }
                        if changed {
                            let mut snapshot: Vec<RoomListEntry> = Vec::new();
                            for item in items.iter() {
                                let room = &**item;
                                let last_ts  = room.recency_stamp().map_or(0, |s| s.into());
                                let is_dm    = room.is_direct().await.unwrap_or(false);
                                let mut avatar_url = room.avatar_url().map(|mxc| mxc.to_string());
                                if avatar_url.is_none() && is_dm {
                                    avatar_url = CoreClient::dm_peer_avatar_url(room, core.sdk.user_id()).await;
                                }
                                let latest_event = latest_room_event_for(&core.timeline_mgr, room).await;
                                snapshot.push(RoomListEntry {
                                    room_id: room.room_id().to_string(),
                                    name: item.cached_display_name().clone().unwrap_or(RoomDisplayName::Named(room.room_id().to_string())).to_string(),
                                    last_ts, notifications: room.num_unread_notifications(),
                                    messages: room.num_unread_messages(), mentions: room.num_unread_mentions(),
                                    marked_unread: room.is_marked_unread(), is_favourite: room.is_favourite(),
                                    is_low_priority: room.is_low_priority(),
                                    is_invited: matches!(room.state(), RoomState::Invited),
                                    avatar_url, is_dm,
                                    is_encrypted: matches!(room.encryption_state(), matrix_sdk::EncryptionState::Encrypted),
                                    member_count: room.joined_members_count().min(u32::MAX as u64) as u32,
                                    topic: room.topic(), latest_event,
                                });
                            }
                            let _ = platform::write_room_list_cache(&store_dir, &snapshot).await;
                            let obs_clone = obs.clone();
                            let _ = catch_unwind(AssertUnwindSafe(move || obs_clone.on_reset(snapshot)));
                        }
                    }
                    else => break,
                }
            }
        });
        self.room_list_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_room_list(&self, token: u64) -> bool {
        self.room_list_cmds.lock().unwrap().remove(&token);
        unsub!(self, room_list_subs, token)
    }

    pub fn room_list_set_unread_only(&self, token: u64, unread_only: bool) -> bool {
        if let Some(tx) = self.room_list_cmds.lock().unwrap().get(&token).cloned() {
            tx.send(RoomListCmd::SetUnreadOnly(unread_only)).is_ok()
        } else {
            false
        }
    }

    pub fn start_call_inbox(&self, observer: Box<dyn CallObserver>) -> u64 {
        let obs: Arc<dyn CallObserver> = Arc::from(observer);
        let sdk = self.core.sdk.clone();
        sub_manager!(self, call_subs, async move {
            let handler = sdk.observe_events::<OriginalSyncCallInviteEvent, Room>();
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

    pub fn stop_call_inbox(&self, token: u64) -> bool {
        unsub!(self, call_subs, token)
    }

    pub fn observe_live_location(
        &self,
        room_id: String,
        observer: Box<dyn LiveLocationObserver>,
    ) -> u64 {
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn LiveLocationObserver> = Arc::from(observer);
        let sdk = self.core.sdk.clone();
        sub_manager!(self, live_location_subs, async move {
            let Some(room) = sdk.get_room(&rid) else {
                return;
            };
            let observable = room.observe_live_location_shares();
            let stream = observable.subscribe();
            use futures_util::{StreamExt, pin_mut};
            pin_mut!(stream);
            let mut latest_shares: HashMap<String, LiveLocationShareInfo> = HashMap::new();
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
                latest_shares.insert(info.user_id.clone(), info);
                let snapshot = latest_shares.values().cloned().collect();
                let _ = catch_unwind(AssertUnwindSafe(|| obs.on_update(snapshot)));
            }
        })
    }

    pub fn unobserve_live_location(&self, sub_id: u64) -> bool {
        unsub!(self, live_location_subs, sub_id)
    }

    pub fn monitor_connection(&self, observer: Box<dyn ConnectionObserver>) -> u64 {
        let sdk = self.core.sdk.clone();
        let obs: Arc<dyn ConnectionObserver> = Arc::from(observer);
        sub_manager!(self, connection_subs, async move {
            let mut last_state = ConnectionState::Disconnected;
            let mut session_rx = sdk.subscribe_to_session_changes();
            loop {
                tokio::select! {
                    Ok(change) = session_rx.recv() => {
                        let current = match change {
                            matrix_sdk::SessionChange::TokensRefreshed => ConnectionState::Connected,
                            matrix_sdk::SessionChange::UnknownToken { .. } => ConnectionState::Reconnecting { attempt: 1, next_retry_secs: 5 },
                        };
                        if !matches!((&current, &last_state), (ConnectionState::Connected, ConnectionState::Connected) | (ConnectionState::Disconnected, ConnectionState::Disconnected)) {
                            obs.on_connection_change(current.clone());
                            last_state = current;
                        }
                    }
                    _ = sleep(Duration::from_secs(30)) => {
                        let current = if sdk.is_active() { ConnectionState::Connected } else { ConnectionState::Disconnected };
                        if !matches!((&current, &last_state), (ConnectionState::Connected, ConnectionState::Connected) | (ConnectionState::Disconnected, ConnectionState::Disconnected)) {
                            obs.on_connection_change(current.clone());
                            last_state = current;
                        }
                    }
                }
            }
        })
    }

    pub fn unobserve_connection(&self, sub_id: u64) -> bool {
        unsub!(self, connection_subs, sub_id)
    }

    pub fn observe_sends(&self, observer: Box<dyn SendObserver>) -> u64 {
        let id = self
            .send_obs_counter
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1);
        self.send_observers
            .lock()
            .unwrap()
            .insert(id, Arc::from(observer));
        id
    }

    pub fn unobserve_sends(&self, id: u64) -> bool {
        self.send_observers.lock().unwrap().remove(&id).is_some()
    }

    pub fn observe_recovery_state(&self, observer: Box<dyn RecoveryStateObserver>) -> u64 {
        let obs: Arc<dyn RecoveryStateObserver> = Arc::from(observer);
        let sdk = self.core.sdk.clone();
        sub_manager!(self, recovery_state_subs, async move {
            let mut stream = sdk.encryption().recovery().state_stream();
            while let Some(state) = stream.next().await {
                let mapped = match state {
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

    pub fn unobserve_recovery_state(&self, sub_id: u64) -> bool {
        unsub!(self, recovery_state_subs, sub_id)
    }

    pub fn observe_backup_state(&self, observer: Box<dyn BackupStateObserver>) -> u64 {
        let obs: Arc<dyn BackupStateObserver> = Arc::from(observer);
        let sdk = self.core.sdk.clone();
        sub_manager!(self, backup_state_subs, async move {
            let mut stream = sdk.encryption().backups().state_stream();
            while let Some(state) = stream.next().await {
                let mapped = match state {
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
        })
    }

    pub fn unobserve_backup_state(&self, sub_id: u64) -> bool {
        unsub!(self, backup_state_subs, sub_id)
    }

    pub fn start_verification_inbox(&self, observer: Box<dyn VerificationInboxObserver>) -> u64 {
        let sdk = self.core.sdk.clone();
        let obs: Arc<dyn VerificationInboxObserver> = Arc::from(observer);
        sub_manager!(self, inbox_subs, async move {
            sdk.encryption().wait_for_e2ee_initialization_tasks().await;
            if let Err(e) = sdk.event_cache().subscribe() {
                warn!("verification_inbox: event_cache.subscribe() failed: {e:?}");
            }
            let td_handler = sdk.observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
            let mut td_sub = td_handler.subscribe();
            let ir_handler = sdk.observe_events::<SyncRoomMessageEvent, Room>();
            let mut ir_sub = ir_handler.subscribe();
            loop {
                tokio::select! {
                    maybe = td_sub.next() => {
                        if let Some((ev, ())) = maybe {
                            let flow_id = ev.content.transaction_id.to_string();
                            let from_user = ev.sender.to_string();
                            let from_device = ev.content.from_device.to_string();
                            let _ = catch_unwind(AssertUnwindSafe(|| obs.on_request(flow_id, from_user, from_device)));
                        } else { break; }
                    }
                    maybe = ir_sub.next() => {
                        if let Some((ev, _room)) = maybe {
                            if let SyncRoomMessageEvent::Original(o) = ev {
                                if let MessageType::VerificationRequest(_) = &o.content.msgtype {
                                    let flow_id = o.event_id.to_string();
                                    let from_user = o.sender.to_string();
                                    let _ = catch_unwind(AssertUnwindSafe(|| obs.on_request(flow_id, from_user, String::new())));
                                }
                            }
                        } else { break; }
                    }
                }
            }
        })
    }

    pub fn unobserve_verification_inbox(&self, sub_id: u64) -> bool {
        unsub!(self, inbox_subs, sub_id)
    }

    pub fn load_room_list_cache(&self) -> Vec<RoomListEntry> {
        RT.block_on(async { platform::load_room_list_cache(&self.store_dir).await })
    }

    pub fn enter_foreground(&self) {
        self.app_in_foreground
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = RT.block_on(async {
            self.core.ensure_sync_service().await;
            if let Err(e) = self.core.sdk.event_cache().subscribe() {
                warn!("event_cache.subscribe() failed: {e:?}");
            }
            if let Some(svc) = self.core.sync_service.lock().unwrap().as_ref().cloned() {
                let _ = svc.start().await;
            }
        });
    }

    pub fn enter_background(&self) {
        self.app_in_foreground
            .store(false, std::sync::atomic::Ordering::SeqCst);
        let _ = RT.block_on(async {
            if let Some(svc) = self.core.sync_service.lock().unwrap().as_ref().cloned() {
                let _ = svc.stop().await;
            }
        });
    }

    pub fn start_supervised_sync(&self, observer: Box<dyn SyncObserver>) {
        let obs: Arc<dyn SyncObserver> = Arc::from(observer);
        let svc_slot = self.core.sync_service.clone();
        let in_foreground = self.app_in_foreground.clone();
        let h = spawn_task!(async move {
            obs.on_state(SyncStatus {
                phase: SyncPhase::Idle,
                message: None,
            });
            let svc = loop {
                if let Some(s) = svc_slot.lock().unwrap().as_ref().cloned() {
                    break s;
                }
                sleep(Duration::from_millis(200)).await;
            };
            let mut st = svc.state();
            svc.start().await;
            while let Some(state) = st.next().await {
                match state {
                    State::Idle => obs.on_state(SyncStatus {
                        phase: SyncPhase::Idle,
                        message: None,
                    }),
                    State::Running => obs.on_state(SyncStatus {
                        phase: SyncPhase::Running,
                        message: None,
                    }),
                    State::Offline => obs.on_state(SyncStatus {
                        phase: SyncPhase::BackingOff,
                        message: Some("Offline (auto-retrying)".into()),
                    }),
                    State::Terminated => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Idle,
                            message: Some("Sync stopped".into()),
                        });
                        if in_foreground.load(std::sync::atomic::Ordering::SeqCst) {
                            sleep(Duration::from_millis(500)).await;
                            svc.start().await;
                        }
                    }
                    State::Error(err) => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Error,
                            message: Some(format!("Sync error: {err}")),
                        });
                        sleep(Duration::from_secs(2)).await;
                        if in_foreground.load(std::sync::atomic::Ordering::SeqCst) {
                            svc.start().await;
                        }
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    fn ensure_send_queue_supervision(&self) {
        if self.send_queue_supervised.swap(true, Ordering::SeqCst) {
            return;
        }
        let sdk = self.core.sdk.clone();
        let tx = self.send_tx.clone();
        let h = spawn_task!(async move {
            let mut rx = sdk.send_queue().subscribe();
            let mut attempts: HashMap<String, u32> = HashMap::new();
            loop {
                let upd = match rx.recv().await {
                    Ok(u) => u,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                };
                let room_id_str = upd.room_id.to_string();
                if let Some(u) =
                    crate::core::map_send_queue_update(&room_id_str, upd.update, &mut attempts)
                {
                    let _ = tx.send(u);
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn login_password(&self, username: String, password: String) -> Result<String, FfiError> {
        RT.block_on(async {
            self.core
                .sdk
                .matrix_auth()
                .login_username(&username, &password)
                .send()
                .await
                .map_err(|e| FfiError::Msg(format!("login failed: {e}")))?;
            self.finish_login_setup().await;
            Ok(self.core.user_id_str())
        })
    }

    pub fn homeserver_login_details(&self) -> HomeserverLoginDetails {
        RT.block_on(async {
            let (supports_sso, supports_password) =
                match self.core.sdk.matrix_auth().get_login_types().await {
                    Ok(resp) => {
                        use matrix_sdk::ruma::api::client::session::get_login_types::v3::LoginType;
                        (
                            resp.flows.iter().any(|f| matches!(f, LoginType::Sso(_))),
                            resp.flows
                                .iter()
                                .any(|f| matches!(f, LoginType::Password(_))),
                        )
                    }
                    Err(e) => {
                        warn!("get_login_types failed: {e:?}");
                        (false, false)
                    }
                };

            let supports_oauth = match self.core.sdk.oauth().server_metadata().await {
                Ok(_) => true,
                Err(e) => {
                    if !e.is_not_supported() {
                        warn!("OAuth discovery failed, treating as unsupported: {e:?}");
                    }
                    false
                }
            };

            HomeserverLoginDetails {
                supports_oauth,
                supports_sso,
                supports_password,
            }
        })
    }

    pub fn login_oauth(&self, redirect_uri: String) -> Result<String, FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg(
            "login_oauth: use async version on web".into(),
        ));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            let redirect = Url::parse(&redirect_uri).ffi()?;
            let metadata = mages_client_metadata(&redirect);
            let auth_data = self
                .core
                .sdk
                .oauth()
                .login(redirect, None, Some(metadata.into()), None)
                .build()
                .await
                .map_err(|e| FfiError::Msg(format!("oauth: {e}")))?;
            Ok(auth_data.url.to_string())
        })
    }

    pub fn complete_oauth_login(&self, callback_url: String) -> Result<String, FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg(
            "complete_oauth_login: use async version on web".into(),
        ));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            let url = Url::parse(&callback_url).ffi()?;
            self.core
                .sdk
                .oauth()
                .finish_login(UrlOrQuery::Url(url))
                .await
                .map_err(|e| FfiError::Msg(format!("oauth finish failed: {e}")))?;
            self.finish_login_setup().await;
            Ok(self.core.user_id_str())
        })
    }

    pub fn login_sso_loopback(
        &self,
        opener: Box<dyn UrlOpener>,
        device_name: Option<String>,
    ) -> Result<(), FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg(
            "login_sso_loopback: not supported on wasm".into(),
        ));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            self.core
                .sdk
                .matrix_auth()
                .login_sso(move |sso_url: String| async move {
                    let _ = opener.open(sso_url);
                    Ok(())
                })
                .server_builder(
                    LocalServerBuilder::new().ip_address(LocalServerIpAddress::Localhostv4),
                )
                .initial_device_display_name(device_name.as_deref().unwrap_or("Mages"))
                .send()
                .await
                .ffi()?;
            self.finish_login_setup().await;
            Ok(())
        })
    }

    pub fn login_oauth_loopback(
        &self,
        opener: Box<dyn UrlOpener>,
        device_name: Option<String>,
    ) -> Result<(), FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg(
            "login_oauth_loopback: not supported on wasm".into(),
        ));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            let oauth = self.core.sdk.oauth();
            let (redirect_uri, server_handle) = LocalServerBuilder::new()
                .ip_address(LocalServerIpAddress::Localhostv4)
                .spawn()
                .await
                .ffi()?;
            let registration_data = mages_client_metadata(&redirect_uri).into();
            let auth_data = oauth
                .login(redirect_uri, None, Some(registration_data), None)
                .build()
                .await
                .ffi()?;
            let _ = opener.open(auth_data.url.to_string());
            let callback_query = server_handle.await.or_ffi("No OAuth callback received")?;
            oauth
                .finish_login(UrlOrQuery::Query(callback_query.0))
                .await
                .ffi()?;
            if let Some(name) = device_name {
                if let Some(device_id) = self.core.sdk.device_id() {
                    use matrix_sdk::ruma::api::client::device::update_device;
                    let mut req = update_device::v3::Request::new(device_id.to_owned());
                    req.display_name = Some(name);
                    let _ = self.core.sdk.send(req).await;
                }
            }
            self.finish_login_setup().await;
            Ok(())
        })
    }

    pub fn start_oauth_login(&self, redirect_uri: String) -> Result<String, FfiError> {
        RT.block_on(async {
            let oauth = self.core.sdk.oauth();
            let redirect_uri = Url::parse(&redirect_uri).ffi()?;
            let registration_data = mages_client_metadata(&redirect_uri).into();
            let auth_data = oauth
                .login(redirect_uri, None, Some(registration_data), None)
                .build()
                .await
                .ffi()?;
            Ok(auth_data.url.to_string())
        })
    }

    pub fn finish_oauth_login(
        &self,
        callback_data: String,
        device_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let oauth = self.core.sdk.oauth();
            let callback = Url::parse(&callback_data)
                .map(UrlOrQuery::Url)
                .unwrap_or_else(|_| UrlOrQuery::Query(callback_data));
            oauth.finish_login(callback).await.ffi()?;
            Self::maybe_update_device_name(self, device_name).await;
            self.finish_login_setup().await;
            Ok(())
        })
    }

    pub fn start_sso_login(
        &self,
        redirect_uri: String,
        idp_id: Option<String>,
    ) -> Result<String, FfiError> {
        RT.block_on(async {
            let auth = self.core.sdk.matrix_auth();
            let url = auth
                .get_sso_login_url(&redirect_uri, idp_id.as_deref())
                .await
                .ffi()?;
            Ok(url)
        })
    }

    pub fn finish_sso_login(
        &self,
        callback_url: String,
        device_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let auth = self.core.sdk.matrix_auth();
            let callback_url = Url::parse(&callback_url).ffi()?;
            let mut builder = auth
                .login_with_sso_callback(UrlOrQuery::Url(callback_url))
                .ffi()?;
            if let Some(name) = device_name.as_deref() {
                builder = builder.initial_device_display_name(name);
            }
            let _response = builder.await.ffi()?;
            self.finish_login_setup().await;
            Ok(())
        })
    }

    pub fn logout(&self) -> bool {
        self.shutdown();
        let _ = RT.block_on(async { self.core.sdk.logout().await });
        platform::remove_session_file(&self.store_dir);
        platform::reset_store_dir(&self.store_dir);
        true
    }

    pub fn login(
        &self,
        username: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            self.core
                .login_password(
                    PasswordLoginKind::Username,
                    username,
                    password,
                    None,
                    device_display_name,
                )
                .await?;
            self.finish_authenticated_setup(true).await;
            Ok(())
        })
    }

    pub fn login_email(
        &self,
        email: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            self.core
                .login_password(
                    PasswordLoginKind::Email,
                    email,
                    password,
                    None,
                    device_display_name,
                )
                .await?;
            self.finish_authenticated_setup(true).await;
            Ok(())
        })
    }

    pub fn login_phone(
        &self,
        country: String,
        phone: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            self.core
                .login_password(
                    PasswordLoginKind::Phone,
                    phone,
                    password,
                    Some(country),
                    device_display_name,
                )
                .await?;
            self.finish_authenticated_setup(true).await;
            Ok(())
        })
    }

    async fn finish_authenticated_setup(&self, persist_session: bool) {
        self.core.finish_authenticated_setup_common().await;
        self.ensure_send_queue_supervision();

        if persist_session {
            Self::persist_current_session(self).await;
        }
    }

    pub fn send_existing_attachment(
        &self,
        room_id: String,
        att: AttachmentInfo,
        body: Option<String>,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> bool {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(room) = self.core.sdk.get_room(&rid) else {
                return false;
            };
            let default_caption = match att.kind {
                AttachmentKind::Image => "Image",
                AttachmentKind::Video => "Video",
                AttachmentKind::File => "File",
            };
            let caption = body.unwrap_or_else(|| default_caption.to_string());
            let media_source = if let Some(enc) = att.encrypted.as_ref() {
                let ef: matrix_sdk::ruma::events::room::EncryptedFile =
                    match serde_json::from_str(&enc.json) {
                        Ok(f) => f,
                        Err(_) => return false,
                    };
                MediaSource::Encrypted(Box::new(ef))
            } else {
                MediaSource::Plain(att.mxc_uri.clone().into())
            };
            let msgtype = match att.kind {
                AttachmentKind::Image => {
                    let mut info = ImageInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);
                    info.width = att.width.map(UInt::from);
                    info.height = att.height.map(UInt::from);
                    let mut img = ImageMessageEventContent::new(caption.clone(), media_source);
                    img.info = Some(Box::new(info));
                    MessageType::Image(img)
                }
                AttachmentKind::Video => {
                    let mut info = VideoInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);
                    info.width = att.width.map(UInt::from);
                    info.height = att.height.map(UInt::from);
                    info.duration = att.duration_ms.map(Duration::from_millis);
                    let mut vid = VideoMessageEventContent::new(caption.clone(), media_source);
                    vid.info = Some(Box::new(info));
                    MessageType::Video(vid)
                }
                AttachmentKind::File => {
                    let mut info = matrix_sdk::ruma::events::room::message::FileInfo::new();
                    info.mimetype = att.mime.clone();
                    info.size = att.size_bytes.and_then(UInt::new);
                    let mut file = FileMessageEventContent::new(caption.clone(), media_source);
                    file.info = Some(Box::new(info));
                    MessageType::File(file)
                }
            };
            let content = RoomMessageEventContent::new(msgtype);
            if let Some(p) = progress.as_ref() {
                p.on_progress(0, None);
            }
            let res = room.send(content).await;
            if let Some(p) = progress {
                p.on_progress(1, Some(1));
            }
            res.is_ok()
        })
    }

    pub fn download_media(
        &self,
        mxc_uri: String,
        dest_path: String,
        encrypted_json: Option<String>,
    ) -> Result<DownloadResult, FfiError> {
        RT.block_on(async {
            let source = if let Some(json) = encrypted_json {
                let enc_file: matrix_sdk::ruma::events::room::EncryptedFile =
                    serde_json::from_str(&json).ffi()?;
                MediaSource::Encrypted(Box::new(enc_file))
            } else {
                MediaSource::Plain(matrix_sdk::ruma::OwnedMxcUri::from(mxc_uri))
            };
            let request = MediaRequestParameters {
                source,
                format: MediaFormat::File,
            };
            let data = self
                .core
                .sdk
                .media()
                .get_media_content(&request, true)
                .await
                .ffi()?;
            #[cfg(not(target_family = "wasm"))]
            {
                std::fs::write(&dest_path, &data).ffi()?;
            }
            Ok(DownloadResult {
                path: dest_path,
                bytes: data.len() as u64,
            })
        })
    }

    pub fn download_thumbnail(
        &self,
        mxc_uri: String,
        dest_path: String,
        width: u32,
        height: u32,
    ) -> Result<DownloadResult, FfiError> {
        RT.block_on(async {
            let uri = matrix_sdk::ruma::OwnedMxcUri::from(mxc_uri);
            let settings = matrix_sdk::media::MediaThumbnailSettings::with_method(
                matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method::Scale,
                UInt::try_from(width).unwrap_or(UInt::MIN),
                UInt::try_from(height).unwrap_or(UInt::MIN),
            );
            let request = MediaRequestParameters {
                source: MediaSource::Plain(uri),
                format: MediaFormat::Thumbnail(settings),
            };
            let data = self
                .core
                .sdk
                .media()
                .get_media_content(&request, true)
                .await
                .ffi()?;
            #[cfg(not(target_family = "wasm"))]
            {
                std::fs::write(&dest_path, &data).ffi()?;
            }
            Ok(DownloadResult {
                path: dest_path,
                bytes: data.len() as u64,
            })
        })
    }

    pub fn fetch_notification(
        &self,
        room_id: String,
        event_id: String,
    ) -> Result<Option<RenderedNotification>, FfiError> {
        RT.block_on(async {
            let rid = OwnedRoomId::try_from(room_id).ffi()?;
            let eid = matrix_sdk::ruma::OwnedEventId::try_from(event_id).ffi()?;
            let nc = NotificationClient::new(
                self.core.sdk.clone(),
                NotificationProcessSetup::MultipleProcesses,
            )
            .await?;
            let item = nc.get_notification(&rid, &eid).await.ffi()?;
            match item {
                NotificationStatus::Event(notif) => {
                    Ok(map_notification_item_to_rendered(&rid, &eid, &notif))
                }
                _ => Ok(None),
            }
        })
    }

    pub fn enable_recovery(&self, observer: Box<dyn RecoveryObserver>) -> bool {
        let sdk = self.core.sdk.clone();
        let store = self.store_dir.clone();
        let obs: Arc<dyn RecoveryObserver> = Arc::from(observer);
        let h = spawn_task!(async move {
            obs.on_progress("Starting recovery setup...".into());
            match sdk.encryption().recovery().enable().await {
                Ok(key) => {
                    platform::build_and_persist_session(&sdk, &store).await;
                    obs.on_done(key);
                }
                Err(e) => obs.on_error(format!("Recovery setup failed: {e}")),
            }
        });
        self.guards.lock().unwrap().push(h);
        true
    }

    pub fn recover_with_key(&self, recovery_key: String) -> Result<(), FfiError> {
        RT.block_on(async {
            self.core
                .sdk
                .encryption()
                .recovery()
                .recover(&recovery_key)
                .await
                .ffi()
        })
    }

    pub fn disable_recovery(&self) -> Result<(), FfiError> {
        RT.block_on(async {
            self.core
                .sdk
                .encryption()
                .recovery()
                .disable()
                .await
                .ffi()?;
            Self::persist_current_session(self).await;
            Ok(())
        })
    }

    pub fn recovery_state(&self) -> RecoveryState {
        RT.block_on(async {
            match self.core.sdk.encryption().recovery().state() {
                matrix_sdk::encryption::recovery::RecoveryState::Disabled => {
                    RecoveryState::Disabled
                }
                matrix_sdk::encryption::recovery::RecoveryState::Enabled => RecoveryState::Enabled,
                matrix_sdk::encryption::recovery::RecoveryState::Incomplete => {
                    RecoveryState::Incomplete
                }
                _ => RecoveryState::Unknown,
            }
        })
    }

    pub fn backup_state(&self) -> BackupState {
        RT.block_on(async {
            match self.core.sdk.encryption().backups().state() {
                matrix_sdk::encryption::backups::BackupState::Unknown => BackupState::Unknown,
                matrix_sdk::encryption::backups::BackupState::Creating => BackupState::Creating,
                matrix_sdk::encryption::backups::BackupState::Enabling => BackupState::Enabling,
                matrix_sdk::encryption::backups::BackupState::Resuming => BackupState::Resuming,
                matrix_sdk::encryption::backups::BackupState::Enabled => BackupState::Enabled,
                matrix_sdk::encryption::backups::BackupState::Downloading => {
                    BackupState::Downloading
                }
                matrix_sdk::encryption::backups::BackupState::Disabling => BackupState::Disabling,
                _ => BackupState::Unknown,
            }
        })
    }

    pub fn reset_recovery_key(&self) -> Result<String, FfiError> {
        RT.block_on(async {
            self.core
                .sdk
                .encryption()
                .recovery()
                .reset_key()
                .await
                .ffi()
        })
    }

    pub fn devices(&self) -> Result<Vec<DeviceSummary>, FfiError> {
        RT.block_on(async {
            let encryption = self.core.sdk.encryption();
            let own_device_id = self
                .core
                .sdk
                .session_meta()
                .map(|m| m.device_id.to_string())
                .unwrap_or_default();
            let devices = encryption
                .get_user_devices(self.core.sdk.user_id().or_ffi("no user")?)
                .await
                .ffi()?;
            Ok(devices
                .devices()
                .map(|d| DeviceSummary {
                    device_id: d.device_id().to_string(),
                    display_name: d
                        .display_name()
                        .unwrap_or(d.device_id().as_str())
                        .to_string(),
                    ed25519: d.ed25519_key().map(|k| k.to_base64()).unwrap_or_default(),
                    is_own: d.device_id().to_string() == own_device_id,
                    verified: d.is_verified(),
                })
                .collect())
        })
    }

    pub fn device_id(&self) -> Option<String> {
        self.core
            .sdk
            .session_meta()
            .map(|m| m.device_id.to_string())
    }

    pub fn start_element_call(
        &self,
        room_id: String,
        element_call_url: Option<String>,
        parent_url: Option<String>,
        use_controlled_audio_devices: bool,
        intent: ElementCallIntent,
        observer: Box<dyn CallWidgetObserver>,
        language_tag: Option<String>,
        theme: Option<String>,
    ) -> Result<CallSessionInfo, FfiError> {
        let inner = self.core.sdk.clone();
        let obs: Arc<dyn CallWidgetObserver> = Arc::from(observer);
        let session_id = self.next_sub_id();
        let lang = language_tag
            .as_deref()
            .and_then(|s| LanguageTag::parse(s).ok());
        let (widget_settings, widget_url, widget_base_url, resolved_parent) = RT.block_on(async {
            let rid = OwnedRoomId::try_from(room_id.as_str()).ffi()?;
            let Some(room) = inner.get_room(&rid) else {
                return Err(ffi_err!("room not found"));
            };
            let element_call_url = element_call_url.ok_or_else(|| {
                ffi_err!("element_call_url is required - platform must provide embedded URL or explicit fallback")
            })?;
            let resolved_parent = parent_url.unwrap_or_else(|| element_call_url.clone());
            let props = VirtualElementCallWidgetProperties {
                element_call_url,
                parent_url: Some(resolved_parent.clone()),
                widget_id: format!("mages-ecall-{}", session_id),
                ..VirtualElementCallWidgetProperties::default()
            };
            let is_dm = room.is_direct().await.unwrap_or(false);
            let widget_intent = match (intent, is_dm) {
                (ElementCallIntent::StartCall, true) => WidgetIntent::StartCallDm,
                (ElementCallIntent::JoinExisting, true) => WidgetIntent::JoinExistingDm,
                (ElementCallIntent::StartCall, false) => WidgetIntent::StartCall,
                (ElementCallIntent::JoinExisting, false) => WidgetIntent::JoinExisting,
                (ElementCallIntent::StartCallVoiceDm, _) => WidgetIntent::StartCallDmVoice,
                (ElementCallIntent::JoinExistingVoiceDm, _) => WidgetIntent::JoinExistingDmVoice,
            };
            let config = VirtualElementCallWidgetConfig {
                controlled_audio_devices: Some(use_controlled_audio_devices),
                preload: Some(false),
                app_prompt: Some(false),
                confine_to_room: Some(true),
                hide_screensharing: Some(false),
                intent: Some(widget_intent),
                ..VirtualElementCallWidgetConfig::default()
            };
            let settings = WidgetSettings::new_virtual_element_call_widget(props, config).ffi()?;
            let client_props = ClientProperties::new("org.mlm.mages", lang, theme);
            let url = settings
                .generate_webview_url(&room, client_props)
                .await
                .ffi()?;
            let widget_base_url = settings.base_url().map(|u| u.to_string());
            info!(
                "Starting Element Call - parent_url: {}, widget_url: {}, widget_base_url: {:?}",
                resolved_parent,
                url,
                widget_base_url
            );
            Ok::<_, FfiError>((
                settings,
                url.to_string(),
                widget_base_url,
                Some(resolved_parent),
            ))
        })?;
        let (driver, handle) = WidgetDriver::new(widget_settings);
        let cap_provider = ElementCallCapabilitiesProvider {};
        self.widget_handles
            .lock()
            .unwrap()
            .insert(session_id, handle.clone());
        let recv_task = {
            let obs = obs.clone();
            spawn_task!(async move {
                while let Some(msg) = handle.recv().await {
                    let _ = catch_unwind(AssertUnwindSafe(|| obs.on_to_widget(msg)));
                }
            })
        };
        self.widget_recv_tasks
            .lock()
            .unwrap()
            .insert(session_id, recv_task);
        let inner2 = self.core.sdk.clone();
        let room_str = room_id.clone();
        let driver_task = spawn_task!(async move {
            if let Ok(rid) = OwnedRoomId::try_from(room_str.as_str()) {
                if let Some(room) = inner2.get_room(&rid) {
                    let _ = driver.run(room, cap_provider).await;
                }
            }
        });
        self.widget_driver_tasks
            .lock()
            .unwrap()
            .insert(session_id, driver_task);
        Ok(CallSessionInfo {
            session_id,
            widget_url,
            widget_base_url,
            parent_url: resolved_parent,
        })
    }

    pub fn call_widget_from_webview(&self, session_id: u64, message: String) -> bool {
        if let Some(handle) = self
            .widget_handles
            .lock()
            .unwrap()
            .get(&session_id)
            .cloned()
        {
            let _ = RT.block_on(async {
                let _ = handle.send(message).await;
            });
            true
        } else {
            false
        }
    }

    pub fn stop_element_call(&self, session_id: u64) -> bool {
        let mut any = false;
        if let Some(h) = self.widget_driver_tasks.lock().unwrap().remove(&session_id) {
            h.abort();
            any = true;
        }
        if let Some(h) = self.widget_recv_tasks.lock().unwrap().remove(&session_id) {
            h.abort();
            any = true;
        }
        self.widget_handles.lock().unwrap().remove(&session_id);
        any
    }

    pub fn register_unifiedpush(
        &self,
        app_id: String,
        pushkey: String,
        gateway_url: String,
        device_display_name: String,
        lang: String,
        profile_tag: Option<String>,
    ) -> bool {
        #[cfg(target_family = "wasm")]
        return false;
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            use matrix_sdk::ruma::push::PushFormat;
            let client_secret = self
                .core
                .sdk
                .user_id()
                .map(|u| {
                    use std::collections::hash_map::DefaultHasher;
                    use std::hash::{Hash, Hasher};
                    let mut hasher = DefaultHasher::new();
                    u.as_str().hash(&mut hasher);
                    format!("{:x}", hasher.finish())
                })
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
            let mut http_data = HttpPusherData::new(gateway_url.clone());
            http_data.format = Some(PushFormat::EventIdOnly);
            http_data.data.insert(
                "default_payload".to_owned(),
                serde_json::json!({ "cs": client_secret }).into(),
            );
            let init = PusherInit {
                ids: PusherIds::new(pushkey.clone(), app_id.clone()),
                kind: PusherKind::Http(http_data),
                app_display_name: "Mages".into(),
                device_display_name,
                profile_tag,
                lang,
            };
            let pusher: Pusher = init.into();
            self.core.sdk.pusher().set(pusher).await.is_ok()
        })
    }

    pub fn unregister_unifiedpush(&self, app_id: String, pushkey: String) -> bool {
        #[cfg(target_family = "wasm")]
        return false;
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            self.core
                .sdk
                .pusher()
                .delete(PusherIds::new(app_id.into(), pushkey.into()))
                .await
                .is_ok()
        })
    }

    pub fn homeserver_url(&self) -> String {
        self.core.sdk.homeserver().to_string()
    }

    pub fn server_name(&self) -> Option<String> {
        self.core
            .sdk
            .session_meta()
            .map(|m| m.user_id.server_name().to_string())
    }

    pub fn cross_signing_status(&self) -> Result<HashMap<String, bool>, FfiError> {
        RT.block_on(async {
            let status = self
                .core
                .sdk
                .encryption()
                .cross_signing_status()
                .await
                .or_ffi("cross-signing unavailable")?;
            let mut map = HashMap::new();
            map.insert("has_master".into(), status.has_master);
            map.insert("has_self_signing".into(), status.has_self_signing);
            map.insert("has_user_signing".into(), status.has_user_signing);
            Ok(map)
        })
    }

    pub fn is_user_verified(&self, user_id: String) -> bool {
        RT.block_on(async {
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                return false;
            };
            let Ok(Some(identity)) = self.core.sdk.encryption().get_user_identity(&uid).await
            else {
                return false;
            };
            identity.is_verified()
        })
    }

    pub fn setup_recovery(&self, observer: Box<dyn RecoveryObserver>) -> bool {
        self.enable_recovery(observer)
    }

    pub fn backup_exists_on_server(&self, fetch: bool) -> bool {
        RT.block_on(self.core.backup_exists_on_server(fetch))
    }

    pub fn set_key_backup_enabled(&self, enabled: bool) -> bool {
        RT.block_on(self.core.set_key_backup_enabled(enabled))
    }

    pub fn retry_by_txn(&self, _room_id: String, txn_id: String) -> bool {
        RT.block_on(async {
            if let Some(handle) = self
                .send_handles_by_txn
                .lock()
                .unwrap()
                .get(&txn_id)
                .cloned()
            {
                handle.unwedge().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn send_attachment_from_path(
        &self,
        room_id: String,
        path: String,
        mime: String,
        filename: Option<String>,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> bool {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(room) = self.core.sdk.get_room(&rid) else {
                return false;
            };
            let data = match std::fs::read(&path) {
                Ok(d) => d,
                Err(_) => return false,
            };
            let mime_type: Mime = mime.parse().unwrap_or(mime::APPLICATION_OCTET_STREAM);
            let fname = filename.unwrap_or_else(|| {
                std::path::Path::new(&path)
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or("file".into())
            });
            let config = matrix_sdk::attachment::AttachmentConfig::new();
            if let Some(p) = progress.as_ref() {
                p.on_progress(0, Some(data.len() as u64));
            }
            let result = room.send_attachment(&fname, &mime_type, data, config).await;
            if let Some(p) = progress {
                p.on_progress(1, Some(1));
            }
            result.is_ok()
        })
    }

    pub fn download_attachment_to_cache_file(
        &self,
        att: AttachmentInfo,
        filename_hint: Option<String>,
    ) -> Result<DownloadResult, FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg("file downloads not supported on web".into()));

        #[cfg(not(target_family = "wasm"))]
        {
            let dir = cache_dir(&self.store_dir);
            platform::ensure_dir(&dir);
            fn sanitize(name: &str) -> String {
                let mut s = String::with_capacity(name.len());
                for ch in name.chars() {
                    if ch.is_ascii_alphanumeric() || "-_.".contains(ch) {
                        s.push(ch);
                    } else {
                        s.push('_');
                    }
                }
                s.trim_matches('_').to_string()
            }
            let hint = filename_hint
                .as_deref()
                .map(sanitize)
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "file.bin".into());
            let out = dir.join(format!("dl_{}_{}", now_ms(), hint));

            RT.block_on(async {
                let source = if let Some(enc) = att.encrypted.as_ref() {
                    let ef: matrix_sdk::ruma::events::room::EncryptedFile =
                        serde_json::from_str(&enc.json).ffi()?;
                    MediaSource::Encrypted(Box::new(ef))
                } else {
                    MediaSource::Plain(att.mxc_uri.clone().into())
                };
                let req = MediaRequestParameters {
                    source,
                    format: MediaFormat::File,
                };
                let data = self
                    .core
                    .sdk
                    .media()
                    .get_media_content(&req, true)
                    .await
                    .ffi()?;
                std::fs::write(&out, &data).ffi()?;
                Ok(DownloadResult {
                    path: out.to_string_lossy().to_string(),
                    bytes: data.len() as u64,
                })
            })
        }
    }

    pub fn fetch_notifications_since(
        &self,
        since_ts_ms: u64,
        max_rooms: u32,
        max_events: u32,
    ) -> Result<Vec<RenderedNotification>, FfiError> {
        RT.block_on(async {
            self.core.ensure_sync_service().await;
            let process_setup = {
                let g = self.core.sync_service.lock().unwrap();
                if let Some(sync) = g.as_ref().cloned() {
                    NotificationProcessSetup::SingleProcess { sync_service: sync }
                } else {
                    NotificationProcessSetup::MultipleProcesses
                }
            };
            let nc = match NotificationClient::new(self.core.sdk.clone(), process_setup).await {
                Ok(v) => v,
                Err(e) => {
                    warn!("NotificationClient::new failed: {e:?}");
                    return Ok(vec![]);
                }
            };
            let mut out = Vec::new();
            for room in self
                .core
                .sdk
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
                    if let Some(rendered) = map_notification_item_to_rendered(&rid, &eid, &item) {
                        out.push(rendered);
                        if out.len() as u32 >= max_events {
                            return Ok(out);
                        }
                    }
                }
            }
            Ok(out)
        })
    }

    pub fn thumbnail_to_cache(
        &self,
        att: AttachmentInfo,
        width: u32,
        height: u32,
        use_crop: bool,
    ) -> Result<String, FfiError> {
        use matrix_sdk::media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings};
        use ruma::events::room::MediaSource;

        let (source, format, name_key) = if let Some(enc) = att.thumbnail_encrypted.as_ref() {
            let ef: EncryptedFile = serde_json::from_str(&enc.json)
                .map_err(|e| FfiError::Msg(format!("thumb enc parse: {e}")))?;
            (
                MediaSource::Encrypted(Box::new(ef)),
                MediaFormat::File,
                enc.url.clone(),
            )
        } else if let Some(mxc) = att.thumbnail_mxc_uri.as_ref() {
            (
                MediaSource::Plain(mxc.clone().into()),
                MediaFormat::File,
                mxc.clone(),
            )
        } else if let Some(enc) = att.encrypted.as_ref() {
            // fetch full encrypted file as fallback
            let ef: EncryptedFile = serde_json::from_str(&enc.json)
                .map_err(|e| FfiError::Msg(format!("file enc parse: {e}")))?;
            (
                MediaSource::Encrypted(Box::new(ef)),
                MediaFormat::File,
                enc.url.clone(),
            )
        } else {
            // Plain primary mxc
            let settings = if use_crop {
                MediaThumbnailSettings::with_method(
                    matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method::Crop,
                    width.into(),
                    height.into(),
                )
            } else {
                MediaThumbnailSettings::new(width.into(), height.into())
            };
            let mxc = att.mxc_uri.clone();
            (
                MediaSource::Plain(mxc.clone().into()),
                MediaFormat::Thumbnail(settings),
                mxc,
            )
        };

        let req = MediaRequestParameters { source, format };

        let dir = cache_dir(&self.store_dir);
        platform::ensure_dir(&dir);
        fn sanitize(name: &str) -> String {
            let mut s = String::with_capacity(name.len());
            for ch in name.chars() {
                if ch.is_ascii_alphanumeric() || "-_.".contains(ch) {
                    s.push(ch);
                } else {
                    s.push('_');
                }
            }
            s.trim_matches('_').to_string()
        }
        let key =
            blake3::hash(format!("{}-{}x{}-{}", name_key, width, height, use_crop).as_bytes())
                .to_hex();
        let ext = att
            .mime
            .as_deref()
            .and_then(|m| m.split('/').nth(1))
            .filter(|e| !e.is_empty())
            .unwrap_or("jpg");
        let fname = format!(
            "thumb_{}_{}x{}{}.{ext}",
            &key[..16],
            width,
            height,
            if use_crop { "_crop" } else { "_scale" }
        );
        let out = dir.join(sanitize(&fname));

        #[cfg(not(target_family = "wasm"))]
        {
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }

            let bytes = RT
                .block_on(async { self.core.sdk.media().get_media_content(&req, true).await })
                .or_else(|_e| {
                    // Fallback only when we asked for a server-side thumb of a plain mxc
                    if matches!(req.format, MediaFormat::Thumbnail(_)) {
                        let req_full = MediaRequestParameters {
                            source: req.source.clone(),
                            format: MediaFormat::File,
                        };
                        RT.block_on(async {
                            self.core
                                .sdk
                                .media()
                                .get_media_content(&req_full, true)
                                .await
                        })
                    } else {
                        Err(_e)
                    }
                })
                .map_err(|e| FfiError::Msg(format!("thumbnail fetch: {e}")))?;

            std::fs::write(&out, &bytes)?;
            Ok(out.to_string_lossy().to_string())
        }
        #[cfg(target_family = "wasm")]
        Err(FfiError::Msg(
            "thumbnail_to_cache: not supported on wasm".into(),
        ))
    }

    pub fn mxc_thumbnail_to_cache(
        &self,
        mxc_uri: String,
        width: u32,
        height: u32,
        crop: bool,
    ) -> Result<String, FfiError> {
        #[cfg(target_family = "wasm")]
        return Err(FfiError::Msg(
            "mxc_thumbnail_to_cache: not supported on wasm".into(),
        ));
        #[cfg(not(target_family = "wasm"))]
        RT.block_on(async {
            let dir = cache_dir(&self.store_dir);
            platform::ensure_dir(&dir);
            let method = if crop {
                matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method::Crop
            } else {
                matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method::Scale
            };
            let settings = matrix_sdk::media::MediaThumbnailSettings::with_method(
                method,
                UInt::from(width.max(1)),
                UInt::from(height.max(1)),
            );
            let req = MediaRequestParameters {
                source: MediaSource::Plain(mxc_uri.clone().into()),
                format: MediaFormat::Thumbnail(settings),
            };
            let bytes = self
                .core
                .sdk
                .media()
                .get_media_content(&req, true)
                .await
                .ffi()?;
            let ext = if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                "png"
            } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
                "jpg"
            } else if bytes.starts_with(b"GIF8") {
                "gif"
            } else {
                "img"
            };
            let key = blake3::hash(format!("{mxc_uri}|{width}x{height}|{crop}").as_bytes())
                .to_hex()
                .to_string();
            let out = dir.join(format!("mxc_thumb_{key}.{ext}"));
            std::fs::write(&out, bytes).ffi()?;
            Ok(out.to_string_lossy().to_string())
        })
    }

    pub fn start_device_verification(
        &self,
        device_id: String,
        listener: Box<dyn VerifEventListener>,
    ) -> String {
        let lis: Arc<dyn VerifEventListener> = Arc::from(listener);
        let emit_err = |lis: &Arc<dyn VerifEventListener>, msg: String| {
            let err = serde_json::to_string(&crate::verification_flow::VerifEvent::Error {
                message: msg,
            })
            .unwrap_or_default();
            lis.on_event(err);
        };

        let me = match self.core.sdk.user_id() {
            Some(u) => u,
            None => {
                emit_err(&lis, "No user session".into());
                return String::new();
            }
        };
        let device_id_owned = OwnedDeviceId::from(device_id);
        let device = match RT.block_on(self.core.sdk.encryption().get_device(me, &device_id_owned))
        {
            Ok(Some(d)) => d,
            Ok(None) => {
                emit_err(&lis, "Device not found".into());
                return String::new();
            }
            Err(e) => {
                emit_err(&lis, format!("Failed to get device: {e}"));
                return String::new();
            }
        };
        let request = match RT.block_on(device.request_verification()) {
            Ok(r) => r,
            Err(e) => {
                emit_err(&lis, format!("Request verification failed: {e}"));
                return String::new();
            }
        };
        let flow_id = request.flow_id().to_owned();
        let req = request;
        let lis2 = lis.clone();

        let h = spawn_task!(async move {
            let stream = crate::verification_flow::drive_verification_request(req, true).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                lis2.on_event(json);
                if matches!(event, crate::verification_flow::VerifEvent::Done)
                    || matches!(
                        event,
                        crate::verification_flow::VerifEvent::Cancelled { .. }
                    )
                {
                    break;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
        flow_id
    }

    pub fn start_user_verification(
        &self,
        user_id: String,
        listener: Box<dyn VerifEventListener>,
    ) -> String {
        let lis: Arc<dyn VerifEventListener> = Arc::from(listener);
        let emit_err = |lis: &Arc<dyn VerifEventListener>, msg: String| {
            let err = serde_json::to_string(&crate::verification_flow::VerifEvent::Error {
                message: msg,
            })
            .unwrap_or_default();
            lis.on_event(err);
        };

        let Ok(uid) = user_id.parse::<OwnedUserId>() else {
            emit_err(&lis, "Invalid user ID".into());
            return String::new();
        };
        let identity = match RT.block_on(self.core.sdk.encryption().get_user_identity(&uid)) {
            Ok(Some(i)) => i,
            Ok(None) => {
                emit_err(&lis, "User identity not found".into());
                return String::new();
            }
            Err(e) => {
                emit_err(&lis, format!("Failed to get user identity: {e}"));
                return String::new();
            }
        };
        let request = match RT.block_on(identity.request_verification()) {
            Ok(r) => r,
            Err(e) => {
                emit_err(&lis, format!("Request verification failed: {e}"));
                return String::new();
            }
        };
        let flow_id = request.flow_id().to_owned();
        let req = request;
        let lis2 = lis.clone();

        let h = spawn_task!(async move {
            let stream = crate::verification_flow::drive_verification_request(req, true).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                lis2.on_event(json);
                if matches!(event, crate::verification_flow::VerifEvent::Done)
                    || matches!(
                        event,
                        crate::verification_flow::VerifEvent::Cancelled { .. }
                    )
                {
                    break;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
        flow_id
    }

    pub fn cancel_verification(&self, flow_id: String) -> bool {
        let me = match self.core.sdk.user_id() {
            Some(u) => u,
            None => return false,
        };

        RT.block_on(async {
            if let Some(v) = self
                .core
                .sdk
                .encryption()
                .get_verification(me, &flow_id)
                .await
            {
                match v {
                    Verification::SasV1(sas) => sas.cancel().await.is_ok(),
                    _ => false,
                }
            } else if let Some(req) = self
                .core
                .sdk
                .encryption()
                .get_verification_request(me, &flow_id)
                .await
            {
                req.cancel().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn confirm_sas(&self, flow_id: String) -> bool {
        let me = match self.core.sdk.user_id() {
            Some(u) => u,
            None => return false,
        };

        RT.block_on(async {
            if let Some(Verification::SasV1(sas)) = self
                .core
                .sdk
                .encryption()
                .get_verification(me, &flow_id)
                .await
            {
                sas.confirm().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn accept_verification_request(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
    ) -> bool {
        let uid = match other_user_id {
            Some(u) => match u.parse::<OwnedUserId>() {
                Ok(uid) => uid,
                Err(_) => return false,
            },
            None => match self.core.sdk.user_id() {
                Some(u) => u.to_owned(),
                None => return false,
            },
        };

        RT.block_on(async {
            for _ in 0..30 {
                if let Some(req) = self
                    .core
                    .sdk
                    .encryption()
                    .get_verification_request(&uid, &flow_id)
                    .await
                {
                    return req.accept().await.is_ok();
                }
                tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            }
            false
        })
    }

    pub fn accept_sas(&self, flow_id: String, other_user_id: Option<String>) -> bool {
        let uid = match other_user_id {
            Some(u) => match u.parse::<OwnedUserId>() {
                Ok(uid) => uid,
                Err(_) => return false,
            },
            None => match self.core.sdk.user_id() {
                Some(u) => u.to_owned(),
                None => return false,
            },
        };

        RT.block_on(async {
            for _ in 0..30 {
                if let Some(verification) = self
                    .core
                    .sdk
                    .encryption()
                    .get_verification(&uid, &flow_id)
                    .await
                {
                    if let Some(sas) = verification.sas() {
                        return sas.accept().await.is_ok();
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            }
            false
        })
    }

    pub fn accept_and_observe_verification(
        &self,
        flow_id: String,
        other_user_id: String,
        listener: Box<dyn VerifEventListener>,
    ) -> bool {
        let lis: Arc<dyn VerifEventListener> = Arc::from(listener);

        let uid = match other_user_id.parse::<OwnedUserId>() {
            Ok(u) => u,
            Err(_) => return false,
        };

        let request = match RT.block_on(
            self.core
                .sdk
                .encryption()
                .get_verification_request(&uid, &flow_id),
        ) {
            Some(req) => req,
            None => return false,
        };

        let lis2 = lis.clone();
        let h = spawn_task!(async move {
            let stream = crate::verification_flow::drive_incoming_verification(request).await;
            futures_util::pin_mut!(stream);
            while let Some(event) = stream.next().await {
                let json = serde_json::to_string(&event).unwrap_or_default();
                lis2.on_event(json);
                if matches!(event, crate::verification_flow::VerifEvent::Done)
                    || matches!(
                        event,
                        crate::verification_flow::VerifEvent::Cancelled { .. }
                    )
                {
                    break;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
        true
    }

    pub fn shutdown(&self) {
        self.shutdown_inner();
    }
}

impl Client {
    fn shutdown_inner(&self) {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        abort_all_subs!(self;
            timeline_subs, typing_subs, connection_subs, inbox_subs,
            receipts_subs, room_list_subs, call_subs, live_location_subs,
            recovery_state_subs, backup_state_subs, widget_driver_tasks, widget_recv_tasks
        );
    }

    async fn maybe_update_device_name(client: &Client, device_name: Option<String>) {
        if let Some(name) = device_name
            && let Some(device_id) = client.core.sdk.device_id()
        {
            use matrix_sdk::ruma::api::client::device::update_device;
            let mut req = update_device::v3::Request::new(device_id.to_owned());
            req.display_name = Some(name);
            let _ = client.core.sdk.send(req).await;
        }
    }

    async fn persist_current_session(client: &Client) {
        platform::build_and_persist_session(&client.core.sdk, &client.store_dir).await;
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        self.shutdown_inner();
    }
}

pub(crate) fn build_unstable_poll_content(
    def: &PollDefinition,
) -> Result<NewUnstablePollStartEventContent, FfiError> {
    let kind = match def.kind {
        PollKind::Disclosed => RumaPollKind::Disclosed,
        PollKind::Undisclosed => RumaPollKind::Undisclosed,
    };
    let answers: Vec<UnstablePollAnswer> = def
        .answers
        .iter()
        .enumerate()
        .map(|(i, text)| UnstablePollAnswer::new(format!("{i}"), text))
        .collect();
    let poll_answers = UnstablePollAnswers::try_from(answers).ffi()?;
    let mut block = UnstablePollStartContentBlock::new(&def.question, poll_answers);
    block.kind = kind;
    block.max_selections = UInt::try_from(def.max_selections as u64).unwrap_or(UInt::from(1u32));
    Ok(NewUnstablePollStartEventContent::new(block))
}

fn map_sender_profile(
    _sender_mxid: &ruma::UserId,
    details: &TimelineDetails<matrix_sdk_ui::timeline::Profile>,
) -> (Option<String>, Option<String>) {
    match details {
        TimelineDetails::Ready(p) => (
            p.display_name.clone(),
            p.avatar_url.as_ref().map(|u| u.to_string()),
        ),
        _ => {
            // No profile yet (Unavailable/Pending/Error)
            // Return None so Kotlin can fall back to localpart formatting if it wants
            (None, None)
        }
    }
}

fn extract_reactions(content: &TimelineItemContent, me: &str) -> Vec<ReactionSummary> {
    let mut reactions = Vec::new();
    if let Some(reactions_map) = content.reactions() {
        for (key, senders) in reactions_map.iter() {
            let count = senders.len() as u32;
            let me_reacted = senders.keys().any(|sender| sender.as_str() == me);
            reactions.push(ReactionSummary {
                key: key.clone(),
                count,
                mine: me_reacted,
            });
        }
    }
    reactions
}

fn map_timeline_event(
    ev: &EventTimelineItem,
    room_id: &str,
    item_id: Option<&str>,
    me: &str,
) -> Option<MessageEvent> {
    let ts: u64 = ev.timestamp().0.into();

    let direct_event_id = ev.event_id().map(|e| e.to_string());

    let sdk_send_state = ev.send_state();
    let (send_state, event_id_from_send_state) = match sdk_send_state {
        Some(EventSendState::NotSentYet { .. }) => (Some(SendState::Sending), None),
        Some(EventSendState::SendingFailed { .. }) => (Some(SendState::Failed), None),
        Some(EventSendState::Sent { event_id }) => {
            (Some(SendState::Sent), Some(event_id.to_string()))
        }
        None => {
            if direct_event_id.is_some() {
                (Some(SendState::Sent), None)
            } else {
                (Some(SendState::Sending), None)
            }
        }
    };

    let event_id = direct_event_id
        .or(event_id_from_send_state)
        .unwrap_or_default();

    let txn_id = ev.transaction_id().map(|t| t.to_string());

    let item_id_str = item_id
        .map(|s| s.to_string())
        .unwrap_or_else(|| match ev.identifier() {
            TimelineEventItemId::EventId(e) => e.to_string(),
            TimelineEventItemId::TransactionId(t) => t.to_string(),
        });

    let mut reply_to_event_id: Option<String> = None;
    let mut reply_to_sender: Option<String> = None;
    let mut reply_to_body: Option<String> = None;
    let mut attachment: Option<AttachmentInfo> = None;
    let thread_root_event_id = ev.content().thread_root().map(|id| id.to_string());
    let body: String;
    let mut formatted_body: Option<String> = None;
    let mut is_edited = false;
    let mut poll_data: Option<PollData> = None;
    let mut reply_to_sender_display_name: Option<String> = None;
    let mut event_type = EventType::Message;
    let mut live_location: Option<LiveLocationEvent> = None;

    match ev.content() {
        TimelineItemContent::MsgLike(ml) => {
            if let Some(details) = &ml.in_reply_to {
                reply_to_event_id = Some(details.event_id.to_string());
                if let TimelineDetails::Ready(embed) = &details.event {
                    reply_to_sender = Some(embed.sender.to_string());

                    let (dn, _av) = map_sender_profile(&embed.sender, &embed.sender_profile);
                    reply_to_sender_display_name = dn;

                    if let Some(m) = embed.content.as_message() {
                        reply_to_body = Some(m.body().to_owned());
                    }
                }
            }

            match &ml.kind {
                MsgLikeKind::Message(msg) => {
                    attachment = extract_attachment(msg);
                    is_edited = msg.is_edited();
                    let raw = msg.body();
                    body = if reply_to_event_id.is_some() {
                        strip_reply_fallback(raw)
                    } else {
                        raw.to_owned()
                    };

                    use matrix_sdk::ruma::events::room::message::MessageType;
                    match msg.msgtype() {
                        MessageType::Text(c) => {
                            formatted_body = c.formatted.as_ref().map(|f| f.body.clone());
                        }
                        MessageType::Notice(c) => {
                            formatted_body = c.formatted.as_ref().map(|f| f.body.clone());
                        }
                        MessageType::Emote(c) => {
                            formatted_body = c.formatted.as_ref().map(|f| f.body.clone());
                        }
                        _ => {}
                    }
                }
                MsgLikeKind::Poll(poll_state) => {
                    let data = map_poll_state(poll_state, me);
                    body = data.question.clone();
                    poll_data = Some(data);
                    event_type = EventType::Poll;
                }
                MsgLikeKind::Sticker(_) => {
                    body = render_msg_like(ev, ml);
                    event_type = EventType::Sticker;
                }
                _ => {
                    body = render_msg_like(ev, ml);
                }
            }
        }
        TimelineItemContent::MembershipChange(_) => {
            body = render_timeline_text(ev);
            event_type = EventType::MembershipChange;
        }
        TimelineItemContent::ProfileChange(_) => {
            body = render_timeline_text(ev);
            event_type = EventType::ProfileChange;
        }
        TimelineItemContent::OtherState(state) => {
            body = render_timeline_text(ev);
            event_type = map_other_state_type(state);
        }
        TimelineItemContent::CallInvite => {
            body = String::new();
            event_type = EventType::CallInvite;
        }
        TimelineItemContent::RtcNotification => {
            body = "Call started".to_string();
            event_type = EventType::CallNotification;
        }
        TimelineItemContent::LiveLocation(state) => {
            body = render_timeline_text(ev);
            event_type = EventType::LiveLocation;
            live_location = state.latest_location().map(|location| LiveLocationEvent {
                user_id: ev.sender().to_string(),
                geo_uri: location.geo_uri().to_owned(),
                ts_ms: location.ts().0.into(),
                is_live: state.is_live(),
            });
        }
        _ => {
            body = render_timeline_text(ev);
        }
    }

    if body.trim().is_empty() {
        return None;
    }

    let (sender_display_name, sender_avatar_url) =
        map_sender_profile(ev.sender(), ev.sender_profile());

    let reactions = extract_reactions(ev.content(), me);

    Some(MessageEvent {
        item_id: item_id_str,
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        sender_display_name,
        sender_avatar_url,
        body,
        formatted_body,
        timestamp_ms: ts,
        send_state,
        txn_id,
        reply_to_event_id,
        reply_to_sender,
        reply_to_sender_display_name,
        reply_to_body,
        attachment,
        thread_root_event_id,
        is_edited,
        poll_data,
        reactions,
        event_type,
        live_location,
    })
}

fn extract_attachment(msg: &matrix_sdk_ui::timeline::Message) -> Option<AttachmentInfo> {
    use matrix_sdk::ruma::events::room::{MediaSource, message::MessageType as MT};

    // Helper: split a MediaSource into MXC URI and optional EncFile
    fn split_source(source: &MediaSource) -> (String, Option<EncFile>) {
        match source {
            MediaSource::Plain(url) => (url.to_string(), None),
            MediaSource::Encrypted(file) => {
                let url = file.url.to_string();
                let enc = enc_to_record(file.as_ref());
                (url, Some(enc))
            }
        }
    }

    // Helper: same, but for Option<&MediaSource> (used for thumbnails)
    fn split_opt_source(source: Option<&MediaSource>) -> (Option<String>, Option<EncFile>) {
        match source {
            Some(MediaSource::Plain(url)) => (Some(url.to_string()), None),
            Some(MediaSource::Encrypted(file)) => {
                let url = file.url.to_string();
                let enc = enc_to_record(file.as_ref());
                (Some(url), Some(enc))
            }
            None => (None, None),
        }
    }

    match msg.msgtype() {
        MT::Image(c) => {
            // main image source
            let (mxc_uri, encrypted) = split_source(&c.source);
            let file_name = Some(c.filename.clone().unwrap_or_else(|| c.body.clone()));

            // metadata + thumbnail
            let (w, h, size, mime, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::Image,
                mxc_uri,
                file_name,
                mime,
                size_bytes: size,
                width: w,
                height: h,
                duration_ms: None,
                thumbnail_mxc_uri: thumb_mxc,
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        MT::Video(c) => {
            let (mxc_uri, encrypted) = split_source(&c.source);
            let file_name = Some(c.filename.clone().unwrap_or_else(|| c.body.clone()));

            let (w, h, size, mime, dur, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        info.duration.map(|d| d.as_millis() as u64),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::Video,
                mxc_uri: mxc_uri.clone(),
                file_name,
                mime,
                size_bytes: size,
                width: w,
                height: h,
                duration_ms: dur,
                // Fallback to full video if no explicit thumbnail
                thumbnail_mxc_uri: thumb_mxc.or_else(|| Some(mxc_uri.clone())),
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        MT::File(c) => {
            let (mxc_uri, encrypted) = split_source(&c.source);
            let file_name = Some(c.filename.clone().unwrap_or_else(|| c.body.clone()));

            let (size, mime, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::File,
                mxc_uri,
                file_name,
                mime,
                size_bytes: size,
                width: None,
                height: None,
                duration_ms: None,
                thumbnail_mxc_uri: thumb_mxc,
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        _ => None,
    }
}

fn enc_to_record(ef: &EncryptedFile) -> EncFile {
    EncFile {
        url: ef.url.to_string(),
        json: serde_json::to_string(ef).unwrap_or_default(),
    }
}

async fn map_event_id_via_timeline(
    mgr: &TimelineManager,
    client: &SdkClient,
    rid: &ruma::OwnedRoomId,
    eid: &ruma::OwnedEventId,
) -> Option<MessageEvent> {
    let tl = mgr.timeline_for(rid).await?;
    let _ = tl.fetch_details_for_event(eid.as_ref()).await;

    let item = tl.item_by_event_id(eid).await?;
    let item_id = match item.identifier() {
        TimelineEventItemId::EventId(id) => id.to_string(),
        TimelineEventItemId::TransactionId(id) => id.to_string(),
    };
    let me = client.user_id().map(|u| u.to_string()).unwrap_or_default();
    map_timeline_event(&item, rid.as_str(), Some(&item_id), &me)
}

fn render_timeline_text(ev: &EventTimelineItem) -> String {
    match ev.content() {
        TimelineItemContent::MsgLike(msg_like) => render_msg_like(ev, msg_like),
        TimelineItemContent::MembershipChange(change) => render_membership_change(ev, change),
        TimelineItemContent::ProfileChange(change) => render_profile_change(ev, change),
        TimelineItemContent::OtherState(state) => render_other_state(ev, state),
        TimelineItemContent::LiveLocation(_) => render_other_state_like(ev),

        TimelineItemContent::FailedToParseMessageLike { event_type, .. } => {
            format!("Unsupported message-like event: {}", event_type)
        }
        TimelineItemContent::FailedToParseState { event_type, .. } => {
            format!("Unsupported state event: {}", event_type)
        }

        // don’t show call signalling messages
        TimelineItemContent::CallInvite => String::new(),
        TimelineItemContent::RtcNotification => String::new(),
    }
}

fn render_other_state_like(ev: &EventTimelineItem) -> String {
    format!("{} shared live location", ev.sender())
}

fn render_msg_like(_ev: &EventTimelineItem, ml: &MsgLikeContent) -> String {
    use MsgLikeKind::*;
    match &ml.kind {
        Message(m) => render_message_text(m),
        Sticker(_s) => "sent a sticker".to_string(),
        Poll(_p) => "started a poll".to_string(),
        Redacted => "Message deleted".to_string(),
        UnableToDecrypt(_e) => "Unable to decrypt this message".to_string(),
        Other(_) => "Custom message".to_string(),
    }
}

fn render_message_text(msg: &matrix_sdk_ui::timeline::Message) -> String {
    let s = msg.body().to_owned();
    if s.trim().is_empty() {
        "Encrypted or unsupported message. Verify this session or restore keys to view.".to_owned()
    } else {
        s
    }
}

fn is_call_noise(event: &AnySyncTimelineEvent) -> bool {
    let ty = event.event_type().to_string();

    (ty.starts_with("m.rtc.") && !ty.contains("notify"))
        || (ty.starts_with("org.matrix.msc3401.call.") && !ty.contains("notify"))
        || (ty.starts_with("m.call.") && !ty.contains("notify") && !ty.contains("hangup"))
}

pub(crate) fn timeline_event_filter(
    event: &AnySyncTimelineEvent,
    rules: &RoomVersionRules,
) -> bool {
    default_event_filter(event, rules) && !is_call_noise(event)
}

pub(crate) async fn latest_room_event_for(
    mgr: &TimelineManager,
    room: &Room,
) -> Option<LatestRoomEvent> {
    let rid = room.room_id().to_owned();
    let tl = mgr.timeline_for(&rid).await?;

    // Walk backwards to find the latest event we can turn into a room-list preview.
    let items = tl.items().await;
    let ev = items.iter().rev().find_map(|it| it.as_event())?;

    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev.event_id().map(|e| e.to_string()).unwrap_or_default();
    let sender = ev.sender().to_string();

    let mut msgtype: Option<String> = None;
    let mut event_type = "m.room.message".to_owned();
    let mut is_redacted = false;
    let mut is_encrypted = false;
    let body: Option<String>;

    use matrix_sdk::ruma::events::room::message::MessageType;

    match ev.content() {
        TimelineItemContent::MsgLike(ml) => match &ml.kind {
            MsgLikeKind::Message(m) => {
                let text = render_message_text(m);
                if text.trim().is_empty() {
                    return None;
                }
                body = Some(text);

                match m.msgtype() {
                    MessageType::Image(_) => msgtype = Some("m.image".to_owned()),
                    MessageType::Video(_) => msgtype = Some("m.video".to_owned()),
                    MessageType::Audio(_) => msgtype = Some("m.audio".to_owned()),
                    MessageType::File(_) => msgtype = Some("m.file".to_owned()),
                    MessageType::Text(_) => msgtype = Some("m.text".to_owned()),
                    MessageType::Notice(_) => msgtype = Some("m.notice".to_owned()),
                    MessageType::Emote(_) => msgtype = Some("m.emote".to_owned()),
                    MessageType::Location(_) => msgtype = Some("m.location".to_owned()),
                    _ => {}
                }
            }
            MsgLikeKind::Sticker(_) => {
                msgtype = Some("m.sticker".to_owned());
                body = None;
            }
            MsgLikeKind::Poll(_) => {
                event_type = "m.poll.start".to_owned();
                body = None;
            }
            MsgLikeKind::Redacted => {
                is_redacted = true;
                body = None;
            }
            MsgLikeKind::UnableToDecrypt(_) => {
                is_encrypted = true;
                body = None;
            }
            MsgLikeKind::Other(_) => {
                body = Some("Custom event".to_owned());
            }
        },
        TimelineItemContent::CallInvite => {
            event_type = "m.call.invite".to_owned();
            body = None;
        }
        TimelineItemContent::RtcNotification => {
            event_type = "m.rtc.notification".to_owned();
            body = Some("Call started".to_owned());
        }
        _ => {
            let text = render_timeline_text(ev);
            if text.trim().is_empty() {
                return None;
            }
            body = Some(text);
        }
    }

    Some(LatestRoomEvent {
        event_id,
        sender,
        body,
        msgtype,
        event_type,
        timestamp: ts as i64,
        is_redacted,
        is_encrypted,
    })
}

fn strip_reply_fallback(body: &str) -> String {
    let _lines = body.lines();
    let mut consumed = 0usize;
    // Consume leading quoted lines (starting with '>')
    for l in body.lines() {
        if l.starts_with('>') {
            consumed += 1;
        } else {
            break;
        }
    }
    // Optionally consume a single blank line after the quote block
    let remaining: Vec<&str> = body.lines().collect();
    let mut start = consumed;
    if start < remaining.len() && remaining[start].trim().is_empty() && consumed > 0 {
        start += 1;
    }
    remaining[start..]
        .join("\n")
        .if_empty_then(|| body.to_owned())
}

trait IfEmptyThen {
    fn if_empty_then<F: FnOnce() -> String>(self, f: F) -> String;
}
impl IfEmptyThen for String {
    fn if_empty_then<F: FnOnce() -> String>(self, f: F) -> String {
        if self.trim().is_empty() { f() } else { self }
    }
}

fn render_membership_change(
    ev: &EventTimelineItem,
    ch: &matrix_sdk_ui::timeline::RoomMembershipChange,
) -> String {
    use matrix_sdk_ui::timeline::MembershipChange as MC;

    let actor = ev.sender().to_string();
    let subject = ch.user_id().to_string();

    match ch.change() {
        Some(MC::Joined) => format!("{subject} joined the room"),
        Some(MC::Left) => format!("{subject} left the room"),
        Some(MC::Invited) => format!("{actor} invited {subject}"),
        Some(MC::Kicked) => format!("{actor} removed {subject}"),
        Some(MC::Banned) => format!("{actor} banned {subject}"),
        Some(MC::Unbanned) => format!("{actor} unbanned {subject}"),
        Some(MC::InvitationAccepted) => format!("{subject} accepted the invite"),
        Some(MC::InvitationRejected) => format!("{subject} rejected the invite"),
        Some(MC::InvitationRevoked) => format!("{actor} revoked the invite for {subject}"),
        Some(MC::KickedAndBanned) => format!("{actor} removed and banned {subject}"),
        Some(MC::Knocked) => format!("{subject} knocked"),
        Some(MC::KnockAccepted) => format!("{actor} accepted {subject}"),
        Some(MC::KnockDenied) => format!("{actor} denied {subject}"),
        _ => format!("{subject} updated membership"),
    }
}

fn render_profile_change(
    _ev: &EventTimelineItem,
    pc: &matrix_sdk_ui::timeline::MemberProfileChange,
) -> String {
    let subject = pc.user_id().to_string();

    if let Some(ch) = pc.displayname_change() {
        match (&ch.old, &ch.new) {
            (None, Some(new)) => return format!("{subject} set their display name to “{new}”"),
            (Some(old), Some(new)) if old != new => {
                return format!("{subject} changed their display name from “{old}” to “{new}”");
            }
            (Some(_), None) => return format!("{subject} removed their display name"),
            _ => {}
        }
    }

    if pc.avatar_url_change().is_some() {
        return format!("{subject} updated their avatar");
    }

    format!("{subject} updated their profile")
}

fn map_other_state_type(s: &matrix_sdk_ui::timeline::OtherState) -> EventType {
    use matrix_sdk_ui::timeline::AnyOtherStateEventContentChange as A;

    match s.content() {
        A::RoomName(_) => EventType::RoomName,
        A::RoomTopic(_) => EventType::RoomTopic,
        A::RoomAvatar(_) => EventType::RoomAvatar,
        A::RoomEncryption(_) => EventType::RoomEncryption,
        A::RoomPinnedEvents(_) => EventType::RoomPinnedEvents,
        A::RoomPowerLevels(_) => EventType::RoomPowerLevels,
        A::RoomCanonicalAlias(_) => EventType::RoomCanonicalAlias,
        _ => EventType::OtherState,
    }
}

fn render_other_state(ev: &EventTimelineItem, s: &matrix_sdk_ui::timeline::OtherState) -> String {
    use matrix_sdk::ruma::events::StateEventContentChange;
    use matrix_sdk_ui::timeline::AnyOtherStateEventContentChange as A;

    let actor = ev.sender().to_string();
    let ty = s.content().event_type().to_string();

    // Drop MatrixRTC membership state spam
    if ty == "org.matrix.msc3401.call.member" || ty == "m.call.member" || ty == "m.rtc.member" {
        return String::new();
    }

    match s.content() {
        A::RoomName(c) => {
            let mut name = "";
            if let StateEventContentChange::Original { content, .. } = c {
                name = &content.name;
            }
            format!("{actor} changed the room name to {name}")
        }
        A::RoomTopic(c) => {
            let mut topic = "";
            if let StateEventContentChange::Original { content, .. } = c {
                topic = &content.topic;
            }
            format!("{actor} changed the topic to {topic}")
        }
        A::RoomAvatar(_) => format!("{actor} changed the room avatar"),
        A::RoomEncryption(_) => "Encryption enabled for this room".to_string(),
        A::RoomPinnedEvents(_) => format!("{actor} updated pinned events"),
        A::RoomPowerLevels(_) => format!("{actor} changed power levels"),
        A::RoomCanonicalAlias(_) => format!("{actor} changed the main address"),
        _ => format!("{actor} updated state: {ty}"),
    }
}

fn _mxc_from_media_source(src: &matrix_sdk::ruma::events::room::MediaSource) -> Option<String> {
    use matrix_sdk::ruma::events::room::MediaSource as MS;
    match src {
        MS::Plain(mxc) => Some(mxc.to_string()),
        MS::Encrypted(file) => Some(file.url.to_string()),
    }
}

pub(crate) fn missing_reply_event_id(
    ev: &EventTimelineItem,
) -> Option<matrix_sdk::ruma::OwnedEventId> {
    if let TimelineItemContent::MsgLike(ml) = ev.content() {
        if let Some(details) = &ml.in_reply_to {
            use matrix_sdk_ui::timeline::TimelineDetails::*;
            if !matches!(details.event, Ready(_)) {
                return Some(details.event_id.clone());
            }
        }
    }
    None
}

pub(crate) fn map_vec_diff(
    diff: VectorDiff<Arc<TimelineItem>>,
    room_id: &OwnedRoomId,
    tl: &Arc<Timeline>,
    me: &str,
) -> Option<TimelineDiffKind> {
    match diff {
        VectorDiff::Append { values } => {
            let vals: Vec<_> = values
                .iter()
                .filter_map(|v| {
                    v.as_event().and_then(|ei| {
                        fetch_reply_if_needed(ei, tl);
                        map_timeline_event(
                            ei,
                            room_id.as_str(),
                            Some(&v.unique_id().0.to_string()),
                            &me,
                        )
                    })
                })
                .collect();

            if vals.is_empty() {
                None
            } else {
                Some(TimelineDiffKind::Append { values: vals })
            }
        }

        VectorDiff::PushBack { value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(
                    ei,
                    room_id.as_str(),
                    Some(&value.unique_id().0.to_string()),
                    &me,
                )
            })
            .map(|v| TimelineDiffKind::PushBack { value: v }),

        VectorDiff::PushFront { value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(
                    ei,
                    room_id.as_str(),
                    Some(&value.unique_id().0.to_string()),
                    &me,
                )
            })
            .map(|v| TimelineDiffKind::PushFront { value: v }),

        VectorDiff::Set { index: _, value } => {
            let item_id = value.unique_id().0.to_string();
            value
                .as_event()
                .and_then(|ei| {
                    fetch_reply_if_needed(ei, tl);
                    map_timeline_event(ei, room_id.as_str(), Some(&item_id), &me)
                })
                .map(|v| TimelineDiffKind::UpdateByItemId { item_id, value: v })
        }

        VectorDiff::Insert { index: _, value } => {
            let item_id = value.unique_id().0.to_string();
            value
                .as_event()
                .and_then(|ei| {
                    fetch_reply_if_needed(ei, tl);
                    map_timeline_event(ei, room_id.as_str(), Some(&item_id), &me)
                })
                .map(|v| TimelineDiffKind::UpsertByItemId { item_id, value: v })
        }

        VectorDiff::Remove { index: _ } => {
            // Cannot safely map - return None and let Reset handle consistency
            None
        }

        VectorDiff::PopBack => Some(TimelineDiffKind::PopBack),
        VectorDiff::PopFront => Some(TimelineDiffKind::PopFront),

        VectorDiff::Truncate { length } => Some(TimelineDiffKind::Truncate {
            length: length as u32,
        }),

        VectorDiff::Clear => Some(TimelineDiffKind::Clear),

        VectorDiff::Reset { values } => {
            let vals: Vec<_> = values
                .iter()
                .filter_map(|v| {
                    v.as_event().and_then(|ei| {
                        fetch_reply_if_needed(ei, tl);
                        map_timeline_event(
                            ei,
                            room_id.as_str(),
                            Some(&v.unique_id().0.to_string()),
                            &me,
                        )
                    })
                })
                .collect();
            Some(TimelineDiffKind::Reset { values: vals })
        }
    }
}

fn map_poll_state(state: &matrix_sdk_ui::timeline::PollState, me: &str) -> PollData {
    let results = state.results();

    let is_ended = results.end_time.is_some();

    let mut vote_counts: HashMap<String, u32> = HashMap::new();
    let mut my_votes: Vec<String> = Vec::new();

    for (answer_id, voters) in &results.votes {
        vote_counts.insert(answer_id.clone(), voters.len() as u32);

        if voters.iter().any(|u| u == me) {
            my_votes.push(answer_id.clone());
        }
    }

    let total_votes: u32 = vote_counts.values().sum();
    let max_votes = if is_ended {
        vote_counts.values().max().cloned().unwrap_or(0)
    } else {
        0
    };

    let options: Vec<PollOption> = results
        .answers
        .iter()
        .map(|a| {
            let count = *vote_counts.get(&a.id).unwrap_or(&0);
            PollOption {
                id: a.id.clone(),
                text: a.text.clone(),
                votes: count,
                is_selected: my_votes.contains(&a.id),
                is_winner: is_ended && count > 0 && count == max_votes,
            }
        })
        .collect();

    let kind = match results.kind {
        matrix_sdk::ruma::events::poll::start::PollKind::Disclosed => PollKind::Disclosed,
        matrix_sdk::ruma::events::poll::start::PollKind::Undisclosed => PollKind::Undisclosed,
        _ => PollKind::Disclosed,
    };

    PollData {
        question: results.question,
        kind,
        max_selections: results.max_selections as u32,
        options,
        votes: vote_counts,
        my_selections: my_votes,
        total_votes,
        is_ended,
    }
}

fn fetch_reply_if_needed(ei: &EventTimelineItem, tl: &Arc<Timeline>) {
    if let Some(eid) = missing_reply_event_id(ei) {
        let tlc = tl.clone();
        spawn_detached!(async move {
            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
        });
    }
}

fn should_filter_notification_event(ev: &AnySyncTimelineEvent) -> bool {
    match ev {
        AnySyncTimelineEvent::State(_) => true,
        _ => false,
    }
}

async fn count_visible_room_view(tl: &Arc<Timeline>, rid: &OwnedRoomId, me: &str) -> usize {
    let items = tl.items().await;

    items
        .iter()
        .filter_map(|it| {
            let ev = it.as_event()?;
            let item_id = it.unique_id().0.to_string();

            // Use your existing mapper so “visible” matches Kotlin.
            let mapped = map_timeline_event(ev, rid.as_str(), Some(&item_id), me)?;

            // Room view hides thread replies.
            if mapped.thread_root_event_id.is_some() {
                return None;
            }
            Some(())
        })
        .count()
}

async fn is_at_timeline_start(tl: &Arc<Timeline>) -> bool {
    let items = tl.items().await;
    items.iter().any(|it| it.is_timeline_start())
}

async fn map_visible_room_view(
    tl: &Arc<Timeline>,
    rid: &OwnedRoomId,
    me: &str,
) -> Vec<MessageEvent> {
    let items = tl.items().await;
    items
        .iter()
        .filter_map(|it| {
            it.as_event().and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, rid.as_str(), Some(&it.unique_id().0.to_string()), me)
            })
        })
        // room view hides thread replies
        .filter(|ev| ev.thread_root_event_id.is_none())
        .collect()
}

async fn map_room_view_all(tl: &Arc<Timeline>, rid: &OwnedRoomId, me: &str) -> Vec<MessageEvent> {
    let items = tl.items().await;
    items
        .iter()
        .filter_map(|it| {
            it.as_event().and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, rid.as_str(), Some(&it.unique_id().0.to_string()), me)
            })
        })
        .collect()
}

async fn backfill_until_min_visible(
    tl: &Arc<Timeline>,
    rid: &OwnedRoomId,
    me: &str,
    min_visible: usize,
) {
    for _ in 0..MAX_BACKFILL_ROUNDS {
        if is_at_timeline_start(tl).await {
            break;
        }

        let visible_now = map_visible_room_view(tl, rid, me).await.len();
        if visible_now >= min_visible {
            break;
        }

        // adds more events to the start of the timeline
        let hit_start = tl.paginate_backwards(BACKFILL_CHUNK).await.unwrap_or(false);
        if hit_start {
            break;
        }
    }
}

pub(crate) async fn emit_timeline_reset_filled(
    obs: &Arc<dyn TimelineObserver>,
    tl: &Arc<Timeline>,
    rid: &OwnedRoomId,
    me: &str,
) {
    let mut visible = map_visible_room_view(tl, rid, me).await;

    // If empty/small and not at timeline start, backfill
    if visible.len() < MIN_VISIBLE_AFTER_RESET && !is_at_timeline_start(tl).await {
        backfill_until_min_visible(tl, rid, me, MIN_VISIBLE_AFTER_RESET).await;
        visible = map_visible_room_view(tl, rid, me).await;
    }

    let mapped = map_room_view_all(tl, rid, me).await;

    let _ = catch_unwind(AssertUnwindSafe(|| {
        obs.on_diff(TimelineDiffKind::Reset { values: mapped })
    }));
}

async fn paginate_backwards_visible(
    tl: &Arc<Timeline>,
    rid: &OwnedRoomId,
    me: &str,
    want_more_visible: usize,
) -> bool {
    const CHUNK: u16 = 20;
    const MAX_ROUNDS: u8 = 8;

    let before = count_visible_room_view(tl, rid, me).await;
    let target = before.saturating_add(want_more_visible);

    let mut hit_start = false;

    for _ in 0..MAX_ROUNDS {
        hit_start = tl.paginate_backwards(CHUNK).await.unwrap_or(false);

        let after = count_visible_room_view(tl, rid, me).await;
        if after >= target || hit_start {
            break;
        }
    }

    hit_start
}

fn classify_notification_kind_and_expiry(
    ev: &AnySyncTimelineEvent,
) -> (NotificationKind, Option<u64>) {
    use ruma::events::rtc::notification::NotificationType as RtcType;

    match ev {
        AnySyncTimelineEvent::MessageLike(m) => match m {
            AnySyncMessageLikeEvent::RtcNotification(rtc) => {
                if let Some(o) = rtc.as_original() {
                    let expires_at_ms: u64 = o
                        .content
                        .expiration_ts(o.origin_server_ts, None)
                        .get()
                        .into();

                    let kind = match o.content.notification_type {
                        RtcType::Ring => NotificationKind::CallRing,
                        _ => NotificationKind::CallNotify,
                    };
                    (kind, Some(expires_at_ms))
                } else {
                    (NotificationKind::CallNotify, None)
                }
            }
            AnySyncMessageLikeEvent::CallNotify(_) => (NotificationKind::CallNotify, None),
            AnySyncMessageLikeEvent::CallInvite(_) => (NotificationKind::CallInvite, None),
            _ => (NotificationKind::Message, None),
        },
        AnySyncTimelineEvent::State(_) => (NotificationKind::StateEvent, None),
        // _ => (NotificationKind::Message, None),
    }
}

pub fn map_notification_item_to_rendered(
    rid: &ruma::OwnedRoomId,
    eid: &ruma::OwnedEventId,
    item: &NotificationItem,
) -> Option<RenderedNotification> {
    let room_name = item.room_computed_display_name.clone();
    let sender_user_id = item.event.sender().to_string();
    let ts_ms: u64 = notification_event_ts_ms(&item.event);
    let is_dm = item.is_direct_message_room;

    let mut sender = item
        .sender_display_name
        .clone()
        .unwrap_or_else(|| item.event.sender().localpart().to_string());

    let mut body = "New event".to_owned();
    let mut kind = NotificationKind::Message;
    let mut expires_at_ms: Option<u64> = None;

    if let NotificationEvent::Timeline(tl) = &item.event {
        let ev = tl.as_ref();

        if should_filter_notification_event(ev) {
            return None;
        }

        let (k, exp) = classify_notification_kind_and_expiry(ev);
        kind = k;
        expires_at_ms = exp;

        match ev {
            AnySyncTimelineEvent::MessageLike(msg) => match msg {
                AnySyncMessageLikeEvent::RoomMessage(m) => {
                    if let Some(orig) = m.as_original() {
                        sender = item
                            .sender_display_name
                            .clone()
                            .unwrap_or_else(|| orig.sender.localpart().to_string());
                        body = orig.content.body().to_owned();
                    }
                }
                AnySyncMessageLikeEvent::CallNotify(notify) => {
                    if let Some(orig) = notify.as_original() {
                        sender = item
                            .sender_display_name
                            .clone()
                            .unwrap_or_else(|| orig.sender.localpart().to_string());
                    }
                    body = "Incoming call".to_owned();
                }
                AnySyncMessageLikeEvent::CallInvite(invite) => {
                    if let Some(orig) = invite.as_original() {
                        sender = item
                            .sender_display_name
                            .clone()
                            .unwrap_or_else(|| orig.sender.localpart().to_string());
                    }
                    body = "Incoming call".to_owned();
                }
                AnySyncMessageLikeEvent::RtcNotification(_) => {
                    body = "Incoming call".to_owned();
                }
                _ => {}
            },
            _ => {}
        }
    }

    if let NotificationEvent::Invite(invite) = &item.event {
        kind = NotificationKind::Invite;
        sender = item
            .sender_display_name
            .clone()
            .unwrap_or_else(|| invite.sender.to_string());
        body = "Room invite".to_owned();
    }

    Some(RenderedNotification {
        room_id: rid.to_string(),
        event_id: eid.to_string(),
        room_name,
        sender,
        sender_user_id,
        body,
        is_noisy: item.is_noisy.unwrap_or(false),
        has_mention: item.has_mention.unwrap_or(false),
        is_dm,
        ts_ms,
        kind,
        expires_at_ms,
    })
}

fn notification_event_ts_ms(ev: &NotificationEvent) -> u64 {
    match ev {
        NotificationEvent::Timeline(timeline_ev) => timeline_ev.origin_server_ts().get().into(),
        NotificationEvent::Invite(_) => now_ms(),
    }
}

pub struct ElementCallCapabilitiesProvider {}
impl CapabilitiesProvider for ElementCallCapabilitiesProvider {
    fn acquire_capabilities(
        &self,
        requested: Capabilities,
    ) -> impl Future<Output = Capabilities> + Send {
        async move { requested }
    }
}
