package com.jarves.mh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jarves.mh.R
import com.jarves.mh.model.DevStack

/**
 * Resource-backed display strings for [DevStack] entries (roadmap track 2-2, ISSUE-017).
 *
 * The enum itself stays code-only; the UI layer resolves every user-visible
 * string through resources so `values-ar` (and any future locale) renders fully
 * translated. Keep this the only place that maps DevStack entries to resources.
 */
@Composable
fun devStackLabel(stack: DevStack): String = stringResource(
    when (stack) {
        DevStack.WEB -> R.string.devstack_web_label
        DevStack.PYTHON -> R.string.devstack_python_label
        DevStack.ANDROID -> R.string.devstack_android_label
        DevStack.CPP -> R.string.devstack_cpp_label
        DevStack.PHP -> R.string.devstack_php_label
    },
)

@Composable
fun devStackSummary(stack: DevStack): String = stringResource(
    when (stack) {
        DevStack.WEB -> R.string.devstack_web_summary
        DevStack.PYTHON -> R.string.devstack_python_summary
        DevStack.ANDROID -> R.string.devstack_android_summary
        DevStack.CPP -> R.string.devstack_cpp_summary
        DevStack.PHP -> R.string.devstack_php_summary
    },
)

@Composable
fun devStackConcise(stack: DevStack): String = stringResource(
    when (stack) {
        DevStack.WEB -> R.string.devstack_web_concise
        DevStack.PYTHON -> R.string.devstack_python_concise
        DevStack.ANDROID -> R.string.devstack_android_concise
        DevStack.CPP -> R.string.devstack_cpp_concise
        DevStack.PHP -> R.string.devstack_php_concise
    },
)
