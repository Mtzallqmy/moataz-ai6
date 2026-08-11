package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl

/**
 * Project-support surface for the Moataz AI fork.
 *
 * The upstream donation endpoints are intentionally not presented under the Moataz AI
 * identity because that could mislead users about who receives the contribution. Legal
 * upstream attribution remains in About/License instead.
 */
@Composable
fun SettingDonatePage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.support_project_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            CardGroup(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.support_project_desc)) },
            ) {
                item(
                    onClick = { context.openUrl("https://github.com/Mtzallqmy/moataz-ai6") },
                    leadingContent = {
                        AsyncImage(
                            model = R.drawable.moataz_ai_mark,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    },
                    headlineContent = { Text("Moataz AI") },
                    supportingContent = { Text(stringResource(R.string.support_project_github_desc)) },
                )
            }
        }
    }
}
