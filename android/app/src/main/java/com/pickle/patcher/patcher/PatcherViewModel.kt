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
import com.pickle.patcher.lib.ApkSignerTool
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipAnalyzer
import com.pickle.patcher.lib.ZipRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import com.pickle.patcher.jobs.JobProgress

data class SourceInfo(
    val name: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val abis: List<String> = emptyList(),
)

/** Progress of the "download the client APK from GitHub" action. */
sealed interface SourceDownloadState {
    data object Fetching : SourceDownloadState
    data class Downloading(val downloaded: Long, val total: Long) : SourceDownloadState
    data class Done(val name: String) : SourceDownloadState
    data class Failed(val message: String) : SourceDownloadState

    /** The cached APK is not what GitHub serves any more. */
    data class Outdated(
        val name: String,
        val localSize: Long,
        val remoteSize: Long,
        /** Which check tripped: size, content hash, or build date. */
        val reason: String,
    ) : SourceDownloadState

    /** The cached APK is broken (truncated download, unreadable zip). */
    data class Corrupt(val name: String, val reason: String) : SourceDownloadState
}

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
    data class Running(
        val step: ApkPatcher.Step,
        val progress: Float,
        val detail: String = "",
        val index: Int = 0,
        val total: Int = 0,
        val log: List<String> = emptyList(),
        val counters: Map<String, String> = emptyMap(),
    ) : PatchUiState
    data class Done(
        val report: ApkPatcher.PatchReport,
        val log: List<String> = emptyList(),
    ) : PatchUiState
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
    data class Done(val log: String, val failures: Map<String, String> = emptyMap()) : CompileState
    data class Failed(val message: String) : CompileState
}

class PatcherViewModel(app: Application) : AndroidViewModel(app) {

    private val bundleProvider = BundleProvider(app)

    /** The application context, for the job notifications. */
    private val app: Application = app

    private val _source = MutableStateFlow<SourceInfo?>(null)
    val source: StateFlow<SourceInfo?> = _source.asStateFlow()

    private val _receivedSource: MutableStateFlow<File?> = MutableStateFlow(null)

    private val _sourceDownload = MutableStateFlow<SourceDownloadState?>(null)
    val sourceDownload: StateFlow<SourceDownloadState?> = _sourceDownload.asStateFlow()

    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()

    /**
     * The ABI of the phone the patcher runs on. There is no ABI picker: a
     * 32-bit phone cannot load arm64 modules and patching one in would only
     * produce an APK that dies at startup, so the device decides and the source
     * APK is only allowed to narrow it down, never widen it.
     */
    private val deviceAbi: String =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: SUPPORTED_ABIS.first()

    /** ABI every patch step uses. Fixed by the device architecture. */
    private val _abi = MutableStateFlow(deviceAbi)
    val abi: StateFlow<String> = _abi.asStateFlow()

    val supportedAbis: List<String> = SUPPORTED_ABIS
    val isDeviceAbi: String get() = deviceAbi

    val sourceAbis: List<String> get() = _source.value?.abis ?: emptyList()
    val loadedBundleAbi: String? get() = loadedBundle?.manifest?.abi?.ifBlank { null }

    /**
     * Picks the ABI to patch for: the device's own one whenever the source APK
     * ships it, otherwise the only one the APK does have.
     */
    private fun resolveAbi(sourceAbis: List<String>): String? = when {
        sourceAbis.isEmpty() -> null
        deviceAbi in sourceAbis -> deviceAbi
        else -> sourceAbis.first()
    }

    private fun setAbi(abi: String) {
        if (abi !in SUPPORTED_ABIS || abi == _abi.value) return
        _abi.value = abi
        val b = loadedBundle
        if (b != null && b.manifest.abi.isNotBlank() && b.manifest.abi != abi) {
            loadedBundle = null
            _bundle.value = BundleState.None
        }
        viewModelScope.launch(Dispatchers.IO) {
            applyGamedataAbiPolicy(File(_installPath.value), abi)
            // Installed libraries live in libs/<abi>/, so an ABI that was
            // already set up rebuilds its bundle from disk instead of being
            // asked to download one it already has.
            if (loadedBundle == null) useCachedBundle()
            refreshAbiStatus()
        }
        scanLibs(autoLoad = true)
    }

    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()

    /** Per-ABI state so the target card can show every option's status at once. */
    data class AbiStatus(
        val abi: String,
        val inSource: Boolean,
        val libCount: Int,
        val hasBundle: Boolean,
        val selected: Boolean,
        val downloading: Boolean,
        val downloadPercent: Float,
    ) {
        val installed: Boolean get() = hasBundle && libCount > 0
    }

    data class PatchHistoryEntry(
        val time: Long,
        val abi: String,
        val source: String,
        val entries: Int,
        val libs: Int,
        val verified: Boolean,
        val ok: Boolean,
    )

    data class PatchSummary(
        val abi: String,
        val source: String,
        val sourceSize: Long,
        val components: Int,
        val entries: Int,
        val payloadSize: Long,
    )

    sealed interface PatchCheck {
        data object Idle : PatchCheck
        data object Running : PatchCheck
        data class Result(
            val signatureOk: Boolean,
            val usedV1: Boolean,
            val usedV2: Boolean,
            val expectedLibs: Int,
            val presentLibs: Int,
            val engineCommit: String,
        ) : PatchCheck
        data class Failed(val message: String) : PatchCheck
    }

    private val _patchLog = MutableStateFlow<List<String>>(emptyList())
    val patchLog: StateFlow<List<String>> = _patchLog.asStateFlow()
    private val _patchHistory = MutableStateFlow<List<PatchHistoryEntry>>(emptyList())
    val patchHistory: StateFlow<List<PatchHistoryEntry>> = _patchHistory.asStateFlow()
    private val _patchCheck = MutableStateFlow<PatchCheck>(PatchCheck.Idle)

    private val _bundleVersion = MutableStateFlow("")
    val bundleVersion: StateFlow<String> = _bundleVersion.asStateFlow()
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    /** True once per bundle version, so the intro can replay after an update. */
    fun shouldReplayIntro(version: String): Boolean {
        if (version.isBlank()) return false
        if (_bundleVersion.value != version) {
            _bundleVersion.value = version
            val prefs = getApplication<Application>()
                .getSharedPreferences("patcher_ui", Context.MODE_PRIVATE)
            val seen = prefs.getString("intro_version", null)
            if (seen != version) {
                prefs.edit().putString("intro_version", version).apply()
                return true
            }
        }
        return false
    }
    val patchCheck: StateFlow<PatchCheck> = _patchCheck.asStateFlow()

    private val _abiStatus = MutableStateFlow<List<AbiStatus>>(emptyList())
    val abiStatus: StateFlow<List<AbiStatus>> = _abiStatus.asStateFlow()
    private var downloadingAbi: String? = null

    fun refreshAbiStatus() {
        val sourceAbis = _source.value?.abis ?: emptyList()
        val selected = _abi.value
        _abiStatus.value = supportedAbis.map { a ->
            val dir = File(libsDir, a)
            val count = if (dir.isDirectory) {
                dir.listFiles()?.count { it.isFile && it.name.endsWith(".so") } ?: 0
            } else 0
            AbiStatus(
                abi = a,
                inSource = sourceAbis.isEmpty() || a in sourceAbis,
                libCount = count,
                hasBundle = count > 0 || bundleProvider.hasCachedBundle(a) || loadedBundle?.manifest?.abi == a,
                selected = a == selected,
                downloading = downloadingAbi == a,
                downloadPercent = if (downloadingAbi == a) {
                    (_bundle.value as? BundleState.Downloading)?.percent ?: 0f
                } else 0f,
            )
        }
    }

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

    // How many amxxpc processes may run at once. Defaults to every core but one
    // so the UI and the game keep a core, and is clamped on read because the
    // device may have fewer cores than when the setting was saved.
    private val _compileWorkers = MutableStateFlow(0)
    val compileWorkers: StateFlow<Int> = _compileWorkers.asStateFlow()

    init {
        val saved = compilerPrefs.getInt("compile_workers", 0)
        _compileWorkers.value = if (saved > 0) saved.coerceIn(1, maxCompileWorkers()) else maxCompileWorkers()
    }

    /** Upper bound for the worker slider: never more than the usable cores. */
    fun maxCompileWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** Worker count actually used by a compile run. */
    fun effectiveCompileWorkers(): Int =
        _compileWorkers.value.coerceIn(1, maxCompileWorkers())

    /** Persists the worker count; the slider calls this on every change. */
    fun setCompileWorkers(count: Int) {
        val clamped = count.coerceIn(1, maxCompileWorkers())
        if (clamped == _compileWorkers.value) return
        _compileWorkers.value = clamped
        compilerPrefs.edit().putInt("compile_workers", clamped).apply()
    }

    // Declared before init: useCachedBundle() runs synchronously in init and
    // reads these (Kotlin initializes properties in textual order).
    private var loadedBundle: Bundle? = null
    private var lastReport: ApkPatcher.PatchReport? = null

    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle(_abi.value)

    val repo = "berkchy/nexora"

    private val externalRoot: File = app.getExternalFilesDir(null) ?: app.cacheDir
    private val workDir = File(externalRoot, "patcher")

    // The downloaded client APK lives next to the patch output, so both show up
    // under Android/data/<pkg>/files and neither is hidden in internal storage.
    private val sourceCacheDir = File(externalRoot, "apk-source")

    /**
     * A file in [workDir] with the directory created. The external files dir
     * only materializes once something writes into it, and opening the output
     * APK before that fails with ENOENT.
     */
    private fun workFile(name: String): File {
        workDir.mkdirs()
        return File(workDir, name)
    }

    private val libsDir = File(externalRoot, "libs")

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

    /**
     * The client APK the patcher would use: the canonical name first, then the
     * newest APK in either cache location. Both places are checked because the
     * cache moved from internal storage to Android/data, and an APK dropped in
     * by hand under a different name still has to be compared against GitHub
     * instead of looking like there is no client at all.
     */
    private fun cachedClientApk(): File? {
        val candidates = (listOf(sourceCacheDir, legacySourceDir)).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
                ?.toList()
                ?: emptyList()
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name == CLIENT_APK_ASSET }
            ?: candidates.maxByOrNull { it.lastModified() }
    }

    /** Where the cache lived before it moved next to the patch output. */
    private val legacySourceDir = File(getApplication<Application>().filesDir, "apk-source")

    /**
     * Null when the file is a usable client APK, otherwise why it is not.
     * A download that died halfway leaves a zip the analyzer cannot read, so
     * this is what tells "needs another download" apart from "good to patch".
     */
    private fun apkProblem(file: File): String? = try {
        val info = ZipAnalyzer.analyze(file)
        if (info.abis.none { it in SUPPORTED_ABIS }) {
            "no supported native ABI (" +
                "${info.abis.ifEmpty { listOf("none") }.joinToString(", ")})"
        } else {
            null
        }
    } catch (t: Throwable) {
        t.message?.takeIf { it.isNotBlank() } ?: "unreadable archive"
    }

    /**
     * Analyzes an APK already sitting in app storage and adopts it as the
     * source. Shared by the SAF path and the GitHub download path, so both
     * validate the ABI the same way.
     */
    private fun useSourceFile(file: File, label: String) {
        val info = ZipAnalyzer.analyze(file)
        val supported = info.abis.filter { it in SUPPORTED_ABIS }
        if (supported.isEmpty()) {
            _patch.value = PatchUiState.Failed(
                "This APK has no supported native ABIs (found: " +
                    "${info.abis.ifEmpty { listOf("none") }.joinToString(", ")}). " +
                    "Supported: ${SUPPORTED_ABIS.joinToString(", ")}."
            )
            _receivedSource.value = null
            return
        }
        if (_abi.value !in supported) resolveAbi(supported)?.let { setAbi(it) }
        _source.value = SourceInfo(label, file.length(), info.entryCount, supported)
        _receivedSource.value = file
        refreshAbiStatus()
    }

    /**
     * Checks the cached client APK against the current GitHub release whenever
     * the source card shows up: a good APK is adopted without any tap, an
     * outdated one is reported as updatable and a broken one as corrupt, so the
     * patcher never runs against half a download.
     */
    fun refreshSourceApkStatus() {
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val cached = cachedClientApk()
            if (cached == null) {
                _sourceDownload.value = null
                return@launch
            }
            val tag = ReleaseRepository.latestTagRedirect(CLIENT_APK_REPO)
            val remote = tag?.let {
                ReleaseRepository.probe(ReleaseRepository.assetUrl(CLIENT_APK_REPO, it, CLIENT_APK_ASSET))
            }
            val recorded = cached?.let { readSourceStamp(it) }
            val problem = cached?.let { apkProblem(it) }
            val outdated = if (cached != null && remote != null) {
                outdatedReasons(cached, remote, recorded)
            } else {
                emptyList()
            }
            _sourceDownload.value = when {
                problem != null -> SourceDownloadState.Corrupt(CLIENT_APK_ASSET, problem)
                outdated.isNotEmpty() && cached != null -> SourceDownloadState.Outdated(
                    CLIENT_APK_ASSET,
                    cached.length(),
                    remote?.size ?: 0L,
                    outdated.joinToString(", "),
                )
                else -> SourceDownloadState.Done(CLIENT_APK_ASSET)
            }
            // A cached APK that passed every check becomes the source by itself.
            if (_sourceDownload.value is SourceDownloadState.Done &&
                cached != null &&
                _receivedSource.value?.absolutePath != cached.absolutePath &&
                tag != null
            ) {
                useSourceFile(cached, "$cached.name ($tag)")
            }
        }
    }

    /**
     * Every reason the cached APK differs from the asset on GitHub. An empty list
     * means they are the same build.
     *
     * Three independent checks, any of which is enough to offer the update,
     * because each one can be blind on its own: the length is the same for two
     * consecutive client builds, the date is unusable when the file was copied
     * around, and the fingerprint is only known for a file this app downloaded.
     */
    private fun outdatedReasons(
        cached: File,
        remote: ReleaseRepository.RemoteInfo,
        recorded: SourceStamp?,
    ): List<String> {
        val reasons = mutableListOf<String>()
        val localSize = cached.length()

        if (recorded != null) {
            // Something replaced or truncated the file after it was downloaded.
            if (recorded.size != localSize) reasons += "size changed since download"
            // The release asset itself moved on: different content or a rebuild.
            if (remote.etag.isNotEmpty() && recorded.etag.isNotEmpty() &&
                remote.etag != recorded.etag
            ) {
                reasons += "newer build on GitHub"
            }
            if (remote.lastModifiedMillis > 0L && recorded.lastModifiedMillis > 0L &&
                remote.lastModifiedMillis != recorded.lastModifiedMillis
            ) {
                reasons += "rebuilt since download"
            }
        } else {
            // No stamp: the APK was put there by hand. Length and date are all
            // there is, and either one disagreeing means it is not the release.
            if (remote.size > 0L && localSize != remote.size) {
                reasons += "size differs from GitHub"
            }
            if (remote.lastModifiedMillis > 0L &&
                cached.lastModified() + ASSET_DATE_SLOP < remote.lastModifiedMillis
            ) {
                reasons += "older than the GitHub build"
            }
        }

        return reasons
    }

    /** What the cached APK was downloaded from, for the checks above. */
    private data class SourceStamp(val size: Long, val etag: String, val lastModifiedMillis: Long)

    /** What the cached APK was downloaded from, or null if unknown. */
    private fun readSourceStamp(cached: File): SourceStamp? {
        val file = File(cached.parentFile ?: return null, "${cached.name}.stamp")
        if (!file.isFile) return null
        return try {
            val parts = file.readText().trim().split(' ')
            if (parts.size < 3) {
                null
            } else {
                SourceStamp(parts[0].toLong(), parts[1], parts[2].toLong())
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeSourceStamp(cached: File, remote: ReleaseRepository.RemoteInfo?) {
        try {
            cached.parentFile?.mkdirs()
            File(cached.parentFile, "${cached.name}.stamp").writeText(
                "${cached.length()} ${remote?.etag.orEmpty()} ${remote?.lastModifiedMillis ?: 0L}"
            )
        } catch (_: Throwable) {
            // The stamp only sharpens the update check; losing it means the next
            // check falls back to size and timestamps.
        }
    }

    /**
     * Downloads the client APK from the vcs16 Continuous release into
     * filesDir/apk-source and adopts it. The patcher already talks to that
     * release for the native libraries, so this reuses the same quota-free
     * download path instead of asking the user to hunt for a file.
     */
    fun downloadSourceApk() {
        // Only an in-flight download blocks a new one. Returning early on any
        // non-null state is what made the Update button do nothing: the status
        // check always leaves a state behind.
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _sourceDownload.value = SourceDownloadState.Fetching
            JobProgress.begin(app, JOB_CLIENT_APK, "Client APK", "Finding the release…")
            var partial: File? = null
            try {
                val repo = CLIENT_APK_REPO
                val tag = ReleaseRepository.latestTagRedirect(repo)
                    ?: throw IOException("Could not resolve the latest $repo release")
                val name = CLIENT_APK_ASSET
                val url = ReleaseRepository.assetUrl(repo, tag, name)
                val remote = ReleaseRepository.probe(url)
                val total = remote?.size ?: 0L
                val dir = sourceCacheDir.apply { mkdirs() }
                val dest = File(dir, name)
                val part = File(dir, "$name.part").also { it.delete() }
                partial = part
                ReleaseRepository.downloadUrl(url, part, total) { done, size ->
                    _sourceDownload.value = SourceDownloadState.Downloading(done, size)
                    JobProgress.progress(app, JOB_CLIENT_APK, done, size, "Downloading…")
                }
                // A short read means the connection died mid-file: keep the
                // broken bytes out of the cache and say so.
                if (total > 0 && part.length() != total) {
                    part.delete()
                    throw IOException(
                        "Download stopped at %.1f MB of %.1f MB".format(
                            part.length() / 1048576.0,
                            total / 1048576.0,
                        )
                    )
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                apkProblem(dest)?.let {
                    dest.delete()
                    throw IOException("The downloaded APK is broken: $it")
                }
                writeSourceStamp(dest, remote)
                useSourceFile(dest, "$name ($tag)")
                _sourceDownload.value = SourceDownloadState.Done(name)
                JobProgress.finish(app, JOB_CLIENT_APK, "Client APK ready", apkPath = dest.path)
            } catch (t: Throwable) {
                partial?.delete()
                _sourceDownload.value = SourceDownloadState.Failed(t.message ?: "Download failed")
                JobProgress.finish(
                    app,
                    JOB_CLIENT_APK,
                    t.message ?: "Download failed",
                    ok = false,
                )
            }
        }
    }

    fun clearSourceDownloadState() {
        _sourceDownload.value = null
    }

    fun useCachedBundle() {
        val abi = _abi.value
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi)
        if (files.isNotEmpty()) {
            val b = buildBundleFromFileMap(files, abi)
            loadedBundle = b
            _bundle.value = BundleState.Loaded
            _bundleVersion.value = b.manifest.version
            scanLibs()
            refreshAbiStatus()
            return
        }
        // Fallback: the cached bundle for this ABI
        val b = bundleProvider.loadCachedBundle(abi)
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
        downloadingAbi = _abi.value
        refreshAbiStatus()
        viewModelScope.launch(Dispatchers.IO) {
            _bundle.value = BundleState.Downloading(0.04f)
            var jobStarted = false
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
                    // Nothing changed, so no job was ever announced: leaving a
                    // notification up for a download that never happened is
                    // exactly what used to look like a stuck progress bar.
                    return@launch
                }
                JobProgress.begin(app, JOB_LIBS, "Libraries", "Downloading ${diff.toDownload.size} files…")
                jobStarted = true

                // Download changed files with per-lib progress
                IncrementalUpdateManager.downloadChanged(
                    diff.toDownload, libsDir, _abi.value,
                    onFileStart = { index, asset ->
                        markLibDownloading(asset.cleanName, 0f)
                        // Name the file being fetched; the notification used to
                        // say only "Downloading 19 files…" for the whole run.
                        JobProgress.detail(app, JOB_LIBS, asset.cleanName)
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
                        // Count bytes, not files: with a handful of .so files the
                        // per-file counter sits still for the whole download and
                        // the bar looks frozen. Fold the per-file fraction into
                        // the reported position so it always creeps forward.
                        val overall =
                            (index + fileProgress.coerceIn(0f, 1f)) / diff.toDownload.size.toFloat()
                        JobProgress.progress(
                            app,
                            JOB_LIBS,
                            (overall * 100).toLong().toInt().toLong().coerceIn(0, 99),
                            100L,
                            "${asset.cleanName} · ${
                                (fileProgress * 100).toInt().coerceIn(0, 100)
                            }%",
                        )
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
                downloadingAbi = null
                _bundleVersion.value = b.manifest.version
                scanLibs()
                refreshAbiStatus()
                JobProgress.finish(app, JOB_LIBS, "Libraries ready")
            } catch (t: Throwable) {
                // Offline fallback: if there are already libs on disk, load them.
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, _abi.value)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                    scanLibs()
                    if (jobStarted) {
                        JobProgress.finish(app, JOB_LIBS, "Using the libraries already on disk")
                    }
                } else {
                    _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                    if (jobStarted) {
                        JobProgress.finish(app, JOB_LIBS, t.message ?: "Download failed", ok = false)
                    }
                }
                downloadingAbi = null
                refreshAbiStatus()
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
                name == "libmetamod.so" || name.startsWith("libmetamod_android_") -> "Metamod HL1"
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
            name == "libmetamod.so" || name.startsWith("libmetamod_android_") -> "metamod"
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
            JobProgress.begin(app, JOB_ADDONS, "Addons", "Resolving the release…")
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
                    JobProgress.progress(app, JOB_ADDONS, done, total, "Downloading…")
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into ${_installPath.value.substringAfterLast("/")}…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                patchMetamodConfig(target, _abi.value)
                val gamedataToggled = applyGamedataAbiPolicy(target, _abi.value)
                JobProgress.finish(app, JOB_ADDONS, "Addons installed")
                _addons.value = AddonsState.Done(
                    buildString {
                        append("Installed $count addons files into ${target.path}")
                        if (gamedataToggled) {
                            val variant = if (_abi.value == "arm64-v8a") "arm64" else "arm32"
                            append("\n$variant gamedata override selected for ${_abi.value}")
                        }
                    }
                )
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
                JobProgress.finish(app, JOB_ADDONS, t.message ?: "Install failed", ok = false)
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

    /**
     * Files that have to be present in the game directory before the game can
     * start. The client APK is a shell that carries only the menu library -
     * nexora injects libclient into it at patch time and never had the content
     * inside it - so this is the check that stands between the user and a
     * silent game with no sound, no menu graphics and no touch controls.
     *
     * touch.cfg, the touch_default/numbers.cfg layout and the bot databases are
     * read by path, and the gfx/shell bitmaps are what the menu draws itself
     * with; one missing file in each group is enough to tell a complete
     * install from a half-finished one without walking 26 MB of tree. Each
     * entry has to be a real file - touch_default is a directory, so naming
     * it would have been satisfied by the extract alone.
     */
    private val requiredGameAssets = listOf(
        "touch.cfg",
        "touch_default/numbers.cfg",
        "BotProfile.db",
        "BotChatter.db",
        "gfx/shell/btn_touch.bmp",
        "maps/tr_1.bsp",
        "sound/radio/bot/a.wav",
    )

    private fun missingGameAssets(gameDir: File): List<String> =
        requiredGameAssets.filterNot { File(gameDir, it).isFile }

    /**
     * True when the source APK still ships the content itself.
     *
     * Older client APKs (and the googlePlay flavor, which is still standalone)
     * carry assets/, so their installs are already complete and the 26 MB
     * download would be pure waste. Only the shell needs this.
     */
    private fun sourceApkShipsAssets(apk: File): Boolean = runCatching {
        java.util.zip.ZipFile(apk).use { zf ->
            zf.getEntry("assets/touch.cfg") != null
        }
    }.getOrDefault(false)

    /**
     * Makes sure the game content is on disk before patching starts.
     *
     * Returns the number of files extracted, or 0 when the content was already
     * complete. Anything short of complete - a missing group, a truncated zip
     * from a dropped connection - is treated as "not installed": the files that
     * did land are overwritten by the fresh copy, because a partial extract is
     * exactly the case where trusting what is on disk is worst.
     */
    private suspend fun ensureGameAssets(
        gameDir: File,
        onStatus: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Int {
        val missing = missingGameAssets(gameDir)
        if (missing.isEmpty()) {
            onStatus("Game content is already in place")
            return 0
        }
        onStatus("Game content is incomplete (${missing.size} of ${requiredGameAssets.size} checks failed) - downloading…")

        val tag = ReleaseRepository.latestTagRedirect(repo)
            ?: throw IOException("Could not reach GitHub releases")
        val zip = File(bundleProvider.cacheDir(), "game-assets.zip")
        // Always refetch: if the cache holds a truncated download from an
        // interrupted run, reusing it would fail the very check we are here
        // to satisfy.
        if (zip.exists()) zip.delete()
        ReleaseRepository.downloadUrl(
            ReleaseRepository.assetUrl(repo, tag, "game-assets.zip"),
            zip,
            onProgress = onProgress,
        )
        if (!zip.isFile || zip.length() == 0L) {
            throw IOException("game-assets.zip came back empty")
        }

        onStatus("Extracting game content…")
        gameDir.mkdirs()
        val extracted = unzipInto(zip, gameDir)

        // Re-check rather than trusting the extract count: a zip can carry every
        // entry and still miss one we care about, and the user's own deletions
        // after a successful install are a normal reason to come back here.
        val stillMissing = missingGameAssets(gameDir)
        if (stillMissing.isNotEmpty()) {
            throw IOException(
                "Game content is still incomplete after extracting $extracted files: " +
                    stillMissing.joinToString(", ")
            )
        }
        return extracted
    }

    private fun unzipInto(zip: File, target: File): Int {
        var count = 0
        java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.isBlank()) return@forEach
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Unsafe path in addons zip: $name")
                }
                if (entry.isDirectory) {
                    // Create empty dirs too (e.g. addons/amxmodx/plugins/) —
                    // skipping them leaves amxx without its plugins folder.
                    out.mkdirs()
                    return@forEach
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
    /**
     * Writes the metamod config for the selected game.
     *
     * Counter-Strike gets the ReGameDLL game dll and both plugins. Condition Zero
     * gets neither: libcs_android_*.so is ReGameDLL_CS, it only speaks
     * Counter-Strike, and pointing czero at it took the engine into a game dll
     * that knows none of its entities. The user report was a SIGSEGV inside
     * libyapb right after a wall of "non-existent cvar yb_*" warnings, which is
     * YaPB walking czero with Counter-Strike offsets. A mod without a matching
     * game dll stays untouched instead of crashing.
     */
    private fun patchMetamodConfig(gameDir: File, abi: String) {
        val suffix = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            else -> return
        }
        val isCounterStrike = gameDir.name == GAME_CSTRIKE
        val metamodDir = File(gameDir, "addons/metamod").apply { mkdirs() }

        val configFile = File(metamodDir, "config.ini")
        val gamedllLine = "gamedll dlls/libcs_android_${suffix}.so"
        if (isCounterStrike) {
            if (configFile.exists()) {
                val lines = configFile.readLines().toMutableList()
                val idx = lines.indexOfFirst { it.startsWith("gamedll") }
                if (idx >= 0) lines[idx] = gamedllLine else lines.add(0, gamedllLine)
                configFile.writeText(lines.joinToString("\n"))
            } else {
                configFile.writeText("$gamedllLine\n")
            }
        } else {
            // No gamedll line at all: metamod then loads nothing.
            if (configFile.exists()) {
                configFile.writeText(
                    configFile.readLines()
                        .filterNot { it.trimStart().startsWith("gamedll") }
                        .joinToString("\n")
                )
            }
        }

        // The plugin list decides what runs, and YaPB only supports
        // Counter-Strike: loading it elsewhere is what crashed czero.
        val pluginsFile = File(metamodDir, "plugins.ini")
        val plugins = if (isCounterStrike) {
            buildString {
                append("; Metamod plugins.ini\n")
                append("; Format: <platform> <path relative to game directory>\n")
                append("; AMX Mod X core (lives in the app native dir as libamxmodx.so)\n")
                append("linux addons/amxmodx/libamxmodx.so\n")
                append("; YaPB Counter-Strike bot\n")
                append("linux addons/yapb/bin/libyapb.so\n")
            }
        } else {
            buildString {
                append("; Metamod plugins.ini - ${gameDir.name}\n")
                append("; Nothing is loaded here on purpose: the AMXX stack needs a\n")
                append("; Counter-Strike game dll (ReGameDLL_CS) and YaPB is a\n")
                append("; Counter-Strike bot. Loading either under Condition Zero crashes\n")
                append("; the game, so ${gameDir.name} is left as the shipped mod.\n")
            }
        }
        pluginsFile.writeText(plugins)
    }

    /**
     * AMXX parses *every* *.txt in addons/amxmodx/data/gamedata/common.games/custom,
     * and the ReGameDLL member layout is ABI-specific (pointer size, member
     * order), so the wrong variant silently corrupts cstrike pdata. The per-ABI
     * files ship as offsets-cstrike-replugged.<abi>.txt; the one matching the
     * installed ABI is kept as *.txt and the other is renamed out of the scan.
     */
    private fun applyGamedataAbiPolicy(gameDir: File, abi: String): Boolean {
        val customDir = File(gameDir, "addons/amxmodx/data/gamedata/common.games/custom")
        val files = customDir.listFiles() ?: return false
        val wanted = if (abi == "arm64-v8a") "arm64" else "arm32"
        var changed = false
        for (file in files) {
            if (!file.isFile) continue
            // Pre-per-ABI installs still carry the untagged arm64 table; left
            // in place it would keep overriding offsets on a 32-bit gamedll.
            if (file.name == LEGACY_GAMEDATA_NAME) {
                if (file.delete()) changed = true
                continue
            }
            val name = file.name.removeSuffix(GAMEDATA_DISABLED_SUFFIX)
            val match = GAMEDATA_VARIANT.matchEntire(name) ?: continue
            val variant = match.groupValues[1]
            val target = if (variant == wanted) name else name + GAMEDATA_DISABLED_SUFFIX
            if (file.name == target) continue
            if (file.renameTo(File(file.parentFile, target))) changed = true
        }
        return changed
    }

    /** One console line per meaningful event; null keeps the console quiet. */
    private fun logLine(tick: ApkPatcher.Progress): String? {
        val detail = tick.detail
        val leaf = detail.substringAfterLast('/')
        return when {
            tick.kind == "add" -> "+ $leaf"
            tick.kind == "keep" && (detail.startsWith("lib/") || detail.startsWith("addons/")) -> "  $leaf"
            tick.kind.isEmpty() && detail.isNotBlank() -> "\u2192 ${tick.step.name.lowercase()} \u00b7 $detail"
            else -> null
        }
    }

    /** One line for the notification, from the same tick the log line uses. */
    private fun patchStepLabel(tick: ApkPatcher.Progress): String {
        val progress = if (tick.total > 0) {
            " ${(tick.fraction * 100).toInt()}%"
        } else {
            ""
        }
        val leaf = tick.detail.substringAfterLast('/')
        return if (leaf.isNotBlank()) {
            "${tick.step.name.lowercase()} · $leaf$progress"
        } else {
            "${tick.step.name.lowercase()}$progress"
        }
    }

    /** Deterministic per-stage counters, carried forward between ticks. */
    private fun stepCounters(
        tick: ApkPatcher.Progress,
        prev: PatchUiState.Running?,
    ): Map<String, String> {
        val libs = (prev?.counters?.get("libs")?.toIntOrNull() ?: 0) + if (tick.kind == "add") 1 else 0
        val entries = if (tick.total > 0) "${tick.index}/${tick.total}" else ""
        return mapOf("libs" to libs.toString(), "entries" to entries)
    }

    /** A patched APK that is about to be replaced, waiting for the user. */
    private val _replacePrompt = MutableStateFlow<File?>(null)
    val replacePrompt: StateFlow<File?> = _replacePrompt.asStateFlow()

    /** Called from the delete dialog: throw the old APK away and patch again. */
    fun confirmReplaceAndPatch(selectedComponentKeys: Set<String>? = null) {
        val old = _replacePrompt.value
        _replacePrompt.value = null
        if (old != null && old.exists() && !old.delete()) {
            _patch.value = PatchUiState.Failed("Could not delete ${old.name}")
            return
        }
        startPatch(selectedComponentKeys)
    }

    fun dismissReplacePrompt() {
        _replacePrompt.value = null
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
        val out = workFile("patched.apk")
        // A previous result is still sitting next to the new one. Ask before
        // replacing it, then carry on by itself - the user confirmed it.
        if (out.exists() && out.length() > 0L) {
            _replacePrompt.value = out
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            JobProgress.begin(app, JOB_PATCH, "Patching", "Analyzing the source APK…")
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                // The shell APK carries no game content, so the game directory
                // has to be complete before anything else happens: patching
                // first and installing the content after produced an APK whose
                // game booted into a menu with no shell graphics, no touch
                // controls and a silent sound dir.
                val gameDir = File(_installPath.value)
                // An APK that still carries assets/ needs nothing: the engine
                // will find the content inside the package. Only the shell -
                // which has an empty assets/ - has to pull it from the release.
                val extracted = if (sourceApkShipsAssets(src)) {
                    JobProgress.detail(app, JOB_PATCH, "Source APK ships the game content")
                    0
                } else {
                    ensureGameAssets(
                        gameDir = gameDir,
                        onStatus = { msg ->
                            JobProgress.detail(app, JOB_PATCH, msg)
                        },
                        onProgress = { done, total ->
                            JobProgress.progress(app, JOB_PATCH, done, total, "Downloading game content…")
                        },
                    )
                }
                if (extracted > 0) {
                    JobProgress.detail(app, JOB_PATCH, "Game content installed ($extracted files)")
                }
                applyGamedataAbiPolicy(gameDir, selAbi)
                val report = ApkPatcher.patch(
                    ApkPatcher.PatchRequest(src, out, effectiveBundle, keystore, keepAbi = selAbi),
                    onProgress = { tick ->
                        val prev = _patch.value as? PatchUiState.Running
                        JobProgress.detail(app, JOB_PATCH, patchStepLabel(tick))
                        val line = logLine(tick)
                        val log = (prev?.log.orEmpty() + listOfNotNull(line)).takeLast(200)
                        _patchLog.value = log
                        _patch.value = PatchUiState.Running(
                            step = tick.step,
                            progress = tick.fraction,
                            detail = tick.detail,
                            index = tick.index,
                            total = tick.total,
                            log = log,
                            counters = stepCounters(tick, prev),
                        )
                    },
                )
                lastReport = report
                val finished = _patchLog.value
                _patch.value = PatchUiState.Done(report, finished)
                persistPatchRun(selAbi, report, finished)
                JobProgress.finish(
                    app,
                    JOB_PATCH,
                    "Patched APK is ready",
                    apkPath = out.path,
                )
                if (installAfterPatch) {
                    installAfterPatch = false
                    _pendingInstall.value = installIntentFor(out)
                }
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed(t.message ?: "Unknown error")
                writePatchLog(selAbi, null, _patchLog.value, t.message ?: "Unknown error")
                JobProgress.finish(app, JOB_PATCH, t.message ?: "Patch failed", ok = false)
                installAfterPatch = false
            }
        }
    }

    fun outputApk(): File? = lastReport?.let { workFile("patched.apk") }

    /**
     * Client packages already installed on the device. Installing the patched
     * APK over an existing one fails when the signature differs, and users keep
     * several copies around, so Install asks before touching any of them.
     */
    fun installedClientPackages(): List<String> {
        val pm = app.packageManager
        return runCatching {
            pm.getInstalledPackages(0)
                .map { it.packageName }
                .filter { it != app.packageName && isClientPackage(it) }
                .sorted()
        }.getOrElse { emptyList() }
    }

    private fun isClientPackage(name: String): Boolean =
        name.contains("cs16client", ignoreCase = true) || name.contains("xash", ignoreCase = true)

    /** The packages Install is about to offer removing, if any. */
    private val _uninstallPrompt = MutableStateFlow<List<String>>(emptyList())
    val uninstallPrompt: StateFlow<List<String>> = _uninstallPrompt.asStateFlow()

    fun dismissUninstallPrompt() {
        _uninstallPrompt.value = emptyList()
    }

    /**
     * Install entry point: when an older client is still on the device the user
     * is asked to remove it first, and confirming launches the uninstall and the
     * patch in one go - the new APK is built without a second tap.
     */
    /** Install action to run once the uninstall is behind us, if any. */
    private val _pendingInstall = MutableStateFlow<Intent?>(null)
    val pendingInstall: StateFlow<Intent?> = _pendingInstall.asStateFlow()

    fun consumePendingInstall() {
        _pendingInstall.value = null
    }

    fun requestInstall() {
        val installed = installedClientPackages()
        if (installed.isNotEmpty()) {
            _uninstallPrompt.value = installed
            return
        }
        _pendingInstall.value = installIntent()
    }

    /** Set when a patch was started only so the install could follow it. */
    private var installAfterPatch = false

    /**
     * Dialog confirmed. The removal itself is left to the system package
     * installer, and the patched APK is installed right after it returns, so the
     * whole replace is one flow: remove, then install, no second tap.
     */
    fun confirmUninstallAndPatch() {
        val packages = _uninstallPrompt.value
        _uninstallPrompt.value = emptyList()
        for (pkg in packages) {
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        val out = outputApk()
        if (out != null && out.exists() && lastReport != null) {
            // What is on disk is still current, so it can be installed as it is.
            _pendingInstall.value = installIntentFor(out)
            return
        }

        // No usable APK yet: build it now and install the moment it is done.
        installAfterPatch = true
        startPatch()
    }

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
        _patchLog.value = emptyList()
        _patchCheck.value = PatchCheck.Idle
    }

    private fun persistPatchRun(abi: String, report: ApkPatcher.PatchReport, log: List<String>) {
        val entry = PatchHistoryEntry(
            time = System.currentTimeMillis(),
            abi = abi,
            source = report.sourceName,
            entries = report.addedEntries.size,
            libs = report.moduleLibs.size,
            verified = report.verification?.verified == true,
            ok = report.success,
        )
        val history = (listOf(entry) + _patchHistory.value).take(20)
        _patchHistory.value = history
        try {
            val file = File(_installPath.value, "patch-history.json")
            file.writeText(history.joinToString(",", prefix = "[", postfix = "]") { h ->
                "\"{\"time\":${h.time},\"abi\":\"${h.abi}\",\"source\":\"${h.source}\",\"entries\":${h.entries},\"libs\":${h.libs},\"verified\":${h.verified},\"ok\":${h.ok}}"
            })
        } catch (_: Throwable) {
        }
        writePatchLog(abi, report, log, null)
    }

    private fun writePatchLog(
        abi: String,
        report: ApkPatcher.PatchReport?,
        log: List<String>,
        error: String?,
    ) {
        try {
            val dir = File(_installPath.value)
            if (!dir.exists()) return
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val head = buildString {
                append("---- patch ").append(stamp).append(" abi=").append(abi).append(" ----\n")
                if (report != null) {
                    append("source=").append(report.sourceName)
                        .append(" entries=").append(report.addedEntries.size)
                        .append(" libs=").append(report.moduleLibs.size)
                        .append(" verified=").append(report.verification?.verified).append('\n')
                }
                if (error != null) append("error=").append(error).append('\n')
            }
            File(dir, "patch.log").appendText(head + log.joinToString("\n") + "\n")
        } catch (_: Throwable) {
        }
    }

    fun patchSummary(): PatchSummary? {
        val b = loadedBundle ?: return null
        val src = _receivedSource.value ?: return null
        val abi = _abi.value
        val payload = b.files.entries
            .filter { it.key.contains(abi) || it.key.startsWith("addons/") }
            .sumOf { it.value.size.toLong() }
        return PatchSummary(
            abi = abi,
            source = src.name,
            sourceSize = src.length(),
            components = b.manifest.entries.groupBy { componentKeyFor(it.target) }.size,
            entries = b.manifest.entries.size,
            payloadSize = payload,
        )
    }

    fun verifyPatchedApk() {
        val out = outputApk() ?: return
        if (!out.exists()) {
            _patchCheck.value = PatchCheck.Failed("patched.apk is missing")
            return
        }
        _patchCheck.value = PatchCheck.Running
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val abi = _abi.value
                val v = ApkSignerTool.verify(out)
                val zip = ZipRaw.open(out)
                val prefix = "lib/$abi/"
                val present = zip?.entries?.keys?.count { it.startsWith(prefix) && it.endsWith(".so") } ?: 0
                zip?.close()
                val expected = loadedBundle?.manifest?.entries?.count { it.target.startsWith(prefix) } ?: present
                val engine = File(_installPath.value, "engine.log")
                val commit = if (engine.exists()) {
                    engine.useLines { seq ->
                        seq.firstOrNull { it.contains("Commit hash") }?.trim()?.removePrefix("Commit hash  :")?.trim().orEmpty()
                    }
                } else ""
                _patchCheck.value = PatchCheck.Result(
                    signatureOk = v.verified,
                    usedV1 = v.usedV1,
                    usedV2 = v.usedV2,
                    expectedLibs = expected,
                    presentLibs = present,
                    engineCommit = commit,
                )
            } catch (t: Throwable) {
                _patchCheck.value = PatchCheck.Failed(t.message ?: "verification failed")
            }
        }
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
                val dest = workFile("update.apk")
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

    private val _iniText = MutableStateFlow("")
    val iniText: StateFlow<String> = _iniText.asStateFlow()
    private val _iniDirty = MutableStateFlow(false)
    val iniDirty: StateFlow<Boolean> = _iniDirty.asStateFlow()
    private val _iniSavedAt = MutableStateFlow(0L)
    val iniSavedAt: StateFlow<Long> = _iniSavedAt.asStateFlow()

    fun openPluginIni(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _iniText.value = try {
                if (file.exists()) file.readText() else ""
            } catch (t: Throwable) {
                "# could not read ${file.name}\n"
            }
            _iniDirty.value = false
        }
    }

    fun editIniText(text: String) {
        _iniText.value = text
        _iniDirty.value = true
    }

    fun savePluginIni(file: File) {
        val text = _iniText.value
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                file.parentFile?.mkdirs()
                file.writeText(text)
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                _iniDirty.value = false
                _iniSavedAt.value = System.currentTimeMillis()
                loadPluginInis()
            }
        }
    }

    fun revertPluginIni(file: File) = openPluginIni(file)

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

    /** Forgets the picked .sma folder; the watcher then has nothing to watch. */
    fun clearScriptRoot() {
        _scriptRoot.value = null
        _scripts.value = emptyList()
        compilerPrefs.edit().remove("script_root").apply()
    }

    /**
     * Looks for `<xash>/<gamedir>/addons/amxmodx/<sub>` and uses it, so the
     * user does not have to walk the SAF tree by hand: `scripting` for the
     * .sma source folder, `plugins` for the compiled output.
     *
     * cstrike and czero are checked first, then any other game directory that
     * happens to sit under <xash>. Returns false when nothing was found.
     */
    fun autoFindAmxxFolder(forOutput: Boolean): Boolean {
        val sub = if (forOutput) "plugins" else "scripting"
        val found = findAmxxFolder(sub) ?: return false
        if (forOutput) {
            _outputRoot.value = found.absolutePath
            compilerPrefs.edit().putString("output_root", found.absolutePath).apply()
        } else {
            _scriptRoot.value = found.absolutePath
            compilerPrefs.edit().putString("script_root", found.absolutePath).apply()
            refreshScripts()
        }
        return true
    }

    /** The folder Auto Find looks for, used in the messages. */
    fun autoFindTargetName(forOutput: Boolean): String =
        "addons/amxmodx/" + if (forOutput) "plugins" else "scripting"

    private fun findAmxxFolder(sub: String): File? {
        val gamesRoot = File(GAME_ROOT)
        if (!gamesRoot.isDirectory) return null

        val games = ArrayList<String>()
        games.add(GAME_CSTRIKE)
        games.add(GAME_CZERO)
        try {
            gamesRoot.listFiles()?.forEach { f ->
                if (f.isDirectory && !games.contains(f.name)) games.add(f.name)
            }
        } catch (_: Exception) {
            // listing denied, the known game dirs are still tried
        }

        for (game in games) {
            val dir = File(File(File(gamesRoot, game), "addons/amxmodx"), sub)
            if (dir.isDirectory) return dir
        }
        return null
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
     * Watches the picked scripting folder so a .sma dropped in from a file
     * manager shows up on its own. Plugins are edited outside the app, and
     * making the user hit refresh after every save is the kind of thing that
     * makes them stop using the screen at all.
     *
     * Only the Compiler screen has a scripting folder worth watching, so this is
     * called on screen entry and cancelled on leave; a poll that runs for the
     * whole process lifetime would keep the view model alive for nothing.
     */
    private var scriptWatchJob: Job? = null

    fun startScriptWatcher() {
        scriptWatchJob?.cancel()
        scriptWatchJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(SCRIPT_POLL_MS)
                if (_scriptRoot.value == null) continue
                // Re-reads the folder and assigns only when the result differs,
                // so an unchanged folder does not churn the list the user is
                // tapping in.
                val current = _scripts.value
                val updated = readScriptFolder()
                if (updated != current) {
                    _scripts.value = updated
                }
            }
        }
    }

    fun stopScriptWatcher() {
        scriptWatchJob?.cancel()
        scriptWatchJob = null
    }

    /** The .sma list of the current scripting folder, empty when unset. */
    private fun readScriptFolder(): List<SmaSource> {
        val dir = _scriptRoot.value?.let { File(it) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".sma", ignoreCase = true)
        }?.sortedBy { it.name }?.map { f ->
            SmaSource(
                path = f.absolutePath,
                name = f.name,
                hasInclude = File(f.parentFile, "include").isDirectory,
            )
        }.orEmpty()
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
            // Script Folder holds the folder the user picked for plugins
            // (e.g. .../amxmodx/scripting). Log files live in exactly
            // ScriptFolder/logs/ (only "logs/" appended, never another
            // "scripting", even if the picker already points at the
            // scripting folder).
            val scriptFolder = File(sources.first().path).parentFile
            val logDir = if (scriptFolder != null) File(scriptFolder, "logs") else null
            logDir?.mkdirs()

            // Every plugin is a separate amxxpc process, so compiling several at
            // once is real parallelism on the phone's cores. The worker count is
            // a user setting: more workers finish sooner but fight each other
            // for RAM and make the device hot, fewer keep it cool.
            val parallelism = effectiveCompileWorkers()
            val gate = Semaphore(parallelism)
            val finished = AtomicInteger(0)
            val results = arrayOfNulls<Pair<Boolean, String>>(total)

            coroutineScope {
                sources.mapIndexed { index, source ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val outcome = try {
                                compileOne(source)
                            } catch (t: Throwable) {
                                false to (t.message ?: "Compile error")
                            }
                            results[index] = outcome
                            val done = finished.incrementAndGet()
                            _compile.value = CompileState.Compiling(
                                "$done of $total compiled (${source.name})"
                            )
                        }
                    }
                }.awaitAll()
            }

            // The log is written in the order the user selected, not in the
            // order the compiles happened to finish, so two runs over the same
            // folder produce the same compiler.log.
            val log = StringBuilder()
            val failures = LinkedHashMap<String, String>()
            var failed = 0
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            for ((i, source) in sources.withIndex()) {
                val (ok, body) = results[i] ?: (false to "Compile did not run.")
                log
                    .append("── ${source.name} ──\n")
                    .append(body.trim().ifEmpty { if (ok) "Done." else "Compile failed." })
                    .append('\n')
                    .append('\n')
                val entry = "── ${source.name} ──\n${body.trim()}\n\n"
                val target = if (ok) {
                    logDir?.let { File(it, "compiler.log") }
                } else {
                    logDir?.let { File(it, "error.log") }
                }
                if (target != null) {
                    try {
                        target.appendText(
                            if (ok) entry
                            else "===== ${stamp.format(java.util.Date())} ${source.name} =====\n$entry"
                        )
                    } catch (_: Throwable) {}
                }
                if (!ok) {
                    failed++
                    failures[source.path] = body.trim().ifEmpty { "Compile failed." }
                }
            }
            val okCount = total - failed
            val summary = "\n=== $okCount ok, $failed failed ($parallelism at a time) ==="
            _compile.value = CompileState.Done(log.toString() + summary, failures)
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
        val gameDir = File(_installPath.value)
        val amxxPluginsDir = File(gameDir, "addons/amxmodx/plugins")
        val scriptDirName = scriptDir.name
        val scriptIsShipped = scriptDirName == "scripting" &&
            scriptDir.parentFile?.parentFile?.name == "amxmodx" &&
            gameDir.exists()
        val compiledDir = _outputRoot.value?.let { File(it) }?.takeIf { it.isDirectory }
            ?: if (scriptIsShipped) amxxPluginsDir else File(scriptDir, "compiled")
        compiledDir.mkdirs()
        val outPath = File(compiledDir, f.nameWithoutExtension + ".amxx").absolutePath
        cmd.add("-o$outPath")
        cmd.add(source.path)
        // Ensure compiler dir is in LD_LIBRARY_PATH so driver finds amxxpc32.so
        // (driver does dlopen("amxxpc32.so") / dlopen("./amxxpc32.so"))
        val compilerDir = amxxpc.parentFile
        // Run with CWD = the driver's own directory: the driver probes
        // "./amxxpc32.so" FIRST, so this pins it to the kernel sitting next
        // to the driver itself instead of a stale copy next to the scripts
        // (which segfaults the driver silently before its banner flushes).
        // All args below are absolute, so CWD is irrelevant otherwise.
        val workDir = if (compilerDir != null && compilerDir.isDirectory) compilerDir else scriptDir
        // Pre-flight: a missing kernel would kill the driver with zero
        // output — report it plainly instead. The kernel may sit next to
        // the driver or in filesDir/compiler (which is on LD_LIBRARY_PATH).
        val kernelBesideDriver = if (compilerDir != null) File(compilerDir, "amxxpc32.so") else null
        val filesKernel = File(compilerHome(), "amxxpc32.so")
        val kernelFound = (kernelBesideDriver != null && kernelBesideDriver.exists()) ||
            (filesKernel.exists() && matchesDeviceAbi(filesKernel))
        if (!kernelFound) {
            error(
                "Compiler kernel missing.\n" +
                    "Driver: ${amxxpc.absolutePath} (${amxxpc.length()} bytes)\n" +
                    "Looked in: ${kernelBesideDriver?.absolutePath ?: "(unknown)"} and ${filesKernel.absolutePath}\n" +
                    "Reinstall the app or re-download the bundle, then retry."
            )
        }
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        // Loader search path: driver's own dir first, then filesDir/compiler
        // (provisioned kernel lives there when the APK lacks it).
        val ldDirs = listOfNotNull(
            compilerDir?.takeIf { it.isDirectory }?.absolutePath,
            compilerHome().takeIf { it.isDirectory }?.absolutePath,
        ).distinct()
        if (ldDirs.isNotEmpty()) {
            val oldLd = pb.environment()["LD_LIBRARY_PATH"]
            pb.environment()["LD_LIBRARY_PATH"] = (ldDirs + listOfNotNull(oldLd?.takeIf { it.isNotEmpty() })).joinToString(":")
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
            if (output.isBlank() && exit != 0) {
                // Silent death (e.g. driver/kernel mismatch crashes before the
                // banner flushes): record binary sizes for diagnosis.
                val kernel = amxxpc.parentFile?.let { File(it, "amxxpc32.so") }
                append("\n[no output, exit=$exit, driver=${amxxpc.length()} bytes" +
                    (if (kernel != null && kernel.exists()) ", kernel=${kernel.length()} bytes" else ", no kernel") +
                    "]")
            }
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

    /** ELF e_machine of a binary, or null if unreadable / not an ELF. */
    private fun elfMachine(file: File): Int? = try {
        file.inputStream().use { ins ->
            val hdr = ByteArray(20)
            if (ins.read(hdr) < 20) return null
            if (!(hdr[0] == 0x7F.toByte() && hdr[1] == 'E'.code.toByte() &&
                    hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte())
            ) return null
            ((hdr[19].toInt() and 0xFF) shl 8) or (hdr[18].toInt() and 0xFF)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * True if [file] is an ELF matching this device's primary ABI
     * (EM_AARCH64=183 on 64-bit, EM_ARM=40 on 32-bit). Stale/wrong-arch
     * amxxpc copies (e.g. arm64 binary on an arm32 device) are rejected
     * instead of executed.
     */
    private fun matchesDeviceAbi(file: File): Boolean {
        val machine = elfMachine(file) ?: return false
        val want64 = Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        return if (want64) machine == 183 else machine == 40
    }

    private fun compilerHome(): File = File(getApplication<Application>().filesDir, "compiler")

    private fun readCompilerStamp(): String? = try {
        File(compilerHome(), ".stamp").takeIf { it.exists() }?.readText()?.trim()
    } catch (_: Throwable) {
        null
    }

    private fun writeCompilerStamp(s: String) {
        try {
            compilerHome().mkdirs()
            File(compilerHome(), ".stamp").writeText(s)
        } catch (_: Throwable) {}
    }

    private fun appVersionCode(): Long = try {
        val app = getApplication<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Throwable) {
        0L
    }

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
                // Driver dlopens "amxxpc32.so" (no lib prefix) — keep a copy
                // in filesDir. Driver+kernel must always be the same
                // generation: a stale mismatched kernel crashes the driver
                // before its banner flushes (silent "Compile failed"), so the
                // pair is version-stamped and reinstalled atomically.
                try {
                    val dir = compilerHome()
                    dir.mkdirs()
                    val kernelCopy = File(dir, "amxxpc32.so")
                    val stamp = "native:${appVersionCode()}:${if (nativeKernel.exists()) nativeKernel.length() else -1}"
                    if (nativeKernel.exists() && (readCompilerStamp() != stamp || !matchesDeviceAbi(kernelCopy))) {
                        kernelCopy.delete()
                        kernelCopy.writeBytes(nativeKernel.readBytes())
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernelCopy.setReadable(true, false)
                        writeCompilerStamp(stamp)
                    }
                    // Old APKs ship the driver without the kernel next to it:
                    // provision filesDir from bundle bytes (the loader finds
                    // it via LD_LIBRARY_PATH set at exec time).
                    if (!nativeKernel.exists() && !matchesDeviceAbi(kernelCopy)) {
                        val kb = try {
                            (loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value))
                                ?.files?.get("compiler/amxxpc32.so")
                        } catch (_: Throwable) {
                            null
                        }
                        if (kb != null && kb.isNotEmpty()) {
                            kernelCopy.delete()
                            kernelCopy.writeBytes(kb)
                            if (matchesDeviceAbi(kernelCopy)) {
                                try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                                kernelCopy.setReadable(true, false)
                                writeCompilerStamp("bundled-kernel:${kb.size}")
                            } else {
                                kernelCopy.delete()
                            }
                        }
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
            loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value)
        } catch (_: Throwable) {
            null
        }
        val driverBytes = bundleFiles?.files?.get("compiler/amxxpc")
        if (driverBytes != null && driverBytes.isNotEmpty()) {
            try {
                compilerDir.mkdirs()
                val kernelBytes = bundleFiles.files["compiler/amxxpc32.so"]
                val stamp = "bundle:${driverBytes.size}:${kernelBytes?.size ?: -1}"
                if (readCompilerStamp() == stamp && amxxpc.exists() && matchesDeviceAbi(amxxpc) &&
                    (kernelBytes == null || (kernel.exists() && matchesDeviceAbi(kernel)))
                ) {
                    return amxxpc
                }
                amxxpc.writeBytes(driverBytes)
                // Reject wrong-arch binaries (e.g. arm64 driver from an old
                // bundle on an arm32 device) instead of executing them.
                if (!matchesDeviceAbi(amxxpc)) {
                    amxxpc.delete()
                } else {
                    // chmod 755 via shell is more reliable than File.setExecutable alone
                    // (some OEMs / SELinux ignore the Java API). Do both.
                    try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
                    amxxpc.setExecutable(true, false)
                    amxxpc.setReadable(true, false)
                    bundleFiles.files["compiler/amxxpc32.so"]?.let {
                        if (it.isNotEmpty()) {
                            kernel.writeBytes(it)
                            if (!matchesDeviceAbi(kernel)) {
                                kernel.delete()
                            } else {
                                try { Runtime.getRuntime().exec(arrayOf("chmod", "755", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                                kernel.setReadable(true, false)
                                // kernel is dlopened, not executed, but needs r+x for some loaders
                                try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                            }
                        }
                    }
                    if (amxxpc.exists()) {
                        writeCompilerStamp(stamp)
                        return amxxpc
                    }
                }
            } catch (_: Throwable) {
                // extraction failed; fall through to local
            }
        }
        // fallback: a compiler already present in the picked/script folder
        // (ABI-verified — never execute a wrong-arch binary)
        val root = _scriptRoot.value?.let { File(it) }
        return listOfNotNull(root, fallbackDir)
            .map { File(it, "amxxpc") }
            .firstOrNull { it.exists() && it.canExecute() && matchesDeviceAbi(it) }
    }

    companion object {
        /** How often the picked scripting folder is re-read while the screen is open. */
        const val SCRIPT_POLL_MS = 3_000L

        const val JOB_CLIENT_APK = "client-apk"
        const val JOB_LIBS = "libs"
        const val JOB_ADDONS = "addons"
        const val JOB_PATCH = "patch"
        const val CACHE_TAG = "v2"
        const val GAME_DIR = "/storage/emulated/0/xash/cstrike"
        /** Xash base dir; supported games live directly under it. */
        const val GAME_ROOT = "/storage/emulated/0/xash"
        const val GAME_CSTRIKE = "cstrike"
        const val GAME_CZERO = "czero"
        /** ABIs the patcher can build for, in priority order. */
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")

        /**
         * Slack for the "is the cached APK older than the release" check: files
         * copied over USB or written by hand can easily end up a minute or two
         * off without meaning anything.
         */
        const val ASSET_DATE_SLOP = 5 * 60 * 1000L
        /** Suffix that keeps the non-matching gamedata variant out of AMXX's *.txt scan. */
        const val GAMEDATA_DISABLED_SUFFIX = ".disabled"
        /** Per-ABI gamedata override files: offsets-cstrike-replugged.<arm64|arm32>.txt */
        val GAMEDATA_VARIANT = Regex("offsets-cstrike-replugged\\.(arm64|arm32)\\.txt")
        /** Pre-per-ABI override name; removed on sight so it cannot keep applying. */
        const val LEGACY_GAMEDATA_NAME = "offsets-cstrike-replugged.txt"
        /** Releases (tags + patcher APK) are published here by CI. */
        const val APP_RELEASE_REPO = "berkchy/nexora"
        /**
         * The client app itself is built by the vcs16 repository and published
         * under the same rolling Continuous tag. The patcher can fetch that
         * APK directly instead of asking the user to locate it on the device.
         */
        const val CLIENT_APK_REPO = "berkchy/vcs16"
        const val CLIENT_APK_ASSET = "CS16Client-Android.apk"
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

            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value) ?: return@launch
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
            applyGamedataAbiPolicy(gameDir, _abi.value)
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

            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value)
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