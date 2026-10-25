# Hermes Wear OS — Design Spec v3

Standalone, first-party-quality Wear OS app turning a Galaxy Watch Ultra into a voice-first client for a
Hermes gateway, reachable **only over the tailnet** (LAN fallback at home). The watch is an edge device:
it hears you, speaks back, senses your body, and shows the agent's live work — **all intelligence and
heavy processing runs on the gateway**. No cloud, no companion phone app, no third-party servers.

- Marketing name: **Hermes Wear OS** · repo/git: `hermes-wearos`
- License: MIT (our code)

## 1. Architecture

```
Galaxy Watch Ultra (Wear OS, 480×480 round) — Hermes Wear OS app
  Vosk offline STT · low-confidence → audio→gateway whisper · Android TTS (default ON)
  Streaming rich replies: markdown · code-collapsed · MERMAID→image · scaled images
  Session-aware TALK button (continue / new / last / choose) + full agent & session browser
  Health Services body tools — per-tool opt-in switches
  Watch notifications: jobs done, approvals waiting (inline Approve/Deny)
  ALL configuration editable in-app — nothing baked into the APK
      │
      │ Bearer token · HTTPS where TLS applies · connection badge in UI
      │   🌐 tailnet: myhost.example.ts.net:8642  (DEFAULT entry, SPEC §11 — WireGuard-
      │      encrypted; from the watch it is reachable only over a tailnet-routed link today:
      │      the paired phone's tailnet over BT/tether — no Tailscale client exists for Wear OS)
      │   🏠 home LAN: 192.168.1.50:8642   (watch's own Wi-Fi — the FALLBACK; endpoint
      │      failover (§11.2) lands here ~2.5 s after the tailnet entry proves unreachable)
      ▼
Hermes gateway (your gateway box) — Hermes API server :8642
  · OpenAI-compat + Runs API: SSE streaming, durable idempotency, approvals,
    session chat/stream, /p/<profile>/ multi-profile routing (key bound per profile)
  · `wear` profile = default watch agent (persona in SOUL.md)
  · (later, optional) watchkit helper: mermaid render, image scale-to-watch, whisper
    STT fallback, health snapshot cache + MCP, proactive event queue, diag sink
```

**Server surface notes (verified live, Hermes 0.21.7):**
`/v1/capabilities` advertises `run_submission, runs_idempotency (durable, 24 h), run_status,
run_events_sse, run_stop, run_steer, run_approval_response, approval_events, session_chat_streaming,
model_options, session_resources`. Per-profile routing requires `gateway.multiplex_profiles=true` and
each profile's own `API_SERVER_KEY`. Idempotency replay proven (`Idempotency-Key` → same `run_id`,
`replayed: true`). Watch v1 dials `/p/wear/v1/...` directly; **watchkit is deferred** until v1 features
need it (mermaid, image scaling, STT fallback, proactive queue).

## 2. Network paths & leak guarantees

| Situation | Path | Badge |
|---|---|---|
| BT range of paired phone (OnePlus 12) | Watch traffic proxies through phone → phone's Tailscale → tailnet | 🌐 tailnet |
| Home Wi-Fi | Watch → LAN direct, TLS (added in M3), mDNS-discovered | 🏠 LAN |
| Public Wi-Fi / LTE, phone absent | Watch's own network — can't reach tailnet | ⛔ offline (parked) |

Guarantees everywhere: Bearer token on every request · TLS/pinning where applicable (tailnet path is
WireGuard-encrypted end to end) · **no Google/cloud STT ever** (Vosk offline; fallback → your gateway) ·
no analytics, no third-party endpoints, no Telegram in the reply path · **tailnet-only paranoid switch**
disables 🏠 and hard-fails rather than touch the internet.

## 3. Sessions: continuity is the default

The Gemini complaint: every press = new chat, no history. Hermes Wear OS flips it:

| Gesture / screen | Behavior |
|---|---|
| Press & hold (default) | TALK on the **current `wear` session — context continues**; header shows `● wear · 12 min` vs `○ wear · new` |
| Hold → drag-up quick actions | **Continue** · **New session** · **Last session** (one-tap jump back) · **Choose agent/session** |
| "start new session" (voice or chip) | Fresh wear session; old lands in history instantly |
| Chats tab → Agents | Every profile on the gateway (`wear`, `research`, `weather`, …) via the gateway |
| Agent → Sessions | **Cross-profile** list: current agent + the DESKTOP (`default`) profile's sessions newest-first (cap 30), profile chips `all / <agents> / 💻 computer`, ⌚ on watch-made ids; **NEW SESSION pinned top**; tap any row → continue from the wrist (foreign-profile row also swaps the agent) |
| Idle rollover | Continuous until explicitly new or 6 h idle; resume is silent with summary continuity |

## 4. Settings & pairing (all on-device)

**Onboarding:** (1) mDNS auto-discovery of the gateway on home Wi-Fi (no typing) · (2) PC runs
`hermes watch pair` (or equivalent CLI) → 6-digit one-shot code (5-min TTL) · (3) watch taps digits →
receives token + endpoints, Keystore-encrypted. Fallbacks: manual entry with keyboard/paste, `adb push`
config for dev. (No QR scan — Ultra has no usable camera.)

**Settings tree:** Gateways (registry rows, endpoint winner dots, add/edit/delete, row-tap select) ·
Connection (endpoints, test, tailnet-only, cert pin, badge) · Agents & sessions
(default agent, idle rollover) · Voice-in (STT mode: on-watch only / +auto-fallback / gateway) ·
Voice-out (master ON/OFF + pinned chat-screen mute, rate, chunking, TTS engine) · Display (mermaid,
image quality, collapsed code, font scale) · Body tools (every switch + test button, digest cadence) ·
Storage (cache meter, cap, TTL, pinned sessions) · Notifications (jobs, approvals, haptics, quiet hours) ·
Advanced (diag ring buffer + upload, poll interval, dev options).

## 5. Features

### Conversation
| # | Feature |
|---|---|
| F1 | Voice (Vosk streaming partials, push-to-talk) or typed; auto audio→gateway whisper on low confidence |
| F2 | SSE streaming replies (server provides keepalive + tool-progress events) |
| F3 | Markdown-lite: headings, bold/italic, lists, blockquotes, compact tables |
| F4 | Code blocks collapsed → tap-expand, monospace, syntax-hidden |
| F5 | Mermaid: fence detected → server-rendered watch-sized image, crown-zoom/pan, long-press source |
| F6 | Inline images: fetched, re-scaled to viewport (WebP), placeholder→image, tap full-screen |
| F7 | Unicode math enforced by `wear` profile prompt (no LaTeX) |
| F8 | Speak-while-streaming: sentence-chunked Android TTS starts ~1–2 s in; pinned mute; tap interrupt; volume boost; on-device |
| F9 | Approve/Deny/Reply cards from Hermes approval events (approve-once, spoken + shown) |
| F10 | Long turns → jobs; watch notification on completion; exact resume on reopen |
| F11 | Session model per §3 (continuity + browser + last-session jump) |
| F12 | Reliability: `Idempotency-Key` retries (server-side dedupe — never double-runs), timeouts, last-state resume |
| F13 | Budgeted Keystore cache: active session pinned; others evicted after 7 days unseen; ~100 MB cap (media evicts first); agents/titles persist; full offline read incl. images; no offline send (v2) |
| F14 | Instant local answers (`pc health`, `gateway health`) without waking the agent |

### Body tools (all OFF until switched)
W1 `watch_heart_rate` · W2 `watch_steps`/rings · W3 `watch_sleep` · W4 `watch_spo2` · W5 `watch_workout` ·
W6 `watch_location` (watch GPS) · W7 `wear_health_digest` auto-attach (opt-in, battery-aware) ·
W8 plumbing: watch → cache → MCP tools on the `wear` profile · W9 live pull-style calls (v2) ·
phone actions (`phone_*`) → future companion, toggle screen reserved.

### Watch UX
U1 onboarding/pairing (mDNS + 6-digit code) · U2 full in-app settings · U3 watch notifications with inline
Approve/Deny + deep-link · U4 Ask tile + pending-approval complication · U5 proactive event queue (SSE
while open; batched Doze-friendly poll while closed) · U6 crown scroll, swipe-back, true-black OLED,
screen-on only while TALKing/speaking · U7 diagnostics ring buffer.

### Server side
S1 Hermes API server on tailnet bind with token (DONE) · S2 `wear` profile + watch persona (DONE) ·
S3 tailnet access (DONE via tailnet bind; `tailscale serve` hostname+TLS optional once enabled in admin
console) · S4 mDNS beacon + LAN TLS (M3) · S5 pairing CLI (`6-digit code` → token handout) (M1/M3) ·
S6 watchkit helper — mermaid render, image scaling, whisper fallback, snapshot cache + MCP, event queue
(comes online as features need it; v1 talks to the API server directly).

## 6. Reference repos → what's taken

| Repo | License | Take |
|---|---|---|
| hermes-watch | MIT | retry/dedupe/job semantics, last-answer resume, watch-prompt port, whisper worker, diag style |
| herald | MIT | opt-in tool switches, inline-approve notifications, session/agent browsing shape, protocol research docs |
| ClawWatch | AGPL-3 | reimpl only: Vosk pipeline, mic perms, TTS volume boost; Galaxy Watch ADB lore |
| TGwear | GPL-3 | patterns only: crown scroll, round chat layout, notification quick-reply |

## 7. Milestones

| M | Deliverable | Done when |
|---|---|---|
| **M0** ✅ | Toolchain + gateway live | doctor green; APK builds; API server on tailnet; `wear` profile answers; idempotent replay proven (all verified 2026-10-23) |
| M1 | Emulator text chat | pairing flow end-to-end; streamed <3 s; kill-mid-ask → no double-run; cache survives kill; settings tree usable |
| M2 | Voice + media | speak→partials→answer; mermaid→image in-frame; speech mid-stream; mute instant; low-confidence→whisper |
| M3 | Real watch (human in loop) | dev mode + wireless ADB; 🌐 and 🏠 both answer; pcap shows only the gateway host as destination |
| M4 | Session model + browser | interrupt → one-tap continue; resume desktop-started session; last-session jump |
| M5 | Body tools + approvals + notifications | approve from wrist; real health answers; toggles respected |
| M6 | Ship-shape | tile/complication, signing, README, one-command install |

## 8. Installs

**Sudo, once:** Android Studio · KVM group add (`usermod -aG kvm <user>`) · udev rules only if USB adb misbehaves.
**Physical, human:** watch dev-mode, pairing codes, every APK install.
**No root, folder-local (`../devkit/`):** Android SDK (platform 35, build-tools 35), Gradle 8.13, JDK pinned
(system 17), gradle caches, AVDs, whisper models, Node server venvs.
**App deps:** Compose-for-Wear + Horologist (Apache-2), OkHttp/SSE, kotlinx-serialization,
androidx.health.services, **vosk-android (Apache-2)**, Android TTS built-in.

## 9. Defaults (locked with owner)

Voice ON + pinned mute · default agent `wear`, browse-to-switch others · session continuity default,
new-session a gesture, sessions always resumable · in-app settings + 6-digit pairing (no APK-baked config)
· no Telegram reply channel · cache 7 d/100 MB pinned-active · mermaid+images server-rendered, crown-zoom ·
body-digest off by default · offline-send later · Vosk small-en-us · v1 approvals = tool approvals only.

## 10. Known risks

1. Phone-proxied tailnet on Galaxy Wear — flakiest link; M3 proves/hardens it.
2. Samsung Health data exposure to third-party watch apps (sleep stages especially) — verify at M4.
3. ~~API-server surface gaps~~ — resolved: `/v1/capabilities` + Runs/approval/session routes cover the spec.
4. Watch-profile token cost — wear profile prompt currently loads a huge AGENTS.md (35k tokens/turn);
   trim for the watch context (M1).
5. Vosk accuracy on watch mic — mitigated by day-one gateway fallback.

## 11. Gateways, endpoint failover & the tailnet (owner multi-gateway epic, 2026-10-24)

The owner runs MULTIPLE Hermes gateways (gateway box + a VPS box) and wants them all reachable —
including over Tailscale from anywhere. **Owner directive, locked: the tailnet URL is the DEFAULT
entry for the home gateway; the home LAN URL is the fallback.**

### 11.1 Gateway registry (data)

`domain/GatewayModel.kt` — pure, unit-tested. One gateway = `{id, label, apiEndpoints[],
watchkitEndpoints[], token}`. Endpoints are ordered bases WITHOUT the `/p/<agent>` suffix — the
profile segment stays derived exactly as before (ProfileLogic), so one gateway serves every agent.
Persisted under NEW DataStore keys `gateways_json` + `gateway_selected`; the legacy keys
(`base_url` / `token` / `watchkit_url`) are **never removed — they are the live MIRROR** every
consumer (ChatSession/Watchkit/pusher/UI) reads, rewritten by `syncMirror()` whenever the selected
gateway or the winning endpoint changes. Upgrade is additive and rollback-safe.

First run / untouched stock defaults seed `hermes` with

```
api:      [http://hermes.tailnet.ts.net:8642,  http://192.168.1.50:8642]
watchkit: [http://hermes.tailnet.ts.net:8651,  http://192.168.1.50:8651]
```

— placeholder tailnet identity FIRST (MagicDNS publishes `.ts.net` names → CGNAT 100.64.0.0/10
addresses routable only inside your tailnet). A migration
from explicit LEGACY values keeps a CUSTOM host first (what worked today works first); stock
defaults migrate to the tailnet-first order. Settings edits fold into the registry: a known
endpoint keeps its position (agent switches re-set base_url constantly and must never reorder),
an unknown host prepends. Token = the gateway's `API_SERVER_KEY`; the watchkit bearer stays a
separate global setting.

### 11.2 Endpoint failover (`domain/Failover.kt` + `net/EndpointResolver.kt`)

Ordered probes with a 2.5 s leash: `GET {endpoint}/p/<agent>/v1/health`, falling back to bare
`GET {endpoint}/health` — first 2xx wins. **This is what makes "tailnet default" true AT HOME
TODAY even though the watch is not on the tailnet:** from the home Wi-Fi the tailnet connect dies
in ~2.5 s, LAN answers instantly, and LAN carries the session (`endpoint winner: …` in logcat).
Winner is remembered for the session (sticky, revalidated every 5 min); a fallback winner gives
the primary a short-backoff retry (60 s, one probe per window — a dead primary never costs the
user per-request); a mid-session I/O failure dethrones the winner and the gateway's next endpoint
is probed and retried **with the same `Idempotency-Key`** (never a double run). When every probe
is dead the stale winner stands (requests fail naturally with a clear error; no null-pointer
storm). State machine is pure (fake prober + fake clock, 12 tests); the resolver is the thin
android shell + 2.5 s OkHttp prober.

### 11.3 Gateway UI + the IME rule

Settings ▸ **Gateways**: a row per gateway (label, selected marker, per-endpoint probe dots —
green ● winner / red ✕ failed / gray ○ unprobed), inline add/edit/delete editor (label, API URL +
backup, watchkit URL, token), row tap = SELECT → re-probe + reload chats/agents/sessions through
the SAME `switchAgent` plumbing (the runOnUi toast gate). The agent-switcher dialog header shows
the current gateway label (`on myhost`).

**IME RULE — NO FIELD EVER DEPENDS ON ENTER** (owner: "typing a URL, enter made a space, no way
to submit"): every single-line input is `singleLine` + `ImeAction.Done/Send` wired to the SAME
action as its explicit button, and the explicit Save/Send button is always visible — chat compose
(`send ➤`), settings (Save), gateway editor (Save/Cancel), rename (Save), prompt editor (send ➤).
A misbehaving IME can slow input, never strand it.

### 11.4 In-app tailnet (tsnet) — STATUS: researched, NOT shipped (timebox stop)

Plan was `github.com/tailscale/tsnet-go` embedded via gomobile (tsnet node in-process + SOCKS5,
no system VPN). **VERIFIED 2026-10-24: that repository does not exist** (GitHub 404; Go module
proxy `not found` for both `tailscale/tsnet-go` and `tailscale/tsnet`). The real landscape:

| Option | Shape | Fit here |
|---|---|---|
| `tailscale/tailscale/tsnet` (in-repo) | server-style Go lib; no mobile story, no SOCKS5 | wrap-able: our own Go shim = tsnet node + local SOCKS5 server (`Dial` through the node resolves `.ts.net` via MagicDNS) → `gomobile bind` → .aar → `Proxy(socks5 127.0.0.1:port)` per OkHttp client. Feasible, but: Go+wireguard ≈ +15–30 MB APK across our pinned ABIs, NDK/cgo 32-bit toolchain, authkey/usercode flow, battery/Doze story all ours to build (est. ≥ a full day, not the 1.5 h toolchain budget) |
| `tailscale/libtailscale` + `tailscale-android` | the OFFICIAL userspace story: Go .so (gomobile `libgojni.so` + NDK) driven by Kotlin `Tailscale.kt`, traffic via **VpnService** (per-app "non-system" VPN, one user consent dialog) — what the Tailscale app itself ships | closest to "shipped": Kotlin bridge exists to copy, needs our auth UX (browser → `tailscale.com/key`) + Wear validation + `RECEIVE_BOOT`/Doze work; SDK is built-from-source (no clean maven AAR) |
| `leondgarse/tailscale_mobile` | community fork exposing tsnet as c-shared + C API | JNI/JNA glue + same size/ABI/auth caveats; no SOCKS5 |
| exit-node fallback (ships TODAY, §2 badge 🌐) | BT-tethered phone holds the tailnet; watch default-routes through it | zero APK size, works; costs the phone radio; the endpoint system already handles it — LAN/phone-absent failover picks the tailnet URL when reachable |

**Stop decision (per this ticket's own timebox):** ship the gateway system; the tailnet DEFAULT
already behaves correctly at home via failover, and away-from-home works via the tethered-phone
path. To make the watch a first-class tailnet node, the owner picks the lane: (a) gomobile tsnet
shim (SOCKS5, +size, battery note: goroutine lives in Graph scope, state on
`filesDir/tailnet` re-auths silently — WorkManager-gated cadence is the follow-up), or (b) ride
libtailscale/VpnService (official, consent dialog, heavier on Wear). Either needs a tsnet
**authkey** (or once-per-node approval on his phone: AuthURL → tailscale.com/key). Dev hook
today: none — this section IS the Phase B artifact.


---

## Appendix A — Landed slices (build log, newest first)

- **Multi-gateway epic + IME no-Enter rule (SPEC §11)** — gateway registry (seed
  tailnet-first `hermes.tailnet.ts.net` → LAN fallback; legacy-key migration that keeps
  the old keys as the live mirror — an updated watch keeps working either way), endpoint
  failover state machine (2.5 s probes `/p/<agent>/v1/health` else `/health`, sticky session
  winner, 60 s primary-retry backoff, mid-session dethrone + advance-and-retry under the same
  `Idempotency-Key`), Settings ▸ Gateways (rows + winner dots + inline add/edit/delete; row tap
  = select reusing the switchAgent reload plumbing), agent dialog header names its gateway.
  **IME rule locked: no field depends on Enter** — every single-line field got singleLine +
  Done/Send wired to the same action as its now-always-visible button; the prompt editor also
  prefills the chat draft. Emulator proof: cold-start `endpoint winner: hermes.tailnet.ts.net:8642`;
  iptables-DROP the tailnet IP → `endpoint winner: 192.168.1.50:8642 · … (primary
  hermes.tailnet.ts.net:8642 down)` and base_url mirror flips with it; un-DROP → the
  60 s backoff retries the primary and tailnet reclaims the crown (no hammering proven);
  gateway add (`gateway saved id=vps-example-com`) / delete (`remaining=1`) / dot states
  screenshotted (`oz-gw-*.png`, `oz-ime-send.png`: typed URL → keyboard send ➤ → `run accepted`).
  27 new pure tests (139 green, was 109). **tsnet research (§11.4): `tailscale/tsnet-go` does
  not exist** (404 + module-proxy not-found); in-app tailnet stop-decision + the two viable
  lanes documented for the owner's pick.
- **Walkthrough fixes #2 — crash, cross-profile sessions, overlay readability, bottom anchor,
  pinch-zoom** — 85 unit tests (was 60). **Toast crash (root fix)**: `Graph`'s scope is
  `CoroutineScope(SupervisorJob())` with NO dispatcher ⇒ every `ChatSession` callback (switchAgent
  onDone …) fires on a Default worker thread ⇒ `Toast.show()` died with "Can't toast on a thread
  that has not called Looper.prepare()". New `Graph.runOnUi{}` main-Looper gate wraps every UI
  touch in a callback (audit: it was the only UI-touching callback; Log.i/goAsync ones are safe).
  **Cross-profile sessions**: new watchkit `GET /sessions?profile=<p>&limit<30` reads the profile's
  **state.db** directly with node:sqlite (the dashboard's own store — `_session_db_path_for_profile`:
  `default` = `~/.hermes/state.db`, else `profiles/<p>/state.db`; dashboard scrypt basic-auth has no
  plaintext to proxy with, and watchkit lives beside the files). Same wire shape as gateway
  `/api/sessions` (+`profile` per row) so `GatewaySessions.parse` serves both; archived/hidden/cron/
  tool/subagent rows and compression children filtered (sidebar semantics). App merges
  gateway(current) + watchkit(default) newest-first, **caps 30, caches zero transcripts** (owner:
  "don't cache too many"). UI: NEW SESSION pinned top (also in Chats header), filter chips, foreign
  rows labeled `💻 computer`, tap → switch agent + adopt (the crash path above). 401s from the
  `default` gateway under the wear token are expected + cache-served, never fatal.
  **PTT overlay redesign** (owner: "font way too big? … show the last few sentences"):
  bottom-of-safe-circle stack of ≤3 lines (`SttFeed`, pure+tested — sentence-split + word-wrap the
  cumulative partial, history on top, freshest LAST in title3, older dimmed caption2), small mic
  dot-ring. `-e ptt_demo` is the forced-listen shot hook. **Bottom anchor** (owner: "after stt …
  it scrolls to the top"): list state HOISTED above the overlay if/else + `ChatAnchor.bottomIndex`
  row-count (pure+tested) + scroll loop in ChatScreen. wear 1.4.1 gotchas measured on-device: the
  effect can fire BEFORE first layout (scroll silently lost → retry per frame); `scrollToItem(last,0)`
  lands short; end-of-list = last row at the Middle anchor with auto-centering padding below (wear
  "bottom" ≠ phone bottom-hugging — latest stays on screen, which was the ask).
  **Pinch-zoom media viewer**: tap image/mermaid card → `ZoomableMediaViewer` (Dialog, full-screen)
  — pinch+pan via raw `awaitEachGesture` (foundation 1.7.6 REMOVED the `TransformState`
  `transformable` overload — compile-time surprise), double-tap toggles 1×↔2.5×, tap/✕ chip closes,
  caption caption3 at bottom. Scope (owner "maybe everything"): images+mermaid now; text keeps
  crown scroll — SLC handles rotary natively on Wear; **follow-up: crown = font-scale** (needs
  RotaryService injection; `adb input` can't synthesize AXIS_ROTATION, verify with the owner's crown).
- **UI epic — owner's watch walkthrough** — five asks + two follow-ups, all on-device proven on
  the emulator (60 unit tests, was 26). **Agent switcher**: round button left of the orb,
  caption3 label = current profile (ellipsized to the circle); dialog lists profiles from the new
  watchkit `GET /agents` (`"default"` + every `profiles/<name>/` with a config.yaml, dot-dirs
  skipped), cached in DataStore, fallback `["wear","default"]`. Switch = swap the `/p/<segment>`
  of the base URL (pure `ProfileLogic`, everything before `/p/` stands); chats carry a `profile`,
  list + new session ids (`<profile>-watch-xxxx`) follow the agent. **Sessions fast-path**: right
  button → gateway `GET /api/sessions` (raw body cached ⇒ readable offline w/ stale hint), watch
  ids matched by `<profile>-watch…` get a ⌚ marker, tapping a row ADOPTS its session id (M4
  resume — desktop chat continues from the wrist). **Stop control** — cancel-route finding: the
  gateway DOES have `POST /v1/runs/{run_id}/stop` (`_http_routes` in
  `gateway/platforms/api_server_runs.py`, `run_stop` capability); app stops client-side first
  (detach SSE+poll via stopped-run guard, settle partial, `— stopped —` marker) then best-effort
  server cancel — proven: `resp {status:"stopping"}` → run `cancelled`. Orb morphs red **STOP**
  while streaming; hold semantics apply only when not running. **PTT overlay**: while listening the
  transcript is REPLACED by a full-screen pulsing-mic view with the big live partial (STT text
  never starts above the fold anymore). **Round insets**: Wear Compose **1.4.1 has no `Scaffold`
  for insets and no `ROUND_SCAFFOLD_INSETS`** (checked the shipped AARs) — own `RoundScreen` math
  (clock top 30 / chin 16 / edge 20 dp + Now-in-Edge arc-lift for the side buttons) applied via
  `windowInsetsPadding` + SLC `contentPadding` on every screen. Gotchas: window insets are NOT
  delivered inside `Dialog` windows (spell paddings out), soft-wrap `Text` in a wrap-content
  centered `Column` can render as nothing (`fillMaxWidth`), Middle-anchored SLC needs the header
  ITEM itself to carry the top inset. **Tap = hands-free** (follow-up A): tap starts Vosk capture
  that auto-finalizes — `Vad` watchdog (pure, tested): ≥1 word + 1.2 s of no-new-word partials →
  flush & send, 20 s hard cap, re-tap = send-now, zero words = cancel; `keyboard` chip flips to
  text input without leaving the listen feel. **Location tool** (follow-up B): Settings ▸ Body
  tools ▸ Location (default OFF); `LocationTool` = `LocationManager.getCurrentLocation(provider,…)`
  (API 30 overload — the Criteria+ProviderRequest one is 31), last-known first, 10 s cap, fail-soft
  status strings; snapshot gains `values.location {lat,lng,accuracy_m,ts}` when on (fresh fix on
  chat-ask pushes, last-known on interval); watchkit serves `watch_location` with a **30-min**
  freshness cap (a 2-h-old position is a wrong position) + MCP `tools/list` entry. Emulator proof:
  `perms=granted providers=passive,fused,gps` → fix `lat=37.4226 lng=-122.0847 acc=5m` (Android emulator mock default) →
  `/health/tool/watch_location` `ok:true`; 45-min-old fix → `stale:true`. Per-profile gateway keys
  reject the on-device wear token with a clean surfaced 401 (expected); E2E on `default` used that
  profile's key via `set_token` — ask answered, `session=default-watch-mvmbxfov`, sessions_dump
  shows it `watch=true`. Watch pending: physical reinstall + bar/round shots (`ui-watch-*`), GPS
  reality vs mock, hands-free VAD in real speech.

- **W1–W4+W8 body tools** (`e070675`) — `androidx.health:health-services-client` **1.0.0**
  (the `…services:services-client` artifact doesn't exist; 1.1.0 needs Kotlin 2.1 — pinned like
  wear-tiles). MeasureClient live HR + PassiveMonitoring steps; sleep/SpO2 probed by data-type
  name so a backing device that exposes them activates them **without recompile**. Snapshot push
  (15-min + on-ask refresh, drop-not-queue) → watchkit `POST /health/snapshot` (atomic
  `.state/snapshot.json`) → `GET /health/tool/<name>` (stale >2 h ⇒ `ok:false stale:true`) →
  **stdio MCP server** (`watchkit.mjs mcp`, zero-dep JSON-RPC) registered under
  `mcp_servers.hermes-watchkit` in the wear profile — hot-reconciled, no gateway restart.
  E2E proof: agent replied "67 bpm, measured about 2 minutes ago" from the synthetic snapshot.
  4 Settings toggles default **OFF** (owner-locked). Watch pending: `scripts/watch-health.sh grant`,
  real data-type probe (`-e body_status`), Doze/battery cost of the cadence.
- **Machine dashboard** (gateway-box side) — `hermes-dashboard.service` (systemd user unit) on
  `0.0.0.0:9119`; sidebar profile switcher → `wear` lists every watch session live (verified,
  9 sessions); desktop app attaches via same URL. Basic auth: provider verifies
  `password_hash` **first** (malformed hash ⇒ "Invalid credentials" even with correct plaintext
  `password`); hash = `scrypt$16384$8$1$<b64salt>$<b64dk>` (stdlib scrypt, 16-B salt, 32-B dk —
  precompute via plugin's `hash_password`); `HERMES_DASHBOARD_BASIC_AUTH_*` env vars **win over
  yaml** (`.env` username overrode `config.yaml` admin — root cause of the login mystery).
  LAN reach needs ufw allow 9119 from 192.168.1.0/24.
- **F6 inline images** (`eeaa8e4`) — watchkit `GET+POST /img {path,width≤1200}`:
  `img_roots` containment **checked before existence** (403 never leaks what exists) → realpath
  + symlink re-check → ext whitelist → 15 MB cap → Pillow Lanczos fit-within → WebP q82,
  cache key `sha256(realpath|mtime|width)`, GIF→first frame. App: `MdBlock.Image` (image-only
  lines), http(s) targets fetched direct (no bearer leak), paths via `/img?path=` with bearer;
  states loading-caption → rounded card → error card (alt + tap-revealed target). SOUL rule:
  emit `![desc](/abs/path.png)`, never raw paths. Proven: agent-written red PNG → card on screen.
- **TALK orb** (`7727e7c`) — floating bottom-center (fell below the fold at 480×480);
  hold = Vosk push-to-talk, tap = keyboard; physical buttons belong to other apps.
