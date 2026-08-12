package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.runtime.AgentRuntimeStage
import me.rerere.rikkahub.data.ai.runtime.AgentRuntimeState
import me.rerere.rikkahub.data.datastore.ExperienceMode

/**
 * Small execution timeline for the zero-config experience.
 *
 * It deliberately shows product language rather than tool/provider internals. Pro mode adds the
 * selected tool count for diagnostics while Auto stays focused on the current task stage.
 */
@Composable
fun AgentRuntimeStatusBar(
    state: AgentRuntimeState,
    experienceMode: ExperienceMode,
    modifier: Modifier = Modifier,
) {
    if (state.stage in setOf(
            AgentRuntimeStage.IDLE,
            AgentRuntimeStage.COMPLETED,
            AgentRuntimeStage.CANCELLED,
        )
    ) {
        return
    }

    val label = when (state.stage) {
        AgentRuntimeStage.IDLE -> return
        AgentRuntimeStage.PREPARING -> stringResource(R.string.agent_runtime_preparing)
        AgentRuntimeStage.PLANNING -> stringResource(R.string.agent_runtime_planning)
        AgentRuntimeStage.EXECUTING -> stringResource(R.string.agent_runtime_executing)
        AgentRuntimeStage.VERIFYING -> stringResource(R.string.agent_runtime_verifying)
        AgentRuntimeStage.WAITING_APPROVAL -> stringResource(R.string.agent_runtime_waiting_approval)
        AgentRuntimeStage.COMPLETED -> stringResource(R.string.agent_runtime_completed)
        AgentRuntimeStage.FAILED -> stringResource(R.string.agent_runtime_failed)
        AgentRuntimeStage.CANCELLED -> stringResource(R.string.agent_runtime_cancelled)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
            if (experienceMode == ExperienceMode.PRO && state.toolCount > 0) {
                Text(
                    text = stringResource(R.string.agent_runtime_tools_count, state.toolCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
