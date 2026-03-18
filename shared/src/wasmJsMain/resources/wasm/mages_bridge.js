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

function parsePostMessagePayload(message) {
  if (typeof message !== "string") {
    return message;
  }
  try {
    return JSON.parse(message);
  } catch {
    return message;
  }
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

  async loginOauthBrowser(redirectUri, deviceDisplayName) {
    return normalizeWasmValue(
      await this.client.loginOauthBrowser(
        redirectUri,
        deviceDisplayName ?? undefined,
      ),
    );
  }

  async finishLoginFromRedirect(callbackUrl, state, deviceDisplayName) {
    return await this.client.finishLoginFromRedirect(
      callbackUrl,
      state ?? "",
      deviceDisplayName ?? undefined,
    );
  }

  login_oauth_loopback_available() {
    return this.client.login_oauth_loopback_available();
  }

  login_sso_loopback_available() {
    return this.client.login_sso_loopback_available();
  }

  async homeserver_login_details() {
    return normalizeWasmValue(await this.client.homeserver_login_details());
  }

  async rooms() {
    return normalizeWasmValue(await this.client.rooms());
  }

  async recent_events(roomId, limit) {
    return normalizeWasmValue(await this.client.recent_events(roomId, limit));
  }

  async paginate_backwards(roomId, count) {
    return await this.client.paginate_backwards(roomId, count);
  }

  async paginate_forwards(roomId, count) {
    return await this.client.paginate_forwards(roomId, count);
  }

  async markRead(roomId) {
    return await this.client.markRead(roomId);
  }

  async markReadAt(roomId, eventId) {
    return await this.client.markReadAt(roomId, eventId);
  }

  async markFullyReadAt(roomId, eventId) {
    return await this.client.markFullyReadAt(roomId, eventId);
  }

  async react(roomId, eventId, emoji) {
    return await this.client.react(roomId, eventId, emoji);
  }

  setTyping(roomId, typing) {
    return this.client.setTyping(roomId, typing);
  }

  observe_typing(roomId, onUpdate) {
    return this.client.observe_typing(roomId, (users) => {
      onUpdate(normalizeWasmValue(users ?? []));
    });
  }

  unobserve_typing(id) {
    return this.client.unobserve_typing(id);
  }

  async reactionsForEvent(roomId, eventId) {
    return normalizeWasmValue(await this.client.reactionsForEvent(roomId, eventId));
  }

  reactionsBatch(roomId, eventIdsJson) {
    return normalizeWasmValue(this.client.reactionsBatch(roomId, eventIdsJson));
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
    try {
      return this.client.join_by_id_or_alias(idOrAlias) ? true : false;
    } catch (e) {
      return false;
    }
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

  async acceptInvite(roomId) {
    return await this.client.acceptInvite(roomId);
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

  async roomProfile(roomId) {
    return normalizeWasmValue(await this.client.roomProfile(roomId));
  }

  async setRoomName(roomId, name) {
    return await this.client.setRoomName(roomId, name);
  }

  async setRoomTopic(roomId, topic) {
    return await this.client.setRoomTopic(roomId, topic);
  }

  async roomNotificationMode(roomId) {
    return await this.client.roomNotificationMode(roomId) ?? null;
  }

  async set_room_notification_mode(roomId, mode) {
    return await this.client.set_room_notification_mode(roomId, mode);
  }

  async room_power_levels(roomId) {
    return normalizeWasmValue(await this.client.room_power_levels(roomId));
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

  async update_power_level_for_user(roomId, userId, powerLevel) {
    return await this.client.update_power_level_for_user(roomId, userId, powerLevel);
  }

  async apply_power_level_changes(roomId, changesJson) {
    return await this.client.apply_power_level_changes(roomId, changesJson);
  }

  async ban_user(roomId, userId, reason) {
    return await this.client.ban_user(roomId, userId, reason ?? undefined);
  }

  async unban_user(roomId, userId, reason) {
    return await this.client.unban_user(roomId, userId, reason ?? undefined);
  }

  async kick_user(roomId, userId, reason) {
    return await this.client.kick_user(roomId, userId, reason ?? undefined);
  }

  async invite_user(roomId, userId) {
    return await this.client.invite_user(roomId, userId);
  }

  async enable_room_encryption(roomId) {
    return await this.client.enable_room_encryption(roomId);
  }

  async report_content(roomId, eventId, score, reason) {
    return await this.client.report_content(roomId, eventId, score ?? undefined, reason ?? undefined);
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

  async setRoomFavourite(roomId, favourite) {
    return await this.client.setRoomFavourite(roomId, favourite);
  }

  async setRoomLowPriority(roomId, lowPriority) {
    return await this.client.setRoomLowPriority(roomId, lowPriority);
  }

  async dm_peer_user_id(roomId) {
    return await this.client.dm_peer_user_id(roomId) ?? null;
  }

  async mark_fully_read_at(roomId, eventId) {
    return await this.client.mark_fully_read_at(roomId, eventId);
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

  search_room(roomId, query, limit, offset) {
    return normalizeWasmValue(this.client.search_room(roomId, query, limit, offset ?? undefined));
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

  async reply(roomId, inReplyTo, body, formattedBody) {
    return await this.client.reply(roomId, inReplyTo, body, formattedBody ?? undefined);
  }

  async edit(roomId, targetEventId, newBody, formattedBody) {
    return await this.client.edit(roomId, targetEventId, newBody, formattedBody ?? undefined);
  }

  async redact(roomId, eventId, reason) {
    return await this.client.redact(roomId, eventId, reason ?? undefined);
  }

  async own_last_read(roomId) {
    return normalizeWasmValue(await this.client.own_last_read(roomId));
  }

  async set_presence(presence, status) {
    return await this.client.set_presence(presence, status ?? undefined);
  }

  async get_presence(userId) {
    return normalizeWasmValue(await this.client.get_presence(userId));
  }

  async fetch_notification(roomId, eventId) {
    return normalizeWasmValue(await this.client.fetch_notification(roomId, eventId));
  }

  async fetch_notifications_since(sinceMs, maxRooms, maxEvents) {
    return normalizeWasmValue(
      await this.client.fetch_notifications_since(sinceMs, maxRooms, maxEvents)
    );
  }
  async send_thread_text(roomId, rootEventId, body, replyToEventId, latestEventId, formattedBody) {
    return await this.client.send_thread_text(
      roomId,
      rootEventId,
      body,
      replyToEventId ?? undefined,
      latestEventId ?? undefined,
      formattedBody ?? undefined
    );
  }

  async thread_summary(roomId, rootEventId, perPage, maxPages) {
    return normalizeWasmValue(
      await this.client.thread_summary(roomId, rootEventId, perPage, maxPages)
    );
  }

  async thread_replies(roomId, rootEventId, from, limit, forward) {
    return normalizeWasmValue(
      await this.client.thread_replies(
        roomId,
        rootEventId,
        from ?? undefined,
        limit,
        forward
      )
    );
  }

  start_call_inbox(onInvite) {
    return this.client.start_call_inbox((payload) => {
      onInvite(normalizeWasmValue(payload ?? null));
    });
  }

  stop_call_inbox(id) {
    return this.client.stop_call_inbox(id);
  }

  async start_live_location(roomId, durationMs) {
    return await this.client.start_live_location(roomId, durationMs);
  }

  async stop_live_location(roomId) {
    return await this.client.stop_live_location(roomId);
  }

  async send_live_location(roomId, geoUri) {
    return await this.client.send_live_location(roomId, geoUri);
  }

  observe_live_location(roomId, onUpdate) {
    return this.client.observe_live_location(roomId, (shares) => {
      onUpdate(normalizeWasmValue(shares ?? []));
    });
  }

  unobserve_live_location(id) {
    return this.client.unobserve_live_location(id);
  }

  async room_preview(idOrAlias) {
    return normalizeWasmValue(await this.client.room_preview(idOrAlias));
  }

  async knock(idOrAlias) {
    return await this.client.knock(idOrAlias);
  }

  async room_successor(roomId) {
    return normalizeWasmValue(await this.client.room_successor(roomId));
  }

  async room_predecessor(roomId) {
    return normalizeWasmValue(await this.client.room_predecessor(roomId));
  }

  async start_element_call(roomId, intent, elementCallUrl, parentUrl, languageTag, theme, onToWidget) {
    return normalizeWasmValue(
      await this.client.start_element_call(
        roomId,
        intent,
        elementCallUrl ?? undefined,
        parentUrl ?? undefined,
        languageTag ?? undefined,
        theme ?? undefined,
        (message) => onToWidget(message ?? "")
      )
    );
  }

  call_widget_from_webview(sessionId, message) {
    return this.client.call_widget_from_webview(sessionId, message);
  }

  stop_element_call(sessionId) {
    return this.client.stop_element_call(sessionId);
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

  async send_message(roomId, body, formattedBody) {
    return normalizeWasmValue(await this.client.send_message(roomId, body, formattedBody ?? undefined));
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

  async my_spaces() {
    return normalizeWasmValue(await this.client.my_spaces() ?? []);
  }

  async create_space(name, topic, isPublic, invitees) {
    return await this.client.create_space(name, topic ?? undefined, isPublic, invitees) ?? null;
  }

  async space_add_child(spaceId, childRoomId, order, suggested) {
    return await this.client.space_add_child(spaceId, childRoomId, order ?? undefined, suggested ?? undefined);
  }

  async space_remove_child(spaceId, childRoomId) {
    return await this.client.space_remove_child(spaceId, childRoomId);
  }

  async space_hierarchy(spaceId, from, limit, maxDepth, suggestedOnly) {
    return normalizeWasmValue(
      await this.client.space_hierarchy(
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

  async loginOauthBrowser(redirectUri, deviceDisplayName) {
    return await this.client.loginOauthBrowser(
      redirectUri,
      deviceDisplayName ?? undefined,
    );
  }

  async finishLoginFromRedirect(callbackUrl, state, deviceDisplayName) {
    const ok = await this.client.finishLoginFromRedirect(
      callbackUrl,
      state ?? "",
      deviceDisplayName ?? undefined,
    );

    if (ok && this.isLoggedIn()) {
      this.sessionStore.save(accountKey(this.accountId, this.homeserverUrl), {
        homeserverUrl: this.homeserverUrl,
        accountId: this.accountId,
        userId: this.whoami(),
      });
    }

    return ok;
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

  async homeserverLoginDetails() {
    return normalizeWasmValue(
      await this.client.homeserver_login_details(),
    );
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

  async setRoomFavourite(roomId, favourite) {
    return await this.client.setRoomFavourite(roomId, favourite);
  }

  async setRoomLowPriority(roomId, lowPriority) {
    return await this.client.setRoomLowPriority(roomId, lowPriority);
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
    const okResult = await this.client.send_message(roomId, body, formattedBody ?? undefined);
    return okResult ? { ok: true } : err("Failed to send message");
  }

  async paginateBackwards(roomId, count) {
    return await this.client.paginate_backwards(roomId, count);
  }

  async paginateForwards(roomId, count) {
    return await this.client.paginate_forwards(roomId, count);
  }

  async markRead(roomId) {
    return await this.client.markRead(roomId);
  }

  async markReadAt(roomId, eventId) {
    return await this.client.markReadAt(roomId, eventId);
  }

  async react(roomId, eventId, emoji) {
    return await this.client.react(roomId, eventId, emoji);
  }

  observeTyping(roomId, onUpdate) {
    return this.client.observe_typing(roomId, onUpdate);
  }

  unobserveTyping(token) {
    return this.client.unobserve_typing(token);
  }

  async reactionsForEvent(roomId, eventId) {
    return await this.client.reactionsForEvent(roomId, eventId);
  }

  async reactionsBatch(roomId, eventIdsJson) {
    return await this.client.reactionsBatch(roomId, eventIdsJson);
  }

  async publicRooms(server, search, limit, since) {
    return await this.client.public_rooms(server ?? undefined, search ?? undefined, limit, since ?? undefined);
  }

  resolveRoomId(idOrAlias) {
    return this.client.resolve_room_id(idOrAlias) ?? null;
  }

  joinByIdOrAlias(idOrAlias) {
    try {
      return this.client.join_by_id_or_alias(idOrAlias);
    } catch (e) {
      return false;
    }
  }

  async listInvited() {
    return await this.client.list_invited();
  }

  async acceptInvite(roomId) {
    return await this.client.acceptInvite(roomId);
  }

  leaveRoom(roomId) {
    return this.client.leave_room(roomId);
  }

  createRoom(name, topic, invitees, isPublic, roomAlias) {
    return this.client.create_room(name ?? undefined, topic ?? undefined, invitees, isPublic, roomAlias ?? undefined);
  }

  async setRoomName(roomId, name) {
    return await this.client.setRoomName(roomId, name);
  }

  async setRoomTopic(roomId, topic) {
    return await this.client.setRoomTopic(roomId, topic);
  }

  async roomProfile(roomId) {
    return await this.client.roomProfile(roomId);
  }

  async roomNotificationMode(roomId) {
    return await this.client.roomNotificationMode(roomId) ?? null;
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

  async dmPeerUserId(roomId) {
    return await this.client.dm_peer_user_id(roomId);
  }

  async isEventReadBy(roomId, eventId, userId) {
    return await this.client.is_event_read_by(roomId, eventId, userId);
  }

  async markFullyReadAt(roomId, eventId) {
    return await this.client.markFullyReadAt(roomId, eventId);
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

  searchRoom(roomId, query, limit, offset) {
    return this.client.search_room(roomId, query, limit, offset ?? undefined);
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
  async sendThreadText(roomId, rootEventId, body, replyToEventId, latestEventId, formattedBody) {
    return await this.client.send_thread_text(
      roomId,
      rootEventId,
      body,
      replyToEventId,
      latestEventId,
      formattedBody
    );
  }

  async threadSummary(roomId, rootEventId, perPage, maxPages) {
    return await this.client.thread_summary(roomId, rootEventId, perPage, maxPages);
  }

  async threadReplies(roomId, rootEventId, from, limit, forward) {
    return await this.client.thread_replies(roomId, rootEventId, from, limit, forward);
  }

  startCallInbox(onInvite) {
    return this.client.start_call_inbox(onInvite);
  }

  stopCallInbox(id) {
    return this.client.stop_call_inbox(id);
  }

  async startLiveLocation(roomId, durationMs) {
    return await this.client.start_live_location(roomId, durationMs);
  }

  async stopLiveLocation(roomId) {
    return await this.client.stop_live_location(roomId);
  }

  async sendLiveLocation(roomId, geoUri) {
    return await this.client.send_live_location(roomId, geoUri);
  }

  observeLiveLocation(roomId, onUpdate) {
    return this.client.observe_live_location(roomId, onUpdate);
  }

  unobserveLiveLocation(id) {
    return this.client.unobserve_live_location(id);
  }

  async roomPreview(idOrAlias) {
    return await this.client.room_preview(idOrAlias);
  }

  async knock(idOrAlias) {
    return await this.client.knock(idOrAlias);
  }

  async roomSuccessor(roomId) {
    return await this.client.room_successor(roomId);
  }

  async roomPredecessor(roomId) {
    return await this.client.room_predecessor(roomId);
  }

  async startElementCall(roomId, intent, elementCallUrl, parentUrl, languageTag, theme, onToWidget) {
    return await this.client.start_element_call(
      roomId,
      intent,
      elementCallUrl,
      parentUrl,
      languageTag,
      theme,
      onToWidget
    );
  }

  callWidgetFromWebview(sessionId, message) {
    return this.client.call_widget_from_webview(sessionId, message);
  }

  stopElementCall(sessionId) {
    return this.client.stop_element_call(sessionId);
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

  async mySpaces() {
    return await this.client.my_spaces();
  }

  async createSpace(name, topic, isPublic, invitees) {
    const parsedInvitees = Array.isArray(invitees) ? invitees : JSON.parse(invitees);
    return await this.client.create_space(name, topic ?? undefined, isPublic, parsedInvitees);
  }

  async spaceAddChild(spaceId, childRoomId, order, suggested) {
    return await this.client.space_add_child(spaceId, childRoomId, order ?? undefined, suggested ?? undefined);
  }

  async spaceRemoveChild(spaceId, childRoomId) {
    return await this.client.space_remove_child(spaceId, childRoomId);
  }

  async spaceHierarchy(spaceId, from, limit, maxDepth, suggestedOnly) {
    return await this.client.space_hierarchy(spaceId, from ?? undefined, limit, maxDepth ?? undefined, suggestedOnly);
  }

  async spaceInviteUser(spaceId, userId) {
    return await this.client.space_invite_user(spaceId, userId);
  }

  async sendAttachmentBytes(roomId, filename, mime, data) {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    return await this.client.send_attachment_bytes(roomId, filename, mime, bytes);
  }

  async sendExistingAttachment(roomId, attachmentJson, body) {
    return await this.client.send_existing_attachment(roomId, attachmentJson, body ?? undefined);
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

  startSync(onState) {
    this.client.start_supervised_sync(onState);
  }
}

export function as_web_matrix_facade(value) {
  return value;
}

// Element Call iframe related stuff
let elementCallIframe = null;
let elementCallMessageListener = null;
let elementCallOnMessage = null;
let elementCallContainer = null;

function isWidgetProtocolMessage(data) {
  return !!data && typeof data === 'object' && (
    (data.response && data.api === 'toWidget') ||
    (!data.response && data.api === 'fromWidget')
  );
}

function applyElementCallContainerState(minimized) {
  if (!elementCallContainer) return;

  if (minimized) {
    elementCallContainer.style.width = '220px';
    elementCallContainer.style.height = '140px';
    elementCallContainer.style.top = '120px';
    elementCallContainer.style.left = '24px';
    elementCallContainer.style.right = 'auto';
    elementCallContainer.style.bottom = 'auto';
    elementCallContainer.style.borderRadius = '16px';
    elementCallContainer.style.overflow = 'hidden';
    elementCallContainer.style.boxShadow = '0 8px 24px rgba(0, 0, 0, 0.35)';
    elementCallContainer.style.background = '#000';
  } else {
    elementCallContainer.style.top = '0';
    elementCallContainer.style.left = '0';
    elementCallContainer.style.right = '0';
    elementCallContainer.style.bottom = '0';
    elementCallContainer.style.width = '100vw';
    elementCallContainer.style.height = '100vh';
    elementCallContainer.style.borderRadius = '0';
    elementCallContainer.style.overflow = 'visible';
    elementCallContainer.style.boxShadow = 'none';
    elementCallContainer.style.background = '#000';
  }
}

export function createElementCallIframe(containerId, widgetUrl, onMessage) {
  if (elementCallIframe) {
    removeElementCallIframe();
  }

  elementCallOnMessage = onMessage;

  let container = document.getElementById(containerId);
  if (!container) {
    container = document.createElement('div');
    container.id = containerId;
    container.style.position = 'fixed';
    container.style.top = '0';
    container.style.left = '0';
    container.style.width = '100vw';
    container.style.height = '100vh';
    container.style.zIndex = '999999';
    container.style.background = '#000';
    document.body.appendChild(container);
  }

  elementCallContainer = container;
  applyElementCallContainerState(false);

  container.innerHTML = '';

  const iframe = document.createElement('iframe');
  iframe.src = widgetUrl;
  iframe.style.width = '100%';
  iframe.style.height = '100%';
  iframe.style.border = 'none';
  iframe.style.position = 'absolute';
  iframe.style.top = '0';
  iframe.style.left = '0';
  iframe.allow = 'camera; microphone; display-capture; fullscreen; geolocation';
  iframe.setAttribute('allow', 'camera; microphone; display-capture; fullscreen; geolocation');

  container.appendChild(iframe);
  elementCallIframe = iframe;

  elementCallMessageListener = function (event) {
    if (elementCallOnMessage && isWidgetProtocolMessage(event.data)) {
      const data = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
      try {
        elementCallOnMessage(data);
      } catch (_) { }
    }
  };

  window.addEventListener('message', elementCallMessageListener);

  return true;
}

export default createElementCallIframe;

export function sendToElementCallIframe(message) {
  const payload = parsePostMessagePayload(message);

  if (elementCallIframe && elementCallIframe.contentWindow) {
    try {
      elementCallIframe.contentWindow.postMessage(payload, '*');
      return true;
    } catch (_) { }
  }

  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(payload, '*');
      return true;
    }
  } catch (_) { }

  return false;
}

export function removeElementCallIframe() {
  if (elementCallMessageListener) {
    window.removeEventListener('message', elementCallMessageListener);
    elementCallMessageListener = null;
  }

  if (elementCallIframe) {
    if (elementCallIframe.parentNode) {
      elementCallIframe.parentNode.removeChild(elementCallIframe);
    }
    elementCallIframe = null;
  }

  if (elementCallContainer) {
    if (elementCallContainer.parentNode) {
      elementCallContainer.parentNode.removeChild(elementCallContainer);
    }
    elementCallContainer = null;
  }

  elementCallOnMessage = null;
}

export function setElementCallMinimized(minimized) {
  applyElementCallContainerState(minimized);
}

export function sendElementActionResponse(originalMessage) {
  try {
    const msg = JSON.parse(originalMessage);
    const response = {
      api: "toWidget",
      widgetId: msg.widgetId || "",
      requestId: msg.requestId || "",
      action: msg.action || "",
      response: {}
    };

    if (elementCallIframe && elementCallIframe.contentWindow) {
      elementCallIframe.contentWindow.postMessage(response, '*');
      return true;
    }
  } catch (_) { }
  return false;
}
