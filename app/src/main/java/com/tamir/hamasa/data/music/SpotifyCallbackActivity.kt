package com.tamir.hamasa.data.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible activity that receives Spotify's redirect (com.tamir.hamasa://spotify-callback?code=...&state=...),
 * hands it to [SpotifyAuth] and closes right away, returning to the app. Kept separate from MainActivity on purpose.
 */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == SpotifyConfig.REDIRECT_SCHEME && data.host == SpotifyConfig.REDIRECT_HOST) {
            runCatching { SpotifyAuth.handleRedirect(data) }
        }
        // the app's task was not running (or the browser opened us in its own task): bring the app to the front
        if (isTaskRoot) {
            runCatching {
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launch)
                }
            }
        }
        finish()
    }
}
