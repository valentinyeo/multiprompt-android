package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
    Column {
        if (question.header.isNotEmpty()) Text(question.header, style = MaterialTheme.typography.labelSmall)
        Text(question.title, style = MaterialTheme.typography.titleSmall)
        question.options.forEachIndexed { index, option ->
            OutlinedButton(
                onClick = {
                    selected = if (question.multiSelect) {
                        if (index in selected) selected - index else selected + index
                    } else { other = ""; setOf(index) }
                },
                enabled = enabled && !sent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row {
                    if (question.multiSelect) Checkbox(checked = index in selected, onCheckedChange = null)
                    else RadioButton(selected = index in selected, onClick = null)
                    Column {
                        Text(option.label)
                        if (option.description.isNotEmpty()) Text(option.description)
                    }
                }
            }
        }
        if (question.other) OutlinedTextField(
            value = other,
            onValueChange = { other = it; if (!question.multiSelect) selected = emptySet() },
            label = { Text("Other") },
            enabled = enabled && !sent,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { if (onChoose(selected, other)) sent = true },
            enabled = enabled && !sent && (selected.isNotEmpty() || other.isNotBlank()),
        ) { Text("Submit answer") }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
