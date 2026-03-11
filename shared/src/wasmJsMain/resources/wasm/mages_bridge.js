import * as magesFfi from "./mages_ffi.js";

let initPromise = null;

export function ensureMagesFfi() {
  if (initPromise == null) {
    initPromise = Promise.resolve(magesFfi);
  }
  return initPromise;
}

export function normalizeWasmValue(value) {
  if (typeof value === "bigint") {
    return Number(value);
  }
  if (Array.isArray(value)) {
    return value.map(normalizeWasmValue);
  }
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, entry] of Object.entries(value)) {
      out[key] = normalizeWasmValue(entry);
    }
    return out;
  }
  return value;
}

function normalizeBridgeValue(value) {
  if (typeof value === "string") {
    try {
      return normalizeWasmValue(JSON.parse(value));
    } catch {
      return value;
    }
  }
  return normalizeWasmValue(value);
}

function parseJsonArray(value) {
  return Array.isArray(value) ? value : JSON.parse(value);
}

const SESSION_KEY = "mages_web_sessions_v1";

function loadAllSessions() {
  try {
    const raw = globalThis.localStorage?.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function saveAllSessions(sessions) {
  globalThis.localStorage?.setItem(SESSION_KEY, JSON.stringify(sessions));
}

class SessionStore {
  load(accountId) {
    const all = loadAllSessions();
    return all[accountId] ?? null;
  }

  save(accountId, session) {
    const all = loadAllSessions();
    all[accountId] = session;
    saveAllSessions(all);
  }

  clear(accountId) {
    const all = loadAllSessions();
    delete all[accountId];
    saveAllSessions(all);
  }
}

function accountKey(accountId, homeserverUrl) {
  return accountId ?? `hs:${homeserverUrl}`;
}

function err(error) {
  return { ok: false, error };
}

class WasmClientBridge {
  constructor(client) {
    this.client = client;
  }

  static async create(homeserverUrl, baseStoreDir, accountId) {
    const client = await magesFfi.WasmClient.createAsync(
      homeserverUrl,
      baseStoreDir,
      accountId ?? undefined,
    );
    return new WasmClientBridge(client);
  }

  free() {
    this.client.free();
  }

  logout() {
    return this.client.logout?.() ?? false;
  }

  whoami() {
    return this.client.whoami() ?? null;
  }

  is_logged_in() {
    return this.client.is_logged_in();
  }

  async login(username, password, deviceDisplayName) {
    const result = await this.client.loginAsync(username, password, deviceDisplayName ?? undefined);
    return result ?? null;
  }

  login_oauth_loopback_available() {
    return this.client.login_oauth_loopback_available();
  }

  login_sso_loopback_available() {
    return this.client.login_sso_loopback_available();
  }

  homeserver_login_details() {
    return normalizeWasmValue(this.client.homeserver_login_details());
  }

  async rooms() {
    return normalizeWasmValue(await this.client.rooms());
  }

  async recent_events(roomId, limit) {
    return normalizeWasmValue(await this.client.recent_events(roomId, limit));
  }

  paginate_backwards(roomId, count) {
    return this.client.paginate_backwards(roomId, count);
  }

  paginate_forwards(roomId, count) {
    return this.client.paginate_forwards(roomId, count);
  }

  mark_read(roomId) {
    return this.client.mark_read(roomId);
  }

  mark_read_at(roomId, eventId) {
    return this.client.mark_read_at(roomId, eventId);
  }

  react(roomId, eventId, emoji) {
    return this.client.react(roomId, eventId, emoji);
  }

  reply(roomId, inReplyTo, body) {
    return this.client.reply(roomId, inReplyTo, body);
  }

  edit(roomId, targetEventId, newBody) {
    return this.client.edit(roomId, targetEventId, newBody);
  }

  redact(roomId, eventId, reason) {
    return this.client.redact(roomId, eventId, reason ?? undefined);
  }

  set_typing(roomId, typing) {
    return this.client.set_typing(roomId, typing);
  }

  observe_typing(roomId, onUpdate) {
    return this.client.observe_typing(roomId, (users) => {
      onUpdate(normalizeWasmValue(users ?? []));
    });
  }

  unobserve_typing(id) {
    return this.client.unobserve_typing(id);
  }

  reactions_for_event(roomId, eventId) {
    return normalizeWasmValue(this.client.reactions_for_event(roomId, eventId));
  }

  reactions_batch(roomId, eventIdsJson) {
    return normalizeWasmValue(this.client.reactions_batch(roomId, eventIdsJson));
  }

  async public_rooms(server, search, limit, since) {
    return normalizeWasmValue(
      await this.client.public_rooms(
        server ?? undefined,
        search ?? undefined,
        limit,
        since ?? undefined,
      ),
    );
  }

  join_by_id_or_alias(idOrAlias) {
    return this.client.join_by_id_or_alias(idOrAlias);
  }

  resolve_room_id(idOrAlias) {
    return this.client.resolve_room_id(idOrAlias) ?? null;
  }

  async list_members(roomId) {
    return normalizeWasmValue(await this.client.list_members(roomId));
  }

  async list_invited() {
    return normalizeWasmValue(await this.client.list_invited() ?? []);
  }

  accept_invite(roomId) {
    return this.client.accept_invite(roomId);
  }

  leave_room(roomId) {
    return this.client.leave_room(roomId);
  }

  create_room(name, topic, invitees, isPublic, roomAlias) {
    return this.client.create_room(
      name ?? undefined,
      topic ?? undefined,
      parseJsonArray(invitees),
      isPublic,
      roomAlias ?? undefined,
    ) ?? null;
  }

  async room_profile(roomId) {
    return normalizeWasmValue(await this.client.room_profile(roomId));
  }

  set_room_name(roomId, name) {
    return this.client.set_room_name(roomId, name);
  }

  set_room_topic(roomId, topic) {
    return this.client.set_room_topic(roomId, topic);
  }

  async room_notification_mode(roomId) {
    return await this.client.room_notification_mode(roomId) ?? null;
  }

  async set_room_notification_mode(roomId, mode) {
    return await this.client.set_room_notification_mode(roomId, mode);
  }

  room_power_levels(roomId) {
    return normalizeWasmValue(this.client.room_power_levels(roomId));
  }

  async get_user_power_level(roomId, userId) {
    return await this.client.get_user_power_level(roomId, userId);
  }
  async list_my_devices() {
    return normalizeWasmValue(await this.client.list_my_devices());
  }

  start_verification_inbox(onRequest, onError) {
    return this.client.start_verification_inbox(
      (payload) => {
        try {
          const p = JSON.parse(payload ?? "{}");
          onRequest(p);
        } catch (e) {
          onError(`verification inbox payload parse failed: ${e}`);
        }
      },
      (message) => onError(message ?? "")
    );
  }

  unobserve_verification_inbox(id) {
    return this.client.unobserve_verification_inbox(id);
  }

  async start_self_sas(targetDeviceId, onPhase, onEmojis, onError) {
    return await this.client.start_self_sas(
      targetDeviceId,
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onPhase(p);
      },
      (payload) => {
        const p = normalizeWasmValue(payload ?? null) ?? {};
        onEmojis(p);
      },
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onError(p);
      }
    );
  }

  async start_user_sas(userId, onPhase, onEmojis, onError) {
    return await this.client.start_user_sas(
      userId,
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onPhase(p);
      },
      (payload) => {
        const p = normalizeWasmValue(payload ?? null) ?? {};
        onEmojis(p);
      },
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onError(p);
      }
    );
  }

  async accept_verification_request(flowId, otherUserId, onPhase, onEmojis, onError) {
    return await this.client.accept_verification_request(
      flowId,
      otherUserId ?? undefined,
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onPhase(p);
      },
      (payload) => {
        const p = normalizeWasmValue(payload ?? null) ?? {};
        onEmojis(p);
      },
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onError(p);
      }
    );
  }

  async accept_sas(flowId, otherUserId, onPhase, onEmojis, onError) {
    return await this.client.accept_sas(
      flowId,
      otherUserId ?? undefined,
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onPhase(p);
      },
      (payload) => {
        const p = normalizeWasmValue(payload ?? null) ?? {};
        onEmojis(p);
      },
      (payload) => {
        const p = JSON.parse(payload ?? "{}");
        onError(p);
      }
    );
  }

  async confirm_verification(flowId) {
    return await this.client.confirm_verification(flowId);
  }

  async cancel_verification(flowId) {
    return await this.client.cancel_verification(flowId);
  }

  async cancel_verification_request(flowId, otherUserId) {
    return await this.client.cancel_verification_request(flowId, otherUserId ?? undefined);
  }

  observe_recovery_state(onUpdate) {
    return this.client.observe_recovery_state((state) => onUpdate(normalizeWasmValue(state)));
  }

  unobserve_recovery_state(id) {
    return this.client.unobserve_recovery_state(id);
  }

  observe_backup_state(onUpdate) {
    return this.client.observe_backup_state((state) => onUpdate(normalizeWasmValue(state)));
  }

  unobserve_backup_state(id) {
    return this.client.unobserve_backup_state(id);
  }

  async backup_exists_on_server(fetch) {
    return await this.client.backup_exists_on_server(fetch);
  }

  async set_key_backup_enabled(enabled) {
    return await this.client.set_key_backup_enabled(enabled);
  }

  async recover_with_key(recoveryKey) {
    return await this.client.recover_with_key(recoveryKey);
  }

  setup_recovery(onProgress, onDone, onError) {
    return this.client.setup_recovery(
      (step) => onProgress(step ?? ""),
      (key) => onDone(key ?? ""),
      (message) => onError(message ?? "")
    );
  }

  can_user_ban(roomId, userId) {
    return this.client.can_user_ban(roomId, userId);
  }

  can_user_invite(roomId, userId) {
    return this.client.can_user_invite(roomId, userId);
  }

  can_user_redact_other(roomId, userId) {
    return this.client.can_user_redact_other(roomId, userId);
  }

  update_power_level_for_user(roomId, userId, powerLevel) {
    return this.client.update_power_level_for_user(roomId, userId, powerLevel);
  }

  apply_power_level_changes(roomId, changesJson) {
    return this.client.apply_power_level_changes(roomId, changesJson);
  }

  ban_user(roomId, userId, reason) {
    return this.client.ban_user(roomId, userId, reason ?? undefined);
  }

  unban_user(roomId, userId, reason) {
    return this.client.unban_user(roomId, userId, reason ?? undefined);
  }

  kick_user(roomId, userId, reason) {
    return this.client.kick_user(roomId, userId, reason ?? undefined);
  }

  invite_user(roomId, userId) {
    return this.client.invite_user(roomId, userId);
  }

  enable_room_encryption(roomId) {
    return this.client.enable_room_encryption(roomId);
  }

  report_content(roomId, eventId, score, reason) {
    return this.client.report_content(roomId, eventId, score ?? undefined, reason ?? undefined);
  }

  report_room(roomId, reason) {
    return this.client.report_room(roomId, reason ?? undefined);
  }

  room_join_rule(roomId) {
    return this.client.room_join_rule(roomId) ?? null;
  }

  set_room_join_rule(roomId, rule) {
    return this.client.set_room_join_rule(roomId, rule);
  }

  room_history_visibility(roomId) {
    return this.client.room_history_visibility(roomId) ?? null;
  }

  set_room_history_visibility(roomId, visibility) {
    return this.client.set_room_history_visibility(roomId, visibility);
  }

  room_directory_visibility(roomId) {
    return this.client.room_directory_visibility(roomId) ?? null;
  }

  set_room_directory_visibility(roomId, visibility) {
    return this.client.set_room_directory_visibility(roomId, visibility);
  }

  room_aliases(roomId) {
    return normalizeWasmValue(this.client.room_aliases(roomId) ?? []);
  }

  publish_room_alias(roomId, alias) {
    return this.client.publish_room_alias(roomId, alias);
  }

  unpublish_room_alias(roomId, alias) {
    return this.client.unpublish_room_alias(roomId, alias);
  }

  set_room_canonical_alias(roomId, alias, altAliases) {
    return this.client.set_room_canonical_alias(roomId, alias ?? undefined, JSON.stringify(parseJsonArray(altAliases)));
  }

  room_unread_stats(roomId) {
    return normalizeWasmValue(this.client.room_unread_stats(roomId));
  }

  fetch_notification(roomId, eventId) {
    return normalizeWasmValue(this.client.fetch_notification(roomId, eventId));
  }

  room_tags(roomId) {
    return normalizeBridgeValue(this.client.room_tags(roomId));
  }

  set_room_favourite(roomId, favourite) {
    return this.client.set_room_favourite(roomId, favourite);
  }

  set_room_low_priority(roomId, lowPriority) {
    return this.client.set_room_low_priority(roomId, lowPriority);
  }

  async own_last_read(roomId) {
    return normalizeWasmValue(await this.client.own_last_read(roomId));
  }

  async dm_peer_user_id(roomId) {
    return await this.client.dm_peer_user_id(roomId) ?? null;
  }

  mark_fully_read_at(roomId, eventId) {
    return this.client.mark_fully_read_at(roomId, eventId);
  }

  observe_receipts(roomId, onChanged) {
    return this.client.observe_receipts(roomId, () => onChanged());
  }

  observe_own_receipt(roomId, onChanged) {
    return this.client.observe_own_receipt(roomId, () => onChanged());
  }

  unobserve_receipts(id) {
    return this.client.unobserve_receipts(id);
  }

  search_users(term, limit) {
    return normalizeWasmValue(this.client.search_users(term, limit) ?? []);
  }

  get_user_profile(userId) {
    return normalizeWasmValue(this.client.get_user_profile(userId));
  }

  ignore_user(userId) {
    return this.client.ignore_user(userId);
  }

  unignore_user(userId) {
    return this.client.unignore_user(userId);
  }

  ignored_users() {
    return normalizeWasmValue(this.client.ignored_users() ?? []);
  }

  get_pinned_events(roomId) {
    return normalizeWasmValue(this.client.get_pinned_events(roomId) ?? []);
  }

  set_pinned_events(roomId, eventIds) {
    return this.client.set_pinned_events(roomId, parseJsonArray(eventIds));
  }

  seen_by_for_event(roomId, eventId, limit) {
    return normalizeBridgeValue(this.client.seen_by_for_event(roomId, eventId, limit));
  }

  send_poll_start(roomId, question, answers, kind, maxSelections) {
    return this.client.send_poll_start(
      roomId,
      question,
      JSON.stringify(parseJsonArray(answers)),
      kind,
      maxSelections,
    );
  }

  send_poll_response(roomId, pollEventId, answers) {
    return this.client.send_poll_response(roomId, pollEventId, JSON.stringify(parseJsonArray(answers)));
  }

  send_poll_end(roomId, pollEventId) {
    return this.client.send_poll_end(roomId, pollEventId);
  }

  load_room_list_cache() {
    return normalizeWasmValue(this.client.load_room_list_cache() ?? []);
  }

  send_queue_set_enabled(enabled) {
    return this.client.send_queue_set_enabled(enabled);
  }

  room_send_queue_set_enabled(roomId, enabled) {
    return this.client.room_send_queue_set_enabled(roomId, enabled);
  }

  async send_message(roomId, body) {
    return normalizeWasmValue(await this.client.send_message(roomId, body));
  }

  observe_sends(onUpdate) {
    return this.client.observe_sends((update) => {
      onUpdate(normalizeWasmValue(update ?? null));
    });
  }

  unobserve_sends(id) {
    return this.client.unobserve_sends(id);
  }

  ensure_dm(userId) {
    return this.client.ensure_dm(userId) ?? null;
  }

  my_spaces() {
    return normalizeWasmValue(this.client.my_spaces() ?? []);
  }

  create_space(name, topic, isPublic, invitees) {
    return this.client.create_space(name, topic ?? undefined, isPublic, invitees) ?? null;
  }

  space_add_child(spaceId, childRoomId, order, suggested) {
    return this.client.space_add_child(spaceId, childRoomId, order ?? undefined, suggested ?? undefined);
  }

  space_remove_child(spaceId, childRoomId) {
    return this.client.space_remove_child(spaceId, childRoomId);
  }

  space_hierarchy(spaceId, from, limit, maxDepth, suggestedOnly) {
    return normalizeWasmValue(
      this.client.space_hierarchy(
        spaceId,
        from ?? undefined,
        limit,
        maxDepth ?? undefined,
        suggestedOnly,
      ),
    );
  }

  space_invite_user(spaceId, userId) {
    return this.client.space_invite_user(spaceId, userId);
  }

  send_attachment_bytes(roomId, filename, mime, data) {
    return this.client.send_attachment_bytes(roomId, filename, mime, data);
  }

  send_existing_attachment(roomId, attachmentJson, body) {
    return this.client.send_existing_attachment(roomId, attachmentJson, body ?? undefined);
  }

  download_attachment_to_cache_file(infoJson, filenameHint) {
    return this.client.download_attachment_to_cache_file(infoJson, filenameHint ?? undefined) ?? null;
  }

  thumbnail_to_cache(infoJson, width, height, crop) {
    return this.client.thumbnail_to_cache(infoJson, width, height, crop) ?? null;
  }

  mxc_thumbnail_to_cache(mxcUri, width, height, crop) {
    return this.client.mxc_thumbnail_to_cache(mxcUri, width, height, crop) ?? null;
  }

  room_successor(roomId) {
    return normalizeWasmValue(this.client.room_successor(roomId));
  }

  room_predecessor(roomId) {
    return normalizeWasmValue(this.client.room_predecessor(roomId));
  }

  observe_timeline(roomId, onDiff, onError) {
    return this.client.observe_timeline(
      roomId,
      (diff) => onDiff(normalizeWasmValue(diff ?? null)),
      (message) => onError(message ?? ""),
    );
  }

  unobserve_timeline(id) {
    return this.client.unobserve_timeline(id);
  }

  observe_room_list(onReset, onUpdate) {
    return this.client.observe_room_list(
      (items) => onReset(normalizeWasmValue(items ?? [])),
      (item) => onUpdate(normalizeWasmValue(item ?? null)),
    );
  }

  unobserve_room_list(id) {
    return this.client.unobserve_room_list(id);
  }

  room_list_set_unread_only(id, unreadOnly) {
    return this.client.room_list_set_unread_only(id, unreadOnly);
  }

  start_supervised_sync(onState) {
    this.client.start_supervised_sync((state) => {
      onState(normalizeWasmValue(state ?? null));
    });
  }
}

export class WebMatrixFacade {
  constructor(client, homeserverUrl, baseStoreDir, accountId) {
    this.client = client;
    this.homeserverUrl = homeserverUrl;
    this.baseStoreDir = baseStoreDir;
    this.accountId = accountId ?? null;
    this.roomListToken = null;
    this.sessionStore = new SessionStore();
  }

  static async create(homeserverUrl, baseStoreDir, accountId) {
    const client = await WasmClientBridge.create(homeserverUrl, baseStoreDir, accountId);
    return new WebMatrixFacade(client, homeserverUrl, baseStoreDir, accountId ?? null);
  }

  free() {
    if (this.roomListToken != null) {
      this.client.unobserve_room_list(this.roomListToken);
      this.roomListToken = null;
    }
    this.client.free();
  }

  async login(username, password, deviceDisplayName) {
    const result = await this.client.login(username, password, deviceDisplayName ?? undefined);
    if (this.isLoggedIn()) {
      this.sessionStore.save(accountKey(this.accountId, this.homeserverUrl), {
        homeserverUrl: this.homeserverUrl,
        accountId: this.accountId,
        userId: this.whoami(),
      });
    }
    return result;
  }

  async logout() {
    const result = await this.client.logout();
    this.sessionStore.clear(accountKey(this.accountId, this.homeserverUrl));
    return result;
  }

  isLoggedIn() {
    return this.client.is_logged_in();
  }

  loginOauthLoopbackAvailable() {
    return this.client.login_oauth_loopback_available();
  }

  loginSsoLoopbackAvailable() {
    return this.client.login_sso_loopback_available();
  }

  homeserverLoginDetails() {
    return this.client.homeserver_login_details();
  }

  whoami() {
    return this.client.whoami() ?? null;
  }

  async listRooms() {
    return await this.client.rooms();
  }

  loadRoomListCache() {
    return this.client.load_room_list_cache();
  }

  roomTags(roomId) {
    return this.client.room_tags(roomId);
  }

  setRoomFavourite(roomId, favourite) {
    return this.client.set_room_favourite(roomId, favourite);
  }

  setRoomLowPriority(roomId, lowPriority) {
    return this.client.set_room_low_priority(roomId, lowPriority);
  }

  async getRoomTimeline(roomId, limit = 50) {
    return await this.client.recent_events(roomId, limit);
  }

  observeTimeline(roomId, onDiff, onError) {
    const token = this.client.observe_timeline(roomId, onDiff, (error) => {
      onError?.(error ?? "Timeline error");
    });
    return () => {
      this.client.unobserve_timeline(token);
    };
  }

  unobserveTimeline(unsubscribe) {
    unsubscribe();
    return true;
  }

  async sendText(roomId, body, formattedBody) {
    void formattedBody;
    const okResult = await this.client.send_message(roomId, body);
    return okResult ? { ok: true } : err("Failed to send message");
  }

  paginateBackwards(roomId, count) {
    return this.client.paginate_backwards(roomId, count);
  }

  paginateForwards(roomId, count) {
    return this.client.paginate_forwards(roomId, count);
  }

  markRead(roomId) {
    return this.client.mark_read(roomId);
  }

  markReadAt(roomId, eventId) {
    return this.client.mark_read_at(roomId, eventId);
  }

  react(roomId, eventId, emoji) {
    return this.client.react(roomId, eventId, emoji);
  }

  reply(roomId, inReplyTo, body) {
    return this.client.reply(roomId, inReplyTo, body);
  }

  edit(roomId, targetEventId, newBody) {
    return this.client.edit(roomId, targetEventId, newBody);
  }

  redact(roomId, eventId, reason) {
    return this.client.redact(roomId, eventId, reason);
  }

  setTyping(roomId, typing) {
    return this.client.set_typing(roomId, typing);
  }

  observeTyping(roomId, onUpdate) {
    return this.client.observe_typing(roomId, onUpdate);
  }

  unobserveTyping(token) {
    return this.client.unobserve_typing(token);
  }

  reactionsForEvent(roomId, eventId) {
    return this.client.reactions_for_event(roomId, eventId);
  }

  reactionsBatch(roomId, eventIdsJson) {
    return this.client.reactions_batch(roomId, eventIdsJson);
  }

  async publicRooms(server, search, limit, since) {
    return await this.client.public_rooms(server ?? undefined, search ?? undefined, limit, since ?? undefined);
  }

  resolveRoomId(idOrAlias) {
    return this.client.resolve_room_id(idOrAlias) ?? null;
  }

  joinByIdOrAlias(idOrAlias) {
    this.client.join_by_id_or_alias(idOrAlias);
    return true;
  }

  async listInvited() {
    return await this.client.list_invited();
  }

  acceptInvite(roomId) {
    return this.client.accept_invite(roomId);
  }

  leaveRoom(roomId) {
    return this.client.leave_room(roomId);
  }

  createRoom(name, topic, invitees, isPublic, roomAlias) {
    return this.client.create_room(name ?? undefined, topic ?? undefined, invitees, isPublic, roomAlias ?? undefined);
  }

  setRoomName(roomId, name) {
    return this.client.set_room_name(roomId, name);
  }

  setRoomTopic(roomId, topic) {
    return this.client.set_room_topic(roomId, topic);
  }

  async roomProfile(roomId) {
    return await this.client.room_profile(roomId);
  }

  async roomNotificationMode(roomId) {
    return await this.client.room_notification_mode(roomId) ?? null;
  }

  async setRoomNotificationMode(roomId, mode) {
    return await this.client.set_room_notification_mode(roomId, mode);
  }

  async listMembers(roomId) {
    return await this.client.list_members(roomId);
  }

  roomPowerLevels(roomId) {
    return this.client.room_power_levels(roomId);
  }

  async getUserPowerLevel(roomId, userId) {
    return await this.client.get_user_power_level(roomId, userId);
  }

  async listMyDevices() {
    return await this.client.list_my_devices();
  }

  startVerificationInbox(onRequest, onError) {
    return this.client.start_verification_inbox(onRequest, onError);
  }

  unobserveVerificationInbox(id) {
    return this.client.unobserve_verification_inbox(id);
  }

  async checkVerificationRequest(userId, flowId) {
    return await this.client.check_verification_request(userId, flowId);
  }

  async startSelfSas(targetDeviceId, onPhase, onEmojis, onError) {
    return await this.client.start_self_sas(targetDeviceId, onPhase, onEmojis, onError);
  }

  async startUserSas(userId, onPhase, onEmojis, onError) {
    return await this.client.start_user_sas(userId, onPhase, onEmojis, onError);
  }

  async acceptVerificationRequest(flowId, otherUserId, onPhase, onEmojis, onError) {
    return await this.client.accept_verification_request(flowId, otherUserId, onPhase, onEmojis, onError);
  }

  async acceptSas(flowId, otherUserId, onPhase, onEmojis, onError) {
    return await this.client.accept_sas(flowId, otherUserId, onPhase, onEmojis, onError);
  }

  async confirmVerification(flowId) {
    return await this.client.confirm_verification(flowId);
  }

  async cancelVerification(flowId) {
    return await this.client.cancel_verification(flowId);
  }

  async cancelVerificationRequest(flowId, otherUserId) {
    return await this.client.cancel_verification_request(flowId, otherUserId);
  }

  setupRecovery(onProgress, onDone, onError) {
    return this.client.setup_recovery(onProgress, onDone, onError);
  }

  observeRecoveryState(onUpdate) {
    return this.client.observe_recovery_state(onUpdate);
  }

  unobserveRecoveryState(token) {
    return this.client.unobserve_recovery_state(token);
  }

  observeBackupState(onUpdate) {
    return this.client.observe_backup_state(onUpdate);
  }

  unobserveBackupState(token) {
    return this.client.unobserve_backup_state(token);
  }

  async backupExistsOnServer(fetch) {
    return await this.client.backup_exists_on_server(fetch);
  }

  async setKeyBackupEnabled(enabled) {
    return await this.client.set_key_backup_enabled(enabled);
  }

  async recoverWithKey(recoveryKey) {
    return await this.client.recover_with_key(recoveryKey);
  }

  canUserBan(roomId, userId) {
    return this.client.can_user_ban(roomId, userId);
  }

  canUserInvite(roomId, userId) {
    return this.client.can_user_invite(roomId, userId);
  }

  canUserRedactOther(roomId, userId) {
    return this.client.can_user_redact_other(roomId, userId);
  }

  updatePowerLevelForUser(roomId, userId, powerLevel) {
    return this.client.update_power_level_for_user(roomId, userId, powerLevel);
  }

  applyPowerLevelChanges(roomId, changesJson) {
    return this.client.apply_power_level_changes(roomId, changesJson);
  }

  reportContent(roomId, eventId, score, reason) {
    return this.client.report_content(roomId, eventId, score ?? undefined, reason ?? undefined);
  }

  reportRoom(roomId, reason) {
    return this.client.report_room(roomId, reason ?? undefined);
  }

  roomJoinRule(roomId) {
    return this.client.room_join_rule(roomId) ?? null;
  }

  setRoomJoinRule(roomId, rule) {
    return this.client.set_room_join_rule(roomId, rule);
  }

  roomHistoryVisibility(roomId) {
    return this.client.room_history_visibility(roomId) ?? null;
  }

  setRoomHistoryVisibility(roomId, visibility) {
    return this.client.set_room_history_visibility(roomId, visibility);
  }

  roomDirectoryVisibility(roomId) {
    return this.client.room_directory_visibility(roomId) ?? null;
  }

  setRoomDirectoryVisibility(roomId, visibility) {
    return this.client.set_room_directory_visibility(roomId, visibility);
  }

  roomAliases(roomId) {
    return this.client.room_aliases(roomId);
  }

  publishRoomAlias(roomId, alias) {
    return this.client.publish_room_alias(roomId, alias);
  }

  unpublishRoomAlias(roomId, alias) {
    return this.client.unpublish_room_alias(roomId, alias);
  }

  setRoomCanonicalAlias(roomId, alias, altAliases) {
    return this.client.set_room_canonical_alias(roomId, alias ?? undefined, altAliases);
  }

  roomUnreadStats(roomId) {
    return this.client.room_unread_stats(roomId);
  }

  fetchNotification(roomId, eventId) {
    return this.client.fetch_notification(roomId, eventId);
  }

  async ownLastRead(roomId) {
    return await this.client.own_last_read(roomId);
  }

  async dmPeerUserId(roomId) {
    return await this.client.dm_peer_user_id(roomId);
  }

  async isEventReadBy(roomId, eventId, userId) {
    return await this.client.is_event_read_by(roomId, eventId, userId);
  }

  markFullyReadAt(roomId, eventId) {
    return this.client.mark_fully_read_at(roomId, eventId);
  }

  observeReceipts(roomId, onChanged) {
    return this.client.observe_receipts(roomId, onChanged);
  }

  observeOwnReceipt(roomId, onChanged) {
    return this.client.observe_own_receipt(roomId, onChanged);
  }

  unobserveReceipts(token) {
    return this.client.unobserve_receipts(token);
  }

  searchUsers(term, limit) {
    return this.client.search_users(term, limit);
  }

  getUserProfile(userId) {
    return this.client.get_user_profile(userId);
  }

  ignoreUser(userId) {
    return this.client.ignore_user(userId);
  }

  unignoreUser(userId) {
    return this.client.unignore_user(userId);
  }

  ignoredUsers() {
    return this.client.ignored_users();
  }

  getPinnedEvents(roomId) {
    return this.client.get_pinned_events(roomId);
  }

  setPinnedEvents(roomId, eventIds) {
    return this.client.set_pinned_events(roomId, eventIds);
  }

  seenByForEvent(roomId, eventId, limit) {
    return this.client.seen_by_for_event(roomId, eventId, limit);
  }

  banUser(roomId, userId, reason) {
    return this.client.ban_user(roomId, userId, reason ?? undefined);
  }

  unbanUser(roomId, userId, reason) {
    return this.client.unban_user(roomId, userId, reason ?? undefined);
  }

  kickUser(roomId, userId, reason) {
    return this.client.kick_user(roomId, userId, reason ?? undefined);
  }

  inviteUser(roomId, userId) {
    return this.client.invite_user(roomId, userId);
  }

  enableRoomEncryption(roomId) {
    return this.client.enable_room_encryption(roomId);
  }

  sendPollStart(roomId, question, answers, kind = "Disclosed", maxSelections = 1) {
    return this.client.send_poll_start(roomId, question, answers, kind, maxSelections);
  }

  sendPollResponse(roomId, pollEventId, answers) {
    return this.client.send_poll_response(roomId, pollEventId, answers);
  }

  sendPollEnd(roomId, pollEventId) {
    return this.client.send_poll_end(roomId, pollEventId);
  }

  observeRoomList(onReset, onUpdate) {
    const token = this.client.observe_room_list(onReset, onUpdate);
    this.roomListToken = token;
    return token;
  }

  unobserveRoomList(token) {
    if (this.roomListToken === token) {
      this.roomListToken = null;
    }
    return this.client.unobserve_room_list(token);
  }

  setRoomListUnreadOnly(token, unreadOnly) {
    return this.client.room_list_set_unread_only(token, unreadOnly);
  }

  sendQueueSetEnabled(enabled) {
    return this.client.send_queue_set_enabled(enabled);
  }

  roomSendQueueSetEnabled(roomId, enabled) {
    return this.client.room_send_queue_set_enabled(roomId, enabled);
  }

  observeSends(onUpdate) {
    return this.client.observe_sends(onUpdate);
  }

  unobserveSends(token) {
    return this.client.unobserve_sends(token);
  }

  ensureDm(userId) {
    return this.client.ensure_dm(userId);
  }

  mySpaces() {
    return this.client.my_spaces();
  }

  createSpace(name, topic, isPublic, invitees) {
    const parsedInvitees = Array.isArray(invitees) ? invitees : JSON.parse(invitees);
    return this.client.create_space(name, topic ?? undefined, isPublic, parsedInvitees);
  }

  spaceAddChild(spaceId, childRoomId, order, suggested) {
    return this.client.space_add_child(spaceId, childRoomId, order ?? undefined, suggested ?? undefined);
  }

  spaceRemoveChild(spaceId, childRoomId) {
    return this.client.space_remove_child(spaceId, childRoomId);
  }

  spaceHierarchy(spaceId, from, limit, maxDepth, suggestedOnly) {
    return this.client.space_hierarchy(spaceId, from ?? undefined, limit, maxDepth ?? undefined, suggestedOnly);
  }

  spaceInviteUser(spaceId, userId) {
    return this.client.space_invite_user(spaceId, userId);
  }

  sendAttachmentBytes(roomId, filename, mime, data) {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    return this.client.send_attachment_bytes(roomId, filename, mime, bytes);
  }

  sendExistingAttachment(roomId, attachmentJson, body) {
    return this.client.send_existing_attachment(roomId, attachmentJson, body ?? undefined);
  }

  downloadAttachmentToCacheFile(infoJson, filenameHint) {
    return this.client.download_attachment_to_cache_file(infoJson, filenameHint ?? undefined);
  }

  thumbnailToCache(infoJson, width, height, crop) {
    return this.client.thumbnail_to_cache(infoJson, width, height, crop);
  }

  mxcThumbnailToCache(mxcUri, width, height, crop) {
    return this.client.mxc_thumbnail_to_cache(mxcUri, width, height, crop);
  }

  roomSuccessor(roomId) {
    return this.client.room_successor(roomId);
  }

  roomPredecessor(roomId) {
    return this.client.room_predecessor(roomId);
  }

  startSync(onState) {
    this.client.start_supervised_sync(onState);
  }
}

export function as_web_matrix_facade(value) {
  return value;
}
