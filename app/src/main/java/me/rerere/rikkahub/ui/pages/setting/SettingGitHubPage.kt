package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.source.github.GitHubConnectionManager
import me.rerere.rikkahub.data.source.github.GitHubConnectionStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun SettingGitHubPage() {
    val manager: GitHubConnectionManager = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var refreshKey by remember { mutableStateOf(0) }
    val connection by produceState<GitHubConnectionStatus?>(initialValue = null, refreshKey) {
        value = manager.currentConnection()
    }
    var token by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_github_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(title = { Text(stringResource(R.string.setting_github_connection)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_github_status)) },
                    supportingContent = {
                        when (val state = connection) {
                            null -> Text(stringResource(R.string.setting_github_checking))
                            GitHubConnectionStatus.Disconnected -> Text(stringResource(R.string.setting_github_disconnected))
                            is GitHubConnectionStatus.Connected -> Text(
                                stringResource(R.string.setting_github_connected_as, state.login)
                            )
                            is GitHubConnectionStatus.Invalid -> Text(stringResource(R.string.setting_github_invalid))
                        }
                    },
                    trailingContent = {
                        if (connection == null) CircularProgressIndicator()
                    },
                )
            }

            Text(
                text = stringResource(R.string.setting_github_security_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_github_token_label)) },
                supportingText = { Text(error ?: stringResource(R.string.setting_github_token_help)) },
                isError = error != null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    enabled = token.isNotBlank() && !saving,
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching { manager.connectFineGrainedPat(token) }
                                .onSuccess {
                                    token = ""
                                    refreshKey++
                                }
                                .onFailure { error = it.message ?: "GitHub authentication failed" }
                            saving = false
                        }
                    },
                ) {
                    if (saving) CircularProgressIndicator() else Text(stringResource(R.string.setting_github_connect))
                }

                if (connection is GitHubConnectionStatus.Connected || connection is GitHubConnectionStatus.Invalid) {
                    TextButton(
                        onClick = {
                            manager.disconnect()
                            token = ""
                            error = null
                            refreshKey++
                        },
                    ) {
                        Text(stringResource(R.string.setting_github_disconnect))
                    }
                }
            }

            Text(
                text = stringResource(R.string.setting_github_pat_recommendation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
