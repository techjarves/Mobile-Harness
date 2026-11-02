package com.jarves.mh.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.R
import com.jarves.mh.backup.BackupController
import com.jarves.mh.backup.BackupCrypto
import com.jarves.mh.backup.BackupJobState
import com.jarves.mh.backup.BackupJobStatus
import com.jarves.mh.backup.BackupOperation
import com.jarves.mh.backup.BackupStage
import com.jarves.mh.backup.InvalidBackupException
import com.jarves.mh.backup.NewerBackupException
import com.jarves.mh.backup.WrongBackupPasswordException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD_LENGTH = 6

/** A picked backup file waiting for the user's password and/or confirmation. */
private data class PendingRestore(val uri: Uri, val encrypted: Boolean, val password: String? = null)

/**
 * Create / restore buttons, their dialogs and live progress. [busy] blocks starting while an
 * agent, terminal or build is running, since a restore replaces the files they work on.
 */
@Composable
fun BackupRestoreSection(busy: Boolean, compact: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by BackupController.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var checking by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) BackupController.start(context, BackupOperation.BACKUP, uri, backupPassword?.toCharArray())
        backupPassword = null
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            checking = true
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { BackupCrypto.readHeader(it).encrypted } }
            }
            checking = false
            result
                .onSuccess { encrypted -> pendingRestore = PendingRestore(uri, encrypted) }
                .onFailure { error -> context.toast(backupErrorText(context, error)) }
        }
    }

    fun startIfIdle(action: () -> Unit) {
        if (busy) context.toast(context.getString(R.string.backup_busy)) else action()
    }

    if (showCreateDialog) {
        CreateBackupDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { password ->
                showCreateDialog = false
                backupPassword = password
                createLauncher.launch(defaultBackupName())
            },
        )
    }

    pendingRestore?.let { pending ->
        if (pending.encrypted && pending.password == null) {
            RestorePasswordDialog(
                checking = checking,
                error = passwordError,
                onDismiss = {
                    pendingRestore = null
                    passwordError = null
                },
                onConfirm = { password ->
                    scope.launch {
                        checking = true
                        passwordError = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(pending.uri)!!.use {
                                    BackupCrypto.verifyPassword(it, password.toCharArray())
                                }
                            }
                        }
                        checking = false
                        result
                            .onSuccess { pendingRestore = pending.copy(password = password) }
                            .onFailure { error -> passwordError = backupErrorText(context, error) }
                    }
                },
            )
        } else {
            RestoreConfirmDialog(
                encrypted = pending.encrypted,
                // A fresh install has nothing of its own to keep, so bring everything back there.
                settingsByDefault = compact,
                onDismiss = { pendingRestore = null },
                onConfirm = { includeSettings ->
                    pendingRestore = null
                    startIfIdle {
                        BackupController.start(
                            context,
                            BackupOperation.RESTORE,
                            pending.uri,
                            pending.password?.toCharArray(),
                            includeSettings,
                        )
                    }
                },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!compact) {
            Text(stringResource(R.string.backup_description), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (job.status != BackupJobStatus.IDLE) BackupProgressCard(job)
        val idle = !job.running && !checking
        if (!compact) {
            Button(
                onClick = { startIfIdle { showCreateDialog = true } },
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Backup, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.backup_create))
            }
        }
        OutlinedButton(
            onClick = { startIfIdle { restoreLauncher.launch(arrayOf("*/*")) } },
            enabled = idle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (compact) R.string.backup_restore_on_setup else R.string.backup_restore))
        }
    }
}

@Composable
private fun BackupProgressCard(job: BackupJobState) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (job.status) {
                BackupJobStatus.RUNNING -> {
                    val percent = job.percent
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                when (job.stage) {
                                    BackupStage.SCANNING -> R.string.backup_stage_scanning
                                    BackupStage.WRITING -> R.string.backup_stage_writing
                                    BackupStage.READING -> if (percent == null) R.string.backup_stage_preparing else R.string.backup_stage_reading
                                    BackupStage.APPLYING -> R.string.backup_stage_applying
                                },
                            ),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (percent != null) Text("$percent%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    if (job.totalBytes > 0) {
                        Text(
                            stringResource(
                                R.string.backup_progress_bytes,
                                Formatter.formatShortFileSize(context, job.doneBytes),
                                Formatter.formatShortFileSize(context, job.totalBytes),
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(R.string.backup_running_background), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (job.stage != BackupStage.APPLYING) {
                        TextButton(onClick = { BackupController.cancel(context) }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                BackupJobStatus.COMPLETE -> {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    val summary = job.summary
                    Text(
                        if (job.operation == BackupOperation.RESTORE) {
                            stringResource(R.string.backup_restore_done_restarting)
                        } else {
                            stringResource(
                                R.string.backup_done,
                                summary?.let { stringResource(R.string.backup_summary, it.projects, it.files) }.orEmpty(),
                            )
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (job.operation == BackupOperation.BACKUP) {
                        TextButton(onClick = BackupController::dismiss) { Text(stringResource(R.string.action_ok)) }
                    }
                }
                BackupJobStatus.ERROR, BackupJobStatus.CANCELLED -> {
                    Text(
                        if (job.status == BackupJobStatus.CANCELLED) stringResource(R.string.backup_cancelled) else job.error.orEmpty(),
                        color = if (job.status == BackupJobStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = BackupController::dismiss) { Text(stringResource(R.string.action_ok)) }
                }
                BackupJobStatus.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    encrypted: Boolean,
    settingsByDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (includeSettings: Boolean) -> Unit,
) {
    var includeSettings by remember { mutableStateOf(settingsByDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_restore_confirm_desc))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (encrypted) R.string.backup_restore_settings_toggle else R.string.backup_restore_settings_only_toggle),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.backup_restore_settings_toggle_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = includeSettings, onCheckedChange = { includeSettings = it })
                }
                if (includeSettings && !encrypted) {
                    Text(
                        stringResource(R.string.backup_restore_confirm_no_secrets),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeSettings) }) { Text(stringResource(R.string.backup_restore_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CreateBackupDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var protect by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val error = when {
        !protect -> null
        password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH -> stringResource(R.string.backup_password_short)
        confirm.isNotEmpty() && confirm != password -> stringResource(R.string.backup_password_mismatch)
        else -> null
    }
    val valid = !protect || (password.length >= MIN_PASSWORD_LENGTH && password == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_password_toggle), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.backup_password_toggle_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = protect, onCheckedChange = { protect = it })
                }
                if (protect) {
                    PasswordField(password, { password = it }, stringResource(R.string.backup_password), visible) { visible = !visible }
                    PasswordField(confirm, { confirm = it }, stringResource(R.string.backup_password_confirm), visible) { visible = !visible }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Text(stringResource(R.string.backup_password_warning), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.takeIf { protect }) }, enabled = valid) {
                Text(stringResource(R.string.backup_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestorePasswordDialog(checking: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(stringResource(R.string.backup_restore_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_restore_password_desc))
                PasswordField(password, { password = it }, stringResource(R.string.backup_password), visible) { visible = !visible }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty() && !checking) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_checking))
                } else {
                    Text(stringResource(R.string.backup_continue))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, visible: Boolean, onToggleVisible: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.backup_hide_password else R.string.backup_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * After a restore replaced the data under the running app, restart the process so every
 * screen and cached value reloads from the restored files.
 */
@Composable
fun BackupRestartEffect() {
    val context = LocalContext.current
    val job by BackupController.state.collectAsStateWithLifecycle()
    LaunchedEffect(job.status, job.operation) {
        if (job.operation == BackupOperation.RESTORE && job.status == BackupJobStatus.COMPLETE) {
            delay(1_500)
            restartApp(context)
        }
    }
}

private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}

private fun defaultBackupName(): String =
    "MobileHarness-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".mhbackup"

private fun backupErrorText(context: Context, error: Throwable): String = when (error) {
    is WrongBackupPasswordException -> context.getString(R.string.backup_error_wrong_password)
    is NewerBackupException -> context.getString(R.string.backup_error_newer)
    is InvalidBackupException -> context.getString(R.string.backup_error_invalid)
    else -> context.getString(R.string.backup_error_open_input)
}

private fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
