package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.dictation.AudioLevel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay

private val DictationBarHeight = 64.dp
private const val LevelDots = 14

@Composable
internal fun DictationBar(
    level: StateFlow<Float>,
    sending: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
) {
    val dark = Color(0xFF2B2B2B)
    val onDark = Color.White
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).height(DictationBarHeight).clip(RoundedCornerShape(32.dp)).background(dark)
                .clickable(enabled = !sending, onClick = onStop)
                .semantics { contentDescription = "Stop dictation" }
                .testTag("dictation-stop"),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(24.dp).background(onDark, RoundedCornerShape(4.dp))) }
        Row(
            Modifier.weight(1.4f).height(DictationBarHeight).clip(RoundedCornerShape(32.dp)).background(dark)
                .padding(horizontal = 20.dp).testTag("dictation-meter"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DictationClock(onDark)
            LevelDotsRow(level, onDark)
        }
        Box(
            Modifier.size(DictationBarHeight).clip(CircleShape).background(Color(0xFFF2F2F2))
                .clickable(enabled = !sending, onClick = onSend)
                .semantics { contentDescription = "Send prompt" }
                .testTag("dictation-send"),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Black)
            else Icon(Icons.Default.ArrowUpward, null, Modifier.size(32.dp), tint = Color.Black)
        }
    }
}

@Composable
private fun DictationClock(color: Color) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - started
            delay(250)
        }
    }
    Text(
        AudioLevel.timer(elapsed),
        color = color,
        fontSize = 26.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.testTag("dictation-timer"),
    )
}

@Composable
private fun LevelDotsRow(level: StateFlow<Float>, color: Color) {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        while (true) {
            history.add(level.value)
            while (history.size > LevelDots) history.removeAt(0)
            delay(80)
        }
    }
    val lit = AudioLevel.litDots(history, LevelDots)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        lit.forEach { on ->
            Box(Modifier.size(if (on) 7.dp else 4.dp).background(if (on) color else color.copy(alpha = 0.45f), CircleShape))
        }
    }
}
