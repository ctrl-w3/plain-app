package com.ismartcoding.plain.ui.page.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.ismartcoding.plain.R
import com.ismartcoding.plain.enums.ButtonSize
import com.ismartcoding.plain.enums.ButtonType
import com.ismartcoding.plain.ui.base.PFilledButton
import com.ismartcoding.plain.ui.base.VerticalSpace
import com.ismartcoding.plain.ui.models.MainViewModel
import com.ismartcoding.plain.ui.theme.cardBackground
import com.ismartcoding.plain.tunnel.TunnelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TunnelConsolePage(
    navController: NavController,
    mainVM: MainViewModel,
) {
    val context = LocalContext.current
    val logs by TunnelManager.logs.collectAsState()
    val status by TunnelManager.status.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var autoScroll by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when logs change
    LaunchedEffect(logs) {
        if (autoScroll && logs.isNotEmpty()) {
            coroutineScope.launch {
                delay(100) // Small delay to ensure UI is updated
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Status indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = when (status) {
                                        TunnelManager.TunnelStatus.CONNECTED -> Color.Green
                                        TunnelManager.TunnelStatus.CONNECTING -> Color.Yellow
                                        TunnelManager.TunnelStatus.FAILED -> Color.Red
                                        else -> Color.Gray
                                    },
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = "Live Tunnel Console",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    // Auto-scroll toggle
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            painter = painterResource(if (autoScroll) R.drawable.pause else R.drawable.play),
                            contentDescription = if (autoScroll) "Disable auto-scroll" else "Enable auto-scroll"
                        )
                    }
                    // Copy logs
                    IconButton(onClick = {
                        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("Tunnel Logs", logs)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(painter = painterResource(R.drawable.copy), contentDescription = "Copy logs")
                    }
                    // Clear logs
                    IconButton(onClick = {
                        // Note: Clearing logs would require modifying TunnelManager to expose a clear method
                        Toast.makeText(context, "Clear logs not implemented", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(painter = painterResource(R.drawable.delete), contentDescription = "Clear logs")
                    }
                    // Stop tunnel
                    if (TunnelManager.isTunnelRunning) {
                        IconButton(onClick = {
                            TunnelManager.stopTunnel(context)
                            mainVM.enableTunnel(context, false)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.stop),
                                contentDescription = "Stop tunnel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black) // Terminal background
                .padding(16.dp)
        ) {
            // Status text
            Text(
                text = when (status) {
                    TunnelManager.TunnelStatus.STOPPED -> "Status: Stopped"
                    TunnelManager.TunnelStatus.STARTING -> "Status: Starting..."
                    TunnelManager.TunnelStatus.CONNECTING -> "Status: Connecting..."
                    TunnelManager.TunnelStatus.CONNECTED -> "Status: Connected ✓"
                    TunnelManager.TunnelStatus.FAILED -> "Status: Failed ✗"
                },
                color = when (status) {
                    TunnelManager.TunnelStatus.CONNECTED -> Color.Green
                    TunnelManager.TunnelStatus.FAILED -> Color.Red
                    else -> Color.Cyan
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Logs area
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = Color.Black,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Waiting for tunnel output...\n\n" +
                                   "Make sure Termux and Termux:API are installed.\n" +
                                   "The tunnel will start automatically when enabled.",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        val annotatedString = buildAnnotatedString {
                            logs.lines().forEach { line ->
                                when {
                                    line.contains("[ERROR]") || line.contains("error") || line.contains("failed") -> {
                                        withStyle(style = SpanStyle(color = Color.Red, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    }
                                    line.contains("[WARNING]") || line.contains("retrying") || line.contains("connection refused") -> {
                                        withStyle(style = SpanStyle(color = Color.Yellow, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    }
                                    line.contains("[INFO]") || line.contains("Connected") || line.contains("success") || line.contains("tunnel registered") -> {
                                        withStyle(style = SpanStyle(color = Color.Green, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    }
                                    else -> {
                                        withStyle(style = SpanStyle(color = Color.White, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    }
                                }
                                append("\n")
                            }
                        }
                        Text(
                            text = annotatedString,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            VerticalSpace(16.dp)

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!TunnelManager.isTunnelRunning) {
                    PFilledButton(
                        text = "Start Tunnel",
                        onClick = {
                            if (!TunnelManager.isTermuxInstalled(context)) {
                                TunnelManager.openTermuxInstallPage(context)
                                return@PFilledButton
                            }
                            if (!TunnelManager.isTermuxApiInstalled(context)) {
                                TunnelManager.openTermuxApiInstallPage(context)
                                return@PFilledButton
                            }
                            TunnelManager.setupTermux(context)
                            if (TunnelManager.startTunnel(context)) {
                                mainVM.enableTunnel(context, true)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    PFilledButton(
                        text = "Stop Tunnel",
                        onClick = {
                            TunnelManager.stopTunnel(context)
                            mainVM.enableTunnel(context, false)
                        },
                        type = ButtonType.DANGER,
                        modifier = Modifier.weight(1f)
                    )
                }

                PFilledButton(
                    text = "Setup Termux",
                    onClick = {
                        if (!TunnelManager.isTermuxInstalled(context)) {
                            TunnelManager.openTermuxInstallPage(context)
                        } else if (!TunnelManager.isTermuxApiInstalled(context)) {
                            TunnelManager.openTermuxApiInstallPage(context)
                        } else {
                            TunnelManager.setupTermux(context)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}