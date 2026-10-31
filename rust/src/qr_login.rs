use std::sync::{Arc, Mutex};

use futures_util::StreamExt;
use matrix_sdk::authentication::oauth::qrcode::{
    CheckCodeSender, ContinuationMessageSender, GeneratedQrProgress, GrantLoginProgress,
    LoginProgress, QrCodeData, QrProgress,
};
use matrix_sdk::authentication::oauth::registration::{
    ApplicationType, ClientMetadata, Localized, OAuthGrantType,
};
use matrix_sdk::reqwest::Url;
use matrix_sdk::ruma::serde::Raw;
use tokio::task::JoinHandle;
use uniffi::Record;

use crate::types::FfiError;
use crate::{Client, RT, check_not_on_runtime, spawn_task};

#[derive(Clone, Record)]
pub struct QrMatrix {
    pub size: u32,
    pub modules: Vec<u8>,
}

#[uniffi::export(callback_interface)]
pub trait QrLoginObserver: Send + Sync {
    fn on_qr_ready(&self, matrix: QrMatrix);
    fn on_scanned(&self);
    fn on_waiting_for_token(&self, user_code: String);
    fn on_syncing_secrets(&self);
    fn on_done(&self);
    fn on_error(&self, message: String);
}

#[uniffi::export(callback_interface)]
pub trait QrGrantObserver: Send + Sync {
    fn on_check_code(&self, code: u8);
    fn on_waiting_for_auth(&self, verification_uri: String);
    fn on_syncing_secrets(&self);
    fn on_done(&self);
    fn on_error(&self, message: String);
}

pub(crate) struct QrState {
    pub(crate) login_task: Mutex<Option<JoinHandle<()>>>,
    pub(crate) login_check_code: tokio::sync::Mutex<Option<CheckCodeSender>>,
    pub(crate) grant_task: Mutex<Option<JoinHandle<()>>>,
    pub(crate) grant_continuation: tokio::sync::Mutex<Option<ContinuationMessageSender>>,
}

impl Default for QrState {
    fn default() -> Self {
        Self {
            login_task: Mutex::new(None),
            login_check_code: tokio::sync::Mutex::new(None),
            grant_task: Mutex::new(None),
            grant_continuation: tokio::sync::Mutex::new(None),
        }
    }
}

fn render_qr(data: &[u8]) -> Result<QrMatrix, String> {
    let code = qrcode::QrCode::new(data).map_err(|e| format!("Could not render QR code: {e}"))?;
    let size = code.width() as u32;
    let modules = code
        .to_colors()
        .into_iter()
        .map(|color| u8::from(color == qrcode::Color::Dark))
        .collect();
    Ok(QrMatrix { size, modules })
}

fn qr_registration() -> Raw<ClientMetadata> {
    let client_uri = Url::parse("https://github.com/mlm-games/mages")
        .expect("static client URI should parse");
    let redirect_uri = Url::parse("com.github.mlm-games.mages:/oauth")
        .expect("static redirect URI should parse");
    let client_uri = Localized::new(client_uri, []);
    let metadata = ClientMetadata {
        client_name: Some(Localized::new("Mages".to_owned(), [])),
        policy_uri: Some(client_uri.clone()),
        tos_uri: Some(client_uri.clone()),
        ..ClientMetadata::new(
            ApplicationType::Native,
            vec![
                OAuthGrantType::AuthorizationCode {
                    redirect_uris: vec![redirect_uri],
                },
                OAuthGrantType::DeviceCode,
            ],
            client_uri,
        )
    };
    Raw::new(&metadata).expect("client metadata should serialize")
}

#[uniffi::export]
impl Client {
    pub fn start_qr_login(
        &self,
        device_name: Option<String>,
        observer: Box<dyn QrLoginObserver>,
    ) -> Result<(), FfiError> {
        check_not_on_runtime("start_qr_login")?;

        let sdk = self.core.sdk.clone();
        let core = self.core.clone();
        let store_dir = self.store_dir.clone();
        let check_code = self.qr.login_check_code.clone();
        let observer: Arc<dyn QrLoginObserver> = Arc::from(observer);
        let registration = qr_registration().into();

        if let Some(previous) = self
            .qr
            .login_task
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            previous.abort();
        }

        let task = spawn_task!(async move {
            let login = sdk
                .oauth()
                .login_with_qr_code(Some(&registration))
                .generate();
            let mut progress = login.subscribe_to_progress();

            let progress_observer = observer.clone();
            let progress_check_code = check_code.clone();
            let progress_task = spawn_task!(async move {
                while let Some(update) = progress.next().await {
                    match update {
                        LoginProgress::Starting => {}
                        LoginProgress::EstablishingSecureChannel(GeneratedQrProgress::QrReady(
                            data,
                        )) => match render_qr(&data.to_bytes()) {
                            Ok(matrix) => progress_observer.on_qr_ready(matrix),
                            Err(message) => progress_observer.on_error(message),
                        },
                        LoginProgress::EstablishingSecureChannel(
                            GeneratedQrProgress::QrScanned(sender),
                        ) => {
                            *progress_check_code.lock().await = Some(sender);
                            progress_observer.on_scanned();
                        }
                        LoginProgress::WaitingForToken { user_code } => {
                            progress_observer.on_waiting_for_token(user_code);
                        }
                        LoginProgress::SyncingSecrets => {
                            progress_observer.on_syncing_secrets();
                        }
                        LoginProgress::Done => {}
                    }
                }
            });

            let outcome = login.await;
            progress_task.abort();
            *check_code.lock().await = None;

            match outcome {
                Ok(()) => {
                    core.finish_authenticated_setup_common().await;
                    if let Some(name) = device_name
                        && let Some(device_id) = sdk.device_id()
                    {
                        use matrix_sdk::ruma::api::client::device::update_device;
                        let mut request = update_device::v3::Request::new(device_id.to_owned());
                        request.display_name = Some(name);
                        let _ = sdk.send(request).await;
                    }
                    crate::platform::build_and_persist_session(&sdk, &store_dir).await;
                    observer.on_done();
                }
                Err(error) => observer.on_error(error.to_string()),
            }
        });

        *self.qr.login_task.lock().unwrap_or_else(|e| e.into_inner()) = Some(task);
        Ok(())
    }

    pub fn submit_qr_login_check_code(&self, code: u8) -> Result<(), FfiError> {
        check_not_on_runtime("submit_qr_login_check_code")?;

        RT.block_on(async {
            let sender = self
                .qr
                .login_check_code
                .lock()
                .await
                .take()
                .ok_or_else(|| {
                    FfiError::Msg("QR login is not waiting for a check code".to_owned())
                })?;
            sender
                .send(code)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn cancel_qr_login(&self) -> bool {
        if check_not_on_runtime("cancel_qr_login").is_err() {
            return false;
        }

        let task = self
            .qr
            .login_task
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let cancelled = task.is_some();
        if let Some(task) = task {
            task.abort();
        }
        RT.block_on(async {
            *self.qr.login_check_code.lock().await = None;
        });
        cancelled
    }

    pub fn start_qr_grant(
        &self,
        qr_data: Vec<u8>,
        observer: Box<dyn QrGrantObserver>,
    ) -> Result<(), FfiError> {
        check_not_on_runtime("start_qr_grant")?;

        let sdk = self.core.sdk.clone();
        let continuation = self.qr.grant_continuation.clone();
        let observer: Arc<dyn QrGrantObserver> = Arc::from(observer);

        if let Some(previous) = self
            .qr
            .grant_task
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            previous.abort();
        }

        let task = spawn_task!(async move {
            let data = match QrCodeData::from_bytes(&qr_data) {
                Ok(data) => data,
                Err(error) => {
                    observer.on_error(error.to_string());
                    return;
                }
            };

            let grant = sdk.oauth().grant_login_with_qr_code().scan(&data);
            let mut progress = grant.subscribe_to_progress();

            let progress_observer = observer.clone();
            let progress_continuation = continuation.clone();
            let progress_task = spawn_task!(async move {
                while let Some(update) = progress.next().await {
                    match update {
                        GrantLoginProgress::Starting => {}
                        GrantLoginProgress::EstablishingSecureChannel(QrProgress {
                            check_code,
                        }) => progress_observer.on_check_code(check_code),
                        GrantLoginProgress::WaitingForAuth {
                            verification_uri,
                            continuation_sender,
                        } => {
                            *progress_continuation.lock().await = Some(continuation_sender);
                            progress_observer.on_waiting_for_auth(verification_uri.to_string());
                        }
                        GrantLoginProgress::SyncingSecrets => {
                            progress_observer.on_syncing_secrets();
                        }
                        GrantLoginProgress::Done => {}
                    }
                }
            });

            let outcome = grant.await;
            progress_task.abort();
            *continuation.lock().await = None;

            match outcome {
                Ok(()) => observer.on_done(),
                Err(error) => observer.on_error(error.to_string()),
            }
        });

        *self.qr.grant_task.lock().unwrap_or_else(|e| e.into_inner()) = Some(task);
        Ok(())
    }

    pub fn confirm_qr_grant(&self) -> Result<(), FfiError> {
        check_not_on_runtime("confirm_qr_grant")?;

        RT.block_on(async {
            let sender = self
                .qr
                .grant_continuation
                .lock()
                .await
                .take()
                .ok_or_else(|| {
                    FfiError::Msg("QR login grant is not waiting for confirmation".to_owned())
                })?;
            sender
                .confirm()
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn cancel_qr_grant(&self) -> bool {
        if check_not_on_runtime("cancel_qr_grant").is_err() {
            return false;
        }

        RT.block_on(async {
            if let Some(sender) = self.qr.grant_continuation.lock().await.take() {
                let _ = sender.cancel().await;
                return true;
            }

            let task = self
                .qr
                .grant_task
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
            let cancelled = task.is_some();
            if let Some(task) = task {
                task.abort();
            }
            cancelled
        })
    }
}
