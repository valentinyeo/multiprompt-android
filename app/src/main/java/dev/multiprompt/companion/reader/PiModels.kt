package dev.multiprompt.companion.reader

import org.json.JSONObject

data class PiModel(val name: String, val provider: String, val detail: String = "") {
    val reference: String
        get() = if (provider.isEmpty()) name else "$provider/$name"
}

data class PiModelList(val models: List<PiModel> = emptyList(), val loading: Boolean = false, val error: String? = null)

object PiModels {
    const val SECTION = "Model"
    const val EMPTY = "No Pi models on this server. Tap to retry."
    const val FAILED = "Could not load Pi models. Tap to retry."
    const val SWITCHED = "Pi did not switch the model; try again"
    const val MAX_MODELS = 512
    private val PART = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,159}")

    fun request(id: Long): String {
        require(id > 0)
        return "$id\tmodels\tpi\t-\t-\t\n"
    }

    fun parse(wire: String, id: Long): List<PiModel>? {
        val lines = wire.split('\n').map { it.trimEnd('\r') }
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size < 6 || header[0] != "@mp" || header[1] != id.toString() || header[2] != "models") return null
        if (header[3] != "ok") return emptyList()
        val models = LinkedHashMap<String, PiModel>()
        for (line in lines.drop(1)) {
            if (line == TranscriptProtocol.END) break
            val fields = line.split('\t')
            if (fields.size < 2 || fields[0] != "M") continue
            val name = fields[1]
            val provider = fields.getOrElse(2) { "" }
            if (!PART.matches(name) || provider.isNotEmpty() && !PART.matches(provider)) continue
            val model = PiModel(name, provider, fields.getOrElse(3) { "" })
            if (models.size < MAX_MODELS) models.putIfAbsent(model.reference, model)
        }
        return models.values.toList()
    }

    fun line(id: Long, sid: String, model: PiModel): String =
        "{\"id\":$id,\"op\":\"set_model\",\"provider\":\"pi\",\"sid\":${JSONObject.quote(sid)}," +
            "\"provider_name\":${JSONObject.quote(model.provider)},\"model\":${JSONObject.quote(model.reference)}}"

    fun command(model: PiModel): String = "/model ${model.reference}"

    fun notice(model: PiModel): String = "Model: ${model.name}"
}
