package dev.pocket.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocket.app.model.ActivityItem
import dev.pocket.app.model.ChangeItem
import dev.pocket.app.model.ChatMessage
import dev.pocket.app.model.Project
import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile
import dev.pocket.app.model.ToolRequest
import dev.pocket.app.model.WorkspaceEntry
import dev.pocket.app.network.ConnectionValidation
import dev.pocket.app.network.DiscoveredModel
import dev.pocket.app.network.ModelDiscoveryResult
import dev.pocket.app.ui.theme.PocketGreen
import dev.pocket.app.ui.theme.PocketOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RootScreen { PROJECTS, SETTINGS }
private enum class WorkspaceTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Default.AutoAwesome),
    FILES("Files", Icons.Default.Folder),
    CHANGES("Changes", Icons.Default.Code),
    PREVIEW("Preview", Icons.Default.Preview),
    ACTIVITY("Activity", Icons.Default.History),
}

@Composable
fun PocketDevApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.startupStage == StartupStage.SETUP_REQUIRED -> RuntimeSetupPromptScreen(viewModel::startRuntimeSetup)
        state.startupStage == StartupStage.INSTALLING ||
            state.startupStage == StartupStage.INITIALIZING ||
            state.startupStage == StartupStage.CHECKING -> StartupLoadingScreen(state)
        state.startupStage == StartupStage.ERROR -> StartupErrorScreen(state.startupError, viewModel::retryStartup)
        state.startupStage == StartupStage.MODEL_SETUP -> ProviderSetupScreen(
            initial = state.provider,
            onboarding = true,
            initialStep = 1,
            onSave = viewModel::finishOnboarding,
            onDiscover = viewModel::discoverModels,
            onValidate = viewModel::validateProvider,
        )
        state.activeProject != null -> WorkspaceScreen(
            state = state,
            onBack = viewModel::closeProject,
            onSend = viewModel::sendPrompt,
            onApproval = viewModel::answerApproval,
            onRefreshFiles = viewModel::refreshProjectFiles,
        )
        else -> RootScreenHost(state, viewModel)
    }
}

@Composable
private fun RuntimeSetupPromptScreen(onDownload: () -> Unit) {
    val context = LocalContext.current
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem / 1_073_741_824L
    val arm64 = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
    val compatible = arm64 && totalRamGb >= 4
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text("Set up your phone for coding", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Pocket Dev needs to download and install a private Linux workspace and real Claude Code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            CheckRow(Icons.Default.Memory, "Memory", "$totalRamGb GB · ${if (totalRamGb >= 8) "Full mode" else "Lite mode"}", totalRamGb >= 4)
            Spacer(Modifier.height(12.dp))
            CheckRow(Icons.Default.Code, "Processor", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown", arm64)
            Spacer(Modifier.height(12.dp))
            CheckRow(Icons.Default.Storage, "Download", "About 350 MB · Wi-Fi recommended", true)
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Text(
                    "Everything is installed in Pocket Dev's private storage. You do not need Termux or root access.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDownload, enabled = compatible, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (compatible) "Download and install" else "This device is not supported")
            }
        }
    }
}

@Composable
private fun StartupLoadingScreen(state: AppUiState) {
    val installing = state.startupStage == StartupStage.INSTALLING
    val tips = remember(installing) {
        if (installing) listOf(
            "Giving your phone a tiny Linux workshop…",
            "Sharpening the coding tools…",
            "Teaching files where their new home is…",
            "Almost ready for your first build…",
            "No terminal magic required.",
        ) else listOf(
            "Waking up Claude Code…",
            "Reconnecting the private workspace…",
            "Checking your coding tools…",
            "Getting your projects ready…",
        )
    }
    var tipIndex by remember(installing) { mutableIntStateOf(0) }
    LaunchedEffect(installing) {
        while (true) {
            delay(1_900)
            val choices = tips.indices.filter { it != tipIndex }
            tipIndex = choices.random()
        }
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark()
            Spacer(Modifier.height(28.dp))
            Text(
                if (installing) "Building your pocket workspace" else "Opening Pocket Dev",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                state.startupMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { state.startupProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = PocketOrange,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(state.startupProgress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                state.startupBytes?.let { (downloaded, total) ->
                    Text("${formatMegabytes(downloaded)} / ${formatMegabytes(total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(34.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    tips[tipIndex],
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (installing) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Keep Pocket Dev open during the first setup.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StartupErrorScreen(message: String?, onRetry: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Text(
                "Pocket Dev couldn't finish starting",
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
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun RootScreenHost(state: AppUiState, viewModel: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(RootScreen.PROJECTS) }
    AnimatedContent(screen, label = "root") { current ->
        when (current) {
            RootScreen.PROJECTS -> ProjectsScreen(
                state = state,
                onOpen = viewModel::openProject,
                onCreate = viewModel::createProject,
                onSettings = { screen = RootScreen.SETTINGS },
            )
            RootScreen.SETTINGS -> ProviderSetupScreen(
                initial = state.provider,
                onboarding = false,
                onBack = { screen = RootScreen.PROJECTS },
                onSave = { profile, key ->
                    viewModel.updateProvider(profile, key)
                    screen = RootScreen.PROJECTS
                },
                onDiscover = viewModel::discoverModels,
                onValidate = viewModel::validateProvider,
            )
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
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var selected by rememberSaveable { mutableStateOf(initial.kind) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var apiKey by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Set up Pocket Dev" else "AI provider") },
                navigationIcon = {
                    if (!onboarding) IconButton(onClick = { onBack?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                            baseUrl = it.defaultBaseUrl
                            model = it.defaultModel
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
                    onBack = { step = 1 },
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
        Text("Choose your AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("You can change this later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(ProviderKind.entries) { provider ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelected(provider) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected == provider) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = CardDefaults.outlinedCardBorder().takeIf { selected != provider },
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = if (selected == provider) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.title, fontWeight = FontWeight.SemiBold)
                                if (provider.experimental) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("EXPERIMENTAL", color = PocketOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(provider.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        if (selected == provider) Icon(Icons.Default.Check, null)
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp)) { Text("Continue") }
    }
}

@Composable
private fun ProviderCredentialsStep(
    provider: ProviderKind,
    baseUrl: String,
    model: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onBack: () -> Unit,
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
    val hasKey = apiKey.isNotBlank() || hasStoredSecret

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Text(provider.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (provider.protocol.name.startsWith("OPENAI")) "Pocket Dev will translate Claude Code requests for this provider."
                else "Claude Code will connect through this API endpoint.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                baseUrl,
                { onBaseUrl(it); status = null },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                apiKey,
                { onApiKey(it); status = null },
                label = { Text(if (hasStoredSecret && apiKey.isBlank()) "API key (saved securely)" else "API key") },
                placeholder = { if (hasStoredSecret) Text("Leave blank to keep saved key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isDiscovering = true
                        status = null
                        when (val result = onDiscover()) {
                            is ModelDiscoveryResult.Success -> {
                                models = result.models
                                statusOk = true
                                status = "Found ${result.models.size} available model${if (result.models.size == 1) "" else "s"}."
                                if (model.isBlank() && result.models.isNotEmpty()) onModel(result.models.first().id)
                            }
                            is ModelDiscoveryResult.Failure -> {
                                statusOk = false
                                status = result.message
                            }
                        }
                        isDiscovering = false
                    }
                },
                enabled = baseUrl.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (models.isEmpty()) "Get available models" else "Refresh model list")
            }
        }
        if (models.isNotEmpty()) {
            item { Text("Select a model", fontWeight = FontWeight.SemiBold) }
            items(models, key = { it.id }) { option ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onModel(option.id)
                        status = null
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (model == option.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = CardDefaults.outlinedCardBorder().takeIf { model != option.id },
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(option.displayName, fontWeight = FontWeight.SemiBold)
                            if (option.displayName != option.id) Text(option.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (model == option.id) Icon(Icons.Default.Check, null)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                model,
                { onModel(it); status = null },
                label = { Text("Custom model name") },
                supportingText = { Text("You can type an exact model ID even if it is not listed.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                Text("The key is encrypted with Android Keystore and never shown again.", Modifier.padding(14.dp), fontSize = 13.sp)
            }
        }
        if (status != null) {
            item {
                Text(
                    status.orEmpty(),
                    color = if (statusOk) PocketGreen else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, enabled = !isValidating, modifier = Modifier.weight(1f)) { Text("Back") }
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
                    modifier = Modifier.weight(1f),
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsScreen(state: AppUiState, onOpen: (Project) -> Unit, onCreate: (String) -> Unit, onSettings: () -> Unit) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { BrandMark(compact = true); Spacer(Modifier.width(9.dp)); Text("Pocket Dev", fontWeight = FontWeight.Bold) } },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
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
                AssistChip(onClick = onSettings, label = { Text(state.provider.kind.title) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) })
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New project")
                }
                Spacer(Modifier.height(10.dp))
                Text("Your projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(state.projects, key = { it.id }) { project -> ProjectCard(project) { onOpen(project) } }
        }
    }
    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false },
        title = { Text("Create a starter project") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name); showCreate = false; name = "" }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Default.Folder, null, Modifier.padding(13.dp), tint = PocketOrange)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, fontWeight = FontWeight.SemiBold)
                Text(project.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text("${project.language} · ${project.updatedAt}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onRefreshFiles: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.CHAT) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(state.activeProject?.name.orEmpty(), fontWeight = FontWeight.SemiBold); Text(state.provider.kind.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Projects") } },
                actions = { if (state.isRunning) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                WorkspaceTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (tab == WorkspaceTab.FILES) onRefreshFiles()
                        },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                WorkspaceTab.CHAT -> ChatTab(state.messages, state.pendingApproval, state.isRunning, onSend, onApproval)
                WorkspaceTab.FILES -> FilesTab(state.workspaceFiles, state.filesLoading, onRefreshFiles)
                WorkspaceTab.CHANGES -> ChangesTab(state.changes)
                WorkspaceTab.PREVIEW -> PreviewTab(state.previewReady)
                WorkspaceTab.ACTIVITY -> ActivityTab(state.activity)
            }
        }
    }
}

@Composable
private fun ChatTab(messages: List<ChatMessage>, approval: ToolRequest?, isRunning: Boolean, onSend: (String) -> Unit, onApproval: (Boolean) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
            approval?.let { request -> item { ApprovalCard(request, onApproval) } }
        }
        HorizontalDivider()
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("What do you want to build?") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardActions = KeyboardActions(onSend = { if (!isRunning) { onSend(prompt); prompt = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { onSend(prompt); prompt = "" },
                enabled = prompt.isNotBlank() && !isRunning,
                modifier = Modifier.background(PocketOrange, CircleShape),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.Black) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (message.fromUser) .82f else .92f),
        ) { Text(message.text, Modifier.padding(14.dp)) }
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
private fun ChangesTab(changes: List<ChangeItem>) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Changes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Review everything the AI changed.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (changes.isEmpty()) item { EmptyState(Icons.Default.Code, "No changes yet", "Ask Pocket Dev to update your project.") }
        items(changes) { change ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null); Spacer(Modifier.width(10.dp)); Text(change.path, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("+${change.additions}", color = PocketGreen); Spacer(Modifier.width(7.dp)); Text("-${change.deletions}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (changes.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = {}, Modifier.weight(1f)) { Text("Undo task") }
                Button(onClick = {}, Modifier.weight(1f)) { Text("Keep changes") }
            }
        }
    }
}

@Composable
private fun PreviewTab(ready: Boolean) {
    if (!ready) {
        EmptyState(Icons.Default.PlayArrow, "Preview not running", "Approve a project update to start the demo preview.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(PocketGreen, CircleShape)); Spacer(Modifier.width(8.dp)); Text("127.0.0.1:4173", fontSize = 12.sp)
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    loadDataWithBaseURL(
                        "https://pocket.local/",
                        """<html><meta name='viewport' content='width=device-width'><body style='background:#0b0e14;color:white;font-family:sans-serif;padding:40px'><p style='color:#f28c52'>POCKET DEV</p><h1>Your project is ready.</h1><p style='color:#aab2c0;line-height:1.6'>This secure preview panel will display the web server running inside the local workspace.</p><button style='padding:14px 20px;background:#f28c52;border:0;border-radius:12px;font-weight:bold'>Explore project</button></body></html>""",
                        "text/html", "UTF-8", null,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ActivityTab(activity: List<ActivityItem>) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Text("Activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Technical work translated into plain language.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (activity.isEmpty()) item { EmptyState(Icons.Default.History, "Nothing running", "Task progress will appear here.") }
        items(activity) { item -> ActivityRow(item) }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = if (item.isComplete) PocketGreen.copy(alpha = .18f) else PocketOrange.copy(alpha = .18f)) {
            if (item.isComplete) Icon(Icons.Default.Check, null, Modifier.padding(7.dp).size(16.dp), tint = PocketGreen)
            else CircularProgressIndicator(Modifier.padding(8.dp).size(14.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

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
private fun BrandMark(compact: Boolean = false) {
    Surface(shape = RoundedCornerShape(if (compact) 10.dp else 16.dp), color = PocketOrange) {
        Icon(Icons.Default.Code, null, Modifier.padding(if (compact) 7.dp else 12.dp).size(if (compact) 20.dp else 34.dp), tint = Color.Black)
    }
}
