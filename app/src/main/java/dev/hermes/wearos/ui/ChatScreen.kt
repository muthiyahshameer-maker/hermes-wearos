package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.ChatAnchor
import dev.hermes.wearos.domain.ApprovalInfo
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.ChatUiState
import dev.hermes.wearos.ui.md.MdMessage

@Composable
fun ChatScreen(
    state: ChatUiState,
    onApprove: (String) -> Unit,
    voiceEnabled: Boolean,
    onToggleVoice: () -> Unit,
    voiceStatus: String = "",
    chatLabel: String = "",
    onOpenChats: () -> Unit = {},
    markdown: Boolean = true,
    // OWNER BATCH 2 (edit old prompts): long-press on a USER message opens the action sheet.
    onEditPrompt: (ChatMessage) -> Unit = {},
    // LAYOUT D: transcript bottom clearance = the bottom-cluster's top edge (px→dp by the caller)
    // so the LAST message can rest clear of the orb cluster; null keeps the chin-based default.
    contentPadBottomDp: Dp? = null,
    // Hoisted from MainActivity's ChatBody so the scroll SURVIVES the PTT overlay (if/else pair
    // leaves composition while listening) — walkthrough: "after stt talk button done, it scrolls
    // to the top of convo instead of staying at bottom".
    listState: ScalingLazyListState = rememberScalingLazyListState(),
) {
    // PINCH FONT-SCALE: every type change re-runs the bottom anchor (text height changes shift
    // the tail) — same scroll machinery, toast-free.
    val typeScale = LocalFontScale.current
    // BOTTOM ANCHOR (walkthrough): send (voice OR keyboard) grows messages; stream deltas grow
    // `live`; STOP adds the marker row; approval appends a card; agent switch / chat open change
    // activeChatId; overlay dismiss re-enters composition (effect re-runs). Every path → bottom.
    LaunchedEffect(
        state.activeChatId, state.messages.size, state.live.length,
        state.busy, state.approval?.requestId, state.status, typeScale,
    ) {
        val idx = ChatAnchor.bottomIndex(
            messageCount = state.messages.size,
            hasApproval = state.approval != null,
            hasLive = state.live.isNotEmpty(),
            hasStatus = state.status.isNotEmpty(),
            showEmptyHint = ChatAnchor.showEmptyHint(state.messages.size, state.live, state.approval != null),
        )
        if (idx > 0) {
            // THE anchor (owner walkthrough): latest row must be on screen at the list's end.
            // wear-compose 1.4.1 ScalingLazyColumn facts measured on-device: scrollToItem(last, 0)
            // lands short (lazy composition hasn't laid the tail out yet), and the true list end
            // = LAST item resting at the Middle anchor (auto-centering padding fills below it).
            // So: push with a viewport-sized offset (clamps to the end) and retry per frame while
            // the list still reports forward scroll available (bounded — never spins forever).
            val target = idx.coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
            var tries = 0
            while (listState.canScrollForward && tries < 8) {
                // Oversized offset → clamps to the true list end (see comment above).
                listState.scrollToItem(target, 4096)
                withFrameNanos { }
                tries++
            }
            if (tries > 1) android.util.Log.d("HermesWear", "anchor idx=$idx tries=$tries (lazy tail)")
        }
    }
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        // Round discipline (ask #5): first item rests below the clock, last above the orb bar,
        // horizontal gutter tops rows' own padding up to the safe circle; SLC scaling does falloff.
        // LAYOUT D: the chat passes the measured cluster top edge as the bottom clearance.
        contentPadding = roundListPadding(bottomExtraDp = 6f, bottomOverrideDp = contentPadBottomDp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            ChatHeader(voiceEnabled, onToggleVoice, voiceStatus, chatLabel, onOpenChats)
        }
        if (state.messages.isEmpty() && state.live.isEmpty() && state.approval == null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                        .clickable(onClick = onOpenChats),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ask Hermes", style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
                    Text("press the mic below", style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f))
                }
            }
        }
        items(state.messages, key = { it.id }) { msg -> MessageLine(msg, markdown, onEditPrompt) }
        if (state.approval != null) {
            item { ApprovalCard(state.approval, onApprove) }
        }
        if (state.live.isNotEmpty()) {
            item {
                val live = state.live + (if (state.busy) " ▉" else "")
                if (markdown) {
                    // Partial markdown parsed every delta: unclosed fence → code block, unfinished
                    // markers stay literal, so the buffer never shows a backtick storm (F4).
                    MdMessage(live, msgKey = "live")
                } else Text(
                    live,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.body2.scaled(),
                )
            }
        }
        if (state.status.isNotEmpty()) {
            item {
                Text(
                    state.status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.caption2.scaled(),
                    color = Color(0xFFF5C518),
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(
    enabled: Boolean,
    onToggle: () -> Unit,
    status: String,
    chatLabel: String,
    onOpenChats: () -> Unit,
) {
    // The header is item 0 of a Middle-anchored SLC: contentPadding can't keep it below the
    // clock when content is short, so the header carries the top inset itself (round ask #5).
    val insets = roundInsets()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = insets.topDp.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (enabled) "🔊" else "🔇", style = MaterialTheme.typography.body2)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onOpenChats),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                chatLabel.ifEmpty { "💬" },
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.caption3,
                color = Color(0xFFF5C518),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageLine(msg: ChatMessage, markdown: Boolean, onEditPrompt: (ChatMessage) -> Unit) {
    when (msg.role) {
        ChatMessage.Role.User -> {
            // OWNER BATCH 2 (edit old prompts): the user's own messages are the one thing on the
            // watch they can act on — long-press opens the action sheet ("click on my old prompts
            // and hold with an edit option like desktop has"). combinedClickable: tap does nothing
            // special (no resend-to-compose surprise), long-press is the affordance.
            Text(
                msg.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .combinedClickable(
                        onClick = { onEditPrompt(msg) },
                        onLongClick = { onEditPrompt(msg) },
                    ),
                style = MaterialTheme.typography.body2.scaled(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.primary,
            )
        }
        ChatMessage.Role.System -> Text(
            msg.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            style = MaterialTheme.typography.caption2.scaled(),
            color = Color(0xFFE57373),
        )
        ChatMessage.Role.Hermes -> if (markdown) {
            // Parse-then-render (F3/F4); user messages stay plain bold lines by design.
            MdMessage(msg.text, msgKey = msg.id)
        } else Text(
            msg.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun ApprovalCard(info: ApprovalInfo, onApprove: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠ Approve?", style = MaterialTheme.typography.body1, color = Color(0xFFF5C518))
        Text(info.question, style = MaterialTheme.typography.caption1, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val approveName = info.choices.firstOrNull { !it.contains("deny", true) && !it.equals("no", true) } ?: "approve"
            val denyName = info.choices.firstOrNull { it.contains("deny", true) || it.equals("no", true) } ?: "deny"
            Button(
                onClick = { onApprove(approveName) },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
            ) { Text("OK") }
            Button(
                onClick = { onApprove(denyName) },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7F1D1D)),
            ) { Text("No") }
        }
    }
}
