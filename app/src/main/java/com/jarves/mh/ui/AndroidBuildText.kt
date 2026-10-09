package com.jarves.mh.ui

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.runtime.AndroidBuildIssue

/**
 * The build-output parser in AndroidBuildSupport stays English so it remains pure and
 * testable. Its fixed titles, diagnoses and suggestions are translated here at display
 * time; text embedded from compiler output is shown unchanged.
 */
private val androidBuildTextResources: Map<String, Int> = mapOf(
    "Build failed" to R.string.android_build_failed_title,
    "Installation failed" to R.string.rt_apk_install_failed,
    "Source error" to R.string.an_issue_source_error,
    "Open the file and correct the reported line." to R.string.an_issue_open_file,
    "Free device storage, then retry the build." to R.string.an_issue_free_storage,
    "Check the network connection or use a cached dependency version." to R.string.an_issue_check_network,
    "Review duplicate or conflicting manifest declarations." to R.string.an_issue_review_manifest,
    "Review resource names and XML diagnostics in the build output." to R.string.an_issue_review_resources,
    "Close other apps and retry." to R.string.an_issue_close_apps,
    "Open the build details for the complete Gradle output." to R.string.an_issue_open_details,
    "The device does not have enough free storage for this build." to R.string.an_diag_no_space,
    "Gradle ran out of memory. Close other apps and retry." to R.string.an_diag_oom,
    "A project dependency is unavailable. Connect to the internet or use an offline-cached version." to R.string.an_diag_dependency,
    "Android manifest merge failed. Open the build log for the conflicting declaration." to R.string.an_diag_manifest,
    "Android resources could not be compiled. Open the build log for the file and line." to R.string.an_diag_resources,
    "Kotlin or Java compilation failed. Open the build log for source errors." to R.string.an_diag_compile,
    "This project's Gradle and Android plugin versions are incompatible." to R.string.an_diag_gradle_version,
)

private val gradleExitCodePattern = Regex("""^Gradle build failed \(exit code (-?\d+)\)\.$""")

internal fun localizedAndroidBuildText(context: Context, text: String): String {
    androidBuildTextResources[text]?.let { return context.getString(it) }
    gradleExitCodePattern.matchEntire(text)?.let { match ->
        match.groupValues[1].toIntOrNull()?.let { return context.getString(R.string.an_diag_exit_code, it) }
    }
    return text
}

internal fun AndroidBuildIssue.localized(context: Context): AndroidBuildIssue = copy(
    title = localizedAndroidBuildText(context, title),
    detail = localizedAndroidBuildText(context, detail),
    suggestion = suggestion?.let { localizedAndroidBuildText(context, it) },
)
