package dev.multiprompt.companion.reader

import dev.multiprompt.companion.ssh.TmuxText

object ModelMenu {
    fun currentModelChange(
        settings: HostSettings?,
        runtime: TmuxText.RuntimeDetails,
        pendingFamily: String? = null,
        provider: String = "claude",
    ): HostChange? {
        pendingFamily?.let { pending ->
            return HostChange.entries.firstOrNull { it.provider == provider && it.key == "model" && it.value == pending }
        }
        val family = HostSettings.family(runtime.model)
            ?: HostSettings.family(settings?.model?.takeIf { it.isNotEmpty() })
        return family?.let { value ->
            HostChange.entries.firstOrNull { it.provider == provider && it.key == "model" && it.value == value }
        }
    }

    fun modelRowLabel(
        settings: HostSettings?,
        runtime: TmuxText.RuntimeDetails,
        pendingFamily: String?,
        provider: String = "claude",
    ): String? = currentModelChange(settings, runtime, pendingFamily, provider)?.label
        ?: runtime.label?.substringBefore(" ·")?.takeIf { it.isNotBlank() }

    fun menuRuntimeLabel(
        settings: HostSettings?,
        runtime: TmuxText.RuntimeDetails,
        pendingFamily: String?,
        provider: String = "claude",
    ): String? {
        val model = currentModelChange(settings, runtime, pendingFamily, provider)?.label
        val effort = currentEffortLabel(settings, runtime)
        return when {
            model != null && effort != null -> "$model · $effort"
            model != null -> model
            else -> runtime.label
        }
    }

    fun switchFailureLine(error: String?): String? = error?.takeIf { it.isNotBlank() }

    fun displayRuntime(stream: TmuxText.RuntimeDetails, transcript: String): TmuxText.RuntimeDetails =
        stream.takeIf { it.model != null } ?: TmuxText.runtimeDetails(transcript)

    private fun currentEffortLabel(settings: HostSettings?, runtime: TmuxText.RuntimeDetails): String? {
        val effort = runtime.effort?.trim()?.lowercase()?.ifEmpty { null }
            ?: settings?.effort?.takeIf { it.isNotEmpty() }?.trim()?.lowercase()
        return effort?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}