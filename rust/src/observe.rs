use crate::core::CoreClient;
use crate::spawn_detached;
use crate::types::*;
use crate::{
    count_visible_room_view, map_live_location_share, map_live_location_vec_diff,
    map_timeline_items_to_events, map_vec_diff, missing_reply_event_id, paginate_backwards_visible,
    safe_call,
};
use futures_util::StreamExt;
use matrix_sdk::Room;
use matrix_sdk::ruma::events::call::invite::OriginalSyncCallInviteEvent;
use matrix_sdk::ruma::events::receipt::SyncReceiptEvent;
use matrix_sdk::ruma::{OwnedEventId, OwnedRoomId};
use matrix_sdk_ui::eyeball_im::VectorDiff;
use matrix_sdk_ui::timeline::Timeline;
use std::sync::Arc;
use tracing::{info, warn};

impl CoreClient {
    pub(crate) async fn drive_typing(&self, room_id: &OwnedRoomId, obs: Arc<dyn TypingObserver>) {
        let Some((_guard, stream)) = self.typing_stream(room_id).await else {
            return;
        };
        futures_util::pin_mut!(stream);
        let mut last: Vec<String> = Vec::new();
        while let Some(names) = stream.next().await {
            if names != last {
                last = names.clone();
                safe_call(|| obs.on_update(names));
            }
        }
    }

    pub(crate) async fn drive_receipts(
        &self,
        room_id: &OwnedRoomId,
        obs: Arc<dyn ReceiptsObserver>,
    ) {
        let Some(mut stream) = self.receipts_changed_stream(room_id).await else {
            return;
        };
        while let Some(()) = stream.next().await {
            safe_call(|| obs.on_changed());
        }
    }

    pub(crate) async fn drive_own_receipt(
        &self,
        room_id: &OwnedRoomId,
        obs: Arc<dyn ReceiptsObserver>,
    ) {
        let stream = self
            .sdk
            .observe_room_events::<SyncReceiptEvent, matrix_sdk::room::Room>(room_id);
        let mut sub = stream.subscribe();
        while let Some((_ev, _room)) = sub.next().await {
            safe_call(|| obs.on_changed());
        }
    }

    pub(crate) async fn drive_room_call_state(
        &self,
        room_id: &OwnedRoomId,
        obs: Arc<dyn RoomCallStateObserver>,
    ) {
        let Some(room) = self.sdk.get_room(room_id) else {
            return;
        };
        let mut rx = room.subscribe_to_updates();
        let mut last = Self::snapshot_room_call_state(&room);
        safe_call(|| obs.on_update(last.clone()));
        loop {
            match rx.recv().await {
                Ok(_) => {}
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    // Fall through to the re-read below: the snapshot heals the gap.
                    warn!(room_id = %room_id, skipped, "room call-state updates lagged; re-reading snapshot");
                }
            }
            let next = Self::snapshot_room_call_state(&room);
            if next != last {
                last = next.clone();
                safe_call(|| obs.on_update(next));
            }
        }
    }

    pub(crate) async fn drive_room_info(
        &self,
        room_id: &OwnedRoomId,
        obs: Arc<dyn RoomInfoObserver>,
    ) {
        let Some(room) = self.sdk.get_room(room_id) else {
            return;
        };
        let mut rx = room.subscribe_to_updates();
        let mut last: Option<RoomInfoSnapshot> = None;
        loop {
            if last.is_none() {
                match self.build_room_info_snapshot(&room).await {
                    Ok(first) => {
                        last = Some(first.clone());
                        safe_call(|| obs.on_update(first));
                    }
                    Err(e) => {
                        warn!(room_id = %room_id, "observe_room_info: snapshot failed: {e}; retrying after the next room update");
                    }
                }
            }
            match rx.recv().await {
                Ok(_) => {}
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    warn!(room_id = %room_id, skipped, "room info updates lagged; re-reading snapshot");
                }
            }
            if last.is_none() {
                continue;
            }
            let Ok(next) = self.build_room_info_snapshot(&room).await else {
                continue;
            };
            if last.as_ref() != Some(&next) {
                last = Some(next.clone());
                safe_call(|| obs.on_update(next));
            }
        }
    }

    pub(crate) async fn drive_call_decline(
        &self,
        room_id: &OwnedRoomId,
        notification_event_id: &OwnedEventId,
        obs: Arc<dyn CallDeclineObserver>,
    ) {
        let Some(room) = self.sdk.get_room(room_id) else {
            return;
        };
        let (_guard, mut rx) = room.subscribe_to_call_decline_events(notification_event_id);
        loop {
            match rx.recv().await {
                Ok(decliner) => safe_call(|| obs.on_decline(decliner.to_string())),
                // Lagged means we may have missed a decline: keep listening
                // (future declines still arrive). The ringing timeout and the
                // room call-state observer remain as backstops. Only Closed
                // ends the stream.
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    warn!(room_id = %room_id, notification_event_id = %notification_event_id, skipped, "call-decline updates lagged; a decline may have been missed");
                    continue;
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    }

    pub(crate) async fn drive_live_location(
        &self,
        room_id: &OwnedRoomId,
        obs: Arc<dyn LiveLocationObserver>,
    ) {
        let Some(room) = self.sdk.get_room(room_id) else {
            return;
        };
        let observable = room.live_locations_observer().await;
        let (initial_shares, stream) = observable.subscribe();
        // Keep observable alive so its event handlers stay registered.
        let _observable = observable;
        let mut all_shares: Vec<LiveLocationShareInfo> =
            initial_shares.iter().map(map_live_location_share).collect();
        safe_call(|| obs.on_update(all_shares.clone()));
        let mut stream = stream;
        while let Some(diffs) = stream.next().await {
            for diff in diffs {
                if let Some(mapped) = map_live_location_vec_diff(diff) {
                    match mapped {
                        VectorDiff::Insert { index, value } => {
                            let idx = index.min(all_shares.len());
                            if idx != index {
                                warn!("live-location Insert OOB: {index}");
                            }
                            all_shares.insert(idx, value);
                        }
                        VectorDiff::Set { index, value } => {
                            if let Some(slot) = all_shares.get_mut(index) {
                                *slot = value;
                            } else {
                                warn!("live-location Set OOB: {index}");
                            }
                        }
                        VectorDiff::Remove { index } => {
                            if index < all_shares.len() {
                                all_shares.remove(index);
                            } else {
                                warn!("live-location Remove OOB: {index}");
                            }
                        }
                        VectorDiff::PushBack { value } => all_shares.push(value),
                        VectorDiff::PopBack => {
                            all_shares.pop();
                        }
                        VectorDiff::PushFront { value } => all_shares.insert(0, value),
                        VectorDiff::PopFront => {
                            if !all_shares.is_empty() {
                                all_shares.remove(0);
                            } else {
                                warn!("live-location PopFront on empty");
                            }
                        }
                        VectorDiff::Clear => all_shares.clear(),
                        VectorDiff::Truncate { length } => all_shares.truncate(length),
                        VectorDiff::Append { values } => all_shares.extend(values),
                        VectorDiff::Reset { values } => {
                            all_shares = values.into_iter().collect();
                        }
                    }
                }
            }
            safe_call(|| obs.on_update(all_shares.clone()));
        }
    }

    pub(crate) async fn drive_call_inbox(&self, obs: Arc<dyn CallObserver>) {
        let handler = self
            .sdk
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
            safe_call(|| obs.on_invite(invite));
        }
    }

    pub(crate) async fn drive_recovery_state(&self, obs: Arc<dyn RecoveryStateObserver>) {
        let mut stream = self.sdk.encryption().recovery().state_stream();
        while let Some(state) = stream.next().await {
            let mapped = match state {
                matrix_sdk::encryption::recovery::RecoveryState::Disabled => RecoveryState::Disabled,
                matrix_sdk::encryption::recovery::RecoveryState::Enabled => RecoveryState::Enabled,
                matrix_sdk::encryption::recovery::RecoveryState::Incomplete => {
                    RecoveryState::Incomplete
                }
                _ => RecoveryState::Unknown,
            };
            safe_call(|| obs.on_update(mapped));
        }
    }

    pub(crate) async fn drive_backup_state(&self, obs: Arc<dyn BackupStateObserver>) {
        let mut stream = self.sdk.encryption().backups().state_stream();
        while let Some(state) = stream.next().await {
            let mapped = match state {
                Ok(matrix_sdk::encryption::backups::BackupState::Unknown) => BackupState::Unknown,
                Ok(matrix_sdk::encryption::backups::BackupState::Creating) => BackupState::Creating,
                Ok(matrix_sdk::encryption::backups::BackupState::Enabling) => BackupState::Enabling,
                Ok(matrix_sdk::encryption::backups::BackupState::Resuming) => BackupState::Resuming,
                Ok(matrix_sdk::encryption::backups::BackupState::Enabled) => BackupState::Enabled,
                Ok(matrix_sdk::encryption::backups::BackupState::Downloading) => {
                    BackupState::Downloading
                }
                Ok(matrix_sdk::encryption::backups::BackupState::Disabling) => {
                    BackupState::Disabling
                }
                Err(e) => {
                    warn!("backup state query failed: {e:?}");
                    BackupState::Unknown
                }
            };
            safe_call(|| obs.on_update(mapped));
        }
    }
}

pub(crate) async fn drive_timeline(
    tl: Arc<Timeline>,
    room_id: &OwnedRoomId,
    me: &str,
    obs: Arc<dyn TimelineObserver>,
) {
    {
        let before = count_visible_room_view(&tl, room_id, me).await;
        if before < 20 {
            let _ =
                paginate_backwards_visible(&tl, room_id, me, 20usize.saturating_sub(before)).await;
        }
    }

    let (items, mut stream) = tl.subscribe().await;

    let mut item_ids: Vec<String> = items
        .iter()
        .map(|item| item.unique_id().0.to_string())
        .collect();

    {
        let mapped = map_timeline_items_to_events(&items, room_id, &tl, me);
        info!(
            "observe_timeline Reset room={} cached_items={} mapped={}",
            room_id,
            items.len(),
            mapped.len()
        );
        safe_call(|| obs.on_diff(TimelineDiffKind::Reset { values: mapped }));
    }

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
            match &diff {
                VectorDiff::Append { values } => {
                    item_ids.extend(values.iter().map(|v| v.unique_id().0.to_string()));
                }
                VectorDiff::PushBack { value } => {
                    item_ids.push(value.unique_id().0.to_string());
                }
                VectorDiff::PushFront { value } => {
                    item_ids.insert(0, value.unique_id().0.to_string());
                }
                VectorDiff::Insert { index, value } => {
                    let idx = (*index).min(item_ids.len());
                    item_ids.insert(idx, value.unique_id().0.to_string());
                }
                VectorDiff::Set { index, value } => {
                    if let Some(id) = item_ids.get_mut(*index) {
                        *id = value.unique_id().0.to_string();
                    }
                }
                VectorDiff::Remove { index } => {
                    if *index < item_ids.len() {
                        let removed = item_ids.remove(*index);
                        safe_call(|| {
                            obs.on_diff(TimelineDiffKind::RemoveByItemId {
                                item_id: removed,
                            })
                        });
                    }
                }
                VectorDiff::PopBack => {
                    if let Some(removed) = item_ids.pop() {
                        safe_call(|| {
                            obs.on_diff(TimelineDiffKind::RemoveByItemId {
                                item_id: removed,
                            })
                        });
                    }
                }
                VectorDiff::PopFront => {
                    if !item_ids.is_empty() {
                        let removed = item_ids.remove(0);
                        safe_call(|| {
                            obs.on_diff(TimelineDiffKind::RemoveByItemId {
                                item_id: removed,
                            })
                        });
                    }
                }
                VectorDiff::Truncate { length } => {
                    let keep = (*length).min(item_ids.len());
                    let removed: Vec<String> = item_ids.drain(keep..).collect();
                    for item_id in removed {
                        safe_call(|| {
                            obs.on_diff(TimelineDiffKind::RemoveByItemId { item_id })
                        });
                    }
                }
                VectorDiff::Clear => {
                    item_ids.clear();
                }
                VectorDiff::Reset { .. } => {}
            }

            match diff {
                // Already emitted as RemoveByItemId above.
                VectorDiff::Remove { .. }
                | VectorDiff::PopBack
                | VectorDiff::PopFront
                | VectorDiff::Truncate { .. } => {}

                VectorDiff::Clear => {
                    // Keep shadow lockstep: empty UI + empty shadow; later stream
                    // ops rebuild. Never rewrite item_ids from tl.items() here.
                    safe_call(|| {
                        obs.on_diff(TimelineDiffKind::Reset { values: Vec::new() })
                    });
                }

                VectorDiff::Reset { values } => {
                    item_ids = values
                        .iter()
                        .map(|it| it.unique_id().0.to_string())
                        .collect();
                    let mapped = map_timeline_items_to_events(&values, room_id, &tl, me);
                    safe_call(|| obs.on_diff(TimelineDiffKind::Reset { values: mapped }));
                }

                other => {
                    if let Some(mapped) = map_vec_diff(other, room_id, &tl, me) {
                        safe_call(|| obs.on_diff(mapped));
                    }
                }
            }
        }
    }
}
