use crate::{RoomListEntry, SessionInfo};
use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};

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
        let path = session_file(store_dir);
        let txt = tokio::fs::read_to_string(path).await.ok()?;
        serde_json::from_str::<SessionInfo>(&txt).ok()
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
    store_dir.join("session.json")
}

fn room_list_cache_file(store_dir: &Path) -> PathBuf {
    store_dir.join("room_list_cache.json")
}

pub(crate) async fn persist_session(store_dir: &Path, info: &SessionInfo) -> std::io::Result<()> {
    #[cfg(not(target_family = "wasm"))]
    {
        tokio::fs::create_dir_all(store_dir).await?;
        let payload = serde_json::to_string(info).unwrap();
        tokio::fs::write(session_file(store_dir), payload).await
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
        
        

        let homeserver = sdk.homeserver().to_string();

        if let Some(oauth_sess) = sdk.oauth().user_session() {
            let client_id_str = sdk.oauth().client_id().map(|c| c.to_string());
            let info = SessionInfo {
                user_id: oauth_sess.meta.user_id.to_string(),
                device_id: oauth_sess.meta.device_id.to_string(),
                access_token: oauth_sess.tokens.access_token.clone(),
                refresh_token: oauth_sess.tokens.refresh_token.clone(),
                homeserver,
                auth_api: "oauth".to_string(),
                client_id: client_id_str,
            };
            let _ = persist_session(store_dir, &info).await;
        } else if let Some(matrix_sess) = sdk.matrix_auth().session() {
            let info = SessionInfo {
                user_id: matrix_sess.meta.user_id.to_string(),
                device_id: matrix_sess.meta.device_id.to_string(),
                access_token: matrix_sess.tokens.access_token.clone(),
                refresh_token: matrix_sess.tokens.refresh_token.clone(),
                homeserver,
                auth_api: "matrix".to_string(),
                client_id: None,
            };
            let _ = persist_session(store_dir, &info).await;
        }
    }

    #[cfg(target_family = "wasm")]
    {
        let _ = (sdk, store_dir);
    }
}
