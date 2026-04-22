package com.ismartcoding.plain.ui.page.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ismartcoding.plain.R
import com.ismartcoding.plain.enums.ButtonSize
import com.ismartcoding.plain.enums.ButtonType
import com.ismartcoding.plain.ui.base.PFilledButton
import com.ismartcoding.plain.ui.base.VerticalSpace
import com.ismartcoding.plain.ui.models.MainViewModel
import com.ismartcoding.plain.ui.theme.cardBackgroundNormal
import com.ismartcoding.plain.tunnel.TunnelManager

enum class TunnelState { OFF, CONNECTING, ON }

@Composable
fun HomeTunnelSection(
    context: Context,
    mainVM: MainViewModel,
    tunnelEnabled: Boolean,
) {
    val tunnelState = when {
        TunnelManager.isTunnelRunning -> TunnelState.ON
        tunnelEnabled -> TunnelState.CONNECTING
        else -> TunnelState.OFF
    }

    val logs by TunnelManager.logs.collectAsState()
    val localContext = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.cardBackgroundNormal,
    ) {
        Column(
            modifier = Modifier.padding(
                top = 16.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = 24.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (tunnelState) {
                        TunnelState.OFF -> stringResource(R.string.remote_access_off)
                        TunnelState.CONNECTING -> stringResource(R.string.tunnel_connecting)
                        TunnelState.ON -> stringResource(R.string.remote_access_on)
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold, lineHeight = 36.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpace(12.dp)
            Text(
                text = when (tunnelState) {
                    TunnelState.OFF -> stringResource(R.string.remote_access_desc_off)
                    TunnelState.CONNECTING -> stringResource(R.string.remote_access_desc_connecting)
                    TunnelState.ON -> stringResource(R.string.remote_access_desc_on)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalSpace(8.dp)
            Text(
                text = "Token: eyJhIjoiNzk4MDRjYzVhNTdhMGFjZTVkZDA4NmZhMDdkOTc2NTAiLCJ0IjoiNDI3MzQyYjMtODU4MS00ZjMxLThiYjctN2UzOGViYWEwMzI3IiwicyI6Ik5qUmtPVEk1TnpNdE9UVTNZUzAwWVRCaUxXSmlPV1F0T1RnNVpESXdaalU0WkdZMiJ9",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalSpace(8.dp)
            Text(
                text = "https://app.shakti.buzz?key=SECRET123",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            // Show logs when tunnel is enabled
            if (tunnelEnabled && logs.isNotEmpty()) {
                VerticalSpace(16.dp)
                HorizontalDivider()
                VerticalSpace(12.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tunnel_logs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = {
                            val clipboard = ContextCompat.getSystemService(localContext, ClipboardManager::class.java)
                            val clip = ClipData.newPlainText("Tunnel Logs", logs)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(localContext, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.content_copy),
                            contentDescription = "Copy logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                VerticalSpace(8.dp)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "No logs available yet...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            val annotatedString = buildAnnotatedString {
                                logs.lines().forEach { line ->
                                    if (line.contains("[ERROR]")) {
                                        withStyle(style = SpanStyle(color = Color.Red, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    } else {
                                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)) {
                                            append(line)
                                        }
                                    }
                                    append("\n")
                                }
                            }
                            Text(
                                text = annotatedString,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            VerticalSpace(24.dp)
            when (tunnelState) {
                TunnelState.OFF -> PFilledButton(
                    text = stringResource(R.string.enable_remote_access),
                    onClick = { mainVM.enableTunnel(context, true) },
                    buttonSize = ButtonSize.LARGE,
                )

                TunnelState.CONNECTING -> PFilledButton(
                    text = stringResource(R.string.tunnel_connecting),
                    onClick = {},
                    buttonSize = ButtonSize.LARGE,
                    enabled = false,
                )

                TunnelState.ON -> PFilledButton(
                    text = stringResource(R.string.disable_remote_access),
                    onClick = { mainVM.enableTunnel(context, false) },
                    type = ButtonType.DANGER,
                    buttonSize = ButtonSize.LARGE,
                )
            }
        }
    }
}