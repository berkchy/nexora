package com.pickle.patcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.ui.theme.Accent

/**
 * The in-app face of [JobProgress]: what the notification shows while the app
 * is in the background, shown inline while it is in front. Finished jobs stay
 * for a moment with their result and a dismiss button.
 */
@Composable
fun JobStrip(jobs: List<JobProgress.Job>, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = jobs.isNotEmpty(), modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            jobs.forEach { job ->
                JobRow(job)
            }
        }
    }
}

@Composable
private fun JobRow(job: JobProgress.Job) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (job.detail.isNotBlank() || job.message.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        job.detail.ifBlank { job.message },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (job.running) {
                    Spacer(Modifier.height(8.dp))
                    if (job.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { job.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                job.state == JobProgress.State.DONE -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Done",
                    tint = Accent,
                    modifier = Modifier.size(20.dp),
                )
                job.state == JobProgress.State.FAILED -> Icon(
                    Icons.Filled.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { JobProgress.clear(job.id) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}