package com.openminis.app.mcp.oauth

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import org.json.JSONObject

/**
 * [T-android-mcp-token-bridge] Materialize MCP OAuth tokens for the in-guest
 * CLI (`minis-mcp-cli`), which reads
 * `/var/minis/mcp-servers/oauth/<server>.json` (guest-visible path for
 * `<filesDir>/minis-global/mcp-servers/oauth/`) and attaches
 * `Authorization: Bearer <access_token>`, refreshing via the stored grant.
 *
 * Without this bridge an OAuth server authorized in the app still fails every
 * guest-side call with AUTH_REQUIRED — the tokens never left the app. The
 * format mirrors the Python contract in transport/http.py: `expires_at` in
 * epoch SECONDS (the CLI refreshes 60s early), `resource` included when the
 * server URL canonicalizes, `client_secret` optional. Written atomically
 * (tmp+rename) because the daemon may read it between calls; 0600 since it
 * carries bearer material.
 */
object MCPTokenBridge {

    private const val TAG = "MCPTokenBridge"

    fun oauthDir(context: Context): File =
        File(context.filesDir, "minis-global/mcp-servers/oauth")

    /**
     * Write/refresh the bridge for [server]. [expiresAtMs] uses epoch millis
     * (Android side); the bridge stores epoch seconds for the CLI. Best-effort:
     * a failed write logs and returns — the next authorize/refresh retries.
     */
    fun write(
        context: Context,
        server: String,
        tokens: MCPOAuthStore.StoredTokens,
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        resource: String?,
    ) {
        if (server.isBlank() || server.contains('/') || server.contains('\\')) {
            AppLogger.warning(TAG, "refusing bridge write for odd server id '$server'")
            return
        }
        val payload = JSONObject().apply {
            put("access_token", tokens.accessToken)
            if (!tokens.refreshToken.isNullOrBlank()) put("refresh_token", tokens.refreshToken)
            if (tokens.expiresAtMs > 0) put("expires_at", tokens.expiresAtMs / 1000)
            put("token_endpoint", tokenEndpoint)
            put("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
            if (!resource.isNullOrBlank()) put("resource", resource)
        }
        try {
            val dir = oauthDir(context)
            dir.mkdirs()
            val target = File(dir, "$server.json")
            val tmp = File(dir, "$server.json.tmp")
            tmp.writeText(payload.toString())
            if (!tmp.renameTo(target)) {
                // Filesystems that refuse rename over an existing target.
                target.writeText(payload.toString())
                tmp.delete()
            }
            target.setReadable(false, false)
            target.setWritable(false, false)
            target.setReadable(true, true)
            AppLogger.info(TAG, "bridge written for '$server' (hasRefresh=${!tokens.refreshToken.isNullOrBlank()})")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "bridge write failed for '$server': ${e.message}")
        }
    }

    /** Remove the bridge (sign-out / server deletion) — best-effort. */
    fun remove(context: Context, server: String) {
        if (server.isBlank() || server.contains('/') || server.contains('\\')) return
        try {
            val f = File(oauthDir(context), "$server.json")
            if (f.exists() && !f.delete()) {
                AppLogger.warning(TAG, "bridge remove failed for '$server'")
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "bridge remove failed for '$server': ${e.message}")
        }
    }
}
