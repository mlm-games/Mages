use futures_util::StreamExt;
use matrix_sdk::encryption::verification::{
    SasState as SdkSasState, Verification, VerificationRequest,
    VerificationRequestState,
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "phase")]
pub enum VerifEvent {
    Requested {
        flow_id: String,
    },
    Ready,
    SasStarted,
    KeysExchanged {
        emojis: Vec<EmojiEntry>,
        other_user: String,
        other_device: String,
    },
    Confirmed,
    Done,
    Cancelled {
        reason: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmojiEntry {
    pub symbol: String,
    pub description: String,
}

/// Outgoing: we created the request, we start SAS after Ready.
pub async fn drive_verification_request(
    request: VerificationRequest,
    we_start_sas: bool,
) -> impl futures_util::Stream<Item = VerifEvent> {
    let flow_id = request.flow_id().to_owned();
    async_stream::stream! {
        yield VerifEvent::Requested { flow_id };

        let mut req_changes = request.changes();
        let sas = loop {
            let Some(state) = req_changes.next().await else {
                yield VerifEvent::Error { message: "Request stream ended".into() };
                return;
            };
            match state {
                VerificationRequestState::Ready { .. } => {
                    yield VerifEvent::Ready;
                    if we_start_sas {
                        match request.start_sas().await {
                            Ok(Some(sas)) => break sas,
                            Ok(None) => {
                                yield VerifEvent::Error { message: "start_sas returned None".into() };
                                return;
                            }
                            Err(e) => {
                                yield VerifEvent::Error { message: format!("start_sas failed: {e}") };
                                return;
                            }
                        }
                    }
                }
                VerificationRequestState::Transitioned { verification } => {
                    if let Verification::SasV1(sas) = verification { break sas; }
                    yield VerifEvent::Error { message: "Non-SAS method".into() };
                    return;
                }
                VerificationRequestState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                VerificationRequestState::Done => { yield VerifEvent::Done; return; }
                _ => {}
            }
        };

        yield VerifEvent::SasStarted;

        // Outgoing side initiated SAS, no need to accept it ourselves.
        let mut sas_changes = sas.changes();
        while let Some(state) = sas_changes.next().await {
            match state {
                SdkSasState::KeysExchanged { emojis, .. } => {
                    yield VerifEvent::KeysExchanged {
                        emojis: emojis.map(|e| e.emojis.iter().map(|em| EmojiEntry {
                            symbol: em.symbol.to_string(),
                            description: em.description.to_string(),
                        }).collect()).unwrap_or_default(),
                        other_user: sas.other_user_id().to_string(),
                        other_device: sas.other_device().device_id().to_string(),
                    };
                }
                SdkSasState::Confirmed => { yield VerifEvent::Confirmed; }
                SdkSasState::Done { .. } => { yield VerifEvent::Done; return; }
                SdkSasState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                _ => {}
            }
        }
    }
}

pub async fn drive_incoming_verification(
    request: VerificationRequest,
) -> impl futures_util::Stream<Item = VerifEvent> {
    async_stream::stream! {
        // Subscribe BEFORE accept so we never miss the Transitioned event
        let mut req_changes = request.changes();

        if let Err(e) = request.accept().await {
            yield VerifEvent::Error { message: format!("Accept failed: {e}") };
            return;
        }
        yield VerifEvent::Ready;

        // Wait for other side to start SAS → Transitioned
        let sas = loop {
            let Some(state) = req_changes.next().await else {
                yield VerifEvent::Error { message: "Stream ended waiting for SAS".into() };
                return;
            };
            match state {
                VerificationRequestState::Ready { .. } => { /* already emitted */ }
                VerificationRequestState::Transitioned { verification } => {
                    if let Verification::SasV1(sas) = verification { break sas; }
                    yield VerifEvent::Error { message: "Non-SAS method".into() };
                    return;
                }
                VerificationRequestState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                VerificationRequestState::Done => { yield VerifEvent::Done; return; }
                _ => {}
            }
        };

        yield VerifEvent::SasStarted;
        let mut sas_changes = sas.changes();

        if let Err(e) = sas.accept().await {
            yield VerifEvent::Error { message: format!("SAS accept failed: {e}") };
            return;
        }

        while let Some(state) = sas_changes.next().await {
            match state {
                SdkSasState::KeysExchanged { emojis, .. } => {
                    yield VerifEvent::KeysExchanged {
                        emojis: emojis.map(|e| e.emojis.iter().map(|em| EmojiEntry {
                            symbol: em.symbol.to_string(),
                            description: em.description.to_string(),
                        }).collect()).unwrap_or_default(),
                        other_user: sas.other_user_id().to_string(),
                        other_device: sas.other_device().device_id().to_string(),
                    };
                }
                SdkSasState::Confirmed => { yield VerifEvent::Confirmed; }
                SdkSasState::Done { .. } => { yield VerifEvent::Done; return; }
                SdkSasState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                _ => {}
            }
        }
    }
}
