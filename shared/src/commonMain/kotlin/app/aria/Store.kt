package app.aria

class Store {
    private val path: String get() = dataDir() + "/aria-data.json"

    fun load(): AppData {
        val d = readTextFile(path)
            ?.let { runCatching { AppJson.decodeFromString<AppData>(it) }.getOrNull() }
            ?: AppData()
        val have = d.providers.map { it.id }.toSet()
        val missing = ProviderPresets.all().filter { it.id !in have }
        return if (missing.isEmpty()) d else d.copy(providers = d.providers + missing)
    }

    fun save(d: AppData) {
        writeTextFile(path, AppJson.encodeToString(d.copy(messages = d.messages.takeLast(3000))))
    }
}
