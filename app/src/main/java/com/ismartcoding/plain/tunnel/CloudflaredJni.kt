package com.ismartcoding.plain.tunnel

object CloudflaredJni {
    init {
        System.loadLibrary("cloudflared")
    }

    external fun startTunnel(token: String): Int

    // Callback for logs
    external fun setLogCallback(callback: (String) -> Unit)
}