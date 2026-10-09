package com.jarves.mh.ui

import android.app.Application
import android.app.ActivityManager
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
import android.os.Debug
import android.system.Os
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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

data class DeveloperDiagnostics(
    val loading: Boolean = false,
    val appPssBytes: Long = 0L,
    val javaHeapBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val activeProcesses: List<String> = emptyList(),
    val messageCount: Int = 0,
    val liveActivityCount: Int = 0,
    val terminalBufferBytes: Long = 0L,
    val buildLogBytes: Long = 0L,
    val logcatLines: Int = 0,
    val fileEntries: Int = 0,
    val runtimeCacheBytes: Long = 0L,
    val recentExit: String? = null,
    val refreshedAtMillis: Long? = null,
)

/** Counts UTF-8 storage without allocating a second byte array for large logs. */
internal fun utf8SizeInBytes(value: String): Long {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.code < 0x80 -> bytes += 1
            char.code < 0x800 -> bytes += 2
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
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
    val diagnostics: DeveloperDiagnostics = DeveloperDiagnostics(),
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
    val androidBuildRecentDurationsMillis: List<Long> = emptyList(),
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
    /** Resolves a user-visible string in the language selected in the app. */
    private fun str(@StringRes id: Int, vararg args: Any): String = AppStrings.get(getApplication(), id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        AppStrings.context(getApplication()).resources.getQuantityString(id, count, *args)
    private data class FolderMetadata(val modifiedAtMillis: Long, val childCount: Int)

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
    private val providerApi = ProviderApiClient(application)
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
    private var diagnosticsJob: kotlinx.coroutines.Job? = null
    private var lastDiagnosticsRefreshAtElapsedMillis = 0L
    private var cachedRecentExitSummary: String? = null
    private var recentExitLoadedAtElapsedMillis = 0L
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
    private val folderMetadataCache = java.util.concurrent.ConcurrentHashMap<String, FolderMetadata>()
    private val folderCountsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Writes are drained into a per-chat batch below so rapid activity frames do not
    // serialize hundreds of obsolete snapshots.
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
            startupMessage = str(R.string.vm_checking_device),
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
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { str(R.string.vm_connected_as, it) },
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
            for (pending in transcriptWrites) {
                delay(TRANSCRIPT_WRITE_DEBOUNCE_MS)
                val latestByChat = linkedMapOf((pending.projectId to pending.chatId) to pending)
                while (true) {
                    val next = transcriptWrites.tryReceive().getOrNull() ?: break
                    latestByChat[next.projectId to next.chatId] = next
                }
                latestByChat.values.forEach { latest ->
                    preferences.saveMessages(latest.projectId, latest.chatId, latest.messages)
                }
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
                        _state.update { state -> state.copy(toastMessage = str(R.string.vm_browser_open_failed_signin)) }
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
                        return@runCatching str(R.string.vm_linux_not_ready) to 1
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
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else str(R.string.vm_process_exited_code, exit)
                    finalOut to exit
                }.getOrElse { str(R.string.vm_error_with_message, it.message.orEmpty()) to 1 }
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
                it.copy(toastMessage = str(R.string.vm_android_tools_missing))
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
                            output = str(R.string.vm_terminal_error, error.message ?: error::class.java.simpleName),
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else str(R.string.vm_process_exited_code, result.exitCode)
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

    fun refreshDeveloperDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsRefreshAtElapsedMillis < DIAGNOSTICS_REFRESH_COOLDOWN_MS) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(loading = true)) }
        diagnosticsJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { collectDeveloperDiagnostics() }
                lastDiagnosticsRefreshAtElapsedMillis = SystemClock.elapsedRealtime()
                _state.update { it.copy(diagnostics = snapshot) }
            } finally {
                diagnosticsJob = null
            }
        }
    }

    fun stopInactiveDeveloperProcesses() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            if (!current.isRunning) {
                runCatching { claudeRuntime.stopActiveSession() }
                runCatching { dshRuntime.stopActiveSession() }
                runCatching { antigravityRuntime.stopActiveSession() }
            }
            if (!current.projectTerminalRunning) {
                projectTerminalProcess?.takeIf(Process::isAlive)?.destroyForcibly()
                projectTerminalProcess = null
            }
            if (!current.androidBuildRunning) {
                androidBuildProcess?.takeIf(Process::isAlive)?.destroyForcibly()
                androidBuildProcess = null
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(toastMessage = str(R.string.diagnostics_inactive_stopped)) }
                lastDiagnosticsRefreshAtElapsedMillis = 0L
                refreshDeveloperDiagnostics()
            }
        }
    }

    fun clearDeveloperRuntimeCache() {
        val current = _state.value
        if (current.isRunning || current.projectTerminalRunning || current.androidBuildRunning) {
            _state.update { it.copy(toastMessage = str(R.string.diagnostics_cache_busy)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().cacheDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("runtime-output-") }
                .forEach { runCatching { it.delete() } }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(toastMessage = str(R.string.diagnostics_cache_cleared)) }
                lastDiagnosticsRefreshAtElapsedMillis = 0L
                refreshDeveloperDiagnostics()
            }
        }
    }

    /** Drops only rebuildable or already-persisted data when Android reports memory pressure. */
    fun releaseMemoryCaches() {
        folderMetadataCache.clear()
        cachedRecentExitSummary = null
        _state.update { current ->
            current.copy(
                projectTerminalLines = current.projectTerminalLines.takeLast(MEMORY_PRESSURE_TERMINAL_LINES),
                projectTerminalLiveOutput = current.projectTerminalLiveOutput.takeLast(MEMORY_PRESSURE_TEXT_CHARS),
                androidBuildLog = current.androidBuildLog.takeLast(MEMORY_PRESSURE_TEXT_CHARS),
                androidLogcat = current.androidLogcat.copy(
                    lines = current.androidLogcat.lines.takeLast(MEMORY_PRESSURE_LOGCAT_LINES),
                ),
            )
        }
    }

    private fun collectDeveloperDiagnostics(): DeveloperDiagnostics {
        val current = _state.value
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val active = buildList {
            if (current.isRunning) add(current.agentKind.title)
            if (current.projectTerminalRunning || projectTerminalProcess?.isAlive == true) add("Project terminal")
            if (terminalProcess?.isAlive == true) add("Quick terminal")
            if (current.androidBuildRunning || androidBuildProcess?.isAlive == true) add("Android build")
            if (current.androidLogcat.running || androidLogcatProcess?.isAlive == true) add("Logcat")
        }
        val runtimeCache = getApplication<Application>().cacheDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("runtime-output-") }
            .sumOf(File::length)
        return DeveloperDiagnostics(
            appPssBytes = memory.totalPss.toLong() * 1_024L,
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMaxBytes = runtime.maxMemory(),
            activeProcesses = active,
            messageCount = current.messages.size,
            liveActivityCount = current.liveProcess.size,
            terminalBufferBytes = utf8SizeInBytes(current.projectTerminalLiveOutput) +
                current.projectTerminalLines.sumOf { utf8SizeInBytes(it.output) },
            buildLogBytes = utf8SizeInBytes(current.androidBuildLog),
            logcatLines = current.androidLogcat.lines.size,
            fileEntries = current.workspaceFiles.size,
            runtimeCacheBytes = runtimeCache,
            recentExit = recentAppExitSummary(),
            refreshedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun recentAppExitSummary(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - recentExitLoadedAtElapsedMillis < RECENT_EXIT_CACHE_MS) return cachedRecentExitSummary
        recentExitLoadedAtElapsedMillis = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val exit = manager.getHistoricalProcessExitReasons(getApplication<Application>().packageName, 0, 8)
            .firstOrNull { info ->
                info.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                    info.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
            } ?: run {
                cachedRecentExitSummary = null
                return null
            }
        val reason = when (exit.reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            else -> "Crash"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(exit.timestamp))
        return ("$reason · $time" + exit.description?.takeIf(String::isNotBlank)?.let { " · ${it.take(160)}" }.orEmpty())
            .also { cachedRecentExitSummary = it }
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult(str(R.string.vm_linux_not_ready), 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult(str(R.string.vm_project_unavailable), 1, cwd)
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
            ?: return ProjectTerminalResult(str(R.string.vm_unsupported_terminal_process), 1, cwd)
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
                _state.update { current -> current.copy(toastMessage = str(R.string.vm_process_not_accepting_input)) }
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
        require(selected.toPath().startsWith(base.toPath())) { str(R.string.vm_unsafe_project_root) }
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
                it.copy(toastMessage = str(R.string.vm_android_tools_missing))
            }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_wait_agent_before_build)) }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_wait_terminal_before_build)) }
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
                    ?: error(str(R.string.vm_no_android_project))
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
                val buildCompletedAt = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    _state.update { current ->
                        val duration = (buildCompletedAt - startedAt).coerceAtLeast(1L)
                        val recentDurations = if (apk != null) {
                            (current.androidBuildRecentDurationsMillis + duration).takeLast(5)
                        } else current.androidBuildRecentDurationsMillis
                        current.copy(
                            androidBuildRunning = shouldInstallAndRun,
                            androidBuildPhase = AndroidBuildPhase.SUCCEEDED,
                            androidBuildStage = if (shouldInstallAndRun) AndroidBuildStage.INSTALLING else AndroidBuildStage.COMPLETE,
                            androidBuildMessage = message,
                            androidBuildIssues = emptyList(),
                            androidBuildFinishedAtMillis = buildCompletedAt,
                            androidBuildApkPath = apk?.absolutePath,
                            androidBuildApkSizeBytes = apk?.length(),
                            androidBuildRecentDurationsMillis = recentDurations,
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
                        .put("recentDurationsMillis", JSONArray(state.androidBuildRecentDurationsMillis))
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
                recentDurationsMillis = json.optJSONArray("recentDurationsMillis")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optLong(index).takeIf { it > 0L } }
                }.orEmpty(),
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
                val (toolchainExit, output) = runAndroidGuestCommand(
                    project,
                    "java -version 2>&1 | grep -E '\"17\\.|version 17' >/dev/null && " +
                        "gradle --version >/dev/null && " +
                        "/root/android-sdk/build-tools/35.0.0/aapt2 version >/dev/null && " +
                        "printf POCKETDEV_TOOLCHAIN_OK",
                )
                val ok = toolchainExit == 0 && "POCKETDEV_TOOLCHAIN_OK" in output
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
            _state.update { it.copy(androidHealthRunning = false, androidHealthChecks = checks) }
        }
    }

    fun performAndroidHealthFix(fix: AndroidHealthFix) {
        val app = getApplication<Application>()
        when (fix) {
            AndroidHealthFix.INSTALL_TOOLS -> {
                _state.update { current ->
                    current.copy(
                        androidHealthRunning = true,
                        androidHealthChecks = current.androidHealthChecks.map { check ->
                            if (check.fix == AndroidHealthFix.INSTALL_TOOLS) {
                                check.copy(
                                    detail = "Repairing Android development tools…",
                                    status = AndroidHealthStatus.RUNNING,
                                    fix = AndroidHealthFix.NONE,
                                )
                            } else check
                        },
                    )
                }
                installDevStack(DevStack.ANDROID, refreshAndroidHealthAfter = true)
            }
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
                    startupMessage = str(R.string.vm_arm64_required),
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
                startupMessage = str(R.string.vm_preparing_workspace),
                startupBytes = null,
                startupLogs = listOf("\$ " + str(R.string.vm_preparing_workspace)),
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
                    startupMessage = str(R.string.vm_setup_paused),
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
                startupMessage = str(R.string.vm_opening_workspace),
                startupBytes = null,
                startupLogs = listOf("\$ " + str(R.string.vm_opening_workspace)),
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
            showStartupError(result.exceptionOrNull() ?: IllegalStateException(str(R.string.vm_initialization_failed)))
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
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: str(R.string.vm_installer_start_failed)) }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: str(R.string.vm_update_download_failed)) }
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
            str(R.string.vm_setup_offline)
        } else {
            error.message?.take(300) ?: str(R.string.vm_setup_generic_error)
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
            str(R.string.vm_antigravity_sign_in_first)
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
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_agent_before_switching)) }
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
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_agent_before_switching)) }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = str(R.string.vm_preparing_named, kind.localizedTitle(getApplication())),
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
                        onSuccess = { str(R.string.vm_agent_ready, kind.localizedTitle(getApplication())) },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: str(R.string.vm_install_failed_named, kind.localizedTitle(getApplication())) },
                    ),
                )
            }
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = str(R.string.vm_checking_agent_releases)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrDefault(emptyMap()),
                    agentUpdatesChecking = false,
                    agentUpdateMessage = result.fold(
                        onSuccess = { updates -> if (updates.isEmpty()) str(R.string.vm_agents_up_to_date) else plural(R.plurals.vm_agent_updates_available, updates.size, updates.size) },
                        onFailure = { error -> error.message?.take(200) ?: str(R.string.vm_agent_update_check_failed) },
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
                agentUpdateMessage = str(R.string.vm_preparing_version, kind.localizedTitle(getApplication()), update.latestVersion),
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
                        onSuccess = { str(R.string.vm_agent_updated_to, kind.localizedTitle(getApplication()), update.latestVersion) },
                        onFailure = { error -> error.message?.take(220) ?: str(R.string.vm_update_failed_named, kind.localizedTitle(getApplication())) },
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
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_submit_code_failed)) } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_sign_out_failed)) } }
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
            _state.update { it.copy(toastMessage = str(
                R.string.vm_model_no_effort,
                str(
                    when (effort) {
                        "low" -> R.string.vm_effort_low
                        "medium" -> R.string.vm_effort_medium
                        else -> R.string.vm_effort_high
                    },
                ),
            )) }
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
                check(process.waitFor() == 0) { str(R.string.vm_antigravity_list_models_failed) }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { str(R.string.vm_antigravity_no_models) } }
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
                            toastMessage = error.message ?: str(R.string.vm_antigravity_models_load_failed),
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
    fun installDevStack(stack: DevStack, refreshAndroidHealthAfter: Boolean = false) {
        if (_state.value.devStackInstalling != null) return
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = str(R.string.vm_preparing_named, stack.localizedLabel(getApplication())),
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
                        onSuccess = { str(R.string.vm_stack_tools_ready, stack.localizedLabel(getApplication())) },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: str(R.string.vm_install_failed_named, stack.localizedLabel(getApplication())) },
                    ),
                    androidHealthRunning = if (refreshAndroidHealthAfter) false else current.androidHealthRunning,
                )
            }
            if (refreshAndroidHealthAfter) refreshAndroidHealth()
        }
    }

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || stack == DevStack.WEB) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_removing_tools)) }
            return
        }
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = str(R.string.vm_removing_named, stack.localizedLabel(getApplication())),
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
                        onSuccess = { str(R.string.vm_removed_named, stack.localizedLabel(getApplication())) },
                        onFailure = { result.exceptionOrNull()?.message?.take(200) ?: str(R.string.vm_remove_failed_named, stack.localizedLabel(getApplication())) },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { str(R.string.vm_removed_named, stack.localizedLabel(getApplication())) },
                        onFailure = { str(R.string.vm_remove_failed_named, stack.localizedLabel(getApplication())) },
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
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = str(R.string.vm_ping_sending)) }
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
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = str(R.string.vm_ping_success))
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
                    apiPingMessage = str(R.string.vm_antigravity_needs_signin),
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = str(R.string.vm_antigravity_hello)) }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = str(R.string.vm_antigravity_working),
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
            value.contains("not installed", true) -> str(R.string.vm_antigravity_cli_missing)
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> str(R.string.vm_antigravity_reconnect)
            value.contains("did not answer", true) -> str(R.string.vm_antigravity_no_answer)
            value.isBlank() -> str(R.string.vm_antigravity_no_answer)
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
                listOf(ProjectChat(title = str(R.string.vm_main_chat)))
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
            listOf(ProjectChat(title = str(R.string.vm_main_chat))).also { preferences.saveProjectChats(project.id, it) }
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
                androidBuildRecentDurationsMillis = androidBuild.recentDurationsMillis,
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
                        it.isRunning -> str(R.string.vm_task_continues_background)
                        it.androidBuildRunning -> "Android build continues in the background"
                        else -> str(R.string.vm_terminal_continues_background)
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
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_new_project)) }
            return
        }
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = str(R.string.vm_starter_web_project),
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
        val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
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
            _state.update { it.copy(toastMessage = str(R.string.vm_stop_before_new_project)) }
            return
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = str(R.string.vm_language_general),
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = str(R.string.vm_reading_archive)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = str(R.string.vm_new_chat))
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = str(R.string.vm_project_imported, project.name),
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
                        toastMessage = str(R.string.vm_import_failed, error.message?.take(180) ?: str(R.string.vm_invalid_zip)),
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
            val source = resolver.openInputStream(uri) ?: error(str(R.string.vm_zip_open_failed))
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { str(R.string.vm_zip_too_many_files) }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { str(R.string.vm_zip_invalid_path) }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { str(R.string.vm_zip_unsafe_path) }
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
                                    require(extractedBytes <= availableLimit) { str(R.string.vm_zip_too_large) }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { str(R.string.vm_zip_no_files) }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = str(R.string.vm_language_general),
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
                        require(extractedBytes + sourceBytes <= availableLimit) { str(R.string.vm_import_too_large) }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(str(R.string.vm_zip_preserve_failed))
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
            else -> "Imported ZIP project" to str(R.string.vm_language_general)
        }
    }

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = str(R.string.vm_github_connect_again)) }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    private fun cloneGitRepository(url: String, repositoryName: String?, branch: String?, useGitHubCli: Boolean) {
        if (_state.value.gitCloneRunning || _state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning || _state.value.androidBuildRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_invalid_git_url)) }
            return
        }
        _state.update { it.copy(gitCloneRunning = true, gitCloneMessage = str(R.string.vm_connecting_git)) }
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
                        _state.update { it.copy(gitCloneMessage = str(R.string.vm_cloning_named, repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git"))) }
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
                        val details = output.readTailText(MAX_PROCESS_OUTPUT_BYTES).trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { str(R.string.vm_git_clone_failed_exit, exit) } }
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
                val chat = ProjectChat(title = str(R.string.vm_new_chat))
                preferences.saveProjectChats(project.id, listOf(chat))
                _state.update { current -> current.copy(projects = listOf(project) + current.projects) }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = str(R.string.vm_repository_cloned)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = str(R.string.vm_clone_failed, error.message?.lineSequence()?.lastOrNull()?.take(180) ?: str(R.string.vm_unknown_error)),
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { str(R.string.vm_git_https_only) }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { str(R.string.vm_git_no_credentials) }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { str(R.string.vm_git_local_unsupported) }
        require(uri.path.count { it == '/' } >= 2) { str(R.string.vm_git_url_not_repo) }
        return uri.toASCIIString()
    }

    fun startGitHubLogin() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = str(R.string.vm_github_preparing_signin)) }
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
                                            githubMessage = str(R.string.vm_github_enter_code),
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
                                ?: str(R.string.vm_github_signin_failed_exit, exit)
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error(str(R.string.vm_github_account_unknown))
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
                        githubMessage = str(R.string.vm_github_connected_as, login),
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: str(R.string.vm_github_signin_failed),
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
                githubMessage = str(R.string.vm_github_new_code),
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (_state.value.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubMessage = str(R.string.vm_loading_repositories)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = plural(R.plurals.vm_repositories_available, repositories.size, repositories.size),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: str(R.string.vm_github_repos_failed),
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = _state.value.githubLogin
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = str(R.string.vm_github_signing_out)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: str(R.string.vm_github_logout_failed) }
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
                        githubMessage = str(R.string.vm_signed_out),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: str(R.string.vm_sign_out_failed)) }
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
                    githubMessage = str(R.string.vm_github_connected_as, login),
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
        check(installer.isGitHubCliInstalled()) { str(R.string.vm_github_cli_missing) }
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
        check(exit == 0) { output.lineSequence().lastOrNull { it.isNotBlank() } ?: str(R.string.vm_github_repos_failed) }
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
            _state.update { state -> state.copy(toastMessage = str(R.string.vm_browser_open_failed_url)) }
        }
    }

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, str(R.string.vm_notif_github_signin))
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_TITLE, str(R.string.vm_notif_connecting_github))
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
        folderMetadataCache.keys.removeAll { it.startsWith("$projectId:") }
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
                toastMessage = str(R.string.vm_project_root_set, root),
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
            _state.update { it.copy(toastMessage = str(R.string.export_busy)) }
            return
        }
        _state.update { it.copy(projectExportRunning = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error(str(R.string.vm_export_location_failed))
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
                        onSuccess = { str(R.string.export_success, "${project.slug}.zip") },
                        onFailure = { error -> str(R.string.export_failed, error.message ?: str(R.string.unknown_error)) },
                    ),
                )
            }
        }
    }

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        persistMessages()
        val chat = ProjectChat(title = str(R.string.vm_new_chat))
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
                loadMissingFolderCounts(project, entries)
            }
        }
    }

    fun loadProjectDirectory(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val existingEntries = _state.value.workspaceFiles
        viewModelScope.launch {
            val (children, updatedEntries) = withContext(Dispatchers.IO) {
                val loadedChildren = readWorkspaceDirectory(project, relativePath)
                val prefix = relativePath.trim('/').let { if (it.isBlank()) "" else "$it/" }
                val retained = existingEntries.filterNot { entry ->
                    entry.path.startsWith(prefix) && entry.path.removePrefix(prefix).let { !it.contains('/') }
                }
                val sorted = sortWorkspaceEntries((retained + loadedChildren).distinctBy { it.path }).map { entry ->
                    if (entry.path == relativePath) entry.copy(childCount = loadedChildren.size) else entry
                }
                loadedChildren to sorted
            }
            if (_state.value.activeProject?.id != project.id) return@launch
            _state.update { current -> current.copy(workspaceFiles = updatedEntries) }
            updateFolderMetadata(project, relativePath, children.size)
            loadMissingFolderCounts(project, children)
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
                    _state.update { it.copy(toastMessage = str(R.string.file_saved)) }
                    invalidateFolderMetadata(project.id, listOf(opened.path))
                    refreshProjectFiles()
                    loadOpenedFile(project, opened.path)
                }
                else -> _state.update {
                    it.copy(
                        openedFile = it.openedFile?.copy(saving = false),
                        toastMessage = str(R.string.file_save_failed),
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
        fun isVisibleSafeEntry(file: File): Boolean {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            return !isClaudeRuntimeMetadata(relative) &&
                !Files.isSymbolicLink(file.toPath()) &&
                runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
        }
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter(::isVisibleSafeEntry)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                    childCount = if (file.isDirectory) {
                        folderMetadataCache[folderMetadataKey(project.id, relative)]
                            ?.takeIf { it.modifiedAtMillis == file.lastModified() }
                            ?.childCount
                    } else {
                        null
                    },
                )
            }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private fun loadMissingFolderCounts(project: Project, entries: List<WorkspaceEntry>) {
        entries.asSequence()
            .filter { it.isDirectory && it.childCount == null && !isDeferredMetadataPath(it.path) }
            .forEach { entry ->
                val key = folderMetadataKey(project.id, entry.path)
                if (!folderCountsInFlight.add(key)) return@forEach
                viewModelScope.launch {
                    val metadata = withContext(Dispatchers.IO) {
                        val directory = safeWorkspaceDirectory(project, entry.path)
                        directory?.let {
                            FolderMetadata(
                                modifiedAtMillis = it.lastModified(),
                                childCount = countVisibleChildren(project, it),
                            )
                        }
                    }
                    folderCountsInFlight.remove(key)
                    if (metadata == null || _state.value.activeProject?.id != project.id) return@launch
                    folderMetadataCache[key] = metadata
                    _state.update { current ->
                        current.copy(
                            workspaceFiles = current.workspaceFiles.map { item ->
                                if (item.path == entry.path) item.copy(childCount = metadata.childCount) else item
                            },
                        )
                    }
                }
            }
    }

    private fun updateFolderMetadata(project: Project, relativePath: String, count: Int) {
        val directory = safeWorkspaceDirectory(project, relativePath) ?: return
        folderMetadataCache[folderMetadataKey(project.id, relativePath)] = FolderMetadata(
            modifiedAtMillis = directory.lastModified(),
            childCount = count,
        )
    }

    private fun countVisibleChildren(project: Project, directory: File): Int {
        val root = projectWorkspaceRoot(project).canonicalFile.toPath()
        return directory.listFiles().orEmpty().count { file ->
            val relative = runCatching { file.relativeTo(projectWorkspaceRoot(project)).invariantSeparatorsPath }
                .getOrNull() ?: return@count false
            !isClaudeRuntimeMetadata(relative) &&
                !Files.isSymbolicLink(file.toPath()) &&
                runCatching { file.canonicalFile.toPath().startsWith(root) }.getOrDefault(false)
        }
    }

    private fun safeWorkspaceDirectory(project: Project, relativePath: String): File? {
        val root = projectWorkspaceRoot(project).canonicalFile
        if (relativePath.isBlank()) return root.takeIf(File::isDirectory)
        val directory = safeWorkspaceFile(project, relativePath) ?: return null
        return directory.takeIf(File::isDirectory)
    }

    private fun folderMetadataKey(projectId: String, path: String): String = "$projectId:${path.trim('/')}"

    private fun invalidateFolderMetadata(projectId: String, paths: List<String>) {
        paths.forEach { changedPath ->
            val normalized = changedPath.trim('/')
            val parent = normalized.substringBeforeLast('/', "")
            folderMetadataCache.remove(folderMetadataKey(projectId, parent))
            folderMetadataCache.remove(folderMetadataKey(projectId, normalized))
        }
    }

    private fun isDeferredMetadataPath(path: String): Boolean {
        val segments = path.split('/')
        return segments.any { it in DEFERRED_FOLDER_METADATA_NAMES }
    }

    private fun sortWorkspaceEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> {
        val childrenByParent = entries.groupBy { entry -> entry.path.substringBeforeLast('/', "") }
        val siblingOrder = compareByDescending<WorkspaceEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
        return buildList(entries.size) {
            fun appendChildren(parentPath: String) {
                childrenByParent[parentPath].orEmpty().sortedWith(siblingOrder).forEach { entry ->
                    add(entry)
                    appendChildren(entry.path)
                }
            }
            appendChildren("")
        }
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
            _state.update { it.copy(toastMessage = str(R.string.vm_max_attachments, MAX_ATTACHMENTS_PER_MESSAGE)) }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: str(R.string.vm_attach_failed)) }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) plural(R.plurals.vm_more_files_can_be_added, remaining, remaining) else null,
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
            require(file.isFile && file.toPath().startsWith(root.toPath())) { str(R.string.vm_attachment_unavailable) }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: str(R.string.vm_no_app_for_attachment)) }
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
        require(supported) { str(R.string.vm_attachment_unsupported_type) }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { str(R.string.vm_attachment_too_large, displayName) }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { str(R.string.vm_unsafe_attachment_folder) }
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
                        require(copied <= MAX_ATTACHMENT_BYTES) { str(R.string.vm_attachment_too_large, displayName) }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(str(R.string.vm_read_named_failed, displayName))
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
            _state.update { it.copy(toastMessage = str(R.string.vm_antigravity_signin_before_task)) }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = str(R.string.vm_dsh_claude_unsupported)) }
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
        // requestText is sent to the agent, so it stays English; the chat title is user-facing.
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        updateActiveChatTitle(prompt.trim().ifBlank { str(R.string.md_chat_title_attachments) })
        _state.update {
            val startedAt = System.currentTimeMillis()
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim(), attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                activity = listOf(ActivityItem(str(R.string.vm_understanding_request), str(R.string.vm_preparing_safe_plan), false)) + it.activity,
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
                            if (restored) str(R.string.vm_changes_undone) else str(R.string.vm_undo_unavailable),
                            if (restored) str(R.string.vm_files_restored) else str(R.string.vm_no_checkpoint),
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
                    activity = listOf(ActivityItem(str(R.string.vm_changes_kept), str(R.string.vm_changes_kept_detail))) + it.activity,
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
        // Never allocate or scan an entire command/result just to classify the row.
        val combined = "${item.title} ${item.detail.take(NOISY_ITEM_SCAN_CHARS)}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true) ||
            combined.contains(str(R.string.vm_task_completed), true)
    }

    private fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { str(R.string.vm_plan_current_project) }
        return when (toolName) {
            "Write" -> str(R.string.vm_plan_create, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Edit", "NotebookEdit" -> str(R.string.vm_plan_update, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Read" -> str(R.string.vm_plan_inspect, clean.substringAfterLast('/').ifBlank { str(R.string.vm_plan_a_project_file) })
            "Glob" -> str(R.string.vm_plan_find)
            "Grep" -> str(R.string.vm_plan_search, short)
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                str(R.string.vm_plan_bash_files)
            } else {
                str(R.string.vm_plan_run, short)
            }
            else -> str(R.string.vm_plan_use_tool, toolName)
        }
    }

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String {
        val agentName = agentKind.localizedTitle(getApplication())
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            str(R.string.vm_plan_agent_reviewing, agentName)
        } else {
            str(R.string.vm_plan_user_asking, cleanRequest)
        }
        return if (toolName == null) {
            str(R.string.vm_plan_deciding, requestPart, agentName)
        } else {
            str(R.string.vm_plan_with_tool, requestPart, toolPlanSummary(toolName, detail))
        }
    }

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val meaningfulItems = current.liveProcess.filterNot(::isNoisyRuntimeItem)
            .map(::boundedActivityItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
            .takeLast(MAX_VISIBLE_WORK_ITEMS)
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
        val boundedItem = boundedActivityItem(item)
        if (isNoisyRuntimeItem(boundedItem)) return current
        return current.copy(
            liveProcess = (current.liveProcess
                .map { if (!it.isComplete) it.copy(isComplete = true) else it } + boundedItem)
                .takeLast(MAX_VISIBLE_WORK_ITEMS),
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
                        val initial = summary.ifBlank { str(R.string.vm_thinking) }
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
                        activity = (listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it })
                            .take(MAX_VISIBLE_WORK_ITEMS),
                    )
                    appendWorkItem(
                        planned,
                        ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                    )
                }
                is RuntimeEvent.ToolProgress -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    if (runningIndex < 0) current else current.copy(
                        liveProcess = current.liveProcess.toMutableList().also { items ->
                            items[runningIndex] = boundedActivityItem(
                                items[runningIndex].copy(detail = event.detail),
                            )
                        },
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem(str(R.string.vm_waiting_approval), event.request.explanation, false)) + current.activity,
                ), ActivityItem(str(R.string.vm_waiting_approval), event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem(str(R.string.vm_applying_approved), str(R.string.vm_editing_project_files), false)) + current.activity,
                ), ActivityItem(str(R.string.vm_action_approved), str(R.string.vm_agent_continuing, current.agentKind.localizedTitle(getApplication())), false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem(str(R.string.vm_action_rejected), str(R.string.vm_agent_continue_without, current.agentKind.localizedTitle(getApplication()))))
                is RuntimeEvent.ToolCompleted -> {
                    // Bridges fill empty tool results with English placeholders; translate for display.
                    val summary = when (event.summary) {
                        "Tool failed" -> str(R.string.md_tool_failed)
                        "Completed successfully" -> str(R.string.md_tool_completed_successfully)
                        else -> event.summary
                    }
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { summary },
                                isCommand = event.toolName == "Bash",
                            ).let(::boundedActivityItem)
                        }
                    } else {
                        current.liveProcess + boundedActivityItem(ActivityItem(
                            "${event.toolName} completed",
                            summary,
                            isCommand = event.toolName == "Bash",
                        ))
                    }
                    current.copy(
                        activity = (listOf(ActivityItem(summary, event.toolName)) + current.activity)
                            .take(MAX_VISIBLE_WORK_ITEMS),
                        liveProcess = process.takeLast(MAX_VISIBLE_WORK_ITEMS),
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            str(R.string.vm_files_changed),
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " " + str(R.string.vm_files_more, event.paths.size - 4) else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem(str(R.string.vm_preview_ready), event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem(str(R.string.vm_preview_ready), event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> {
                    val finishedAt = System.currentTimeMillis()
                    attachTaskDuration(finishWorkSegment(current, finishedAt), finishedAt).copy(
                        isRunning = false,
                        activeSessionId = null,
                        activity = listOf(ActivityItem(str(R.string.vm_task_completed), str(R.string.vm_agent_finished, current.agentKind.localizedTitle(getApplication())))) +
                            current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                    )
                }
                is RuntimeEvent.SessionFailed -> {
                    val finishedAt = System.currentTimeMillis()
                    val displayReason = localizedFailureReason(event.reason)
                    attachTaskDuration(
                        finishWorkSegment(
                            appendWorkItem(current, ActivityItem("Task stopped", displayReason)),
                            finishedAt,
                        ),
                        finishedAt,
                    ).copy(
                        isRunning = false,
                        activeSessionId = null,
                        pendingApproval = null,
                        toastMessage = displayReason.takeIf {
                            event.reason.contains("user not found", true) ||
                                event.reason.contains("API key", true) ||
                                event.reason.contains("authentication", true)
                        },
                        activity = listOf(ActivityItem("Task stopped", displayReason)) + current.activity,
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
            _state.value.activeProject?.id?.let { projectId ->
                if (event is RuntimeEvent.FilesChanged) invalidateFolderMetadata(projectId, event.paths)
                touchProject(projectId)
            }
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
                toastMessage = str(R.string.vm_key_switched_toast, active.name, next.name),
                liveProcess = it.liveProcess + ActivityItem(str(R.string.vm_api_key_switched), str(R.string.vm_using_key, next.name), true),
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

    /**
     * Runtime bridges report failures in English (matched by [isApiKeyFailure] and the toast
     * check), so known reasons are translated only for display.
     */
    private fun localizedFailureReason(reason: String): String {
        val trimmed = reason.trim()
        Regex("^No API key is saved for (.+)\\.$").matchEntire(trimmed)?.let { match ->
            val name = match.groupValues[1]
            val kind = ProviderKind.entries.firstOrNull { it.title == name }
            return str(R.string.md_reason_no_api_key, kind?.localizedTitle(getApplication()) ?: name)
        }
        Regex("^(Claude Code|DeepSeek Harness) stopped with exit code (-?\\d+)$").matchEntire(trimmed)?.let { match ->
            return str(R.string.md_reason_agent_exit_code, match.groupValues[1], match.groupValues[2].toInt())
        }
        Regex("^Antigravity exited with code (-?\\d+)$").matchEntire(trimmed)?.let { match ->
            return str(R.string.md_reason_antigravity_exit_code, match.groupValues[1].toInt())
        }
        return when (trimmed) {
            "Stopped by user" -> str(R.string.md_reason_stopped_by_user)
            "User not found. Check the API key and provider account." -> str(R.string.md_reason_user_not_found)
            "The provider rejected the saved API key." -> str(R.string.md_reason_key_rejected)
            "Runtime verification failed. Nothing unverified was executed." -> str(R.string.md_reason_verification_failed)
            "The real Claude Code runtime could not start." -> str(R.string.md_reason_claude_could_not_start)
            "Claude Code reported an error" -> str(R.string.md_reason_claude_reported_error)
            "Antigravity CLI is not installed." -> str(R.string.md_reason_antigravity_cli_missing_short)
            "Antigravity needs Google sign-in. Open Settings → Coding agent." -> str(R.string.md_reason_antigravity_signin)
            "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset." -> str(R.string.md_reason_antigravity_no_credits)
            "Antigravity reached the 60-minute task limit. Your files were kept." -> str(R.string.md_reason_antigravity_time_limit)
            "The selected Antigravity model is unavailable. Refresh models in Settings." -> str(R.string.md_reason_antigravity_model_unavailable)
            "Antigravity could not complete the task." -> str(R.string.md_reason_antigravity_failed)
            "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings." -> str(R.string.vm_dsh_claude_unsupported)
            "DeepSeek Harness stopped before processing the prompt" -> str(R.string.md_reason_dsh_stopped_before_prompt)
            "No API key reached DeepSeek Harness. Re-save the provider key in Settings." -> str(R.string.md_reason_dsh_no_key)
            "DeepSeek Harness could not start." -> str(R.string.md_reason_dsh_could_not_start)
            "DeepSeek Harness reported an unspecified error" -> str(R.string.md_reason_dsh_unspecified)
            "DeepSeek Harness turn failed" -> str(R.string.md_reason_dsh_turn_failed)
            "DeepSeek Harness was blocked from completing the task" -> str(R.string.md_reason_dsh_blocked)
            "No Claude subscription token is saved. Add one from Agent → AI provider." -> str(R.string.md_reason_no_claude_token)
            "DeepSeek Harness is not installed. Open Settings → Coding agent to install it." -> str(R.string.md_reason_dsh_not_installed)
            "Antigravity CLI is not installed. Open Settings → Coding agent to install it." -> str(R.string.md_reason_antigravity_not_installed)
            "Antigravity did not answer. Try again." -> str(R.string.vm_antigravity_no_answer)
            else -> reason
        }
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
        val liveItems = if (includeLiveProcess) {
            current.liveProcess.takeLast(MAX_VISIBLE_WORK_ITEMS)
                .map(::boundedActivityItem)
                .filterNot(::isNoisyRuntimeItem)
        } else {
            emptyList()
        }
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            current.messages + ChatMessage(
                id = "interrupted-${current.activeSessionId ?: chatId}",
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    str(R.string.vm_task_interrupted),
                    str(R.string.vm_task_interrupted_detail),
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
        }
        transcriptWrites.trySend(TranscriptWrite(project.id, chatId, messages))
    }

    private fun boundedActivityItem(item: ActivityItem): ActivityItem {
        if (item.detail.length <= MAX_ACTIVITY_DETAIL_CHARS) return item
        return item.copy(
            detail = "… Earlier output omitted …\n" + item.detail.takeLast(MAX_ACTIVITY_DETAIL_CHARS),
        )
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val newChatTitle = str(R.string.vm_new_chat)
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat" || chat.title == newChatTitle) title else chat.title,
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
        /** A long-running agent can poll a managed command hundreds of times. Keep the chat
         * responsive by retaining the latest useful steps instead of composing an unbounded list. */
        private const val MAX_VISIBLE_WORK_ITEMS = 32
        private val DEFERRED_FOLDER_METADATA_NAMES = setOf(".git", ".gradle", "build", "node_modules")
        private const val MAX_ACTIVITY_DETAIL_CHARS = 8_000
        private const val NOISY_ITEM_SCAN_CHARS = 320
        private const val TRANSCRIPT_WRITE_DEBOUNCE_MS = 500L
        private const val DIAGNOSTICS_REFRESH_COOLDOWN_MS = 1_000L
        private const val RECENT_EXIT_CACHE_MS = 60_000L
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_EDITABLE_FILE_BYTES = 512_000L
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
        private const val MAX_ANDROID_BUILD_LOG = 300_000
        private const val MAX_LOGCAT_LINES = 5_000
        private const val MEMORY_PRESSURE_TERMINAL_LINES = 20
        private const val MEMORY_PRESSURE_LOGCAT_LINES = 500
        private const val MEMORY_PRESSURE_TEXT_CHARS = 64 * 1024
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
