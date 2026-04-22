package com.ismartcoding.plain.tunnel

import android.content.Context
import com.ismartcoding.lib.logcat.LogCat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object AssetExtractor {
    private const val ASSET_NAME = "cloudflared"
    private const val BINARY_NAME = "cloudflared"

    fun extractBinary(context: Context): File? {
        val filesDir = context.filesDir
        val binaryFile = File(filesDir, BINARY_NAME)

        addLog("Binary path: ${binaryFile.absolutePath}")
        addLog("Exists: ${binaryFile.exists()}")
        addLog("Executable before: ${binaryFile.canExecute()}")

        if (binaryFile.exists() && ensureExecutable(binaryFile)) {
            addLog("Cloudflared binary is ready to execute")
            return binaryFile
        }

        return try {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(binaryFile).use { output ->
                    input.copyTo(output)
                }
            }

            addLog("Copied cloudflared asset to internal storage")
            if (!ensureExecutable(binaryFile)) {
                throw IOException("Failed to make cloudflared executable after extraction")
            }

            addLog("Cloudflared binary extracted and made executable: ${binaryFile.absolutePath}")
            binaryFile
        } catch (e: IOException) {
            LogCat.e("Failed to extract cloudflared binary: ${e.message}")
            null
        }
    }

    private fun ensureExecutable(binaryFile: File): Boolean {
        addLog("Setting executable permission...")
        addLog("Executable before: ${binaryFile.canExecute()}")

        try {
            if (binaryFile.setExecutable(true, false)) {
                addLog("setExecutable(true, false) returned true")
            } else {
                addLog("setExecutable(true, false) returned false")
            }
        } catch (e: Exception) {
            LogCat.e("setExecutable failed: ${e.message}")
        }

        if (binaryFile.canExecute()) {
            addLog("Executable after setExecutable: true")
            return true
        }

        addLog("Applying fallback chmod 700...")
        val chmodCommand = arrayOf("chmod", "700", binaryFile.absolutePath)
        try {
            val process = Runtime.getRuntime().exec(chmodCommand)
            val exitCode = process.waitFor()
            addLog("chmod exit code: $exitCode")
            logProcessStreams(process.inputStream, process.errorStream)
        } catch (e: IOException) {
            LogCat.e("Fallback chmod failed: ${e.message}")
        }

        val executableAfter = binaryFile.canExecute()
        addLog("Executable after chmod: $executableAfter")
        return executableAfter
    }

    private fun logProcessStreams(inputStream: InputStream, errorStream: InputStream) {
        readStream(inputStream)?.let { output ->
            if (output.isNotBlank()) {
                addLog("chmod stdout: $output")
            }
        }
        readStream(errorStream)?.let { error ->
            if (error.isNotBlank()) {
                LogCat.e("chmod stderr: $error")
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
            LogCat.e("Failed to read stream: ${e.message}")
            null
        }
    }

    private fun addLog(message: String) {
        LogCat.d("Cloudflared: $message")
    }
}