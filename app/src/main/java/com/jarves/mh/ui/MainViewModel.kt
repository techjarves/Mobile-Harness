package com.jarves.mh.ui

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.SystemClock
import android.os.Build
import android.os.StatFs
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectType
import com.jarves.mh.model.AndroidTemplate
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.generateQuickChatIdentity
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AgentRegistry
import com.jarves.mh.runtime.AgentUpdateInfo
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.RuntimeSetupController
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.RuntimeSetupSnapshot
import com.jarves.mh.runtime.RuntimeSetupStatus
import com.jarves.mh.runtime.readTailText
import com.jarves.mh.runtime.supportsArm64Runtime
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.runtime.AndroidBuildPhase
import com.jarves.mh.runtime.AndroidBuildRecord
import com.jarves.mh.runtime.AndroidAction
import com.jarves.mh.runtime.AndroidApkInfo
import com.jarves.mh.runtime.AndroidBuildIssue
import com.jarves.mh.runtime.AndroidBuildStage
import com.jarves.mh.runtime.AndroidHealthCheck
import com.jarves.mh.runtime.AndroidHealthFix
import com.jarves.mh.runtime.AndroidHealthStatus
import com.jarves.mh.runtime.AndroidLogLevel
import com.jarves.mh.runtime.AndroidLogLine
import com.jarves.mh.runtime.AndroidLogcatState
import com.jarves.mh.runtime.AndroidProjectTemplateGenerator
import com.jarves.mh.runtime.RuntimeExecutionService
import com.jarves.mh.runtime.RuntimeTaskController
import com.jarves.mh.runtime.androidGradleCommand
import com.jarves.mh.runtime.diagnoseAndroidBuildFailure
import com.jarves.mh.runtime.findAndroidProjectRoot
import com.jarves.mh.runtime.findDebugApk
import com.jarves.mh.runtime.findReusableDebugApk
import com.jarves.mh.runtime.inferAndroidBuildStage
import com.jarves.mh.runtime.isDebugApkStale
import com.jarves.mh.runtime.parseAndroidBuildIssues
import com.jarves.mh.update.AppUpdateInfo
import com.jarves.mh.update.AppUpdater
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.UnknownHostException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

data class ExportSelection(
    val defaultIncluded: Boolean = true,
    val overrides: Map<String, Boolean> = emptyMap(),
) {
    fun includes(path: String): Boolean {
        val normalized = path.trim('/')
        return overrides.entries
            .asSequence()
            .filter { (rule, _) -> normalized == rule || normalized.startsWith("$rule/") }
            .maxByOrNull { it.key.length }
            ?.value ?: defaultIncluded
    }

    fun canContainIncluded(path: String): Boolean {
        val normalized = path.trim('/')
        return includes(normalized) || overrides.any { (rule, included) ->
            included && rule.startsWith("$normalized/")
        }
    }

    fun toggled(path: String, directory: Boolean): ExportSelection {
        val normalized = path.trim('/')
        val nextValue = !includes(normalized)
        val next = overrides.toMutableMap()
        if (directory) next.keys.filter { it.startsWith("$normalized/") }.forEach(next::remove)
        next[normalized] = nextValue
        return copy(overrides = next)
    }
}

enum class FileReadOnlyReason { TOO_LARGE, BINARY, INVALID_UTF8, UNSAFE, READ_ERROR }

data class OpenedFileState(
    val path: String,
    val content: String? = null,
    val draft: String = "",
    val loading: Boolean = true,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val conflict: Boolean = false,
    val readOnlyReason: FileReadOnlyReason? = null,
    val originalFingerprint: String? = null,
    val lineEnding: String = "\n",
    val hadTrailingNewline: Boolean = false,
    val targetLine: Int? = null,
    val targetColumn: Int? = null,
)

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
)

private data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

private data class RuntimeRetryRequest(
    val runtime: com.jarves.mh.runtime.RuntimeBridge,
    val project: Project,
    val prompt: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
)

data class QueuedFollowUp(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val prompt: String,
    val attachments: List<ChatAttachment>,
)

private data class TranscriptWrite(
    val projectId: String,
    val chatId: String,
    val messages: List<ChatMessage>,
)

private data class ImportedZipProject(
    val project: Project,
    val sourceAttachment: ChatAttachment,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val showDetailedSetupProgress: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val initialLanguageSelected: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val activeApiKeyName: String? = null,
    val themeMode: com.jarves.mh.ui.theme.AppThemeMode = com.jarves.mh.ui.theme.AppThemeMode.DARK,
    val languageCode: String = "system",
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val projects: List<Project> = emptyList(),
    val projectImporting: Boolean = false,
    val projectImportMessage: String? = null,
    val gitCloneRunning: Boolean = false,
    val gitCloneMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val activeProject: Project? = null,
    val workspaceVisible: Boolean = false,
    val readOnlyProject: Project? = null,
    val readOnlyProjectChats: List<ProjectChat> = emptyList(),
    val readOnlyChatId: String? = null,
    val readOnlyMessages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    val openedFile: OpenedFileState? = null,
    val projectExportRunning: Boolean = false,
    val projectExportSucceededAtMillis: Long? = null,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val queuedFollowUps: List<QueuedFollowUp> = emptyList(),
    val activeSessionId: String? = null,
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val devStackInstalling: DevStack? = null,
    val devStackRemoving: Boolean = false,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val devStackBytesPerSecond: Long? = null,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val primaryAgentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgentVersions: Map<AgentKind, String> = emptyMap(),
    val agentInstalling: AgentKind? = null,
    val agentMessage: String? = null,
    val agentProgress: Float = 0f,
    val agentDownloadedBytes: Long? = null,
    val agentTotalBytes: Long? = null,
    val agentBytesPerSecond: Long? = null,
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val agentUpdatesChecking: Boolean = false,
    val agentUpdating: AgentKind? = null,
    val agentUpdateMessage: String? = null,
    val agentUpdateProgress: Float = 0f,
    val agentUpdateDownloadedBytes: Long? = null,
    val agentUpdateTotalBytes: Long? = null,
    val agentUpdateBytesPerSecond: Long? = null,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val androidBuildPhase: AndroidBuildPhase = AndroidBuildPhase.IDLE,
    val androidBuildAction: AndroidAction = AndroidAction.NONE,
    val androidBuildStage: AndroidBuildStage = AndroidBuildStage.IDLE,
    val androidBuildIssues: List<AndroidBuildIssue> = emptyList(),
    val androidBuildLog: String = "",
    val androidBuildStartedAtMillis: Long? = null,
    val androidBuildFinishedAtMillis: Long? = null,
    val androidBuildApkPath: String? = null,
    val androidBuildApkSizeBytes: Long? = null,
    val androidApkInfo: AndroidApkInfo? = null,
    val androidHealthChecks: List<AndroidHealthCheck> = emptyList(),
    val androidHealthRunning: Boolean = false,
    val androidLogcat: AndroidLogcatState = AndroidLogcatState(),
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val claudeRuntime = ClaudeRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val dshRuntime = DshRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val installer = RuntimeInstaller(application)
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
        },
        saveConversationId = { projectId, id ->
            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
        },
    )
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime)
    private fun activeRuntime(): com.jarves.mh.runtime.RuntimeBridge = agentRegistry.require(_state.value.agentKind).runtime
    private val providerApi = ProviderApiClient()
    private fun appUpdater(): AppUpdater = AppUpdater(
        getApplication(),
        if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else "",
    )
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false
    @Volatile private var androidBuildProcess: Process? = null
    @Volatile private var androidBuildStopRequested: Boolean = false
    @Volatile private var androidLogcatProcess: Process? = null
    private var androidLogcatJob: kotlinx.coroutines.Job? = null
    private var pendingAndroidInstallId: String? = null
    private var pendingAndroidInstallLaunch = false
    private var androidLaunchAtMillis: Long? = null
    @Volatile private var setupCompletionHandled: Boolean = false
    @Volatile private var githubAuthProcess: Process? = null
    private var githubAuthJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastOpenedAntigravityAuthUrl: String? = null
    private var activeRuntimeRequest: RuntimeRetryRequest? = null
    private val pendingFollowUps = java.util.ArrayDeque<QueuedFollowUp>()
    @Volatile private var steeringToFollowUp: Boolean = false
    private val failedApiKeyIds = mutableSetOf<String>()
    private val transcriptWrites = Channel<TranscriptWrite>(Channel.UNLIMITED)
    private val initialAgentKind = AgentKind.fromStored(preferences.agentKind)
    private val initialPrimaryAgentKind = preferences.primaryAgentKind
        .takeIf(String::isNotBlank)
        ?.let(AgentKind::fromStored)
        ?: initialAgentKind
    private val antigravityAuthController = AntigravityAuthController(
        application,
        preferences.antigravitySignedIn,
        preferences.antigravityAccountEmail,
    ) { signedIn, email ->
        preferences.antigravitySignedIn = signedIn
        preferences.antigravityAccountEmail = email.orEmpty()
        if (!signedIn) preferences.clearAgentConversations(AgentKind.ANTIGRAVITY)
    }
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            initialLanguageSelected = preferences.initialLanguageSelected,
            agentKind = initialAgentKind,
            primaryAgentKind = initialPrimaryAgentKind,
            provider = preferences.loadProvider(vault, initialAgentKind),
            activeApiKeyName = vault.list(preferences.loadProvider(vault, initialAgentKind).kind.name)
                .firstOrNull(ApiKeyInfo::isActive)?.name,
            antigravityAuth = AntigravityAuthState(
                status = if (preferences.antigravitySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
                accountEmail = preferences.antigravityAccountEmail.takeIf(String::isNotBlank),
            ),
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            themeMode = runCatching { com.jarves.mh.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.jarves.mh.ui.theme.AppThemeMode.DARK),
            languageCode = preferences.languageCode,
            projects = preferences.loadProjects(),
            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
        ),
    )

    init {
        viewModelScope.launch {
            AndroidAppInstaller.events.collect { event ->
                if (event.operationId != pendingAndroidInstallId) return@collect
                val project = _state.value.activeProject
                val launchRequested = pendingAndroidInstallLaunch
                pendingAndroidInstallId = null
                pendingAndroidInstallLaunch = false
                if (event.success) {
                    if (event.launched) androidLaunchAtMillis = System.currentTimeMillis()
                    val message = when {
                        event.launched -> "App installed and launched"
                        launchRequested -> "App installed, but no launchable activity was found"
                        else -> "APK installed"
                    }
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                            androidBuildStage = AndroidBuildStage.COMPLETE,
                            androidBuildMessage = message,
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    project?.let { saveAndroidBuildRecord(it.id) }
                    refreshAndroidApkInfo()
                } else {
                    val message = event.message ?: "APK installation failed"
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = AndroidBuildPhase.FAILED,
                            androidBuildStage = AndroidBuildStage.FAILED,
                            androidBuildMessage = message,
                            androidBuildIssues = listOf(AndroidBuildIssue(title = "Installation failed", detail = message)),
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    project?.let { saveAndroidBuildRecord(it.id) }
                }
            }
        }
        // GitHub's official CLI owns its OAuth credential. Remove credentials from
        // the retired custom OAuth implementation and discover the real CLI status.
        vault.remove(LEGACY_GITHUB_TOKEN_KEY)
        viewModelScope.launch { refreshGitHubConnection() }
        RuntimeSetupController.restore(application)
        viewModelScope.launch(Dispatchers.IO) {
            for (write in transcriptWrites) {
                preferences.saveMessages(write.projectId, write.chatId, write.messages)
            }
        }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                    lastOpenedAntigravityAuthUrl = url
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the sign-in URL instead.") }
                    }
                }
            }
        }
        if (antigravityAuthController.hasOfficialCredential() &&
            (!preferences.antigravitySignedIn || preferences.antigravityAccountEmail.isBlank())
        ) {
            viewModelScope.launch { antigravityAuthController.beginLogin() }
        }
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider, _state.value.agentKind)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        val loadedProjects = preferences.loadProjects()
        val cleanedProjects = loadedProjects.filter { project ->
            if (project.kind == ProjectKind.QUICK_PROJECT) {
                val chats = preferences.loadProjectChats(project.id)
                val userMessages = chats.sumOf { preferences.loadMessages(project.id, it.id).count { m -> m.fromUser } }
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                val keep = userMessages > 0 || userFiles > 0
                if (!keep) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(project.id).delete()
                    preferences.deleteProjectChats(project.id)
                }
                keep
            } else true
        }
        if (cleanedProjects.size != loadedProjects.size) {
            preferences.saveProjects(cleanedProjects)
            _state.update { it.copy(projects = cleanedProjects) }
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

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = StringBuilder()
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || _state.value.projectTerminalRunning || _state.value.androidBuildRunning || projectTerminalProcess?.isAlive == true) return
        if (requiresAndroidToolchain(normalized) && !installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (isDestructiveTerminalCommand(normalized)) {
            _state.update { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (command.isBlank() || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        _state.update { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = _state.value.pendingTerminalCommand ?: return
        _state.update { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        _state.update { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val startingCwd = _state.value.projectTerminalCwd
        val existingLines = _state.value.projectTerminalLines
        projectTerminalStopRequested = false
        val requestedPreviewUrl = detectServerUrl(command)
        _state.update {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = "Terminal error: ${error.message ?: error::class.java.simpleName}",
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else "Process exited with code ${result.exitCode}"
                },
                exitCode = result.exitCode,
            )
            val updatedLines = (existingLines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            saveProjectTerminal(project.id, result.cwd, updatedLines)
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        projectTerminalLines = updatedLines,
                        projectTerminalLiveOutput = "",
                        projectTerminalRunning = false,
                        projectTerminalCwd = result.cwd,
                        projectTerminalCommand = null,
                    )
                }
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!_state.value.projectTerminalRunning) return
        projectTerminalStopRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            projectTerminalProcess?.destroy()
            delay(400)
            if (projectTerminalProcess?.isAlive == true) projectTerminalProcess?.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        sendProcessInput(projectTerminalProcess, text)
    }

    fun interruptProjectTerminalCommand() {
        interruptProcess(projectTerminalProcess)
    }

    fun clearProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalLines = emptyList(), projectTerminalLiveOutput = "") }
        saveProjectTerminal(project.id, _state.value.projectTerminalCwd, emptyList())
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult("Linux environment is not ready yet.", 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult("Project is no longer available.", 1, cwd)
        val workspace = projectWorkspaceRoot(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult("Unsupported terminal process.", 1, cwd)
        var offset = 0L
        val output = StringBuilder()
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = detectPreviewUrl(visible)
                _state.update { current ->
                    if (current.activeProject?.id == projectId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                _state.update { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        viewModelScope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive and
     * can receive input through [sendProcessInput].
     */
    private fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold $1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    private fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    private fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    private fun detectPreviewUrl(output: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(output)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    private fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\s+-m\s+http\.server(?:\s+(\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun terminalHistoryFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "terminal-history/$projectId.json")

    private fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot {
        val file = terminalHistoryFile(project.id)
        val guestRoot = projectGuestRoot(project)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            ProjectTerminalSnapshot(
                lines = lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                cwd = root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    private fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        runCatching {
            val file = terminalHistoryFile(projectId)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                array.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("command", line.command)
                        .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                        .put("exitCode", line.exitCode),
                )
            }
            file.writeText(JSONObject().put("cwd", cwd).put("lines", array).toString())
        }
    }

    private fun projectGuestRoot(project: Project): String = "/workspace/${project.slug}"

    private fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        performAndroidAction(AndroidAction.BUILD_AND_RUN)
    }

    fun performAndroidAction(action: AndroidAction) {
        when (action) {
            AndroidAction.BUILD -> {
                if (!completeFromReusableApk(action, launch = false)) startAndroidGradleTask("assembleDebug", action)
            }
            AndroidAction.FORCE_BUILD -> startAndroidGradleTask("assembleDebug", action)
            AndroidAction.INSTALL -> installLatestAndroidApk(launch = false, action = action)
            AndroidAction.RUN -> runLatestAndroidApk()
            AndroidAction.BUILD_AND_RUN -> {
                if (!completeFromReusableApk(action, launch = true)) startAndroidGradleTask("assembleDebug", action)
            }
            AndroidAction.CLEAN -> startAndroidGradleTask("clean", action)
            AndroidAction.NONE -> Unit
        }
    }

    private fun completeFromReusableApk(action: AndroidAction, launch: Boolean): Boolean {
        val current = _state.value
        val project = current.activeProject ?: return false
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) return false
        val workspace = findAndroidProjectRoot(projectWorkspaceRoot(project)) ?: return false
        val apk = findReusableDebugApk(workspace) ?: return false
        if (launch) {
            if (AndroidAppInstaller.openIfAlreadyInstalled(getApplication(), apk)) {
                androidLaunchAtMillis = System.currentTimeMillis()
            } else {
                beginAndroidInstall(apk, launch = true, action = action)
                return true
            }
        }
        val now = System.currentTimeMillis()
        val message = if (launch) "No changes — opened the installed app" else "APK is already current"
        _state.update {
            it.copy(
                androidBuildRunning = false,
                androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.COMPLETE,
                androidBuildIssues = emptyList(),
                androidBuildMessage = message,
                androidBuildLog = "Gradle skipped: project files have not changed since the last successful build.\n",
                androidBuildStartedAtMillis = now,
                androidBuildFinishedAtMillis = now,
                androidBuildApkPath = apk.absolutePath,
                androidBuildApkSizeBytes = apk.length(),
                toastMessage = message,
            )
        }
        saveAndroidBuildRecord(project.id)
        refreshAndroidApkInfo()
        return true
    }

    fun cleanAndroidProject() {
        performAndroidAction(AndroidAction.CLEAN)
    }

    private fun runLatestAndroidApk() {
        val project = _state.value.activeProject ?: return
        val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
        val apk = root?.let(::findDebugApk)
        if (apk == null) {
            _state.update { it.copy(toastMessage = "Build an APK before running the app") }
            return
        }
        if (AndroidAppInstaller.openIfAlreadyInstalled(getApplication(), apk)) {
            androidLaunchAtMillis = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    androidBuildAction = AndroidAction.RUN,
                    androidBuildStage = AndroidBuildStage.COMPLETE,
                    androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                    androidBuildMessage = "App launched without rebuilding",
                    androidBuildStartedAtMillis = now,
                    androidBuildFinishedAtMillis = now,
                    toastMessage = "App launched",
                )
            }
        } else {
            beginAndroidInstall(apk, launch = true, action = AndroidAction.RUN)
        }
    }

    private fun installLatestAndroidApk(launch: Boolean, action: AndroidAction) {
        val project = _state.value.activeProject ?: return
        val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
        val apk = root?.let(::findDebugApk)
        if (apk == null) {
            _state.update { it.copy(toastMessage = "Build an APK before installing it") }
            return
        }
        beginAndroidInstall(apk, launch, action)
    }

    private fun beginAndroidInstall(apk: File, launch: Boolean, action: AndroidAction) {
        if (_state.value.androidBuildRunning && pendingAndroidInstallId != null) return
        val operationId = UUID.randomUUID().toString()
        pendingAndroidInstallId = operationId
        pendingAndroidInstallLaunch = launch
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                androidBuildRunning = true,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.INSTALLING,
                androidBuildPhase = AndroidBuildPhase.BUILDING,
                androidBuildMessage = if (launch) "Installing APK before launch…" else "Installing APK…",
                androidBuildIssues = emptyList(),
                androidBuildStartedAtMillis = it.androidBuildStartedAtMillis ?: now,
                androidBuildFinishedAtMillis = null,
                androidBuildApkPath = apk.absolutePath,
                androidBuildApkSizeBytes = apk.length(),
            )
        }
        runCatching {
            AndroidAppInstaller.install(getApplication(), apk, launchAfterInstall = launch, operationId = operationId)
        }.onFailure { error ->
            pendingAndroidInstallId = null
            pendingAndroidInstallLaunch = false
            _state.update {
                it.copy(
                    androidBuildRunning = false,
                    androidBuildPhase = AndroidBuildPhase.FAILED,
                    androidBuildStage = AndroidBuildStage.FAILED,
                    androidBuildMessage = error.message ?: "Could not start APK installation",
                    androidBuildIssues = listOf(AndroidBuildIssue(title = "Installation failed", detail = error.message ?: "Could not start Android's installer")),
                )
            }
        }
    }

    fun cancelAndroidBuild() {
        if (!_state.value.androidBuildRunning) return
        androidBuildStopRequested = true
        _state.update {
            it.copy(
                androidBuildPhase = AndroidBuildPhase.CANCELLING,
                androidBuildStage = AndroidBuildStage.CANCELLING,
                androidBuildMessage = "Stopping Gradle safely…",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            androidBuildProcess?.destroy()
            delay(500)
            if (androidBuildProcess?.isAlive == true) androidBuildProcess?.destroyForcibly()
        }
    }

    private fun startAndroidGradleTask(task: String, action: AndroidAction) {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Wait for Claude to finish creating the project before building.") }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the project terminal command to finish before building.") }
            return
        }
        val startedAt = System.currentTimeMillis()
        androidBuildStopRequested = false
        _state.update {
            it.copy(
                androidBuildRunning = true,
                androidBuildPhase = AndroidBuildPhase.PREPARING,
                androidBuildAction = action,
                androidBuildStage = AndroidBuildStage.PREPARING,
                androidBuildIssues = emptyList(),
                androidBuildMessage = if (task == "clean") "Preparing clean…" else "Preparing Android build…",
                androidBuildLog = "",
                androidBuildStartedAtMillis = startedAt,
                androidBuildFinishedAtMillis = null,
                androidBuildApkPath = null,
                androidBuildApkSizeBytes = null,
                toastMessage = null,
            )
        }
        saveAndroidBuildRecord(project.id)
        startAndroidBuildForegroundService(project, task)
        viewModelScope.launch(Dispatchers.IO) {
            var buildWorkspace: File? = null
            var finalOutput = ""
            var finalExitCode = -1
            runCatching {
                val installed = installer.installedRuntime()
                val workspace = findAndroidProjectRoot(projectWorkspaceRoot(project))
                    ?: error("No Android Gradle project found yet. Ask Claude to create it, then wait for the task to finish.")
                buildWorkspace = workspace
                val command = androidGradleCommand(workspace, task)
                _state.update {
                    it.copy(
                        androidBuildPhase = AndroidBuildPhase.BUILDING,
                        androidBuildStage = AndroidBuildStage.RESOLVING,
                        androidBuildMessage = if (task == "clean") "Cleaning project…" else "Running assembleDebug…",
                        androidBuildLog = "$ $command\n",
                    )
                }
                val process = installer.process(
                    installed.proot, installed.rootfs, workspace, emptyMap(),
                    listOf("/usr/bin/bash", "-lc", command),
                    projectGuestRoot(project),
                )
                androidBuildProcess = process
                RuntimeTaskController.stopAction = ::cancelAndroidBuild
                val native = process as? NativeSpawnProcess ?: error("Unsupported Android build process")
                var offset = 0L
                while (process.isAlive || native.outputFile.length() > offset) {
                    val available = native.outputFile.length() - offset
                    if (available <= 0L) {
                        Thread.sleep(75)
                        continue
                    }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(native.outputFile, "r").use { file ->
                        file.seek(offset)
                        file.read(bytes)
                    }
                    if (count > 0) {
                        offset += count
                        val chunk = sanitizeTerminalOutput(bytes.decodeToString(0, count))
                        _state.update { current ->
                            val nextStage = inferAndroidBuildStage(chunk, current.androidBuildStage)
                            current.copy(
                                androidBuildLog = (current.androidBuildLog + chunk).takeLast(MAX_ANDROID_BUILD_LOG),
                                androidBuildStage = nextStage,
                                androidBuildMessage = androidStageMessage(nextStage),
                            )
                        }
                    }
                }
                val exitCode = process.waitFor()
                val buildOutput = sanitizeTerminalOutput(native.outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES))
                finalExitCode = exitCode
                finalOutput = buildOutput
                if (androidBuildStopRequested) throw java.util.concurrent.CancellationException("Build cancelled")
                check(exitCode == 0) {
                    diagnoseAndroidBuildFailure(buildOutput, exitCode)
                }
                if (task == "clean") null else {
                    _state.update { it.copy(androidBuildStage = AndroidBuildStage.FINDING_APK, androidBuildMessage = "Finding debug APK…") }
                    findDebugApk(workspace) ?: error("Gradle finished but no debug APK was found")
                }
            }.onSuccess { apk ->
                val shouldInstallAndRun = apk != null && action == AndroidAction.BUILD_AND_RUN
                val message = if (apk == null) "Project cleaned successfully" else "Debug APK built successfully"
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = shouldInstallAndRun,
                            androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                            androidBuildStage = if (shouldInstallAndRun) AndroidBuildStage.INSTALLING else AndroidBuildStage.COMPLETE,
                            androidBuildMessage = message,
                            androidBuildIssues = emptyList(),
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            androidBuildApkPath = apk?.absolutePath,
                            androidBuildApkSizeBytes = apk?.length(),
                            toastMessage = message,
                        )
                    }
                    saveAndroidBuildRecord(project.id)
                    refreshAndroidApkInfo()
                }
                finishAndroidBuildForegroundService(project, message, failed = false)
                if (shouldInstallAndRun) beginAndroidInstall(apk!!, launch = true, action = action)
            }.onFailure { error ->
                val cancelled = error is java.util.concurrent.CancellationException || androidBuildStopRequested
                val message = if (cancelled) "Android build cancelled" else error.message ?: "Could not build APK"
                val issues = if (cancelled) emptyList() else buildWorkspace?.let {
                    parseAndroidBuildIssues(finalOutput.ifBlank { message }, it, finalExitCode)
                }.orEmpty().ifEmpty { listOf(AndroidBuildIssue(title = "Build failed", detail = message)) }
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildPhase = if (cancelled) AndroidBuildPhase.CANCELLED else AndroidBuildPhase.FAILED,
                            androidBuildStage = if (cancelled) AndroidBuildStage.CANCELLED else AndroidBuildStage.FAILED,
                            androidBuildMessage = message,
                            androidBuildIssues = issues,
                            androidBuildFinishedAtMillis = System.currentTimeMillis(),
                            toastMessage = message,
                        )
                    }
                    saveAndroidBuildRecord(project.id)
                }
                finishAndroidBuildForegroundService(project, message, failed = !cancelled, cancelled = cancelled)
            }.also {
                androidBuildProcess = null
                androidBuildStopRequested = false
                RuntimeTaskController.stopAction = null
                steeringToFollowUp = false
                if (pendingFollowUps.isNotEmpty()) {
                    viewModelScope.launch {
                        delay(150)
                        if (!_state.value.isRunning && !_state.value.androidBuildRunning && !_state.value.projectTerminalRunning) {
                            startNextFollowUp()
                        }
                    }
                }
            }
        }
    }

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)

    private fun androidStageMessage(stage: AndroidBuildStage): String = when (stage) {
        AndroidBuildStage.PREPARING -> "Preparing Android build…"
        AndroidBuildStage.RESOLVING -> "Resolving dependencies…"
        AndroidBuildStage.COMPILING -> "Compiling source code…"
        AndroidBuildStage.RESOURCES -> "Processing Android resources…"
        AndroidBuildStage.PACKAGING -> "Packaging debug APK…"
        AndroidBuildStage.FINDING_APK -> "Finding debug APK…"
        AndroidBuildStage.INSTALLING -> "Installing APK…"
        AndroidBuildStage.LAUNCHING -> "Launching app…"
        AndroidBuildStage.COMPLETE -> "Complete"
        AndroidBuildStage.CANCELLING -> "Stopping Gradle safely…"
        AndroidBuildStage.CANCELLED -> "Android build cancelled"
        AndroidBuildStage.FAILED -> "Android operation failed"
        AndroidBuildStage.IDLE -> "Ready"
    }

    private fun startAndroidBuildForegroundService(project: Project, task: String) {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                .putExtra(RuntimeExecutionService.EXTRA_TITLE, if (task == "clean") "Cleaning Android project" else "Building Android app")
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, if (task == "clean") "Running Gradle clean" else "Running Gradle assembleDebug")
                .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
        )
    }

    private fun finishAndroidBuildForegroundService(
        project: Project,
        detail: String,
        failed: Boolean,
        cancelled: Boolean = false,
    ) {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), RuntimeExecutionService::class.java)
                    .setAction(
                        when {
                            cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                            failed -> RuntimeExecutionService.ACTION_FAILED
                            else -> RuntimeExecutionService.ACTION_COMPLETE
                        },
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }
    }

    private fun androidBuildRecordFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "android-builds/$projectId.json")

    private fun saveAndroidBuildRecord(projectId: String) {
        val state = _state.value
        runCatching {
            androidBuildRecordFile(projectId).apply {
                parentFile?.mkdirs()
                writeText(
                    JSONObject()
                        .put("phase", state.androidBuildPhase.name)
                        .put("message", state.androidBuildMessage)
                        .put("log", state.androidBuildLog.takeLast(MAX_ANDROID_BUILD_LOG))
                        .put("startedAtMillis", state.androidBuildStartedAtMillis)
                        .put("finishedAtMillis", state.androidBuildFinishedAtMillis)
                        .put("apkPath", state.androidBuildApkPath)
                        .put("apkSizeBytes", state.androidBuildApkSizeBytes)
                        .put("action", state.androidBuildAction.name)
                        .put("stage", state.androidBuildStage.name)
                        .put("issues", JSONArray().apply {
                            state.androidBuildIssues.forEach { issue ->
                                put(JSONObject()
                                    .put("severity", issue.severity.name)
                                    .put("title", issue.title)
                                    .put("detail", issue.detail)
                                    .put("suggestion", issue.suggestion)
                                    .put("filePath", issue.filePath)
                                    .put("line", issue.line)
                                    .put("column", issue.column))
                            }
                        })
                        .toString(),
                )
            }
        }
    }

    private fun loadAndroidBuildRecord(projectId: String): AndroidBuildRecord {
        val file = androidBuildRecordFile(projectId)
        if (!file.isFile) return AndroidBuildRecord()
        return runCatching {
            val json = JSONObject(file.readText())
            val phase = runCatching { AndroidBuildPhase.valueOf(json.optString("phase")) }
                .getOrDefault(AndroidBuildPhase.IDLE)
                .let { if (it in setOf(AndroidBuildPhase.PREPARING, AndroidBuildPhase.BUILDING, AndroidBuildPhase.CANCELLING)) AndroidBuildPhase.CANCELLED else it }
            AndroidBuildRecord(
                phase = phase,
                message = if (phase == AndroidBuildPhase.CANCELLED) "Previous Android build was interrupted" else json.optString("message").takeIf(String::isNotBlank),
                log = json.optString("log"),
                startedAtMillis = json.optLong("startedAtMillis").takeIf { it > 0L },
                finishedAtMillis = json.optLong("finishedAtMillis").takeIf { it > 0L },
                apkPath = json.optString("apkPath").takeIf { it.isNotBlank() && File(it).isFile },
                apkSizeBytes = json.optLong("apkSizeBytes").takeIf { it > 0L },
                action = runCatching { AndroidAction.valueOf(json.optString("action")) }.getOrDefault(AndroidAction.NONE),
                stage = runCatching { AndroidBuildStage.valueOf(json.optString("stage")) }.getOrDefault(
                    when (phase) {
                        AndroidBuildPhase.SUCCEEDED -> AndroidBuildStage.COMPLETE
                        AndroidBuildPhase.FAILED -> AndroidBuildStage.FAILED
                        AndroidBuildPhase.CANCELLED -> AndroidBuildStage.CANCELLED
                        else -> AndroidBuildStage.IDLE
                    },
                ),
                issues = json.optJSONArray("issues")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let { item ->
                            AndroidBuildIssue(
                                severity = runCatching { com.jarves.mh.runtime.AndroidIssueSeverity.valueOf(item.optString("severity")) }
                                    .getOrDefault(com.jarves.mh.runtime.AndroidIssueSeverity.ERROR),
                                title = item.optString("title"),
                                detail = item.optString("detail"),
                                suggestion = item.optString("suggestion").takeIf(String::isNotBlank),
                                filePath = item.optString("filePath").takeIf(String::isNotBlank),
                                line = item.optInt("line").takeIf { it > 0 },
                                column = item.optInt("column").takeIf { it > 0 },
                            )
                        }
                    }
                }.orEmpty(),
            )
        }.getOrDefault(AndroidBuildRecord())
    }

    fun refreshAndroidDashboard() {
        refreshAndroidApkInfo()
        refreshAndroidHealth()
    }

    fun refreshAndroidApkInfo() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching {
                val projectRoot = findAndroidProjectRoot(projectWorkspaceRoot(project)) ?: return@runCatching null
                val apk = findDebugApk(projectRoot) ?: return@runCatching null
                val app = getApplication<Application>()
                @Suppress("DEPRECATION")
                val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                val packageName = archive?.packageName ?: AndroidAppInstaller.packageName(app, apk)
                AndroidApkInfo(
                    path = apk.absolutePath,
                    fileName = apk.name,
                    sizeBytes = apk.length(),
                    builtAtMillis = apk.lastModified(),
                    packageName = packageName,
                    versionName = archive?.versionName,
                    versionCode = archive?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
                    },
                    minSdk = archive?.applicationInfo?.minSdkVersion,
                    targetSdk = archive?.applicationInfo?.targetSdkVersion,
                    stale = isDebugApkStale(projectRoot, apk),
                    installed = packageName?.let { AndroidAppInstaller.isInstalled(app, it) } == true,
                    installedMatches = AndroidAppInstaller.installedMatches(app, apk),
                )
            }.getOrNull()
            _state.update { it.copy(androidApkInfo = info) }
        }
    }

    fun shareAndroidApk() {
        val info = _state.value.androidApkInfo ?: return
        val file = File(info.path).takeIf(File::isFile) ?: return
        runCatching {
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Share APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not share APK") } }
    }

    fun saveAndroidApk(uri: Uri) {
        val info = _state.value.androidApkInfo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = File(info.path).takeIf(File::isFile) ?: error("APK is unavailable")
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().buffered().use { it.copyTo(output) }
                } ?: error("The selected location could not be opened")
            }
            _state.update { it.copy(toastMessage = if (result.isSuccess) "APK saved" else result.exceptionOrNull()?.message ?: "Could not save APK") }
        }
    }

    fun openInstalledAndroidAppDetails() {
        val packageName = _state.value.androidApkInfo?.packageName ?: return
        getApplication<Application>().startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun uninstallAndroidApp() {
        val packageName = _state.value.androidApkInfo?.packageName ?: return
        getApplication<Application>().startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAndroidIssue(issue: AndroidBuildIssue) {
        val project = _state.value.activeProject ?: return
        val path = issue.filePath ?: return
        val root = projectWorkspaceRoot(project)
        val candidate = File(root, path)
        val resolved = when {
            candidate.isFile -> candidate
            else -> root.walkTopDown().firstOrNull { it.isFile && it.name == File(path).name }
        } ?: return
        val relative = resolved.relativeTo(root).invariantSeparatorsPath
        _state.update { it.copy(openedFile = OpenedFileState(path = relative, targetLine = issue.line, targetColumn = issue.column)) }
        loadOpenedFile(project, relative, issue.line, issue.column)
    }

    fun refreshAndroidHealth() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidHealthRunning) return
        _state.update { it.copy(androidHealthRunning = true, androidHealthChecks = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val checks = mutableListOf<AndroidHealthCheck>()
            val toolsInstalled = installer.isStackInstalled(DevStack.ANDROID)
            checks += AndroidHealthCheck(
                "tools", "Android development tools",
                if (toolsInstalled) "Android SDK, Gradle, and Java are installed." else "The Android development stack is not installed.",
                if (toolsInstalled) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                if (toolsInstalled) AndroidHealthFix.NONE else AndroidHealthFix.INSTALL_TOOLS,
            )
            val root = findAndroidProjectRoot(projectWorkspaceRoot(project))
            checks += AndroidHealthCheck(
                "project", "Android project",
                root?.let { "Project detected at ${it.name}." } ?: "No Android Gradle project was detected.",
                if (root != null) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
            )
            val launcher = root?.let { File(it, "gradlew").isFile || File(it, "build.gradle").isFile || File(it, "build.gradle.kts").isFile } == true
            checks += AndroidHealthCheck(
                "gradle", "Gradle launcher",
                if (launcher) "A Gradle launcher is available." else "No Gradle wrapper or build file was found.",
                if (launcher) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
            )
            if (toolsInstalled && root != null) {
                val (_, output) = runAndroidGuestCommand(project, "java -version 2>&1; gradle --version; aapt2 version")
                val ok = "version \"17" in output && "gradle" in output.lowercase() && "aapt2" in output.lowercase()
                checks += AndroidHealthCheck(
                    "toolchain", "Java, Gradle, and AAPT2",
                    if (ok) "Java 17, Gradle, and AAPT2 responded successfully." else "One or more Android build tools could not be verified.",
                    if (ok) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                    if (ok) AndroidHealthFix.NONE else AndroidHealthFix.INSTALL_TOOLS,
                )
                val compileSdk = root.walkTopDown().maxDepth(3).filter { it.isFile && it.name in setOf("build.gradle", "build.gradle.kts") }
                    .mapNotNull { Regex("compileSdk(?:Version)?\\s*(?:=|\\s)\\s*(\\d+)").find(it.readText())?.groupValues?.get(1)?.toIntOrNull() }
                    .maxOrNull()
                val platformAvailable = compileSdk == null || File(installer.installedRuntime().rootfs, "root/android-sdk/platforms/android-$compileSdk/android.jar").isFile
                checks += AndroidHealthCheck(
                    "sdk", "Compile SDK",
                    when { compileSdk == null -> "The compile SDK could not be determined."; platformAvailable -> "Android API $compileSdk is installed."; else -> "Android API $compileSdk is required but unavailable." },
                    when { compileSdk == null -> AndroidHealthStatus.WARNING; platformAvailable -> AndroidHealthStatus.PASSED; else -> AndroidHealthStatus.FAILED },
                    if (!platformAvailable) AndroidHealthFix.INSTALL_TOOLS else AndroidHealthFix.NONE,
                )
            }
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            val online = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            checks += AndroidHealthCheck(
                "network", "Dependency network",
                if (online) "An internet connection is available." else "No internet connection is available; uncached dependencies may fail.",
                if (online) AndroidHealthStatus.PASSED else AndroidHealthStatus.WARNING,
                if (online) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_NETWORK_SETTINGS,
            )
            val freeBytes = StatFs(app.filesDir.absolutePath).availableBytes
            val enoughStorage = freeBytes >= 1_500L * 1024L * 1024L
            checks += AndroidHealthCheck(
                "storage", "Free storage",
                "${freeBytes / 1024 / 1024} MB available for builds and APK installation.",
                if (enoughStorage) AndroidHealthStatus.PASSED else AndroidHealthStatus.WARNING,
                if (enoughStorage) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_STORAGE_SETTINGS,
            )
            val installAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()
            checks += AndroidHealthCheck(
                "install", "APK installation permission",
                if (installAllowed) "PocketDev can request APK installation." else "Allow PocketDev to install unknown apps.",
                if (installAllowed) AndroidHealthStatus.PASSED else AndroidHealthStatus.FAILED,
                if (installAllowed) AndroidHealthFix.NONE else AndroidHealthFix.OPEN_INSTALL_SETTINGS,
            )
            val apk = root?.let(::findDebugApk)
            checks += AndroidHealthCheck(
                "apk", "Debug APK",
                when { apk == null -> "No debug APK has been built yet."; isDebugApkStale(root, apk) -> "The latest APK is older than the project source."; else -> "The debug APK matches the current project source." },
                when { apk == null -> AndroidHealthStatus.WARNING; isDebugApkStale(root, apk) -> AndroidHealthStatus.WARNING; else -> AndroidHealthStatus.PASSED },
            )
            val logAccess = hasLogcatAccess()
            checks += AndroidHealthCheck(
                "logcat", "Logcat access",
                if (logAccess) "Target-app logs can be read." else "Grant READ_LOGS with ADB to view target-app logs.",
                if (logAccess) AndroidHealthStatus.PASSED else AndroidHealthStatus.WARNING,
                if (logAccess) AndroidHealthFix.NONE else AndroidHealthFix.COPY_LOGCAT_COMMAND,
            )
            _state.update { it.copy(androidHealthRunning = false, androidHealthChecks = checks) }
        }
    }

    fun performAndroidHealthFix(fix: AndroidHealthFix) {
        val app = getApplication<Application>()
        when (fix) {
            AndroidHealthFix.INSTALL_TOOLS -> installDevStack(DevStack.ANDROID)
            AndroidHealthFix.OPEN_INSTALL_SETTINGS -> app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.OPEN_NETWORK_SETTINGS -> app.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.OPEN_STORAGE_SETTINGS -> app.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AndroidHealthFix.CLEAN -> cleanAndroidProject()
            AndroidHealthFix.REFRESH -> refreshAndroidHealth()
            AndroidHealthFix.COPY_LOGCAT_COMMAND, AndroidHealthFix.NONE -> Unit
        }
    }

    private fun runAndroidGuestCommand(project: Project, command: String): Pair<Int, String> {
        return runCatching {
            val runtime = installer.installedRuntime()
            val workspace = projectWorkspaceRoot(project)
            val outputFile = File(getApplication<Application>().cacheDir, "android-health-${System.nanoTime()}.log")
            try {
                val process = installer.process(
                    runtime.proot, runtime.rootfs, workspace, emptyMap(),
                    listOf("/usr/bin/bash", "-lc", command), projectGuestRoot(project), outputFile = outputFile,
                )
                val exit = process.waitFor()
                exit to sanitizeTerminalOutput(outputFile.readTailText(64 * 1024)).trim()
            } finally { outputFile.delete() }
        }.getOrElse { -1 to (it.message ?: "Command failed") }
    }

    private fun hasLogcatAccess(): Boolean {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) return true
        return runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(800, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    fun startAndroidLogcat() {
        if (androidLogcatJob?.isActive == true) return
        val info = _state.value.androidApkInfo
        val packageName = info?.packageName
        if (packageName.isNullOrBlank() || info.installed.not()) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = "Install the APK before opening Logcat.")) }
            return
        }
        if (!hasLogcatAccess()) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = LOGCAT_GRANT_COMMAND, packageName = packageName)) }
            return
        }
        val app = getApplication<Application>()
        val uid = runCatching { app.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
        if (uid == null) {
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = false, message = "The installed app could not be found.")) }
            return
        }
        androidLogcatJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val root = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED
                val since = androidLaunchAtMillis?.let {
                    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it))
                } ?: "1"
                val logCommand = "/system/bin/logcat --uid=$uid -v threadtime -T '$since'"
                val process = if (root) ProcessBuilder("su", "-c", logCommand).redirectErrorStream(true).start()
                    else ProcessBuilder("/system/bin/logcat", "--uid=$uid", "-v", "threadtime", "-T", since).redirectErrorStream(true).start()
                androidLogcatProcess = process
                _state.update { it.copy(androidLogcat = it.androidLogcat.copy(available = true, running = true, paused = false, message = null, packageName = packageName)) }
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        if (!_state.value.androidLogcat.paused) appendAndroidLogLine(raw)
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = error.message ?: "Logcat stopped")) }
            }
            androidLogcatProcess = null
            _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false)) }
        }
    }

    private fun appendAndroidLogLine(raw: String) {
        val levelChar = Regex("\\s([VDIWEF])\\s").find(raw)?.groupValues?.getOrNull(1)
        val level = when (levelChar) {
            "D" -> AndroidLogLevel.DEBUG; "I" -> AndroidLogLevel.INFO; "W" -> AndroidLogLevel.WARNING
            "E" -> AndroidLogLevel.ERROR; "F" -> AndroidLogLevel.FATAL; else -> AndroidLogLevel.VERBOSE
        }
        val location = Regex("([A-Za-z0-9_]+\\.(?:kt|java)):(\\d+)").find(raw)
        _state.update { current ->
            val line = AndroidLogLine(
                id = System.nanoTime(), text = raw, level = level,
                filePath = location?.groupValues?.getOrNull(1), line = location?.groupValues?.getOrNull(2)?.toIntOrNull(),
            )
            current.copy(androidLogcat = current.androidLogcat.copy(lines = (current.androidLogcat.lines + line).takeLast(MAX_LOGCAT_LINES)))
        }
    }

    fun stopAndroidLogcat() {
        androidLogcatProcess?.destroy()
        androidLogcatJob?.cancel()
        androidLogcatJob = null
        _state.update { it.copy(androidLogcat = it.androidLogcat.copy(running = false)) }
    }

    fun toggleAndroidLogcatPause() = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(paused = !it.androidLogcat.paused)) }
    fun clearAndroidLogcat() = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList())) }
    fun setAndroidLogcatQuery(value: String) = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(query = value)) }
    fun setAndroidLogcatLevel(value: AndroidLogLevel) = _state.update { it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = value)) }

    fun openAndroidLogLine(line: AndroidLogLine) {
        val fileName = line.filePath ?: return
        openAndroidIssue(AndroidBuildIssue(title = "Logcat", detail = line.text, filePath = fileName, line = line.line))
    }


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.jarves.mh.ui.theme.AppThemeMode.DARK) {
            com.jarves.mh.ui.theme.AppThemeMode.LIGHT
        } else {
            com.jarves.mh.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.jarves.mh.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setLanguage(code: String) {
        preferences.languageCode = code
        _state.update { it.copy(languageCode = code) }
    }

    fun finishInitialLanguageSetup() {
        preferences.initialLanguageSelected = true
        _state.update { it.copy(initialLanguageSelected = true) }
    }

    fun reopenInitialLanguageSetup() {
        preferences.initialLanguageSelected = false
        _state.update { it.copy(initialLanguageSelected = false) }
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    fun getSavedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = vault.list(kind.name)

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> {
        vault.add(kind.name, name, secret)
        val keys = vault.list(kind.name)
        refreshActiveApiKey(kind)
        return keys
    }

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.activate(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.remove(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    private fun refreshActiveApiKey(kind: ProviderKind) {
        if (_state.value.provider.kind != kind) return
        val keys = vault.list(kind.name)
        _state.update { current ->
            current.copy(
                activeApiKeyName = keys.firstOrNull(ApiKeyInfo::isActive)?.name,
                provider = current.provider.copy(hasSecret = keys.isNotEmpty()),
            )
        }
    }

    /** Keeps both agent bridges mapped to the same workspace root; the active one is used. */
    private fun configureBridgeRoots(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
    }

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "ARM64 device required",
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            return
        }
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        _state.update { current ->
            current.copy(
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
                installedAgentVersions = if (installed) installer.installedAgentVersions() else emptyMap(),
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
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
        setupCompletionHandled = false
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Preparing your private coding workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
                showDetailedSetupProgress = true,
            )
        }
        resumeRuntimeSetupService()
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else {
            _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = _state.value.selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, _state.value.agentKind.name),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> _state.update {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                viewModelScope.launch {
                    val installedStacks = withContext(Dispatchers.IO) { installer.installedStacks() }
                    _state.update { it.copy(installedDevStacks = installedStacks) }
                    if (preferences.onboardingComplete) {
                        initializeRuntime()
                    } else {
                        _state.update {
                            it.copy(
                                startupStage = StartupStage.MODEL_SETUP,
                                startupProgress = 1f,
                                startupBytes = null,
                                startupIndeterminate = false,
                            )
                        }
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> _state.update {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "Setup paused",
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startupStage = StartupStage.INITIALIZING,
                startupProgress = 0.05f,
                startupMessage = "Opening your private workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Opening your private workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    _state.update { current ->
                        current.copy(
                            startupProgress = 0.05f + progress.fraction * 0.95f,
                            startupMessage = progress.message,
                            startupBytes = null,
                            startupLogs = mergeStartupLog(current.startupLogs, progress),
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
            _state.update {
                it.copy(
                    startupStage = StartupStage.READY,
                    startupProgress = 1f,
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
            pingApi()
            checkForAppUpdate()
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < 24L * 60L * 60L * 1000L) return
        viewModelScope.launch(Dispatchers.IO) {
            val update = runCatching { appUpdater().check() }.getOrNull()
            preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
            if (update != null) {
                _state.update {
                    it.copy(appUpdate = update, appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null)
                }
            }
        }
    }

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    fun installAppUpdate() {
        val info = _state.value.appUpdate ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getApplication<Application>().packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED) }
            return
        }
        if (_state.value.appUpdateStatus == AppUpdateStatus.DOWNLOADING) return
        _state.update {
            it.copy(appUpdateStatus = AppUpdateStatus.DOWNLOADING, appUpdateDownloadedBytes = 0L, appUpdateTotalBytes = info.sizeBytes, appUpdateError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appUpdater().download(info) { downloaded, total ->
                    _state.update { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                }
            }.onSuccess { apk ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING) }
                runCatching { AndroidAppInstaller.install(getApplication(), apk) }.onFailure { error ->
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Could not start the Android installer") }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Update download failed") }
            }
        }
    }

    fun dismissAppUpdateError() {
        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            "Connect to Wi-Fi or mobile data, then try again. Internet is required to finish the first-time setup."
        } else {
            error.message?.take(300) ?: "Something went wrong while preparing Mobile Harness. Please try again."
        }
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name),
        )
        preferences.saveProvider(saved, _state.value.agentKind)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshActiveApiKey(profile.kind)
        pingApi()
    }

    fun finishAntigravityOnboarding() {
        check(_state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
            "Sign in to Antigravity first"
        }
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
    }

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) {
        selectAgent(kind)
        _state.update {
            it.copy(
                startupStage = if (installer.isAgentInstalled(kind)) {
                    StartupStage.MODEL_SETUP
                } else {
                    StartupStage.SETUP_REQUIRED
                },
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        _state.update { it.copy(backgroundSetupComplete = true) }
    }

    /** Called from the first-launch setup screen; persists the agent choice for setup and Settings. */
    fun selectAgent(kind: AgentKind) {
        if (_state.value.agentKind == kind) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        val selectingInitialAgent = !preferences.runtimeSetupComplete
        preferences.agentKind = kind.stableId
        if (selectingInitialAgent) preferences.primaryAgentKind = kind.stableId
        _state.update { current ->
            preferences.saveProvider(current.provider, current.agentKind)
            val provider = preferences.loadProvider(vault, kind)
            current.copy(
                agentKind = kind,
                primaryAgentKind = if (selectingInitialAgent) kind else current.primaryAgentKind,
                provider = provider,
                activeApiKeyName = vault.list(provider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name,
                // Ping results belong to the previous agent; never leak them across.
                apiPingStatus = ApiPingStatus.IDLE,
                apiPingMessage = null,
            )
        }
    }

    /** Installs the other agent on demand (Settings) with live progress, then switches to it. */
    fun installAgent(kind: AgentKind) {
        if (_state.value.agentInstalling != null) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = "Preparing ${kind.title}…",
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    agentRegistry.require(kind).install(installer) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentBytesPerSecond
                        _state.update { current ->
                            current.copy(
                                agentMessage = progress.message,
                                agentProgress = progress.fraction.coerceIn(0f, 1f),
                                agentDownloadedBytes = bytes ?: current.agentDownloadedBytes,
                                agentTotalBytes = progress.totalBytes ?: current.agentTotalBytes,
                                agentBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            result.onSuccess {
                if (kind == AgentKind.DEEPSEEK_HARNESS) preferences.dshVersion = installer.dshVersion
                selectAgent(kind)
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentInstalling = null,
                    agentProgress = 0f,
                    agentDownloadedBytes = null,
                    agentTotalBytes = null,
                    agentBytesPerSecond = null,
                    agentMessage = result.fold(
                        onSuccess = { "${kind.title} is ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${kind.title}" },
                    ),
                )
            }
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = "Checking official agent releases…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrDefault(emptyMap()),
                    agentUpdatesChecking = false,
                    agentUpdateMessage = result.fold(
                        onSuccess = { updates -> if (updates.isEmpty()) "All installed agents are up to date" else "${updates.size} agent update${if (updates.size == 1) "" else "s"} available" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not check agent updates" },
                    ),
                )
            }
        }
    }

    fun updateAgent(kind: AgentKind) {
        val update = _state.value.agentUpdates[kind] ?: return
        if (_state.value.agentUpdating != null || _state.value.agentInstalling != null || _state.value.isRunning) return
        _state.update {
            it.copy(
                agentUpdating = kind,
                agentUpdateMessage = "Preparing ${kind.title} ${update.latestVersion}…",
                agentUpdateProgress = 0f,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.updateAgent(kind, update.latestVersion) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentUpdateBytesPerSecond
                        _state.update {
                            it.copy(
                                agentUpdateMessage = progress.message,
                                agentUpdateProgress = progress.fraction.coerceIn(0f, 1f),
                                agentUpdateDownloadedBytes = bytes ?: it.agentUpdateDownloadedBytes,
                                agentUpdateTotalBytes = progress.totalBytes ?: it.agentUpdateTotalBytes,
                                agentUpdateBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentUpdates = if (result.isSuccess) current.agentUpdates - kind else current.agentUpdates,
                    agentUpdating = null,
                    agentUpdateMessage = result.fold(
                        onSuccess = { "${kind.title} updated to ${update.latestVersion}" },
                        onFailure = { error -> error.message?.take(220) ?: "Could not update ${kind.title}" },
                    ),
                    agentUpdateProgress = if (result.isSuccess) 1f else 0f,
                    agentUpdateDownloadedBytes = null,
                    agentUpdateTotalBytes = null,
                    agentUpdateBytesPerSecond = null,
                )
            }
        }
    }

    fun startAntigravityLogin() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit the code") } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun setAntigravityModel(model: String) {
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
            )
        }
    }

    fun setAntigravityEffort(effort: String) {
        if (effort !in setOf("low", "medium", "high")) return
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = "This model does not offer ${effort.replaceFirstChar(Char::uppercase)} reasoning") }
            return
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
    }

    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading || !installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    emptyMap(),
                    listOf(com.jarves.mh.runtime.RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                )
                while (process.isAlive) delay(50)
                check(process.waitFor() == 0) { "Could not list Antigravity models" }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { "Antigravity returned no models" } }
            }
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    result.fold(
                        onSuccess = { models ->
                            val preferred = antigravityModelWithEffort(
                                current.antigravityModel,
                                current.antigravityEffort,
                            )?.takeIf(models::contains)
                            val selected = preferred
                                ?: current.antigravityModel.takeIf(models::contains)
                                ?: models.first()
                            val selectedEffort = antigravityEffortFromModel(selected) ?: current.antigravityEffort
                            preferences.antigravityModel = selected
                            preferences.antigravityEffort = selectedEffort
                            current.copy(
                                antigravityModelsLoading = false,
                                antigravityModels = models,
                                antigravityModel = selected,
                                antigravityEffort = selectedEffort,
                            )
                        },
                        onFailure = { error -> current.copy(
                            antigravityModelsLoading = false,
                            toastMessage = error.message ?: "Could not load Antigravity models",
                        ) },
                    )
                }
            }
        }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = _state.value.selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        _state.update { it.copy(selectedDevStacks = updated) }
    }

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null) return
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Preparing ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureStackInstalled(stack) { progress ->
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        } else {
                            sampleBytes = 0L
                            sampleTime = android.os.SystemClock.elapsedRealtime()
                            latestSpeed = null
                        }
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks + stack else current.installedDevStacks,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || stack == DevStack.WEB) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop running tasks and terminal commands before removing tools") }
            return
        }
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = "Removing ${stack.label}…",
                devStackProgress = 0.1f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.removeStack(stack) { progress ->
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) {
                val selected = _state.value.selectedDevStacks - stack
                preferences.selectedDevStacks = selected.map { it.name }.toSet()
            }
            _state.update { current ->
                current.copy(
                    selectedDevStacks = if (result.isSuccess) current.selectedDevStacks - stack else current.selectedDevStacks,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks - stack else current.installedDevStacks,
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { result.exceptionOrNull()?.message?.take(200) ?: "Could not remove ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { "Could not remove ${stack.label}" },
                    ),
                )
            }
        }
    }

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.jarves.mh.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.validate(
            profile.baseUrl,
            profile.model,
            key,
            providerProtocolForAgent(profile, _state.value.agentKind),
            models,
            profile.openRouterProviderOrder,
            profile.openRouterAllowFallbacks,
        )
    }

    fun pingApi() {
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY) {
            testAntigravityConnection()
            return
        }
        val profile = _state.value.provider
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Sending a minimal test request…") }
        viewModelScope.launch {
            val key = vault.get(profile.kind.name).orEmpty()
            val result = providerApi.validate(
                profile.baseUrl,
                profile.model,
                key,
                providerProtocolForAgent(profile, _state.value.agentKind),
                emptyList(),
                profile.openRouterProviderOrder,
                profile.openRouterAllowFallbacks,
            )
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "API responded successfully")
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    /** Sends a tiny hello to the agy CLI to prove it actually answers. Silent timeout inside. */
    fun testAntigravityConnection() {
        if (_state.value.agentKind != AgentKind.ANTIGRAVITY) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        if (_state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update {
                it.copy(
                    apiPingStatus = ApiPingStatus.FAILED,
                    apiPingMessage = "Antigravity needs Google sign-in",
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Saying hello to Antigravity…") }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = "Antigravity is Working!",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = helloFailureMessage(error.message.orEmpty()))
                }
            }
        }
    }

    private fun helloFailureMessage(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("not installed", true) -> "Antigravity CLI is not installed. Install it from the Coding agent section."
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> "Antigravity needs Google sign-in. Reconnect from the Google connection section."
            value.contains("did not answer", true) -> "Antigravity did not answer. Try again."
            value.isBlank() -> "Antigravity did not answer. Try again."
            else -> value.take(200)
        }
    }

    fun openProject(project: Project) {
        val current = _state.value
        if (current.activeProject?.id == project.id) {
            _state.update {
                it.copy(
                    workspaceVisible = true,
                    readOnlyProject = null,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                )
            }
            return
        }
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) {
            val chats = preferences.loadProjectChats(project.id).ifEmpty {
                listOf(ProjectChat(title = "Main chat"))
            }
            val chat = chats.first()
            _state.update {
                it.copy(
                    readOnlyProject = project,
                    readOnlyProjectChats = chats,
                    readOnlyChatId = chat.id,
                    readOnlyMessages = preferences.loadMessages(project.id, chat.id),
                )
            }
            return
        }
        configureBridgeRoots(project.id, project.rootPath)
        val terminal = loadProjectTerminal(project)
        val suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
        }
        val activeChat = chats.first()
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val androidBuild = loadAndroidBuildRecord(project.id)
        _state.update {
            it.copy(
                activeProject = project,
                workspaceVisible = true,
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = msgs,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = terminal.lines,
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = terminal.cwd,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = suggestedRoot,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
                androidBuildRunning = false,
                androidBuildPhase = androidBuild.phase,
                androidBuildAction = androidBuild.action,
                androidBuildStage = androidBuild.stage,
                androidBuildIssues = androidBuild.issues,
                androidBuildMessage = androidBuild.message,
                androidBuildLog = androidBuild.log,
                androidBuildStartedAtMillis = androidBuild.startedAtMillis,
                androidBuildFinishedAtMillis = androidBuild.finishedAtMillis,
                androidBuildApkPath = androidBuild.apkPath,
                androidBuildApkSizeBytes = androidBuild.apkSizeBytes,
            )
        }
        refreshProjectFiles()
        viewModelScope.launch {
            val pending = activeRuntime().loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        val active = _state.value.activeProject
        persistMessages()
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update {
                it.copy(
                    workspaceVisible = false,
                    toastMessage = when {
                        it.isRunning -> "Task continues in the background"
                        it.androidBuildRunning -> "Android build continues in the background"
                        else -> "Terminal command continues in the background"
                    },
                )
            }
            return
        }

        if (active != null) {
            val chats = preferences.loadProjectChats(active.id)
            val userMessages = chats.sumOf { preferences.loadMessages(active.id, it.id).count { m -> m.fromUser } }
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (userMessages == 0 && userFiles == 0 && !_state.value.isRunning && !_state.value.projectTerminalRunning) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(active.id).delete()
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        _state.update {
            it.copy(
                activeProject = null,
                workspaceVisible = false,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun closeReadOnlyProject() {
        _state.update {
            it.copy(
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
            )
        }
    }

    fun switchReadOnlyChat(chatId: String) {
        val project = _state.value.readOnlyProject ?: return
        if (_state.value.readOnlyProjectChats.none { it.id == chatId }) return
        _state.update {
            it.copy(
                readOnlyChatId = chatId,
                readOnlyMessages = preferences.loadMessages(project.id, chatId),
            )
        }
    }

    fun activateReadOnlyProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val project = _state.value.readOnlyProject ?: return
        closeReadOnlyProject()
        openProject(project)
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }

    fun createProject(name: String) {
        if (name.isBlank()) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = "Starter web project",
            language = "TypeScript",
            slug = slug,
        )
        configureBridgeRoots(project.id, project.rootPath)
        val guestRoot = projectGuestRoot(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceVisible = true,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    fun createAndroidProject(name: String, template: AndroidTemplate) {
        if (name.isBlank()) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = "Stop the active task before creating another project") }
            return
        }
        val cleanName = name.replace(Regex("\\s+"), " ").trim().take(60)
        val baseSlug = projectSlug(cleanName)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = cleanName,
            description = if (template == AndroidTemplate.COMPOSE) "Android · Jetpack Compose" else "Android · Kotlin + XML",
            language = "Kotlin",
            slug = slug,
            type = ProjectType.ANDROID,
            androidTemplate = template,
        )
        val workspace = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        runCatching { AndroidProjectTemplateGenerator.generate(workspace, cleanName, template) }
            .onFailure { error ->
                _state.update { it.copy(toastMessage = error.message ?: "Could not create Android project") }
                return
            }
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update { it.copy(toastMessage = "Android project created. Install Android tools from Settings before building.") }
        }
    }

    fun createQuickProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = "New chat")
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Reading project archive…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported successfully",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(pendingAttachments = listOf(imported.sourceAttachment)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Import failed: ${error.message?.take(180) ?: "Invalid ZIP archive"}",
                    )
                }
            }
        }
    }

    private fun extractImportedProject(uri: Uri): ImportedZipProject {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var archiveName = "Imported project.zip"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    archiveName = cursor.getString(index) ?: archiveName
                }
            }
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId")
        destination.mkdirs()
        val destinationPath = destination.canonicalFile.toPath()
        val availableLimit = (destination.usableSpace * 8L / 10L).coerceAtMost(MAX_IMPORTED_PROJECT_BYTES)
        var extractedBytes = 0L
        var entries = 0
        try {
            val source = resolver.openInputStream(uri) ?: error("The selected ZIP could not be opened")
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { "The ZIP contains too many files" }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { "The ZIP contains an invalid path" }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { "The ZIP contains an unsafe path" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= availableLimit) { "The extracted project is too large for available storage" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { "The ZIP does not contain project files" }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = "General",
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            val nestedRoot = detectNestedProjectRoot(preliminary)
            val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
            val metadata = detectImportedProjectMetadata(projectRoot)
            val safeArchiveName = sanitizeAttachmentName(archiveName).let { name ->
                if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            }
            val archiveFolder = File(projectRoot, ".pocketdev/imports").apply { mkdirs() }
            val archivedSource = File(archiveFolder, safeArchiveName)
            var sourceBytes = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                archivedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sourceBytes += count
                        require(extractedBytes + sourceBytes <= availableLimit) { "The imported project is too large for available storage" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected ZIP could not be preserved")
            val project = preliminary.copy(
                description = metadata.first,
                language = metadata.second,
                rootPath = nestedRoot.orEmpty(),
            )
            return ImportedZipProject(
                project = project,
                sourceAttachment = ChatAttachment(
                    displayName = archiveName.take(120),
                    relativePath = archivedSource.relativeTo(projectRoot).invariantSeparatorsPath,
                    mimeType = "application/zip",
                    sizeBytes = sourceBytes,
                ),
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to "General"
        }
    }

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = "Connect GitHub again") }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    private fun cloneGitRepository(url: String, repositoryName: String?, branch: String?, useGitHubCli: Boolean) {
        if (_state.value.gitCloneRunning || _state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            _state.update { it.copy(toastMessage = error.message ?: "Enter a valid public HTTPS Git URL") }
            return
        }
        _state.update { it.copy(gitCloneRunning = true, gitCloneMessage = "Connecting to Git repository…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
                    val projectId = UUID.randomUUID().toString()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/$projectId").apply { mkdirs() }
                    val output = File(getApplication<Application>().cacheDir, "git-clone-${System.nanoTime()}.log")
                    try {
                        val environment = mutableMapOf(
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_LFS_SKIP_SMUDGE" to "1",
                            "GH_PROMPT_DISABLED" to "1",
                            "GH_NO_UPDATE_NOTIFIER" to "1",
                        )
                        val installed = installer.installedRuntime()
                        val command = if (useGitHubCli && repositoryName != null) {
                            buildList {
                                addAll(listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH, "repo", "clone", repositoryName, ".", "--", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                            }
                        } else {
                            buildList {
                                addAll(listOf("git", "clone", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                                add(normalized)
                                add(".")
                            }
                        }
                        _state.update { it.copy(gitCloneMessage = "Cloning ${repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git")}…") }
                        val process = installer.process(
                            installed.proot,
                            installed.rootfs,
                            workspace,
                            environment,
                            command,
                            guestWorkspacePath = "/workspace/${identity.slug}",
                            outputFile = output,
                        )
                        val exit = process.waitFor()
                        val details = output.readText().trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { "Git clone failed with exit code $exit" } }
                        val metadata = detectImportedProjectMetadata(workspace)
                        Project(
                            id = projectId,
                            name = identity.displayName,
                            description = repositoryName?.let { "GitHub · $it" } ?: "Imported Git repository",
                            language = metadata.second,
                            slug = identity.slug,
                            kind = ProjectKind.QUICK_PROJECT,
                        )
                    } catch (error: Throwable) {
                        workspace.deleteRecursively()
                        throw error
                    } finally {
                        output.delete()
                    }
                }
            }
            result.onSuccess { project ->
                val chat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(chat))
                _state.update { current -> current.copy(projects = listOf(project) + current.projects) }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = "Repository cloned successfully") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = "Clone failed: ${error.message?.lineSequence()?.lastOrNull()?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS Git URLs are supported" }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { "Enter a valid HTTPS Git URL without credentials" }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { "Local Git URLs are not supported" }
        require(uri.path.count { it == '/' } >= 2) { "The URL must identify a Git repository" }
        return uri.toASCIIString()
    }

    fun startGitHubLogin() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Preparing official GitHub sign-in…") }
        startGitHubForegroundOperation()
        githubAuthJob = viewModelScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.ensureGitHubCliInstalled { progress ->
                        _state.update { it.copy(githubMessage = progress.message) }
                    }
                    val runtime = installer.installedRuntime()
                    val outputFile = File(getApplication<Application>().cacheDir, "github-auth-${System.nanoTime()}.log")
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                    val process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        githubCliEnvironment(),
                        listOf(
                            RuntimeInstaller.GITHUB_CLI_GUEST_PATH,
                            "auth", "login",
                            "--hostname", "github.com",
                            "--git-protocol", "https",
                            "--web",
                            "--insecure-storage",
                        ),
                        guestWorkspacePath = "/workspace/github-auth",
                        outputFile = outputFile,
                    )
                    githubAuthProcess = process
                    var offset = 0L
                    val captured = StringBuilder()
                    var browserOpened = false
                    try {
                        while (process.isAlive || outputFile.length() > offset) {
                            if (outputFile.length() > offset) {
                                val count = (outputFile.length() - offset).coerceAtMost(16L * 1024).toInt()
                                val bytes = ByteArray(count)
                                RandomAccessFile(outputFile, "r").use { file -> file.seek(offset); file.readFully(bytes) }
                                offset += count
                                captured.append(bytes.toString(Charsets.UTF_8))
                                val clean = sanitizeTerminalOutput(captured.toString()).takeLast(20_000)
                                val code = GITHUB_DEVICE_CODE.find(clean)?.value
                                if (code != null && !browserOpened) {
                                    browserOpened = true
                                    _state.update {
                                        it.copy(
                                            githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                            githubUserCode = code,
                                            githubVerificationUri = GITHUB_DEVICE_URL,
                                            githubMessage = "Enter this one-time code on GitHub",
                                        )
                                    }
                                    openExternalUrl(GITHUB_DEVICE_URL)
                                }
                            } else {
                                delay(100)
                            }
                        }
                        val exit = process.waitFor()
                        check(exit == 0) {
                            sanitizeTerminalOutput(captured.toString()).lineSequence().lastOrNull { it.isNotBlank() }
                                ?: "GitHub sign-in failed (exit $exit)"
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error("GitHub connected, but the account could not be identified")
                }
            }
            result.onSuccess { login ->
                preferences.githubLogin = login
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = login,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = "Connected as @$login",
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: "GitHub sign-in failed",
                    )
                }
            }
            } finally {
                stopGitHubForegroundOperation()
                githubAuthJob = null
            }
        }
    }

    fun generateNewGitHubCode() {
        githubAuthProcess?.destroy()
        githubAuthJob?.cancel()
        githubAuthProcess = null
        githubAuthJob = null
        stopGitHubForegroundOperation()
        _state.update {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                githubUserCode = null,
                githubVerificationUri = null,
                githubMessage = "Generating a new GitHub code…",
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (_state.value.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubMessage = "Loading repositories…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = "${repositories.size} repositories available",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: "Could not load GitHub repositories",
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = _state.value.githubLogin
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: "GitHub logout failed" }
                }
            }
            result.onSuccess {
                preferences.githubLogin = ""
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubRepositories = emptyList(),
                        githubMessage = "Signed out",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: "Could not sign out") }
            }
        }
    }

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isGitHubCliInstalled()) return@withContext
        val login = runCatching { githubAccountLogin() }.getOrNull()
        if (login.isNullOrBlank()) {
            preferences.githubLogin = ""
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        } else {
            preferences.githubLogin = login
            _state.update {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.CONNECTED,
                    githubLogin = login,
                    githubMessage = "Connected as @$login",
                )
            }
        }
    }

    private fun githubCliEnvironment(): Map<String, String> = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        // Android PRoot has no Secret Service. This keeps the official gh-owned
        // credential in PocketDev's private Linux home instead of exporting it.
        "BROWSER" to "/bin/false",
    )

    private fun runGitHubCli(arguments: List<String>): Pair<Int, String> {
        check(installer.isGitHubCliInstalled()) { "GitHub CLI is not installed" }
        val runtime = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "github-cli-${System.nanoTime()}.log")
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                githubCliEnvironment(),
                listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH) + arguments,
                guestWorkspacePath = "/workspace/github-auth",
                outputFile = outputFile,
            )
            val exit = process.waitFor()
            exit to sanitizeTerminalOutput(outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES)).trim()
        } finally {
            outputFile.delete()
        }
    }

    private fun githubAccountLogin(): String? {
        val (exit, output) = runGitHubCli(listOf("api", "user", "--jq", ".login"))
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().takeIf { exit == 0 && !it.isNullOrBlank() }
    }

    private fun githubRepositoriesFromCli(): List<GitHubRepository> {
        val endpoint = "user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100"
        val (exit, output) = runGitHubCli(listOf("api", "--paginate", "--slurp", endpoint))
        check(exit == 0) { output.lineSequence().lastOrNull { it.isNotBlank() } ?: "Could not load GitHub repositories" }
        val pages = JSONArray(output)
        val repositories = LinkedHashMap<String, GitHubRepository>()
        for (pageIndex in 0 until pages.length()) {
            val page = pages.optJSONArray(pageIndex) ?: continue
            for (index in 0 until page.length()) {
                val item = page.optJSONObject(index) ?: continue
                val fullName = item.optString("full_name").takeIf(String::isNotBlank) ?: continue
                repositories[fullName] = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = item.optString("clone_url", "https://github.com/$fullName.git"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
            }
        }
        return repositories.values.toList()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the URL instead.") }
        }
    }

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, "GitHub sign-in")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_TITLE, "Connecting GitHub")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_CAN_STOP, false),
        )
    }

    private fun stopGitHubForegroundOperation() {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                    .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            getApplication<Application>().stopService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java),
            )
        }
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        _state.update { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalHistoryFile(project.id).delete()
            androidBuildRecordFile(project.id).delete()
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val updated = project.copy(rootPath = root)
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = "$root is now the project root",
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri, selection: ExportSelection) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning ||
            current.projectExportRunning || current.openedFile?.saving == true
        ) {
            _state.update { it.copy(toastMessage = getApplication<Application>().getString(R.string.export_busy)) }
            return
        }
        _state.update { it.copy(projectExportRunning = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("The selected location could not be opened")
                    output.buffered().use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${project.slug}/"))
                            zip.closeEntry()
                            root.walkTopDown()
                                .onEnter { directory ->
                                    if (directory == root) {
                                        true
                                    } else {
                                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                                        selection.canContainIncluded(relative) &&
                                            !isClaudeRuntimeMetadata(relative) &&
                                            !Files.isSymbolicLink(directory.toPath()) &&
                                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                                    }
                                }
                                .drop(1)
                                .filter { file ->
                                    !Files.isSymbolicLink(file.toPath()) &&
                                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                                        !isClaudeRuntimeMetadata(file.relativeTo(root).invariantSeparatorsPath) &&
                                        if (file.isDirectory) {
                                            selection.canContainIncluded(file.relativeTo(root).invariantSeparatorsPath)
                                        } else {
                                            selection.includes(file.relativeTo(root).invariantSeparatorsPath)
                                        }
                                }
                                .forEach { file ->
                                    val relative = file.relativeTo(root).invariantSeparatorsPath
                                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    projectExportRunning = false,
                    projectExportSucceededAtMillis = result.getOrNull()?.let { System.currentTimeMillis() },
                    toastMessage = result.fold(
                        onSuccess = { getApplication<Application>().getString(R.string.export_success, "${project.slug}.zip") },
                        onFailure = { error -> getApplication<Application>().getString(R.string.export_failed, error.message ?: getApplication<Application>().getString(R.string.unknown_error)) },
                    ),
                )
            }
        }
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
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
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
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            val (entries, suggestedRoot, androidProjectDetected) = withContext(Dispatchers.IO) {
                Triple(
                    readWorkspaceDirectory(project, ""),
                    if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                    findAndroidProjectRoot(projectWorkspaceRoot(project)) != null,
                )
            }
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        workspaceFiles = entries,
                        filesLoading = false,
                        suggestedProjectRoot = suggestedRoot,
                        androidProjectDetected = androidProjectDetected,
                    )
                }
            }
        }
    }

    fun loadProjectDirectory(relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val children = withContext(Dispatchers.IO) { readWorkspaceDirectory(project, relativePath) }
            if (_state.value.activeProject?.id != project.id) return@launch
            _state.update { current ->
                val prefix = relativePath.trim('/').let { if (it.isBlank()) "" else "$it/" }
                val retained = current.workspaceFiles.filterNot { entry ->
                    entry.path.startsWith(prefix) && entry.path.removePrefix(prefix).let { !it.contains('/') }
                }
                current.copy(workspaceFiles = (retained + children).distinctBy { it.path }.sortedBy { it.path.lowercase() })
            }
        }
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(openedFile = OpenedFileState(path = entry.path)) }
        loadOpenedFile(project, entry.path)
    }

    private fun loadOpenedFile(project: Project, path: String, targetLine: Int? = null, targetColumn: Int? = null) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val file = safeWorkspaceFile(project, path)
                    ?: return@withContext OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.UNSAFE)
                runCatching {
                    val tooLarge = file.length() > MAX_EDITABLE_FILE_BYTES
                    val bytes = if (tooLarge) file.inputStream().use { it.readNBytes(MAX_EDITABLE_FILE_BYTES.toInt()) } else file.readBytes()
                    if (bytes.any { it == 0.toByte() }) {
                        OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.BINARY)
                    } else {
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                        if (decoded == null) {
                            OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.INVALID_UTF8)
                        } else {
                            val content = if (tooLarge) decoded + "\n\n…" else decoded
                            OpenedFileState(
                                path = path,
                                content = content,
                                draft = content,
                                loading = false,
                                readOnlyReason = FileReadOnlyReason.TOO_LARGE.takeIf { tooLarge },
                                originalFingerprint = if (tooLarge) null else fingerprint(file),
                                lineEnding = if (decoded.contains("\r\n")) "\r\n" else "\n",
                                hadTrailingNewline = decoded.endsWith("\n") || decoded.endsWith("\r"),
                            )
                        }
                    }
                }.getOrElse {
                    OpenedFileState(path = path, loading = false, readOnlyReason = FileReadOnlyReason.READ_ERROR)
                }
            }
            if (_state.value.openedFile?.path == path) {
                _state.update { it.copy(openedFile = loaded.copy(targetLine = targetLine, targetColumn = targetColumn)) }
            }
        }
    }

    fun closeFile() {
        _state.update { it.copy(openedFile = null) }
    }

    fun beginFileEdit() {
        val current = _state.value
        val opened = current.openedFile ?: return
        if (opened.loading || opened.readOnlyReason != null || opened.content == null ||
            current.isRunning || current.projectTerminalRunning || current.androidBuildRunning
        ) return
        _state.update { it.copy(openedFile = opened.copy(editing = true, draft = opened.content, dirty = false, conflict = false)) }
    }

    fun updateFileDraft(value: String) {
        val opened = _state.value.openedFile ?: return
        if (!opened.editing || opened.saving) return
        _state.update { it.copy(openedFile = opened.copy(draft = value, dirty = value != opened.content, conflict = false)) }
    }

    fun cancelFileEdit() {
        val opened = _state.value.openedFile ?: return
        _state.update { it.copy(openedFile = opened.copy(editing = false, draft = opened.content.orEmpty(), dirty = false, conflict = false)) }
    }

    fun reloadOpenedFile() {
        val project = _state.value.activeProject ?: return
        val path = _state.value.openedFile?.path ?: return
        _state.update { it.copy(openedFile = OpenedFileState(path = path)) }
        loadOpenedFile(project, path)
    }

    fun dismissFileConflict() {
        val opened = _state.value.openedFile ?: return
        _state.update { it.copy(openedFile = opened.copy(conflict = false, saving = false)) }
    }

    fun saveOpenedFile() {
        val current = _state.value
        val project = current.activeProject ?: return
        val opened = current.openedFile ?: return
        if (!opened.editing || opened.saving || !opened.dirty || current.isRunning ||
            current.projectTerminalRunning || current.androidBuildRunning
        ) return
        _state.update { it.copy(openedFile = opened.copy(saving = true, conflict = false)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = safeWorkspaceFile(project, opened.path) ?: error("unsafe_path")
                    if (opened.originalFingerprint == null || fingerprint(file) != opened.originalFingerprint) {
                        return@runCatching false
                    }
                    var normalized = opened.draft.replace("\r\n", "\n").replace('\r', '\n')
                    normalized = normalized.trimEnd('\n') + if (opened.hadTrailingNewline) "\n" else ""
                    if (opened.lineEnding == "\r\n") normalized = normalized.replace("\n", "\r\n")
                    val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
                    try {
                        temporary.writeText(normalized, Charsets.UTF_8)
                        if (file.canExecute()) temporary.setExecutable(true, false)
                        runCatching {
                            Files.move(
                                temporary.toPath(), file.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                            )
                        }.getOrElse {
                            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temporary.delete()
                    }
                    true
                }
            }
            when {
                result.getOrNull() == false -> _state.update {
                    it.copy(openedFile = it.openedFile?.copy(saving = false, conflict = true))
                }
                result.isSuccess -> {
                    _state.update { it.copy(toastMessage = getApplication<Application>().getString(R.string.file_saved)) }
                    refreshProjectFiles()
                    loadOpenedFile(project, opened.path)
                }
                else -> _state.update {
                    it.copy(
                        openedFile = it.openedFile?.copy(saving = false),
                        toastMessage = getApplication<Application>().getString(R.string.file_save_failed),
                    )
                }
            }
        }
    }


    private fun readWorkspaceDirectory(project: Project, relativePath: String): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        val directory = if (relativePath.isBlank()) root else File(root, relativePath)
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) ||
            !runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        ) return emptyList()
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
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
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

    private fun safeWorkspaceFile(project: Project, relativePath: String): File? {
        if (relativePath.isBlank() || isClaudeRuntimeMetadata(relativePath)) return null
        val root = projectWorkspaceRoot(project).canonicalFile
        var cursor = root
        for (segment in relativePath.replace('\\', '/').split('/')) {
            if (segment.isBlank() || segment == "." || segment == "..") return null
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) return null
        }
        val canonical = runCatching { cursor.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: "Could not attach file") }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) "Only $remaining more file${if (remaining == 1) "" else "s"} could be added" else null,
                )
            }
            if (added.isNotEmpty()) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: "No app can open this attachment") }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val supportedTextExtensions = setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
            "gradle", "properties", "toml", "ini", "conf", "sql",
        )
        val supported = mimeType.startsWith("image/") ||
            mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType.endsWith("+json") || mimeType.endsWith("+xml") || extension in supportedTextExtensions
        require(supported) { "Only images and text files are supported" }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { "$displayName is larger than 25 MB" }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { "Unsafe attachment folder" }
        val stem = safeName.substringBeforeLast('.', safeName)
        val safeExtension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var destination = File(folder, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$safeExtension")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { "$displayName is larger than 25 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not read $displayName")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName.take(120),
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(100)
        return clean.ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
    }

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY &&
            _state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = "Sign in to Antigravity from Settings before starting a task.") }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = "Claude subscription login is not supported by DeepSeek Harness — pick a key-based provider in Settings.") }
            return
        }
        val attachments = state.value.pendingAttachments
        if (prompt.isBlank() && attachments.isEmpty()) return
        if (state.value.isRunning || state.value.androidBuildRunning) {
            queueFollowUp(project, prompt, attachments)
            return
        }
        if (state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the terminal command to finish, then send your message.") }
            return
        }
        startPrompt(project, prompt, attachments)
    }

    private fun queueFollowUp(project: Project, prompt: String, attachments: List<ChatAttachment>) {
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        pendingFollowUps.addLast(QueuedFollowUp(projectId = project.id, prompt = requestText, attachments = attachments))
        _state.update {
            it.copy(
                pendingAttachments = emptyList(),
                queuedFollowUps = pendingFollowUps.toList(),
                toastMessage = "Follow-up queued",
            )
        }
    }

    fun steerQueuedFollowUp(id: String) {
        if (steeringToFollowUp) return
        val selected = pendingFollowUps.firstOrNull { it.id == id } ?: return
        pendingFollowUps.remove(selected)
        pendingFollowUps.addFirst(selected)
        _state.update {
            it.copy(
                queuedFollowUps = pendingFollowUps.toList(),
                toastMessage = "Steering to this follow-up",
            )
        }
        when {
            _state.value.isRunning -> {
                steeringToFollowUp = true
                viewModelScope.launch { activeRuntime().stopActiveSession() }
            }
            _state.value.androidBuildRunning -> {
                steeringToFollowUp = true
                cancelAndroidBuild()
            }
            else -> startNextFollowUp()
        }
    }

    fun removeQueuedFollowUp(id: String) {
        val selected = pendingFollowUps.firstOrNull { it.id == id } ?: return
        pendingFollowUps.remove(selected)
        _state.update { it.copy(queuedFollowUps = pendingFollowUps.toList()) }
    }

    private fun startPrompt(project: Project, prompt: String, attachments: List<ChatAttachment>) {
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        updateActiveChatTitle(requestText)
        _state.update {
            val startedAt = System.currentTimeMillis()
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim(), attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                activity = listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity,
                liveProcess = listOf(ActivityItem("Think", requestPlanningSummary(requestText, it.agentKind), false)),
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
            )
        }
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now
        val runtimePrompt = if (attachments.isEmpty()) requestText else buildString {
            appendLine(requestText)
            appendLine()
            appendLine("<attached_files>")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.displayName}: ${projectGuestRoot(project)}/${attachment.relativePath} (${attachment.mimeType})")
            }
            appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
            appendLine("</attached_files>")
        }
        failedApiKeyIds.clear()
        activeRuntimeRequest = RuntimeRetryRequest(
            runtime = activeRuntime(),
            project = project,
            prompt = runtimePrompt,
            history = history,
            provider = state.value.provider,
        )
        viewModelScope.launch {
            activeRuntimeRequest?.let { request ->
                request.runtime.startSession(
                    request.project.id,
                    request.project.slug,
                    request.project.kind,
                    request.prompt,
                    request.history,
                    request.provider,
                )
            }
        }
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        viewModelScope.launch { activeRuntime().respondToApproval(request, approved) }
    }

    fun stopTask() {
        if (!_state.value.isRunning) return
        steeringToFollowUp = false
        pendingFollowUps.clear()
        _state.update { it.copy(queuedFollowUps = emptyList()) }
        viewModelScope.launch { activeRuntime().stopActiveSession() }
    }

    private fun startNextFollowUp() {
        val next = if (pendingFollowUps.isEmpty()) null else pendingFollowUps.removeFirst()
        if (next == null) {
            _state.update { it.copy(queuedFollowUps = emptyList()) }
            return
        }
        val project = _state.value.activeProject
        if (project == null || project.id != next.projectId) {
            pendingFollowUps.clear()
            _state.update { it.copy(queuedFollowUps = emptyList()) }
            return
        }
        _state.update { it.copy(queuedFollowUps = pendingFollowUps.toList()) }
        startPrompt(project, next.prompt, next.attachments)
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = activeRuntime().undoLastChanges(project.id)
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
            activeRuntime().acceptLastChanges(project.id)
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
            if (activeRuntime().undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
        val combined = "${item.title} ${item.detail}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true)
    }

    private fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { "the current project" }
        return when (toolName) {
            "Write" -> "Preparing to create ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Edit", "NotebookEdit" -> "Preparing to update ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Read" -> "Preparing to inspect ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Glob" -> "Preparing to find matching project files"
            "Grep" -> "Preparing to search the project for $short"
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                "Preparing to create or update project files with Bash"
            } else {
                "Preparing to run: $short"
            }
            else -> "Preparing to use $toolName for the next step"
        }
    }

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String {
        val agentName = agentKind.title
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            "$agentName is reviewing the request"
        } else {
            "The user is asking: “$cleanRequest”"
        }
        return if (toolName == null) {
            "$requestPart. $agentName is deciding the next useful step."
        } else {
            "$requestPart. ${toolPlanSummary(toolName, detail)}."
        }
    }

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val meaningfulItems = current.liveProcess.filterNot(::isNoisyRuntimeItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
        if (!current.liveThinking && meaningfulItems.isEmpty()) {
            return current.copy(liveProcess = emptyList(), workSegmentStartedAtMillis = null)
        }
        val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: finishedAt
        val block = ChatMessage(
            fromUser = false,
            text = "",
            workItems = meaningfulItems,
            workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
        )
        return current.copy(
            messages = current.messages + block,
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    /** Adds the complete request duration to the response produced after the latest user message. */
    private fun attachTaskDuration(current: AppUiState, finishedAt: Long): AppUiState {
        val startedAt = current.taskStartedAtMillis ?: return current
        val lastUserIndex = current.messages.indexOfLast { it.fromUser }
        val responseIndex = current.messages.indices.lastOrNull { index ->
            index > lastUserIndex && !current.messages[index].fromUser && current.messages[index].text.isNotBlank()
        } ?: return current
        val updated = current.messages.toMutableList()
        updated[responseIndex] = updated[responseIndex].copy(
            workedMillis = (finishedAt - startedAt).coerceAtLeast(1L),
        )
        return current.copy(messages = updated)
    }

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        if (isNoisyRuntimeItem(item)) return current
        return current.copy(
            liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } + item,
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        val terminalEventForActiveSession =
            (event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed) &&
                _state.value.isRunning &&
                (_state.value.activeSessionId == null || _state.value.activeSessionId == event.sessionId)
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.ANTIGRAVITY &&
            (event.reason.contains("sign-in", true) || event.reason.contains("authentication", true))) {
            antigravityAuthController.invalidateSession(event.reason)
        }
        if (event is RuntimeEvent.SessionFailed && retryWithNextApiKey(event)) return
        _state.update { current ->
            if (!current.isRunning) {
                current
            } else if (current.activeSessionId != null && current.activeSessionId != event.sessionId) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                )
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    val lastMessage = timeline.messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.fromUser && lastMessage.workItems.isEmpty() && lastMessage.workedMillis == 0L) {
                        timeline.copy(messages = timeline.messages.dropLast(1) + lastMessage.copy(text = lastMessage.text + event.text))
                    } else {
                        timeline.copy(messages = timeline.messages + ChatMessage(fromUser = false, text = event.text))
                    }
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@update current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty(), current.agentKind),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { "Thinking…" }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@update current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val planned = current.copy(
                        liveProcess = current.liveProcess.map { item ->
                            if (!item.isComplete) item.copy(isComplete = true) else item
                        },
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    )
                    appendWorkItem(
                        planned,
                        ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                ), ActivityItem("Waiting for approval", event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                ), ActivityItem("Action approved", "Claude is continuing the task", false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem("Action rejected", "Claude will continue without this action"))
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { event.summary },
                                isCommand = event.toolName == "Bash",
                            )
                        }
                    } else {
                        current.liveProcess + ActivityItem(
                            "${event.toolName} completed",
                            event.summary,
                            isCommand = event.toolName == "Bash",
                        )
                    }
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> {
                    val finishedAt = System.currentTimeMillis()
                    attachTaskDuration(finishWorkSegment(current, finishedAt), finishedAt).copy(
                        isRunning = false,
                        activeSessionId = null,
                        activity = listOf(ActivityItem("Task completed", "${current.agentKind.title} finished successfully")) +
                            current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                    )
                }
                is RuntimeEvent.SessionFailed -> {
                    val finishedAt = System.currentTimeMillis()
                    attachTaskDuration(
                        finishWorkSegment(
                            appendWorkItem(current, ActivityItem("Task stopped", event.reason)),
                            finishedAt,
                        ),
                        finishedAt,
                    ).copy(
                        isRunning = false,
                        activeSessionId = null,
                        pendingApproval = null,
                        toastMessage = event.reason.takeIf { reason ->
                            reason.contains("user not found", true) ||
                                reason.contains("API key", true) ||
                                reason.contains("authentication", true)
                        },
                        activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                    )
                }
            }
        }
        if (terminalEventForActiveSession) {
            activeRuntimeRequest = null
            failedApiKeyIds.clear()
            steeringToFollowUp = false
            if (pendingFollowUps.isNotEmpty()) {
                viewModelScope.launch {
                    delay(150)
                    if (!_state.value.isRunning) startNextFollowUp()
                }
            }
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {
            _state.value.activeProject?.id?.let { touchProject(it) }
            refreshProjectFiles()
        }
        // Save every visible reasoning/tool transition, not only assistant text and
        // final results. If Android kills the process, the last displayed timeline
        // is restored as an interrupted work block rather than disappearing.
        persistMessages(includeLiveProcess = true)
    }

    private fun retryWithNextApiKey(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        if (current.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.activeSessionId != event.sessionId) return false
        if (!isApiKeyFailure(event.reason)) return false
        val request = activeRuntimeRequest ?: return false
        val credentials = vault.credentials(request.provider.kind.name)
        val active = credentials.firstOrNull { it.isActive } ?: return false
        failedApiKeyIds += active.id
        val next = credentials.firstOrNull { it.id !in failedApiKeyIds } ?: return false
        if (!vault.activate(request.provider.kind.name, next.id)) return false
        _state.update {
            it.copy(
                activeSessionId = null,
                activeApiKeyName = next.name,
                toastMessage = "${active.name} failed. Switched to ${next.name}.",
                liveProcess = it.liveProcess + ActivityItem("API key switched", "Using ${next.name}", true),
            )
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
            )
        }
        return true
    }

    private fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
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

    private fun persistMessages(includeLiveProcess: Boolean = true) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        val liveItems = if (includeLiveProcess) current.liveProcess.filterNot(::isNoisyRuntimeItem) else emptyList()
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            current.messages + ChatMessage(
                id = "interrupted-${current.activeSessionId ?: chatId}",
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    "Task interrupted",
                    "The agent process stopped before reporting completion. Continue this chat to resume its official session.",
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
        }
        transcriptWrites.trySend(TranscriptWrite(project.id, chatId, messages))
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

    override fun onCleared() {
        stopAndroidLogcat()
        super.onCleared()
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_EDITABLE_FILE_BYTES = 512_000L
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
        private const val MAX_ANDROID_BUILD_LOG = 300_000
        private const val MAX_LOGCAT_LINES = 5_000
        const val LOGCAT_GRANT_COMMAND = "adb shell pm grant com.jarves.mh android.permission.READ_LOGS"
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_PROCESS_OUTPUT_BYTES = 512 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val MAX_IMPORTED_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_ZIP_ENTRIES = 100_000
        private const val LEGACY_GITHUB_TOKEN_KEY = "GITHUB_APP"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"
    }
}
