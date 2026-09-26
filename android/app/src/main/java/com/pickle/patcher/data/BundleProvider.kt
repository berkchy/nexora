package com.pickle.patcher.data

import android.content.Context
import com.pickle.patcher.lib.Bundle
import java.io.File

/**
 * Supplies the mod bundle:
 *  1. a bundle.zip downloaded from GitHub Releases (preferred, always fresh)
 *  2. a locally cached bundle
 */
class BundleProvider(private val context: Context) {

    /** Where downloaded release bundles are stored. */
    fun cacheDir(): File = File(context.cacheDir, "patcher")

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
