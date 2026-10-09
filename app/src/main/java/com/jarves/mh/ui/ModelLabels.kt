package com.jarves.mh.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind

/*
 * Display-only labels for model enums. The English fields on the enums stay as-is
 * because they are also used for logs, persistence and text sent to agents; UI code
 * should go through these helpers instead.
 */

@StringRes
fun ProviderKind.titleRes(): Int = when (this) {
    ProviderKind.CLAUDE -> R.string.provider_title_claude
    ProviderKind.ANTHROPIC -> R.string.provider_title_anthropic
    ProviderKind.LLM_ROUTER -> R.string.provider_title_openrouter
    ProviderKind.DEEPSEEK -> R.string.provider_title_deepseek
    ProviderKind.KIMI -> R.string.provider_title_kimi
    ProviderKind.OPENCODE_ZEN -> R.string.provider_title_opencode
    ProviderKind.NVIDIA_NIM -> R.string.provider_title_nvidia
    ProviderKind.CUSTOM -> R.string.provider_title_custom
}

@StringRes
fun ProviderKind.subtitleRes(): Int = when (this) {
    ProviderKind.CLAUDE -> R.string.provider_subtitle_claude
    ProviderKind.ANTHROPIC -> R.string.provider_subtitle_anthropic
    ProviderKind.LLM_ROUTER -> R.string.provider_subtitle_openrouter
    ProviderKind.DEEPSEEK -> R.string.provider_subtitle_deepseek
    ProviderKind.KIMI, ProviderKind.CUSTOM -> R.string.provider_subtitle_anthropic_compatible
    ProviderKind.OPENCODE_ZEN -> R.string.provider_subtitle_opencode
    ProviderKind.NVIDIA_NIM -> R.string.provider_subtitle_nvidia
}

@StringRes
fun AgentKind.titleRes(): Int = when (this) {
    AgentKind.CLAUDE_CODE -> R.string.agent_title_claude
    AgentKind.DEEPSEEK_HARNESS -> R.string.agent_title_deepseek
    AgentKind.ANTIGRAVITY -> R.string.agent_title_antigravity
}

@StringRes
fun AgentKind.subtitleRes(): Int = when (this) {
    AgentKind.CLAUDE_CODE -> R.string.agent_subtitle_claude
    AgentKind.DEEPSEEK_HARNESS -> R.string.agent_subtitle_deepseek
    AgentKind.ANTIGRAVITY -> R.string.agent_subtitle_antigravity
}

@StringRes
fun DevStack.labelRes(): Int = when (this) {
    DevStack.WEB -> R.string.md_stack_web_label
    DevStack.PYTHON -> R.string.md_stack_python_label
    DevStack.ANDROID -> R.string.md_stack_android_label
    DevStack.CPP -> R.string.md_stack_cpp_label
    DevStack.PHP -> R.string.md_stack_php_label
}

@StringRes
fun DevStack.descriptionRes(): Int = when (this) {
    DevStack.WEB -> R.string.md_stack_web_description
    DevStack.PYTHON -> R.string.md_stack_python_description
    DevStack.ANDROID -> R.string.md_stack_android_description
    DevStack.CPP -> R.string.md_stack_cpp_description
    DevStack.PHP -> R.string.md_stack_php_description
}

@StringRes
fun DevStack.installsSummaryRes(): Int = when (this) {
    DevStack.WEB -> R.string.md_stack_web_installs
    DevStack.PYTHON -> R.string.md_stack_python_installs
    DevStack.ANDROID -> R.string.md_stack_android_installs
    DevStack.CPP -> R.string.md_stack_cpp_installs
    DevStack.PHP -> R.string.md_stack_php_installs
}

// Composable variants (LocalContext is already localized).
@Composable fun ProviderKind.localizedTitle(): String = stringResource(titleRes())
@Composable fun ProviderKind.localizedSubtitle(): String = stringResource(subtitleRes())
@Composable fun AgentKind.localizedTitle(): String = stringResource(titleRes())
@Composable fun AgentKind.localizedSubtitle(): String = stringResource(subtitleRes())
@Composable fun DevStack.localizedLabel(): String = stringResource(labelRes())
@Composable fun DevStack.localizedDescription(): String = stringResource(descriptionRes())
@Composable fun DevStack.localizedInstallsSummary(): String = stringResource(installsSummaryRes())

// Context variants for non-Compose code; resolved in the language chosen inside the app.
fun ProviderKind.localizedTitle(context: Context): String = AppStrings.get(context, titleRes())
fun ProviderKind.localizedSubtitle(context: Context): String = AppStrings.get(context, subtitleRes())
fun AgentKind.localizedTitle(context: Context): String = AppStrings.get(context, titleRes())
fun AgentKind.localizedSubtitle(context: Context): String = AppStrings.get(context, subtitleRes())
fun DevStack.localizedLabel(context: Context): String = AppStrings.get(context, labelRes())
fun DevStack.localizedDescription(context: Context): String = AppStrings.get(context, descriptionRes())
fun DevStack.localizedInstallsSummary(context: Context): String = AppStrings.get(context, installsSummaryRes())

/** Localized relative form of [Project.updatedAtMillis] (mirrors [Project.formattedUpdatedAt]). */
@Composable
fun Project.localizedUpdatedAt(): String {
    val diff = System.currentTimeMillis() - updatedAtMillis
    val minutes = diff / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 60_000L -> stringResource(R.string.md_time_just_now)
        minutes < 60 -> stringResource(R.string.md_time_minutes_ago, minutes)
        hours < 24 -> stringResource(R.string.md_time_hours_ago, hours)
        days == 1L -> stringResource(R.string.md_time_yesterday)
        days < 7 -> stringResource(R.string.md_time_days_ago, days)
        else -> formattedUpdatedAt
    }
}

/** Project descriptions created in English by older versions, shown localized. */
@Composable
fun Project.localizedDescription(): String = when (description) {
    "Starter web project" -> stringResource(R.string.vm_starter_web_project)
    else -> description
}

/** Default chat titles saved in English (model default, older versions) shown localized. */
@Composable
fun ProjectChat.localizedTitle(): String = when (title) {
    "New chat" -> stringResource(R.string.vm_new_chat)
    "Main chat" -> stringResource(R.string.vm_main_chat)
    else -> title
}
