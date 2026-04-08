use crate::{RoomListEntry, SessionInfo};
use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use tracing::{warn, info};

#[cfg(not(target_family = "wasm"))]
use tracing_subscriber::{EnvFilter, fmt};

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
static TRACING_INIT: Lazy<()> = Lazy::new(|| {
    let filter = EnvFilter::from_default_env()
        .add_directive("mages_ffi=debug".parse().unwrap())
        .add_directive("matrix_sdk=info".parse().unwrap())
        .add_directive("matrix_sdk_crypto=info".parse().unwrap());

    fmt()
        .with_env_filter(filter)
        .with_target(true)
        .without_time()
        .init();
});

pub(crate) fn init_tracing() {
    #[cfg(not(target_family = "wasm"))]
    Lazy::force(&TRACING_INIT);
}

pub(crate) fn reset_store_dir(path: &Path) {
    #[cfg(not(target_family = "wasm"))]
    {
        let _ = std::fs::remove_dir_all(path);
        let _ = std::fs::create_dir_all(path);
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
                            info!("Migrated session file from {:?} to {:?}", old_path, new_path);
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
