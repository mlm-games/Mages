use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::io::Read;
use std::time::Duration;

use futures_util::StreamExt;
use matrix_sdk::{
    Client, Room, SlidingSync, SlidingSyncList, SlidingSyncListLoadingState, SlidingSyncMode,
    deserialized_responses::RawAnySyncOrStrippedState,
    ruma::{
        OwnedMxcUri, OwnedRoomId, UInt,
        api::client::{
            state::{
                get_state_event_for_key::v3 as state_event_for_key,
                get_state_events::v3 as state_events,
            },
            sync::sync_events::v5 as http,
        },
        events::{
            StateEventType,
            image_pack::rooms::{ImagePackRoomsEventContent, RoomImagePackMeta},
            room::{
                ImageInfo, MediaSource,
                image_pack::{ImagePackImage, PackUsage, RoomImagePackEventContent},
            },
            space::parent::SpaceParentEventContent,
        },
    },
    sliding_sync::PollTimeout,
    timeout::timeout,
};
use once_cell::sync::Lazy;
use serde::de::DeserializeOwned;
use tracing::warn;

const MAX_TGS_COMPRESSED_BYTES: usize = 8 * 1024 * 1024;
const MAX_TGS_JSON_BYTES: usize = 4 * 1024 * 1024;
const MAX_TGS_LAYERS: usize = 500;
const MAX_TGS_ASSETS: usize = 200;
const MAX_TGS_DIMENSION: u64 = 1024;
const MAX_TGS_DURATION_SECONDS: f64 = 30.0;

use crate::errors::IntoFfi;
use crate::{FfiError, ImagePackImageEntry, ImagePackSummary};

static ROOM_IMAGE_PACK: Lazy<StateEventType> =
    Lazy::new(|| StateEventType::from("m.room.image_pack"));
static SPACE_PARENT: Lazy<StateEventType> = Lazy::new(|| StateEventType::from("m.space.parent"));

/// Spec v1.19 §42 asks implementations to "impose a reasonable limit on the
/// traversal depth" of the canonical space hierarchy.
const MAX_SPACE_DEPTH: usize = 8;

const PACK_ID_SEP: char = '\u{1f}';

/// Connection id of the on-demand pack scan. Sliding sync permits several
/// connections per session; this one is deliberately distinct from the room
/// list's and the encryption sync's connections.
const PACK_SCAN_CONN_ID: &str = "image-packs";
const PACK_SCAN_LIST: &str = "packs";
/// Pages of rooms per scan request while the list is still loading.
const PACK_SCAN_BATCH: u32 = 100;
const PACK_SCAN_ATTEMPT_TIMEOUT: Duration = Duration::from_secs(60);
const PACK_SCAN_ATTEMPTS: u8 = 3;

/// Scan connections are serialised so two of them cannot race on the stored
/// sync position of [`PACK_SCAN_CONN_ID`].
static PACK_SCAN_LOCK: Lazy<futures_util::lock::Mutex<()>> =
    Lazy::new(|| futures_util::lock::Mutex::new(()));

/// Spec v1.19 "Shortcode grammar": must match `[a-zA-Z0-9-_]+` and must not
/// exceed 100 bytes. The character set deliberately excludes `:`, `/` and
/// space.
fn valid_shortcode(shortcode: &str) -> bool {
    !shortcode.is_empty()
        && shortcode.len() <= 100
        && shortcode
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Collapse an arbitrary label down to something inside the shortcode grammar,
/// so a filename or pack name can seed one.
fn slugify_shortcode(base: &str) -> String {
    let mut out = String::with_capacity(base.len());
    // Any run of non-alphanumerics becomes a single separator, which covers
    // spaces, the grammar's own `-`/`_`, and filename punctuation like `.`.
    let mut last_was_sep = true;
    for ch in base.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            last_was_sep = false;
        } else if !last_was_sep {
            out.push('_');
            last_was_sep = true;
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    if out.is_empty() {
        "sticker".to_owned()
    } else {
        out
    }
}

fn suffixed(base: &str, taken: impl Fn(&str) -> bool) -> String {
    for n in 2..10_000u32 {
        let candidate = format!("{base}_{n}");
        if !taken(&candidate) {
            return candidate;
        }
    }
    format!("{base}_{}", uuid::Uuid::new_v4().simple())
}

/// Assign a collision-free state key to a brand-new pack. The spec only asks
/// for "a unique identifier for the pack itself", so the display name is
/// reused when free and suffixed otherwise.
pub(crate) fn assign_state_key(existing: &BTreeSet<String>, display_name: &str) -> String {
    let base = if display_name.trim().is_empty() {
        "pack"
    } else {
        display_name.trim()
    };
    if !existing.contains(base) {
        return base.to_owned();
    }
    suffixed(base, |candidate| existing.contains(candidate))
}

/// Resolve a shortcode for each entry of a batch being added to a pack.
///
/// `taken` are the shortcodes already in the pack, and each result is reserved
/// as it is produced. Reserving incrementally is the point: a multi-file drop
/// seeds the same base for every file, and inserting them verbatim would
/// collapse all but the last into a single map entry. Bases that do not fit
/// the grammar are slugified; a base that is already valid is kept verbatim so
/// a user's explicit shortcode is never rewritten behind their back.
pub(crate) fn suggest_image_shortcodes(bases: &[String], taken: &[String]) -> Vec<String> {
    let mut used: HashSet<String> = taken.iter().cloned().collect();
    let mut out = Vec::with_capacity(bases.len());
    for base in bases {
        let cleaned = if valid_shortcode(base) {
            base.clone()
        } else {
            let slug = slugify_shortcode(base);
            if valid_shortcode(&slug) {
                slug
            } else {
                continue;
            }
        };
        let mut candidate = cleaned.clone();
        if used.contains(&candidate) {
            candidate = suffixed(&cleaned, |c| used.contains(c));
        }
        used.insert(candidate.clone());
        out.push(candidate);
    }
    out
}

fn parse_usage(usage: &[String]) -> BTreeSet<PackUsage> {
    usage
        .iter()
        .filter_map(|u| match u.as_str() {
            "emoticon" => Some(PackUsage::Emoticon),
            "sticker" => Some(PackUsage::Sticker),
            _ => None,
        })
        .collect()
}

fn parse_info(info_json: Option<&str>) -> Option<ImageInfo> {
    serde_json::from_str::<ImageInfo>(info_json?).ok()
}

/// Whether the signed-in user may write `m.room.image_pack` in this room.
///
/// The MSC says nothing about permissions, so ordinary state-event auth
/// applies: the type has no `events` entry unless the room grants one, which
/// leaves `state_default` (50) as the bar. False on any uncertainty.
pub(crate) async fn can_edit_room_packs(client: &Client, room: &Room) -> bool {
    let Some(user_id) = client.user_id() else {
        return false;
    };
    let Ok(levels) = room.power_levels().await else {
        return false;
    };
    let required: i64 = levels.for_state(ROOM_IMAGE_PACK.clone()).into();
    crate::core::effective_user_level(room, &levels, &user_id) >= required
}

/// One image of a pack, as the editor stages it.
#[derive(Clone, serde::Deserialize)]
pub(crate) struct PackImageDraft {
    pub shortcode: String,
    pub mxc_url: String,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub info_json: Option<String>,
}

/// The complete desired contents of one pack.
///
/// This is a replacement, not a merge: a shortcode absent from `images` is
/// removed from the pack. `state_key` empty means a new pack, and the assigned
/// key is returned by [`save_image_pack`].
#[derive(Clone, serde::Deserialize)]
pub(crate) struct PackWrite {
    #[serde(default)]
    pub state_key: String,
    pub display_name: String,
    #[serde(default)]
    pub usage: Vec<String>,
    #[serde(default)]
    pub images: Vec<PackImageDraft>,
}

impl PackWrite {
    /// Reject the whole write rather than silently dropping an entry, so a
    /// shortcode the spec would not accept is reported instead of swallowed.
    fn into_content(self) -> Result<(String, RoomImagePackEventContent), FfiError> {
        let mut images: BTreeMap<String, ImagePackImage> = BTreeMap::new();
        for draft in self.images {
            if !valid_shortcode(&draft.shortcode) {
                return Err(FfiError::Msg(format!(
                    "invalid shortcode {:?}: must match [a-zA-Z0-9-_]+ and be at most 100 bytes",
                    draft.shortcode
                )));
            }
            // Pack URLs arrive from arbitrary homeservers, so a non-`mxc://`
            // source is rejected here rather than trusted downstream.
            let url = OwnedMxcUri::from(draft.mxc_url.as_str());
            if !url.is_valid() {
                return Err(FfiError::Msg(format!(
                    "image {:?} has an invalid mxc uri",
                    draft.shortcode
                )));
            }
            if images.contains_key(&draft.shortcode) {
                return Err(FfiError::Msg(format!(
                    "duplicate shortcode {:?}",
                    draft.shortcode
                )));
            }
            let mut image = ImagePackImage::new(url);
            image.body = draft.body.filter(|b| !b.is_empty());
            image.info = parse_info(draft.info_json.as_deref());
            images.insert(draft.shortcode, image);
        }

        let mut content = RoomImagePackEventContent::new(images);
        let name = self.display_name.trim();
        if !name.is_empty() {
            content.pack.display_name = Some(name.to_owned());
        }
        content.pack.usage = parse_usage(&self.usage);
        Ok((self.state_key, content))
    }
}

/// Create or replace one of a room's packs, returning the state key it landed
/// under.
///
/// The `images` map is rebuilt from `write` rather than merged onto what the
/// room already holds, which is what makes a removed image actually disappear.
/// An empty `images` is the spec's signal for removing state, so callers that
/// mean to delete a pack go through [`remove_image_pack`].
pub(crate) async fn save_image_pack(room: &Room, write: PackWrite) -> Result<String, FfiError> {
    let (requested_key, content) = write.into_content()?;

    let state_key = if requested_key.is_empty() {
        let taken = existing_state_keys(room).await;
        assign_state_key(&taken, content.pack.display_name.as_deref().unwrap_or(""))
    } else {
        requested_key
    };

    room.send_state_event_for_key(&state_key, content)
        .await
        .ffi()?;
    Ok(state_key)
}

/// Every state key already carrying a pack in this room, so a new pack can be
/// given a free one.
async fn existing_state_keys(room: &Room) -> BTreeSet<String> {
    let request = state_events::Request::new(room.room_id().to_owned());
    let Ok(resp) = room.client().send(request).await else {
        return BTreeSet::new();
    };
    let wanted = ROOM_IMAGE_PACK.clone();
    resp.room_state
        .iter()
        .filter_map(|raw| serde_json::from_str::<serde_json::Value>(raw.json().get()).ok())
        .filter(|event| {
            event
                .get("type")
                .and_then(|t| t.as_str())
                .is_some_and(|t| StateEventType::from(t) == wanted)
        })
        .filter_map(|event| {
            event
                .get("state_key")
                .and_then(|k| k.as_str())
                .map(str::to_owned)
        })
        .collect()
}

/// Empty a pack's `images` map. A state event cannot be deleted, only emptied,
/// and the spec defines an empty pack as its removal.
pub(crate) async fn remove_image_pack(room: &Room, state_key: String) -> Result<(), FfiError> {
    room.send_state_event_for_key(&state_key, RoomImagePackEventContent::new(BTreeMap::new()))
        .await
        .ffi()
        .map(|_| ())
}

/// An uploaded image, ready to be staged into a pack.
#[derive(Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct UploadedPackImage {
    pub mxc_url: String,
    /// Serialised `ImageInfo`, ready to be carried as an `info_json` draft.
    pub info_json: String,
}

pub(crate) fn parse_image_mime(mime: &str) -> Result<mime::Mime, FfiError> {
    let parsed: mime::Mime = mime
        .parse()
        .map_err(|_| FfiError::Msg(format!("unsupported image type {mime:?}")))?;
    if parsed.type_() != "image" && !is_tgs_mime(&parsed) {
        return Err(FfiError::Msg(format!("{mime} is not an image")));
    }
    Ok(parsed)
}

pub(crate) fn is_tgs_mime(parsed: &mime::Mime) -> bool {
    (parsed.type_().as_str() == "application"
        && parsed.subtype().as_str() == "x-tgsticker")
        || (parsed.type_().as_str() == "image" && parsed.subtype().as_str() == "tgs")
}

fn tgs_dimensions(bytes: &[u8]) -> Result<(u32, u32), FfiError> {
    if bytes.len() > MAX_TGS_COMPRESSED_BYTES {
        return Err(FfiError::Msg("tgs file is too large".into()));
    }
    if bytes.len() < 2 || bytes[0] != 0x1f || bytes[1] != 0x8b {
        return Err(FfiError::Msg("tgs file is not gzip encoded".into()));
    }

    let mut json = Vec::new();
    flate2::read::GzDecoder::new(bytes)
        .take(MAX_TGS_JSON_BYTES as u64 + 1)
        .read_to_end(&mut json)
        .map_err(|e| FfiError::Msg(format!("tgs decompression failed: {e}")))?;
    if json.len() > MAX_TGS_JSON_BYTES {
        return Err(FfiError::Msg("tgs animation is too large".into()));
    }

    let value: serde_json::Value = serde_json::from_slice(&json)
        .map_err(|e| FfiError::Msg(format!("tgs animation is invalid: {e}")))?;
    let object = value
        .as_object()
        .ok_or_else(|| FfiError::Msg("tgs animation is invalid".into()))?;

    if object.get("tgs").and_then(serde_json::Value::as_u64) != Some(1) {
        return Err(FfiError::Msg("tgs animation is invalid".into()));
    }
    let width = object
        .get("w")
        .and_then(serde_json::Value::as_u64)
        .filter(|v| (1..=MAX_TGS_DIMENSION).contains(v))
        .ok_or_else(|| FfiError::Msg("tgs animation has invalid dimensions".into()))?;
    let height = object
        .get("h")
        .and_then(serde_json::Value::as_u64)
        .filter(|v| (1..=MAX_TGS_DIMENSION).contains(v))
        .ok_or_else(|| FfiError::Msg("tgs animation has invalid dimensions".into()))?;

    let frame_rate = object
        .get("fr")
        .and_then(serde_json::Value::as_f64)
        .ok_or_else(|| FfiError::Msg("tgs animation has invalid frame rate".into()))?;
    let start = object
        .get("ip")
        .and_then(serde_json::Value::as_f64)
        .unwrap_or(0.0);
    let end = object
        .get("op")
        .and_then(serde_json::Value::as_f64)
        .ok_or_else(|| FfiError::Msg("tgs animation has invalid duration".into()))?;
    if !(1.0..=120.0).contains(&frame_rate)
        || end <= start
        || (end - start) / frame_rate > MAX_TGS_DURATION_SECONDS
    {
        return Err(FfiError::Msg("tgs animation has invalid timing".into()));
    }

    let layers = object
        .get("layers")
        .and_then(serde_json::Value::as_array)
        .filter(|layers| !layers.is_empty() && layers.len() <= MAX_TGS_LAYERS)
        .ok_or_else(|| FfiError::Msg("tgs animation has invalid layers".into()))?;
    if layers.iter().any(|layer| {
        matches!(
            layer.get("ty").and_then(serde_json::Value::as_u64),
            Some(2) | Some(5)
        )
    }) {
        return Err(FfiError::Msg(
            "tgs animation contains unsupported layers".into(),
        ));
    }

    if let Some(assets) = object.get("assets").and_then(serde_json::Value::as_array) {
        if assets.len() > MAX_TGS_ASSETS {
            return Err(FfiError::Msg("tgs animation has too many assets".into()));
        }
        if assets.iter().any(|asset| {
            asset.get("p").is_some()
                || asset.get("u").is_some()
                || asset.get("fFamily").is_some()
        }) {
            return Err(FfiError::Msg(
                "tgs animation references external assets".into(),
            ));
        }
    }

    if object
        .get("chars")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|chars| !chars.is_empty())
        || object
            .get("fonts")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|fonts| !fonts.is_empty())
    {
        return Err(FfiError::Msg("tgs animation contains text".into()));
    }

    Ok((width as u32, height as u32))
}

/// Upload one image for a pack and describe it for the pack's `info`.
///
/// Pack media is never encrypted: the MSC puts E2EE of packs explicitly out
/// of scope, so this is always a plain upload.
///
/// `w`/`h` come from the file header rather than a decode: nothing in the
/// client needs pixel data, since previews are fetched through the homeserver's
/// own thumbnail endpoint.
pub(crate) async fn upload_pack_image(
    client: &Client,
    bytes: Vec<u8>,
    mime: &str,
) -> Result<UploadedPackImage, FfiError> {
    let parsed = parse_image_mime(mime)?;

    let byte_len = bytes.len();
    let is_tgs = is_tgs_mime(&parsed);
    let dimensions = if is_tgs {
        Some(tgs_dimensions(&bytes)?)
    } else {
        imagesize::blob_size(&bytes)
            .ok()
            .map(|d| (d.width as u32, d.height as u32))
    };
    let upload = client
        .media()
        .upload(&parsed, bytes, None)
        .await
        .map_err(FfiError::from)?;

    // A client that cannot tell whether an image animates should leave the flag
    // unset, so only the formats that can animate claim to.
    let is_animated = if is_tgs {
        Some(true)
    } else {
        match parsed.subtype().as_str() {
            "gif" | "webp" | "apng" => Some(true),
            _ => None,
        }
    };

    let mut info = ImageInfo::new();
    info.mimetype = Some(parsed.to_string());
    // `UInt::new` is fallible for values past 2^53-1; a byte count or pixel
    // dimension can never get there, so a dropped dimension just stays unset.
    info.size = UInt::new(byte_len as u64);
    info.width = dimensions.and_then(|(width, _)| UInt::new(width as u64));
    info.height = dimensions.and_then(|(_, height)| UInt::new(height as u64));
    info.is_animated = is_animated;

    Ok(UploadedPackImage {
        mxc_url: upload.content_uri.to_string(),
        info_json: serde_json::to_string(&info).unwrap_or_else(|_| "{}".to_owned()),
    })
}

/// Per-call cache of whole-room state. The protocol has no per-type state
/// endpoint, so every type has to be filtered out of a full dump; caching it
/// keeps a room that is read for both `m.space.parent` and `m.room.image_pack`
/// down to a single request.
struct StateCache<'a> {
    client: &'a Client,
    dumps: HashMap<OwnedRoomId, Vec<serde_json::Value>>,
}

impl<'a> StateCache<'a> {
    fn new(client: &'a Client) -> Self {
        Self {
            client,
            dumps: HashMap::new(),
        }
    }

    async fn dump(&mut self, room: &OwnedRoomId) -> &[serde_json::Value] {
        if !self.dumps.contains_key(room) {
            let fetched = match self
                .client
                .send(state_events::Request::new(room.clone()))
                .await
            {
                Ok(resp) => resp
                    .room_state
                    .iter()
                    .filter_map(|raw| serde_json::from_str(raw.json().get()).ok())
                    .collect(),
                Err(e) => {
                    warn!("image pack: state dump for {room} failed: {e}");
                    Vec::new()
                }
            };
            self.dumps.insert(room.clone(), fetched);
        }
        &self.dumps[room]
    }

    async fn of_type<T: DeserializeOwned>(
        &mut self,
        room: &OwnedRoomId,
        wanted: &StateEventType,
    ) -> Vec<(String, T)> {
        self.dump(room)
            .await
            .iter()
            .filter_map(|event| {
                if StateEventType::from(event.get("type")?.as_str()?) != *wanted {
                    return None;
                }
                let key = event.get("state_key")?.as_str()?.to_string();
                let content: T = serde_json::from_value(event.get("content")?.clone()).ok()?;
                Some((key, content))
            })
            .collect()
    }

    /// An empty `state_key` is a valid pack identifier, but it cannot be
    /// addressed through the per-key endpoint because it would collapse into a
    /// trailing path separator, so those come out of the cached dump instead.
    async fn by_key<T: DeserializeOwned>(
        &mut self,
        room: &OwnedRoomId,
        wanted: &StateEventType,
        state_key: &str,
    ) -> Vec<(String, T)> {
        if state_key.is_empty() {
            return self
                .of_type::<T>(room, wanted)
                .await
                .into_iter()
                .filter(|(k, _)| k.is_empty())
                .collect();
        }

        let req =
            state_event_for_key::Request::new(room.clone(), wanted.clone(), state_key.to_string());
        match self.client.send(req).await {
            // StateEventFormat::Content is the endpoint default, so the body is
            // the event content directly.
            Ok(resp) => match serde_json::from_str::<T>(resp.event_or_content.get()) {
                Ok(content) => vec![(state_key.to_string(), content)],
                Err(e) => {
                    warn!("image pack {room}/{state_key}: bad content: {e}");
                    Vec::new()
                }
            },
            Err(e) => {
                warn!("image pack {room}/{state_key}: state fetch failed: {e}");
                Vec::new()
            }
        }
    }
}

/// Resolve the image packs visible from `room_id`, in the spec's source-priority
/// order: `m.image_pack.rooms` account data, then the current room's own state,
/// then the canonical space hierarchy.
///
/// Packs reached through other rooms are restricted to the state keys the user
/// enabled in account data, per "Clients SHOULD present the images in a room's
/// packs only when the user is interacting in that room, unless that image pack
/// is enabled globally."
///
/// State is read over HTTP rather than from the local store: the client sends
/// no sync filter, so the store never holds these event types. Callers cache.
pub(crate) async fn list_image_packs(
    client: &Client,
    room_id: OwnedRoomId,
) -> Vec<ImagePackSummary> {
    let mut cache = StateCache::new(client);
    let mut targets: Vec<(OwnedRoomId, Option<Vec<String>>, BTreeSet<String>)> = Vec::new();

    // Account data is user-writable, so a malformed blob must not take the
    // whole picker down; the other tiers still resolve.
    let subscribed = match client
        .account()
        .account_data::<ImagePackRoomsEventContent>()
        .await
    {
        Ok(Some(raw)) => raw.deserialize().ok().map(|c| c.rooms),
        Ok(None) => None,
        Err(e) => {
            warn!("image pack: account data unreadable: {e}");
            None
        }
    };

    // Subscribed packs are grouped per room so a room with several of them
    // costs one state fetch rather than one per pack.
    let mut subscribed_by_room: HashMap<OwnedRoomId, Vec<String>> = HashMap::new();
    for (target_room, packs) in subscribed.into_iter().flatten() {
        // The user may have left a referenced room, in which case none of its
        // images are reachable.
        if client.get_room(&target_room).is_none() {
            continue;
        }
        subscribed_by_room
            .entry(target_room)
            .or_default()
            .extend(packs.into_keys());
    }

    // Spec source priority puts account data above room state, so the room
    // being viewed belongs in this tier too rather than being demoted below
    // every other subscribed room.
    let own_global: Vec<String> = subscribed_by_room.remove(&room_id).unwrap_or_default();
    if !own_global.is_empty() {
        targets.push((
            room_id.clone(),
            Some(own_global.clone()),
            own_global.iter().cloned().collect(),
        ));
    }

    // Sorted so the picker does not reshuffle packs between loads.
    let mut subscribed_rooms: Vec<OwnedRoomId> = subscribed_by_room.keys().cloned().collect();
    subscribed_rooms.sort();
    for room in subscribed_rooms {
        let keys: Vec<String> = subscribed_by_room.remove(&room).unwrap_or_default();
        targets.push((room, Some(keys.clone()), keys.into_iter().collect()));
    }

    // Then every pack in the room being viewed; the subscribed ones emitted
    // above are dropped by `seen`.
    targets.push((room_id.clone(), None, own_global.into_iter().collect()));
    // Space packs are not individually subscribable, so none of them is global.
    for space in canonical_space_ancestors(&mut cache, &room_id).await {
        targets.push((space, None, BTreeSet::new()));
    }

    let mut seen: HashSet<(OwnedRoomId, String)> = HashSet::new();
    let mut out: Vec<ImagePackSummary> = Vec::new();

    for (source_room, wanted_keys, global_keys) in targets {
        let found: Vec<(String, RoomImagePackEventContent)> = match &wanted_keys {
            // A single non-empty key is cheaper to address directly than to
            // pull the room's whole state for.
            Some(keys) if keys.len() == 1 && !keys[0].is_empty() => {
                cache.by_key(&source_room, &ROOM_IMAGE_PACK, &keys[0]).await
            }
            Some(keys) => {
                let all = cache.of_type(&source_room, &ROOM_IMAGE_PACK).await;
                all.into_iter()
                    .filter(|(k, _)| keys.iter().any(|w| w == k))
                    .collect()
            }
            None => cache.of_type(&source_room, &ROOM_IMAGE_PACK).await,
        };

        if found.is_empty() {
            continue;
        }
        let source_room_name = room_display_name(client, &source_room).await;

        for (key, content) in found {
            if !seen.insert((source_room.clone(), key.clone())) {
                continue;
            }
            // A pack with no images is how a client deletes state, and the
            // room-name fallback for a missing display_name would otherwise
            // resurface it as an empty pack.
            if content.images.is_empty() {
                continue;
            }
            let is_global = global_keys.contains(&key);
            out.push(summary_from(
                source_room.to_string(),
                source_room_name.clone(),
                key,
                content,
                is_global,
            ));
        }
    }

    out
}

/// The room's display name as the room store knows it, or `None` when the
/// room is unknown or the name cannot be computed.
async fn room_display_name(client: &Client, room_id: &OwnedRoomId) -> Option<String> {
    let room: Room = client.get_room(room_id)?;
    room.display_name().await.ok().map(|name| name.to_string())
}

fn summary_from(
    source_room: String,
    source_room_name: Option<String>,
    state_key: String,
    content: RoomImagePackEventContent,
    is_global: bool,
) -> ImagePackSummary {
    let mut images: Vec<ImagePackImageEntry> = content
        .images
        .into_iter()
        .map(|(shortcode, image)| entry_from(shortcode, image))
        .collect();
    images.sort_by(|a, b| a.shortcode.cmp(&b.shortcode));

    let pack = content.pack;
    ImagePackSummary {
        pack_id: format!("{source_room}{PACK_ID_SEP}{state_key}"),
        source_room,
        source_room_name,
        state_key,
        display_name: pack.display_name,
        avatar_url: pack.avatar_url.map(|m| m.to_string()),
        usage: usage_strings(&pack.usage),
        attribution: pack.attribution,
        is_global,
        images,
    }
}

fn usage_strings(usage: &BTreeSet<PackUsage>) -> Vec<String> {
    usage
        .iter()
        .filter_map(|u| serde_json::to_value(u).ok()?.as_str().map(str::to_string))
        .collect()
}

fn entry_from(shortcode: String, image: ImagePackImage) -> ImagePackImageEntry {
    // `ImageInfo::thumbnail_source` is a `MediaSource`, so it is either a plain
    // mxc or an encrypted file. Pack media is never encrypted, so only the plain
    // form can be fetched as a preview.
    let thumbnail_mxc_uri = image
        .info
        .as_ref()
        .and_then(|info| match &info.thumbnail_source {
            Some(MediaSource::Plain(mxc)) => Some(mxc.to_string()),
            _ => None,
        });

    ImagePackImageEntry {
        shortcode,
        mxc_url: image.url.to_string(),
        body: image.body,
        info_json: image
            .info
            .as_ref()
            .map(|info| serde_json::to_string(info).unwrap_or_default()),
        thumbnail_mxc_uri,
        is_animated: image.info.as_ref().and_then(|info| info.is_animated),
        mime: image.info.as_ref().and_then(|info| info.mimetype.clone()),
    }
}

/// Add or remove one pack from `m.image_pack.rooms`, making its images available
/// in every room.
pub(crate) async fn set_image_pack_enabled(
    client: &Client,
    room_id: OwnedRoomId,
    state_key: String,
    enabled: bool,
) -> Result<(), FfiError> {
    let account = client.account();

    let mut rooms = match account
        .account_data::<ImagePackRoomsEventContent>()
        .await
        .ffi()?
    {
        Some(raw) => match raw.deserialize() {
            Ok(content) => content.rooms,
            Err(e) => {
                // The blob is user-writable, so it can be unreadable. Writing an
                // empty map over it would silently drop every other room's
                // subscriptions, which is the one outcome worse than failing.
                return Err(FfiError::Msg(format!(
                    "existing image pack subscriptions failed to parse, refusing to \
                     overwrite: {e}"
                )));
            }
        },
        None => BTreeMap::new(),
    };

    if enabled {
        rooms
            .entry(room_id)
            .or_default()
            .insert(state_key, RoomImagePackMeta::new());
    } else if let Some(packs) = rooms.get_mut(&room_id) {
        packs.remove(&state_key);
        // An empty inner object has no defined meaning per the spec, so drop the
        // room entirely rather than leave a dangling key.
        if packs.is_empty() {
            rooms.remove(&room_id);
        }
    }

    account
        .set_account_data(ImagePackRoomsEventContent::new(rooms))
        .await
        .ffi()
        .map(|_| ())
}

/// Walk `m.space.parent` edges upwards, keeping only canonical parents the user
/// is joined to. Cycle-safe via `visited`, bounded by `MAX_SPACE_DEPTH`.
async fn canonical_space_ancestors(
    cache: &mut StateCache<'_>,
    room: &OwnedRoomId,
) -> Vec<OwnedRoomId> {
    let mut out = Vec::new();
    let mut visited: HashSet<OwnedRoomId> = HashSet::new();
    visited.insert(room.clone());

    let mut frontier: Vec<(OwnedRoomId, usize)> = vec![(room.clone(), 0)];
    while let Some((current, depth)) = frontier.pop() {
        if depth >= MAX_SPACE_DEPTH {
            continue;
        }
        let parents: Vec<(String, SpaceParentEventContent)> =
            cache.of_type(&current, &SPACE_PARENT).await;
        for (state_key, content) in parents {
            if !content.canonical || content.via.is_empty() {
                continue;
            }
            let Ok(space) = OwnedRoomId::try_from(state_key.as_str()) else {
                continue;
            };
            if !visited.insert(space.clone()) {
                continue;
            }
            if cache.client.get_room(&space).is_none() {
                continue;
            }
            out.push(space.clone());
            frontier.push((space, depth + 1));
        }
    }

    out
}

/// Pull `m.room.image_pack` state for every joined room into the local store.
pub(crate) async fn refresh_image_pack_state(client: &Client) -> Result<(), FfiError> {
    let _guard = PACK_SCAN_LOCK.lock().await;
    let mut last = FfiError::Msg("image pack scan did not finish".into());
    for _ in 0..PACK_SCAN_ATTEMPTS {
        match scan_image_pack_state(client).await {
            ScanOutcome::Loaded => return Ok(()),
            ScanOutcome::Failed(error) => return Err(error),
            ScanOutcome::Restartable(error) => {
                last = error;
                matrix_sdk::sleep::sleep(Duration::from_millis(250)).await;
            }
        }
    }
    Err(last)
}

enum ScanOutcome {
    Loaded,
    /// The connection ended before the list covered every room, e.g. the
    /// server expired the position. A fresh attempt restarts cleanly.
    Restartable(FfiError),
    /// Not worth retrying within this call.
    Failed(FfiError),
}

async fn scan_image_pack_state(client: &Client) -> ScanOutcome {
    let sync = match build_pack_scan(client).await {
        Ok(sync) => sync,
        Err(error) => return ScanOutcome::Failed(error),
    };
    let outcome = run_pack_scan(&sync).await;
    // Keep the stored position so the next scan resumes as a delta; the
    // server side recovers on its own when it has expired.
    let _ = sync.stop_sync();
    outcome
}

async fn build_pack_scan(client: &Client) -> Result<SlidingSync, FfiError> {
    let list = SlidingSyncList::builder(PACK_SCAN_LIST)
        .sync_mode(SlidingSyncMode::new_paging(PACK_SCAN_BATCH))
        .required_state(vec![(ROOM_IMAGE_PACK.clone(), "*".to_owned())])
        .no_timeline_limit()
        // Joined rooms only: a response without `invite_state` is treated as
        // a joined room, which an invited room's must not be.
        .filters(Some(matrix_sdk::ruma::assign!(
            http::request::ListFilters::default(),
            {
                is_invite: Some(false),
            }
        )))
        .requires_timeout(|generator| {
            if generator.is_fully_loaded() {
                PollTimeout::Default
            } else {
                PollTimeout::Some(0)
            }
        });
    SlidingSync::builder(PACK_SCAN_CONN_ID.to_owned(), client.clone())
        .map_err(|error| FfiError::Msg(format!("image pack scan: {error}")))?
        .share_pos()
        .add_list(list)
        .build()
        .await
        .map_err(|error| FfiError::Msg(format!("image pack scan: {error}")))
}

async fn run_pack_scan(sync: &SlidingSync) -> ScanOutcome {
    let mut stream = Box::pin(sync.sync());
    let drive = async {
        loop {
            let loaded = sync
                .on_list(PACK_SCAN_LIST, |list| {
                    futures_util::future::ready(matches!(
                        list.state(),
                        SlidingSyncListLoadingState::FullyLoaded
                    ))
                })
                .await
                .unwrap_or(false);
            if loaded {
                return Ok(true);
            }
            match stream.next().await {
                Some(Ok(_)) => {}
                Some(Err(error)) => {
                    return Err(FfiError::Msg(format!("image pack scan: {error}")));
                }
                None => return Ok(false),
            }
        }
    };
    match timeout(drive, PACK_SCAN_ATTEMPT_TIMEOUT).await {
        Ok(Ok(true)) => ScanOutcome::Loaded,
        Ok(Ok(false)) => ScanOutcome::Restartable(FfiError::Msg(
            "image pack scan connection ended early".into(),
        )),
        Ok(Err(error)) => ScanOutcome::Restartable(error),
        Err(_) => ScanOutcome::Failed(FfiError::Msg("image pack scan timed out".into())),
    }
}

/// Resolve every image pack in the joined rooms from the local store, the
/// read side of [`refresh_image_pack_state`]. Results are grouped by room and
/// sorted so a browse list does not reshuffle between loads.
pub(crate) async fn list_all_image_packs(client: &Client) -> Vec<ImagePackSummary> {
    let mut global: HashMap<OwnedRoomId, BTreeSet<String>> = HashMap::new();
    match client
        .account()
        .account_data::<ImagePackRoomsEventContent>()
        .await
    {
        Ok(Some(raw)) => {
            if let Ok(content) = raw.deserialize() {
                for (room, packs) in content.rooms {
                    global.insert(room, packs.into_keys().collect());
                }
            }
        }
        Ok(None) => {}
        Err(error) => warn!("image pack: account data unreadable: {error}"),
    }

    let mut out: Vec<ImagePackSummary> = Vec::new();
    for room in client.joined_rooms() {
        let events = match room.get_state_events(ROOM_IMAGE_PACK.clone()).await {
            Ok(events) if !events.is_empty() => events,
            Ok(_) => continue,
            Err(error) => {
                warn!(
                    "image pack: store read for {} failed: {error}",
                    room.room_id()
                );
                continue;
            }
        };
        let source_room = room.room_id().to_owned();
        let source_room_name = room_display_name(client, &source_room).await;
        let global_keys = global.get(&source_room);

        for event in events {
            let RawAnySyncOrStrippedState::Sync(raw) = event else {
                continue;
            };
            let Ok(value) = serde_json::from_str::<serde_json::Value>(raw.json().get()) else {
                continue;
            };
            let Some(state_key) = value.get("state_key").and_then(|key| key.as_str()) else {
                continue;
            };
            let content: RoomImagePackEventContent = match value
                .get("content")
                .cloned()
                .and_then(|content| serde_json::from_value(content).ok())
            {
                Some(content) => content,
                None => continue,
            };
            if content.images.is_empty() {
                continue;
            }
            out.push(summary_from(
                source_room.to_string(),
                source_room_name.clone(),
                state_key.to_owned(),
                content,
                global_keys.is_some_and(|keys| keys.contains(state_key)),
            ));
        }
    }

    out.sort_by(|a, b| {
        let left = a
            .source_room_name
            .as_deref()
            .unwrap_or(&a.source_room)
            .to_lowercase();
        let right = b
            .source_room_name
            .as_deref()
            .unwrap_or(&b.source_room)
            .to_lowercase();
        left.cmp(&right)
            .then_with(|| a.source_room.cmp(&b.source_room))
            .then_with(|| a.state_key.cmp(&b.state_key))
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shortcode_grammar_accepts_ascii_alnum_dash_underscore() {
        assert!(valid_shortcode("cat"));
        assert!(valid_shortcode("cat_wave"));
        assert!(valid_shortcode("cat-wave"));
        assert!(valid_shortcode("CatWave2"));
        assert!(valid_shortcode("_leading"));
    }

    #[test]
    fn shortcode_grammar_rejects_empty_overlong_and_reserved_chars() {
        // The grammar excludes `:`, `/` and space on purpose, and an empty
        // shortcode would break lookup.
        assert!(!valid_shortcode(""));
        assert!(!valid_shortcode("cat wave"));
        assert!(!valid_shortcode("cat:wave"));
        assert!(!valid_shortcode("cat/wave"));
        assert!(valid_shortcode(&"a".repeat(100)));
        assert!(!valid_shortcode(&"a".repeat(101)));
    }

    #[test]
    fn slugify_collapses_separators_and_falls_back() {
        assert_eq!(slugify_shortcode("Happy Face"), "happy_face");
        assert_eq!(slugify_shortcode("a - b"), "a_b");
        assert_eq!(slugify_shortcode("Already_Fine"), "already_fine");
        assert_eq!(slugify_shortcode("Happy Face.gif"), "happy_face_gif");
        assert_eq!(slugify_shortcode("trailing__"), "trailing");
        assert_eq!(slugify_shortcode(""), "sticker");
    }

    #[test]
    fn state_key_uses_display_name_then_suffixes_on_collision() {
        let empty = BTreeSet::new();
        assert_eq!(assign_state_key(&empty, "Stickers"), "Stickers");

        let mut taken = BTreeSet::new();
        taken.insert("Stickers".to_owned());
        assert_eq!(assign_state_key(&taken, "Stickers"), "Stickers_2");

        taken.insert("Stickers_2".to_owned());
        assert_eq!(assign_state_key(&taken, "Stickers"), "Stickers_3");

        assert_eq!(assign_state_key(&taken, "   "), "pack");
    }

    #[test]
    fn batch_of_identical_bases_does_not_collapse() {
        // A multi-file drop seeds the same base for every file. Without
        // reserving each result as it is produced, all but the last would
        // overwrite the same map entry and the pack would silently lose images.
        let out = suggest_image_shortcodes(&["cat".into(), "cat".into(), "cat".into()], &[]);
        assert_eq!(out, vec!["cat", "cat_2", "cat_3"]);
    }

    #[test]
    fn batch_avoids_shortcodes_already_in_the_pack() {
        let out = suggest_image_shortcodes(
            &["cat".into(), "cat".into()],
            &["cat".into(), "cat_2".into()],
        );
        assert_eq!(out, vec!["cat_3", "cat_4"]);
    }

    #[test]
    fn explicit_valid_shortcodes_are_never_rewritten() {
        // A shortcode the user typed that already fits the grammar is kept
        // verbatim, so case and separators survive.
        let out = suggest_image_shortcodes(&["Cat-Wave".into()], &[]);
        assert_eq!(out, vec!["Cat-Wave"]);
    }

    #[test]
    fn batch_slugifies_bases_outside_the_grammar() {
        let out =
            suggest_image_shortcodes(&["Happy Face.gif".into(), "happy face.gif".into()], &[]);
        assert_eq!(out, vec!["happy_face_gif", "happy_face_gif_2"]);
    }

    #[test]
    fn usage_drops_unknown_values() {
        let parsed = parse_usage(&["sticker".into(), "bogus".into(), "emoticon".into()]);
        assert!(parsed.contains(&PackUsage::Sticker));
        assert!(parsed.contains(&PackUsage::Emoticon));
        assert_eq!(parsed.len(), 2);
        assert!(parse_usage(&[]).is_empty());
    }

    fn draft(shortcode: &str, mxc: &str) -> PackImageDraft {
        PackImageDraft {
            shortcode: shortcode.into(),
            mxc_url: mxc.into(),
            body: None,
            info_json: None,
        }
    }

    #[test]
    fn write_builds_images_and_pack_metadata() {
        let write = PackWrite {
            state_key: "cats".into(),
            display_name: "  Cats  ".into(),
            usage: vec!["sticker".into()],
            images: vec![draft("cat", "mxc://h/cat"), draft("cat2", "mxc://h/cat2")],
        };
        let (key, content) = write.into_content().expect("valid write");
        assert_eq!(key, "cats");
        assert_eq!(content.images.len(), 2);
        // The name is trimmed, so the state key it would derive stays tidy.
        assert_eq!(content.pack.display_name.as_deref(), Some("Cats"));
        assert!(content.pack.usage.contains(&PackUsage::Sticker));
    }

    #[test]
    fn write_rejects_an_invalid_shortcode_rather_than_dropping_it() {
        let write = PackWrite {
            state_key: String::new(),
            display_name: "Cats".into(),
            usage: vec![],
            images: vec![draft("ok", "mxc://h/a"), draft("not ok", "mxc://h/b")],
        };
        let err = write.into_content().expect_err("must reject");
        assert!(err.to_string().contains("not ok"), "{err}");
    }

    #[test]
    fn write_rejects_a_non_mxc_source() {
        // Pack URLs come from arbitrary homeservers, so a `javascript:` or
        // remote URL must never reach the media layer.
        for hostile in [
            "https://evil.example/x.png",
            "javascript:alert(1)",
            "not-a-uri",
        ] {
            let write = PackWrite {
                state_key: String::new(),
                display_name: "Cats".into(),
                usage: vec![],
                images: vec![draft("cat", hostile)],
            };
            assert!(write.into_content().is_err(), "{hostile} was accepted");
        }
    }

    #[test]
    fn write_rejects_duplicate_shortcodes() {
        let write = PackWrite {
            state_key: String::new(),
            display_name: "Cats".into(),
            usage: vec![],
            images: vec![draft("cat", "mxc://h/a"), draft("cat", "mxc://h/b")],
        };
        assert!(write.into_content().is_err());
    }

    #[test]
    fn write_keeps_info_and_body_and_omits_empty_ones() {
        let write = PackWrite {
            state_key: String::new(),
            display_name: "Cats".into(),
            usage: vec![],
            images: vec![
                PackImageDraft {
                    shortcode: "with_info".into(),
                    mxc_url: "mxc://h/a".into(),
                    body: Some("a cat".into()),
                    info_json: Some(r#"{"w":128,"h":128}"#.into()),
                },
                draft("bare", "mxc://h/b"),
            ],
        };
        let (_, content) = write.into_content().expect("valid write");
        let with_info = &content.images["with_info"];
        assert_eq!(with_info.body.as_deref(), Some("a cat"));
        assert!(with_info.info.is_some());
        // An empty body is omitted rather than serialised as "", which would
        // otherwise read back as an explicit empty description.
        assert!(content.images["bare"].body.is_none());
    }

    #[test]
    fn write_carries_the_requested_state_key_through_untouched() {
        // An empty key is what asks for allocation; anything else is the
        // caller's existing pack and must be written where it already lives.
        for key in ["cats", "some key/with slashes", ""] {
            let write = PackWrite {
                state_key: key.into(),
                display_name: "Cats".into(),
                usage: vec![],
                images: vec![draft("cat", "mxc://h/a")],
            };
            let (got, _) = write.into_content().expect("valid write");
            assert_eq!(got, key);
        }
    }
}
