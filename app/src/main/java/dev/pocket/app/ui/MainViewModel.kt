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

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupError: String? = null,
    val onboardingComplete: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val projects: List<Project> = emptyList(),
    val activeProject: Project? = null,
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
    val previewReady: Boolean = false,
    val isRunning: Boolean = false,
    val activeSessionId: String? = null,
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
            projects = emptyList(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { runtime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val installed = withContext(Dispatchers.IO) { installer.isInstalled() }
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
        _state.update { it.copy(activeProject = project, workspaceFiles = emptyList(), filesLoading = true) }
        refreshProjectFiles()
    }

    fun closeProject() = _state.update {
        it.copy(activeProject = null, workspaceFiles = emptyList(), filesLoading = false)
    }

    fun createProject(name: String) {
        if (name.isBlank()) return
        val project = Project(name = name.trim(), description = "Starter web project", language = "TypeScript")
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceFiles = emptyList(),
                filesLoading = true,
            )
        }
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        refreshProjectFiles()
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
                directory == root || (
                    !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
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

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (prompt.isBlank() || state.value.isRunning) return
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim()),
                isRunning = true,
                activity = listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity,
            )
        }
        viewModelScope.launch {
            runtime.startSession(project.id, prompt.trim(), state.value.provider)
        }
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        viewModelScope.launch { runtime.respondToApproval(request, approved) }
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        _state.update { current ->
            when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                )
                is RuntimeEvent.AssistantDelta -> current.copy(
                    messages = current.messages + ChatMessage(fromUser = false, text = event.text),
                )
                is RuntimeEvent.ToolRequested -> current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                )
                is RuntimeEvent.ToolApproved -> current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                )
                is RuntimeEvent.ToolRejected -> current.copy(pendingApproval = null)
                is RuntimeEvent.ToolCompleted -> current.copy(
                    activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                )
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.paths.map { ChangeItem(it, additions = 18, deletions = 4) },
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                )
                is RuntimeEvent.SessionCompleted -> current.copy(isRunning = false, activeSessionId = event.sessionId)
                is RuntimeEvent.SessionFailed -> current.copy(
                    isRunning = false,
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                )
            }
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {
            refreshProjectFiles()
        }
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
    }
}
