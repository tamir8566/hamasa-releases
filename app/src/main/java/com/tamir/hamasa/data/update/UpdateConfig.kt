package com.tamir.hamasa.data.update

/**
 * In-app update constants. Builds are published (by .github/workflows/build.yml) to the PUBLIC repo
 * tamir8566/hamasa-releases, so the release metadata and the APK are read without any token.
 * The unauthenticated GitHub API allows 60 requests per hour per IP: plenty for a check every 10 minutes at most,
 * and UpdateChecker backs off when GitHub answers "rate limit exceeded". No token lives in the source.
 */
object UpdateConfig {
    const val REPO = "tamir8566/hamasa-releases"

    const val ASSET_NAME = "app-debug.apk"

    const val TIMEOUT_MS = 15_000

    /** Automatic checks run at most once per this interval (manual "בדוק עכשיו" ignores it). */
    const val CHECK_INTERVAL_MS = 10L * 60L * 1000L  // every app open, at most once per 10 minutes
}
