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
    ): String? = menuRuntimeDetails(settings, runtime, pendingFamily, provider).label

    fun menuRuntimeDetails(
        settings: HostSettings?,
        runtime: TmuxText.RuntimeDetails,
        pendingFamily: String?,
        provider: String = "claude",
    ): TmuxText.RuntimeDetails {
        val effort = runtime.effort?.trim()?.lowercase()?.ifEmpty { null }
            ?: settings?.effort?.takeIf { it.isNotEmpty() }?.trim()?.lowercase()
        val model = when {
            pendingFamily != null -> {
                val display = HostChange.entries.firstOrNull {
                    it.provider == provider && it.key == "model" && it.value == pendingFamily
                }?.label ?: pendingFamily.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                runtime.model?.let { swapModelFamily(it, pendingFamily, display) } ?: display
            }
            runtime.model != null -> runtime.model
            else -> settings?.model?.takeIf { it.isNotEmpty() }?.let { saved ->
                HostChange.entries.firstOrNull {
                    it.provider == provider && it.key == "model" && it.value == saved
                }?.label ?: saved.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
        return TmuxText.RuntimeDetails(model, effort)
    }

    fun switchFailureLine(error: String?): String? = error?.takeIf { it.isNotBlank() }

    fun displayRuntime(stream: TmuxText.RuntimeDetails, transcript: String): TmuxText.RuntimeDetails =
        stream.takeIf { it.model != null } ?: TmuxText.runtimeDetails(transcript)

    private fun swapModelFamily(model: String, family: String, display: String): String {
        val fromFamily = HostSettings.family(model) ?: return display
        if (fromFamily == family) return model
        return Regex("(?i)${Regex.escape(fromFamily)}").replace(model, display)
    }
}