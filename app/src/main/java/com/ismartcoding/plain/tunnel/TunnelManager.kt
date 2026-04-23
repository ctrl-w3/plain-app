package com.ismartcoding.plain.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.ismartcoding.lib.logcat.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelManager {
    private const val TOKEN = "eyJhIjoiNzk4MDRjYzVhNTdhMGFjZTVkZDA4NmZhMDdkOTc2NTAiLCJ0IjoiODhiNjc0MTMtNjUyMi00YTMyLWJiZjItYTc4NmMxNjc3ZWU5IiwicyI6IllXVTVOVFUzTm1RdFlUWXhaQzAwTkdZMExUbGhaVGt0TkRVNVpXWmtZV0ptTmpoaSJ9"

    val maskedToken: String
        get() = "${TOKEN.take(6)}...${TOKEN.takeLast(4)}"

    private var job: Job? = null
    private var isRunning = false
    private val logBuffer = StringBuilder()
    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val isTunnelRunning: Boolean
        get() = isRunning

    enum class TunnelStatus {
        STOPPED, STARTING, CONNECTING, CONNECTED, FAILED
    }

    private val _status = MutableStateFlow(TunnelStatus.STOPPED)
    val status: StateFlow<TunnelStatus> = _status

    private var lastLogContent = ""

    // Broadcast receiver for Termux API results
    private val commandResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val stdout = it.getStringExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_STDOUT") ?: ""
                val stderr = it.getStringExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_STDERR") ?: ""
                val exitCode = it.getIntExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_EXIT_CODE", -1)

                // Handle command results
                handleCommandResult(stdout, stderr, exitCode)
            }
        }
    }

    fun initialize(context: Context) {
        // Register broadcast receiver
        val filter = IntentFilter("com.termux.api.iab.TermuxApiReceiver.ACTION_COMMAND_RESULT")
        ContextCompat.registerReceiver(context, commandResultReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isTermuxApiInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openTermuxInstallPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
        context.startActivity(intent)
    }

    fun openTermuxApiInstallPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux.api/"))
        context.startActivity(intent)
    }

    fun setupTermux(context: Context): Boolean {
        if (!isTermuxApiInstalled(context)) {
            addLog("Termux:API not installed. Please install it first.", true)
            return false
        }

        addLog("Setting up Termux: updating packages and installing cloudflared...")
        runTermuxCommand(context, "pkg update -y && pkg install cloudflared -y", background = true)
        return true
    }

    fun startTunnel(context: Context): Boolean {
        if (isTunnelRunning) {
            addLog("Tunnel is already running")
            return true
        }

        if (!isTermuxInstalled(context)) {
            addLog("Termux is not installed", true)
            return false
        }

        if (!isTermuxApiInstalled(context)) {
            addLog("Termux:API is not installed", true)
            return false
        }

        clearLogs()
        addLog("Starting Cloudflare tunnel via Termux...")
        _status.value = TunnelStatus.STARTING

        // Start tunnel command with log redirection
        val command = "cloudflared tunnel run --token $TOKEN > tunnel.log 2>&1"
        runTermuxCommand(context, command, background = true)

        isRunning = true
        addLog("Tunnel command sent to Termux")

        // Start monitoring job
        job = CoroutineScope(Dispatchers.IO).launch {
            monitorTunnel(context)
        }

        // Start foreground service
        val intent = Intent(context, TunnelService::class.java)
        ContextCompat.startForegroundService(context, intent)

        return true
    }

    fun stopTunnel(context: Context) {
        if (!isRunning) return

        addLog("Stopping tunnel...")
        runTermuxCommand(context, "pkill cloudflared", background = false)
        isRunning = false
        _status.value = TunnelStatus.STOPPED
        job?.cancel()
    }

    private fun runTermuxCommand(context: Context, command: String, background: Boolean = false) {
        val intent = Intent()
        intent.setClassName("com.termux.api", "com.termux.api.ExecuteCommand")
        intent.putExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_COMMAND", command)
        intent.putExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_BACKGROUND", background)
        context.startService(intent)
    }

    private fun handleCommandResult(stdout: String, stderr: String, exitCode: Int) {
        if (stdout.isNotEmpty()) {
            // Check if this is log content
            if (stdout.contains("tunnel.log") || stdout.contains("Log file not found")) {
                // This is from cat command, update logs
                updateLogsFromOutput(stdout)
            } else {
                addLog("Termux stdout: $stdout")
            }
        }
        if (stderr.isNotEmpty()) {
            addLog("Termux stderr: $stderr", true)
        }
        if (exitCode != 0 && exitCode != -1) { // -1 might be for background commands
            addLog("Command failed with exit code: $exitCode", true)
        }
    }

    private fun updateLogsFromOutput(output: String) {
        if (output == lastLogContent) return
        val newLines = output.removePrefix(lastLogContent).trim()
        if (newLines.isNotEmpty()) {
            newLines.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    addLog(line)
                }
            }
        }
        lastLogContent = output
    }

    private suspend fun monitorTunnel(context: Context) {
        while (isActive) {
            // Poll logs every 1 second
            runTermuxCommand(context, "cat tunnel.log 2>/dev/null || echo 'Log file not found'", background = false)
            delay(1000)

            // Check if tunnel is still running every 10 seconds
            if (System.currentTimeMillis() % 10000 < 1000) {
                runTermuxCommand(context, "pgrep cloudflared > /dev/null && echo 'running' || echo 'stopped'", background = false)
            }
        }
    }

    private fun addLog(message: String, isError: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val prefix = if (isError) "[ERROR]" else "[INFO]"
        val logLine = "$timestamp $prefix $message\n"

        synchronized(logBuffer) {
            logBuffer.append(logLine)
            // Keep only last 1000 lines
            val lines = logBuffer.toString().split("\n")
            if (lines.size > 1000) {
                logBuffer.setLength(0)
                logBuffer.append(lines.takeLast(1000).joinToString("\n"))
            }
        }

        _logs.value = logBuffer.toString()
        LogCat.d("Tunnel: $message")

        // Update status based on log content
        updateStatusFromLogs(message)
    }

    private fun updateStatusFromLogs(message: String) {
        when {
            message.contains("Connected") || message.contains("tunnel registered") || message.contains("tunnel connected") -> {
                _status.value = TunnelStatus.CONNECTED
            }
            message.contains("Starting") || message.contains("connecting") || message.contains("registering") -> {
                _status.value = TunnelStatus.CONNECTING
            }
            message.contains("error") || message.contains("failed") || message.contains("stopped") -> {
                if (_status.value != TunnelStatus.STOPPED) {
                    _status.value = TunnelStatus.FAILED
                }
            }
        }
    }

    private fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.setLength(0)
        }
        _logs.value = ""
        lastLogContent = ""
    }

    // Public method for external classes to add logs
    fun addToLogs(logLine: String) {
        addLog(logLine)
    }
}package com.ismartcoding.plain.tunnel

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

