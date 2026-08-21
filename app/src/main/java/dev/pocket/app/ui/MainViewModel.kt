package dev.pocket.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocket.app.data.ApiKeyVault
import dev.pocket.app.data.AppPreferences
import dev.pocket.app.model.ActivityItem
import dev.pocket.app.model.ChangeItem
import dev.pocket.app.model.ChatMessage
import dev.pocket.app.model.Project
import dev.pocket.app.model.ProjectChat
import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile
import dev.pocket.app.model.RuntimeEvent
import dev.pocket.app.model.ToolRequest
import dev.pocket.app.model.WorkspaceEntry
import dev.pocket.app.network.ConnectionValidation
import dev.pocket.app.network.ModelDiscoveryResult
import dev.pocket.app.network.ProviderApiClient
import dev.pocket.app.runtime.ClaudeRuntimeBridge
import dev.pocket.app.runtime.RuntimeInstaller
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupError: String? = null,
    val onboardingComplete: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val themeMode: dev.pocket.app.ui.theme.AppThemeMode = dev.pocket.app.ui.theme.AppThemeMode.DARK,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val projects: List<Project> = emptyList(),
    val activeProject: Project? = null,
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val fileContentLoading: Boolean = false,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val previewReady: Boolean = false,
    val isRunning: Boolean = false,
    val activeSessionId: String? = null,
    val toastMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val runtime = ClaudeRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val installer = RuntimeInstaller(application)
    private val providerApi = ProviderApiClient()
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = preferences.onboardingComplete,
            provider = preferences.loadProvider(vault),
            themeMode = runCatching { dev.pocket.app.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(dev.pocket.app.ui.theme.AppThemeMode.DARK),
            projects = preferences.loadProjects(),
        ),
    )

    init {
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(
        listOf(
            TerminalOutputLine(
                command = "uname -a",
                output = "Linux pocket-dev 6.1.0-arm64 #1 SMP aarch64 GNU/Linux (PRoot Sandbox)",
                exitCode = 0,
            ),
        ),
    )
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", command),
                    )
                    val exit = proc.waitFor()
                    val out = (proc as? dev.pocket.app.runtime.NativeSpawnProcess)?.outputFile?.readText().orEmpty().trim()
                    val finalOut = if (out.isNotEmpty()) out else if (exit == 0) "[Process completed with exit code 0]" else "[Process exited with code $exit]"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _isTerminalRunning.value = false
        }
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }


    fun toggleTheme() {
        val next = if (_state.value.themeMode == dev.pocket.app.ui.theme.AppThemeMode.DARK) {
            dev.pocket.app.ui.theme.AppThemeMode.LIGHT
        } else {
            dev.pocket.app.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: dev.pocket.app.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    init {
        viewModelScope.launch { runtime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val installed = withContext(Dispatchers.IO) {
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        when {
            !installed -> _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime()
        }
    }

    fun startRuntimeSetup() {
        if (state.value.startupStage == StartupStage.INSTALLING) return
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupError = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureInstalled { progress ->
                        _state.update {
                            it.copy(
                                startupProgress = progress.fraction.coerceIn(0f, 1f),
                                startupMessage = progress.message,
                                startupBytes = progress.totalBytes?.let { total -> (progress.downloadedBytes ?: 0L) to total },
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) {
                preferences.runtimeSetupComplete = true
                if (preferences.onboardingComplete) {
                    initializeRuntime()
                } else {
                    _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f, startupBytes = null) }
                }
            } else {
                showStartupError(result.exceptionOrNull() ?: IllegalStateException("Setup could not be completed"))
            }
        }
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else startRuntimeSetup()
    }

    private suspend fun initializeRuntime() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startupStage = StartupStage.INITIALIZING,
                startupProgress = 0.05f,
                startupMessage = "Opening your private workspace",
                startupBytes = null,
                startupError = null,
            )
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.ensureInstalled { progress ->
                    _state.update {
                        it.copy(
                            startupProgress = progress.fraction * 0.65f,
                            startupMessage = progress.message,
                            startupBytes = progress.totalBytes?.let { total ->
                                (progress.downloadedBytes ?: 0L) to total
                            },
                        )
                    }
                }
                installer.initializeExisting { progress ->
                    _state.update {
                        it.copy(
                            startupProgress = 0.65f + progress.fraction * 0.35f,
                            startupMessage = progress.message,
                            startupBytes = null,
                        )
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable.
            val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            _state.update { it.copy(startupStage = StartupStage.READY, startupProgress = 1f) }
            pingApi()
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    private fun showStartupError(error: Throwable) {
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = error.message?.take(300) ?: "Setup could not be completed",
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name) || profile.kind == ProviderKind.CLAUDE,
        )
        preferences.saveProvider(saved)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        pingApi()
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, profile.kind.protocol)
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<dev.pocket.app.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.validate(profile.baseUrl, profile.model, key, profile.kind.protocol, models)
    }

    fun pingApi() {
        val url = _state.value.provider.baseUrl.ifBlank { return }
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.instanceFollowRedirects = true
                    conn.connect()
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 100..599  // any HTTP response = server is alive
                }.getOrDefault(false)
            }
            _state.update { it.copy(apiPingStatus = if (ok) ApiPingStatus.OK else ApiPingStatus.FAILED) }
        }
    }

    fun openProject(project: Project) {
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
        }
        val activeChat = chats.first()
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        _state.update {
            it.copy(
                activeProject = project,
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = msgs,
                liveProcess = emptyList(),
                changes = emptyList(),
                workspaceFiles = emptyList(),
                filesLoading = true,
            )
        }
        refreshProjectFiles()
        viewModelScope.launch {
            val pending = runtime.loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        persistMessages()
        if (_state.value.isRunning) {
            viewModelScope.launch { runtime.stopActiveSession() }
        }
        _state.update {
            it.copy(
                activeProject = null,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                filesLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
            )
        }
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }

    fun createProject(name: String) {
        if (name.isBlank()) return
        val project = Project(name = name.trim(), description = "Starter web project", language = "TypeScript")
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                changes = emptyList(),
                workspaceFiles = emptyList(),
                filesLoading = true,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        persistMessages()
        val chat = ProjectChat()
        val chats = listOf(chat) + _state.value.projectChats
        preferences.saveProjectChats(project.id, chats)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                pendingApproval = null,
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = preferences.loadMessages(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) },
                liveProcess = emptyList(),
                pendingApproval = null,
            )
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { readWorkspace(project.id) }
            if (_state.value.activeProject?.id == project.id) {
                _state.update { it.copy(workspaceFiles = entries, filesLoading = false) }
            }
        }
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(openedFilePath = entry.path, openedFileContent = null, fileContentLoading = true) }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(getApplication<Application>().filesDir, "workspaces/${project.id}/${entry.path}")
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            _state.update { it.copy(openedFileContent = content, fileContentLoading = false) }
        }
    }

    fun closeFile() {
        _state.update { it.copy(openedFilePath = null, openedFileContent = null, fileContentLoading = false) }
    }


    private fun readWorkspace(projectId: String): List<WorkspaceEntry> {
        val root = File(getApplication<Application>().filesDir, "workspaces/$projectId")
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (prompt.isBlank() || state.value.isRunning) return
        updateActiveChatTitle(prompt)
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim()),
                isRunning = true,
                activity = listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity,
                liveProcess = listOf(ActivityItem("Starting Claude Code", "Launching the project agent…", false)),
            )
        }
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now
        viewModelScope.launch {
            runtime.startSession(project.id, prompt.trim(), history, state.value.provider)
        }
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        viewModelScope.launch { runtime.respondToApproval(request, approved) }
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = runtime.undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) "Changes undone" else "Undo unavailable",
                            if (restored) "Restored files to their state before the task" else "No restorable checkpoint was found",
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            runtime.acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem("Changes kept", "Accepted the task's file changes")) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (runtime.undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (runtime.acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        _state.update { current ->
            when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                    liveProcess = current.liveProcess.map { item ->
                        if (item.title == "Starting Claude Code") item.copy(detail = "Agent process started", isComplete = true) else item
                    },
                )
                is RuntimeEvent.AssistantDelta -> {
                    val lastMsg = current.messages.lastOrNull()
                    val process = if (current.liveProcess.any { it.title == "Writing response" }) {
                        current.liveProcess
                    } else {
                        current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                            ActivityItem("Writing response", "Streaming Claude's response into chat…", false)
                    }
                    if (lastMsg != null && !lastMsg.fromUser) {
                        // Append to existing assistant message (streaming)
                        current.copy(
                            messages = current.messages.dropLast(1) + lastMsg.copy(text = lastMsg.text + event.text),
                            liveProcess = process,
                        )
                    } else {
                        // Start a new assistant message
                        current.copy(
                            messages = current.messages + ChatMessage(fromUser = false, text = event.text),
                            liveProcess = process,
                        )
                    }
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val withoutPrevious = current.activity.filterNot { it.title == "Claude is reasoning" && !it.isComplete }
                    val liveReasoning = current.liveProcess.indexOfLast { it.title == "Claude is reasoning" && !it.isComplete }
                    val process = if (liveReasoning >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            items[liveReasoning] = items[liveReasoning].copy(
                                detail = "${event.estimatedTokens} reasoning tokens processed",
                            )
                        }
                    } else {
                        current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                            ActivityItem("Claude is reasoning", "${event.estimatedTokens} reasoning tokens processed", false)
                    }
                    current.copy(
                        activity = listOf(
                            ActivityItem(
                                "Claude is reasoning",
                                "${event.estimatedTokens} reasoning tokens processed",
                                false,
                            ),
                        ) + withoutPrevious,
                        liveProcess = process,
                    )
                }
                is RuntimeEvent.ToolStarted -> current.copy(
                    activity = listOf(ActivityItem("Running ${event.toolName}", event.detail, false)) +
                        current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Running ${event.toolName}", event.detail, false),
                )
                is RuntimeEvent.RuntimeLog -> current.copy(
                    activity = listOf(ActivityItem(event.title, event.detail)) + current.activity,
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Waiting for approval", event.request.explanation, false),
                )
                is RuntimeEvent.ToolApproved -> current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Action approved", "Claude is continuing the task", false),
                )
                is RuntimeEvent.ToolRejected -> current.copy(
                    pendingApproval = null,
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Action rejected", "Claude will continue without this action"),
                )
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            items[runningIndex] = ActivityItem("${event.toolName} completed", event.summary)
                        }
                    } else {
                        current.liveProcess + ActivityItem("${event.toolName} completed", event.summary)
                    }
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                )
                is RuntimeEvent.SessionCompleted -> current.copy(
                    isRunning = false,
                    activeSessionId = null,
                    activity = listOf(ActivityItem("Task completed", "Claude Code finished successfully")) +
                        current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Task completed", "Claude Code finished successfully"),
                )
                is RuntimeEvent.SessionFailed -> current.copy(
                    isRunning = false,
                    activeSessionId = null,
                    pendingApproval = null,
                    toastMessage = event.reason.takeIf { reason ->
                        reason.contains("user not found", true) ||
                            reason.contains("API key", true) ||
                            reason.contains("authentication", true)
                    },
                    activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                    liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } +
                        ActivityItem("Task stopped", event.reason),
                )
            }
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {
            _state.value.activeProject?.id?.let { touchProject(it) }
            refreshProjectFiles()
        }
        if (event is RuntimeEvent.AssistantDelta || event is RuntimeEvent.SessionCompleted) {
            persistMessages()
        }
    }

    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages() {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val msgs = _state.value.messages
        viewModelScope.launch(Dispatchers.IO) {
            preferences.saveMessages(project.id, chatId, msgs)
        }
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat") title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
    }
}
