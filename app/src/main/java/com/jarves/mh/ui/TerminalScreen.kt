package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.TextUnit
import com.jarves.mh.R
import com.jarves.mh.model.DEFAULT_TERMINAL_FONT_SIZE
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    lines: List<TerminalOutputLine>,
    isRunning: Boolean,
    onRun: (String) -> Unit,
    onInput: (String) -> Unit = {},
    onInterrupt: (() -> Unit)? = null,
    onClear: () -> Unit,
    onToggleTheme: () -> Unit,
    themeMode: AppThemeMode,
    title: String = stringResource(R.string.ws_terminal_linux_title),
    subtitle: String = stringResource(R.string.ws_terminal_linux_subtitle),
    liveOutput: String = "",
    currentCommand: String? = null,
    commandDraft: String? = null,
    onCommandDraftConsumed: () -> Unit = {},
    promptPath: String = "/workspace",
    onStop: (() -> Unit)? = null,
    showThemeAction: Boolean = false,
    showQuickCommands: Boolean = true,
    compactHeader: Boolean = false,
    showHeader: Boolean = true,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    onViewportSizeChanged: (columns: Int, rows: Int) -> Unit = { _, _ -> },
) {
    var commandInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var commandHistory by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it },
        ),
    ) { mutableStateOf(emptyList<String>()) }
    var historyIndex by rememberSaveable { mutableStateOf(-1) }
    var altActive by rememberSaveable { mutableStateOf(false) }
    var ctrlActive by rememberSaveable { mutableStateOf(false) }
    val terminalScrollState = rememberScrollState()
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val terminalFontSize = fontSize.sp
    val terminalLineHeightUnit = terminalLineHeight(fontSize)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var consoleSize by remember { mutableStateOf(IntSize.Zero) }
    val charWidthPx = remember(fontSize, density, textMeasurer) {
        textMeasurer.measure(
            text = AnnotatedString("M".repeat(10)),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
        ).size.width / 10f
    }
    LaunchedEffect(consoleSize, charWidthPx, density) {
        if (consoleSize.width <= 0 || charWidthPx <= 0f) return@LaunchedEffect
        val horizontalPadding = with(density) { 28.dp.toPx() }
        val verticalPadding = with(density) { 28.dp.toPx() }
        val columns = ((consoleSize.width - horizontalPadding) / charWidthPx).toInt()
        val rows = ((consoleSize.height - verticalPadding) / with(density) { terminalLineHeightUnit.toPx() })
            .toInt()
        onViewportSizeChanged(columns, rows)
    }
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
        if (commandInput.text.isNotBlank()) {
            val submitted = commandInput.text
            if (isRunning) {
                onInput(submitted)
            } else {
                onRun(submitted)
                commandHistory = (commandHistory + submitted).takeLast(50)
                historyIndex = -1
            }
            commandInput = TextFieldValue()
        }
    }

    LaunchedEffect(commandDraft) {
        if (!commandDraft.isNullOrBlank()) {
            commandInput = TextFieldValue(commandDraft, TextRange(commandDraft.length))
            historyIndex = -1
            onCommandDraftConsumed()
        }
    }

    // Auto-scroll to bottom whenever scrollable content grows
    LaunchedEffect(Unit) {
        snapshotFlow { terminalScrollState.maxValue }
            .collect { maxValue ->
                terminalScrollState.scrollTo(maxValue)
            }
    }
    // Also trigger scroll when key state changes (e.g. isRunning toggling)
    LaunchedEffect(lines.size, isRunning, commandInput.text.length) {
        delay(100)
        terminalScrollState.scrollTo(terminalScrollState.maxValue)
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
        modifier = if (compactHeader || !showHeader) Modifier else Modifier.statusBarsPadding(),
        contentWindowInsets = if (showHeader) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        topBar = {
            if (compactHeader && showHeader) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(PocketOrange.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(subtitle, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isRunning) {
                            (onStop ?: onInterrupt)?.let { stop ->
                                TerminalStopChip(onClick = stop)
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.ws_terminal_clear_output), modifier = Modifier.size(20.dp))
                        }
                        if (showThemeAction) {
                            IconButton(onClick = onToggleTheme) {
                                Icon(
                                    if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = stringResource(R.string.theme_toggle),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
                }
            } else if (showHeader) {
                TopAppBar(
                    modifier = Modifier.padding(top = 8.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(9.dp),
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                        shape = RoundedCornerShape(9.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        if (isRunning) {
                            (onStop ?: onInterrupt)?.let { stop -> TerminalStopChip(onClick = stop) }
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.ws_terminal_clear_output))
                        }
                        if (showThemeAction) {
                            IconButton(onClick = onToggleTheme) {
                                Icon(
                                    if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = stringResource(R.string.theme_toggle),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // Shrink above the keyboard so the helper key row stays reachable while typing.
                .imePadding(),
        ) {
            if (showQuickCommands && !keyboardVisible) {
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

            val isDark = when (themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Neutral console background in light mode; the theme surface is tinted orange.
            val terminalBg = if (isDark) Color(0xFF090D14) else Color(0xFFF6F8FA)
            val promptGreen = if (isDark) PocketGreen else Color(0xFF0D7A3E)
            val commandTextColor = if (isDark) Color(0xFFF0F6FC) else MaterialTheme.colorScheme.onSurface
            val outputTextColor = if (isDark) Color(0xFFC9D1D9) else MaterialTheme.colorScheme.onSurface
            val emptyStateColor = if (isDark) Color(0xFF6E7681) else MaterialTheme.colorScheme.onSurfaceVariant

            // Console output area
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .border(1.dp, if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                color = terminalBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onSizeChanged { consoleSize = it }
                            .verticalScroll(terminalScrollState)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (lines.isEmpty()) {
                                    Text(
                                        stringResource(R.string.workspace_terminal_ready),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = terminalFontSize,
                                        color = emptyStateColor,
                                    )
                                }

                                lines.forEach { item ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TerminalCommandPrompt(
                                            promptPath = terminalPromptPath,
                                            command = item.command,
                                            isDark = isDark,
                                            fontSize = fontSize,
                                        )
                                        if (item.output.isNotEmpty()) {
                                            Text(
                                                text = sanitizeTerminalOutput(item.output),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = terminalFontSize,
                                                lineHeight = terminalLineHeightUnit,
                                                color = if (item.exitCode != 0) MaterialTheme.colorScheme.error else outputTextColor,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                }

                                if (isRunning && currentCommand != null) {
                                    TerminalCommandPrompt(
                                        promptPath = terminalPromptPath,
                                        command = currentCommand,
                                        isDark = isDark,
                                        fontSize = fontSize,
                                    )
                                }

                                if (liveOutput.isNotBlank()) {
                                    Text(
                                        sanitizeTerminalOutput(liveOutput),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = terminalFontSize,
                                        lineHeight = terminalLineHeightUnit,
                                        color = outputTextColor,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        run {
                            // While a process is running, keep the input surface visually
                            // empty until the user types. A permanent prompt/cursor here
                            // changed the console height and made auto-scroll look like a
                            // full terminal refresh on every blink.
                            val prefix = if (isRunning) "" else "root@pocket:$terminalPromptPath# "
                            val prefixVisualTransformation = remember(prefix, isDark) {
                                VisualTransformation { text ->
                                    val transformed = buildAnnotatedString {
                                        withStyle(SpanStyle(color = promptGreen, fontWeight = FontWeight.Bold)) {
                                            append(prefix)
                                        }
                                        withStyle(SpanStyle(color = commandTextColor, fontWeight = FontWeight.SemiBold)) {
                                            append(text.text)
                                        }
                                    }
                                    val offsetMapping = object : OffsetMapping {
                                        override fun originalToTransformed(offset: Int): Int =
                                            (offset + prefix.length).coerceIn(0, prefix.length + text.length)

                                        override fun transformedToOriginal(offset: Int): Int =
                                            (offset - prefix.length).coerceIn(0, text.length)
                                    }
                                    TransformedText(transformed, offsetMapping)
                                }
                            }

                            BasicTextField(
                                value = commandInput,
                                onValueChange = { next ->
                                    if (ctrlActive && next.text.length > commandInput.text.length) {
                                        val inserted = next.text.substring(
                                            commandInput.selection.start.coerceAtMost(next.text.length),
                                            next.selection.end.coerceAtMost(next.text.length),
                                        )
                                        if (inserted.any { it.equals('c', ignoreCase = true) }) {
                                            if (isRunning) {
                                                onInterrupt?.invoke()
                                            } else {
                                                // A shell with no foreground process uses
                                                // Ctrl+C to cancel the current command line.
                                                commandInput = TextFieldValue()
                                            }
                                            ctrlActive = false
                                        } else {
                                            commandInput = next
                                            ctrlActive = false
                                        }
                                    } else {
                                        commandInput = next
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(inputFocusRequester),
                                singleLine = false,
                                visualTransformation = prefixVisualTransformation,
                                cursorBrush = SolidColor(promptGreen),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = terminalFontSize,
                                    lineHeight = terminalLineHeightUnit,
                                    fontWeight = FontWeight.SemiBold,
                                    color = commandTextColor,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = { submitCommand() },
                                    onDone = { submitCommand() },
                                    onGo = { submitCommand() },
                                ),
                            )
                        }
                    }
                }
            }

            // Keyboard helper row. These operate on the command draft, so they are
            // useful even when the phone keyboard does not expose terminal keys.
            // Kept visible with the keyboard open: that is when the arrows and modifiers are needed.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TerminalKeyButton("↑", stringResource(R.string.ws_terminal_previous_command), fontSize = fontSize) {
                    commandHistory.getOrNull(if (historyIndex < 0) commandHistory.lastIndex else (historyIndex - 1).coerceAtLeast(0))?.let {
                        historyIndex = if (historyIndex < 0) commandHistory.lastIndex else (historyIndex - 1).coerceAtLeast(0)
                        commandInput = TextFieldValue(it, TextRange(it.length))
                    }
                }
                TerminalKeyButton("↓", stringResource(R.string.ws_terminal_next_command), fontSize = fontSize) {
                    if (historyIndex >= 0) {
                        historyIndex = (historyIndex + 1).takeIf { it < commandHistory.size } ?: -1
                        commandInput = TextFieldValue(commandHistory.getOrNull(historyIndex) ?: "", TextRange((commandHistory.getOrNull(historyIndex) ?: "").length))
                    }
                }
                TerminalIconKeyButton(Icons.Default.ArrowBack, stringResource(R.string.ws_terminal_cursor_left)) {
                    commandInput = commandInput.copy(selection = TextRange((commandInput.selection.start - 1).coerceAtLeast(0)))
                }
                TerminalIconKeyButton(Icons.Default.ArrowForward, stringResource(R.string.ws_terminal_cursor_right)) {
                    commandInput = commandInput.copy(selection = TextRange((commandInput.selection.end + 1).coerceAtMost(commandInput.text.length)))
                }
                TerminalKeyButton(stringResource(R.string.ws_terminal_key_alt), stringResource(R.string.ws_terminal_alt_modifier), active = altActive, fixedWidth = true, showActiveState = true, fontSize = fontSize) { altActive = !altActive }
                TerminalKeyButton(stringResource(R.string.ws_terminal_key_esc), stringResource(R.string.ws_terminal_escape), fontSize = fontSize) { commandInput = TextFieldValue() }
                TerminalKeyButton(stringResource(R.string.ws_terminal_key_ctrl), stringResource(R.string.ws_terminal_ctrl_modifier), active = ctrlActive, fixedWidth = true, showActiveState = true, fontSize = fontSize) {
                    ctrlActive = !ctrlActive
                    if (ctrlActive) openTerminalKeyboard()
                }
            }
        }
    }
}

private fun terminalLineHeight(fontSize: Int): TextUnit = (fontSize * 1.5f).sp

@Composable
private fun TerminalKeyButton(
    label: String,
    description: String,
    active: Boolean = false,
    fixedWidth: Boolean = false,
    showActiveState: Boolean = false,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    onClick: () -> Unit,
) {
    val activeStateDescription = if (showActiveState) {
        stringResource(
            if (active) R.string.ws_terminal_modifier_active else R.string.ws_terminal_modifier_inactive,
        )
    } else {
        null
    }
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .heightIn(min = 34.dp)
            .then(if (fixedWidth) Modifier.widthIn(min = 70.dp) else Modifier)
            .semantics {
                contentDescription = description
                activeStateDescription?.let { stateDescription = it }
            },
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) PocketOrange.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (active) PocketOrange else MaterialTheme.colorScheme.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) PocketOrange else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = terminalLineHeight(fontSize),
        )
        if (showActiveState) {
            // Reserve the slot even when inactive so activating ALT/CTRL cannot reflow the row.
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (active) PocketOrange else Color.Transparent,
            )
        }
    }
}

@Composable
private fun TerminalIconKeyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TerminalCommandPrompt(
    promptPath: String,
    command: String,
    isDark: Boolean = true,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
) {
    val promptGreen = if (isDark) PocketGreen else Color(0xFF0D7A3E)
    val commandColor = if (isDark) Color(0xFFF0F6FC) else MaterialTheme.colorScheme.onSurface
    val promptText = remember(promptPath, command, isDark) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = promptGreen, fontWeight = FontWeight.Bold)) {
                append("root@pocket:$promptPath# ")
            }
            withStyle(SpanStyle(color = commandColor, fontWeight = FontWeight.SemiBold)) {
                append(command)
            }
        }
    }
    Text(
        text = promptText,
        modifier = Modifier.fillMaxWidth(),
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = terminalLineHeight(fontSize),
        softWrap = true,
    )
}

// Small stop pill shown in the terminal header while a command is running.
@Composable
private fun TerminalStopChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.chat_stop), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
