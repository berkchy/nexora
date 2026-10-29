package com.pickle.patcher.data

import android.content.Context

object StoreSession {
    private const val PREFS = "store_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_EMAIL_VERIFIED = "email_verified"

    fun save(context: Context, token: String, username: String, role: String? = null, emailVerified: Boolean = false) {
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putBoolean(KEY_EMAIL_VERIFIED, emailVerified)
        if (role != null) ed.putString(KEY_ROLE, role)
        ed.apply()
    }

    fun setEmailVerified(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EMAIL_VERIFIED, value).apply()
    }

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun username(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USERNAME, null)

    fun role(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROLE, null)

    fun emailVerified(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EMAIL_VERIFIED, false)

    fun isAdmin(context: Context): Boolean = role(context) == "admin"

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
