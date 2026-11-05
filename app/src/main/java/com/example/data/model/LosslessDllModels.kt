package com.example.data.model

/**
 * Metadata and analysis of an imported Windows Portable Executable (PE) DLL file,
 * specifically tailored for Lossless Scaling Frame Generation (lossless.dll / LSFG).
 */
data class LosslessDllInfo(
    val isImported: Boolean = false,
    val isEnabled: Boolean = false,
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val lastImportedTimestampMs: Long = 0L,
    val md5Checksum: String = "",
    val sha256Checksum: String = "",
    val architecture: String = "", // e.g. "x86_64 (PE32+ 64-bit)"
    val machineTypeHex: String = "", // e.g. "0x8664"
    val peTimestamp: String = "",
    val detectedProfile: String = "", // e.g. "Lossless Scaling Frame Generation (LSFG 2.x/3.x) Core"
    val isOfficialLsfgSignature: Boolean = false,
    val numberOfSections: Int = 0,
    val sectionNames: List<String> = emptyList(), // e.g. [".text", ".rdata", ".data", ".pdata", ".reloc"]
    val exportedFunctions: List<String> = emptyList(), // e.g. ["LSFG_Initialize", "LSFG_GenerateFrame", ...]
    val antiGhostingShieldActive: Boolean = true,
    val subpixelBicubicSampling: Boolean = true,
    val lowLatencyReflexPacing: Boolean = true,
    val activeMultiplier: Float = 2.0f,
    val optimizationTierNotes: String = ""
)

/**
 * Detailed binary inspection data from PE headers for display in developer inspection dialog.
 */
data class LosslessPeInspection(
    val magicHeader: String,
    val peSignature: String,
    val machineType: String,
    val numberOfSections: Int,
    val timeDateStampRaw: Long,
    val timeDateStampFormatted: String,
    val characteristicsHex: String,
    val isDll: Boolean,
    val optionalHeaderMagic: String,
    val imageBaseHex: String,
    val subsystem: String,
    val sectionHeaders: List<LosslessSectionInfo>,
    val exportTableAddress: String,
    val exportTableSize: String,
    val detectedTokens: List<String>
)

data class LosslessSectionInfo(
    val name: String,
    val virtualSize: Long,
    val virtualAddress: String,
    val sizeOfRawData: Long,
    val characteristics: String
)
