package dev.multiprompt.companion.skills

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Maintains multiprompt's own skills, independent of any coding harness: the list is
 * fetched from GitHub (a public skills index + raw SKILL.md files), cached in prefs, and
 * served to the composer picker. When a skill is selected, its full body is injected
 * alongside the user's prompt — so the agent in the tmux session receives the real
 * instructions no matter which harness it runs under.
 *
 * No secrets: public repos, no API token. Offline → last cached snapshot.
 */
class SkillStore(context: Context) {

    private val preferences = context.getSharedPreferences("skills", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Repos to index, as "owner/repo" strings holding a skills index at [INDEX_PATH]. */
    var repos: List<String> = DEFAULT_REPOS

    /** Snapshot for the UI; bundled skills are always present, GitHub adds/refreshes. */
    @Volatile
    var snapshot: SkillSnapshot? = null
        private set

    private val assets = context.assets

    fun loadFromCache(): SkillSnapshot? {
        val cached = preferences.getString(KEY_CACHE, null)?.let(SkillModel::parseCache)
        snapshot = bundled() ?: cached
        return snapshot
    }

    /**
     * Loads the skills bundled with the app: the harness set (source=harness) and the
     * curated multiprompt set (source=multiprompt). Always available, no network.
     */
    fun bundled(): SkillSnapshot? = runCatching {
        val indexJson = assets.open("skills/index.json").bufferedReader().use { it.readText() }
        val entries = SkillModel.parseIndex(indexJson).entries
        val skills = entries.mapNotNull { entry ->
            val body = runCatching {
                assets.open("skills/${entry.file}").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            Skill(
                name = entry.name,
                description = entry.description,
                body = SkillModel.stripFrontmatter(body),
                repo = entry.repo,
                ref = entry.ref,
                source = entry.source,
            )
        }
        if (skills.isEmpty()) return@runCatching null
        SkillSnapshot(SkillModel.sortedByDisplayName(skills), System.currentTimeMillis() / 1000)
    }.getOrNull()

    /**
     * Refreshes from GitHub in the background; emits the fresh snapshot via [onDone]
     * (main caller decides threading). Falls back silently to the cache when offline.
     */
    fun refresh(scope: CoroutineScope, onDone: (SkillSnapshot?) -> Unit = {}) {
        scope.launch {
            val fresh = fetchSnapshot()
            if (fresh != null) {
                snapshot = fresh
                preferences.edit()
                    .putString(KEY_CACHE, SkillModel.cacheJson(fresh))
                    .putLong(KEY_FETCHED_AT, fresh.fetchedAtEpochSeconds)
                    .apply()
                onDone(fresh)
            } else {
                onDone(loadFromCache())
            }
        }
    }

    suspend fun fetchSnapshot(): SkillSnapshot? = withContext(Dispatchers.IO) {
        val bundled = bundled()
        val entries = mutableListOf<SkillModel.ParsedIndex.Entry>()
        for (repo in repos) {
            val indexJson = fetchText(rawUrl(repo, INDEX_PATH, "main")) ?: continue
            val parsed = SkillModel.parseIndex(indexJson)
            entries += parsed.entries.map { it.copy(repo = repo) }
        }
        if (entries.isEmpty()) return@withContext bundled
        val fetched = entries.mapNotNull { entry ->
            val body = fetchText(rawUrl(entry.repo, entry.file, entry.ref)) ?: return@mapNotNull null
            Skill(
                name = entry.name,
                description = entry.description,
                body = SkillModel.stripFrontmatter(body),
                repo = entry.repo,
                ref = entry.ref,
                source = entry.source,
            )
        }
        // Harness skills ship with the app and cannot be removed remotely; fetched
        // skills only add or update multiprompt-source entries.
        val byName = linkedMapOf<String, Skill>()
        (bundled?.skills ?: emptyList()).forEach { byName[it.name.lowercase()] = it }
        fetched.forEach { if (it.source == "multiprompt") byName[it.name.lowercase()] = it }
        SkillSnapshot(SkillModel.sortedByDisplayName(byName.values.toList()), System.currentTimeMillis() / 1000)
    }

    private fun fetchText(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    companion object {
        const val INDEX_PATH = "skills/index.json"
        const val DEFAULT_REPOS_KEY = "repos"
        private const val KEY_CACHE = "cache_json"
        private const val KEY_FETCHED_AT = "fetched_at"

        /** Temporary default: multiprompt's own skills repo (owner/repo). */
        val DEFAULT_REPOS: List<String> = listOf("valentinyeo/multiprompt-skills")

        fun rawUrl(repo: String, path: String, ref: String): String =
            "https://raw.githubusercontent.com/$repo/$ref/$path"
    }
}
