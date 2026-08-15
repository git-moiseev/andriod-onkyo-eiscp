package com.moiseev.onkyoremote.ui

import com.moiseev.onkyoremote.network.ReceiverInfo

data class ReceiverState(
    val discovering: Boolean = false,
    val discovered: List<ReceiverInfo> = emptyList(),
    val connected: Boolean = false,
    val receiver: ReceiverInfo? = null,
    val powerOn: Boolean = false,
    val muted: Boolean = false,
    val listeningMode: String = "01",
    val musicOptimizer: Boolean = false,
    val soundProfile: String = "direct",
    val volume: Int = 35,
    val inputCode: String = "",
    val receiverIp: String = "",
    val customInputNames: Map<String, String> = emptyMap(),
    val selectedTab: Int = 0,
    val error: String? = null
)
