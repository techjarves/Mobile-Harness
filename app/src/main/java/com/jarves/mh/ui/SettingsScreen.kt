package com.jarves.mh.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacySettingsScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onPing: () -> Unit,
    onClearTerminal: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    onInstallDevStack: (DevStack) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedKind by rememberSaveable(state.provider.kind) { mutableStateOf(state.provider.kind) }
    var baseUrl by rememberSaveable(state.provider.baseUrl) {
        mutableStateOf(state.provider.baseUrl.ifBlank { "https://api.deepseek.com/anthropic" })
    }
    var model by rememberSaveable(state.provider.model) {
        mutableStateOf(state.provider.model.ifBlank { "deepseek-chat" })
    }
    var apiKey by rememberSaveable { mutableStateOf(getSavedApiKey(state.provider.kind)) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var validationStatus by remember { mutableStateOf<String?>(null) }
    var validationOk by remember { mutableStateOf(false) }
    var discoveredModels by remember(baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var terminalClearedMessage by remember { mutableStateOf(false) }
    var showChildProcessHelp by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = PocketOrange,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Settings", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // -------------------------------------------------------------
            // 1. APPEARANCE / THEME
            // -------------------------------------------------------------
            item {
                SectionHeader(
                    title = "Appearance",
                    subtitle = "Customize app theme and styling",
                    icon = Icons.Default.Tune,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeOptionCard(
                        title = "Dark",
                        icon = Icons.Default.DarkMode,
                        selected = state.themeMode == AppThemeMode.DARK,
                        onClick = { onSetThemeMode(AppThemeMode.DARK) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionCard(
                        title = "Light",
                        icon = Icons.Default.LightMode,
                        selected = state.themeMode == AppThemeMode.LIGHT,
                        onClick = { onSetThemeMode(AppThemeMode.LIGHT) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionCard(
                        title = "System",
                        icon = Icons.Default.PhoneAndroid,
                        selected = state.themeMode == AppThemeMode.SYSTEM,
                        onClick = { onSetThemeMode(AppThemeMode.SYSTEM) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // -------------------------------------------------------------
            // 2. DEVELOPER TOOLS
            // -------------------------------------------------------------
            item {
                SectionHeader(
                    title = "Developer tools",
                    subtitle = "Language toolchains inside the Ubuntu runtime",
                    icon = Icons.Default.Code,
                )
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "Node.js, npm, and Git are always installed — Claude Code runs on them.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        DevStack.entries.forEachIndexed { index, stack ->
                            val installed = stack in state.installedDevStacks
                            val installing = state.devStackInstalling == stack
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stack.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            if (installed) {
                                                Spacer(Modifier.width(7.dp))
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    "Installed",
                                                    tint = PocketGreen,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            stack.installsSummary,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    when {
                                        installing -> {
                                            Text(
                                                "${(state.devStackProgress * 100).toInt().coerceIn(0, 100)}%",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = PocketOrange,
                                            )
                                        }
                                        installed -> Unit // badge already shown next to the name
                                        else -> {
                                            OutlinedButton(
                                                onClick = { onInstallDevStack(stack) },
                                                enabled = state.devStackInstalling == null,
                                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 5.dp),
                                            ) { Text("Add") }
                                        }
                                    }
                                }
                                if (installing) {
                                    Spacer(Modifier.height(9.dp))
                                    LinearProgressIndicator(
                                        progress = { state.devStackProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = PocketOrange,
                                        trackColor = MaterialTheme.colorScheme.surface,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            state.devStackMessage ?: "Working…",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        state.devStackBytes?.let { (downloaded, total) ->
                                            if (total > 0) {
                                                Text(
                                                    "${formatBytes(downloaded)} / ${formatBytes(total)}",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                } else if (index != DevStack.entries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(top = 11.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                            if (!installing && index != DevStack.entries.lastIndex) {
                                Spacer(Modifier.height(11.dp))
                            }
                        }
                        if (state.devStackInstalling == null) {
                            state.devStackMessage?.let { message ->
                                Spacer(Modifier.height(10.dp))
                                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // 3. AI PROVIDER & CONNECTION
            // -------------------------------------------------------------
            item {
                SectionHeader(
                    title = "AI Provider & Model",
                    subtitle = "Configure endpoint, model, and API key",
                    icon = Icons.Default.SmartToy,
                )
                Spacer(Modifier.height(10.dp))

                // Current Live Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ACTIVE ENDPOINT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                state.provider.model.ifBlank { "deepseek-chat" },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                state.provider.baseUrl.ifBlank { "Default Gateway" },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        // Ping indicator + manual test button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable { onPing() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (state.apiPingStatus) {
                                                ApiPingStatus.OK -> PocketGreen
                                                ApiPingStatus.FAILED -> MaterialTheme.colorScheme.error
                                                ApiPingStatus.PINGING -> PocketOrange
                                                ApiPingStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when (state.apiPingStatus) {
                                        ApiPingStatus.OK -> "Online"
                                        ApiPingStatus.FAILED -> "Offline"
                                        ApiPingStatus.PINGING -> "Checking"
                                        ApiPingStatus.IDLE -> "Test"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Ping",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Provider Kind Selection
                Text("Select Provider", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderKind.entries.forEach { kind ->
                        val isSelected = selectedKind == kind
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedKind != kind) {
                                        selectedKind = kind
                                        baseUrl = if (kind == ProviderKind.CUSTOM || kind == ProviderKind.ANTHROPIC) {
                                            "https://api.deepseek.com/anthropic"
                                        } else {
                                            kind.defaultBaseUrl
                                        }
                                        model = if (kind == ProviderKind.CUSTOM) "deepseek-chat" else kind.defaultModel
                                        val saved = getSavedApiKey(kind)
                                        apiKey = saved
                                        validationStatus = null
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (!isSelected) CardDefaults.outlinedCardBorder() else null,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    tint = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(kind.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        if (kind.experimental) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("EXPERIMENTAL", color = PocketOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(kind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; validationStatus = null },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketOrange,
                    ),
                )

                Spacer(Modifier.height(10.dp))

                // Model Name + Discover Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; validationStatus = null },
                        label = { Text("Model name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PocketOrange,
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isDiscovering = true
                                validationStatus = "Fetching models from …"
                                validationOk = true
                                val profile = ProviderProfile(selectedKind, baseUrl = baseUrl.trim(), model = model.trim())
                                when (val result = onDiscoverModels(profile, apiKey.trim())) {
                                    is ModelDiscoveryResult.Success -> {
                                        discoveredModels = result.models
                                        result.models.firstOrNull()?.let { model = it.id }
                                        validationStatus = "Discovered ${result.models.size} models. Selected ${model}."
                                        validationOk = true
                                    }
                                    is ModelDiscoveryResult.Failure -> {
                                        validationStatus = result.message
                                        validationOk = false
                                    }
                                }
                                isDiscovering = false
                            }
                        },
                        enabled = baseUrl.isNotBlank() && !isDiscovering && !isValidating,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(54.dp),
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Dns, contentDescription = "Discover", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Fetch", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // API Key with eye toggle
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; validationStatus = null },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle API Key Visibility",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketOrange,
                    ),
                )

                // Status banner
                AnimatedVisibility(visible = validationStatus != null) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (validationOk) PocketGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (validationOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (validationOk) PocketGreen else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    validationStatus.orEmpty(),
                                    color = if (validationOk) PocketGreen else MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Test & Save Button
                Button(
                    onClick = {
                        scope.launch {
                            isValidating = true
                            validationStatus = "Testing provider connection…"
                            validationOk = true
                            val profile = ProviderProfile(
                                kind = selectedKind,
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                            )
                            when (val result = onValidateProvider(profile, apiKey.trim(), discoveredModels)) {
                                is ConnectionValidation.Success -> {
                                    validationStatus = result.message
                                    validationOk = true
                                    onSaveProvider(profile, apiKey.trim())
                                }
                                is ConnectionValidation.Failure -> {
                                    validationStatus = result.message
                                    validationOk = false
                                }
                            }
                            isValidating = false
                        }
                    },
                    enabled = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank() && !isDiscovering && !isValidating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketOrange),
                ) {
                    if (isValidating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing Connection…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection & Save", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // -------------------------------------------------------------
            // 3. LINUX RUNTIME & ENVIRONMENT
            // -------------------------------------------------------------
            item {
                SectionHeader(
                    title = "Linux Environment",
                    subtitle = "Local sandboxed execution engine",
                    icon = Icons.Default.Terminal,
                )
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoRow(icon = Icons.Default.Memory, label = "Architecture", value = "ARM64 (aarch64)")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        InfoRow(icon = Icons.Default.Terminal, label = "Linux Rootfs", value = "Ubuntu 20.04 PRoot")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        InfoRow(icon = Icons.Default.SmartToy, label = "Developer tools", value = "Claude Code + Node.js 24 + Python 3")

                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                onClearTerminal()
                                terminalClearedMessage = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (terminalClearedMessage) "Terminal Cleared!" else "Clear Terminal History", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { showChildProcessHelp = !showChildProcessHelp },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Advanced runtime reliability", fontSize = 13.sp)
                        }

                        AnimatedVisibility(showChildProcessHelp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Only use this if large npm or build tasks stop unexpectedly. In Developer options, enable “Disable child process restrictions”. The name may differ or be unavailable on some phones.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp,
                                )
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                        }.onFailure {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Open Developer options")
                                }
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // 4. ABOUT POCKET DEV
            // -------------------------------------------------------------
            item {
                SectionHeader(
                    title = "About",
                    subtitle = "App details & build info",
                    icon = Icons.Default.Settings,
                )
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Mobile Harness", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("v1.0.0", color = PocketOrange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Text(
                            "Autonomous AI Developer with native on-device Linux PRoot sandbox and Claude Code integration.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = PocketOrange.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) PocketOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(14.dp),
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                title,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
