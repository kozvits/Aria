package app.aria

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Синхронизация через WebDAV: один файл aria-sync.json в вашей папке.
 * Подходит Яндекс.Диск (https://webdav.yandex.ru/Aria/, пароль приложения), Nextcloud, ownCloud, любой WebDAV.
 */
class WebDavSync(private val cfg: SyncConfig) {
    private val folder = cfg.webDavUrl.trim().trimEnd('/')
    private val file = "$folder/aria-sync.json"

    private fun HttpRequestBuilder.auth() {
        if (cfg.user.isNotBlank()) basicAuth(cfg.user, cfg.password)
    }

    suspend fun ensureFolder() {
        val r = Llm.http.request("$folder/") {
            method = HttpMethod("MKCOL")
            auth()
        }
        // 201 — создана, 405 — уже есть. Остальное не критично, кроме отказа в доступе.
        if (r.status.value == 401 || r.status.value == 403) {
            throw LlmException("WebDAV: нет доступа (${r.status.value}). Проверьте логин и пароль приложения")
        }
    }

    suspend fun pull(): SyncSnapshot? {
        val r = Llm.http.get(file) { auth() }
        if (r.status == HttpStatusCode.NotFound) return null
        val text = r.bodyAsText()
        if (!r.status.isSuccess()) throw LlmException("WebDAV GET ${r.status.value}")
        return AppJson.decodeFromString<SyncSnapshot>(text)
    }

    suspend fun push(s: SyncSnapshot) {
        val r = Llm.http.put(file) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(s))
        }
        if (!r.status.isSuccess()) throw LlmException("WebDAV PUT ${r.status.value}")
    }
}

object SyncMerge {
    fun snapshot(d: AppData) = SyncSnapshot(
        prefs = d.prefs,
        providers = d.providers.map { it.copy(apiKey = "") },
        messages = d.messages.takeLast(2000),
        clearedAt = d.clearedAt,
    )

    /**
     * Сообщения объединяются по id, настройки и провайдеры — побеждает более свежий updatedAt.
     * Локальными остаются: текущий режим, подтверждение 18+, API-ключи, голос, параметры синхронизации.
     */
    fun merge(local: AppData, r: SyncSnapshot): AppData {
        val prefs = if (r.prefs.updatedAt > local.prefs.updatedAt) {
            r.prefs.copy(mode = local.prefs.mode, adultConfirmed = local.prefs.adultConfirmed)
        } else local.prefs

        val localIds = local.providers.map { it.id }.toSet()
        val byId = local.providers.associateBy { it.id }.toMutableMap()
        for (rp in r.providers) {
            val lp = byId[rp.id]
            byId[rp.id] = when {
                lp == null -> rp
                rp.updatedAt > lp.updatedAt -> rp.copy(apiKey = lp.apiKey)
                else -> lp
            }
        }
        val order = local.providers.map { it.id } + r.providers.map { it.id }.filter { it !in localIds }
        val providers = order.distinct().mapNotNull { byId[it] }

        val cleared = (local.clearedAt.keys + r.clearedAt.keys).associateWith {
            maxOf(local.clearedAt[it] ?: 0L, r.clearedAt[it] ?: 0L)
        }
        val messages = (local.messages + r.messages)
            .distinctBy { it.id }
            .filter { it.ts > (cleared[it.roleId] ?: 0L) }
            .sortedBy { it.ts }
            .takeLast(3000)

        return local.copy(prefs = prefs, providers = providers, clearedAt = cleared, messages = messages)
    }
}
