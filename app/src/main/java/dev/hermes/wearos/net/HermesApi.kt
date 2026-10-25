package dev.hermes.wearos.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hermes API server (runs surface) over the tailnet. Wire contract verified against
 * Hermes Agent 0.21.7 (gateway/platforms/api_server_runs.py):
 *  - POST /v1/runs {input, session_id} + `Idempotency-Key` header → {run_id, status, replayed}
 *  - GET  /v1/runs/{id}/events → SSE frames `id: <seq>` + `data: {"event": ..., ...}`
 *  - events: message.delta {delta}, message.interim {text, already_streamed},
 *    tool.started {tool, preview}, tool.completed, approval.request {command?, choices?},
 *    run.completed {output,...}, run.failed, run.cancelled
 *  - POST /v1/runs/{id}/approval {choice, request_id?}
 *  - GET  /v1/runs/{id} → status; terminal snapshots carry `output` (resume-safe final answer)
 */
class HermesApi(
    val baseUrl: String,              // e.g. http://hermes.tailnet.ts.net:8642/p/wear (failover winner + /p/agent)
    private val token: String,
    private val client: OkHttpClient = DEFAULT,
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val Json_ = Json { ignoreUnknownKeys = true }
        val DEFAULT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE: rely on server keepalive
            .build()

        fun parse(data: String): JsonObject = Json_.parseToJsonElement(data).jsonObject
        fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun JsonObject.choiceNames(): List<String> =
            ((this["choices"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.str("id") ?: (el as? JsonPrimitive)?.contentOrNull
            })?.takeIf { it.isNotEmpty() } ?: listOf("approve", "deny")
    }

    private fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url)
            .method(if (body == null) "GET" else "POST", body?.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $token")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

    data class RunAccepted(val runId: String, val status: String, val replayed: Boolean)

    /** Durably idempotent: retrying with the same [idempotencyKey] never starts a second run. */
    fun submitRun(input: String, sessionId: String, idempotencyKey: String): RunAccepted {
        val body = "{\"input\":${esc(input)},\"session_id\":${esc(sessionId)},\"model\":\"hermes-agent\"}"
        client.newCall(request("$baseUrl/v1/runs", body, mapOf("Idempotency-Key" to idempotencyKey))).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run submit ${r.code}: ${text.take(160)}")
            val obj = parse(text)
            return RunAccepted(obj.str("run_id") ?: throw ApiException(r.code, "no run_id"), obj.str("status") ?: "started", obj.str("replayed")?.toBoolean() ?: false)
        }
    }

    data class RunSnapshot(val status: String, val output: String?, val approval: JsonObject?)

    /** Poll run state; terminal snapshots carry the final `output`. */
    fun runStatus(runId: String): RunSnapshot {
        client.newCall(request("$baseUrl/v1/runs/$runId")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run status ${r.code}")
            val obj = parse(text)
            return RunSnapshot(obj.str("status") ?: "unknown", obj.str("output"), obj["approval"] as? JsonObject)
        }
    }

    fun eventsUrl(runId: String) = "$baseUrl/v1/runs/$runId/events"
    fun authHeader(): Pair<String, String> = "Authorization" to "Bearer $token"

    /**
     * Gateway health probe (Settings "Test"). Verified: GET /v1/health → 200
     * `{status:"ok", platform:"hermes-agent", version:"0.21.7"}`. Throws on any failure.
     */
    fun health(): String {
        client.newCall(request("$baseUrl/v1/health")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "health ${r.code}: ${text.take(120)}")
            val obj = try { parse(text) } catch (e: Exception) { return "ok (unparsed: ${text.take(60)})" }
            return "${obj.str("status") ?: "ok"} · ${obj.str("platform") ?: "hermes"} v${obj.str("version") ?: "?"}"
        }
    }

    data class SessionInfo(val id: String, val title: String, val lastActiveMs: Long = 0L, val messages: Int = 0)

    /**
     * Gateway session list for label sync + the sessions fast-path (owner ask #2). Verified: GET
     * /api/sessions → 200 `{object:"list", data:[{id, title, last_active, message_count, ...}]}`
     * (sessions are keyed by the same id we submit as session_id). /v1/sessions and
     * /api/sessions/list were 404.
     */
    fun listSessions(): List<SessionInfo> =
        GatewaySessions.parse(sessionsJson()).map { SessionInfo(it.id, it.title, it.lastActiveMs, it.messages) }

    /** Raw `/api/sessions` body — the sessions screen caches this text for offline reads. */
    fun sessionsJson(): String {
        client.newCall(request("$baseUrl/api/sessions")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "sessions ${r.code}")
            return text
        }
    }

    /**
     * Server-side run cancel (ask #3). Route table verified in
     * gateway/platforms/api_server_runs.py `_http_routes`: POST /v1/runs/{run_id}/stop →
     * `{run_id, status:"stopping"}` (`run_stop` capability advertised by /v1/capabilities).
     * Best-effort from the watch: the client-side stop already detached the stream either way.
     */
    fun stopRun(runId: String): String {
        client.newCall(request("$baseUrl/v1/runs/$runId/stop", "{}")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run stop ${r.code}: ${text.take(120)}")
            return text.take(120)
        }
    }

    fun respondApproval(runId: String, choice: String, requestId: String?) {
        val reqId = if (requestId != null) ",\"request_id\":${esc(requestId)}" else ""
        val body = "{\"choice\":${esc(choice)}$reqId}"
        client.newCall(request("$baseUrl/v1/runs/$runId/approval", body)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "approval ${r.code}: ${r.body?.string()?.take(120)}")
        }
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
