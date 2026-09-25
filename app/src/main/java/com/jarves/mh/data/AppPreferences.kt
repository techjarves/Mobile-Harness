package com.jarves.mh.data

import android.content.Context
import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
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

    /**
     * Agent tool-call autonomy mode (ISSUE-001). Everyone — including fresh
     * installs and users upgrading from v1.0.x — starts on APPROVE_RISKY;
     * FULLY_AUTONOMOUS is an explicit opt-in stored by stable id.
     */
    var agentAutonomyMode: AgentAutonomyMode
        get() = AgentAutonomyMode.fromStored(preferences.getString("agent_autonomy_mode", null))
        set(value) { preferences.edit().putString("agent_autonomy_mode", value.stableId).apply() }

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

    /**
     * Pre-1.3.0 conversation JSON lived under filesDir/chats/. The directory is
     * kept only to discover and import those files once; all new persistence
     * goes through the SQLite [ConversationStore] (ISSUE-021).
     */
    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    private val conversationStore: ConversationStore
        get() = ConversationStore.get(context.applicationContext)

    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        conversationStore.saveChats(projectId, chats)
    }

    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val stored = conversationStore.loadChats(projectId)
        if (stored.isNotEmpty()) return stored

        // One-time import: multi-chat era index.json → store. The file is only
        // removed after the imported rows are confirmed readable (fail-safe:
        // any failure leaves the JSON untouched and retried on next launch).
        val index = File(chatsDir, "$projectId/index.json")
        if (index.isFile) {
            val imported = runCatching {
                val parsed = ConversationCodec.legacyChatIndexFromJson(index.readText())
                if (parsed.isEmpty()) return@runCatching false
                conversationStore.saveChats(projectId, parsed)
                conversationStore.loadChats(projectId).isNotEmpty()
            }.getOrDefault(false)
            if (imported && !index.delete()) {
                index.renameTo(File(index.parentFile, "index.json.imported"))
            }
            if (imported) return conversationStore.loadChats(projectId)
        }

        // Oldest layout: one conversation file per project. Same verified-import
        // flow as above; the chat entry itself is always created so callers can
        // rely on at least one chat existing (previous behavior).
        val legacy = File(chatsDir, "$projectId.json")
        val legacyMessages = if (legacy.isFile) {
            runCatching { ConversationCodec.legacyMessagesFromJson(legacy.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        conversationStore.saveChats(projectId, listOf(chat))
        if (legacy.isFile) {
            val imported = runCatching {
                if (legacyMessages.isNotEmpty()) {
                    conversationStore.saveMessages(projectId, chat.id, legacyMessages)
                }
                true
            }.getOrDefault(false)
            if (imported && !legacy.delete()) {
                legacy.renameTo(File(chatsDir, "$projectId.json.imported"))
            }
        }
        return listOf(chat)
    }

    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        conversationStore.saveMessages(projectId, chatId, messages)
    }

    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val stored = conversationStore.loadMessages(projectId, chatId)
        if (stored.isNotEmpty()) return stored

        // One-time verified import of the pre-1.3.0 per-chat JSON file.
        val legacy = File(File(chatsDir, projectId), "$chatId.json")
        if (!legacy.isFile) return emptyList()
        val imported = runCatching {
            val parsed = ConversationCodec.legacyMessagesFromJson(legacy.readText())
            if (parsed.isNotEmpty()) conversationStore.saveMessages(projectId, chatId, parsed)
            true
        }.getOrDefault(false)
        if (imported && !legacy.delete()) {
            legacy.renameTo(File(legacy.parentFile, "$chatId.json.imported"))
        }
        return if (imported) conversationStore.loadMessages(projectId, chatId) else stored
    }

    fun deleteProjectChats(projectId: String) {
        File(chatsDir, projectId).deleteRecursively()
        File(chatsDir, "$projectId.json").delete()
        conversationStore.deleteProject(projectId)
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
