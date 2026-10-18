package com.pickle.patcher.lib

import java.io.File
import java.io.RandomAccessFile

/**
 * Enough of an ELF header to know whether Android will load this file from the
 * APK we are about to build.
 *
 * Everything here reads the first few dozen bytes. That is deliberate: the
 * check runs on every candidate the user picks, on a phone, and a library that
 * is not ELF at all is the case worth catching fastest.
 */
data class ElfInfo(
    val is64Bit: Boolean,
    val littleEndian: Boolean,
    /** e_type: 2 = executable, 3 = shared object. */
    val type: Int,
    /** e_machine: 40 = ARM, 62/183 = AArch64, 3 = x86, 62 = x86-64. */
    val machine: Int,
) {
    val isSharedObject: Boolean get() = type == ET_DYN

    /** e.g. "AArch64", for the message shown when the library is refused. */
    val machineName: String
        get() = when (machine) {
            EM_ARM -> "ARM (32-bit)"
            EM_AARCH64 -> "AArch64 (64-bit ARM)"
            EM_386 -> "x86 (32-bit)"
            EM_X86_64 -> "x86-64"
            else -> "machine 0x${machine.toString(16)}"
        }

    companion object {
        const val ET_DYN = 3

        /** e_machine values. AArch64 is 183; 62 is x86-64, which is the trap. */
        const val EM_ARM = 40
        const val EM_AARCH64 = 183
        const val EM_386 = 3
        const val EM_X86_64 = 62

        /**
         * What a given Android ABI requires. Returns the 32/64-bit, endianness
         * and e_machine the library has to have to be loadable from
         * lib/<abi>/ in an APK.
         */
        fun expectedFor(abi: String): Pair<Boolean, Int>? = when (abi) {
            "arm64-v8a" -> true to EM_AARCH64
            "armeabi-v7a" -> false to EM_ARM
            "x86" -> false to EM_386
            "x86_64" -> true to EM_X86_64
            else -> null
        }
    }
}

/** Why a file cannot be embedded, or null when it can. */
sealed class ElfVerdict {
    object Ok : ElfVerdict()
    data class Rejected(val reason: String) : ElfVerdict()
}

/**
 * Reads [file]'s ELF header and checks it against [abi].
 *
 * A wrong-architecture library is refused rather than warned about: putting a
 * 32-bit .so in lib/arm64-v8a/ does not produce a library that fails to load,
 * it produces an APK the installer rejects outright, which looks like the
 * patcher's fault and costs the user a reinstall to recover from.
 */
fun validateElf(file: File, abi: String): ElfVerdict {
    val expected = ElfInfo.expectedFor(abi) ?: return ElfVerdict.Rejected("Unknown ABI $abi")

    val info = try {
        readElfInfo(file)
    } catch (e: Throwable) {
        return ElfVerdict.Rejected(e.message ?: "Could not read the file")
    } ?: return ElfVerdict.Rejected("Not an ELF file")

    if (!info.isSharedObject) {
        return ElfVerdict.Rejected(
            "e_type is ${info.type}, not a shared object - Android cannot dlopen it"
        )
    }
    if (!info.littleEndian) {
        return ElfVerdict.Rejected("Big-endian ELF; Android is always little-endian")
    }
    if (info.is64Bit != expected.first || info.machine != expected.second) {
        val want = if (expected.first) "64-bit ARM (AArch64)" else when (abi) {
            "armeabi-v7a" -> "32-bit ARM"
            "x86" -> "32-bit x86"
            else -> "64-bit x86"
        }
        return ElfVerdict.Rejected(
            "Built for ${info.machineName}; $abi needs $want"
        )
    }
    return ElfVerdict.Ok
}

/** The header, or null when the file is not ELF. Throws if it cannot be read. */
fun readElfInfo(file: File): ElfInfo? {
    if (!file.isFile || file.length() < 64) return null
    return RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(64)
        raf.readFully(head)

        if (!(head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte())
        ) {
            return@use null
        }
        val is64 = head[4].toInt() == 2
        val little = when (head[5].toInt()) {
            1 -> true
            2 -> false
            else -> return@use null
        }

        // e_type and e_machine are 2 bytes each at 16 and 18. Same byte order
        // as the file, which is the only one the APK loader ever accepts.
        fun u16(at: Int): Int {
            val a = head[at].toInt() and 0xFF
            val b = head[at + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }

        ElfInfo(
            is64Bit = is64,
            littleEndian = little,
            type = u16(16),
            machine = u16(18),
        )
    }
}