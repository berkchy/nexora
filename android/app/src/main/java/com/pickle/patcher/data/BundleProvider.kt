package com.pickle.patcher.data

import android.content.Context
import android.os.Build
import android.os.Environment
import com.pickle.patcher.lib.Bundle
import java.io.File

/**
 * Shared-storage root for everything the patcher downloads.
 *
 * Deliberately not under Android/data/<pkg>: Android deletes that on
 * uninstall, and the client package is uninstalled and reinstalled on every
 * patch. See [BundleProvider.cacheDir] for what that costs.
 */
const val NEXORA_ROOT = "nexora"

/**
 * Supplies the mod bundle:
 *  1. a bundle.zip downloaded from GitHub Releases (preferred, always fresh)
 *  2. a locally cached bundle
 */
class BundleProvider(private val context: Context) {

    /**
     * Where downloaded release bundles are stored.
     *
     * Shared storage under /sdcard/nexora rather than the app cache: context.
     * cacheDir is wiped on uninstall, and the client APK gets uninstalled on
     * every patch cycle, which used to mean re-downloading ~145 MB of
     * libraries. Falls back to the app cache when MANAGE_EXTERNAL_STORAGE has
     * not been granted, since /sdcard is not writable then.
     */
    fun cacheDir(): File {
        val sd = File(Environment.getExternalStorageDirectory(), NEXORA_ROOT)
        val root = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        ) {
            sd
        } else {
            File(context.getExternalFilesDir(null) ?: context.cacheDir, NEXORA_ROOT)
        }
        val dir = File(root, "downloads")
        dir.mkdirs()
        return dir
    }

    /**
     * One cache slot per ABI: a bundle only carries one architecture, and
     * switching the target ABI in the app would otherwise overwrite the
     * previously downloaded one and make an already installed ABI look like
     * it still needs a download.
     */
    fun cachedBundleFile(abi: String): File = File(cacheDir(), "amxx-bundle-$abi.zip")

    /** Pre per-ABI cache; kept so an upgrade can still read the old slot. */
    private fun legacyBundleFile(): File = File(cacheDir(), "amxx-bundle.zip")

    fun hasCachedBundle(abi: String): Boolean = cachedBundleFile(abi).exists()

    fun loadCachedBundle(abi: String): Bundle? {
        loadFrom(cachedBundleFile(abi))?.let { return it }
        val legacy = loadFrom(legacyBundleFile()) ?: return null
        return if (legacy.manifest.abi.isBlank() || legacy.manifest.abi == abi) legacy else null
    }

    fun loadFrom(file: File): Bundle? = try {
        Bundle.fromZip(file.readBytes())
    } catch (t: Throwable) {
        null
    }
}
