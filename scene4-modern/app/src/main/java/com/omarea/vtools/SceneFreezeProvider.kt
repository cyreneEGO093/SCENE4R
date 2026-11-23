package com.omarea.vtools

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import com.omarea.Scene
import com.omarea.scene_mode.SceneMode
import com.omarea.store.SceneConfigStore

class SceneFreezeProvider : ContentProvider() {
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    private val safePkgRegex = Regex("^[a-zA-Z0-9._]+$")

    private fun resolveDefaultLauncherPackage(ctx: Context): String? {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun isCallerAuthorized(ctx: Context, claimedSource: String): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) {
            return true
        }
        val callerPkg = callingPackage ?: return false
        if (callerPkg != claimedSource) {
            return false
        }
        val pm = ctx.packageManager
        val uidMatch = try {
            val packagesForUid = pm.getPackagesForUid(callingUid) ?: return false
            packagesForUid.contains(callerPkg)
        } catch (_: Exception) {
            false
        }
        if (!uidMatch) {
            return false
        }
        // Only authorize the active system-selected default Home launcher (not arbitrary packages)
        val defaultHome = resolveDefaultLauncherPackage(ctx)
        return !defaultHome.isNullOrEmpty() && callerPkg == defaultHome
    }

    private fun isControlledInstalledPackage(ctx: Context, pkg: String): Boolean {
        if (!safePkgRegex.matches(pkg)) return false
        val controlled = SceneConfigStore(ctx).freezeAppList
        if (!controlled.contains(pkg)) return false
        return try {
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(pkg, flags)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: Scene.context
        if (values != null && values.containsKey("packageName") && values.containsKey("source")) {
            val packageName = values.getAsString("packageName") ?: return null
            val source = values.getAsString("source") ?: return null
            if (!isCallerAuthorized(ctx, source)) {
                return null
            }
            if (!isControlledInstalledPackage(ctx, packageName)) {
                return null
            }
            SceneMode.unfreezeApp(packageName)
            return uri
        }
        return null
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
