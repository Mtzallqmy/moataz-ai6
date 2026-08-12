from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# --- Persisted Auto / Pro experience mode ---
prefs_path = Path("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
prefs = prefs_path.read_text()

prefs = replace_once(
    prefs,
    "enum class AutoCompactionThresholdMode {\n    PERCENT,\n    TOKENS,\n}\n",
    "enum class AutoCompactionThresholdMode {\n    PERCENT,\n    TOKENS,\n}\n\n"
    "enum class ExperienceMode {\n    AUTO,\n    PRO,\n}\n",
    "experience enum",
)

prefs = replace_once(
    prefs,
    "        val DEVELOPER_MODE = booleanPreferencesKey(\"developer_mode\")\n",
    "        val DEVELOPER_MODE = booleanPreferencesKey(\"developer_mode\")\n"
    "        val EXPERIENCE_MODE = stringPreferencesKey(\"experience_mode\")\n",
    "experience key",
)

prefs = replace_once(
    prefs,
    "                developerMode = preferences[DEVELOPER_MODE] == true,\n"
    "                displaySetting = runCatching {\n",
    "                developerMode = preferences[DEVELOPER_MODE] == true,\n"
    "                experienceMode = preferences[EXPERIENCE_MODE]\n"
    "                    ?.let { value -> runCatching { ExperienceMode.valueOf(value) }.getOrNull() }\n"
    "                    ?: ExperienceMode.AUTO,\n"
    "                displaySetting = runCatching {\n",
    "experience read",
)

prefs = replace_once(
    prefs,
    "            preferences[DEVELOPER_MODE] = settings.developerMode\n"
    "            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)\n",
    "            preferences[DEVELOPER_MODE] = settings.developerMode\n"
    "            preferences[EXPERIENCE_MODE] = settings.experienceMode.name\n"
    "            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)\n",
    "experience write",
)

prefs = replace_once(
    prefs,
    "    val developerMode: Boolean = false,\n"
    "    val displaySetting: DisplaySetting = DisplaySetting(),\n",
    "    val developerMode: Boolean = false,\n"
    "    val experienceMode: ExperienceMode = ExperienceMode.AUTO,\n"
    "    val displaySetting: DisplaySetting = DisplaySetting(),\n",
    "experience settings field",
)

prefs_path.write_text(prefs)


# --- Settings surface: Auto stays compact; Pro exposes the full control plane. ---
setting_path = Path("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt")
setting = setting_path.read_text()

setting = replace_once(
    setting,
    "import me.rerere.rikkahub.data.datastore.isNotConfigured\n",
    "import me.rerere.rikkahub.data.datastore.ExperienceMode\n"
    "import me.rerere.rikkahub.data.datastore.isNotConfigured\n",
    "SettingPage experience import",
)

setting = replace_once(
    setting,
    "                ) {\n"
    "                    item(\n"
    "                        leadingContent = { Icon(HugeIcons.Sun01, null) },\n",
    "                ) {\n"
    "                    item(\n"
    "                        trailingContent = {\n"
    "                            Select(\n"
    "                                options = ExperienceMode.entries,\n"
    "                                selectedOption = settings.experienceMode,\n"
    "                                onOptionSelected = { mode ->\n"
    "                                    vm.updateSettings(settings.copy(experienceMode = mode))\n"
    "                                },\n"
    "                                optionToString = { mode ->\n"
    "                                    when (mode) {\n"
    "                                        ExperienceMode.AUTO -> stringResource(R.string.experience_mode_auto)\n"
    "                                        ExperienceMode.PRO -> stringResource(R.string.experience_mode_pro)\n"
    "                                    }\n"
    "                                },\n"
    "                                modifier = Modifier.width(150.dp),\n"
    "                            )\n"
    "                        },\n"
    "                        headlineContent = { Text(stringResource(R.string.experience_mode_title)) },\n"
    "                        supportingContent = {\n"
    "                            Text(\n"
    "                                stringResource(\n"
    "                                    if (settings.experienceMode == ExperienceMode.AUTO) {\n"
    "                                        R.string.experience_mode_auto_desc\n"
    "                                    } else {\n"
    "                                        R.string.experience_mode_pro_desc\n"
    "                                    }\n"
    "                                )\n"
    "                            )\n"
    "                        },\n"
    "                    )\n"
    "                    item(\n"
    "                        leadingContent = { Icon(HugeIcons.Sun01, null) },\n",
    "experience selector row",
)

# Extensions are an advanced surface; keep Assistant visible in Auto.
setting = replace_once(
    setting,
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.Extensions) },\n"
    "                        leadingContent = { Icon(HugeIcons.Package, null) },\n"
    "                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },\n"
    "                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },\n"
    "                    )\n",
    "                    if (settings.experienceMode == ExperienceMode.PRO) {\n"
    "                        item(\n"
    "                            onClick = { navController.navigate(Screen.Extensions) },\n"
    "                            leadingContent = { Icon(HugeIcons.Package, null) },\n"
    "                            supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },\n"
    "                            headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },\n"
    "                        )\n"
    "                    }\n",
    "extensions Pro gate",
)

# Providers/search/speech/MCP are advanced; GitHub stays visible as a first-class source.
setting = replace_once(
    setting,
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingProvider) },\n",
    "                    if (settings.experienceMode == ExperienceMode.PRO) {\n"
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingProvider) },\n",
    "open first Pro services gate",
)

setting = replace_once(
    setting,
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingGitHub) },\n",
    "                    }\n"
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingGitHub) },\n",
    "close first Pro services gate",
)

setting = replace_once(
    setting,
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingWeb) },\n",
    "                    if (settings.experienceMode == ExperienceMode.PRO) {\n"
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingWeb) },\n",
    "open second Pro services gate",
)

setting = replace_once(
    setting,
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingPermissions) },\n"
    "                        leadingContent = { Icon(HugeIcons.Shield01, null) },\n"
    "                        supportingContent = { Text(stringResource(R.string.setting_page_permissions_desc)) },\n"
    "                        headlineContent = { Text(stringResource(R.string.setting_page_permissions)) },\n"
    "                    )\n"
    "                }\n"
    "            }\n\n"
    "            item(\"dataSettings\") {\n",
    "                    item(\n"
    "                        onClick = { navController.navigate(Screen.SettingPermissions) },\n"
    "                        leadingContent = { Icon(HugeIcons.Shield01, null) },\n"
    "                        supportingContent = { Text(stringResource(R.string.setting_page_permissions_desc)) },\n"
    "                        headlineContent = { Text(stringResource(R.string.setting_page_permissions)) },\n"
    "                    )\n"
    "                    }\n"
    "                }\n"
    "            }\n\n"
    "            item(\"dataSettings\") {\n",
    "close second Pro services gate",
)

setting_path.write_text(setting)


# --- ViewModel exposes the runtime state already owned by ChatService. ---
vm_path = Path("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt")
vm = vm_path.read_text()
vm = replace_once(
    vm,
    "    val processingStatus: StateFlow<String?> =\n"
    "        chatService\n"
    "            .getProcessingStatusFlow(_conversationId)\n",
    "    val processingStatus: StateFlow<String?> =\n"
    "        chatService\n"
    "            .getProcessingStatusFlow(_conversationId)\n\n"
    "    val agentRuntimeState = chatService.getAgentRuntimeState(_conversationId)\n",
    "ChatVM runtime state",
)
vm_path.write_text(vm)


# --- Chat page: status bar sits immediately above the composer. ---
chat_path = Path("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
chat = chat_path.read_text()

chat = replace_once(
    chat,
    "import me.rerere.rikkahub.data.datastore.Settings\n",
    "import me.rerere.rikkahub.data.ai.runtime.AgentRuntimeState\n"
    "import me.rerere.rikkahub.data.datastore.Settings\n",
    "ChatPage runtime state import",
)
chat = replace_once(
    chat,
    "import me.rerere.rikkahub.ui.components.ai.ChatInput\n",
    "import me.rerere.rikkahub.ui.components.ai.AgentRuntimeStatusBar\n"
    "import me.rerere.rikkahub.ui.components.ai.ChatInput\n",
    "ChatPage status import",
)
chat = replace_once(
    chat,
    "    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()\n",
    "    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()\n"
    "    val agentRuntimeState by vm.agentRuntimeState.collectAsStateWithLifecycle()\n",
    "ChatPage runtime collect",
)

# Both adaptive ChatPageContent call sites use the same processingStatus anchor.
old = "                    processingStatus = processingStatus,\n                    setting = setting,\n"
new = "                    processingStatus = processingStatus,\n                    runtimeState = agentRuntimeState,\n                    setting = setting,\n"
count = chat.count(old)
if count != 2:
    raise SystemExit(f"ChatPageContent call sites: expected 2 matches, found {count}")
chat = chat.replace(old, new)

chat = replace_once(
    chat,
    "    processingStatus: String? = null,\n"
    "    setting: Settings,\n",
    "    processingStatus: String? = null,\n"
    "    runtimeState: AgentRuntimeState,\n"
    "    setting: Settings,\n",
    "ChatPageContent runtime arg",
)

chat = replace_once(
    chat,
    "            bottomBar = {\n"
    "                ChatInput(\n",
    "            bottomBar = {\n"
    "                Column {\n"
    "                AgentRuntimeStatusBar(\n"
    "                    state = runtimeState,\n"
    "                    experienceMode = setting.experienceMode,\n"
    "                )\n"
    "                ChatInput(\n",
    "status bar before ChatInput",
)

# Close the new Column immediately after ChatInput.
chat = replace_once(
    chat,
    "                    onMoreClick = {\n"
    "                        showFilesSheet = true\n"
    "                    },\n"
    "                )\n"
    "            },\n"
    "            containerColor = Color.Transparent,\n",
    "                    onMoreClick = {\n"
    "                        showFilesSheet = true\n"
    "                    },\n"
    "                )\n"
    "                }\n"
    "            },\n"
    "            containerColor = Color.Transparent,\n",
    "close status Column",
)

chat_path.write_text(chat)
