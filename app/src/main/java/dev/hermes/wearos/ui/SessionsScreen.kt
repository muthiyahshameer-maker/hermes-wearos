package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.domain.SessionsMeta
import dev.hermes.wearos.net.GatewaySessions

/**
 * Gateway sessions fast-path (owner ask #2 → cross-profile upgrade): "continue my computer chats
 * from the watch" — the list carries the CURRENT agent's gateway sessions AND the DESKTOP
 * (`default`) profile's, newest first, filterable by profile chips (all / agents / 💻 computer).
 * The watch's own sessions (ids `<profile>-watch…`) keep the ⌚ marker. **NEW SESSION is pinned as
 * the very first row** (owner asked twice): fresh chat for the current agent, jumps right in.
 * Tapping a foreign-profile row adopts it: agent swap + session resume (crash-safe — the swap's
 * callback toasts via Graph.runOnUi). Offline: the merged cache stays readable with a hint.
 */
@Composable
fun SessionsScreen(
    agent: String,
    sessions: List<GatewaySessions.Info>,
    meta: SessionsMeta,
    filter: String,
    onFilter: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpen: (sessionId: String, title: String, profile: String) -> Unit,
    onRefresh: () -> Unit,
) {
    val shown = GatewaySessions.filterProfile(sessions, filter)
    val chips = buildList {
        add(GatewaySessions.FILTER_ALL)
        addAll(GatewaySessions.profilesIn(sessions))
    }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = roundListPadding(bottomExtraDp = 16f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── NEW SESSION, pinned at the very top (owner asked twice) ──────────
        item {
            Button(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth().size(width = 90.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.30f)),
            ) { Text("＋ new session", style = MaterialTheme.typography.button) }
        }
        item {
            Text(
                "Sessions · ${shown.size}",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Profile filter chips: all + every profile present (💻 computer = `default`).
        if (chips.size > 1) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    chips.forEach { c ->
                        val sel = c == filter
                        Text(
                            if (c == GatewaySessions.FILTER_ALL) "all" else ProfileLogic.chipLabel(c),
                            style = MaterialTheme.typography.caption3,
                            color = if (sel) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (sel) MaterialTheme.colors.primary.copy(alpha = 0.25f)
                                    else MaterialTheme.colors.surface.copy(alpha = 0.35f),
                                )
                                .clickable(onClickLabel = "filter sessions") { onFilter(c) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (meta.stale) {
            item {
                Text(
                    "cached · ${relTime(meta.fetchedAtMs)} — pull refresh to retry",
                    style = MaterialTheme.typography.caption3,
                    color = Color(0xFFF5C518),
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (meta.stale) "no cached list yet — refresh once online" else "no sessions yet",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        items(shown, key = { it.id }) { s ->
            SessionRow(s, currentAgent = agent, onClick = { onOpen(s.id, s.title, s.profile) })
        }
        item {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().size(width = 90.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f),
                ),
            ) { Text("↻ refresh", style = MaterialTheme.typography.button) }
        }
    }
}

@Composable
private fun SessionRow(s: GatewaySessions.Info, currentAgent: String, onClick: () -> Unit) {
    val watchOwn = ProfileLogic.isWatchSession(s.id)
    val foreign = s.profile.isNotEmpty() && s.profile != currentAgent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (watchOwn) Text("⌚", style = MaterialTheme.typography.caption2)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                s.title.ifBlank { s.id },
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (watchOwn) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
            )
            Text(
                buildString {
                    if (foreign) append(ProfileLogic.chipLabel(s.profile)).append(" · ")
                    append(relTime(s.lastActiveMs)).append(" · ").append(s.messages).append(" msg · ")
                    append(s.id.take(18))
                },
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
