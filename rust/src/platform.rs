use crate::{RoomListEntry, SessionInfo};
use std::path::{Path, PathBuf};
use tracing::{info, warn};

#[cfg(all(not(target_family = "wasm"), not(target_os = "android")))]
use tracing_subscriber::{EnvFilter, fmt};
#[cfg(all(not(target_family = "wasm"), target_os = "android"))]
use tracing_subscriber::EnvFilter;

pub(crate) fn ensure_dir(path: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        let _ = std::fs::create_dir_all(path);
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = path;
    }
}

#[cfg(not(target_family = "wasm"))]
static TRACING_INIT: std::sync::OnceLock<()> = std::sync::OnceLock::new();

#[cfg(not(target_family = "wasm"))]
static FILTER_HANDLE: std::sync::OnceLock<
    tracing_subscriber::reload::Handle<
        tracing_subscriber::EnvFilter,
        tracing_subscriber::Registry,
    >,
> = std::sync::OnceLock::new();

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, uniffi::Enum)]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

impl LogLevel {
    fn as_str(self) -> &'static str {
        match self {
            LogLevel::Error => "error",
            LogLevel::Warn => "warn",
            LogLevel::Info => "info",
            LogLevel::Debug => "debug",
            LogLevel::Trace => "trace",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, uniffi::Enum)]
pub enum TraceLogPacks {
    Sync,
    Timeline,
    Crypto,
    EventCache,
    SendQueue,
    SlidingSync,
    SsoLogin,
}

impl TraceLogPacks {
    fn targets(self) -> &'static [(&'static str, LogLevel)] {
        match self {
            TraceLogPacks::Sync => &[
                ("matrix_sdk::sliding_sync", LogLevel::Trace),
                ("matrix_sdk::sync", LogLevel::Trace),
            ],
            TraceLogPacks::Timeline => &[("matrix_sdk_ui::timeline", LogLevel::Trace)],
            TraceLogPacks::Crypto => &[
                ("matrix_sdk_crypto", LogLevel::Trace),
                ("matrix_sdk::encryption", LogLevel::Trace),
            ],
            TraceLogPacks::EventCache => &[("matrix_sdk::event_cache", LogLevel::Trace)],
            TraceLogPacks::SendQueue => &[("matrix_sdk::send_queue", LogLevel::Trace)],
            TraceLogPacks::SlidingSync => &[("matrix_sdk::sliding_sync", LogLevel::Trace)],
            TraceLogPacks::SsoLogin => &[("matrix_sdk::authentication::oauth", LogLevel::Trace)],
        }
    }
}

#[derive(uniffi::Record)]
pub struct TracingConfiguration {
    pub log_level: LogLevel,
    pub trace_log_packs: Vec<TraceLogPacks>,
    pub extra_targets: Vec<String>,
}

impl Default for TracingConfiguration {
    fn default() -> Self {
        Self {
            log_level: LogLevel::Info,
            trace_log_packs: Vec::new(),
            extra_targets: vec!["mages_ffi".to_owned()],
        }
    }
}

#[cfg(not(target_family = "wasm"))]
fn build_tracing_filter(config: &TracingConfiguration) -> String {
    let mut filters = vec!["panic=error".to_owned()];
    for (target, pack_level) in config
        .trace_log_packs
        .iter()
        .flat_map(|pack| pack.targets().iter().copied())
    {
        filters.push(format!("{target}={}", pack_level.as_str()));
    }
    filters.push(format!("mages_ffi={}", config.log_level.as_str()));
    filters.push(format!("matrix_sdk={}", config.log_level.as_str()));
    filters.push(format!("matrix_sdk_crypto={}", config.log_level.as_str()));
    for target in &config.extra_targets {
        filters.push(format!("{target}={}", config.log_level.as_str()));
    }
    filters.join(",")
}

#[cfg(not(target_family = "wasm"))]
fn init_logging_once(config: &TracingConfiguration) {
    let filter_string = build_tracing_filter(config);

    let first_install = TRACING_INIT.get().is_none();
    let filter_string = if first_install {
        std::env::var("RUST_LOG").unwrap_or(filter_string)
    } else {
        filter_string
    };

    TRACING_INIT.get_or_init(|| {
        log_panics::init();
        let _ = tracing_log::LogTracer::init();

        let filter = EnvFilter::try_new(&filter_string)
            .unwrap_or_else(|_| "mages_ffi=info".parse().unwrap());
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::layer::SubscriberExt as _;
            use tracing_subscriber::util::SubscriberInitExt as _;
            use tracing_subscriber::reload;
            let (filter_layer, handle) = reload::Layer::new(filter);
            let _ = FILTER_HANDLE.set(handle);
            let android_layer = paranoid_android::layer(env!("CARGO_PKG_NAME"));
            tracing_subscriber::registry().with(filter_layer).with(android_layer).init();
        }

        #[cfg(not(target_os = "android"))]
        {
            use tracing_subscriber::layer::SubscriberExt as _;
            use tracing_subscriber::reload;
            use tracing_subscriber::util::SubscriberInitExt as _;
            let (filter_layer, handle) = reload::Layer::new(filter);
            let _ = FILTER_HANDLE.set(handle);
            tracing_subscriber::registry()
                .with(filter_layer)
                .with(fmt::layer().with_target(true).without_time())
                .init();
        }
    });

    if let Some(handle) = FILTER_HANDLE.get() {
        let filter = EnvFilter::try_new(&filter_string)
            .unwrap_or_else(|_| "mages_ffi=info".parse().unwrap());
        let _ = handle.reload(filter);
    }
    info!(filter = filter_string.as_str(), "logging initialised");
}

pub(crate) fn init_tracing() {
    init_logging(TracingConfiguration::default());
}

#[cfg(not(target_family = "wasm"))]
#[uniffi::export]
pub fn init_logging(config: TracingConfiguration) {
    init_logging_once(&config);
}

pub(crate) fn reset_store_dir(path: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        info!("resetting store dir {:?}", path);
        let _ = std::fs::remove_dir_all(path);
        let _ = std::fs::create_dir_all(path);
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = path;
    }
}

pub(crate) fn trash_store_dir(path: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        let trash_name = format!(
            "{}.trash.{}",
            path.file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default(),
            uuid::Uuid::new_v4()
        );
        if let Some(parent) = path.parent() {
            let trash = parent.join(&trash_name);
            match std::fs::rename(path, &trash) {
                Ok(()) => {
                    info!(
                        "Moved store dir {:?} -> {:?} for deferred cleanup",
                        path, trash
                    );
                }
                Err(e) => {
                    warn!("Failed to rename store dir {:?} to {:?}: {e}", path, trash);
                }
            }
        }
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = path;
    }
}

pub(crate) async fn load_session(store_dir: &Path) -> Option<SessionInfo> {
    #[cfg(not(target_family = "wasm"))]
    {
        let new_path = session_file(store_dir);

        if let Ok(txt) = tokio::fs::read_to_string(&new_path).await {
            match serde_json::from_str::<SessionInfo>(&txt) {
                Ok(info) => return Some(info),
                Err(e) => {
                    warn!("Failed to parse session file at {:?}: {e}", new_path);
                }
            }
        }

        let old_path = store_dir.join("session.json");
        if let Ok(txt) = tokio::fs::read_to_string(&old_path).await {
            match serde_json::from_str::<SessionInfo>(&txt) {
                Ok(info) => {
                    if let Some(parent) = new_path.parent() {
                        let _ = tokio::fs::create_dir_all(parent).await;
                    }

                    match tokio::fs::rename(&old_path, &new_path).await {
                        Ok(()) => {
                            info!(
                                "Migrated session file from {:?} to {:?}",
                                old_path, new_path
                            );
                        }
                        Err(rename_err) => {
                            warn!(
                                "rename({:?} -> {:?}) failed: {rename_err}; trying copy+delete",
                                old_path, new_path
                            );

                            match tokio::fs::write(&new_path, &txt).await {
                                Ok(()) => {
                                    let _ = tokio::fs::remove_file(&old_path).await;
                                    info!(
                                        "Migrated session file from {:?} to {:?} using copy+delete",
                                        old_path, new_path
                                    );
                                }
                                Err(write_err) => {
                                    warn!(
                                        "Failed to migrate session file to {:?}: {write_err}; leaving old file in place",
                                        new_path
                                    );
                                }
                            }
                        }
                    }

                    return Some(info);
                }
                Err(e) => {
                    warn!("Failed to parse legacy session file at {:?}: {e}", old_path);
                }
            }
        }

        None
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
        None
    }
}

pub(crate) fn remove_session_file(store_dir: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        let _ = std::fs::remove_file(session_file(store_dir));
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
    }
}

pub(crate) async fn load_room_list_cache(store_dir: &Path) -> Vec<RoomListEntry> {
    #[cfg(not(target_family = "wasm"))]
    {
        let path = room_list_cache_file(store_dir);
        match tokio::fs::read_to_string(path).await {
            Ok(txt) => serde_json::from_str::<Vec<RoomListEntry>>(&txt).unwrap_or_default(),
            Err(_) => Vec::new(),
        }
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
        Vec::new()
    }
}

pub(crate) async fn write_room_list_cache(
    store_dir: &Path,
    entries: &[RoomListEntry],
) -> std::io::Result<()> {
    #[cfg(not(target_family = "wasm"))]
    {
        let payload = serde_json::to_string(entries).unwrap_or_default();
        tokio::fs::write(room_list_cache_file(store_dir), payload).await
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = (store_dir, entries);
        Ok(())
    }
}

pub(crate) struct SearchIndexConfig {
    pub(crate) dir: PathBuf,
    pub(crate) key: String,
}

pub(crate) fn search_index_config(store_dir: &Path) -> Option<SearchIndexConfig> {
    #[cfg(not(target_family = "wasm"))]
    {
        let dir = store_dir.join("search_index");
        let _ = std::fs::create_dir_all(&dir);

        let key_file = store_dir.join("search_index_key.txt");
        let key = match std::fs::read_to_string(&key_file) {
            Ok(existing) => {
                let trimmed = existing.trim().to_string();
                if trimmed.is_empty() {
                    let generated = format!("{}{}", uuid::Uuid::new_v4(), uuid::Uuid::new_v4());
                    let _ = std::fs::write(&key_file, &generated);
                    generated
                } else {
                    trimmed
                }
            }
            Err(_) => {
                let generated = format!("{}{}", uuid::Uuid::new_v4(), uuid::Uuid::new_v4());
                let _ = std::fs::write(&key_file, &generated);
                generated
            }
        };

        Some(SearchIndexConfig { dir, key })
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
        None
    }
}

fn session_file(store_dir: &Path) -> PathBuf {
    let mut name = store_dir
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_else(|| "session".into());

    name.push(".session.json");

    store_dir
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join(name)
}

/// Restores (session file already should be available /
/// present, holding the resolved base URL) return `true` ->
/// `homeserver_url` with zero network at build (matrix-rust-sdk#3699).
pub(crate) fn has_session_file(store_dir: &Path) -> bool {
    #[cfg(not(target_family = "wasm"))]
    {
        if std::fs::metadata(session_file(store_dir)).is_ok() {
            return true;
        }
        std::fs::metadata(store_dir.join("session.json")).is_ok()
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
        false
    }
}

/// Best-effort cleanup of stale `probe_*` store dirs left behind by server
/// checks (each probe builds a real SQLite store). Only runs inside an
/// `accounts/` dir, keeps the caller's own dir, and only removes
/// directories — real accounts and `<id>.session.json` siblings are never
/// touched.
pub(crate) fn sweep_probe_dirs(store_dir: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        let Some(accounts) = store_dir
            .parent()
            .filter(|p| p.file_name().and_then(|n| n.to_str()) == Some("accounts"))
        else {
            return;
        };
        let Ok(entries) = std::fs::read_dir(accounts) else {
            return;
        };
        let keep = store_dir.file_name();
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            if !name.starts_with("probe_") || path.file_name() == keep {
                continue;
            }
            let idle = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.elapsed().ok())
                .is_some_and(|d| d.as_secs() > 600);
            if !idle {
                continue;
            }
            if path.is_dir() {
                let _ = std::fs::remove_dir_all(&path);
            }
        }
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = store_dir;
    }
}

fn room_list_cache_file(store_dir: &Path) -> PathBuf {
    store_dir.join("room_list_cache.json")
}

pub(crate) async fn persist_session(store_dir: &Path, info: &SessionInfo) -> std::io::Result<()> {
    #[cfg(not(target_family = "wasm"))]
    {
        let path = session_file(store_dir);

        if let Some(parent) = path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }

        let payload = serde_json::to_string(info).unwrap();
        let tmp_path = path.with_extension("tmp");
        tokio::fs::write(&tmp_path, payload).await?;
        tokio::fs::rename(&tmp_path, &path).await
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = (store_dir, info);
        Ok(())
    }
}

pub(crate) async fn build_and_persist_session(sdk: &matrix_sdk::Client, store_dir: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        use tracing::warn;
        let homeserver = sdk.homeserver().to_string();

        if let Some(full) = sdk.oauth().full_session() {
            let info = SessionInfo {
                user_id: full.user.meta.user_id.to_string(),
                device_id: full.user.meta.device_id.to_string(),
                access_token: full.user.tokens.access_token.clone(),
                refresh_token: full.user.tokens.refresh_token.clone(),
                homeserver,
                auth_api: "oauth".to_string(),
                client_id: Some(full.client_id.to_string()),
                is_token_valid: true,
            };
            let _ = persist_session(store_dir, &info).await;
            return;
        }

        if let Some(matrix_sess) = sdk.matrix_auth().session() {
            let info = SessionInfo {
                user_id: matrix_sess.meta.user_id.to_string(),
                device_id: matrix_sess.meta.device_id.to_string(),
                access_token: matrix_sess.tokens.access_token.clone(),
                refresh_token: matrix_sess.tokens.refresh_token.clone(),
                homeserver,
                auth_api: "matrix".to_string(),
                client_id: None,
                is_token_valid: true,
            };
            let _ = persist_session(store_dir, &info).await;
            return;
        }

        warn!("No restorable session available; not updating session file");
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = (sdk, store_dir);
    }
}
