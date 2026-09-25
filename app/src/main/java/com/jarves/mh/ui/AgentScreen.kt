package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DSH_PROTOCOL_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.ui.theme.PocketBlue
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

private data class KeyConnectionStatus(
    val message: String,
    val successful: Boolean? = null,
    val providerMessage: String? = null,
    val label: String = if (successful == true) "Verified" else "Failed",
)

/** Formats Antigravity model identifiers into clean, human-friendly names. */
internal fun formatAntigravityModelName(id: String): String = when (id) {
    "gemini-3.8-flash-high" -> "Gemini 3.8 Flash (High)"
    "gemini-3.8-flash-medium" -> "Gemini 3.8 Flash"
    "gemini-3.8-flash-low" -> "Gemini 3.8 Flash (Low)"
    "gemini-3.6-flash-high" -> "Gemini 3.6 Flash (High)"
    "gemini-3.6-flash-medium" -> "Gemini 3.6 Flash"
    "gemini-3.6-flash-low" -> "Gemini 3.6 Flash (Low)"
    "gemini-3.1-pro-high" -> "Gemini 3.1 Pro (High)"
    "gemini-3.1-pro-low" -> "Gemini 3.1 Pro (Low)"
    "claude-sonnet-4-6" -> "Claude 3.7 Sonnet"
    "claude-opus-4-6-thinking" -> "Claude 3.7 Opus (Thinking)"
    "gpt-oss-120b-medium" -> "GPT-OSS 120B"
    else -> id.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
    }
}

/** Returns tier classification badges for Antigravity models. */
internal fun formatAntigravityModelTier(id: String): String = when {
    id.contains("3.8") -> "Recommended"
    id.contains("3.6") -> "Stable"
    id.contains("3.1-pro") -> "Pro Reasoning"
    id.contains("claude") -> "Anthropic"
    id.contains("gpt") -> "Open Source"
    else -> ""
}

/**
 * Dedicated Agent + AI connection hub.
 *
 * Replaces the old dashboard Terminal tab. Terminal is now a FAB
 * on the project/workspace screen; this screen owns:
 *  1. Status hero (active agent + model + connectivity)
 *  2. Coding agent picker + installs + updates
 *  3. AI connection (Antigravity OAuth OR provider + model + API keys)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onPing: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onSelectAgent: (AgentKind) -> Unit = {},
    onInstallAgent: (AgentKind) -> Unit = {},
    onCheckAgentUpdates: () -> Unit = {},
    onUpdateAgent: (AgentKind) -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onRefreshAntigravityModels: () -> Unit = {},
    onSetAntigravityModel: (String) -> Unit = {},
    onSetAntigravityEffort: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedKind by rememberSaveable(state.provider.kind) { mutableStateOf(state.provider.kind) }
    var baseUrl by rememberSaveable(state.provider.baseUrl) { mutableStateOf(state.provider.baseUrl) }
    var model by rememberSaveable(state.provider.model) { mutableStateOf(state.provider.model) }
    var dshApi by rememberSaveable(state.provider.dshApi) { mutableStateOf(state.provider.dshApi) }
    var apiKey by rememberSaveable(selectedKind) { mutableStateOf(getSavedApiKey(selectedKind)) }
    var savedKeys by remember(selectedKind, state.activeApiKeyName) {
        mutableStateOf(getSavedApiKeys(selectedKind))
    }
    var newKeyName by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newApiKey by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newKeyVisible by rememberSaveable(selectedKind, savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    var models by remember(selectedKind, baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var modelSearch by rememberSaveable(selectedKind) { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var statusProviderMessage by remember { mutableStateOf<String?>(null) }
    var keyConnectionStatuses by remember(selectedKind) {
        mutableStateOf<Map<String, KeyConnectionStatus>>(emptyMap())
    }
    // Antigravity model sheet state
    var showAntigravityModelSheet by rememberSaveable { mutableStateOf(false) }
    var antigravitySearch by rememberSaveable { mutableStateOf("") }
    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var viewedAgent by rememberSaveable { mutableStateOf(state.agentKind) }

    val orderedAgents = remember(state.primaryAgentKind) {
        listOf(state.primaryAgentKind) + AgentKind.entries.filterNot { it == state.primaryAgentKind }
    }
    val viewedAgentInstalled = viewedAgent == state.agentKind ||
        state.installedAgentVersions.containsKey(viewedAgent)

    val providerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val antigravitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredModels = remember(models, modelSearch) {
        val q = modelSearch.trim()
        if (q.isBlank()) models else models.filter {
            it.id.contains(q, true) || it.displayName.contains(q, true)
        }
    }

    val antigravityModelList = remember(state.antigravityModels) {
        if (state.antigravityModels.isNotEmpty()) state.antigravityModels
        else listOf(
            "gemini-3.8-flash-high",
            "gemini-3.8-flash-medium",
            "gemini-3.6-flash-high",
            "gemini-3.6-flash-medium",
            "gemini-3.6-flash-low",
            "gemini-3.1-pro-high",
            "gemini-3.1-pro-low",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking",
            "gpt-oss-120b-medium",
        )
    }

    val filteredAntigravityModels = remember(antigravityModelList, antigravitySearch) {
        val q = antigravitySearch.trim()
        if (q.isBlank()) antigravityModelList
        else antigravityModelList.filter {
            it.contains(q, true) || formatAntigravityModelName(it).contains(q, true)
        }
    }

    fun discoverModels() {
        val effectiveKey = apiKey.trim().ifBlank { newApiKey.trim() }
        val supportsPublicDiscovery = selectedKind == ProviderKind.LLM_ROUTER ||
            selectedKind == ProviderKind.OPENCODE_ZEN
        if (effectiveKey.isBlank() && !supportsPublicDiscovery) {
            status = context.getString(R.string.agent_api_key_required)
            statusOk = false
            statusProviderMessage = null
            newKeyVisible = true
            showModels = true
            return
        }
        scope.launch {
            isDiscovering = true
            status = context.getString(R.string.agent_discovering_from, selectedKind.title)
            statusOk = true
            statusProviderMessage = null
            val kind = selectedKind
            val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
            val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
            when (val result = onDiscoverModels(profile, effectiveKey)) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    if (apiKey.isBlank() && newApiKey.isNotBlank()) {
                        val keyName = newKeyName.trim().ifBlank { "${kind.title} Key" }
                        savedKeys = onAddApiKey(kind, keyName, newApiKey.trim())
                        apiKey = getSavedApiKey(kind)
                        newKeyName = ""
                        newApiKey = ""
                        newKeyVisible = false
                    }
                    status = context.getString(R.string.agent_discovered, result.models.size, selectedKind.title)
                    statusOk = true
                    statusProviderMessage = null
                    showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    status = result.message
                    statusOk = false
                    statusProviderMessage = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    // ── Live connection status calculations ──
    val isAntigravity = state.agentKind == AgentKind.ANTIGRAVITY
    val antigravityTesting = isAntigravity && state.apiPingStatus == ApiPingStatus.PINGING
    val antigravityHelloFailed = isAntigravity && state.apiPingStatus == ApiPingStatus.FAILED
    val pillLoading = antigravityTesting || (!isAntigravity && state.apiPingStatus == ApiPingStatus.PINGING)

    val (dot, label, pillBg) = if (isAntigravity) {
        when {
            antigravityTesting -> Triple(PocketOrange, stringResource(R.string.agent_testing), PocketOrange.copy(alpha = 0.13f))
            state.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN || antigravityHelloFailed ->
                Triple(MaterialTheme.colorScheme.error, stringResource(R.string.agent_attention), MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            else -> Triple(Color(0xFF58C9A3), stringResource(R.string.agent_online), Color(0xFF58C9A3).copy(alpha = 0.13f))
        }
    } else {
        when (state.apiPingStatus) {
            ApiPingStatus.OK -> Triple(Color(0xFF58C9A3), stringResource(R.string.agent_online), Color(0xFF58C9A3).copy(alpha = 0.13f))
            ApiPingStatus.FAILED -> Triple(MaterialTheme.colorScheme.error, stringResource(R.string.agent_attention), MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            ApiPingStatus.PINGING -> Triple(PocketOrange, stringResource(R.string.agent_testing), PocketOrange.copy(alpha = 0.13f))
            ApiPingStatus.IDLE -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.agent_not_tested), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        }
    }

    // ── Antigravity Model Modal Bottom Sheet ──
    if (showAntigravityModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAntigravityModelSheet = false },
            sheetState = antigravitySheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_select_model), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.agent_models_for_antigravity, filteredAntigravityModels.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading) {
                        if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, stringResource(R.string.settings_refresh_models))
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = antigravitySearch,
                    onValueChange = { antigravitySearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.agent_search_series)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (filteredAntigravityModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.agent_no_matching), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredAntigravityModels, key = { it }) { modelId ->
                            val isSelected = state.antigravityModel == modelId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSetAntigravityModel(modelId)
                                        showAntigravityModelSheet = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                formatAntigravityModelName(modelId),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                            )
                                            val tier = formatAntigravityModelTier(modelId)
                                            val tierLabel = when (tier) {
                                                "Recommended" -> stringResource(R.string.agent_tier_recommended)
                                                "Stable" -> stringResource(R.string.agent_tier_stable)
                                                "Pro Reasoning" -> stringResource(R.string.agent_tier_pro_reasoning)
                                                "Open Source" -> stringResource(R.string.agent_tier_open_source)
                                                else -> tier
                                            }
                                            if (tier.isNotEmpty()) {
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    color = if (isSelected) PocketOrange.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text(
                                                        tierLabel,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            modelId,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Provider Models Modal Bottom Sheet ──
    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = providerSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_available_models), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (models.isEmpty()) selectedKind.title else stringResource(R.string.agent_models_of, filteredModels.size, models.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = ::discoverModels, enabled = !isDiscovering) {
                        if (isDiscovering) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PocketOrange)
                        } else {
                            Icon(Icons.Default.Refresh, stringResource(R.string.settings_refresh_models))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.agent_search_custom)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (status != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (statusOk) PocketOrange.copy(alpha = 0.09f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            if (statusOk) PocketOrange.copy(alpha = 0.28f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 1.6.dp,
                                    color = PocketOrange,
                                )
                            } else {
                                Icon(
                                    if (statusOk) Icons.Default.Info else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (statusOk) PocketOrange else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status.orEmpty(),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            }
                            statusProviderMessage?.let { providerMessage ->
                                Text(
                                    stringResource(R.string.agent_provider_message, providerMessage),
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 23.dp, top = 5.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (isDiscovering) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = PocketOrange, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.agent_discovering_from, selectedKind.title),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (filteredModels.isEmpty()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (modelSearch.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PocketOrange.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = modelSearch.trim()
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Check, null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_use_custom_model), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketOrange)
                                    }
                                }
                            }
                        }


                        Button(
                            onClick = ::discoverModels,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.agent_discover_from_api))
                        }

                        val recommended = remember(selectedKind) { defaultModelsForProvider(selectedKind) }
                        if (recommended.isNotEmpty()) {
                            Text(
                                stringResource(R.string.agent_recommended_models),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            recommended.forEach { opt ->
                                val isSelected = model == opt.id
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = opt.id
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                opt.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                opt.id,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        AgentSelectionDot(selected = isSelected)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (modelSearch.isNotBlank() && filteredModels.none { it.id.equals(modelSearch.trim(), ignoreCase = true) }) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = PocketOrange.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = modelSearch.trim()
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(R.string.agent_use_custom_model), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketOrange)
                                        }
                                    }
                                }
                            }
                        }
                        items(filteredModels, key = { it.id }) { option ->
                            val isSelected = model == option.id
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = option.id
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                option.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (option.isFree) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.agent_free), color = Color(0xFF58C99C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        if (option.displayName != option.id) {
                                            Text(
                                                option.id,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 4.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(stringResource(R.string.agent_hub_title), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                if (isAntigravity) {
                                    stringResource(R.string.agent_antigravity_model, formatAntigravityModelName(state.antigravityModel))
                                } else {
                                    "${state.agentKind.title} · ${model.ifBlank { selectedKind.title }}"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // Top Bar Live Status Pill
                    Surface(
                        color = pillBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, dot.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.5.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (pillLoading) stringResource(R.string.agent_checking) else label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = dot,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 1. Compact 3-Way Segmented Engine Selector ──
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            orderedAgents.forEach { agent ->
                                val isSelected = viewedAgent == agent
                                val isInstalled = agent == state.agentKind || state.installedAgentVersions.containsKey(agent)
                                val updateAvailable = state.agentUpdates.containsKey(agent)
                                val shortTitle = when (agent) {
                                    AgentKind.ANTIGRAVITY -> "Antigravity"
                                    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
                                    AgentKind.CLAUDE_CODE -> "Claude Code"
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = state.agentInstalling == null) {
                                            viewedAgent = agent
                                            if (isInstalled) onSelectAgent(agent)
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                shortTitle,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 12.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                            if (updateAvailable) {
                                                Spacer(Modifier.width(3.dp))
                                                Box(Modifier.size(5.dp).background(PocketOrange, CircleShape))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.agentInstalling == viewedAgent) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        state.agentMessage ?: stringResource(R.string.agent_installing_binary),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { state.agentProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val downloaded = state.agentDownloadedBytes
                                val total = state.agentTotalBytes
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (downloaded != null || total != null) {
                                            buildString {
                                                append(formatAgentBytes(downloaded ?: 0L))
                                                total?.let { append(" / ${formatAgentBytes(it)}") }
                                            }
                                        } else {
                                            stringResource(R.string.agent_processing_files)
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        buildString {
                                            append("${(state.agentProgress * 100).toInt()}%")
                                            state.agentBytesPerSecond?.takeIf { it > 0L }?.let {
                                                append(" · ${formatAgentBytes(it)}/s")
                                            }
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (!viewedAgentInstalled) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${viewedAgent.title} is not installed",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.agent_install_package_note, viewedAgent.downloadNote),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { onInstallAgent(viewedAgent) },
                                    enabled = state.agentInstalling == null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(R.string.agent_install_title, viewedAgent.title), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Primary Configuration Card (Antigravity OR Provider) ──
            item {
                if (!viewedAgentInstalled || viewedAgent != state.agentKind) {
                    // Installation/selection guidance is shown directly below the tabs.
                } else if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    AgentAntigravityCard(
                        state = state,
                        code = antigravityCode,
                        onCode = { antigravityCode = it },
                        onStartLogin = onStartAntigravityLogin,
                        onSubmitCode = { onSubmitAntigravityCode(antigravityCode); antigravityCode = "" },
                        onLogout = onLogoutAntigravity,
                        onRefreshModels = onRefreshAntigravityModels,
                        onOpenModelSheet = { showAntigravityModelSheet = true },
                        onSetEffort = onSetAntigravityEffort,
                        onTest = onPing,
                    )
                } else {
                    AgentProviderCard(
                        state = state,
                        selectedKind = selectedKind,
                        baseUrl = baseUrl,
                        model = model,
                        dshApi = dshApi,
                        apiKey = apiKey,
                        models = models,
                        isDiscovering = isDiscovering,
                        isValidating = isValidating,
                        status = status,
                        statusOk = statusOk,
                        statusProviderMessage = statusProviderMessage,
                        keyConnectionStatuses = keyConnectionStatuses,
                        savedKeys = savedKeys,
                        newKeyName = newKeyName,
                        newApiKey = newApiKey,
                        newKeyVisible = newKeyVisible,
                        onProvider = { kind ->
                            selectedKind = kind
                            baseUrl = kind.defaultBaseUrl
                            model = kind.defaultModel
                            dshApi = defaultDshApiForProvider(kind)
                            models = emptyList()
                            modelSearch = ""
                            showModels = false
                            newKeyName = ""
                            newApiKey = ""
                            status = null
                            statusProviderMessage = null
                        },
                        onBaseUrl = {
                            baseUrl = it
                            if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind == ProviderKind.CUSTOM) {
                                dshApi = inferredDshApiForUrl(it)
                            }
                            models = emptyList()
                            status = null
                            statusProviderMessage = null
                            keyConnectionStatuses = emptyMap()
                        },
                        onModel = { model = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onDshApi = { dshApi = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onNewKeyName = { newKeyName = it },
                        onNewApiKey = { newApiKey = it },
                        onToggleNewKey = { newKeyVisible = !newKeyVisible },
                        onAddKey = {
                            savedKeys = onAddApiKey(selectedKind, newKeyName, newApiKey.trim())
                            newKeyName = ""
                            newApiKey = ""
                            apiKey = getSavedApiKey(selectedKind)
                            status = context.getString(R.string.agent_key_added, selectedKind.title)
                            statusOk = true
                        },
                        onActivateKey = { keyId ->
                            savedKeys = onActivateApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            status = context.getString(R.string.agent_key_changed, selectedKind.title)
                            statusOk = true
                        },
                        onRemoveKey = { keyId ->
                            savedKeys = onRemoveApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            keyConnectionStatuses = keyConnectionStatuses - keyId
                            status = context.getString(R.string.agent_key_removed, selectedKind.title)
                            statusOk = true
                        },
                        onOpenModelSheet = {
                            showModels = true
                        },
                        onDiscover = ::discoverModels,
                        onValidate = {
                            scope.launch {
                                isValidating = true
                                val activeKeyId = savedKeys.firstOrNull { it.isActive }?.id
                                if (activeKeyId != null) {
                                    keyConnectionStatuses = keyConnectionStatuses +
                                        (activeKeyId to KeyConnectionStatus(context.getString(R.string.agent_checking_connection)))
                                    status = null
                                } else {
                                    status = context.getString(R.string.agent_checking_connection)
                                    statusOk = true
                                }
                                val kind = selectedKind
                                val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
                                val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
                                if (kind == ProviderKind.CLAUDE) {
                                    onSaveProvider(profile, apiKey.trim())
                                    status = context.getString(R.string.agent_token_saved_secure)
                                    statusOk = true
                                    activeKeyId?.let {
                                        keyConnectionStatuses = keyConnectionStatuses +
                                            (it to KeyConnectionStatus(context.getString(R.string.agent_token_saved), true, label = context.getString(R.string.agent_saved_label)))
                                    }
                                } else when (val result = onValidateProvider(profile, apiKey.trim(), models)) {
                                    is ConnectionValidation.Success -> {
                                        onSaveProvider(profile, apiKey.trim())
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, true, label = context.getString(R.string.agent_verified)))
                                        } else {
                                            status = result.message
                                            statusOk = true
                                        }
                                    }
                                    is ConnectionValidation.Failure -> {
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, false, result.providerMessage, result.label))
                                        } else {
                                            status = result.message
                                            statusOk = false
                                            statusProviderMessage = result.providerMessage
                                        }
                                    }
                                }
                                isValidating = false
                            }
                        },
                    )
                }
            }

            // ── 3. Runtime Updates Card ──
            item {
                AgentUpdateBlock(
                    state = state,
                    onCheck = onCheckAgentUpdates,
                    onUpdate = onUpdateAgent,
                )
            }

            // ── 4. Subtle Footer ──
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.agent_keys_footer),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/**
 * Modern Antigravity Configuration Bento Card.
 */
@Composable
private fun AgentAntigravityCard(
    state: AppUiState,
    code: String,
    onCode: (String) -> Unit,
    onStartLogin: () -> Unit,
    onSubmitCode: () -> Unit,
    onLogout: () -> Unit,
    onRefreshModels: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onSetEffort: (String) -> Unit,
    onTest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val auth = state.antigravityAuth

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.agent_google_account), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // Google Account Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF34A853).copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF34A853).copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("G", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val email = auth.accountEmail
                    Text(
                        email ?: if (auth.status == AntigravityAuthStatus.SIGNED_IN) stringResource(R.string.agent_connected) else stringResource(R.string.agent_not_signed_in),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (auth.status == AntigravityAuthStatus.SIGNED_IN) stringResource(R.string.agent_connected_with_google) else stringResource(R.string.agent_required_for_antigravity),
                        fontSize = 11.sp,
                        color = if (auth.status == AntigravityAuthStatus.SIGNED_IN) Color(0xFF2E9D72) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                    Text(
                        stringResource(R.string.action_disconnect),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onLogout() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }

            // Authentication actions if not signed in
            when (auth.status) {
                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AntigravityAuthStatus.AWAITING_CODE -> {
                    auth.authorizationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.agent_copy_url))
                        }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCode,
                        label = { Text(stringResource(R.string.agent_one_time_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = onSubmitCode,
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.agent_complete_sign_in))
                    }
                }
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> {
                    Button(
                        onClick = onStartLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (auth.status == AntigravityAuthStatus.ERROR) stringResource(R.string.agent_reconnect_google) else stringResource(R.string.settings_signin_google))
                    }
                }
                AntigravityAuthStatus.SIGNED_IN -> {}
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Active Intelligence Model Tile
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.agent_active_model),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = !state.antigravityModelsLoading) { onRefreshModels() }
                                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            if (state.antigravityModelsLoading) {
                                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.4.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                stringResource(R.string.agent_sync),
                                fontSize = 11.sp,
                                color = PocketOrange,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenModelSheet() },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(PocketOrange.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = PocketOrange,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                val currentModel = state.antigravityModel.ifBlank { stringResource(R.string.agent_select_model_hint) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formatAntigravityModelName(currentModel),
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    currentModel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.agent_choose_model_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Reasoning Depth (Effort) Segmented Capsule
                Column(modifier = Modifier.fillMaxWidth()) {
                    val effortCaption = when (state.antigravityEffort) {
                        "low" -> stringResource(R.string.agent_effort_low)
                        "medium" -> stringResource(R.string.agent_effort_medium)
                        "high" -> stringResource(R.string.agent_effort_high)
                        else -> stringResource(R.string.agent_effort_medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.agent_reasoning_depth),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            effortCaption,
                            fontSize = 11.sp,
                            color = PocketOrange,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            listOf("low", "medium", "high").forEach { effort ->
                                val isSelected = state.antigravityEffort == effort
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (isSelected) PocketOrange else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSetEffort(effort) },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() },
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF241107) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = state.apiPingStatus != ApiPingStatus.PINGING,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
                ) {
                    if (state.apiPingStatus == ApiPingStatus.PINGING) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (state.apiPingStatus == ApiPingStatus.PINGING) stringResource(R.string.agent_testing_connection) else stringResource(R.string.agent_test_connection),
                        color = PocketOrange,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                state.apiPingMessage?.takeIf { state.apiPingStatus != ApiPingStatus.IDLE }?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.apiPingStatus == ApiPingStatus.FAILED) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else Color(0xFF2E9D72),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            message,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.agent_auto_approval_note),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AgentProviderCard(
    state: AppUiState,
    selectedKind: ProviderKind,
    baseUrl: String,
    model: String,
    dshApi: String,
    apiKey: String,
    models: List<DiscoveredModel>,
    isDiscovering: Boolean,
    isValidating: Boolean,
    status: String?,
    statusOk: Boolean,
    statusProviderMessage: String?,
    keyConnectionStatuses: Map<String, KeyConnectionStatus>,
    savedKeys: List<ApiKeyInfo>,
    newKeyName: String,
    newApiKey: String,
    newKeyVisible: Boolean,
    onProvider: (ProviderKind) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onDshApi: (String) -> Unit,
    onNewKeyName: (String) -> Unit,
    onNewApiKey: (String) -> Unit,
    onToggleNewKey: () -> Unit,
    onAddKey: () -> Unit,
    onActivateKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
    onDiscover: () -> Unit,
    onValidate: () -> Unit,
) {
    val visibleKinds = remember(state.agentKind) { providersForAgent(state.agentKind) }
    var connectionExpanded by rememberSaveable(selectedKind) { mutableStateOf(false) }
    // Keep this state across provider changes so selecting Custom API can
    // immediately reveal its required setup instead of resetting on recomposition.
    var endpointExpanded by rememberSaveable(state.agentKind) { mutableStateOf(false) }
    var keysExpanded by rememberSaveable(state.agentKind, selectedKind) { mutableStateOf(false) }
    var addKeyExpanded by rememberSaveable(savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    val activeKey = savedKeys.firstOrNull { it.isActive }
    val activeKeyStatus = activeKey?.let { keyConnectionStatuses[it.id] }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(stringResource(R.string.agent_ai_provider), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            PremiumSummaryRow(
                icon = Icons.Default.Link,
                title = selectedKind.title,
                subtitle = selectedKind.subtitle,
                expanded = connectionExpanded,
                onClick = { connectionExpanded = !connectionExpanded },
            )

            AnimatedVisibility(connectionExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    ) {
                        Column {
                            visibleKinds.forEachIndexed { index, kind ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onProvider(kind)
                                            connectionExpanded = false
                                            endpointExpanded = kind == ProviderKind.CUSTOM
                                        }
                                        .padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(kind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    AgentSelectionDot(selectedKind == kind)
                                }
                                if (index != visibleKinds.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = 13.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }

                }
            }

            if (selectedKind != ProviderKind.CLAUDE) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                val baseUrlRequired = stringResource(R.string.agent_base_url_required)
                PremiumSummaryRow(
                    icon = Icons.Default.Info,
                    title = if (selectedKind == ProviderKind.CUSTOM) stringResource(R.string.agent_custom_api) else stringResource(R.string.agent_endpoint_protocol),
                    subtitle = buildString {
                        append(baseUrl.ifBlank { baseUrlRequired })
                        if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS) {
                            append(" · ")
                            append(if (selectedKind.fixedProtocol) defaultDshApiForProvider(selectedKind) else dshApi)
                        }
                    },
                    expanded = endpointExpanded,
                    onClick = { endpointExpanded = !endpointExpanded },
                )

                AnimatedVisibility(endpointExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectedKind == ProviderKind.CUSTOM) {
                            Text(
                                stringResource(R.string.agent_endpoint_hint),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { if (!selectedKind.fixedBaseUrl) onBaseUrl(it) },
                            label = { Text(stringResource(R.string.agent_base_url)) },
                            supportingText = if (selectedKind.fixedBaseUrl) ({ Text(stringResource(R.string.provider_fixed_by, selectedKind.title)) }) else null,
                            readOnly = selectedKind.fixedBaseUrl,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS && !selectedKind.fixedProtocol) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.agent_gateway_protocol), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(5.dp))
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
                                    Column {
                                        listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable { onDshApi(option) }.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(option, Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                                AgentSelectionDot(dshApi == option)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.agent_model_access), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        if (isDiscovering) stringResource(R.string.agent_discovering) else if (models.isEmpty()) stringResource(R.string.agent_discover_models) else stringResource(R.string.agent_models_count, models.size),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketOrange,
                        modifier = Modifier.clickable(enabled = !isDiscovering, onClick = onDiscover).padding(6.dp),
                    )
                }

                PremiumSummaryRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.agent_ai_model),
                    subtitle = model.ifBlank { stringResource(R.string.agent_select_or_type) },
                    expanded = false,
                    onClick = onOpenModelSheet,
                )
            } else {
                Text(
                    stringResource(R.string.agent_setup_token_note),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            if (status != null) {
                Column(modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (statusOk) Icons.Default.Info else Icons.Default.Warning, null, tint = if (statusOk) PocketOrange else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(status, fontSize = 10.sp, lineHeight = 14.sp, color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                    statusProviderMessage?.let { providerMessage ->
                        Text(stringResource(R.string.agent_provider_message, providerMessage), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 21.dp, top = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            val noTokenSaved = stringResource(R.string.agent_no_token_saved)
            val noKeySaved = stringResource(R.string.agent_no_key_saved)
            val activeSuffix = stringResource(R.string.agent_active_suffix)
            val verifiedLabel = stringResource(R.string.agent_verified)
            val checkingLabel = stringResource(R.string.agent_checking)
            PremiumSummaryRow(
                icon = Icons.Default.Key,
                title = if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_subscription_token) else stringResource(R.string.agent_credentials),
                subtitle = buildString {
                    append(activeKey?.name ?: if (selectedKind == ProviderKind.CLAUDE) noTokenSaved else noKeySaved)
                    if (activeKey != null) append(activeSuffix)
                    activeKeyStatus?.let {
                        append(" · ")
                        append(when (it.successful) { true -> verifiedLabel; false -> it.label; null -> checkingLabel })
                    }
                },
                positive = activeKeyStatus?.successful == true,
                error = activeKeyStatus?.successful == false,
                expanded = keysExpanded,
                onClick = { keysExpanded = !keysExpanded },
            )

            activeKeyStatus?.let { keyStatus ->
                if (!keysExpanded) {
                    Text(
                        keyStatus.message,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = when (keyStatus.successful) {
                            true -> Color(0xFF2E9D72)
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                    )
                    keyStatus.providerMessage?.let { providerMessage ->
                        Text(
                            stringResource(R.string.agent_provider_message, providerMessage),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(keysExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_saved_tokens, savedKeys.size) else stringResource(R.string.agent_saved_keys, savedKeys.size), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            if (addKeyExpanded) stringResource(R.string.action_cancel) else stringResource(R.string.agent_add_key),
                            fontSize = 11.sp,
                            color = PocketOrange,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { addKeyExpanded = !addKeyExpanded }.padding(6.dp),
                        )
                    }
                    savedKeys.forEach { key ->
                        val keyStatus = keyConnectionStatuses[key.id]
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onActivateKey(key.id) }.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(if (key.isActive) stringResource(R.string.agent_active) else stringResource(R.string.agent_tap_to_activate), fontSize = 10.sp, color = if (key.isActive) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    AgentSelectionDot(key.isActive)
                                    IconButton(onClick = { onRemoveKey(key.id) }) {
                                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.agent_remove_cd), Modifier.size(17.dp))
                                    }
                                }
                                keyStatus?.let {
                                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (it.successful == null) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                        else Icon(if (it.successful) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (it.successful) Color(0xFF2E9D72) else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(it.message, fontSize = 10.sp, lineHeight = 14.sp, color = if (it.successful == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            it.providerMessage?.let { providerMessage ->
                                                Text(stringResource(R.string.agent_provider_message, providerMessage), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(addKeyExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newKeyName,
                                onValueChange = { input ->
                                    if ((input.startsWith("sk-") || input.startsWith("ant-") || input.length > 30) && !input.contains(" ") && newApiKey.isBlank()) {
                                        onNewApiKey(input.trim())
                                        onNewKeyName("${selectedKind.title} Key")
                                    } else onNewKeyName(input)
                                },
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_token_name) else stringResource(R.string.agent_key_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            OutlinedTextField(
                                value = newApiKey,
                                onValueChange = onNewApiKey,
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_claude_token_label) else stringResource(R.string.agent_api_key_label)) },
                                singleLine = true,
                                visualTransformation = if (newKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { IconButton(onClick = onToggleNewKey) { Icon(if (newKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.agent_toggle_visibility)) } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Button(
                                onClick = { onAddKey(); addKeyExpanded = false },
                                enabled = newKeyName.isNotBlank() && newApiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_save_token) else stringResource(R.string.agent_save_api_key)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onValidate,
                enabled = apiKey.isNotBlank() && !isDiscovering && !isValidating &&
                    (selectedKind == ProviderKind.CLAUDE || (baseUrl.isNotBlank() && model.isNotBlank())),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
            ) {
                if (isValidating) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(if (selectedKind == ProviderKind.CLAUDE) Icons.Default.Check else Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        isValidating && selectedKind == ProviderKind.CLAUDE -> stringResource(R.string.agent_saving_token)
                        isValidating -> stringResource(R.string.agent_testing_connection)
                        selectedKind == ProviderKind.CLAUDE -> stringResource(R.string.agent_save_subscription)
                        else -> stringResource(R.string.agent_test_connection)
                    },
                    color = PocketOrange,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PremiumSummaryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    positive: Boolean = false,
    error: Boolean = false,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).background(PocketOrange.copy(alpha = 0.10f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = PocketOrange, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = when {
                    error -> MaterialTheme.colorScheme.error
                    positive -> Color(0xFF2E9D72)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Compact Runtime Updates Card.
 */
@Composable
private fun AgentUpdateBlock(
    state: AppUiState,
    onCheck: () -> Unit,
    onUpdate: (AgentKind) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_runtime_updates), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(
                    state.agentUpdateMessage ?: stringResource(R.string.agent_updates_default),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val canCheck = !state.agentUpdatesChecking && state.agentUpdating == null && state.agentInstalling == null
            Row(
                modifier = Modifier
                    .clickable(enabled = canCheck, onClick = onCheck)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.agentUpdatesChecking) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.agent_check_updates), color = PocketOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        state.agentUpdates.forEach { (agent, update) ->
                val updating = state.agentUpdating == agent
                val downloaded = state.agentUpdateDownloadedBytes
                val total = state.agentUpdateTotalBytes
                val fraction = if (updating && downloaded != null && total != null && total > 0L) {
                    (downloaded.toFloat() / total).coerceIn(0f, 1f)
                } else state.agentUpdateProgress.coerceIn(0f, 1f)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketOrange.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.28f)),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(agent.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(stringResource(R.string.agent_version_transition, update.installedVersion, update.latestVersion), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.agent_update_badge), color = PocketOrange, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        if (updating) {
                            state.agentUpdateMessage?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(
                                onClick = { onUpdate(agent) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.agentUpdating == null && state.agentInstalling == null,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.agent_update_agent, agent.title), fontSize = 12.sp)
                            }
                        }
                    }
                }
        }
    }
}

private fun formatAgentBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/**
 * Fixed-size three-dot wave. All three dots are always laid out, only their
 * alpha animates — so surrounding text never shifts while loading.
 */
@Composable
private fun AgentTypingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    spacing: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "agent-typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "wave",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(3) { index ->
            val alpha = 0.25f + 0.75f * ((phase + index / 3f) % 1f)
            Box(Modifier.size(dotSize).background(color.copy(alpha = alpha), CircleShape))
        }
    }
}

@Composable
private fun AgentSelectionDot(selected: Boolean) {
    Box(
        Modifier.size(22.dp).border(if (selected) 2.dp else 1.5.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(PocketOrange, CircleShape))
    }
}

/** Provides popular default models for providers when discovery hasn't been run or is unavailable. */
private fun defaultModelsForProvider(kind: ProviderKind): List<DiscoveredModel> = when (kind) {
    ProviderKind.DEEPSEEK -> listOf(
        DiscoveredModel("deepseek-v4-flash", "DeepSeek-V4 Flash"),
    )
    ProviderKind.OPENCODE_ZEN -> listOf(
        DiscoveredModel(ProviderKind.OPENCODE_ZEN.defaultModel, "OpenCode Zen default"),
    )
    ProviderKind.NVIDIA_NIM -> listOf(
        DiscoveredModel(ProviderKind.NVIDIA_NIM.defaultModel, "Qwen 2.5 Coder 32B"),
    )
    ProviderKind.ANTHROPIC -> listOf(
        DiscoveredModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet (Hybrid)"),
        DiscoveredModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2"),
        DiscoveredModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku"),
    )
    ProviderKind.LLM_ROUTER -> listOf(
        DiscoveredModel("deepseek/deepseek-r1", "DeepSeek R1 (via OpenRouter)"),
        DiscoveredModel("anthropic/claude-3.7-sonnet", "Claude 3.7 Sonnet"),
        DiscoveredModel("openai/gpt-4o", "GPT-4o"),
        DiscoveredModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
    )
    ProviderKind.KIMI -> listOf(
        DiscoveredModel("kimi-k2.6", "Kimi K2.6"),
        DiscoveredModel("moonshot-v1-8k", "Moonshot v1 8K"),
        DiscoveredModel("moonshot-v1-32k", "Moonshot v1 32K"),
    )
    else -> if (kind.defaultModel.isNotBlank()) listOf(
        DiscoveredModel(kind.defaultModel, "${kind.title} Default (${kind.defaultModel})")
    ) else emptyList()
}
