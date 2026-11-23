package com.tamir.hamasa.data.music

/**
 * Spotify Web API settings ("מוזיקה שמגיבה"). Tamir creates his own app on developer.spotify.com (see docs/music.md)
 * and pastes its Client ID here. Blank [CLIENT_ID] = the whole music feature is hidden/disabled.
 *
 * Authorization Code with PKCE: a public mobile client, no client secret anywhere in the app.
 * This package is a sanctioned network place (like data/ai), talking only to accounts.spotify.com and api.spotify.com.
 */
object SpotifyConfig {
    /** Client ID from the Spotify developer dashboard. Not a secret (PKCE has no secret). */
    const val CLIENT_ID: String = "f7b07cf67f0f4e0a93fdf58560a3962e"

    /**
     * Must be registered EXACTLY like this under "Redirect URIs" in the dashboard. A custom scheme is still allowed by
     * Spotify (the April 2025 rules ban plain http except loopback IPs, and "localhost"). Matched by the intent-filter of
     * [SpotifyCallbackActivity] in AndroidManifest.xml (scheme + host must stay in sync with these two constants).
     */
    const val REDIRECT_SCHEME = "com.tamir.hamasa"
    const val REDIRECT_HOST = "spotify-callback"
    const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"

    /** Playback control + reading his devices/playback + his private playlists (and collaborative ones). */
    const val SCOPES = "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative"

    const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val API_BASE = "https://api.spotify.com/v1"
    const val TIMEOUT_MS = 15_000

    /** The Spotify app's package, used only to open it ("פתח את ספוטיפיי"). */
    const val SPOTIFY_PACKAGE = "com.spotify.music"

    val enabled: Boolean get() = CLIENT_ID.isNotBlank()
}
