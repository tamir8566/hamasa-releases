package com.tamir.hamasa.data.cloud

/**
 * Supabase project constants. The anon key is public by design (it is the same key a browser client would
 * ship with); each user's rows are separated by the recovery code header (docs/multiuser.sql).
 */
object CloudConfig {
    const val SUPABASE_URL = "https://oacijyezkwmysertkmvl.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im9hY2lqeWV6a3dteXNlcnRrbXZsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMzNTIzODIsImV4cCI6MjEwODkyODM4Mn0.Ww_eI5KQ34B-Mo_RXL5heY2cpdMVXANVnRyTrL3Lq0g"

    /** Rows per upsert batch and per select page (PostgREST default max-rows is 1000). */
    const val BATCH = 200
    const val PAGE = 1000
    const val TIMEOUT_MS = 15_000

    /** Header that identifies the user's rows (recovery code, see Account.kt and docs/multiuser.sql). */
    const val PROFILE_HEADER = "x-profile-code"

    /** Supabase Edge Function that holds the AI keys (supabase/functions/ai/index.ts, docs/ai.md). */
    const val AI_FUNCTION_URL = "$SUPABASE_URL/functions/v1/ai"
}
