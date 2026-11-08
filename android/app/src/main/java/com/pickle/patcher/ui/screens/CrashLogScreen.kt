package com.pickle.patcher.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.Gray30

@Composable
fun CrashLogScreen(vm: PatcherViewModel) {
    val crash by vm.crashLog.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val textScroll = rememberScrollState()

    LaunchedEffect(Unit) { vm.refreshCrashLog() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader(
            icon = Icons.Filled.BugReport,
            title = "Crash log",
            subtitle = "Stack traces from uncaught exceptions.",
            status = if (crash != null) "Crash found" else "Healthy",
            statusTone = if (crash != null) Tone.Error else Tone.Primary,
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("Latest crash", "Most recent stack trace")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BugReport,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Latest crash", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (crash != null) {
                    StatusPill("Logged", tone = Tone.Error)
                } else {
                    StatusPill("None", tone = Tone.Primary)
                }
            }

            Spacer(Modifier.height(12.dp))

            val c = crash
            if (c == null) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text("No crash file found.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "The app hasn't crashed yet, or the log file is missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${c.fileName}  ·  ${c.modified}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    Text(
                        c.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .verticalScroll(textScroll)
                            .padding(12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = "Refresh",
                onClick = { vm.refreshCrashLog() },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "Share",
                onClick = { crash?.content?.let { shareCrash(context, it) } },
                enabled = crash != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun shareCrash(context: Context, content: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "nexora-crash.txt\n\n$content")
    }
    context.startActivity(Intent.createChooser(intent, "Share crash log"))
}
