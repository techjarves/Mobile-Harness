package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.BuildConfig
import com.jarves.mh.R

/** Shown once after an update. Edit `whats_new_items` for each release. */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit, onOpenBackup: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column {
                Text(stringResource(R.string.whats_new_title), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.whats_new_version, BuildConfig.VERSION_NAME),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stringArrayResource(R.array.whats_new_items).forEach { item ->
                    Row {
                        Box(
                            Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(item, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenBackup) { Text(stringResource(R.string.whats_new_open_backup)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_got_it)) } },
    )
}
