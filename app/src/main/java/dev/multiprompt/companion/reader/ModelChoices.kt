package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind

data class ModelChoice(
    val label: String,
    val current: Boolean,
    val change: HostChange? = null,
    val command: String? = null,
    val pi: PiModel? = null,
)

object ModelChoices {
    const val SECTION = "Model"

    fun pi(models: List<PiModel>, current: String?): List<ModelChoice> = models.map { model ->
        ModelChoice(label = model.name, current = PiModels.selected(model, current), pi = model)
    }

    fun of(agent: AgentKind, settings: HostSettings?, pendingFamily: String? = null): List<ModelChoice> {
        val provider = HostChange.providerOf(agent) ?: return emptyList()
        val changes = HostChange.sections(settings != null, provider).firstOrNull { it.first == SECTION }?.second
            ?: return emptyList()
        val current = pendingFamily?.trim()?.lowercase()
            ?: HostSettings.family(settings?.model)
            ?: settings?.model?.trim()?.lowercase()?.ifEmpty { null }
        return changes.map { change ->
            ModelChoice(
                label = change.label,
                change = change,
                current = current != null && change.value.lowercase() == current,
                command = change.command.takeIf { settings == null },
            )
        }
    }
}
