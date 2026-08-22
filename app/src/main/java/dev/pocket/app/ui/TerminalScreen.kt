package dev.pocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.app.ui.theme.AppThemeMode
import dev.pocket.app.ui.theme.PocketGreen
import dev.pocket.app.ui.theme.PocketOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    lines: List<TerminalOutputLine>,
    isRunning: Boolean,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onToggleTheme: () -> Unit,
    themeMode: AppThemeMode,
    title: String = "Linux Terminal",
    subtitle: String = "Ubuntu 24.04 · PRoot Sandbox",
    liveOutput: String = "",
    currentCommand: String? = null,
    commandDraft: String? = null,
    onCommandDraftConsumed: () -> Unit = {},
    promptPath: String = "/workspace",
    onStop: (() -> Unit)? = null,
    showThemeAction: Boolean = true,
    showQuickCommands: Boolean = true,
    compactHeader: Boolean = false,
) {
    var commandInput by remember { mutableStateOf(TextFieldValue()) }
    var commandHistory by remember { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var altActive by rememberSaveable { mutableStateOf(false) }
    var cursorVisible by rememberSaveable { mutableStateOf(true) }
    val terminalScrollState = rememberScrollState()
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val terminalPromptPath = if (promptPath == "/workspace") "~" else "~${promptPath.removePrefix("/workspace")}" 
    val openTerminalKeyboard = {
        inputFocusRequester.requestFocus()
        scope.launch {
            delay(80)
            keyboardController?.show()
        }
    }
    val submitCommand = {
        if (commandInput.text.isNotBlank() && !isRunning) {
            val submitted = commandInput.text
            onRun(submitted)
            commandHistory = (commandHistory + submitted).takeLast(50)
            historyIndex = -1
            commandInput = TextFieldValue()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(550)
            cursorVisible = !cursorVisible
        }
    }

    LaunchedEffect(commandDraft) {
        if (!commandDraft.isNullOrBlank()) {
            commandInput = TextFieldValue(commandDraft, TextRange(commandDraft.length))
            historyIndex = -1
            onCommandDraftConsumed()
        }
    }

    LaunchedEffect(lines.size, liveOutput.length, isRunning, currentCommand, commandInput.text.length) {
        delay(20)
        terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
    }

    val quickCommands = listOf(
        "uname -a",
        "ls -la",
        "pwd",
        "node -v",
        "python3 --version",
        "df -h",
        "free -m",
        "claude --version",
    )

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = if (compactHeader) 4.dp else 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compactHeader) 5.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, style = if (compactHeader) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClear) { Icon(Icons.Default.DeleteOutline, contentDescription = "Clear output") }
                    if (showThemeAction) {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme",
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showQuickCommands) {
                // Quick command chips are useful in the standalone terminal, but
                // project terminal space is reserved for the actual project session.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickCommands.forEach { cmd ->
                        AssistChip(
                            onClick = { onRun(cmd) },
                            label = {
                                Text(
                                    cmd,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            // Console output area
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !isRunning) { openTerminalKeyboard() },
                color = Color(0xFF090D14),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(terminalScrollState)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (lines.isEmpty()) {
                            Text(
                                "Pocket Dev Terminal ready.\nType a bash command below or tap a quick command chip above.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF6E7681),
                            )
                            }

                            lines.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TerminalCommandPrompt(promptPath = terminalPromptPath, command = item.command)
                                    if (item.output.isNotEmpty()) {
                                        Text(
                                            text = item.output,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = if (item.exitCode != 0) MaterialTheme.colorScheme.error else Color(0xFFC9D1D9),
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }

                            if (isRunning && currentCommand != null) {
                                TerminalCommandPrompt(promptPath = terminalPromptPath, command = currentCommand)
                            }

                            if (liveOutput.isNotBlank()) {
                                Text(
                                    liveOutput,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = Color(0xFFC9D1D9),
                                )
                            }

                            if (isRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = PocketOrange,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Executing…",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = PocketOrange,
                                    )
                                }
                            }
                        }
                    }
                    if (!isRunning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clickable { openTerminalKeyboard() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "root@pocket:$terminalPromptPath#",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PocketGreen,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                Text(
                                    text = commandInput.text + if (cursorVisible) "│" else " ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = Color(0xFFF0F6FC),
                                    maxLines = 1,
                                )
                                BasicTextField(
                                    value = commandInput,
                                    onValueChange = { commandInput = it },
                                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                                    singleLine = true,
                                    cursorBrush = SolidColor(Color.Transparent),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = Color.Transparent,
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submitCommand() }),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }

            // Keyboard helper row. These operate on the command draft, so they are
            // useful even when the phone keyboard does not expose terminal keys.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TerminalKeyButton("↑", "Previous command") {
                    commandHistory.getOrNull(if (historyIndex < 0) commandHistory.lastIndex else (historyIndex - 1).coerceAtLeast(0))?.let {
                        historyIndex = if (historyIndex < 0) commandHistory.lastIndex else (historyIndex - 1).coerceAtLeast(0)
                        commandInput = TextFieldValue(it, TextRange(it.length))
                    }
                }
                TerminalKeyButton("↓", "Next command") {
                    if (historyIndex >= 0) {
                        historyIndex = (historyIndex + 1).takeIf { it < commandHistory.size } ?: -1
                        commandInput = TextFieldValue(commandHistory.getOrNull(historyIndex) ?: "", TextRange((commandHistory.getOrNull(historyIndex) ?: "").length))
                    }
                }
                TerminalIconKeyButton(Icons.Default.ArrowBack, "Move cursor left") {
                    commandInput = commandInput.copy(selection = TextRange((commandInput.selection.start - 1).coerceAtLeast(0)))
                }
                TerminalIconKeyButton(Icons.Default.ArrowForward, "Move cursor right") {
                    commandInput = commandInput.copy(selection = TextRange((commandInput.selection.end + 1).coerceAtMost(commandInput.text.length)))
                }
                TerminalKeyButton(if (altActive) "ALT ✓" else "ALT", "Alt modifier") { altActive = !altActive }
                TerminalKeyButton("ESC", "Escape") { commandInput = TextFieldValue() }
                TerminalKeyButton("CTRL", "Control (Ctrl+C stops a running command)") {
                    if (isRunning && onStop != null) onStop()
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyButton(label: String, description: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
}

@Composable
private fun TerminalIconKeyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TerminalCommandPrompt(promptPath: String, command: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "root@pocket:$promptPath#",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = PocketGreen,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(command)) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", modifier = Modifier.size(16.dp))
            }
        }
        Text(
            command,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF0F6FC),
            softWrap = true,
        )
    }
}
