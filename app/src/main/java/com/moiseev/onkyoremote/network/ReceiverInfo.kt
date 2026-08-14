package com.moiseev.onkyoremote.network

data class ReceiverInfo(
    val host: String,
    val port: Int = 60128,
    val model: String = "",
    val model_name: String = model,
    val area: String = "",
    val identifier: String = ""
) {
    val modelName: String
        get() = model_name.ifBlank { model }
}
