package com.ismartcoding.plain.tunnel

import android.content.Context
import android.os.Build
import com.ismartcoding.lib.logcat.LogCat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

object AssetExtractor {
    private const val ASSET_NAME = "cloudflared"
    private const val BINARY_NAME = "cloudflared"

    fun extractBinary(context: Context): File? {
        // Log device information
        logDeviceInfo()

        // Try multiple extraction locations
        val locations = listOf(
            context.filesDir,
            context.codeCacheDir,
            context.cacheDir
        )

        for (location in locations) {
            val binaryFile = File(location, BINARY_NAME)
            addLog("Trying location: ${binaryFile.absolutePath}")

            try {
                val file = extractToLocation(context, binaryFile)
                if (file != null && verifyBinary(file)) {
                    addLog("Successfully extracted and verified binary at: ${file.absolutePath}")
                    return file
                }
            } catch (e: Exception) {
                addLog("Failed to extract to ${location.name}: ${e.message}")
            }
        }

        addLog("All extraction locations failed")
        return null
    }

    private fun logDeviceInfo() {
        addLog("Device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        addLog("Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        addLog("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        addLog("Device: ${Build.DEVICE}")
        addLog("Manufacturer: ${Build.MANUFACTURER}")
    }

    private fun extractToLocation(context: Context, binaryFile: File): File? {
        // Create directory if needed
        binaryFile.parentFile?.mkdirs()

        // If file exists and is valid, return it
        if (binaryFile.exists() && verifyBinary(binaryFile)) {
            addLog("Binary already exists and is valid at: ${binaryFile.absolutePath}")
            return binaryFile
        }

        // Extract from assets
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(binaryFile).use { output ->
                    input.copyTo(output)
                }
            }

            addLog("Extracted binary to: ${binaryFile.absolutePath}")
            addLog("File size: ${binaryFile.length()} bytes")

            // Make executable
            if (!ensureExecutable(binaryFile)) {
                throw IOException("Failed to make binary executable")
            }

            binaryFile
        } catch (e: IOException) {
            LogCat.e("Failed to extract binary: ${e.message}")
            null
        }
    }

    private fun ensureExecutable(binaryFile: File): Boolean {
        addLog("Setting executable permission...")
        addLog("Executable before: ${binaryFile.canExecute()}")

        // Try File.setExecutable first
        try {
            val result = binaryFile.setExecutable(true, false)
            addLog("setExecutable result: $result")
        } catch (e: Exception) {
            addLog("setExecutable exception: ${e.message}")
        }

        if (binaryFile.canExecute()) {
            addLog("Binary is now executable")
            return true
        }

        // Fallback to chmod
        addLog("Trying chmod fallback...")
        return runChmod(binaryFile)
    }

    private fun runChmod(binaryFile: File): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("chmod", "700", binaryFile.absolutePath))
            val exitCode = process.waitFor()
            addLog("chmod exit code: $exitCode")

            // Log output
            logProcessStreams(process.inputStream, process.errorStream)

            val executable = binaryFile.canExecute()
            addLog("Executable after chmod: $executable")
            executable
        } catch (e: Exception) {
            addLog("chmod failed: ${e.message}")
            false
        }
    }

    private fun verifyBinary(binaryFile: File): Boolean {
        if (!binaryFile.exists()) {
            addLog("Binary file does not exist")
            return false
        }

        if (binaryFile.length() == 0L) {
            addLog("Binary file is empty")
            return false
        }

        // Check if it's an ELF file
        if (!isElfFile(binaryFile)) {
            addLog("Binary is not a valid ELF file")
            return false
        }

        // Try to execute a simple command to test
        if (!testExecution(binaryFile)) {
            addLog("Binary execution test failed")
            return false
        }

        addLog("Binary verification passed")
        return true
    }

    private fun isElfFile(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.read(magic)
                // ELF magic number: 0x7F 'E' 'L' 'F'
                magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            addLog("Failed to check ELF magic: ${e.message}")
            false
        }
    }

    private fun testExecution(binaryFile: File): Boolean {
        return try {
            // Try to run with --help or --version to test execution
            val process = Runtime.getRuntime().exec(arrayOf(binaryFile.absolutePath, "--version"))
            val exitCode = process.waitFor()
            addLog("Test execution exit code: $exitCode")

            // Log any output
            logProcessStreams(process.inputStream, process.errorStream)

            exitCode == 0
        } catch (e: Exception) {
            addLog("Test execution failed: ${e.message}")
            false
        }
    }

    private fun logProcessStreams(inputStream: InputStream, errorStream: InputStream) {
        readStream(inputStream)?.let { output ->
            if (output.isNotBlank()) {
                addLog("Process stdout: $output")
            }
        }
        readStream(errorStream)?.let { error ->
            if (error.isNotBlank()) {
                addLog("Process stderr: $error")
            }
        }
    }

    private fun readStream(stream: InputStream): String? {
        return try {
            ByteArrayOutputStream().use { buffer ->
                stream.copyTo(buffer)
                buffer.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            addLog("Failed to read stream: ${e.message}")
            null
        }
    }

    private fun addLog(message: String) {
        LogCat.d("Cloudflared: $message")
    }
}