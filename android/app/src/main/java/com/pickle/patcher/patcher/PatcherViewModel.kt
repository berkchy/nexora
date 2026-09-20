package com.pickle.patcher.patcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickle.patcher.CrashLog
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.IncrementalUpdateManager
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder

data class SourceInfo(
    val name: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val abis: List<String> = emptyList(),
)

sealed interface BundleState {
    data object None : BundleState
    data object Loaded : BundleState
    data class Ready(val bundleName: String, val entries: Int, val version: String) : BundleState
    data class Downloading(
        val percent: Float,
        val tagName: String = "",
        val currentFile: String = "",
        val fileIndex: Int = 0,
        val fileTotal: Int = 0,
    ) : BundleState
    data class DownloadError(val message: String) : BundleState
}

sealed interface AddonsState {
    data object None : AddonsState
    data class Downloading(val percent: Float, val step: String) : AddonsState
    data class Done(val message: String) : AddonsState
    data class Error(val message: String) : AddonsState
}

sealed interface PatchUiState {
    data object Idle : PatchUiState
    data class Running(val step: ApkPatcher.Step, val progress: Float) : PatchUiState
    data class Done(val report: ApkPatcher.PatchReport) : PatchUiState
    data class Failed(val message: String) : PatchUiState
}

data class SmaSource(val path: String, val name: String, val hasInclude: Boolean) {
    val scriptDir: String get() = File(path).parentFile?.absolutePath.orEmpty()
}

data class LibInfo(
    val name: String,
    val localSize: Long,
    val releaseSize: Long,
    val upToDate: Boolean,
    val downloading: Boolean = false,
    val downloadProgress: Float = -1f,
)

sealed interface CompileState {
    data object Idle : CompileState
    data class Compiling(val source: String) : CompileState
    data class Done(val log: String) : CompileState
    data class Failed(val message: String) : CompileState
}

class PatcherViewModel(app: Application) : AndroidViewModel(app) {

    private val bundleProvider = BundleProvider(app)

    private val _source = MutableStateFlow<SourceInfo?>(null)
    val source: StateFlow<SourceInfo?> = _source.asStateFlow()

    private val _receivedSource: MutableStateFlow<File?> = MutableStateFlow(null)

    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()

    /** ABI the user chose for the patch. Defaults to arm64-v8a. */
    private val _abi = MutableStateFlow(SUPPORTED_ABIS.first())
    val abi: StateFlow<String> = _abi.asStateFlow()

    val supportedAbis: List<String> = SUPPORTED_ABIS

    val sourceAbis: List<String> get() = _source.value?.abis ?: emptyList()
    val loadedBundleAbi: String? get() = loadedBundle?.manifest?.abi?.ifBlank { null }

    fun setAbi(abi: String) {
        if (abi !in SUPPORTED_ABIS) return
        _abi.value = abi
        val b = loadedBundle
        if (b != null && b.manifest.abi.isNotBlank() && b.manifest.abi != abi) {
            loadedBundle = null
            _bundle.value = BundleState.None
        }
        scanLibs(autoLoad = true)
    }

    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()

    private val _libs = MutableStateFlow<List<LibInfo>>(emptyList())
    val libs: StateFlow<List<LibInfo>> = _libs.asStateFlow()

    /** True while a manual remote lib-status check is in flight. */
    private val _libsChecking = MutableStateFlow(false)
    val libsChecking: StateFlow<Boolean> = _libsChecking.asStateFlow()

    /** Manual "Check updates" for libs: compares local .so files with the release. */
    fun refreshLibStatus() {
        scanLibs(checkRemote = true)
    }

    private val _scripts = MutableStateFlow<List<SmaSource>>(emptyList())
    val scripts: StateFlow<List<SmaSource>> = _scripts.asStateFlow()

    /** Root folder the user picked via SAF; null until a folder is selected. */
    private val _scriptRoot = MutableStateFlow<String?>(null)
    val scriptRoot: StateFlow<String?> = _scriptRoot.asStateFlow()

    /** .amxx output folder picked by the user; null = "<scripts>/compiled". */
    private val _outputRoot = MutableStateFlow<String?>(null)
    val outputRoot: StateFlow<String?> = _outputRoot.asStateFlow()

    private val compilerPrefs by lazy {
        getApplication<Application>().getSharedPreferences("compiler_prefs", Context.MODE_PRIVATE)
    }

    // Declared before init: useCachedBundle() runs synchronously in init and
    // reads these (Kotlin initializes properties in textual order).
    private var loadedBundle: Bundle? = null
    private var lastReport: ApkPatcher.PatchReport? = null

    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle()

    val repo = "berkchy/nexora"

    private val workDir = File(app.getExternalFilesDir(null) ?: app.cacheDir, "patcher")
    private val libsDir = File(app.getExternalFilesDir(null) ?: app.cacheDir, "libs")

    init {
        val savedScripts = compilerPrefs.getString("script_root", null)
        if (!savedScripts.isNullOrEmpty() && File(savedScripts).isDirectory) {
            _scriptRoot.value = savedScripts
            refreshScripts()
        }
        val savedOutput = compilerPrefs.getString("output_root", null)
        if (!savedOutput.isNullOrEmpty() && File(savedOutput).isDirectory) {
            _outputRoot.value = savedOutput
        }
        // Manual-update mode: on launch only show what is already on disk.
        // No network calls here — the user triggers "Download" and
        // "Update check" explicitly.
        scanLibs()
        useCachedBundle()
    }

    private val _compile = MutableStateFlow<CompileState>(CompileState.Idle)
    val compile: StateFlow<CompileState> = _compile.asStateFlow()

    private val _releaseNote = MutableStateFlow<String?>(null)
    val releaseNote: StateFlow<String?> = _releaseNote.asStateFlow()

    private val _patch = MutableStateFlow<PatchUiState>(PatchUiState.Idle)
    val patch: StateFlow<PatchUiState> = _patch.asStateFlow()

    data class CrashLogEntry(
        val fileName: String,
        val modified: String,
        val sizeBytes: Long,
        val content: String,
    )

    private val _crashLog = MutableStateFlow<CrashLogEntry?>(null)
    val crashLog: StateFlow<CrashLogEntry?> = _crashLog.asStateFlow()

    fun refreshCrashLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = CrashLog.latestFile(getApplication())
            _crashLog.value = file?.let {
                CrashLogEntry(
                    fileName = it.name,
                    modified = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(it.lastModified()),
                    sizeBytes = it.length(),
                    content = it.readText().take(1 shl 20),
                )
            }
        }
    }

    /**
     * Loads the bundled signing key. Prefers the PEM pair (PKCS#8 key + X.509 cert)
     * because [SigningKeystore.loadPem] uses only [KeyFactory]/[CertificateFactory],
     * which are always available on Android; falls back to the PKCS12 container.
     */
    private fun loadSigningKeystore(): SigningKeystore {
        val assets = getApplication<Application>().assets
        val keyPem = runCatching {
            assets.open("keystore/debug_key.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        val certPem = runCatching {
            assets.open("keystore/debug_cert.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        return if (keyPem != null && certPem != null) {
            SigningKeystore.loadPem(keyPem, certPem)
        } else {
            val p12 = assets.open("keystore/debug.p12").use { it.readBytes() }
            SigningKeystore.loadBytes(p12)
        }
    }

    /** Copies a SAF-picked source APK into app storage. */
    fun pickSource(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = queryName(uri) ?: "source.apk"
                val out = File(workDir, name)
                out.parentFile?.mkdirs()
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                val info = ZipAnalyzer.analyze(out)
                val supported = info.abis.filter { it in SUPPORTED_ABIS }
                if (supported.isEmpty()) {
                    _patch.value = PatchUiState.Failed(
                        "This APK has no supported native ABIs (found: " +
                            "${info.abis.ifEmpty { listOf("none") }.joinToString(", ")}). " +
                            "Supported: ${SUPPORTED_ABIS.joinToString(", ")}."
                    )
                    _receivedSource.value = null
                    return@launch
                }
                if (_abi.value !in supported) setAbi(supported.first())
                _source.value = SourceInfo(name, out.length(), info.entryCount, supported)
                _receivedSource.value = out
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed("Could not copy source APK: ${t.message}")
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun useCachedBundle() {
        val abi = _abi.value
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi)
        if (files.isNotEmpty()) {
            val b = buildBundleFromFileMap(files, abi)
            loadedBundle = b
            _bundle.value = BundleState.Loaded
            scanLibs()
            return
        }
        // Fallback: try legacy cached bundle zip
        val b = bundleProvider.loadCachedBundle()
        if (b != null) {
            loadedBundle = b
            _bundle.value = BundleState.Ready("Cached", b.manifest.entries.size, b.manifest.version)
            scanLibs()
        }
    }

    fun useLoadedBundle() {
        val b = loadedBundle ?: return
        _bundle.value = BundleState.Ready("Loaded", b.manifest.entries.size, b.manifest.version)
    }

    /**
     * Lists local libs instantly (offline). When [checkRemote] is true it also
     * fetches the release manifest and marks outdated files — that network
     * check only runs from explicit user actions (Update check / Check updates).
     */
    fun scanLibs(autoLoad: Boolean = false, checkRemote: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val abi = _abi.value
            val targetDir = File(libsDir, abi)
            val localFiles = if (targetDir.isDirectory) {
                targetDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") && !it.name.startsWith("libmenu_") }
                    ?.map { it.name to it }
                    ?.toMap()
                    ?: emptyMap()
            } else emptyMap()

            // Read the lib list from the local libs/<abi>/ directory first so the
            // UI has something instantly, even offline. Manifest refines below.
            _libs.value = localFiles.keys.sorted().map { name ->
                val f = localFiles[name] ?: return@map null
                LibInfo(
                    name = name,
                    localSize = f.length(),
                    releaseSize = f.length(),
                    upToDate = true,
                )
            }.filterNotNull()

            // API-free: newest tag via redirect + per-ABI manifest asset list.
            // Only on explicit user request (checkRemote) — never on launch.
            var tagName = "live"
            var assets: List<IncrementalUpdateManager.AssetInfo> = emptyList()
            if (checkRemote) {
                _libsChecking.value = true
                try {
                    tagName = ReleaseRepository.latestTagRedirect(repo) ?: tagName
                    assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abi)
                } catch (_: Throwable) { } finally {
                    _libsChecking.value = false
                }
            }

            if (assets.isNotEmpty()) {
                val releaseMap = assets.associateBy { it.cleanName }
                val allNames = (localFiles.keys + releaseMap.keys).distinct().sorted()
                val rows = allNames.map { name ->
                    val f = localFiles[name]
                    val asset = releaseMap[name]
                    LibInfo(
                        name = name,
                        localSize = f?.length() ?: 0L,
                        releaseSize = asset?.size ?: (f?.length() ?: 0L),
                        upToDate = if (asset != null) {
                            IncrementalUpdateManager.isUpToDate(File(targetDir, name), asset)
                        } else true,
                    )
                }
                _libs.value = rows
            }
            val outdated = _libs.value.filter { !it.upToDate && it.releaseSize > 0 }
            if (autoLoad && outdated.isEmpty() && assets.isNotEmpty()) {
                // Everything on disk is up to date — auto-load the bundle so the
                // user can patch straight away. (Guard on a fresh release list,
                // otherwise a failed check could auto-load stale libs.)
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, abi)
                    markBundleTagKnown(tagName)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                }
            }
        }
    }

    fun refreshSingleLib(libName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadLib(libName)
        }
    }

    private suspend fun downloadLib(libName: String) {
        try {
            val tagName = ReleaseRepository.latestTagRedirect(repo) ?: return
            val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, _abi.value)
            val asset = assets.find { it.cleanName == libName } ?: return
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = true, downloadProgress = 0f) else it
            }
            IncrementalUpdateManager.downloadSingle(asset, libsDir, _abi.value) { p ->
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = true, downloadProgress = p) else it
                }
            }
            val fileOnDisk = File(File(libsDir, _abi.value), libName)
            val newSize = if (fileOnDisk.exists()) fileOnDisk.length() else 0L
            val upToDate = IncrementalUpdateManager.isUpToDate(fileOnDisk, asset)
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(localSize = newSize, upToDate = upToDate) else it
            }
        } catch (t: Throwable) {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = false) else it
            }
        }
    }

    fun fetchAndDownloadBundle() {
        if (_bundle.value is BundleState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _bundle.value = BundleState.Downloading(0.04f)
            try {
                val tagName = ReleaseRepository.latestTagRedirect(repo)
                    ?: throw IOException("Could not reach GitHub releases")
                _releaseNote.value = tagName

                val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, _abi.value)
                if (assets.isEmpty()) throw IOException("No .so assets found for ABI ${_abi.value}")
                _bundle.update { BundleState.Downloading(0.1f, tagName) }

                val diff = IncrementalUpdateManager.diff(assets, libsDir, _abi.value)

                if (diff.toDownload.isEmpty()) {
                    val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                    if (files.isEmpty()) throw IOException("No .so files found in libs/")
                    val b = buildBundleFromFileMap(files, _abi.value)
                    markBundleTagKnown(tagName)
                    _bundle.value = BundleState.Loaded
                    loadedBundle = b
                    scanLibs()
                    return@launch
                }

                // Download changed files with per-lib progress
                IncrementalUpdateManager.downloadChanged(
                    diff.toDownload, libsDir, _abi.value,
                    onFileStart = { index, asset ->
                        markLibDownloading(asset.cleanName, 0f)
                        _bundle.update {
                            BundleState.Downloading(
                                percent = 0.15f + (0.75f * index.toFloat() / diff.toDownload.size).coerceAtMost(0.75f),
                                tagName = tagName,
                                currentFile = asset.cleanName,
                                fileIndex = index,
                                fileTotal = diff.toDownload.size,
                            )
                        }
                    },
                    onFileProgress = { index, asset, fileProgress ->
                        markLibDownloading(asset.cleanName, fileProgress)
                        val base = 0.15f + (0.75f * index.toFloat() / diff.toDownload.size).coerceAtMost(0.75f)
                        val perFileWeight = 0.75f / diff.toDownload.size
                        _bundle.update {
                            BundleState.Downloading(
                                percent = base + perFileWeight * fileProgress,
                                tagName = tagName,
                                currentFile = asset.cleanName,
                                fileIndex = index,
                                fileTotal = diff.toDownload.size,
                            )
                        }
                    },
                )

                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                if (files.isEmpty()) throw IOException("No .so files found after download")
                val b = buildBundleFromFileMap(files, _abi.value)
                markBundleTagKnown(tagName)
                _bundle.value = BundleState.Ready(
                    "Updated ${diff.toDownload.size} files", b.manifest.entries.size, b.manifest.version
                )
                loadedBundle = b
                scanLibs()
            } catch (t: Throwable) {
                // Offline fallback: if there are already libs on disk, load them.
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, _abi.value)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                    scanLibs()
                } else {
                    _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                }
            }
        }
    }

    private fun markLibDownloading(name: String, progress: Float) {
        _libs.value = _libs.value.map {
            if (it.name == name) it.copy(downloading = true, downloadProgress = progress) else it
        }
    }

    /** Builds a disk-backed Bundle: lib bytes stay on disk, streamed one file
     * at a time during patching (low-RAM safe). */
    private fun buildBundleFromFileMap(files: Map<String, File>, abi: String): Bundle {
        val suffix = if (abi == "arm64-v8a") "arm64" else "armv7l"
        val modSuffix = if (abi == "arm64-v8a") "amd64" else "arm"

        val entries = files.keys.map { targetPath ->
            val name = targetPath.substringAfterLast("/")
            val desc = when {
                name == "libamxmodx.so" -> "AMX Mod X core"
                name == "libmetamod.so" || name.startsWith("libyapb_android_") -> "Metamod HL1"
                name == "libyapb.so" -> "YaPB bot plugin"
                name == "libclient_android_$suffix.so" -> "CS16Client client DLL"
                name == "libmenu_android_$suffix.so" -> "CS16Client main menu"
                name == "libcs_android_arm64.so" -> "ReGameDLL game DLL (first-spawn fix)"
                name.contains("_amxx_") -> "AMXX module"
                else -> "Bundle file"
            }
            com.pickle.patcher.lib.BundleManifest.BundleEntry(
                source = targetPath,
                target = targetPath,
                method = com.pickle.patcher.lib.BundleManifest.Compression.STORED,
                required = true,
                description = desc,
            )
        }

        val manifest = com.pickle.patcher.lib.BundleManifest(
            version = "live",
            game = "cs16client",
            abi = abi,
            entries = entries,
        )
        return Bundle(manifest, emptyMap(), files)
    }

    /** A group of bundle entries the user can toggle individually before patching. */
    data class PatchComponent(
        val key: String,
        val label: String,
        val description: String,
        val entryTargets: List<String>,
    ) {
        override fun equals(other: Any?): Boolean = other is PatchComponent && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }

    /** Categorizes a bundle target path into a selectable component key. */
    private fun componentKeyFor(target: String): String {
        val name = target.substringAfterLast("/")
        return when {
            name == "libamxmodx.so" -> "amxx"
            name == "libmetamod.so" || name.startsWith("libyapb_android_") -> "metamod"
            name == "libyapb.so" -> "yapb"
            name.startsWith("libclient_android_") -> "client"
            name.startsWith("libmenu_android_") -> "menu"
            name.startsWith("libcs_android_") -> "game"
            name.contains("_amxx_") -> "modules"
            else -> "other"
        }
    }

    /** Components included in the current patch, derived from the loaded bundle. */
    fun patchComponents(): List<PatchComponent> {
        val b = loadedBundle ?: return emptyList()
        val order = listOf("amxx", "metamod", "yapb", "client", "menu", "modules", "game", "other")
        return b.manifest.entries
            .groupBy { componentKeyFor(it.target) }
            .mapNotNull { (key, entries) ->
                val label = when (key) {
                    "amxx" -> "AMX Mod X core"
                    "metamod" -> "Metamod HL1"
                    "yapb" -> "YaPB bot plugin"
                    "client" -> "CS16Client client DLL"
                    "menu" -> "CS16Client main menu"
                    "game" -> "ReGameDLL game DLL"
                    "modules" -> "AMXX modules"
                    else -> "Bundle files"
                }
                PatchComponent(
                    key = key,
                    label = label,
                    description = entries.joinToString(", ") {
                        it.target.substringAfterLast("/")
                    },
                    entryTargets = entries.map { it.target },
                )
            }
            .sortedBy { order.indexOf(it.key) }
    }

    /**
     * Attempt incremental update: fetch manifest.json, compare hashes, download only changed .so files.
     * Returns true if incremental update succeeded, false if we should fall back to legacy bundle zip.
     */
    /**
     * Downloads only the addons package (plugins + modules + configs) from the
     * latest release and extracts it into the device's cstrike folder. The full
     * mod bundle itself is fetched during patching, not here.
     */
    fun fetchAndInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            _addons.value = AddonsState.Downloading(0f, "Resolving latest release…")
            try {
                val tag = ReleaseRepository.latestTagRedirect(repo)
                    ?: throw IOException("Could not reach GitHub releases")
                val zip = File(bundleProvider.cacheDir(), "amxx-addons.zip")
                _addons.value = AddonsState.Downloading(0f, "Downloading addons…")
                ReleaseRepository.downloadUrl(
                    ReleaseRepository.assetUrl(repo, tag, "amxx-addons.zip"),
                    zip,
                ) { done, total ->
                    _addons.value = AddonsState.Downloading(
                        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                        "Downloading addons…"
                    )
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into ${_installPath.value.substringAfterLast("/")}…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                patchMetamodConfig(target, _abi.value)
                _addons.value = AddonsState.Done(
                    "Installed ${count} addons files into ${target.path}"
                )
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
            }
        }
    }

    /**
     * Legacy entry point (Patch tab / Addons Manager). Addons no longer ship
     * inside the mod bundle — they are a separate amxx-addons.zip release
     * asset — so installing always fetches the latest addons package.
     */
    fun installAddonsFromBundle() {
        fetchAndInstallAddons()
    }

    private fun unzipInto(zip: File, target: File): Int {
        var count = 0
        java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                if (name.isBlank()) return@forEach
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Unsafe path in addons zip: $name")
                }
                // User-edited config files are extracted only when missing — a
                // fresh download must never clobber the user's own settings.
                if (PROTECTED_ADDON_CONFIGS.contains(name) && out.exists()) return@forEach
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    /**
     * Ensures addons/metamod/config.ini has the correct [gamedll] line for the
     * selected ABI.  The stock config shipped in amxx-addons.zip hard-codes the
     * arm64 name; on arm32 this causes a FATAL ERROR.  If the file already
     * exists we patch in-place; otherwise we create it from scratch.
     */
    private fun patchMetamodConfig(gameDir: File, abi: String) {
        val suffix = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            else -> return
        }
        val configFile = File(gameDir, "addons/metamod/config.ini")
        val gamedllLine = "gamedll dlls/libcs_android_${suffix}.so"
        if (configFile.exists()) {
            val lines = configFile.readLines().toMutableList()
            val idx = lines.indexOfFirst { it.startsWith("gamedll") }
            if (idx >= 0) lines[idx] = gamedllLine else lines.add(0, gamedllLine)
            configFile.writeText(lines.joinToString("\n"))
        } else {
            configFile.parentFile?.mkdirs()
            configFile.writeText("$gamedllLine\n")
        }
    }

    fun startPatch(selectedComponentKeys: Set<String>? = null) {
        val src = _receivedSource.value ?: return
        val b = loadedBundle ?: return
        val selAbi = _abi.value
        val bundleAbi = b.manifest.abi.ifBlank { selAbi }
        if (bundleAbi != selAbi) {
            _patch.value = PatchUiState.Failed(
                "The loaded bundle is built for $bundleAbi but you selected $selAbi. " +
                    "Download the bundle for $selAbi first."
            )
            return
        }
        if (selAbi !in (_source.value?.abis ?: emptyList())) {
            _patch.value = PatchUiState.Failed(
                "The source APK does not contain a $selAbi library directory. " +
                    "Re-pick the APK or select another ABI."
            )
            return
        }
        // Filter the bundle down to the user-selected components. A null selection
        // means "everything" (keeps the pre-dialog behaviour).
        val effectiveBundle = if (selectedComponentKeys == null) {
            b
        } else {
            val kept = b.manifest.entries.filter { componentKeyFor(it.target) in selectedComponentKeys }
            b.withEntries(kept)
        }
        if (effectiveBundle.manifest.entries.isEmpty()) {
            _patch.value = PatchUiState.Failed("No patch components selected.")
            return
        }
        val keystore = runCatching { loadSigningKeystore() }
            .getOrElse {
                _patch.value = PatchUiState.Failed("Signing key could not be loaded: ${it.message}")
                return
            }
        val out = File(workDir, "patched.apk")

        viewModelScope.launch(Dispatchers.IO) {
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                val report = ApkPatcher.patch(
                    ApkPatcher.PatchRequest(src, out, effectiveBundle, keystore, keepAbi = selAbi),
                    onStep = { step, p ->
                        _patch.value = PatchUiState.Running(step, p)
                    },
                )
                lastReport = report
                _patch.value = PatchUiState.Done(report)
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed(t.message ?: "Unknown error")
            }
        }
    }

    fun outputApk(): File? = lastReport?.let { File(workDir, "patched.apk") }

    fun installIntent(): Intent? {
        val out = outputApk() ?: return null
        return installIntentFor(out)
    }

    fun installIntentFor(apk: File): Intent? {
        if (!apk.exists()) return null
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun reset() {
        _patch.value = PatchUiState.Idle
        lastReport = null
    }

    // ------------------------------------------------------------------ updater
    // Checks the crashview releases for a newer patcher APK and downloads it
    // with progress (downloaded/total/speed), then hands it to the package
    // installer automatically when done.

    sealed interface AppUpdate {
        data object Idle : AppUpdate
        data object Checking : AppUpdate
        data class Available(
            val tag: String,
            val notes: String,
            val commits: List<String>,
            val size: Long,
            val url: String,
        ) : AppUpdate
        data class Downloading(
            val tag: String,
            val downloaded: Long,
            val total: Long,
            val bytesPerSec: Long,
        ) : AppUpdate
        data class Downloaded(val tag: String, val file: File) : AppUpdate
        data class UpToDate(val tag: String) : AppUpdate
        data class Failed(val message: String) : AppUpdate
    }

    private val _appUpdate = MutableStateFlow<AppUpdate>(AppUpdate.Idle)
    val appUpdate: StateFlow<AppUpdate> = _appUpdate.asStateFlow()

    private val updatePrefs by lazy {
        getApplication<Application>().getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Manual update check only — called from the overflow menu's
     * "Update check". Never runs automatically, no polling, no notifications.
     *
     * Single rolling "Continuous" release: the tag never changes, so the check
     * compares the version string baked into this APK (version.txt asset) plus
     * a byte-size check of the APK. No api.github.com calls.
     */
    fun checkAppUpdate() {
        val cur = _appUpdate.value
        if (cur is AppUpdate.Checking || cur is AppUpdate.Downloading || cur is AppUpdate.Downloaded) return
        viewModelScope.launch(Dispatchers.IO) {
            _appUpdate.value = AppUpdate.Checking
            try {
                val tag = CONTINUOUS_TAG
                val remoteVersion = ReleaseRepository.fetchText(
                    ReleaseRepository.assetUrl(APP_RELEASE_REPO, tag, VERSION_FILE)
                ) ?: throw IOException("Could not reach GitHub releases")
                val apkUrl = ReleaseRepository.assetUrl(APP_RELEASE_REPO, tag, CONTINUOUS_APK)
                val apkSize = ReleaseRepository.probeSize(apkUrl)

                val app = getApplication<Application>()
                val ours = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Throwable) {
                    null
                }
                val localSize = try {
                    File(app.applicationInfo.sourceDir).length().takeIf { it > 0 }
                } catch (_: Throwable) {
                    null
                }
                val versionDiffers = ours == null || remoteVersion != ours
                val sizeDiffers = apkSize != null && apkSize > 0 &&
                    localSize != null && apkSize != localSize
                val notes = buildString {
                    if (ours != null) append("Installed: $ours")
                    if (localSize != null) append(" (${mb(localSize)})")
                    append("  →  $remoteVersion")
                    if (apkSize != null && apkSize > 0) append(" (${mb(apkSize)})")
                }

                if (versionDiffers || sizeDiffers) {
                    _appUpdate.value = AppUpdate.Available(tag, notes, emptyList(), apkSize ?: 0L, apkUrl)
                } else {
                    _appUpdate.value = AppUpdate.UpToDate(remoteVersion)
                }
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(t.message ?: "Update check failed")
            }
        }
    }

    fun downloadAppUpdate() {
        val cur = _appUpdate.value as? AppUpdate.Available ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(workDir, "update.apk")
                if (dest.exists()) dest.delete()
                val t0 = SystemClock.elapsedRealtime()
                ReleaseRepository.downloadUrl(cur.url, dest, cur.size) { done, total ->
                    val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    _appUpdate.value = AppUpdate.Downloading(cur.tag, done, total, done * 1000L / dt)
                }
                                updatePrefs.edit().putString("known_tag", cur.tag).apply()
                _appUpdate.value = AppUpdate.Downloaded(cur.tag, dest)
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(t.message ?: "Download failed")
            }
        }
    }

    fun dismissUpdate() {
        _appUpdate.value = AppUpdate.Idle
    }

    fun consumeDownloaded() {
        _appUpdate.value = AppUpdate.Idle
    }

    fun markBundleTagKnown(tag: String) {
        updatePrefs.edit().putString("known_bundle_tag", tag).apply()
    }

    private fun mb(bytes: Long): String =
        "${(bytes / 1048576.0).let { "%.1f".format(it) }} MB"

    // ------------------------------------------------------- plugins editor
    // Reads plugins-*.ini files and toggles lines with ';' (AMXX skips those).
    data class PluginLine(val text: String, val enabled: Boolean, val editable: Boolean, val isPlugin: Boolean)
    data class PluginIniFile(val name: String, val file: File, val lines: List<PluginLine>)

    private val _pluginInis = MutableStateFlow<List<PluginIniFile>>(emptyList())
    val pluginInis: StateFlow<List<PluginIniFile>> = _pluginInis.asStateFlow()

    fun loadPluginInis() {
        viewModelScope.launch(Dispatchers.IO) {
            val configs = File(File(_installPath.value), "addons/amxmodx/configs")
            val files = configs.listFiles { f ->
                f.isFile && f.name.startsWith("plugins") && f.name.endsWith(".ini")
            }?.sortedBy { it.name } ?: emptyList()
            _pluginInis.value = files.map { f ->
                PluginIniFile(
                    name = f.name,
                    file = f,
                    lines = f.readLines().map { line ->
                        val t = line.trim()
                        val stripped = t.removePrefix(";").trim()
                        val first = stripped.split(Regex("\\s+")).firstOrNull().orEmpty()
                        if (first.endsWith(".amxx", ignoreCase = true)) {
                            // Real plugin line (enabled) or disabled one (';').
                            PluginLine(line, enabled = !t.startsWith(";"), editable = true, isPlugin = true)
                        } else {
                            // Blank line, comment or header — not a plugin, never listed.
                            PluginLine(line, enabled = true, editable = false, isPlugin = false)
                        }
                    }
                )
            }
        }
    }

    fun togglePluginLine(iniName: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _pluginInis.value
            val ini = current.firstOrNull { it.name == iniName } ?: return@launch
            if (index !in ini.lines.indices) return@launch
            val line = ini.lines[index]
            if (!line.editable) return@launch
            val updated = ini.lines.toMutableList()
            updated[index] = if (line.enabled) {
                line.copy(text = ";" + line.text, enabled = false)
            } else {
                line.copy(text = line.text.replaceFirst(Regex("^\\s*;"), ""), enabled = true)
            }
            try {
                ini.file.writeText(updated.joinToString("\n") { it.text })
            } catch (_: Throwable) {
                return@launch
            }
            _pluginInis.value = current.map {
                if (it.name == iniName) it.copy(lines = updated) else it
            }
        }
    }

    // ------------------------------------------------------------ share logs
    // Collects versions + log tails into one text for sharing (bug reports).

    suspend fun buildLogShareText(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val pm = getApplication<Application>().packageManager
        val pkg = getApplication<Application>().packageName
        val ver = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).versionName
            }
        } catch (_: Throwable) {
            "unknown"
        }
        sb.append("Nexora ").append(ver).append("\n")
        sb.append("Game dir: ").append(_installPath.value).append("\n\n")
        fun tail(f: File, max: Int = 60): List<String> = try {
            if (!f.exists()) return listOf("(missing: ${f.path})")
            val lines = f.readLines()
            lines.takeLast(max)
        } catch (t: Throwable) {
            listOf("(unreadable: ${t.message})")
        }
        val base = File(_installPath.value)
        sb.append("=== crash.log ===\n")
        tail(File(base, "crash.log"), 40).forEach { sb.append(it).append("\n") }
        sb.append("\n=== engine.log (tail) ===\n")
        tail(File(File(base, "").parent ?: "", "engine.log"), 40).forEach { sb.append(it).append("\n") }
        val logDir = File(base, "addons/amxmodx/logs")
        val latest = logDir.listFiles { f -> f.isFile && f.name.startsWith("L") }
            ?.maxByOrNull { it.lastModified() }
        if (latest != null) {
            sb.append("\n=== ${latest.name} (tail) ===\n")
            tail(latest, 60).forEach { sb.append(it).append("\n") }
        }
        sb.toString()
    }

    /**
     * Called from the SAF folder picker. Persists the tree grant, resolves the picked
     * volume folder to a real disk path (MANAGE_EXTERNAL_STORAGE already grants raw
     * access) and lists the .sma files inside it.
     */
    fun setScriptRoot(uri: Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // grant may not be persistable (rare); listing still works this session
        }
        val dir = uriToDir(uri) ?: let {
            _compile.value = CompileState.Failed(
                "Could not resolve the picked folder to a disk path.\n" +
                    "Pick a folder on the device's internal storage (e.g. .../xash/cstrike/addons/amxmodx/scripting)."
            )
            return
        }
        _scriptRoot.value = dir.absolutePath
        compilerPrefs.edit().putString("script_root", dir.absolutePath).apply()
        refreshScripts()
    }

    /**
     * Called from the SAF folder picker for the .amxx output folder.
     * Persisted the same way as the scripts folder.
     */
    fun setOutputRoot(uri: Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // grant may not be persistable (rare); output still works this session
        }
        val dir = uriToDir(uri) ?: let {
            _compile.value = CompileState.Failed(
                "Could not resolve the picked folder to a disk path.\n" +
                    "Pick a folder on the device's internal storage."
            )
            return
        }
        _outputRoot.value = dir.absolutePath
        compilerPrefs.edit().putString("output_root", dir.absolutePath).apply()
    }

    /** Clears the custom output folder (back to "<scripts>/compiled"). */
    fun clearOutputRoot() {
        _outputRoot.value = null
        compilerPrefs.edit().remove("output_root").apply()
    }

    private fun uriToDir(uri: Uri): File? {
        // external storage (com.android.externalstorage.documents):
        // content://.../tree/primary%3Axash%2Fcstrike  -> /storage/emulated/0/xash/cstrike
        val path = uri.path ?: return null
        val marker = "/tree/"
        val idx = path.indexOf(marker)
        val doc = if (idx >= 0) path.substring(idx + marker.length) else path.trimStart('/')
        val decoded = URLDecoder.decode(doc, Charsets.UTF_8.name()) // primary:xash/cstrike
        val volumeSep = decoded.indexOf(':')
        if (volumeSep < 0) return null
        val volume = decoded.substring(0, volumeSep) // primary (or SD-card volume id)
        val rel = decoded.substring(volumeSep + 1).trimStart('/')
        if (volume == "primary") {
            return File(File(Environment.getExternalStorageDirectory(), ""), rel)
        }
        // secondary/custom volume: look it up on mounted volumes (API 30+ for a path)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val manager = getApplication<Application>().getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (v in manager.storageVolumes) {
                val dirPath = v.directory?.absolutePath ?: continue
                if (v.uuid == volume && File(dirPath).isDirectory) {
                    return File(File(dirPath, ""), rel)
                }
            }
        }
        return null
    }

    fun refreshScripts() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = _scriptRoot.value?.let { File(it) } ?: run {
                _scripts.value = emptyList()
                return@launch
            }
            _scripts.value = if (dir.isDirectory) {
                dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".sma", ignoreCase = true)
                }?.sortedBy { it.name }?.map { f ->
                    SmaSource(
                        path = f.absolutePath,
                        name = f.name,
                        hasInclude = File(f.parentFile, "include").isDirectory,
                    )
                }.orEmpty()
            } else {
                emptyList()
            }
        }
    }

    /**
     * Compiles a single .sma on-device using the amxxpc bundled in the release
     * module. The driver + its libpc300 kernel (amxxpc32.so) are extracted from the
     * bundle into the app files dir so no separate install is required. Falls back
     * to an amxxpc sitting next to the script if no bundle compiler is available.
     */
    fun compile(source: SmaSource) = compileAll(listOf(source))

    /**
     * Compiles every selected .sma in order (multi-compile). The combined output
     * is shown as one log: a per-file pass/fail header plus amxxpc's own stderr.
     * If any plugin fails, the whole batch is reported as [CompileState.Failed]
     * (the log still shows which ones compiled cleanly).
     */
    fun compileAll(sources: List<SmaSource>) {
        if (sources.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val total = sources.size
            val log = StringBuilder()
            var failed = 0
            // Script Folder holds the folder the user picked for plugins
            // (e.g. .../amxmodx/scripting). Log files live in exactly
            // ScriptFolder/logs/ (only "logs/" appended, never another
            // "scripting", even if the picker already points at the
            // scripting folder).
            val scriptFolder = File(sources.first().path).parentFile
            val logDir = if (scriptFolder != null) File(scriptFolder, "logs") else null
            val compilerLog = logDir?.let { File(it, "compiler.log") }
            val errorLog = logDir?.let { File(it, "error.log") }
            logDir?.mkdirs()
            for ((i, source) in sources.withIndex()) {
                _compile.value = CompileState.Compiling("${source.name} ($i of $total)")
                val (ok, body) = try {
                    compileOne(source)
                } catch (t: Throwable) {
                    false to (t.message ?: "Compile error")
                }
                log
                    .append("── ${source.name} ──\n")
                    .append(body.trim().ifEmpty { if (ok) "Done." else "Compile failed." })
                    .append('\n')
                    .append('\n')
                val entry = "── ${source.name} ──\n${body.trim()}\n\n"
                val target = if (ok) compilerLog else errorLog
                if (target != null) {
                    try {
                        val stamp = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                        ).format(java.util.Date())
                        target.appendText(
                            if (ok) entry
                            else "===== $stamp ${source.name} =====\n$entry"
                        )
                    } catch (_: Throwable) {}
                }
                if (!ok) failed++
            }
            val okCount = total - failed
            val summary = "\n=== $okCount ok, $failed failed ==="
            _compile.value =
                if (failed == 0) CompileState.Done(log.toString() + summary)
                else CompileState.Failed(log.toString() + summary)
        }
    }

    /**
     * Runs amxxpc once for a single source and returns (success, raw output).
     */
    private fun compileOne(source: SmaSource): Pair<Boolean, String> {
        val f = File(source.path)
        if (!f.exists()) error("Source not found: ${source.name}")
        val scriptDir = f.parentFile ?: error("Bad source path")
        val includeDir = File(scriptDir, "include")

        val amxxpc = prepareCompiler(scriptDir) ?: error(
            "Compiler (amxxpc) unavailable.\n" +
                "Pick the scripting folder, or install a patch with the embedded compiler first:\n" +
                "• bundle compiler: $preparedCompilerPath\n" +
                "• next to script: ${File(scriptDir, "amxxpc").absolutePath}"
        )

        val cmd = mutableListOf(amxxpc.absolutePath)
        if (includeDir.isDirectory) {
            cmd.add("-i${includeDir.absolutePath}")
        }
        val compiledDir = _outputRoot.value?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(scriptDir, "compiled")
        compiledDir.mkdirs()
        val outPath = File(compiledDir, f.nameWithoutExtension + ".amxx").absolutePath
        cmd.add("-o$outPath")
        cmd.add(source.path)
        // Ensure compiler dir is in LD_LIBRARY_PATH so driver finds amxxpc32.so
        // (driver does dlopen("amxxpc32.so") / dlopen("./amxxpc32.so"))
        val compilerDir = amxxpc.parentFile
        val pb = ProcessBuilder(cmd).directory(scriptDir).redirectErrorStream(true)
        if (compilerDir != null && compilerDir.isDirectory) {
            val oldLd = pb.environment()["LD_LIBRARY_PATH"]
            pb.environment()["LD_LIBRARY_PATH"] = compilerDir.absolutePath + if (!oldLd.isNullOrEmpty()) ":$oldLd" else ""
        }
        // Last-chance chmod if file lost exec bit (e.g. after reboot)
        if (!amxxpc.canExecute()) {
            try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
            amxxpc.setExecutable(true, false)
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        var ok = exit == 0
        val body = buildString {
            append(output.trim().ifEmpty { if (ok) "Done." else "Compile failed." })
            val out = File(compiledDir, f.nameWithoutExtension + ".amxx")
            if (exit != 0 || !out.exists()) {
                ok = false
                append("\nCompile failed.")
            }
        }
        return ok to body
    }

    private val preparedCompilerPath: String
        get() = File(getApplication<Application>().filesDir, "compiler/amxxpc").absolutePath

    /**
     * Returns a runnable amxxpc: prefers the copy shipped as native lib
     * (lib/arm64-v8a/libamxxpc.so in the patcher APK → nativeLibraryDir, always
     * executable), then falls back to extracting from bundle into filesDir.
     */
    private fun prepareCompiler(fallbackDir: File): File? {
        // 1) nativeLibraryDir (APK lib, extractNativeLibs=true) — always exec-allowed
        try {
            val nativeDir = File(getApplication<Application>().applicationInfo.nativeLibraryDir)
            val nativeAmxxpc = File(nativeDir, "libamxxpc.so")
            val nativeKernel = File(nativeDir, "libamxxpc32.so")
            if (nativeAmxxpc.exists() && nativeAmxxpc.canExecute()) {
                // Driver dlopens "amxxpc32.so" (no lib prefix) — copy libamxxpc32.so to filesDir/amxxpc32.so so it is found
                try {
                    val compilerDir = File(getApplication<Application>().filesDir, "compiler")
                    compilerDir.mkdirs()
                    val kernelCopy = File(compilerDir, "amxxpc32.so")
                    if (nativeKernel.exists() && (!kernelCopy.exists() || kernelCopy.length() != nativeKernel.length())) {
                        kernelCopy.writeBytes(nativeKernel.readBytes())
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernelCopy.setReadable(true, false)
                    }
                } catch (_: Throwable) {}
                return nativeAmxxpc
            }
        } catch (_: Throwable) {}

        // Use the app's internal files dir (getFilesDir), not external storage:
        // the external/emulated dir is typically mounted noexec, so an ELF written
        // there cannot be exec'd ("permission denied" on ProcessBuilder.start()).
        val compilerDir = File(getApplication<Application>().filesDir, "compiler")
        val amxxpc = File(compilerDir, "amxxpc")
        val kernel = File(compilerDir, "amxxpc32.so")

        val bundleFiles = try {
            loadedBundle ?: bundleProvider.loadCachedBundle()
        } catch (_: Throwable) {
            null
        }
        val driverBytes = bundleFiles?.files?.get("compiler/amxxpc")
        if (driverBytes != null && driverBytes.isNotEmpty()) {
            try {
                compilerDir.mkdirs()
                amxxpc.writeBytes(driverBytes)
                // chmod 755 via shell is more reliable than File.setExecutable alone
                // (some OEMs / SELinux ignore the Java API). Do both.
                try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
                amxxpc.setExecutable(true, false)
                amxxpc.setReadable(true, false)
                bundleFiles.files["compiler/amxxpc32.so"]?.let {
                    if (it.isNotEmpty()) {
                        kernel.writeBytes(it)
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "755", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernel.setReadable(true, false)
                        // kernel is dlopened, not executed, but needs r+x for some loaders
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                    }
                }
                return amxxpc
            } catch (_: Throwable) {
                // extraction failed; fall through to local
            }
        }
        // fallback: a compiler already present in the picked/script folder
        val root = _scriptRoot.value?.let { File(it) }
        return listOfNotNull(root, fallbackDir)
            .map { File(it, "amxxpc") }
            .firstOrNull { it.exists() && it.canExecute() }
    }

    companion object {
        const val CACHE_TAG = "v2"
        const val GAME_DIR = "/storage/emulated/0/xash/cstrike"
        /** Xash base dir; supported games live directly under it. */
        const val GAME_ROOT = "/storage/emulated/0/xash"
        const val GAME_CSTRIKE = "cstrike"
        const val GAME_CZERO = "czero"
        /** ABIs the patcher can build for, in priority order. */
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
        /** Releases (tags + patcher APK) are published here by CI. */
        const val APP_RELEASE_REPO = "berkchy/nexora"
        /**
         * Single rolling release: one "Continuous" tag whose assets are
         * replaced on every build. Update check = version.txt string compare
         * + APK byte-size check (the tag itself never changes).
         */
        const val CONTINUOUS_TAG = "continuous"
        const val CONTINUOUS_APK = "nexora_continuous.apk"
        const val VERSION_FILE = "version.txt"
        /**
         * User-edited AMXX config files from addons/amxmodx/configs/. The addons
         * extractor may only create these when missing — never overwrite them.
         */
        val PROTECTED_ADDON_CONFIGS = setOf(
            "addons/amxmodx/configs/modules.ini",
            "addons/amxmodx/configs/amxx.cfg",
            "addons/amxmodx/configs/cvars.ini",
            "addons/amxmodx/configs/maps.ini",
            "addons/amxmodx/configs/plugins.ini",
            "addons/amxmodx/configs/sql.cfg",
            "addons/amxmodx/configs/users.ini",
        )
    }

    /**
     * Auto-install addons from the embedded bundle into the game directory.
     * Checks storage permission first; if not granted, silently skips.
     * Only writes files that are missing or have different sizes (no overwrite of user edits).
     */
    fun autoInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@launch
            }
            val gameDir = File(_installPath.value)
            if (!gameDir.exists()) return@launch

            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle() ?: return@launch
            var installed = 0
            for (entry in bundle.manifest.entries) {
                if (!entry.target.startsWith("addons/")) continue
                val target = File(gameDir, entry.target)
                if (target.exists()) continue
                val content = bundle.resolveEntry(entry) ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                installed++
            }
            if (installed > 0) {
                _addons.value = AddonsState.Done("Auto-installed $installed addon files")
            }
            scanAddonsStatus()
        }
    }

    data class AddonFileStatus(
        val path: String,
        val expected: Boolean,
        val installed: Boolean,
        val outdated: Boolean = false,
    )

    private val _addonFiles = MutableStateFlow<List<AddonFileStatus>>(emptyList())
    val addonFiles: StateFlow<List<AddonFileStatus>> = _addonFiles.asStateFlow()

    private val gamePrefs by lazy {
        getApplication<Application>().getSharedPreferences("game_prefs", Context.MODE_PRIVATE)
    }

    private fun savedGameId(): String =
        gamePrefs.getString("game_id", GAME_CSTRIKE)?.takeIf {
            it == GAME_CSTRIKE || it == GAME_CZERO
        } ?: GAME_CSTRIKE

    private fun gameDirFor(gameId: String): String = "$GAME_ROOT/$gameId"

    private val _installPath = MutableStateFlow(gameDirFor(savedGameId()))
    val installPath: StateFlow<String> = _installPath.asStateFlow()

    /** Selected game: "cstrike" (Counter-Strike 1.6) or "czero" (Condition Zero). */
    val gameId: StateFlow<String> = _installPath
        .map { path -> if (path.endsWith("/czero")) GAME_CZERO else GAME_CSTRIKE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GAME_CSTRIKE)

    fun setGame(gameId: String) {
        if (gameId != GAME_CSTRIKE && gameId != GAME_CZERO) return
        gamePrefs.edit().putString("game_id", gameId).apply()
        _installPath.value = gameDirFor(gameId)
        scanAddonsStatus()
    }

    fun scanAddonsStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val gameDir = File(_installPath.value)
            val addonsDir = File(gameDir, "addons")

            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle()
            val expected = mutableSetOf<String>()
            if (bundle != null) {
                for (entry in bundle.manifest.entries) {
                    if (!entry.target.startsWith("addons/")) continue
                    expected.add(entry.target)
                }
            }

            val result = mutableListOf<AddonFileStatus>()
            for (target in expected.sorted()) {
                val file = File(gameDir, target)
                if (!file.exists()) {
                    result.add(AddonFileStatus(target, expected = true, installed = false))
                    continue
                }
                // Byte-level compare: different size or different content (or a
                // newer bundle copy) means the installed file is outdated and
                // must be replaced on install.
                var outdated = false
                try {
                    val entry = bundle?.manifest?.entries?.firstOrNull { it.target == target }
                    val content = entry?.let { bundle?.resolveEntry(it) }
                    if (content != null) {
                        outdated = content.size.toLong() != file.length() ||
                            !content.contentEquals(file.readBytes())
                    }
                } catch (_: Throwable) {
                    outdated = false
                }
                result.add(AddonFileStatus(target, expected = true, installed = true, outdated = outdated))
            }
            _addonFiles.value = result
        }
    }
}