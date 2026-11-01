package dev.multiprompt.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class RoomMember(val name: String, val color: Int)

internal data class RoomSummary(val id: String, val name: String, val members: List<RoomMember>)

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
                    RoomMember(value, member.optInt("color", 0).mod(7))
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
