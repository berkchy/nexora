package com.pickle.patcher.data

import android.content.Context

/**
 * Persists the store session token across app restarts. The token is a JWT
 * signed by the API, so it is safe to keep in SharedPreferences (it is not
 * a password, and rotating NEXTAUTH_SECRET invalidates all of them).
 */
object StoreSession {
    private const val PREFS = "store_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"

    fun save(context: Context, token: String, username: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun username(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USERNAME, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
