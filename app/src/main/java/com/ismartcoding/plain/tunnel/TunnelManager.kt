package com.ismartcoding.plain.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.ismartcoding.lib.logcat.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
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
    val isTunnelRunning: Boolean
        get() = isRunning
    private val logBuffer = StringBuilder()
    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    enum class TunnelStatus {
        STOPPED, STARTING, CONNECTING, CONNECTED, FAILED
    }

    private val _status = MutableStateFlow(TunnelStatus.STOPPED)
    val status: StateFlow<TunnelStatus> = _status

    private var lastLogContent = ""
    private var appContext: Context? = null

    private val commandResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val stdout = it.getStringExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_STDOUT") ?: ""
                val stderr = it.getStringExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_STDERR") ?: ""
                val exitCode = it.getIntExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_EXIT_CODE", -1)
                handleCommandResult(stdout, stderr, exitCode)
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val filter = IntentFilter("com.termux.api.iab.TermuxApiReceiver.ACTION_COMMAND_RESULT")
        context.registerReceiver(commandResultReceiver, filter)
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
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun openTermuxApiInstallPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux.api/"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun setupTermux(context: Context): Boolean {
        if (!isTermuxApiInstalled(context)) {
            addLog("Termux:API is not installed. Please install it first.", true)
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

        val command = "cloudflared tunnel run --token $TOKEN > tunnel.log 2>&1"
        runTermuxCommand(context, command, background = true)

        isRunning = true
        addLog("Tunnel command sent to Termux")

        job = CoroutineScope(Dispatchers.IO).launch {
            monitorTunnel(context)
        }

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

    fun stopTunnel() {
        appContext?.let { stopTunnel(it) }
    }

    private fun runTermuxCommand(context: Context, command: String, background: Boolean = false) {
        val intent = Intent()
        intent.setClassName("com.termux.api", "com.termux.api.ExecuteCommand")
        intent.putExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_COMMAND", command)
        intent.putExtra("com.termux.api.iab.TermuxApiReceiver.EXTRA_BACKGROUND", background)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startService(intent)
    }

    private fun handleCommandResult(stdout: String, stderr: String, exitCode: Int) {
        if (stdout.isNotEmpty()) {
            if (stdout.contains("tunnel.log") || stdout.contains("Log file not found")) {
                updateLogsFromOutput(stdout)
            } else {
                addLog("Termux stdout: $stdout")
            }
        }
        if (stderr.isNotEmpty()) {
            addLog("Termux stderr: $stderr", true)
        }
        if (exitCode != 0 && exitCode != -1) {
            addLog("Command failed with exit code: $exitCode", true)
        }
    }

    private fun updateLogsFromOutput(output: String) {
        if (output == lastLogContent) return
        lastLogContent = output
        val newLines = output.lines()
        newLines.forEach { line ->
            if (line.isNotBlank()) {
                addLog(line)
            }
        }
    }

    private suspend fun monitorTunnel(context: Context) {
        while (currentCoroutineContext().isActive) {
            runTermuxCommand(context, "cat tunnel.log 2>/dev/null || echo 'Log file not found'", background = false)
            delay(1000)

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
            val lines = logBuffer.toString().split("\n")
            if (lines.size > 1000) {
                logBuffer.setLength(0)
                logBuffer.append(lines.takeLast(1000).joinToString("\n"))
            }
        }

        _logs.value = logBuffer.toString()
        LogCat.d("Tunnel: $message")
        updateStatusFromLogs(message)
    }

    private fun updateStatusFromLogs(message: String) {
        when {
            message.contains("Connected", ignoreCase = true) || message.contains("tunnel registered", ignoreCase = true) || message.contains("tunnel connected", ignoreCase = true) -> {
                _status.value = TunnelStatus.CONNECTED
            }
            message.contains("starting", ignoreCase = true) || message.contains("connecting", ignoreCase = true) || message.contains("registering", ignoreCase = true) -> {
                _status.value = TunnelStatus.CONNECTING
            }
            message.contains("error", ignoreCase = true) || message.contains("failed", ignoreCase = true) || message.contains("stopped", ignoreCase = true) -> {
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

    fun addToLogs(logLine: String) {
        addLog(logLine)
    }
}
