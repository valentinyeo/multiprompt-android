package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.dictation.KeySave
import kotlinx.coroutines.launch

@Composable
internal fun DeepgramKeySheet(
    dictation: DeepgramDictation,
    configured: Boolean,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    KitSheet(onDismiss = onDismiss) {
        Text("Deepgram dictation", style = Kit.Heading, color = Kit.Text)
        Text("The key is encrypted with Android Keystore and stays on this device.", style = Kit.Body, color = Kit.TextMuted)
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (configured) "Replacement API key" else "API key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
        )
        KitSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        KitPrimaryButton(if (checking) "Checking key" else saveLabel, onClick = {
            checking = true
            scope.launch {
                val result = dictation.saveCheckedApiKey(draft)
                checking = false
                when (result) {
                    KeySave.SAVED -> onSaved()
                    KeySave.NOT_A_KEY -> error = "This is not a Deepgram API key. Copy the 40 character key."
                    KeySave.REJECTED -> error = "Deepgram rejected this key. Copy it again from Deepgram."
                }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = draft.isNotBlank() && !checking)
    }
}
