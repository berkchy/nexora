package com.pickle.patcher.lib

import java.io.File

/**
 * Desktop/CI CLI entry point (not shipped in the Android app; kept for local iteration).
 *
 * Usage:
 *   java -jar patcherlib.jar --source base.apk --bundle bundle.zip --keystore debug.keystore --out patched.apk
 *
 * The bundle may also be a plain directory containing `bundle.json` (for hand-tuned test bundles).
 */
object Cli {

    @JvmStatic
    fun main(args: Array<String>) {
        var source: File? = null
        var bundleInput: File? = null
        var assetsZip: File? = null
        var keystore: File? = null
        var out: File? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--source" -> source = File(args[++i])
                "--bundle" -> bundleInput = File(args[++i])
                "--assets" -> assetsZip = File(args[++i])
                "--keystore" -> keystore = File(args[++i])
                "--out" -> out = File(args[++i])
                else -> throw IllegalArgumentException("Unknown arg: ${args[i]}")
            }
            i++
        }

        requireNotNull(source) { "--source required" }
        requireNotNull(bundleInput) { "--bundle required" }
        requireNotNull(keystore) { "--keystore required" }
        requireNotNull(out) { "--out required" }

        val bundle = if (bundleInput.name.endsWith(".zip")) {
            Bundle.fromZip(bundleInput.readBytes())
        } else {
            loadBundleFromDir(bundleInput)
        }
        val signer = SigningKeystore.load(keystore)

        println("source   : ${source.length() / 1048576.0} MB, ${ZipAnalyzer.analyze(source).entryCount} entries")
        println("bundle   : ${bundle.manifest.entries.size} entries, ver=${bundle.manifest.version}")
        println("signer   : ${signer.fingerprintSha256()}")

        val report = ApkPatcher.patch(
            ApkPatcher.PatchRequest(source, out, bundle, signer, assetsZip = assetsZip),
            onProgress = { t -> println("  step ${t.step.name} ${(t.fraction * 100).toInt()}% ${t.detail}") },
        )
        println("OK  verified=${report.verification?.verified}")
        println("    removed=${report.removedEntries.size} added=${report.addedEntries.size} kept=${report.keptCount}")
        println("    game content files in assets/: ${report.addedAssetFiles}")
        println("    arsc stored=${report.arscStored} aligned=${report.arscAligned}")
        println("    output ${report.outputSizeMb} MB  signer=${report.verification?.signerFingerprintSha256}")
        println("    errors=${report.verification?.errors}")
        if (!report.success) {
            println("PATCH FAILED")
            kotlin.system.exitProcess(1)
        }
    }

    private fun loadBundleFromDir(dir: File): Bundle {
        val manifestFile = File(dir, "bundle.json")
        val manifest = BundleManifest.parse(manifestFile.readText())
        val files = HashMap<String, ByteArray>()
        for (e in manifest.entries) {
            val f = File(dir, e.source)
            if (f.exists()) files[e.source] = f.readBytes()
        }
        return Bundle(manifest, files)
    }
}