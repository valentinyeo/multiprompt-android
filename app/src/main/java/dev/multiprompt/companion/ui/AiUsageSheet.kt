package dev.multiprompt.companion.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.AiUsageState
import dev.multiprompt.companion.model.PlanUsage
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
internal fun AiUsageSheet(state: AiUsageState, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.plans) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val error = state.error
    KitSheet(onDismiss = onDismiss) {
        Text("AI usage", style = Kit.Heading, color = Kit.Text)
        when {
            state.loading -> Text("Loading usage", style = Kit.Hint, color = Kit.TextMuted)
            error != null -> Text(error, style = Kit.Hint, color = Kit.TextMuted)
            state.plans.isEmpty() -> Text("No usage yet", style = Kit.Hint, color = Kit.TextMuted)
            else -> KitSheetScrollColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.plans.forEach { plan ->
                    val view = plan.presentation(now, zone)
                    KitPlanBlock(
                        name = plan.name,
                        percent = view.percent,
                        fraction = view.fraction,
                        paceLine = view.paceLine,
                        detailLine = view.detailLine,
                    )
                }
            }
        }
    }
}
