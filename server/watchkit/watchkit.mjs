#!/usr/bin/env node
/**
 * watchkit — tiny helper daemon for the Hermes Wear OS watch. Two heavy-lift services
 * the watch can't do on-device, behind one bearer token, tailnet-only:
 *
 *   GET  /health          → {status:"ok", version, uptime_s}                (public probe)
 *   GET  /agents          → {agents:["default", …profiles/<name>/ with config.yaml]}  (bearer)
 *   GET  /sessions        → gateway-shaped session list for a profile (bearer)
 *        ?profile=<name>&limit=<n≤30> → {object:"list", data:[{id,title,last_active,message_count,profile}]}
 *        Reads the profile's SQLite state.db DIRECTLY (same store the dashboard's GET /api/sessions
 *        reads) — dashboard auth is scrypt basic-auth with no recoverable plaintext, and watchkit
 *        is on the same box, so reading beats proxying. Hidden/archived/cron/tool/subagent rows and
 *        compression-continuation children are filtered out (desktop-sidebar semantics, newest-first).
 *   GET  /session_messages → transcript backfill for an adopted session (bearer)
 *        ?profile=<name>&session_id=<id>&limit=<n≤120> → {messages:[{role,content,ts}],session_id,profile,count}
 *        Same state.db, `messages` table: user/assistant + active + non-empty content, compression
 *        summary rows surfacing as role:"summary", noise display_kinds dropped, NEWEST [limit] rows
 *        returned oldest-first, per-message content capped at 2KB. Empty list for unknown ids (200).
 *   POST /health/snapshot → {ok:true}   body = watch body-tools snapshot JSON (W1-W8, bearer)
 *   GET  /health/latest   → latest stored snapshot (bearer, 404 before first push)
 *   GET  /health/tool/<name> → per-tool MCP-style answer derived from the snapshot
 *        watch_heart_rate · watch_steps · watch_sleep · watch_spo2
 *        {tool, ok:true, data:{…}} | {tool, ok:false, reason} | {tool, ok:false, stale:true, age_s}
 *   GET  /img             → scaled image/webp ?path=…&width=460 (allowlisted scaler, Pillow → WebP q82)
 *        width OPTIONAL (owner batch 2): no width (or width=0) ⇒ ORIGINAL bytes served, no re-encode,
 *        for png/jpeg/webp (watch downsamples at decode); gif/bmp still fall back to the 460 scaler.
 *        15MB cap + img_roots allowlist always apply. X-Cache key = realpath|mtime|width ("full").
 *   POST /img             → same, JSON body {path[,width]}
 *   POST /render/mermaid  → image/png  {code, width, format?}  (mmdc, .cache/ renders)
 *   POST /stt/whisper     → {text}     multipart "audio" wav/webm ≤10MB     (faster-whisper CPU)
 *
 * Zero framework: node:http + node:crypto only. Everything folder-local under
 * server/watchkit/ (.cache for renders+npx+puppeteer, .models for whisper weights).
 *
 * Auth: `Authorization: Bearer <token>` from config.json (see config.example.json).
 * NOTE v1: the whisper python process is COLD-START on every request (~2-4s model load).
 * Keeping it warm (persistent worker/stdin loop) is a deliberate TODO, not a bug.
 */
import http from 'node:http';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const VERSION = '0.1.0';
const WK_DIR = path.dirname(fileURLToPath(import.meta.url));

// ── config ─────────────────────────────────────────────────────────────────
const CONFIG_PATH = process.env.WATCHKIT_CONFIG || path.join(WK_DIR, 'config.json');
let cfg = {};
try {
  cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
} catch (e) {
  // MCP stdio mode is often launched with WATCHKIT_TOKEN/WATCHKIT_URL env only: config.json
  // may be absent there. HTTP mode keeps the old hard-fail behavior.
  if (!process.env.WATCHKIT_TOKEN) {
    console.error(`watchkit: cannot read ${CONFIG_PATH} (${e.message}) — cp config.example.json config.json + set token`);
    process.exit(2);
  }
  console.error(`watchkit: config.json unreadable (${e.message}) — continuing on WATCHKIT_TOKEN env`);
}
const TOKEN = process.env.WATCHKIT_TOKEN || cfg.token || '';
if (!TOKEN) { console.error('watchkit: empty token in config'); process.exit(2); }
const HOST = process.env.WATCHKIT_HOST || cfg.host || '127.0.0.1'; // bind: set your LAN/tailnet IP in config.json
const PORT = Number(process.env.WATCHKIT_PORT || cfg.port || 8651);
// Stdio MCP mode (`node watchkit.mjs mcp`): stdout is reserved for JSON-RPC; logs go to stderr.
const MCP_MODE = process.argv.slice(2).includes('mcp');
const IMG_TIMEOUT_MS = 30_000;        // Pillow scale hard cap (used by handleImg)
const RENDER_TIMEOUT_MS = 60_000;   // mmdc (chromium) hard cap
const STT_TIMEOUT_MS = 55_000;      // python whisper hard cap
const AUDIO_MAX_BYTES = 10 * 1024 * 1024;
const JSON_MAX_BYTES = 1024 * 1024;

// ── folders (all folder-local, gitignored) ─────────────────────────────────
const CACHE_DIR = path.join(WK_DIR, '.cache');
const TMP_DIR = path.join(CACHE_DIR, 'tmp');
const NPM_CACHE = path.join(CACHE_DIR, 'npm');          // npx package cache
const PUPPETEER_CACHE = path.join(CACHE_DIR, 'puppeteer'); // chromium download
const MODELS_DIR = path.join(WK_DIR, '.models');
const VENV_PY = path.join(WK_DIR, '.venv', 'bin', 'python');
const TRANSCRIBER = path.join(WK_DIR, 'whisper_transcribe.py');
const PUPPETEER_CFG = path.join(CACHE_DIR, 'puppeteer.json');
for (const d of [CACHE_DIR, TMP_DIR, NPM_CACHE, PUPPETEER_CACHE, MODELS_DIR]) fs.mkdirSync(d, { recursive: true });
fs.writeFileSync(PUPPETEER_CFG, JSON.stringify({ args: ['--no-sandbox', '--disable-dev-shm-usage'] }));

const START_MS = Date.now();
const CHILD_ENV = {
  ...process.env,
  PUPPETEER_CACHE_DIR: PUPPETEER_CACHE,
  npm_config_cache: NPM_CACHE,
  // keep any HF hub side-cache off ~/.cache too:
  HF_HOME: path.join(MODELS_DIR, 'hf'),
};

// ── body-tools snapshot (SPEC W8: watch → cache → MCP tools) ───────────────
// The watch POSTs its latest body snapshot; we keep only the freshest one in .state/snapshot.json
// (atomic write, same fs) and derive per-tool answers for MCP tools/call. Stale >2h ⇒ ok:false
// with stale:true — "heart rate 41" from a dead watch is worse than no answer.
// ── agents (profile discovery for the watch's agent switcher) ──────────────
// Same answer the desktop sidebar gives: the multiplexed `default` home + every profiles/<name>/
// that has a config.yaml (a half-created dir is not an agent). Read-only directory scan.
const PROFILES_DIR = process.env.WATCHKIT_PROFILES || cfg.profiles_dir || path.join(os.homedir(), '.hermes', 'profiles');

function listAgents() {
  const agents = ['default'];
  let names = [];
  try { names = fs.readdirSync(PROFILES_DIR); } catch { /* profiles dir missing: default only */ }
  for (const name of names.sort()) {
    if (name.startsWith('.')) continue; // dot-dirs are staging/temp scaffolding, not agents
    try {
      const dir = path.join(PROFILES_DIR, name);
      if (fs.statSync(dir).isDirectory() && fs.existsSync(path.join(dir, 'config.yaml'))) agents.push(name);
    } catch { /* vanished mid-scan / unreadable — skip */ }
  }
  return agents;
}

const STATE_DIR = path.join(WK_DIR, '.state');
const SNAPSHOT_PATH = path.join(STATE_DIR, 'snapshot.json');
const SNAPSHOT_MAX_BYTES = 256 * 1024;
const SNAPSHOT_STALE_MS = 2 * 60 * 60 * 1000;
// Owner ask B: a 2-h-old position is a WRONG position — the location tool ages 4x faster.
const LOCATION_STALE_MS = 30 * 60 * 1000;
const TOOL_NAMES = ['watch_heart_rate', 'watch_steps', 'watch_sleep', 'watch_spo2', 'watch_location'];
fs.mkdirSync(STATE_DIR, { recursive: true });

async function writeJsonAtomic(file, obj) {
  const tmp = `${file}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  await fsp.writeFile(tmp, JSON.stringify(obj, null, 1) + '\n');
  await fsp.rename(tmp, file); // same dir — atomic
}

function readSnapshot() {
  try { return JSON.parse(fs.readFileSync(SNAPSHOT_PATH, 'utf8')); } catch { return null; }
}

/** Per-tool JSON derived from the stored snapshot (shared by HTTP + MCP surfaces). */
function toolResult(name, snap, nowMs = Date.now()) {
  const base = { tool: name };
  if (!snap || typeof snap !== 'object') return { ...base, ok: false, reason: 'no snapshot stored (watch has not pushed yet)' };
  const enabled = Array.isArray(snap.enabled_tools) ? snap.enabled_tools : [];
  if (!enabled.includes(name)) return { ...base, ok: false, reason: 'tool disabled on watch (Settings ▸ Body tools)' };
  const ts = Number(snap.ts) || 0;
  const ageS = Math.max(Math.round((nowMs - ts) / 1000), 0);
  if (!ts || nowMs - ts > SNAPSHOT_STALE_MS) return { ...base, ok: false, stale: true, age_s: ageS, reason: 'snapshot older than 2h' };
  const v = snap.values && typeof snap.values === 'object' ? snap.values : {};
  switch (name) {
    case 'watch_heart_rate':
      return v.heart_rate_bpm == null ? { ...base, ok: false, reason: 'snapshot has no heart_rate_bpm' }
        : { ...base, ok: true, data: { bpm: v.heart_rate_bpm, ts } };
    case 'watch_steps':
      return v.steps_today == null ? { ...base, ok: false, reason: 'snapshot has no steps_today' }
        : { ...base, ok: true, data: { steps_today: v.steps_today, ts } };
    case 'watch_sleep': {
      const s = v.sleep && typeof v.sleep === 'object' ? v.sleep : null;
      if (!s || s.last_session_start == null) return { ...base, ok: false, reason: 'snapshot has no sleep session' };
      return { ...base, ok: true, data: {
        last_session_start: s.last_session_start,
        last_session_end: s.last_session_end ?? null,
        duration_hours: s.duration_hours ?? null,
        ts,
      } };
    }
    case 'watch_spo2':
      return v.spo2_pct == null ? { ...base, ok: false, reason: 'snapshot has no spo2_pct' }
        : { ...base, ok: true, data: { spo2_pct: v.spo2_pct, ts } };
    case 'watch_location': {
      // Freshness keys on the FIX's own ts (not the snapshot push), capped at 30 min by design.
      const l = v.location && typeof v.location === 'object' ? v.location : null;
      if (!l || l.lat == null || l.lng == null) return { ...base, ok: false, reason: 'snapshot has no location fix (watch indoors, perms off, or tool just enabled)' };
      const lts = Number(l.ts) || ts;
      const ageLoc = Math.max(Math.round((nowMs - lts) / 1000), 0);
      if (nowMs - lts > LOCATION_STALE_MS) return { ...base, ok: false, stale: true, age_s: ageLoc, reason: 'location fix older than 30 min' };
      return { ...base, ok: true, data: { lat: l.lat, lng: l.lng, accuracy_m: l.accuracy_m ?? null, ts: lts, age_s: ageLoc } };
    }
    default:
      return { ...base, ok: false, reason: 'unknown tool' };
  }
}

// ── helpers ────────────────────────────────────────────────────────────────
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

function timingSafeEqual(a, b) {
  const ba = Buffer.from(a), bb = Buffer.from(b);
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

function logLine(fields) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...fields }) + '\n';
  if (MCP_MODE) process.stderr.write(line); // stdio MCP: stdout is JSON-RPC only
  else process.stdout.write(line);
}

function send(res, status, body, headers = {}) {
  const isBuf = Buffer.isBuffer(body);
  res.writeHead(status, {
    'Content-Type': isBuf ? 'application/octet-stream' : 'application/json; charset=utf-8',
    'Content-Length': isBuf ? body.length : Buffer.byteLength(String(body)),
    ...headers,
  });
  res.end(body);
}
const sendErr = (res, status, msg) => send(res, status, JSON.stringify({ error: msg }));

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > maxBytes) { reject(Object.assign(new Error('payload too large'), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function runChild(cmd, args, opts) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { ...opts, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    const killer = setTimeout(() => child.kill('SIGKILL'), opts.timeout ?? 60_000);
    child.on('error', (e) => { clearTimeout(killer); resolve({ code: -1, out, err: err + '\nspawn failed: ' + e.message, killed: false, spawnError: true }); });
    child.on('close', (code, signal) => { clearTimeout(killer); resolve({ code, out, err, killed: signal != null }); });
  });
}

// Minimal multipart/form-data extraction: returns {content, filename, contentType} of field `name`.
function extractMultipartFile(buf, contentType, name) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!m) return null;
  const boundary = Buffer.from(`--${m[1] || m[2].trim()}`);
  let pos = buf.indexOf(boundary);
  while (pos !== -1) {
    const partStart = pos + boundary.length;
    if (buf.slice(partStart, partStart + 2).toString() === '--') break; // final boundary
    const head = buf.indexOf('\r\n\r\n', partStart);
    const next = buf.indexOf(boundary, partStart);
    if (head === -1 || next === -1) break;
    const headers = buf.slice(partStart, head).toString('latin1');
    // body ends before the \r\n that precedes the next boundary
    const body = buf.slice(head + 4, Math.max(head + 4, next - 2));
    const dm = /name="([^"]*)"/i.exec(headers);
    if (dm && dm[1] === name) {
      const fm = /filename="([^"]*)"/i.exec(headers);
      return { content: body, filename: fm ? fm[1] : '', contentType: (/content-type:\s*([^\r\n]+)/i.exec(headers) || [])[1] || '' };
    }
    pos = next;
  }
  return null;
}

// ── /render/mermaid ────────────────────────────────────────────────────────
/** Graphviz-ish `digraph{a->b}` (what the dev render_test sends) isn't a diagram type in
 *  mermaid-cli — rewrite a single-statement digraph into `flowchart TD` edges. */
function normalizeMermaid(code) {
  const m = /^\s*digraph\s*\w*\s*\{([\s\S]*)\}\s*$/.exec(code);
  if (!m) return code;
  const edges = m[1].split(/[;\n]+/).map(s => s.trim()).filter(Boolean)
    .map(e => '  ' + e.replace(/-{1,2}>/g, '-->'));
  return edges.length ? ['flowchart TD', ...edges].join('\n') : code;
}

async function handleRender(req, res, ip) {
  const t0 = Date.now();
  let body;
  try { body = JSON.parse((await readBody(req, JSON_MAX_BYTES)).toString('utf8')); }
  catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? 'body >1MB' : 'invalid JSON body'), t0, ip, 'render', e.status || 400); }
  const code = typeof body.code === 'string' ? normalizeMermaid(body.code.trim()) : '';
  if (!code) return done(res, sendErr(res, 400, 'missing "code"'), t0, ip, 'render', 400);
  const width = Math.min(1200, Math.max(100, Number(body.width) || 460));
  // mmdc v12 outputs png/svg/pdf only — png it is (no webp).
  const key = sha256(`${code}|${width}`);
  const cachePath = path.join(CACHE_DIR, `${key}.png`);

  if (fs.existsSync(cachePath)) {
    const img = fs.readFileSync(cachePath);
    return done(res, send(res, 200, img, { 'Content-Type': 'image/png', 'X-Cache': 'hit' }), t0, ip, 'render', 200, 'hit', img.length);
  }

  const dir = fs.mkdtempSync(path.join(TMP_DIR, 'render-'));
  try {
    const inFile = path.join(dir, 'diagram.mmd');
    const outFile = path.join(dir, 'diagram.png');
    await fsp.writeFile(inFile, code + '\n');
    const r = await runChild('npx', ['-y', '@mermaid-js/mermaid-cli', '-i', inFile, '-o', outFile,
      '--size', String(width), '-s', '2', '-p', PUPPETEER_CFG],
      { cwd: dir, env: CHILD_ENV, timeout: RENDER_TIMEOUT_MS });
    if (r.code !== 0 || !fs.existsSync(outFile)) {
      const excerpt = (r.err || 'mmdc produced no output').slice(-1200);
      const status = r.killed ? 504 : 400; // timeout vs mermaid parse/render error
      return done(res, sendErr(res, status, r.killed ? 'render timeout (60s)' : excerpt), t0, ip, 'render', status, 'miss');
    }
    await fsp.rename(outFile, cachePath); // same fs (.cache) — atomic
    const img = await fsp.readFile(cachePath);
    return done(res, send(res, 200, img, { 'Content-Type': 'image/png', 'X-Cache': 'miss' }), t0, ip, 'render', 200, 'miss', img.length);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ── /img ─────────────────────────────────────────────────────────────────────
// Allowlisted image scaler (F6): the gateway emits `![desc](/abs/path.png)` lines and the
// watch fetches them here. img_roots + extension whitelist are the security boundary — the
// bearer token alone must not turn this daemon into an arbitrary-file reader.
const IMG_MAX_INPUT_BYTES = 15 * 1024 * 1024;
const IMG_EXT = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp']);
// Full-res passthrough (owner batch 2: "images should be in full res to the watch; scaling should
// happen on the watch"): originals are byte-identical only for these types — gif/bmp fall back to
// the legacy scaler (animation/mode conversion genuinely needs Pillow).
const IMG_FULL_MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp' };
const IMG_SCALER = path.join(WK_DIR, 'img_scale.py');
const IMG_ROOTS = (Array.isArray(cfg.img_roots) && cfg.img_roots.length ? cfg.img_roots
  : [path.join(os.homedir(), '.hermes'), path.join(os.homedir(), 'dev'), '/tmp', '/var/tmp'])
  .map(r => { try { return fs.realpathSync(path.resolve(String(r))); } catch { return null; } })
  .filter(Boolean);
const underRoots = (p) => IMG_ROOTS.some(root => p === root || p.startsWith(root + path.sep));

async function handleImg(req, res, ip, params) {
  const t0 = Date.now();
  const p = typeof params.path === 'string' ? params.path.trim() : '';
  if (!p) return done(res, sendErr(res, 400, 'missing "path"'), t0, ip, 'img', 400);
  // width OPTIONAL (owner batch 2): missing/empty/0 ⇒ full-res original bytes for png/jpeg/webp.
  // Any width>0 (and non-empty junk → 460) runs the legacy scaler EXACTLY as before.
  const fullMode = Number(params.width ?? 0) === 0;
  const width = Math.min(1200, Math.max(100, Number(params.width) || 460));
  const resolved = path.resolve(p);
  // Containment BEFORE existence: outside-roots is always 403, never a "file exists?" probe.
  if (!underRoots(resolved)) return done(res, sendErr(res, 403, 'path outside img_roots'), t0, ip, 'img', 403);
  let real;
  try { real = fs.realpathSync(resolved); }
  catch (e) {
    const notFound = e.code === 'ENOENT';
    return done(res, sendErr(res, notFound ? 404 : 400, notFound ? 'not found' : 'cannot resolve path'), t0, ip, 'img', notFound ? 404 : 400);
  }
  if (!underRoots(real)) return done(res, sendErr(res, 403, 'symlink escapes img_roots'), t0, ip, 'img', 403);
  if (!IMG_EXT.has(path.extname(real).toLowerCase()))
    return done(res, sendErr(res, 415, 'extension not allowed (png/jpg/jpeg/webp/gif/bmp)'), t0, ip, 'img', 415);
  const st = fs.statSync(real); // race with realpath is fine: worst case a later read 404s via scaler
  if (!st.isFile()) return done(res, sendErr(res, 400, 'not a file'), t0, ip, 'img', 400);
  if (st.size > IMG_MAX_INPUT_BYTES)
    return done(res, sendErr(res, 413, `image >${Math.round(IMG_MAX_INPUT_BYTES / 1048576)}MB`), t0, ip, 'img', 413);

  if (fullMode) {
    const ext = path.extname(real).toLowerCase();
    const mime = IMG_FULL_MIME[ext];
    if (mime) {
      // Serve the source file VERBATIM (byte-identical to disk — no Pillow, no WebP re-encode).
      // Key parity with the scaler cache: realpath|mtime|width where width=full.
      try {
        const img = fs.readFileSync(real);
        const key = sha256(`${real}|${st.mtimeMs}|full`);
        return done(res, send(res, 200, img,
          { 'Content-Type': mime, 'X-Cache': `raw-${key.slice(0, 12)}` }), t0, ip, 'img', 200, 'raw', img.length);
      } catch (e) {
        return done(res, sendErr(res, 500, `read failed: ${e.message}`.slice(0, 200)), t0, ip, 'img', 500);
      }
    }
    // gif/bmp asked for full but genuinely need the scaler → legacy 460 below (documented).
  }

  const key = sha256(`${real}|${st.mtimeMs}|${width}`);
  const cachePath = path.join(CACHE_DIR, `${key}.webp`);
  if (fs.existsSync(cachePath)) {
    let note = null;
    try { note = fs.readFileSync(`${cachePath}.note`, 'utf8') || null; } catch { /* no note — plain image */ }
    const img = fs.readFileSync(cachePath);
    return done(res, send(res, 200, img,
      { 'Content-Type': 'image/webp', 'X-Cache': 'hit', ...(note ? { 'X-Note': note } : {}) }), t0, ip, 'img', 200, 'hit', img.length);
  }

  if (!fs.existsSync(VENV_PY) || !fs.existsSync(IMG_SCALER))
    return done(res, sendErr(res, 503, 'img scaler missing — run scripts/whisper-env.sh'), t0, ip, 'img', 503);
  const tmp = path.join(TMP_DIR, `img-${crypto.randomBytes(6).toString('hex')}.webp`);
  try {
    // Single-threaded by design: watch images are occasional and Pillow scales in ms.
    const r = await runChild(VENV_PY, [IMG_SCALER, real, tmp, String(width)], { cwd: WK_DIR, env: CHILD_ENV, timeout: IMG_TIMEOUT_MS });
    if (r.killed) return done(res, sendErr(res, 504, 'scale timeout (30s)'), t0, ip, 'img', 504);
    if (r.code !== 0) {
      const brace = r.out.indexOf('{'); // python exit 3 = undecodable file / pixel bomb → 415
      const status = r.code === 3 ? 415 : 500;
      const msg = (brace >= 0 ? r.out.slice(brace) : (r.err || 'scale failed')).slice(-1200);
      return done(res, sendErr(res, status, msg), t0, ip, 'img', status, 'miss');
    }
    const brace = r.out.indexOf('{');
    const meta = brace === -1 ? {} : JSON.parse(r.out.slice(brace));
    if (meta.note) fs.writeFileSync(`${cachePath}.note`, meta.note);
    await fsp.rename(tmp, cachePath); // same fs (.cache) — atomic
    const img = await fsp.readFile(cachePath);
    return done(res, send(res, 200, img,
      { 'Content-Type': 'image/webp', 'X-Cache': 'miss', ...(meta.note ? { 'X-Note': meta.note } : {}) }), t0, ip, 'img', 200, 'miss', img.length);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// ── /stt/whisper ───────────────────────────────────────────────────────────
async function handleStt(req, res, ip) {
  const t0 = Date.now();
  if (!fs.existsSync(VENV_PY)) return done(res, sendErr(res, 503, 'whisper venv missing — run scripts/whisper-env.sh'), t0, ip, 'stt', 503);
  let buf;
  try { buf = await readBody(req, AUDIO_MAX_BYTES); }
  catch (e) { return done(res, sendErr(res, 413, 'audio >10MB'), t0, ip, 'stt', 413); }
  const file = extractMultipartFile(buf, req.headers['content-type'], 'audio');
  if (!file || file.content.length === 0) return done(res, sendErr(res, 400, 'missing multipart field "audio"'), t0, ip, 'stt', 400);
  const ext = (path.extname(file.filename || '') || (file.contentType.includes('webm') ? '.webm' : '.wav')).slice(0, 8).replace(/[^.a-z0-9]/gi, '');
  const tmp = path.join(TMP_DIR, `stt-${crypto.randomBytes(6).toString('hex')}${ext}`);
  try {
    await fsp.writeFile(tmp, file.content);
    // COLD START by design in v1: python spins up + loads "base" each call (~2-4s). TODO: warm worker.
    const r = await runChild(VENV_PY, [TRANSCRIBER, tmp], { cwd: WK_DIR, env: CHILD_ENV, timeout: STT_TIMEOUT_MS });
    if (r.killed) return done(res, sendErr(res, 504, 'transcribe timeout (55s)'), t0, ip, 'stt', 504);
    if (r.code !== 0) return done(res, sendErr(res, 500, (r.err || 'transcribe failed').slice(-1200)), t0, ip, 'stt', 500);
    const brace = r.out.indexOf('{');
    if (brace === -1) return done(res, sendErr(res, 500, 'no JSON from transcriber'), t0, ip, 'stt', 500);
    const text = JSON.parse(r.out.slice(brace)).text ?? '';
    return done(res, send(res, 200, JSON.stringify({ text })), t0, ip, 'stt', 200, undefined, file.content.length);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// ── /sessions (cross-profile session list for the watch's Sessions screen) ──
// Owner walkthrough: "continue my computer chats from the watch" needs DESKTOP sessions (profile
// `default`), not just the current agent's. The dashboard serves them at GET /api/sessions but its
// basic-auth is an scrypt hash (no plaintext to proxy with) — so, since watchkit is on the same
// box, read the SAME SQLite store the dashboard reads (default profile: <home>/state.db; others:
// <profiles_dir>/<p>/state.db, layout confirmed in hermes_cli/web_server_sessions.py
// _session_db_path_for_profile + hermes_state_sessions.list_sessions_rich). Response mirrors the
// gateway's `/api/sessions` shape so the app's GatewaySessions.parse handles both sources.
const HERMES_HOME = process.env.WATCHKIT_HERMES_HOME || path.dirname(PROFILES_DIR);
const SESSIONS_PROFILE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/; // same grammar as the app's ProfileLogic
const SESSIONS_LIMIT_MAX = 30;
// Epoch-seconds sanity window: a garbage double salvaged from a damaged page must not pin
// recency at 5e246 (hermes_state_common._sql_freshest_of guards the same thing in SQL).
const saneEpochSec = (v) => { const n = Number(v); return Number.isFinite(n) && n > 1e9 && n < 4e9 ? n : 0; };

function sessionDbPath(profile) {
  // `default` is the multiplexed home profile: its store is <home>/state.db, NOT profiles/default/.
  if (profile === 'default') return path.join(HERMES_HOME, 'state.db');
  const dir = path.resolve(PROFILES_DIR, profile);
  if (!dir.startsWith(path.resolve(PROFILES_DIR) + path.sep)) return null; // traversal guard, belt+suspenders
  return path.join(dir, 'state.db');
}

async function handleSessions(req, res, ip, params) {
  const t0 = Date.now();
  const profile = String(params.profile ?? 'default').trim() || 'default';
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile name'), t0, ip, 'sessions', 400);
  const limit = Math.min(SESSIONS_LIMIT_MAX, Math.max(1, Number(params.limit) || 20));
  const dbPath = sessionDbPath(profile);
  if (!dbPath || !fs.existsSync(dbPath)) {
    // Fresh/never-used profile: an empty list beats an error — the chip still renders.
    const empty = JSON.stringify({ object: 'list', data: [], profile, limit, offset: 0, has_more: false });
    return done(res, send(res, 200, empty), t0, ip, 'sessions', 200, undefined, 0);
  }
  let db;
  try {
    // Lazy import: node:sqlite needs node ≥22.5; older daemons degrade this ONE endpoint (503)
    // instead of failing to boot — /health /agents /img /render /stt all keep working.
    const { DatabaseSync } = await import('node:sqlite');
    db = new DatabaseSync(dbPath, { readOnly: true });
    // Desktop-sidebar semantics (web_routers/sessions.get_sessions defaults): archived + hidden out,
    // cron/tool/subagent noise out, compression continuations folded behind their root (the root's
    // own recency is chain-maxed via message timestamps below).
    const rows = db.prepare(`
      SELECT s.id AS id, s.title AS title, s.message_count AS message_count,
             s.started_at AS started_at, s.last_activity_at AS last_activity_at,
             (SELECT MAX(m.timestamp) FROM messages m WHERE m.session_id = s.id) AS msg_ts
      FROM sessions s
      WHERE s.hidden = 0 AND s.archived = 0
        AND COALESCE(s.source, '') NOT IN ('cron', 'tool', 'subagent')
        AND NOT (s.parent_session_id IS NOT NULL AND EXISTS (
          SELECT 1 FROM sessions p WHERE p.id = s.parent_session_id AND p.end_reason = 'compression'
            AND json_extract(COALESCE(s.model_config, '{}'), '$._branched_from') IS NULL
            AND json_extract(COALESCE(s.model_config, '{}'), '$._delegate_from') IS NULL))
      ORDER BY COALESCE(s.last_activity_at, s.started_at) DESC
      LIMIT ?
    `).all(limit);
    const data = rows.map((r) => ({
      id: String(r.id),
      title: String(r.title ?? ''),
      last_active: Math.max(saneEpochSec(r.msg_ts), saneEpochSec(r.last_activity_at), saneEpochSec(r.started_at)),
      message_count: Number(r.message_count) || 0,
      profile,
    })).sort((a, b) => b.last_active - a.last_active);
    const body = JSON.stringify({ object: 'list', data, profile, limit, offset: 0, has_more: false });
    return done(res, send(res, 200, body), t0, ip, 'sessions', 200, undefined, data.length);
  } catch (e) {
    // Busy/corrupt store is unavailable, not empty (same 503 posture the dashboard takes).
    return done(res, sendErr(res, 503, `sessions store unavailable: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'sessions', 503);
  } finally {
    try { db?.close(); } catch { /* already gone */ }
  }
}

// ── /session_messages (history backfill on adoption) ────────────────────────
// Owner bug: adopting a `default`-profile session from the watch's Sessions screen set the
// session id but opened EMPTY — the transcript lives only in the profile's state.db. Same
// read-the-store-directly approach as /sessions above (see its header for why). The app's
// SessionHistory parser owns the mapping to chat rows; this endpoint just hands over the
// CLEANED transcript: user/assistant turns only, active (un-compacted) rows, non-empty
// content, compression-summary rows kept as role "summary", process/system display_kinds
// dropped. NEWEST [limit] rows, returned oldest-first (a 300-msg chat opens on its tail).
const MSGS_LIMIT_MAX = 120;
const MSGS_CONTENT_MAX = 2000; // a watch can't render a 50 KB wall — cap each message server-side
const SESSION_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/;

async function handleSessionMessages(req, res, ip, params) {
  const t0 = Date.now();
  const profile = String(params.profile ?? 'default').trim() || 'default';
  const sessionId = String(params.session_id ?? '').trim();
  const limit = Math.min(MSGS_LIMIT_MAX, Math.max(1, Number(params.limit) || MSGS_LIMIT_MAX));
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile name'), t0, ip, 'session_messages', 400);
  if (!SESSION_ID_RE.test(sessionId))
    return done(res, sendErr(res, 400, 'bad session_id'), t0, ip, 'session_messages', 400);
  const dbPath = sessionDbPath(profile);
  const bodyEmpty = JSON.stringify({ messages: [], session_id: sessionId, profile, count: 0 });
  if (!dbPath || !fs.existsSync(dbPath)) // fresh profile: empty is the honest answer, not a 404
    return done(res, send(res, 200, bodyEmpty), t0, ip, 'session_messages', 200, undefined, 0);
  let db;
  try {
    const { DatabaseSync } = await import('node:sqlite'); // lazy import — same 503 posture as /sessions
    db = new DatabaseSync(dbPath, { readOnly: true });
    // display_order is the gateway's stable transcript order; its earliest rows (and the odd
    // summary row) predate the column, so id is the fallback — both ascend monotonically.
    const rows = db.prepare(`
      SELECT role, content, timestamp, _compressed_summary AS summ
      FROM messages
      WHERE session_id = ? AND role IN ('user', 'assistant') AND active = 1
        AND COALESCE(display_kind, '') NOT IN ('hidden', 'process_complete', 'internal_notification')
        AND ((content IS NOT NULL AND content <> '') OR _compressed_summary = '1')
      ORDER BY COALESCE(display_order, id) DESC
      LIMIT ?
    `).all(sessionId, limit);
    rows.reverse(); // newest-window → oldest-first reading order
    const messages = rows.map((r) => {
      const text = String(r.content ?? '').trim();
      return {
        role: String(r.summ) === '1' ? 'summary' : String(r.role),
        content: text.length > MSGS_CONTENT_MAX ? text.slice(0, MSGS_CONTENT_MAX) + '…' : text,
        ts: saneEpochSec(r.timestamp),
      };
    }).filter((m) => m.content.length > 0);
    const body = JSON.stringify({ messages, session_id: sessionId, profile, count: messages.length });
    return done(res, send(res, 200, body), t0, ip, 'session_messages', 200, undefined, body.length);
  } catch (e) {
    return done(res, sendErr(res, 503, `messages store unavailable: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'session_messages', 503);
  } finally {
    try { db?.close(); } catch { /* already gone */ }
  }
}

/** Log one structured line per request; `res` kept as first arg so call sites read uniformly. */
function done(_res, _void, t0, ip, ep, status, cache, bytes) {
  logLine({ ev: 'req', ip, ep, status, ms: Date.now() - t0, ...(cache ? { cache } : {}), ...(bytes ? { bytes } : {}) });
}

// ── server ─────────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  const ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  const url = (req.url || '/').split('?')[0];
  try {
    if (req.method === 'GET' && url === '/health') {
      logLine({ ev: 'req', ip, ep: 'health', status: 200, ms: 0 });
      return send(res, 200, JSON.stringify({ status: 'ok', version: VERSION, uptime_s: Math.round((Date.now() - START_MS) / 1000) }));
    }
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || !timingSafeEqual(auth.slice(7), TOKEN)) {
      return done(res, sendErr(res, 401, 'unauthorized'), Date.now(), ip, url.slice(1), 401);
    }
    // ── body tools (W1-W8): snapshot ingest + latest + per-tool answers ──────
    if (req.method === 'POST' && url === '/health/snapshot') {
      const t0 = Date.now();
      let body;
      try { body = JSON.parse((await readBody(req, SNAPSHOT_MAX_BYTES)).toString('utf8')); }
      catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? `body >${SNAPSHOT_MAX_BYTES / 1024}KB` : 'invalid JSON body'), t0, ip, 'snapshot', e.status || 400); }
      if (!body || typeof body !== 'object' || Array.isArray(body))
        return done(res, sendErr(res, 400, 'snapshot must be a JSON object'), t0, ip, 'snapshot', 400);
      const stored = { ...body, received_at: new Date().toISOString(), from: ip };
      await writeJsonAtomic(SNAPSHOT_PATH, stored);
      return done(res, send(res, 200, JSON.stringify({ ok: true, stored_ts: stored.received_at })),
        t0, ip, 'snapshot', 200, undefined, JSON.stringify(stored).length);
    }
    if (req.method === 'GET' && url === '/health/latest') {
      const snap = readSnapshot();
      if (!snap) return done(res, sendErr(res, 404, 'no snapshot yet'), Date.now(), ip, 'latest', 404);
      return done(res, send(res, 200, JSON.stringify(snap)), Date.now(), ip, 'latest', 200);
    }
    const mTool = /^\/health\/tool\/([a-z0-9_]+)$/.exec(url);
    if (req.method === 'GET' && mTool) {
      if (!TOOL_NAMES.includes(mTool[1])) return done(res, sendErr(res, 404, 'unknown tool'), Date.now(), ip, mTool[1], 404);
      const result = toolResult(mTool[1], readSnapshot());
      return done(res, send(res, 200, JSON.stringify(result)), Date.now(), ip, mTool[1], 200);
    }
    // Agent switcher (watch bottom bar): gateway profiles. Bearer-auth like everything else.
    if (req.method === 'GET' && url === '/agents') {
      const t0 = Date.now();
      const agents = listAgents();
      return done(res, send(res, 200, JSON.stringify({ agents })), t0, ip, 'agents', 200, undefined, agents.length);
    }
    // Cross-profile sessions for the watch's Sessions screen (owner: continue computer chats).
    if (req.method === 'GET' && url === '/sessions') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleSessions(req, res, ip, q);
    }
    // Transcript backfill for adopted sessions (owner: computer sessions opened empty on adopt).
    if (req.method === 'GET' && url === '/session_messages') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleSessionMessages(req, res, ip, q);
    }
    if (req.method === 'GET' && url === '/img') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleImg(req, res, ip, q);
    }
    if (req.method === 'POST' && url === '/img') {
      let body;
      try { body = JSON.parse((await readBody(req, JSON_MAX_BYTES)).toString('utf8')); }
      catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? 'body >1MB' : 'invalid JSON body'), Date.now(), ip, 'img', e.status || 400); }
      return await handleImg(req, res, ip, body && typeof body === 'object' ? body : {});
    }
    if (req.method === 'POST' && url === '/render/mermaid') return await handleRender(req, res, ip);
    if (req.method === 'POST' && url === '/stt/whisper') return await handleStt(req, res, ip);
    return done(res, sendErr(res, 404, 'not found'), Date.now(), ip, url.slice(1), 404);
  } catch (e) {
    logLine({ ev: 'req', ip, ep: url, status: 500, ms: 0, err: String(e?.message || e) });
    if (!res.headersSent) sendErr(res, 500, 'internal');
  }
});

// ── MCP stdio server (SPEC W8: `node watchkit.mjs mcp` for the Hermes wear profile) ──
// Newline-delimited JSON-RPC 2.0 over stdin/stdout. Zero deps. Reads the SAME snapshot store
// the watch writes (via loopback HTTP so the daemon stays the single writer of truth).
// env: WATCHKIT_URL (default http://127.0.0.1:<port>), WATCHKIT_TOKEN (falls back to config).
// MCP_MODE itself is defined with the config (logLine needs it early).
const MCP_TOOLS = [
  { name: 'watch_heart_rate', description: 'Latest heart rate (bpm) from the owner\'s Galaxy Watch, pushed within the last 2 hours. Read-only body data; only available if the owner enabled the Heart rate tool.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_steps', description: 'Steps today (daily total) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_sleep', description: 'Last sleep session (start/end epoch ms + duration_hours) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_spo2', description: 'Latest blood-oxygen saturation (%SpO2) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_location', description: 'Where the owner\'s Galaxy Watch is right now: lat/lng + accuracy_m + age_s from the watch\'s last location fix (fresh within 30 minutes; only if the owner enabled the Location tool). Answer "where is my watch" style questions with this.', inputSchema: { type: 'object', properties: {} } },
];

async function fetchToolResult(name) {
  const base = (process.env.WATCHKIT_URL || `http://127.0.0.1:${PORT}`).replace(/\/+$/, '');
  try {
    const r = await fetch(`${base}/health/tool/${encodeURIComponent(name)}`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
      signal: AbortSignal.timeout(8000),
    });
    const text = await r.text();
    try { return JSON.parse(text); }
    catch { return { tool: name, ok: false, reason: `watchkit http ${r.status}: ${text.slice(0, 120)}` }; }
  } catch (e) {
    return { tool: name, ok: false, reason: `watchkit unreachable at ${base}: ${e.message}` };
  }
}

const mcpSend = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');
const mcpResult = (id, result) => mcpSend({ jsonrpc: '2.0', id, result });
const mcpError = (id, code, message) => mcpSend({ jsonrpc: '2.0', id, error: { code, message } });

async function mcpHandle(msg) {
  if (!msg || typeof msg !== 'object') return mcpError(null, -32600, 'invalid request');
  const { id, method, params } = msg;
  const isNotification = id === undefined || id === null; // JSON-RPC notifications get no response
  switch (method) {
    case 'initialize':
      return mcpResult(id, {
        protocolVersion: (params && params.protocolVersion) || '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'hermes-watchkit', version: VERSION },
      });
    case 'ping':
      return mcpResult(id, {});
    case 'tools/list':
      return mcpResult(id, { tools: MCP_TOOLS });
    case 'tools/call': {
      const name = params && params.name;
      if (!TOOL_NAMES.includes(name)) {
        return mcpResult(id, {
          content: [{ type: 'text', text: JSON.stringify({ tool: name ?? null, ok: false, reason: 'unknown tool' }) }],
          isError: true,
        });
      }
      const result = await fetchToolResult(name);
      return mcpResult(id, { content: [{ type: 'text', text: JSON.stringify(result) }], isError: result.ok !== true });
    }
    default:
      if (isNotification) return; // e.g. notifications/initialized — acknowledge by silence
      return mcpError(id, -32601, `method not found: ${method}`);
  }
}

function runMcp() {
  logLine({ ev: 'mcp-start', version: VERSION, pid: process.pid });
  let buf = '';
  let stdinEnded = false;
  let pending = 0;
  const maybeExit = () => { if (stdinEnded && pending === 0) process.exit(0); };
  const handle = (msg) => {
    pending++;
    Promise.resolve(mcpHandle(msg)).catch((e) => mcpError(msg?.id ?? null, -32603, String(e?.message || e)))
      .finally(() => { pending--; setImmediate(maybeExit); });
  };
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buf += chunk;
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { mcpError(null, -32700, 'parse error'); continue; }
      if (Array.isArray(msg)) msg.forEach((m) => handle(m));
      else handle(msg);
    }
  });
  process.stdin.on('end', () => { stdinEnded = true; maybeExit(); }); // supervisor closed the pipe
}

if (MCP_MODE) {
  runMcp();
} else {
  server.listen(PORT, HOST, () => {
    logLine({ ev: 'start', version: VERSION, host: HOST, port: PORT, pid: process.pid });
  });
  for (const s of ['SIGTERM', 'SIGINT']) process.on(s, () => { logLine({ ev: 'stop', sig: s }); server.close(() => process.exit(0)); });
}
