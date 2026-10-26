package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.multiprompt.companion.ssh.ClaudeQuestion

@Composable
internal fun QuestionChoices(question: ClaudeQuestion, enabled: Boolean, onChoose: (Int) -> Boolean) {
    var chosen by rememberSaveable(question) { mutableStateOf<Int?>(null) }
    Column {
        Text(question.title, style = MaterialTheme.typography.titleSmall)
        question.options.forEach { option ->
            val selected = chosen == option.index
            OutlinedButton(
                onClick = { if (chosen == null && onChoose(option.index)) chosen = option.index },
                enabled = enabled && chosen == null,
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContainerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    disabledContentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
            ) { Text("${option.index}: ${option.label}") }
        }
    }
}
