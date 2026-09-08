package com.jarves.mh.data

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.providersForAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class AppPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).apply() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).apply() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var antigravitySignedIn: Boolean
        get() = preferences.getBoolean("agent_antigravity_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).apply() }

    var antigravityAccountEmail: String
        get() = preferences.getString("agent_antigravity_account_email", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.commit()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        // Antigravity v2 sessions are created with an explicit CLI project so
        // old default-project conversations cannot redirect writes to scratch.
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek Harness was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).apply() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).apply() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).apply() }

    /**
     * Debug-only manifest URL override. Empty in release builds; populated via
     * Settings → Update channel in debug builds so a local server (exposed via
     * Cloudflare Tunnel or ngrok) can be tested without publishing a release.
     */
    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).apply() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).apply()
        }


    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
        }
        editor.apply()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        // Older builds copied the global Claude/Anthropic default into a new
        // DeepSeek Harness profile. Treat that untouched, keyless placeholder
        // as unconfigured so DeepSeek opens on its own official provider.
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        // DeepSeek retired its legacy alias. Migrate only DeepSeek profiles so
        // custom and gateway providers keep their independently selected model.
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).apply()
            }
        } else {
            savedModel
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = if (useStoredValues) {
                preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
            } else {
                kind.defaultBaseUrl
            },
            model = model,
            hasSecret = vault.contains(kind.name),
            dshApi = if (useStoredValues) {
                preferences.getString("${sourcePrefix}dsh_api", defaultDshApiForProvider(kind))
                    ?: defaultDshApiForProvider(kind)
            } else {
                defaultDshApiForProvider(kind)
            },
        )
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = File(context.filesDir, "workspaces/$id")
                    millis = if (workspaceDir.exists() && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = obj.optString("rootPath", "").takeIf { root ->
                        root.isBlank() || (!root.startsWith('/') && !root.contains(".."))
                    } ?: "",
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                )
            }
        }.getOrDefault(emptyList())

        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
            })
        }
        File(projectDir, "index.json").writeText(arr.toString())
    }

    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            return runCatching {
                val arr = JSONArray(index.readText())
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ProjectChat(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Chat"),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    )
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrDefault(emptyList())
        }

        // Migrate the original one-file-per-project conversation without losing it.
        val legacy = File(chatsDir, "$projectId.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(projectId, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(projectId, chat.id, legacyMessages)
        return listOf(chat)
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val destination = File(projectDir, "$chatId.json")
        val temporary = File(projectDir, ".$chatId.json.tmp")
        temporary.writeText(arr.toString())
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val file = File(File(chatsDir, projectId), "$chatId.json")
        return loadLegacyMessages(file)
    }

    fun deleteProjectChats(projectId: String) {
        File(chatsDir, projectId).deleteRecursively()
        File(chatsDir, "$projectId.json").delete()
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    fromUser = obj.getBoolean("fromUser"),
                    text = obj.getString("text"),
                    createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                        .getOrDefault(Instant.now()),
                    attachments = obj.optJSONArray("attachments")?.let { attachments ->
                        (0 until attachments.length()).mapNotNull { index ->
                            runCatching {
                                attachments.getJSONObject(index).let { attachment ->
                                    ChatAttachment(
                                        id = attachment.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                        displayName = attachment.getString("displayName"),
                                        relativePath = attachment.getString("relativePath"),
                                        mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                        sizeBytes = attachment.optLong("sizeBytes", 0L),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                    workedMillis = obj.optLong("workedMillis", 0L),
                    workItems = obj.optJSONArray("workItems")?.let { workItems ->
                        (0 until workItems.length()).mapNotNull { index ->
                            runCatching {
                                workItems.getJSONObject(index).let { item ->
                                    com.jarves.mh.model.ActivityItem(
                                        title = item.optString("title"),
                                        detail = item.optString("detail"),
                                        isComplete = item.optBoolean("isComplete", true),
                                        isCommand = item.optBoolean("isCommand", false),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
