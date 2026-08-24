package dev.pocket.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocket.app.model.ActivityItem
import dev.pocket.app.model.ChangeItem
import dev.pocket.app.model.ChatMessage
import dev.pocket.app.model.ChatAttachment
import dev.pocket.app.model.DevStack
import dev.pocket.app.model.DiffLine
import dev.pocket.app.model.DiffLineType
import dev.pocket.app.model.Project
import dev.pocket.app.model.ProjectKind
import dev.pocket.app.model.ProjectChat
import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile
import dev.pocket.app.model.ToolRequest
import dev.pocket.app.model.WorkspaceEntry
import dev.pocket.app.model.projectSlug
import dev.pocket.app.runtime.RuntimeExecutionService
import dev.pocket.app.runtime.RuntimeSetupService
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.pocket.app.network.ConnectionValidation
import dev.pocket.app.network.DiscoveredModel
import dev.pocket.app.network.ModelDiscoveryResult
import dev.pocket.app.ui.theme.PocketBlue
import dev.pocket.app.ui.theme.PocketGreen
import dev.pocket.app.ui.theme.PocketOrange
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import dev.pocket.app.ui.theme.AppThemeMode
import androidx.compose.material.icons.filled.Terminal

private enum class RootScreen(val label: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Default.Folder),
    TERMINAL("Terminal", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings),
}
private enum class WorkspaceTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Default.AutoAwesome),
    FILES("Files", Icons.Default.Folder),
    TERMINAL("Terminal", Icons.Default.Terminal),
    CHANGES("Changes", Icons.Default.Code),
    PREVIEW("Preview", Icons.Default.Preview),
}

@Composable
fun PocketDevApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }
    when {
        state.startupStage == StartupStage.CHECKING -> StartupLoadingScreen(state)
        !state.backgroundSetupComplete && state.startupStage == StartupStage.SETUP_REQUIRED ->
            BackgroundTaskSetupScreen(onContinue = viewModel::finishBackgroundSetup)
        state.startupStage == StartupStage.SETUP_REQUIRED -> RuntimeSetupPromptScreen(
            selectedStacks = state.selectedDevStacks,
            onToggleStack = viewModel::toggleDevStack,
            onDownload = viewModel::startRuntimeSetup,
        )
        state.startupStage == StartupStage.INSTALLING ||
            state.startupStage == StartupStage.INITIALIZING -> StartupLoadingScreen(state)
        state.startupStage == StartupStage.ERROR -> StartupErrorScreen(
            message = state.startupError,
            isOffline = state.startupErrorIsOffline,
            logs = state.startupLogs,
            onRetry = viewModel::retryStartup,
        )
        state.startupStage == StartupStage.MODEL_SETUP -> ProviderSetupScreen(
            initial = state.provider,
            onboarding = true,
            initialStep = 1,
            onSave = viewModel::finishOnboarding,
            onDiscover = viewModel::discoverModels,
            onValidate = viewModel::validateProvider,
        )
        state.startupStage == StartupStage.READY && !state.backgroundSetupComplete ->
            BackgroundTaskSetupScreen(onContinue = viewModel::finishBackgroundSetup)
        state.activeProject != null -> WorkspaceScreen(
            state = state,
            onBack = viewModel::closeProject,
            onSend = viewModel::sendPrompt,
            onStop = viewModel::stopTask,
            onApproval = viewModel::answerApproval,
            onRefreshFiles = viewModel::refreshProjectFiles,
            onOpenFile = viewModel::openFile,
            onCloseFile = viewModel::closeFile,
            onUndoChanges = viewModel::undoLastChanges,
            onKeepChanges = viewModel::keepLastChanges,
            onUndoFileChange = viewModel::undoFileChange,
            onKeepFileChange = viewModel::keepFileChange,
            onCreateChat = viewModel::createChat,
            onSwitchChat = viewModel::switchChat,
            onTerminalRun = viewModel::requestProjectTerminalCommand,
            onTerminalPrepare = viewModel::prepareProjectTerminalCommand,
            onTerminalDraftConsumed = viewModel::consumeProjectTerminalDraft,
            onTerminalOpened = viewModel::openProjectTerminal,
            onTerminalStop = viewModel::stopProjectTerminalCommand,
            onTerminalClear = viewModel::clearProjectTerminal,
            onTerminalConfirm = viewModel::confirmProjectTerminalCommand,
            onTerminalCancel = viewModel::cancelProjectTerminalCommand,
            onUseSuggestedProjectRoot = viewModel::useSuggestedProjectRoot,
            onExportProject = viewModel::exportActiveProject,
            onAddAttachments = viewModel::addChatAttachments,
            onRemoveAttachment = viewModel::removePendingAttachment,
            onOpenAttachment = viewModel::openChatAttachment,
        )
        else -> RootScreenHost(state, viewModel)
    }
}

@Composable
private fun BackgroundTaskSetupScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    fun notificationsAllowed(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun batteryUnrestricted(): Boolean = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var notificationGranted by remember { mutableStateOf(notificationsAllowed()) }
    var batteryGranted by remember { mutableStateOf(batteryUnrestricted()) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var taskProtectionConfirmed by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = notificationsAllowed()
        notificationDenied = !granted
        if (notificationGranted) currentStep = 1
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationGranted = notificationsAllowed()
        if (notificationGranted) currentStep = 1
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryGranted = batteryUnrestricted()
        if (batteryGranted) currentStep = 2
    }

    LaunchedEffect(Unit) {
        notificationGranted = notificationsAllowed()
        batteryGranted = batteryUnrestricted()
    }

    val currentIcon = when (currentStep) {
        0 -> Icons.Default.Notifications
        1 -> Icons.Default.BatterySaver
        else -> Icons.Default.Shield
    }
    val currentTitle = when (currentStep) {
        0 -> "Task notifications"
        1 -> "Background reliability"
        else -> "Task protection"
    }
    val currentDescription = when (currentStep) {
        0 -> "See live progress and receive an alert when Claude finishes or needs your attention."
        1 -> "Allow Pocket Dev to continue a task when you lock the phone or switch to another app."
        else -> "Keep the CPU awake only while a visible coding task is running, then release it automatically."
    }
    val currentPrivacyNote = when (currentStep) {
        0 -> "Only task progress, completion, and error notifications are sent."
        1 -> "You remain in control and can stop every task from its notification."
        else -> "The screen stays off. Protection is capped at 90 minutes and stops with the task."
    }
    val currentGranted = when (currentStep) {
        0 -> notificationGranted
        1 -> batteryGranted
        else -> taskProtectionConfirmed
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(28.dp))
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text("Prepare for reliable setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Initial setup usually takes 10–12 minutes. You may leave Pocket Dev in the background while it works.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(18.dp))
            StepDots(currentStep)
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF101722),
                border = BorderStroke(1.dp, Color(0xFF263247)),
            ) {
                Column {
                    PermissionSummaryRow(Icons.Default.Notifications, "Notifications", notificationGranted, currentStep == 0)
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = Color(0xFF202A3A))
                    PermissionSummaryRow(Icons.Default.BatterySaver, "Background", batteryGranted, currentStep == 1)
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = Color(0xFF202A3A))
                    PermissionSummaryRow(Icons.Default.Shield, "Task protection", taskProtectionConfirmed, currentStep == 2)
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(currentIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("STEP ${currentStep + 1} OF 3", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            Text(currentTitle, color = Color(0xFFE6EDF3), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (currentGranted) Icon(Icons.Default.Check, "Granted", tint = PocketGreen)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(currentDescription, color = Color(0xFFCBD3DF), fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Shield, null, tint = Color(0xFF8F9AAA), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(currentPrivacyNote, color = Color(0xFF8F9AAA), fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            when (currentStep) {
                                0 -> when {
                                    notificationGranted -> currentStep = 1
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationDenied -> {
                                        // Targets below API 33 can have notification prompts tied to
                                        // channel creation. Create channels only after this explicit tap.
                                        RuntimeExecutionService.ensureNotificationChannels(context)
                                        RuntimeSetupService.ensureNotificationChannel(context)
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    else -> notificationSettingsLauncher.launch(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                            Settings.EXTRA_APP_PACKAGE,
                                            context.packageName,
                                        ),
                                    )
                                }
                                1 -> if (batteryGranted) {
                                    currentStep = 2
                                } else {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    runCatching { batteryLauncher.launch(intent) }
                                        .onFailure {
                                            batteryLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                ),
                                            )
                                        }
                                }
                                else -> {
                                    taskProtectionConfirmed = true
                                    onContinue()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            when (currentStep) {
                                0 -> if (notificationGranted) "Next" else if (notificationDenied) "Open notification settings" else "Allow notifications"
                                1 -> if (batteryGranted) "Next" else "Open battery settings"
                                else -> "Enable and finish"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                    if (currentStep < 2 && !currentGranted) {
                        TextButton(
                            onClick = { currentStep += 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (currentStep == 0) "Continue without notifications" else "Continue without battery exemption")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "You can change these settings later. Android may still stop exceptionally heavy work when the device is low on memory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PermissionSummaryRow(
    icon: ImageVector,
    title: String,
    complete: Boolean,
    active: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (active) MaterialTheme.colorScheme.primary else Color(0xFF8F9AAA),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), color = Color(0xFFD7DEE8), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        when {
            complete -> Icon(Icons.Default.Check, "Complete", tint = PocketGreen, modifier = Modifier.size(18.dp))
            active -> Text("Required", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            else -> Text("Next", color = Color(0xFF6F7A8C), fontSize = 10.sp)
        }
    }
}

private data class DevStackVisuals(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val tag: String,
)

private fun getDevStackVisuals(stack: DevStack): DevStackVisuals = when (stack) {
    DevStack.WEB -> DevStackVisuals(
        icon = Icons.Default.Language,
        accentColor = Color(0xFF38BDF8),
        tag = "HTML · CSS · JS · TS",
    )
    DevStack.PYTHON -> DevStackVisuals(
        icon = Icons.Default.Terminal,
        accentColor = Color(0xFFFBBF24),
        tag = "python3 + pip + venv",
    )
    DevStack.ANDROID -> DevStackVisuals(
        icon = Icons.Default.Android,
        accentColor = Color(0xFF4ADE80),
        tag = "OpenJDK build tools",
    )
    DevStack.CPP -> DevStackVisuals(
        icon = Icons.Default.Memory,
        accentColor = Color(0xFFA78BFA),
        tag = "gcc + g++ + cmake",
    )
    DevStack.PHP -> DevStackVisuals(
        icon = Icons.Default.Dns,
        accentColor = Color(0xFF818CF8),
        tag = "php-cli + Composer",
    )
}

@Composable
private fun RuntimeSetupPromptScreen(
    selectedStacks: Set<DevStack>,
    onToggleStack: (DevStack) -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem / 1_073_741_824L
    val arm64 = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
    val compatible = arm64 && totalRamGb >= 4

    var currentStep by remember { mutableIntStateOf(0) }
    val setupScrollState = rememberScrollState()

    LaunchedEffect(currentStep) {
        setupScrollState.scrollTo(0)
    }

    if (currentStep > 0) {
        BackHandler { currentStep = 0 }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(setupScrollState),
        ) {
            Spacer(Modifier.height(24.dp))

            if (currentStep == 0) {
                // Step 0: Device Compatibility & Verification
                BrandMark()

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "Set up your phone for coding",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF0F6FC),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pocket Dev checks compatibility before downloading the private Linux runtime with real Claude Code, Node.js, and Git.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )

                Spacer(Modifier.height(24.dp))

                // Hardware & Compatibility Specs Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF10141D),
                    border = BorderStroke(1.dp, Color(0xFF222B3D)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Speed,
                                    null,
                                    tint = Color(0xFF9AA6B6),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "System Compatibility",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFFE6EDF3),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (compatible) PocketGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, if (compatible) PocketGreen.copy(alpha = 0.35f) else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = if (compatible) "Verified" else "Unsupported",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (compatible) PocketGreen else MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFF1F2737), thickness = 1.dp)

                        SpecRow(
                            icon = Icons.Default.Memory,
                            label = "Memory (RAM)",
                            value = "$totalRamGb GB · ${if (totalRamGb >= 8) "Full mode (8GB+)" else "Lite mode"}",
                            statusOk = totalRamGb >= 4,
                        )

                        SpecRow(
                            icon = Icons.Default.Code,
                            label = "Processor",
                            value = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                            statusOk = arm64,
                        )

                        SpecRow(
                            icon = Icons.Default.Storage,
                            label = "Download",
                            value = "~500 MB · Wi-Fi recommended",
                            statusOk = true,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Zero-Root Security Callout
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF101722),
                    border = BorderStroke(1.dp, Color(0xFF263247)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF192231), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF9AA6B6),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Zero-Root Isolated Environment",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFFF1F5F9),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Everything is installed in Pocket Dev's private app storage. No Termux, ADB root, or OS modifications required.",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFF94A3B8),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = { currentStep = 1 },
                    enabled = compatible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketOrange,
                        contentColor = Color(0xFF1A0C00),
                        disabledContainerColor = Color(0xFF222A38),
                        disabledContentColor = Color(0xFF5B697F),
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (compatible) "Continue to Tool Setup" else "Device not supported",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentStep = 0 }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFFE6EDF3))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TOOLCHAIN SETUP",
                        color = PocketOrange,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Choose your tools",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF0F6FC),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Start lightweight. You can install more toolchains later from Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Spacer(Modifier.height(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF101722),
                    border = BorderStroke(1.dp, Color(0xFF263247)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).background(Color(0xFF192231), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Terminal, null, tint = Color(0xFF9AA6B6), modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Core tools included", color = Color(0xFFE6EDF3), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            Text("Claude Code  ·  Node.js  ·  npm  ·  Git", color = Color(0xFF8F9AAA), fontSize = 11.sp)
                        }
                        Icon(Icons.Default.Check, "Included", tint = PocketGreen, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("OPTIONAL TOOLCHAINS", color = Color(0xFF8F9AAA), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                Spacer(Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF101722),
                    border = BorderStroke(1.dp, Color(0xFF263247)),
                ) {
                    Column {
                        DevStack.entries.forEachIndexed { index, stack ->
                            DevStackChoiceRow(
                                stack = stack,
                                selected = stack in selectedStacks,
                                onClick = { onToggleStack(stack) },
                            )
                            if (index != DevStack.entries.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 62.dp), color = Color(0xFF202A3A))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = Color(0xFF8F9AAA), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (selectedStacks.isEmpty()) "Core runtime only · smallest download"
                        else "${selectedStacks.size} optional toolchain${if (selectedStacks.size == 1) "" else "s"} selected",
                        color = Color(0xFF8F9AAA),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onDownload,
                    enabled = compatible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketOrange,
                        contentColor = Color(0xFF1A0C00),
                        disabledContainerColor = Color(0xFF222A38),
                        disabledContentColor = Color(0xFF5B697F),
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (compatible) "Install Pocket Dev" else "Device not supported",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        if (compatible) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DevStackChoiceRow(
    stack: DevStack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val visuals = getDevStackVisuals(stack)
    val conciseDescription = when (stack) {
        DevStack.WEB -> "Websites and JavaScript apps"
        DevStack.PYTHON -> "Scripts, automation and backends"
        DevStack.ANDROID -> "Java and Kotlin build tools"
        DevStack.CPP -> "Native apps and command-line tools"
        DevStack.PHP -> "PHP sites and Laravel projects"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PocketOrange.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0xFF192231), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(visuals.icon, null, tint = Color(0xFF9AA6B6), modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(stack.label, color = Color(0xFFE6EDF3), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(1.dp))
            Text(conciseDescription, color = Color(0xFF8F9AAA), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(21.dp)
                .background(if (selected) PocketOrange else Color.Transparent, RoundedCornerShape(6.dp))
                .border(
                    1.5.dp,
                    if (selected) PocketOrange else Color(0xFF536077),
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, "Selected", tint = Color(0xFF1A0C00), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SpecRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    statusOk: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
        imageVector = icon,
        contentDescription = null,
            tint = if (statusOk) Color(0xFF9AA6B6) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE6EDF3),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartupLoadingScreen(state: AppUiState) {
    val installing = state.startupStage == StartupStage.INSTALLING
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (installing) "Set up Pocket Dev" else "Pocket Dev") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (installing) {
                StepDots(0)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "STEP 1 OF 3",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PocketOrange,
                        letterSpacing = 1.1.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Local setup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                if (installing) "Build your workspace" else "Opening Pocket Dev",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    state.startupMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF101722),
                border = BorderStroke(1.dp, Color(0xFF263247)),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Installation progress", color = Color(0xFFE6EDF3), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${(state.startupProgress * 100).toInt()}%", color = Color(0xFFE6EDF3), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.startupProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = PocketOrange,
                        trackColor = Color(0xFF202A3A),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (installing) "Usually 10–12 minutes" else "Starting local tools",
                            color = Color(0xFF8F9AAA),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        state.startupBytes?.let { (downloaded, total) ->
                            Text(
                                "${formatMegabytes(downloaded)} / ${formatMegabytes(total)}",
                                color = Color(0xFF8F9AAA),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            if (installing) {
                Spacer(Modifier.height(14.dp))
                SetupLogPanel(
                    logs = state.startupLogs.ifEmpty { listOf("\$ ${state.startupMessage}") },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "You can leave Pocket Dev in the background and follow setup from the notification.",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SetupLogPanel(logs: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(logs.size, logs.lastOrNull()) {
        if (expanded && followLatest) {
            delay(20)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress && expanded) {
            followLatest = scrollState.maxValue - scrollState.value < 32
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = Color(0xFF101722),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF263247)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = PocketOrange,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "Live setup terminal" else logs.lastOrNull().orEmpty(),
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFC9D1D9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse setup details" else "Expand setup details",
                    tint = Color(0xFF8B949E),
                )
            }

            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color(0xFF273244),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    logs.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFC9D1D9),
                        )
                    }
                    Text(
                        text = "▌",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = PocketOrange,
                    )
                }
                if (!followLatest) {
                    TextButton(
                        onClick = {
                            followLatest = true
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Jump to latest") }
                }
            }
        }
    }
}

@Composable
private fun StartupErrorScreen(message: String?, isOffline: Boolean, logs: List<String>, onRetry: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Text(
                if (isOffline) "You're offline" else "Pocket Dev couldn't finish starting",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message ?: "Please try again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                        Toast.makeText(context, "Setup log copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy setup logs")
                }
            }
            Spacer(Modifier.height(24.dp))
            if (isOffline) {
                Button(
                    onClick = {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                        context.startActivity(Intent(action))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open internet settings")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            } else {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun RootScreenHost(state: AppUiState, viewModel: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(RootScreen.PROJECTS) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()
    val isTerminalRunning by viewModel.isTerminalRunning.collectAsStateWithLifecycle()
    val terminalLiveOutput by viewModel.terminalLiveOutput.collectAsStateWithLifecycle()
    val terminalCurrentCommand by viewModel.terminalCurrentCommand.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                RootScreen.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = screen == tab,
                        onClick = { screen = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                RootScreen.PROJECTS -> ProjectsScreen(
                    state = state,
                    onOpen = viewModel::openProject,
                    onCreate = viewModel::createProject,
                    onCreateQuickProject = viewModel::createQuickProject,
                    onRenameProject = viewModel::renameProject,
                    onDeleteProject = viewModel::deleteProject,
                    onSettings = { screen = RootScreen.SETTINGS },
                    onPing = viewModel::pingApi,
                    onToggleTheme = viewModel::toggleTheme,
                )
                RootScreen.TERMINAL -> TerminalScreen(
                    lines = terminalLines,
                    isRunning = isTerminalRunning,
                    onRun = viewModel::runTerminalCommand,
                    onClear = viewModel::clearTerminal,
                    onToggleTheme = viewModel::toggleTheme,
                    themeMode = state.themeMode,
                    liveOutput = terminalLiveOutput,
                    currentCommand = terminalCurrentCommand,
                )
                RootScreen.SETTINGS -> SettingsScreen(
                    state = state,
                    onSaveProvider = { profile, key ->
                        viewModel.updateProvider(profile, key)
                    },
                    onDiscoverModels = viewModel::discoverModels,
                    onValidateProvider = viewModel::validateProvider,
                    onSetThemeMode = viewModel::setThemeMode,
                    onPing = viewModel::pingApi,
                    onClearTerminal = viewModel::clearTerminal,
                    getSavedApiKey = viewModel::getSavedApiKey,
                    onInstallDevStack = viewModel::installDevStack,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSetupScreen(
    initial: ProviderProfile,
    onboarding: Boolean,
    initialStep: Int = if (onboarding) 0 else 1,
    onBack: (() -> Unit)? = null,
    onSave: (ProviderProfile, String) -> Unit,
    onDiscover: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidate: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onToggleTheme: (() -> Unit)? = null,
    themeMode: AppThemeMode = AppThemeMode.DARK,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var selected by rememberSaveable { mutableStateOf(initial.kind) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl.ifBlank { "https://api.deepseek.com/anthropic" }) }
    var model by rememberSaveable { mutableStateOf(initial.model.ifBlank { "deepseek-chat" }) }
    var apiKey by rememberSaveable { mutableStateOf("") }

    val handleBack: (() -> Unit)? = when {
        step > 1 -> { { step = 1 } }
        !onboarding && onBack != null -> onBack
        else -> null
    }

    if (handleBack != null) {
        BackHandler(onBack = handleBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Set up Pocket Dev" else "AI Provider & Settings") },
                navigationIcon = {
                    if (handleBack != null) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (onToggleTheme != null) {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        ) {
            if (onboarding) StepDots(step)
            Spacer(Modifier.height(16.dp))
            when (step) {
                0 -> DeviceCheckStep(context, onContinue = { step = 1 })
                1 -> ProviderChoiceStep(
                    selected = selected,
                    onSelected = {
                        if (selected != it) {
                            selected = it
                            baseUrl = if (it == ProviderKind.CUSTOM || it == ProviderKind.ANTHROPIC) {
                                "https://api.deepseek.com/anthropic"
                            } else {
                                it.defaultBaseUrl
                            }
                            model = if (it == ProviderKind.CUSTOM) "deepseek-chat" else it.defaultModel
                            apiKey = ""
                        }
                    },
                    onContinue = {
                        if (selected == ProviderKind.CLAUDE) {
                            onSave(ProviderProfile(selected), "")
                        } else step = 2
                    },
                )
                else -> ProviderCredentialsStep(
                    provider = selected,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    onBaseUrl = { baseUrl = it },
                    onModel = { model = it },
                    onApiKey = { apiKey = it },
                    hasStoredSecret = initial.kind == selected && initial.hasSecret,
                    onDiscover = { onDiscover(ProviderProfile(selected, baseUrl.trim(), model.trim()), apiKey) },
                    onValidate = { models -> onValidate(ProviderProfile(selected, baseUrl.trim(), model.trim()), apiKey, models) },
                    onSave = { onSave(ProviderProfile(selected, baseUrl.trim(), model.trim()), apiKey) },
                )
            }
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            Box(
                Modifier.height(5.dp).weight(1f)
                    .background(if (index <= step) PocketOrange else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun DeviceCheckStep(context: Context, onContinue: () -> Unit) {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem / 1_073_741_824L
    val arm64 = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
    val compatible = arm64 && totalRamGb >= 4
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BrandMark()
        Text("Your phone is the workspace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Pocket Dev checks compatibility before downloading the private Linux runtime.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        CheckRow(Icons.Default.Memory, "Memory", "$totalRamGb GB · ${if (totalRamGb >= 8) "Full mode" else "Lite mode"}", totalRamGb >= 4)
        CheckRow(Icons.Default.Code, "Processor", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown", arm64)
        CheckRow(Icons.Default.Storage, "Android", "Android ${Build.VERSION.RELEASE}", true)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
            Text(
                "Only open projects you trust. The local Linux environment is a compatibility layer, not a hardened security sandbox.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onContinue, enabled = compatible, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (compatible) "Continue" else "This device is not supported")
        }
    }
}

@Composable
private fun CheckRow(icon: ImageVector, title: String, value: String, passed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(icon, null, Modifier.padding(11.dp).size(22.dp), tint = if (passed) PocketGreen else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Icon(if (passed) Icons.Default.Check else Icons.Default.Warning, null, tint = if (passed) PocketGreen else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProviderChoiceStep(selected: ProviderKind, onSelected: (ProviderKind) -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "STEP 2 OF 3",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PocketOrange,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.weight(1f))
            Surface(
                color = PocketGreen.copy(alpha = 0.10f),
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Shield, null, tint = PocketGreen, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Secure setup", color = PocketGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Connect your AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose how Pocket Dev should access your coding model.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF101722),
            border = BorderStroke(1.dp, Color(0xFF263247)),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(ProviderKind.entries) { index, provider ->
                    ProviderChoiceRow(
                        provider = provider,
                        selected = selected == provider,
                        onClick = { onSelected(provider) },
                    )
                    if (index != ProviderKind.entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = Color(0xFF202A3A),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "API keys are encrypted in Android secure storage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PocketOrange,
                contentColor = Color(0xFF1A0C00),
            ),
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProviderChoiceRow(
    provider: ProviderKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (provider) {
        ProviderKind.CLAUDE -> Color(0xFFD97757)
        ProviderKind.ANTHROPIC -> Color(0xFFE7A26D)
        ProviderKind.LLM_ROUTER -> Color(0xFF5B8DEF)
        ProviderKind.OPENAI -> Color(0xFF19A77C)
        ProviderKind.KIMI -> Color(0xFF8B7CF6)
        ProviderKind.CUSTOM -> PocketOrange
    }
    val mark = when (provider) {
        ProviderKind.CLAUDE -> "C"
        ProviderKind.ANTHROPIC -> "A"
        ProviderKind.LLM_ROUTER -> "LR"
        ProviderKind.OPENAI -> "O"
        ProviderKind.KIMI -> "K"
        ProviderKind.CUSTOM -> "<>"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PocketOrange.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, color = accent, fontSize = if (mark.length > 1) 9.sp else 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(0xFFE6EDF3),
                )
                if (provider.experimental) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = PocketOrange.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(5.dp),
                    ) {
                        Text(
                            "Beta",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            color = PocketOrange,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                provider.subtitle,
                color = Color(0xFF8F9AAA),
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(19.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) PocketOrange else Color(0xFF596579),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(PocketOrange, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCredentialsStep(
    provider: ProviderKind,
    baseUrl: String,
    model: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onApiKey: (String) -> Unit,
    hasStoredSecret: Boolean,
    onDiscover: suspend () -> ModelDiscoveryResult,
    onValidate: suspend (List<DiscoveredModel>) -> ConnectionValidation,
    onSave: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember(baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasKey = apiKey.isNotBlank() || hasStoredSecret
    val filteredModels = remember(models, modelSearch) {
        val query = modelSearch.trim()
        if (query.isEmpty()) models else models.filter {
            it.id.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true)
        }
    }

    fun discoverModels(openWhenReady: Boolean = true) {
        scope.launch {
            isDiscovering = true
            status = null
            when (val result = onDiscover()) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    statusOk = true
                    status = "Found ${result.models.size} available model${if (result.models.size == 1) "" else "s"}."
                    if (model.isBlank() && result.models.isNotEmpty()) onModel(result.models.first().id)
                    if (openWhenReady && result.models.isNotEmpty()) showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    statusOk = false
                    status = result.message
                }
            }
            isDiscovering = false
        }
    }

    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = modelSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Available models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${filteredModels.size} of ${models.size} models",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(
                        onClick = { discoverModels(openWhenReady = false) },
                        enabled = !isDiscovering,
                    ) {
                        if (isDiscovering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh models")
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search model name or ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (filteredModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching models", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(filteredModels, key = { it.id }) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModel(option.id)
                                        status = null
                                        modelSearch = ""
                                        showModels = false
                                    }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (option.displayName != option.id) {
                                        Text(option.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Box(
                                    Modifier.size(20.dp).border(
                                        if (model == option.id) 2.dp else 1.dp,
                                        if (model == option.id) PocketOrange else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (model == option.id) Box(Modifier.size(9.dp).background(PocketOrange, CircleShape))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 3 OF 3", color = PocketOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("Encrypted locally", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(provider.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                if (provider.protocol.name.startsWith("OPENAI")) "Pocket Dev will translate Claude Code requests for this provider."
                else "Claude Code will connect through this API endpoint.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        baseUrl,
                        { onBaseUrl(it); status = null; models = emptyList() },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        apiKey,
                        { onApiKey(it); status = null },
                        label = { Text("API key") },
                        placeholder = { Text(if (hasStoredSecret) "Saved securely — leave blank to keep it" else "Enter your API key") },
                        supportingText = {
                            if (hasStoredSecret && apiKey.isBlank()) Text("A saved key is ready to use")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        model,
                        { onModel(it); status = null },
                        label = { Text("Model name") },
                        supportingText = { Text("Select an available model or enter an exact model ID.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    if (models.isEmpty()) discoverModels() else showModels = true
                },
                enabled = baseUrl.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(if (models.isEmpty()) Icons.Default.Search else Icons.Default.KeyboardArrowDown, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (models.isEmpty()) "Find available models" else "Available models (${models.size})")
            }
        }
        if (status != null) {
            item {
                Text(
                    status.orEmpty(),
                    color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }
        item {
            Button(
                    onClick = {
                        scope.launch {
                            isValidating = true
                            status = "Checking API key, model, and Claude Code settings…"
                            statusOk = true
                            when (val result = onValidate(models)) {
                                is ConnectionValidation.Success -> {
                                    status = result.message
                                    statusOk = true
                                    onSave()
                                }
                                is ConnectionValidation.Failure -> {
                                    status = result.message
                                    statusOk = false
                                }
                            }
                            isValidating = false
                        }
                    },
                    enabled = baseUrl.isNotBlank() && model.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (isValidating) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (isValidating) "Checking" else "Continue")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsScreen(
    state: AppUiState,
    onOpen: (Project) -> Unit,
    onCreate: (String) -> Unit,
    onCreateQuickProject: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSettings: () -> Unit,
    onPing: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val projects = state.projects
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { BrandMark(compact = true); Spacer(Modifier.width(9.dp)); Text("Pocket Dev", fontWeight = FontWeight.Bold) } },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (state.themeMode == dev.pocket.app.ui.theme.AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Build from your phone", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Chat, review changes, and preview your project.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                ApiStatusChip(state = state, onSettings = onSettings, onPing = onPing)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCreateQuickProject,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Chat, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Quick project", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showCreate = true },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("New project", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item { Text("Your projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = PocketOrange.copy(alpha = 0.15f),
                                modifier = Modifier.size(56.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = PocketOrange,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No projects yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "Create a named project or start instantly with a Quick Project.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpen(project) },
                        onRename = { onRenameProject(project.id, it) },
                        onDelete = { onDeleteProject(project.id) },
                    )
                }
            }
        }
    }
    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false },
        title = { Text("Create a starter project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                if (name.isNotBlank()) {
                    Text(
                        "Terminal folder: /workspace/${projectSlug(name)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name); showCreate = false; name = "" }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ApiStatusChip(state: AppUiState, onSettings: () -> Unit, onPing: () -> Unit) {
    val dotColor = when (state.apiPingStatus) {
        ApiPingStatus.OK -> PocketGreen
        ApiPingStatus.FAILED -> MaterialTheme.colorScheme.error
        ApiPingStatus.PINGING -> PocketOrange
        ApiPingStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val providerLabel = when {
        state.provider.model.isNotBlank() -> state.provider.model
        state.provider.baseUrl.isNotBlank() -> {
            runCatching { java.net.URI(state.provider.baseUrl).host ?: state.provider.kind.title }
                .getOrDefault(state.provider.kind.title)
        }
        else -> state.provider.kind.title
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onSettings).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            // Model / provider name
            Text(
                text = providerLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            // Ping button
            IconButton(
                onClick = onPing,
                modifier = Modifier.size(28.dp),
            ) {
                if (state.apiPingStatus == ApiPingStatus.PINGING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PocketOrange)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Ping API",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}



@Composable
private fun ProjectCard(project: Project, onOpen: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var menuOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    var showRename by rememberSaveable(project.id) { mutableStateOf(false) }
    var showDelete by rememberSaveable(project.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(project.id) { mutableStateOf(project.name) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Default.Folder, null, Modifier.padding(13.dp), tint = PocketOrange)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (project.kind == ProjectKind.QUICK_PROJECT) "Quick project" else project.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Text("/workspace/${project.slug}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("${project.language} · ${project.formattedUpdatedAt}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Project options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename project") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; renameText = project.name; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete project") },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDelete = true },
                    )
                }
            }
        }
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(renameText); showRename = false }, enabled = renameText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this project?") },
            text = { Text("Its chats, files, attachments, changes, and terminal history will be permanently removed.") },
            confirmButton = { TextButton(onClick = { onDelete(); showDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onCloseFile: () -> Unit,
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
    onUndoFileChange: (String) -> Unit,
    onKeepFileChange: (String) -> Unit,
    onCreateChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onTerminalRun: (String) -> Unit,
    onTerminalPrepare: (String) -> Unit,
    onTerminalDraftConsumed: () -> Unit,
    onTerminalOpened: () -> Unit,
    onTerminalStop: () -> Unit,
    onTerminalClear: () -> Unit,
    onTerminalConfirm: () -> Unit,
    onTerminalCancel: () -> Unit,
    onUseSuggestedProjectRoot: () -> Unit,
    onExportProject: (Uri) -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val exportProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> if (uri != null) onExportProject(uri) },
    )
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onAddAttachments,
    )
    val chatListState = rememberLazyListState()
    val chatItemCount = state.messages.size +
        (if (state.liveProcess.isNotEmpty() || state.liveThinking) 1 else 0) +
        (if (state.pendingApproval != null) 1 else 0)

    LaunchedEffect(state.activeChatId) {
        if (chatItemCount > 0) chatListState.scrollToItem(chatItemCount - 1)
    }
    // Follow new output only while the reader is already looking at the latest
    // messages. If the user scrolls up to read history, streaming must never
    // drag them back down; the "Latest" chip lets them jump back when ready.
    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text?.length,
        state.liveProcess.size,
        state.liveProcess.lastOrNull()?.detail,
        state.pendingApproval,
    ) {
        if (!state.isRunning || chatItemCount <= 0) return@LaunchedEffect
        val lastVisibleIndex = chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val readerIsNearBottom = lastVisibleIndex >= chatItemCount - 3
        if (readerIsNearBottom) {
            chatListState.animateScrollToItem(chatItemCount - 1)
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.CHAT) }
    var showChats by rememberSaveable { mutableStateOf(false) }
    val activeChat = state.projectChats.firstOrNull { it.id == state.activeChatId }

    // If a file is open, show the FileViewerScreen on top
    if (state.openedFilePath != null) {
        BackHandler(onBack = {
            onCloseFile()
            selectedTab = WorkspaceTab.FILES
        })
        FileViewerScreen(
            filePath = state.openedFilePath,
            content = state.openedFileContent,
            loading = state.fileContentLoading,
            onClose = {
                onCloseFile()
                selectedTab = WorkspaceTab.FILES
            },
        )
        return
    }

    if (showChats) {
        ChatSwitcherDialog(
            chats = state.projectChats,
            activeChatId = state.activeChatId,
            switchingEnabled = !state.isRunning,
            onDismiss = { showChats = false },
            onCreate = {
                onCreateChat()
                showChats = false
                selectedTab = WorkspaceTab.CHAT
            },
            onSwitch = { chatId ->
                onSwitchChat(chatId)
                showChats = false
                selectedTab = WorkspaceTab.CHAT
            },
        )
    }
    state.pendingTerminalCommand?.let { command ->
        AlertDialog(
            onDismissRequest = onTerminalCancel,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Run potentially destructive command?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This command can delete files, rewrite Git history, or change the project significantly.")
                    Surface(color = Color(0xFF14171E), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            command,
                            Modifier.fillMaxWidth().padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0),
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = onTerminalConfirm) { Text("Run anyway") } },
            dismissButton = { TextButton(onClick = onTerminalCancel) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            state.activeProject?.name.orEmpty(),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    Toast.makeText(context, state.activeProject?.name.orEmpty(), Toast.LENGTH_LONG).show()
                                },
                            ),
                        )
                        Text(
                            "${activeChat?.title ?: "Chat"} · ${state.provider.kind.title}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Projects") } },
                actions = {
                    IconButton(onClick = { showChats = true }) { Icon(Icons.Default.History, "Project chats") }
                    if (state.isRunning) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                WorkspaceTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (tab == WorkspaceTab.FILES) onRefreshFiles()
                            if (tab == WorkspaceTab.TERMINAL) onTerminalOpened()
                        },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                WorkspaceTab.CHAT -> ChatTab(
                    state.messages,
                    state.pendingApproval,
                    state.liveProcess,
                    state.isRunning,
                    onSend,
                    onStop,
                    onApproval,
                    listState = chatListState,
                    taskStartedAtMillis = state.workSegmentStartedAtMillis ?: state.taskStartedAtMillis,
                    taskFinishedAtMillis = state.taskFinishedAtMillis,
                    thinkingActive = state.liveThinking,
                    pendingAttachments = state.pendingAttachments,
                    onAttach = {
                        attachmentLauncher.launch(arrayOf("image/*", "text/*", "application/json", "application/xml"))
                    },
                    onRemoveAttachment = onRemoveAttachment,
                    onOpenAttachment = onOpenAttachment,
                    onRunInTerminal = { command ->
                        selectedTab = WorkspaceTab.TERMINAL
                        onTerminalOpened()
                        onTerminalPrepare(command)
                    },
                )
                WorkspaceTab.FILES -> FilesTab(
                    files = state.workspaceFiles,
                    loading = state.filesLoading,
                    suggestedProjectRoot = state.suggestedProjectRoot,
                    onRefresh = onRefreshFiles,
                    onOpenFile = onOpenFile,
                    onUseSuggestedProjectRoot = onUseSuggestedProjectRoot,
                    onExport = {
                        exportProjectLauncher.launch("${state.activeProject?.slug ?: "project"}.zip")
                    },
                )
                WorkspaceTab.TERMINAL -> TerminalScreen(
                    lines = state.projectTerminalLines,
                    isRunning = state.projectTerminalRunning,
                    onRun = onTerminalRun,
                    onClear = onTerminalClear,
                    onToggleTheme = {},
                    themeMode = state.themeMode,
                    title = "Project Terminal",
                    subtitle = "${state.projectTerminalCwd} · Ubuntu PRoot",
                    liveOutput = state.projectTerminalLiveOutput,
                    currentCommand = state.projectTerminalCommand,
                    commandDraft = state.projectTerminalDraft,
                    onCommandDraftConsumed = onTerminalDraftConsumed,
                    promptPath = state.projectTerminalCwd,
                    onStop = onTerminalStop,
                    showThemeAction = false,
                    showQuickCommands = false,
                    compactHeader = true,
                )
                WorkspaceTab.CHANGES -> ChangesTab(
                    state.changes,
                    onUndoChanges,
                    onKeepChanges,
                    onUndoFileChange,
                    onKeepFileChange,
                )
                WorkspaceTab.PREVIEW -> PreviewTab(state.previewReady, state.previewUrl)
            }
        }
    }
}

@Composable
private fun ChatSwitcherDialog(
    chats: List<ProjectChat>,
    activeChatId: String?,
    switchingEnabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project chats") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCreate, enabled = switchingEnabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("New chat")
                }
                if (!switchingEnabled) {
                    Text("Finish the running task before switching chats.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = switchingEnabled) { onSwitch(chat.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (chat.id == activeChatId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, fontWeight = if (chat.id == activeChatId) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                    Text(
                                        if (chat.id == activeChatId) "Current chat" else "Saved conversation",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (chat.id == activeChatId) Icon(Icons.Default.Check, "Current", tint = PocketGreen)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerScreen(
    filePath: String,
    content: String?,
    loading: Boolean,
    onClose: () -> Unit,
) {
    val fileName = filePath.substringAfterLast('/')
    val ext = fileName.substringAfterLast('.', "")
    val isMarkdown = ext == "md"
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, fontWeight = FontWeight.SemiBold)
                        Text(filePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close file") }
                },
                actions = {
                    if (!content.isNullOrEmpty()) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(content))
                            copied = true
                            scope.launch { delay(2000); copied = false }
                        }) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                "Copy file contents",
                                tint = if (copied) PocketOrange else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PocketOrange)
                    }
                }
                content == null -> {
                    EmptyState(Icons.Default.Description, "No content", "The file could not be read.")
                }
                isMarkdown -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item { MarkdownText(markdown = content, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                else -> {
                    // Code / plain-text viewer
                    LazyColumn(
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D1117)),
                    ) {
                        val lines = content.lines()
                        items(lines.size) { idx ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    modifier = Modifier
                                        .width(42.dp)
                                        .padding(start = 8.dp, end = 6.dp),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color(0xFF4A5568),
                                    textAlign = TextAlign.End,
                                )
                                Text(
                                    text = lines[idx],
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = Color(0xFFE2E8F0),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesTab(
    files: List<WorkspaceEntry>,
    loading: Boolean,
    suggestedProjectRoot: String?,
    onRefresh: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onUseSuggestedProjectRoot: () -> Unit,
    onExport: () -> Unit,
) {
    var expandedDirectories by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(files.map { it.path }) {
        val directories = files.asSequence().filter { it.isDirectory }.map { it.path }.toSet()
        expandedDirectories = expandedDirectories.filter { it in directories }
    }
    val expandedSet = expandedDirectories.toSet()
    val visibleFiles = files.filter { entry ->
        val segments = entry.path.split('/')
        segments.size == 1 || (1 until segments.size).all { depth ->
            segments.take(depth).joinToString("/") in expandedSet
        }
    }
    val directChildCounts = files.filter { candidate ->
        candidate.path.contains('/')
    }.groupingBy { candidate -> candidate.path.substringBeforeLast('/') }.eachCount()

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Files",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (expandedDirectories.isNotEmpty()) {
                        TextButton(onClick = { expandedDirectories = emptyList() }) {
                            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Collapse all", fontSize = 11.sp)
                        }
                    }
                    if (!loading && files.any { !it.isDirectory }) {
                        IconButton(onClick = onExport) { Icon(Icons.Default.Download, "Export project as ZIP") }
                    }
                    if (loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh files") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (suggestedProjectRoot != null) {
            item(key = "suggested-project-root") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Project folder detected", fontWeight = FontWeight.Bold)
                        Text(
                            "Use $suggestedProjectRoot as the project root so Chat, Terminal, Changes, and Preview all run from the same folder.",
                            fontSize = 13.sp,
                        )
                        Button(onClick = onUseSuggestedProjectRoot, modifier = Modifier.fillMaxWidth()) {
                            Text("Use $suggestedProjectRoot as project root")
                        }
                    }
                }
            }
        }
        if (!loading && files.isEmpty()) {
            item { EmptyState(Icons.Default.Folder, "No files yet", "Ask Claude Code to create something in this project.") }
        }
        items(visibleFiles, key = { it.path }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.isDirectory) {
                            expandedDirectories = if (entry.path in expandedSet) {
                                expandedDirectories.filterNot { it == entry.path || it.startsWith("${entry.path}/") }
                            } else {
                                expandedDirectories + entry.path
                            }
                        } else {
                            onOpenFile(entry)
                        }
                    }
                    .padding(start = (entry.depth * 20).dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.isDirectory) {
                    Icon(
                        if (entry.path in expandedSet) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        if (entry.path in expandedSet) "Collapse folder" else "Expand folder",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Icon(
                    if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    null,
                    tint = if (entry.isDirectory) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    if (entry.isDirectory) "${entry.name} (${directChildCounts[entry.path] ?: 0})" else entry.name,
                    Modifier.weight(1f),
                    color = if (!entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (!entry.isDirectory) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatFileSize(entry.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!entry.isDirectory) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(start = (entry.depth * 20 + 42).dp))
            }
        }
    }
}

@Composable
private fun ChatTab(
    messages: List<ChatMessage>,
    approval: ToolRequest?,
    liveProcess: List<ActivityItem>,
    isRunning: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (Boolean) -> Unit,
    listState: LazyListState,
    taskStartedAtMillis: Long?,
    taskFinishedAtMillis: Long?,
    thinkingActive: Boolean,
    pendingAttachments: List<ChatAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onRunInTerminal: (String) -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val chatScope = rememberCoroutineScope()
    // True while the newest item (message, live panel, or approval card) is on screen.
    val readerAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            lastVisible >= info.totalItemsCount - 3
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    if (message.workItems.isNotEmpty() || message.workedMillis > 0L) {
                        WorkBlockCard(message)
                    } else {
                        MessageBubble(message, onRunInTerminal, onOpenAttachment)
                    }
                }
                if (liveProcess.isNotEmpty() || thinkingActive) {
                    item(key = "live-claude-process") {
                        LiveClaudeProcess(
                            processItems = liveProcess,
                            isRunning = isRunning,
                            startedAtMillis = taskStartedAtMillis,
                            finishedAtMillis = taskFinishedAtMillis,
                            thinkingActive = thinkingActive,
                        )
                    }
                }
                approval?.let { request -> item { ApprovalCard(request, onApproval) } }
            }
            if (isRunning && !readerAtBottom) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clickable {
                            chatScope.launch {
                                listState.animateScrollToItem(
                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                )
                            }
                        },
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.padding(start = 13.dp, end = 15.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Latest",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    pendingAttachments.forEach { attachment ->
                        AttachmentChip(attachment = attachment, onOpen = null, onRemove = { onRemoveAttachment(attachment.id) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !isRunning && pendingAttachments.size < 5) {
                    Icon(Icons.Default.AttachFile, "Attach files", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("Message Claude…") },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp),
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                Spacer(Modifier.width(8.dp))
                if (isRunning) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.background(MaterialTheme.colorScheme.error, CircleShape),
                    ) {
                        Icon(Icons.Default.Stop, "Stop AI task", tint = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    IconButton(
                        onClick = { onSend(prompt); prompt = "" },
                        enabled = prompt.isNotBlank() || pendingAttachments.isNotEmpty(),
                        modifier = Modifier.background(PocketOrange, CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveClaudeProcess(
    processItems: List<ActivityItem>,
    isRunning: Boolean,
    startedAtMillis: Long?,
    finishedAtMillis: Long?,
    thinkingActive: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val elapsedSeconds = startedAtMillis?.let { rememberLiveElapsedSeconds(it).toLong() } ?: 0L
    ClaudeActivityDisclosure(
        items = processItems,
        headline = activityHeadline(processItems, elapsedSeconds, thinkingActive),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        isRunning = isRunning,
    )
}

@Composable
private fun WorkBlockCard(message: ChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val seconds = (message.workedMillis / 1_000L).coerceAtLeast(1L)
    ClaudeActivityDisclosure(
        items = message.workItems,
        headline = activityHeadline(message.workItems, seconds, message.workItems.isEmpty()),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
}

@Composable
private fun ClaudeActivityDisclosure(
    items: List<ActivityItem>,
    headline: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    isRunning: Boolean = false,
    timestamp: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 6.dp)) {
        if (items.isEmpty()) {
            ActivitySummaryRow(
                item = null,
                text = headline,
                trailing = {
                    ActivityDisclosureTrailing(timestamp, isRunning, expanded, muted)
                },
            )
        } else {
            items.forEachIndexed { index, item ->
                ActivitySummaryRow(
                    item = item,
                    text = compactActivityText(item),
                    trailing = if (index == 0) {
                        { ActivityDisclosureTrailing(timestamp, isRunning, expanded, muted) }
                    } else null,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 29.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("Reviewing the request and planning the next action.", fontSize = 12.sp, color = muted)
                } else {
                    items.forEach { item ->
                        Text(
                            "${activityName(item)} · ${activityDetail(item)}",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = muted,
                            fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySummaryRow(
    item: ActivityItem?,
    text: String,
    trailing: (@Composable () -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (item?.isCommand == true) Icons.Default.Terminal else Icons.Default.AutoAwesome,
            null,
            Modifier.size(16.dp),
            tint = muted,
        )
        Spacer(Modifier.width(9.dp))
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
private fun ActivityDisclosureTrailing(
    timestamp: String?,
    isRunning: Boolean,
    expanded: Boolean,
    color: Color,
) {
    if (timestamp != null) {
        Text(timestamp, fontSize = 10.sp, color = color)
        Spacer(Modifier.width(6.dp))
    }
    if (isRunning) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = color)
        Spacer(Modifier.width(7.dp))
    }
    Icon(
        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        if (expanded) "Collapse activity" else "Expand activity",
        Modifier.size(18.dp),
        tint = color,
    )
}

private fun compactActivityText(item: ActivityItem): String = "${activityName(item)} · ${activityDetail(item).replace(Regex("\\s+"), " ").take(105)}"

private fun activityDetail(item: ActivityItem): String {
    if (item.title == "Think" && item.detail.contains("reasoning tokens processed", true)) {
        return "Reviewed the request and planned the next action"
    }
    return item.detail.ifBlank { item.title }
}

private fun activityHeadline(items: List<ActivityItem>, seconds: Long, thinking: Boolean): String {
    val latest = items.lastOrNull()
    if (latest == null) return "Think · Analyzing the request · ${formatDuration(seconds)}"
    if (thinking && latest.title == "Think") return "Think · ${latest.detail} · ${formatDuration(seconds)}"
    val detail = latest.detail.replace(Regex("\\s+"), " ").trim().ifBlank { latest.title }
    return "${activityName(latest)} · ${detail.take(100)}"
}

private fun activityName(item: ActivityItem): String = item.title
    .removePrefix("Running ")
    .removeSuffix(" completed")
    .replaceFirstChar { it.uppercase() }

private fun completedProcessSummary(
    processItems: List<ActivityItem>,
    startedAtMillis: Long?,
    finishedAtMillis: Long?,
): String {
    val stopped = processItems.lastOrNull()?.title?.startsWith("Task stopped") == true
    val outcome = if (stopped) "Task stopped" else "Task completed"
    val steps = "${processItems.size} step${if (processItems.size == 1) "" else "s"}"
    val duration = startedAtMillis?.let { start ->
        val end = finishedAtMillis ?: System.currentTimeMillis()
        formatDuration(((end - start) / 1000L).coerceAtLeast(0))
    }
    return if (duration != null) "$outcome · $duration · $steps" else "$outcome · $steps"
}

@Composable
private fun rememberLiveElapsedSeconds(startedAtMillis: Long): Int {
    var seconds by remember(startedAtMillis) {
        mutableIntStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            delay(1_000)
            seconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0)
        }
    }
    return seconds
}

private fun formatDuration(totalSeconds: Long): String = when {
    totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ${(totalSeconds % 3_600) / 60}m"
    totalSeconds >= 60 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
    else -> "${totalSeconds}s"
}

@Composable
private fun MessageBubble(message: ChatMessage, onRunInTerminal: (String) -> Unit, onOpenAttachment: (ChatAttachment) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (message.fromUser) .82f else .92f),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                SelectionContainer {
                    if (message.fromUser) {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            onRunCode = onRunInTerminal,
                        )
                    }
                }
                if (message.attachments.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.attachments.forEach { attachment ->
                            AttachmentChip(attachment = attachment, onOpen = { onOpenAttachment(attachment) }, onRemove = null)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: ChatAttachment,
    onOpen: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val icon = when {
        attachment.mimeType.startsWith("image/") -> Icons.Default.Image
        else -> Icons.Default.Description
    }
    Surface(
        modifier = Modifier.then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 9.dp, end = if (onRemove == null) 10.dp else 3.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(17.dp), tint = PocketOrange)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(attachment.displayName, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatFileSize(attachment.sizeBytes), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, "Remove attachment", Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(request: ToolRequest, onApproval: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = PocketOrange)
                Spacer(Modifier.width(8.dp)); Text("Review this action", fontWeight = FontWeight.Bold)
            }
            Text(request.explanation)
            request.affectedPaths.forEach { Text("• $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApproval(false) }, Modifier.weight(1f)) { Text("Reject") }
                Button(onClick = { onApproval(true) }, Modifier.weight(1f)) { Text("Allow once") }
            }
        }
    }
}

@Composable
private fun FilesTab(files: List<WorkspaceEntry>, loading: Boolean, onRefresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Project files", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh files") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!loading && files.isEmpty()) {
            item { EmptyState(Icons.Default.Folder, "No files yet", "Ask Claude Code to create something in this project.") }
        }
        items(files, key = { it.path }) { entry ->
            Row(
                Modifier.fillMaxWidth().padding(start = (entry.depth * 20).dp).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    null,
                    tint = if (entry.isDirectory) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(11.dp))
                Text(entry.name, Modifier.weight(1f))
                if (!entry.isDirectory) {
                    Text(formatFileSize(entry.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

@Composable
private fun ChangesTab(
    changes: List<ChangeItem>,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
    onUndoFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
) {
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Changes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Review everything the AI changed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (changes.isEmpty()) item { EmptyState(Icons.Default.Code, "No changes yet", "Ask Pocket Dev to update your project.") }
        items(changes, key = { it.path }) { change ->
            val expanded = expandedPath == change.path
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedPath = if (expanded) null else change.path }.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(change.path, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                if (expanded) "Hide line-by-line diff" else "Tap to review diff",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("+${change.additions}", color = PocketGreen)
                        Spacer(Modifier.width(7.dp))
                        Text("-${change.deletions}", color = MaterialTheme.colorScheme.error)
                    }
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier.fillMaxWidth().background(Color(0xFF0B0E14)).horizontalScroll(rememberScrollState()),
                        ) {
                            change.diffLines.forEach { line -> DiffLineRow(line) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    expandedPath = null
                                    onUndoFile(change.path)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Undo file") }
                            Button(
                                onClick = {
                                    expandedPath = null
                                    onKeepFile(change.path)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Keep file") }
                        }
                    }
                }
            }
        }
        if (changes.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onUndo, Modifier.weight(1f)) { Text("Undo task") }
                Button(onClick = onKeep, Modifier.weight(1f)) { Text("Keep changes") }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val marker = when (line.type) {
        DiffLineType.ADDITION -> "+"
        DiffLineType.DELETION -> "-"
        DiffLineType.CONTEXT -> " "
        DiffLineType.INFO -> "·"
    }
    val background = when (line.type) {
        DiffLineType.ADDITION -> Color(0xFF123226)
        DiffLineType.DELETION -> Color(0xFF3A1D22)
        else -> Color.Transparent
    }
    val foreground = when (line.type) {
        DiffLineType.ADDITION -> Color(0xFF83E6B8)
        DiffLineType.DELETION -> Color(0xFFFFA4A4)
        DiffLineType.INFO -> Color(0xFF8993A4)
        DiffLineType.CONTEXT -> Color(0xFFD5DAE3)
    }
    val oldNumber = line.oldLine?.toString().orEmpty().padStart(4)
    val newNumber = line.newLine?.toString().orEmpty().padStart(4)
    Text(
        text = "$oldNumber $newNumber  $marker ${line.text}",
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 2.dp),
        color = foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false,
    )
}

@Composable
private fun PreviewTab(ready: Boolean, url: String?) {
    if (!ready || url == null) {
        EmptyState(Icons.Default.PlayArrow, "Preview not running", "Start a local web server in the project Terminal. Its localhost URL will appear here automatically.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(PocketGreen, CircleShape)); Spacer(Modifier.width(8.dp)); Text(url, fontSize = 12.sp, maxLines = 1)
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url ?: return true
                            return !target.isLoopbackPreviewUrl()
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val target = request?.url ?: return blockedPreviewResponse()
                            return if (target.isLoopbackPreviewUrl()) null else blockedPreviewResponse()
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { webView -> if (webView.url != url) webView.loadUrl(url) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun Uri.isLoopbackPreviewUrl(): Boolean =
    scheme in setOf("data", "blob", "about") ||
        (scheme in setOf("http", "https", "ws", "wss") && host in setOf("127.0.0.1", "localhost", "0.0.0.0"))

private fun blockedPreviewResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier, compact: Boolean = false) {
    val size = if (compact) 32.dp else 50.dp
    val iconSize = if (compact) 17.dp else 24.dp
    val cornerRadius = if (compact) 9.dp else 14.dp
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(size)
            .background(
                color = primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(cornerRadius),
            )
            .border(
                width = 1.dp,
                color = primary.copy(alpha = 0.32f),
                shape = RoundedCornerShape(cornerRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = "Pocket Dev",
            modifier = Modifier.size(iconSize),
            tint = primary,
        )
    }
}
