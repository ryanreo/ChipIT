package com.chipit.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chipit_settings", Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "chipit_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("chipit_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    var hfToken: String?
        get() = encryptedPrefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            encryptedPrefs.edit().putString(KEY_HF_TOKEN, value?.trim()).apply()
        }

    var agentMaxSteps: Int
        get() = prefs.getInt(KEY_AGENT_MAX_STEPS, 12).coerceIn(1, 25)
        set(value) {
            prefs.edit().putInt(KEY_AGENT_MAX_STEPS, value.coerceIn(1, 25)).apply()
        }

    var allowedAgentPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_PACKAGES, DEFAULT_ALLOWED_PACKAGES)
            ?: DEFAULT_ALLOWED_PACKAGES
        set(value) {
            prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, value).apply()
        }

    fun isPackageAllowed(packageName: String): Boolean {
        if (BLOCKED_SENSITIVE_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) {
            return false
        }
        return packageName in allowedAgentPackages
    }

    fun togglePackageAllowed(packageName: String, allowed: Boolean) {
        val updated = allowedAgentPackages.toMutableSet()
        if (allowed) updated.add(packageName) else updated.remove(packageName)
        allowedAgentPackages = updated
    }

    companion object {
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_AGENT_MAX_STEPS = "agent_max_steps"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"

        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.maps",
            "com.google.android.deskclock",
            "com.google.android.calendar",
            "com.android.settings"
        )

        val BLOCKED_SENSITIVE_PACKAGES = setOf(
            "authenticator",
            "1password",
            "bitwarden",
            "lastpass",
            "keepass",
            "bank",
            "paypal",
            "venmo",
            "wallet"
        )
    }
}
