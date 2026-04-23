package com.ismartcoding.plain.tunnel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelManager {
    init {
        System.loadLibrary("tunnel")
        System.loadLibrary("cloudflared")
    }

    external fun startTunnel(token: String): Int
    external fun setLogCallback()

    fun onNativeLog(message: String) {
        addLog(message)
    }
    private const val TOKEN = "eyJhIjoiNzk4MDRjYzVhNTdhMGFjZTVkZDA4NmZhMDdkOTc2NTAiLCJ0IjoiODhiNjc0MTMtNjUyMi00YTMyLWJiZjItYTc4NmMxNjc3ZWU5IiwicyI6IllXVTVOVFUzTm1RdFlUWXhaQzAwTkdZMExUbGhaVGt0TkRVNVpXWmtZV0ptTmpoaSJ9"

    val maskedToken: String
        get() = "Token: ${TOKEN.take(6)}...${TOKEN.takeLast(4)}"

    private var process: Process? = null
    private var job: Job? = null
    private var isRunning = false
    private val logBuffer = StringBuilder()
    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val isTunnelRunning: Boolean
        get() = isRunning && process?.isAlive == true

    // Public method for external classes to add logs
    fun addToLogs(logLine: String) {
        synchronized(logBuffer) {
            logBuffer.append(logLine)
            // Keep only last 1000 lines to prevent memory issues
            val lines = logBuffer.toString().split("\n")
            if (lines.size > 1000) {
                logBuffer.setLength(0)
                logBuffer.append(lines.takeLast(1000).joinToString("\n"))
            }
        }
        _logs.value = logBuffer.toString()
    }

    private fun addLog(message: String, isError: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val prefix = if (isError) "[ERROR]" else "[INFO]"
        val logLine = "$timestamp $prefix $message\n"

        synchronized(logBuffer) {
            logBuffer.append(logLine)
            // Keep only last 1000 lines to prevent memory issues
            val lines = logBuffer.toString().split("\n")
            if (lines.size > 1000) {
                logBuffer.setLength(0)
                logBuffer.append(lines.takeLast(1000).joinToString("\n"))
            }
        }

        _logs.value = logBuffer.toString()
        LogCat.d("Tunnel: $message")
    }

    private fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.setLength(0)
        }
        _logs.value = ""
    }

    fun startTunnel(context: Context): Boolean {
        if (isTunnelRunning) {
            addLog("Tunnel is already running")
            return true
        }

        clearLogs()
        addLog("Starting Cloudflare tunnel...")

        addLog("Using token: $maskedToken")

        // Set up log callback
        setLogCallback()

        // Call native library
        val result = startTunnel(TOKEN)
        if (result != 0) {
            addLog("Failed to start tunnel, exit code: $result", true)
            return false
        }

        isRunning = true
        addLog("Tunnel started successfully")

        // Start foreground service
        val intent = Intent(context, TunnelService::class.java)
        ContextCompat.startForegroundService(context, intent)

        true
    }

    private fun tryDirectExecution(binaryFile: File): Process? {
        return try {
            addLog("Trying direct execution...")
            val processBuilder = ProcessBuilder(
                binaryFile.absolutePath,
                "tunnel",
                "run",
                "--token",
                TOKEN
            ).apply {
                redirectErrorStream(false)
                // Set environment variables that might help
                environment()["LD_LIBRARY_PATH"] = "/system/lib64:/system/lib"
            }

            val process = processBuilder.start()
            addLog("Direct execution successful")
            process
        } catch (e: IOException) {
            addLog("Direct execution failed: ${e.message}")
            null
        }
    }

    private fun tryShellExecution(binaryFile: File): Process? {
        return try {
            addLog("Trying shell execution fallback...")

            // Method 1: Use sh -c
            val shellCommand = "/system/bin/sh -c \"${binaryFile.absolutePath} tunnel run --token $TOKEN\""
            addLog("Shell command: $shellCommand")

            val processBuilder = ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "${binaryFile.absolutePath} tunnel run --token $TOKEN"
            ).apply {
                redirectErrorStream(false)
                environment()["LD_LIBRARY_PATH"] = "/system/lib64:/system/lib"
            }

            val process = processBuilder.start()
            addLog("Shell execution successful")
            process
        } catch (e: IOException) {
            addLog("Shell execution failed: ${e.message}")

            // Method 2: Try different shell paths
            try {
                addLog("Trying alternative shell path...")
                val processBuilder = ProcessBuilder(
                    "sh",
                    "-c",
                    "${binaryFile.absolutePath} tunnel run --token $TOKEN"
                ).apply {
                    redirectErrorStream(false)
                }

                val process = processBuilder.start()
                addLog("Alternative shell execution successful")
                process
            } catch (e2: IOException) {
                addLog("Alternative shell execution also failed: ${e2.message}")
                null
            }
        }
    }

    fun stopTunnel() {
        addLog("Stopping tunnel...")
        job?.cancel()
        job = null

        process?.destroy()
        process = null
        isRunning = false

        addLog("Tunnel stopped")
    }

    private suspend fun monitorProcess() {
        val proc = this.process ?: return

        try {
            coroutineScope {
                // Monitor stdout
                launch {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            var line = reader.readLine()
                            while (line != null && isActive) {
                                addLog("STDOUT: $line")
                                line = reader.readLine()
                            }
                        }
                    } catch (e: IOException) {
                        addLog("Error reading stdout: ${e.message}", true)
                    }
                }

                // Monitor stderr
                launch {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                            var line = reader.readLine()
                            while (line != null && isActive) {
                                val isError = line.lowercase().contains("error") ||
                                            line.lowercase().contains("failed") ||
                                            line.lowercase().contains("connection refused") ||
                                            line.lowercase().contains("invalid") ||
                                            line.lowercase().contains("timeout")
                                addLog("STDERR: $line", isError)
                                line = reader.readLine()
                            }
                        }
                    } catch (e: IOException) {
                        addLog("Error reading stderr: ${e.message}", true)
                    }
                }
            }

            // Wait for process to complete
            val exitCode = proc.waitFor()
            addLog("Process exited with code: $exitCode", exitCode != 0)

            if (exitCode != 0) {
                addLog("Tunnel connection failed (exit code: $exitCode)", true)
            }

        } catch (e: Exception) {
            addLog("Error monitoring process: ${e.message}", true)
        } finally {
            isRunning = false
        }
    }

    fun getConnectionStatus(): String {
        return when {
            !isRunning -> "Disconnected"
            process?.isAlive == false -> "Process died"
            logs.value.contains("error", ignoreCase = true) -> "Connection failed"
            logs.value.contains("connected", ignoreCase = true) -> "Connected"
            logs.value.contains("starting", ignoreCase = true) -> "Starting tunnel..."
            logs.value.contains("authenticating", ignoreCase = true) -> "Authenticating..."
            else -> "Connecting..."
        }
    }
}

