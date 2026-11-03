package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class RoomMember(val id: String, val name: String, val color: Int)

internal data class RoomSummary(val id: String, val name: String, val members: List<RoomMember>)

internal data class RoomLine(val seq: Long, val author: RoomMember?, val text: String)

internal fun roomLines(events: List<dev.multiprompt.companion.room.RoomEvent>, members: List<RoomMember>): List<RoomLine> =
    events.map { event ->
        val author = event.memberId?.let { id -> members.firstOrNull { it.id == id } }
        RoomLine(event.seq, if (event.type == "agent_reply") author else null, event.text)
    }

internal fun parseRooms(json: String?): List<RoomSummary> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val room = array.optJSONObject(index) ?: return@mapNotNull null
            val id = room.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = room.optString("name").takeIf(String::isNotBlank) ?: id
            val members = room.optJSONArray("members")?.let { list ->
                (0 until list.length()).mapNotNull { position ->
                    val member = list.optJSONObject(position) ?: return@mapNotNull null
                    val value = member.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    RoomMember(member.optString("id"), value, member.optInt("color", 0).mod(7))
                }
            }.orEmpty()
            RoomSummary(id, name, members)
        }
    }.getOrDefault(emptyList())
}

@Composable
internal fun RoomRow(room: RoomSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp)) {
            room.members.getOrNull(1)?.let { member ->
                BotAvatar(
                    member.name,
                    28.dp,
                    Modifier.align(Alignment.BottomEnd),
                    workspaceColor = member.color,
                    color = member.color,
                )
            }
            room.members.firstOrNull()?.let { member ->
                BotAvatar(
                    member.name,
                    32.dp,
                    Modifier.align(Alignment.TopStart),
                    workspaceColor = member.color,
                    color = member.color,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                room.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                room.members.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun RoomScreen(
    room: RoomSummary,
    lines: List<RoomLine>,
    loading: Boolean,
    exhausted: Boolean,
    error: String?,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    room.members.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            room.members.forEach { member ->
                Text(
                    "@${member.name}",
                    Modifier
                        .background(botColor(member.color).copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = botColor(member.color),
                )
            }
        }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Loading messages…" else "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    KitSecondaryButton(
                        if (loading) "Loading…" else "Load older",
                        onClick = onLoadOlder,
                        enabled = !loading && !exhausted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(lines, key = { it.seq }) { line ->
                    RoomLineRow(line)
                }
            }
        }
        error?.let { message ->
            Text(
                message,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RoomLineRow(line: RoomLine) {
    val mine = line.author == null
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        line.author?.let { author ->
            Text(
                author.name,
                style = MaterialTheme.typography.labelMedium,
                color = botColor(author.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            line.text,
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (mine) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
