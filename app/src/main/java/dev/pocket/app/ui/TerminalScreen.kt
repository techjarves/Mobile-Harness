package dev.pocket.app.ui

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
) {
    var commandInput by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(commandDraft) {
        if (!commandDraft.isNullOrBlank()) {
            commandInput = commandDraft
            onCommandDraftConsumed()
        }
    }

    LaunchedEffect(lines.size, liveOutput.length, isRunning, currentCommand) {
        val itemCount = maxOf(1, lines.size) +
            (if (isRunning && currentCommand != null) 1 else 0) +
            (if (liveOutput.isNotBlank()) 1 else 0) +
            (if (isRunning) 1 else 0)
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(title, fontWeight = FontWeight.Bold)
                        }
                        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear output")
                    }
                    if (showThemeAction) {
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Quick command chips
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

            // Console output area
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                color = Color(0xFF090D14),
                shape = RoundedCornerShape(12.dp),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                "Pocket Dev Terminal ready.\nType a bash command below or tap a quick command chip above.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF6E7681),
                            )
                        }
                    }

                    items(lines, key = { it.id }) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TerminalCommandPrompt(promptPath = promptPath, command = item.command)
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
                        item(key = "running-terminal-command") {
                            TerminalCommandPrompt(promptPath = promptPath, command = currentCommand)
                        }
                    }

                    if (liveOutput.isNotBlank()) {
                        item(key = "live-terminal-output") {
                            Text(
                                liveOutput,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFFC9D1D9),
                            )
                        }
                    }

                    if (isRunning) {
                        item {
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
            }

            // Command input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = {
                        Text(
                            "e.g. ls -la, python3 script.py",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    maxLines = 4,
                    enabled = !isRunning,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (commandInput.isNotBlank() && !isRunning) {
                                onRun(commandInput)
                                commandInput = ""
                            }
                        },
                    ),
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val pasted = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (pasted.isNullOrEmpty()) {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            commandInput = pasted
                        }
                    },
                    enabled = !isRunning,
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste command")
                }

                if (isRunning && onStop != null) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.background(MaterialTheme.colorScheme.error, CircleShape),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop command", tint = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (commandInput.isNotBlank() && !isRunning) {
                                onRun(commandInput)
                                commandInput = ""
                            }
                        },
                        enabled = commandInput.isNotBlank() && !isRunning,
                        modifier = Modifier.background(PocketOrange, CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalCommandPrompt(promptPath: String, command: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "root@pocket:$promptPath#",
            modifier = Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = PocketGreen,
        )
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
