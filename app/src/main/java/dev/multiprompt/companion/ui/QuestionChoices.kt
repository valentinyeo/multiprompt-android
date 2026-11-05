package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.multiprompt.companion.reader.PendingQuestion

@Composable
internal fun QuestionChoices(question: PendingQuestion, enabled: Boolean, error: String? = null, onChoose: (Set<Int>, String) -> Boolean) {
    var selected by remember(question) { mutableStateOf(emptySet<Int>()) }
    var other by remember(question) { mutableStateOf("") }
    var sent by remember(question) { mutableStateOf(false) }
    LaunchedEffect(error) { if (error != null) sent = false }
    val open = enabled && !sent
    Column(verticalArrangement = Arrangement.spacedBy(Kit.ControlGap)) {
        if (question.header.isNotEmpty()) Text(question.header, style = Kit.Hint, color = Kit.TextMuted)
        Text(question.title, style = Kit.BodyStrong, color = Kit.Text)
        question.options.forEachIndexed { index, option ->
            KitAnswerOption(
                letter = "${index + 1}",
                title = option.label,
                detail = option.description,
                state = when {
                    index in selected -> KitOptionState.Picked
                    sent && !question.multiSelect -> KitOptionState.Dimmed
                    else -> KitOptionState.Idle
                },
                onClick = if (open) {
                    {
                        if (question.multiSelect) {
                            selected = if (index in selected) selected - index else selected + index
                        } else {
                            other = ""
                            selected = setOf(index)
                            if (onChoose(selected, "")) sent = true
                        }
                    }
                } else null,
            )
        }
        if (question.other) OutlinedTextField(
            value = other,
            onValueChange = { other = it; if (!question.multiSelect) selected = emptySet() },
            label = { Text("Other") },
            enabled = open,
            modifier = Modifier.fillMaxWidth(),
        )
        if (question.multiSelect || question.other) KitSecondaryButton(
            "Submit answer",
            onClick = { if (onChoose(selected, other)) sent = true },
            enabled = open && (selected.isNotEmpty() || other.isNotBlank()),
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
