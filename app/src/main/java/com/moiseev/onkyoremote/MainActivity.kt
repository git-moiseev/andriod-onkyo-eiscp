package com.moiseev.onkyoremote

import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.moiseev.onkyoremote.network.*
import com.moiseev.onkyoremote.ui.ReceiverState
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : ComponentActivity(), OnkyoClient.Listener {
    private companion object { const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK" }
    private var state by mutableStateOf(ReceiverState())
    private var autoDiscoveryEnabled by mutableStateOf(false)
    private var demoMode by mutableStateOf(false)
    private var hasStartedOnce = false
    private lateinit var client: OnkyoClient
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingNetworkAction: (() -> Unit)? = null
    private val localNetworkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) action?.invoke()
        else state = state.copy(connected = false, error = "Local network access denied")
    }
    private val inputs = listOf("dvd" to "10", "tape-1" to "20", "video1" to "00", "video2" to "01", "video3" to "02", "cd" to "23")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("onkyo_settings", MODE_PRIVATE)
        autoDiscoveryEnabled = prefs.getBoolean("auto_discovery", (prefs.getString("receiver_ip", "") ?: "").isBlank())
        state = state.copy(
            receiverIp = prefs.getString("receiver_ip", "") ?: "",
            customInputNames = inputs.mapNotNull { (_, code) ->
                prefs.getString("input_$code", null)?.let { code to it }
            }.toMap()
        )
        client = OnkyoClient(this)
        setContent { MaterialTheme(darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF080B0F)) { ReceiverScreen() } } }
        reconnect()
    }

    override fun onStart() {
        super.onStart()
        if (hasStartedOnce) reconnect() else hasStartedOnce = true
    }

    override fun onStop() {
        client.disconnect()
        state = state.copy(connected = false)
        super.onStop()
    }

    private fun reconnect() {
        if (demoMode) return
        withLocalNetworkPermission {
            if (autoDiscoveryEnabled || state.receiverIp.isBlank()) discover() else connectConfiguredReceiver()
        }
    }

    private fun toggleDemoMode() {
        demoMode = !demoMode
        client.disconnect()
        state = state.copy(connected = false, error = null)
        if (!demoMode) reconnect()
    }

    private fun withLocalNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingNetworkAction = action
            localNetworkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    override fun onDestroy() { client.disconnect(); executor.shutdownNow(); super.onDestroy() }
    private fun discover() {
        state = state.copy(discovering = true)
        executor.execute {
            try {
                val found = OnkyoDiscovery.discover()
                runOnUiThread {
                    state = state.copy(discovering = false, discovered = found)
                    val previousId = state.receiver?.identifier.orEmpty()
                    val preferred = found.firstOrNull { previousId.isNotBlank() && it.identifier == previousId }
                        ?: found.firstOrNull { !autoDiscoveryEnabled && it.host == state.receiverIp }
                        ?: found.singleOrNull()
                    if (!demoMode) preferred?.let(client::connect)
                }
            } catch (e: Exception) { runOnUiThread { state = state.copy(discovering = false, error = e.message) } }
        }
    }

    private fun connectConfiguredReceiver() {
        val host = state.receiverIp.trim()
        if (host.isBlank()) return
        client.connect(ReceiverInfo(host = host))
        executor.execute {
            val info = OnkyoDiscovery.getInfo(host)
            if (info != null) runOnUiThread {
                Log.i("OnkyoEiscp", "Applying direct receiver info: model='${info.modelName}'")
                state = state.copy(receiver = info)
            }
        }
    }
    override fun onConnected(info: ReceiverInfo) {
        val known = state.receiver?.takeIf { it.host == info.host && it.modelName.isNotBlank() }
        state = state.copy(connected = true, receiver = known ?: info, error = null)
    }
    override fun onDisconnected(reason: String?) { state = state.copy(connected = false, error = reason) }
    override fun onMessage(command: String) {
        val c = command.trim().let { if (it.startsWith("!") && it.length > 1) it.substring(2) else it }
        Log.d("OnkyoEiscp", "UI RX raw='${EiscpProtocol.debugText(command)}' normalized='${EiscpProtocol.debugText(c)}'")
        when {
            c.startsWith("ECN") -> EiscpProtocol.parseInfoResponse(c, state.receiverIp.ifBlank { state.receiver?.host.orEmpty() })?.let {
                Log.i("OnkyoEiscp", "UI applying receiver model='${it.modelName}'")
                state = state.copy(receiver = it)
            }
            c.startsWith("PWR") -> state = state.copy(powerOn = c.drop(3) == "01")
            c.startsWith("AMT") -> state = state.copy(muted = c.drop(3) == "01")
            c.startsWith("LMD") -> {
                val mode = c.drop(3).take(2).uppercase(Locale.US)
                state = state.copy(
                    listeningMode = mode,
                    soundProfile = when (mode) {
                        "01" -> "direct"
                        "00" -> if (state.musicOptimizer) "optimizer_on" else "optimizer_off"
                        else -> state.soundProfile
                    }
                )
            }
            c.startsWith("MOT") -> {
                val optimizerOn = c.drop(3) == "01"
                state = state.copy(
                    musicOptimizer = optimizerOn,
                    soundProfile = if (state.listeningMode == "00") {
                        if (optimizerOn) "optimizer_on" else "optimizer_off"
                    } else state.soundProfile
                )
            }
            c.startsWith("MVL") -> c.drop(3).take(2).toIntOrNull(16)?.let { state = state.copy(volume = it.coerceIn(0, 80)) }
            c.startsWith("SLI") -> state = state.copy(inputCode = c.drop(3).take(2).uppercase(Locale.US))
        }
    }
    private fun name() = state.receiver?.model_name?.takeIf { it.isNotBlank() }
        ?: state.receiver?.model?.takeIf { it.isNotBlank() } ?: "Onkyo Receiver"

    private fun saveInputName(code: String, name: String) {
        val updated = state.customInputNames + (code to name)
        state = state.copy(customInputNames = updated)
        getSharedPreferences("onkyo_settings", MODE_PRIVATE).edit().putString("input_$code", name).apply()
    }

    private fun saveReceiverIp(ip: String) {
        val normalized = ip.trim()
        autoDiscoveryEnabled = false
        getSharedPreferences("onkyo_settings", MODE_PRIVATE).edit()
            .putString("receiver_ip", normalized).putBoolean("auto_discovery", false).apply()
        client.disconnect()
        state = state.copy(receiverIp = normalized, receiver = null, connected = false, error = null)
        withLocalNetworkPermission { connectConfiguredReceiver() }
    }

    private fun saveAutoDiscoveryAddress(ip: String) {
        val host = ip.trim()
        autoDiscoveryEnabled = true
        getSharedPreferences("onkyo_settings", MODE_PRIVATE).edit()
            .remove("receiver_ip").putBoolean("auto_discovery", true).apply()
        client.disconnect()
        state = state.copy(receiverIp = "", connected = false, error = null)
        withLocalNetworkPermission {
            client.connect(ReceiverInfo(host = host))
            executor.execute {
                OnkyoDiscovery.getInfo(host)?.let { info -> runOnUiThread { state = state.copy(receiver = info) } }
            }
        }
    }

    private fun discoverReceiverAddress(onResult: (String?) -> Unit) {
        withLocalNetworkPermission {
            executor.execute {
                val info = try { OnkyoDiscovery.discover().firstOrNull() } catch (e: Exception) {
                    Log.e("OnkyoEiscp", "Address auto-discovery failed", e)
                    null
                }
                runOnUiThread { onResult(info?.host) }
            }
        }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.trim().split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
    }

    @Composable private fun ReceiverScreen() {
        val s = state
        val controlsAvailable = s.connected || demoMode
        var editingCode by remember { mutableStateOf<String?>(null) }
        var editingIp by remember { mutableStateOf(false) }
        var dialogIp by remember { mutableStateOf("") }
        var addressSearchRunning by remember { mutableStateOf(false) }
        var addressNotFound by remember { mutableStateOf(false) }
        var dialogAutoDiscovery by remember { mutableStateOf(false) }
        editingCode?.let { code ->
            val defaultName = inputs.first { it.second == code }.first
            RenameInputDialog(
                initialName = s.customInputNames[code] ?: defaultName,
                onDismiss = { editingCode = null },
                onSave = { saveInputName(code, it); editingCode = null }
            )
        }
        if (editingIp) {
            ReceiverIpDialog(
                initialIp = s.receiverIp,
                onDismiss = { editingIp = false },
                onSave = {
                    if (dialogAutoDiscovery) saveAutoDiscoveryAddress(it) else saveReceiverIp(it)
                    editingIp = false
                },
                discoveredIp = dialogIp,
                searching = addressSearchRunning,
                notFound = addressNotFound,
                alwaysDiscover = dialogAutoDiscovery,
                onAlwaysDiscoverChange = { dialogAutoDiscovery = it },
                onAutoDiscover = {
                    dialogIp = ""
                    addressNotFound = false
                    addressSearchRunning = true
                    discoverReceiverAddress { foundIp ->
                        addressSearchRunning = false
                        dialogIp = foundIp.orEmpty()
                        addressNotFound = foundIp == null
                        if (foundIp != null) dialogAutoDiscovery = true
                    }
                }
            )
        }
        Box(Modifier.fillMaxSize().statusBarsPadding().background(Brush.verticalGradient(listOf(Color(0xFF090C10), Color(0xFF05070A)))).padding(7.dp)) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).hardwarePanelBackground().border(1.dp, Color(0xFF262C32), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ReceiverStatus(
                    connected = controlsAvailable,
                    name = if (demoMode) "DEMO" else if (!controlsAvailable && s.receiver == null) "Long tap to connect" else name(),
                    discovering = s.discovering && !demoMode,
                    connectionHint = !controlsAvailable && s.receiver == null,
                    playbackStatus = when {
                        s.muted -> "Muted"
                        s.soundProfile == "direct" -> "Direct"
                        s.soundProfile == "optimizer_on" -> "Stereo MoON"
                        s.soundProfile == "optimizer_off" -> "Stereo MoOFF"
                        else -> if (s.musicOptimizer) "Stereo MoON" else "Stereo MoOFF"
                    },
                    editIp = {
                        dialogIp = if (autoDiscoveryEnabled) s.receiver?.host.orEmpty() else s.receiverIp
                        addressNotFound = false
                        addressSearchRunning = false
                        dialogAutoDiscovery = autoDiscoveryEnabled
                        editingIp = true
                    }
                )
                Spacer(Modifier.height(23.dp))
                PowerAndHeadphones(s.powerOn, controlsAvailable, ::toggleDemoMode) {
                    val next = !state.powerOn
                    state = state.copy(powerOn = next)
                    if (!demoMode) client.send(if (next) "PWR01" else "PWR00")
                }
                Spacer(Modifier.height(13.dp))
                PanelLabel("INPUTS")
                Spacer(Modifier.height(8.dp))
                InputSelector(s.inputCode, controlsAvailable && s.powerOn, s.powerOn,
                    select = { state = state.copy(inputCode = it); if (!demoMode) client.send("SLI$it") },
                    rename = { editingCode = it })
                Spacer(Modifier.weight(.18f))
                PanelLabel("MASTER VOLUME")
                Spacer(Modifier.height(14.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    VolumeKnob(s.volume, controlsAvailable && s.powerOn, s.muted, s.soundProfile, minOf(maxWidth * .80f, maxHeight),
                        { state = state.copy(volume = it) },
                        { if (!demoMode) client.send("MVL${state.volume.toString(16).uppercase(Locale.US).padStart(2, '0')}") },
                        {
                            if (controlsAvailable && !s.muted) when (s.soundProfile) {
                                "direct" -> {
                                    state = state.copy(listeningMode = "00", musicOptimizer = true, soundProfile = "optimizer_on")
                                    if (!demoMode) { client.send("LMD00"); client.send("MOT01") }
                                }
                                "optimizer_on" -> {
                                    state = state.copy(musicOptimizer = false, soundProfile = "optimizer_off")
                                    if (!demoMode) client.send("MOT00")
                                }
                                else -> {
                                    state = state.copy(listeningMode = "01", soundProfile = "direct")
                                    if (!demoMode) client.send("LMD01")
                                }
                            }
                        },
                        { if (controlsAvailable) { val next = !state.muted; state = state.copy(muted = next); if (!demoMode) client.send(if (next) "AMT01" else "AMT00") } })
                }
                Text("Drag the knob. Tap center for sound mode; hold to mute.", color = Color(0xFF8D95A0), fontSize = 9.sp, lineHeight = 12.75.sp, textAlign = TextAlign.Center)
            }
        }
    }

    private fun Modifier.hardwarePanelBackground(): Modifier = drawWithCache {
        val base = Brush.verticalGradient(
            0f to Color(0xFF1B222A),
            .48f to Color(0xFF171D24),
            1f to Color(0xFF11161C)
        )
        val softLight = Brush.radialGradient(
            colors = listOf(Color(0x162C3A45), Color.Transparent, Color(0x26030609)),
            center = Offset(size.width * .43f, size.height * .34f),
            radius = size.maxDimension * .82f
        )
        onDrawBehind {
            drawRect(base)
            drawRect(softLight)

            // Barely visible matte grain; material is communicated by edges, not decoration.
            repeat(380) { index ->
                val x = ((index * 89 + index * index * 29) % 1031) / 1031f * size.width
                val y = ((index * 233 + index * index * 17) % 1021) / 1021f * size.height
                drawCircle(
                    if (index % 3 == 0) Color(0x0DAAB7C0) else Color(0x10000000),
                    .18.dp.toPx(),
                    Offset(x, y)
                )
            }
        }
    }

    private fun Modifier.hardwareInputSurface(pressed: Boolean): Modifier = drawWithCache {
        val corner = CornerRadius(4.dp.toPx())
        val face = Brush.verticalGradient(
            if (pressed) listOf(Color(0xFF111519), Color(0xFF20262B))
            else listOf(Color(0xFF20252A), Color(0xFF151A1E), Color(0xFF0C1013))
        )
        onDrawBehind {
            drawRoundRect(face, cornerRadius = corner)
            drawLine(Color(0x385D666D), Offset(4.dp.toPx(), .7.dp.toPx()), Offset(size.width - 4.dp.toPx(), .7.dp.toPx()), .45.dp.toPx())
            drawLine(Color(0xCC050709), Offset(4.dp.toPx(), size.height - .8.dp.toPx()), Offset(size.width - 4.dp.toPx(), size.height - .8.dp.toPx()), .7.dp.toPx())
            drawRoundRect(Color(0xFF2B3237), cornerRadius = corner, style = Stroke(.55.dp.toPx()))
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun ReceiverStatus(connected: Boolean, name: String, discovering: Boolean, connectionHint: Boolean, playbackStatus: String, editIp: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .shadow(5.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x88000000), spotColor = Color(0x88000000))
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF070A0D), Color(0xFF11181A), Color(0xFF06080B))),
                    RoundedCornerShape(8.dp)
                )
                .border(1.dp, Color(0xFF303A3D), RoundedCornerShape(8.dp))
                .combinedClickable(onClick = {}, onLongClick = editIp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).shadow(if (connected) 3.dp else 0.dp, CircleShape, spotColor = Color(0x8820B86C)).background(Brush.radialGradient(if (connected) listOf(Color(0xFFA6D7B8), Color(0xFF399C68), Color(0xFF185237)) else listOf(Color(0xFF737B83), Color(0xFF3D444B))), CircleShape).border(0.5.dp, if (connected) Color(0x554BA878) else Color(0x553D444B), CircleShape))
            Spacer(Modifier.width(6.dp)); Text(if (discovering) "Searching" else name, color = if (connected) Color(0xFF83B99A) else Color(0xFF929BA4), fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!connectionHint) {
            Text(
                " ${if (connected) playbackStatus else "Disconnected"}",
                color = if (connected && playbackStatus == "Muted") Color(0xFFC86F6F)
                else if (connected) Color(0xFF83B99A) else Color(0xFF7D858D),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            }
        }
    }

    @Composable private fun PowerButton(on: Boolean, enabled: Boolean, click: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val pressOffset by animateDpAsState(if (pressed) 2.dp else 0.dp, label = "powerPress")
        val haptic = LocalHapticFeedback.current
        Box(Modifier.offset(y = pressOffset).size(71.3.dp).shadow(if (pressed) 3.dp else 10.dp, CircleShape, ambientColor = Color(0xAA000000), spotColor = Color(0xCC000000)).clickable(enabled = enabled, interactionSource = interaction, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); click() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val radius = size.minDimension * .49f

                // Sharp, satin-machined bezel rather than a rounded glossy plastic rim.
                drawCircle(Color(0xFF030506), radius, c)
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF444B50), Color(0xFF22272B), Color(0xFF090C0E), Color(0xFF292F33)),
                        start = Offset(c.x - radius, c.y - radius),
                        end = Offset(c.x + radius, c.y + radius)
                    ),
                    radius * .93f, c
                )
                drawCircle(Color(0xFF090C0E), radius * .79f, c)
                drawCircle(Color(0xFF4A5156), radius * .93f, c, style = Stroke(size.width * .008f))
                drawCircle(Color(0xFF020304), radius * .80f, c, style = Stroke(size.width * .026f))
                drawArc(Color(0x706E767B), 188f, 104f, false, Offset(c.x - radius * .90f, c.y - radius * .90f), Size(radius * 1.80f, radius * 1.80f), style = Stroke(size.width * .010f, cap = StrokeCap.Butt))
                drawArc(Color(0xE0000000), 8f, 126f, false, Offset(c.x - radius * .90f, c.y - radius * .90f), Size(radius * 1.80f, radius * 1.80f), style = Stroke(size.width * .018f, cap = StrokeCap.Butt))

                // Flat face cut from black anodized aluminium.
                val faceRadius = radius * .72f
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF252B2F), Color(0xFF151A1E), Color(0xFF090C0E), Color(0xFF1B2024)),
                        start = Offset(c.x - faceRadius, c.y - faceRadius),
                        end = Offset(c.x + faceRadius, c.y + faceRadius)
                    ),
                    faceRadius, c
                )
                drawCircle(Color(0xFF30373B), faceRadius, c, style = Stroke(size.width * .008f))
                for (ring in 1..11) {
                    drawCircle(
                        if (ring % 2 == 0) Color(0x0B5E686F) else Color(0x10000000),
                        faceRadius * (.28f + ring * .06f), c,
                        style = Stroke(size.width * .0035f)
                    )
                }
                drawArc(Color(0x356B7378), 194f, 102f, false, Offset(c.x - faceRadius * .96f, c.y - faceRadius * .96f), Size(faceRadius * 1.92f, faceRadius * 1.92f), style = Stroke(size.width * .008f, cap = StrokeCap.Butt))

                // A real button stays black; only the engraved symbol emits light.
                if (on) drawCircle(Brush.radialGradient(listOf(Color(0x2638E991), Color(0x0C25C978), Color.Transparent)), radius * .38f, c)
                else drawCircle(Brush.radialGradient(listOf(Color(0x208F3538), Color(0x0A5E2427), Color.Transparent)), radius * .34f, c)
                val iconColor = if (on) Color(0xFF48C985) else Color(0xFF7D4144)
                val iconRadius = radius * .238f
                drawLine(iconColor, Offset(c.x, c.y - iconRadius * 1.15f), Offset(c.x, c.y - iconRadius * .18f), size.width * .025f, StrokeCap.Round)
                drawArc(iconColor, -48f, 276f, false, Offset(c.x - iconRadius, c.y - iconRadius), Size(iconRadius * 2f, iconRadius * 2f), style = Stroke(size.width * .025f, cap = StrokeCap.Round))
            }
        }
    }

    @Composable private fun PowerAndHeadphones(on: Boolean, enabled: Boolean, toggleDemo: () -> Unit, click: () -> Unit) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.width(71.3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PanelLabel("POWER")
                    Spacer(Modifier.height(6.dp))
                    PowerButton(on, enabled, click)
                }
                Column(Modifier.width(71.3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PanelLabel("PHONES")
                    Spacer(Modifier.height(28.4.dp))
                    HeadphoneJack(toggleDemo)
                }
            }
        }
    }

    @Composable private fun PanelLabel(text: String) {
        Text(
            text = text,
            color = Color(0xFF858C95),
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun HeadphoneJack(toggleDemo: () -> Unit) {
        val haptic = LocalHapticFeedback.current
        Canvas(Modifier.size(26.45.dp).combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggleDemo() })) {
            val c = center
            drawCircle(Color(0x55000000), size.minDimension * .50f, c + Offset(1.2f, 1.5f))
            drawCircle(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFE4A7), Color(0xFFB58B42), Color(0xFF4D381A)),
                    center = c - Offset(size.width * .10f, size.height * .12f),
                    radius = size.minDimension * .50f
                ),
                size.minDimension * .46f,
                c
            )
            drawCircle(Color(0xFF080A0C), size.minDimension * .31f, c)
            drawCircle(Color(0xFF252A2D), size.minDimension * .23f, c)
            drawCircle(Color(0xFF050607), size.minDimension * .17f, c)
            drawArc(
                color = Color(0xAAFFF0C5),
                startAngle = 205f,
                sweepAngle = 105f,
                useCenter = false,
                topLeft = Offset(size.width * .08f, size.height * .08f),
                size = Size(size.width * .84f, size.height * .84f),
                style = Stroke(width = 1.2f, cap = StrokeCap.Round)
            )
        }
    }

    @Composable private fun InputSelector(selected: String, enabled: Boolean, receiverOn: Boolean, select: (String) -> Unit, rename: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { inputs.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (defaultName, code) ->
                InputButton(state.customInputNames[code] ?: defaultName, receiverOn && selected == code, enabled, { select(code) }, { rename(code) }, Modifier.weight(1f))
            } }
        } }
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun InputButton(label: String, selected: Boolean, enabled: Boolean, click: () -> Unit, longClick: () -> Unit, modifier: Modifier) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val pressOffset by animateDpAsState(if (pressed) 2.dp else 0.dp, label = "inputPress")
        val haptic = LocalHapticFeedback.current
        Box(modifier.offset(y = pressOffset).height(48.dp).shadow(if (pressed) 0.dp else 1.5.dp, RoundedCornerShape(4.dp), ambientColor = Color(0x99000000), spotColor = Color(0x99000000)).hardwareInputSurface(pressed).combinedClickable(interactionSource = interaction, indication = null, onClick = { if (enabled) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); click() } }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); longClick() }).padding(horizontal = 7.dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).shadow(if (selected) 5.dp else 0.dp, CircleShape, spotColor = Color(0xFF24E78A)).background(Brush.radialGradient(if (selected) listOf(Color(0xFFD5FBE7), Color(0xFF24B96F), Color(0xFF075332)) else listOf(Color(0xFF555C63), Color(0xFF30363B))), CircleShape)); Spacer(Modifier.width(6.dp))
                Text(label, color = Color(0xFFBEC4C9), fontSize = 13.sp, lineHeight = 14.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable private fun RenameInputDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
        var value by remember(initialName) { mutableStateOf(initialName) }
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF171C22),
            titleContentColor = Color(0xFFF0F2F5),
            textContentColor = Color(0xFFCAD0D7),
            title = { Text("Input name", fontSize = 22.sp) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(20) },
                    label = { Text("Button label", fontSize = 16.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF45DE92),
                        cursorColor = Color(0xFF45DE92)
                    )
                )
            },
            confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save", fontSize = 16.sp) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 16.sp) } }
        )
    }

    @Composable private fun ReceiverIpDialog(initialIp: String, discoveredIp: String, searching: Boolean, notFound: Boolean, alwaysDiscover: Boolean, onAlwaysDiscoverChange: (Boolean) -> Unit, onDismiss: () -> Unit, onSave: (String) -> Unit, onAutoDiscover: () -> Unit) {
        var value by remember(initialIp) { mutableStateOf(initialIp) }
        LaunchedEffect(discoveredIp, searching, notFound) {
            if (searching || notFound || discoveredIp.isNotEmpty()) value = discoveredIp
        }
        val valid = isValidIpv4(value)
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF171C22),
            titleContentColor = Color(0xFFF0F2F5),
            title = { Text("Receiver connection", fontSize = 22.sp) },
            text = {
                Column {
                    Text(
                        "Turn off all VPNs and make sure your phone is connected to the same network as the receiver.",
                        color = Color(0xFFB8C0C9),
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        enabled = !searching,
                        onClick = onAutoDiscover,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text(if (searching) "Searching…" else "Auto-discover", fontSize = 16.sp) }
                    Row(
                        Modifier.fillMaxWidth().clickable { onAlwaysDiscoverChange(!alwaysDiscover) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = alwaysDiscover,
                            onCheckedChange = onAlwaysDiscoverChange,
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF299B66))
                        )
                        Text("Always discover receiver automatically", color = Color(0xFFD4D9DF), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filterNot(Char::isWhitespace).take(253) },
                        label = { Text("Receiver IP address", fontSize = 16.sp) },
                        placeholder = { Text("192.168.1.40", fontSize = 16.sp) },
                        isError = value.isNotEmpty() && !valid,
                        supportingText = {
                            when {
                                searching -> Text("Searching…")
                                notFound -> Text("Not found", color = MaterialTheme.colorScheme.error)
                                value.isNotEmpty() && !valid -> Text("Enter a valid IPv4 address, e.g. 192.168.1.40")
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF45DE92),
                            cursorColor = Color(0xFF45DE92)
                        )
                    )
                }
            },
            confirmButton = { TextButton(enabled = valid, onClick = { onSave(value) }) { Text("Connect", fontSize = 16.sp) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 16.sp) } }
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun VolumeKnob(value: Int, enabled: Boolean, muted: Boolean, soundProfile: String, diameter: Dp, change: (Int) -> Unit, finish: () -> Unit, cycleMode: () -> Unit, mute: () -> Unit) {
        val ticks = 41
        val haptic = LocalHapticFeedback.current
        val latestVolume = rememberUpdatedState(value)
        Box(Modifier.size(diameter).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            fun volumeAt(position: Offset): Int {
                var angle = Math.toDegrees(atan2((position.y - size.height / 2).toDouble(), (position.x - size.width / 2).toDouble())); if (angle < 0) angle += 360
                var sweep = (angle - 135 + 360) % 360; if (sweep > 270) sweep = if (sweep < 315) 270.0 else 0.0
                return (sweep / 270 * 80).roundToInt().coerceIn(0, 80)
            }
            var dragAccepted = false
            var lastHapticValue = latestVolume.value
            detectDragGestures(
                onDragStart = { position ->
                    val touchedVolume = volumeAt(position)
                    dragAccepted = abs(touchedVolume - latestVolume.value) <= 5
                    lastHapticValue = latestVolume.value
                    if (!dragAccepted) Log.w("OnkyoEiscp", "Rejected unsafe volume drag: current=${latestVolume.value}, touched=$touchedVolume")
                },
                onDragEnd = { if (dragAccepted) finish(); dragAccepted = false },
                onDragCancel = { dragAccepted = false }
            ) { event, _ ->
                if (!dragAccepted) { event.consume(); return@detectDragGestures }
                val next = volumeAt(event.position)
                if (next != lastHapticValue) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lastHapticValue = next }
                change(next); event.consume()
            }
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center; val outer = size.minDimension * .485f; val active = (value / 80f * (ticks - 1)).roundToInt()
                drawCircle(Brush.radialGradient(listOf(Color(0xFF29303A), Color(0xFF11161C), Color(0xFF080B0F))), outer, c); drawCircle(Color(0xFF323943), outer, c, style = Stroke(size.width * .008f))
                repeat(ticks) { i ->
                    val r = (135f + 270f * i / (ticks - 1)) * PI / 180; val inner = outer * if (i % 5 == 0) .79f else .82f; val end = outer * .94f
                    val start = Offset(c.x + cos(r).toFloat() * inner, c.y + sin(r).toFloat() * inner)
                    val finish = Offset(c.x + cos(r).toFloat() * end, c.y + sin(r).toFloat() * end)
                    if (enabled && i <= active) {
                        drawLine(Color(0x2268C9FF), start, finish, size.width * .027f, StrokeCap.Round)
                        drawLine(Color(0x7768C9FF), start, finish, size.width * .016f, StrokeCap.Round)
                        drawLine(Color(0xFFD0F2FF), start, finish, size.width * .007f, StrokeCap.Round)
                    } else drawLine(Color(0xFF505762), start, finish, size.width * .007f, StrokeCap.Round)
                }
                val dial = outer * .76f; drawCircle(Brush.radialGradient(listOf(Color(0xFF2B323D), Color(0xFF171C24), Color(0xFF10141A))), dial, c); drawCircle(Color(0xFF343B45), dial, c, style = Stroke(size.width * .006f))
                for (groove in 1..5) drawCircle(Color(0x0DFFFFFF), dial * (0.70f + groove * .045f), c, style = Stroke(size.width * .002f))
                val r = (135f + 270f * value / 80f) * PI / 180
                val indicatorStart = Offset(c.x + cos(r).toFloat() * dial * .76f, c.y + sin(r).toFloat() * dial * .76f)
                val indicatorEnd = Offset(c.x + cos(r).toFloat() * dial * .92f, c.y + sin(r).toFloat() * dial * .92f)
                if (enabled) {
                    drawLine(Color(0x3368C9FF), indicatorStart, indicatorEnd, size.width * .050f, StrokeCap.Round)
                    drawLine(Color(0x99A7E2FF), indicatorStart, indicatorEnd, size.width * .035f, StrokeCap.Round)
                    drawLine(Color(0xFFE6F8FF), indicatorStart, indicatorEnd, size.width * .016f, StrokeCap.Round)
                } else drawLine(Color(0xFF555C64), indicatorStart, indicatorEnd, size.width * .025f, StrokeCap.Round)
            }
            Text(value.toString(), color = if (enabled) Color(0xFFDDF6FF) else Color(0xFF858B92), fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = TextStyle(shadow = Shadow(Color(0x9968C9FF), blurRadius = 12f)), modifier = Modifier.align(Alignment.Center).offset(y = (-45.5).dp))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { if (!muted) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); cycleMode() } },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); mute() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (muted && enabled) SpeakerIcon(muted = true, enabled = true)
                else MusicNoteIcon(soundProfile, enabled)
            }
        }
    }
    @Composable private fun MusicNoteIcon(soundProfile: String, enabled: Boolean) { Canvas(Modifier.size(34.dp)) {
        val color = if (!enabled) Color(0xFF626970) else when (soundProfile) {
            "optimizer_on" -> Color(0xFF74C8EE)
            "optimizer_off" -> Color(0xFF6DBB82)
            else -> Color(0xFFE3E7EA)
        }
        val glow = color.copy(alpha = if (enabled) .25f else 0f)
        drawCircle(glow, size.minDimension * .24f, Offset(size.width * .34f, size.height * .72f))
        drawCircle(color, size.minDimension * .14f, Offset(size.width * .31f, size.height * .74f))
        drawLine(color, Offset(size.width * .43f, size.height * .72f), Offset(size.width * .43f, size.height * .22f), size.width * .075f, StrokeCap.Round)
        val flag = Path().apply {
            moveTo(size.width * .43f, size.height * .22f)
            cubicTo(size.width * .68f, size.height * .25f, size.width * .79f, size.height * .37f, size.width * .76f, size.height * .51f)
            cubicTo(size.width * .67f, size.height * .40f, size.width * .56f, size.height * .37f, size.width * .43f, size.height * .38f)
            close()
        }
        drawPath(flag, color)
    } }
    @Composable private fun SpeakerIcon(muted: Boolean, enabled: Boolean) { Canvas(Modifier.size(42.75.dp)) {
        val color = if (!enabled) Color(0xFF626970) else if (muted) Color(0xFFE28C8C) else Color(0xFF9CA8B5); val p = Path().apply { moveTo(size.width*.12f,size.height*.4f); lineTo(size.width*.34f,size.height*.4f); lineTo(size.width*.56f,size.height*.2f); lineTo(size.width*.56f,size.height*.8f); lineTo(size.width*.34f,size.height*.6f); lineTo(size.width*.12f,size.height*.6f); close() }; drawPath(p,color)
        drawArc(color,-55f,110f,false,Offset(size.width*.48f,size.height*.29f),Size(size.width*.27f,size.height*.42f),style=Stroke(1.7f)); drawArc(color,-55f,110f,false,Offset(size.width*.45f,size.height*.17f),Size(size.width*.45f,size.height*.66f),style=Stroke(1.7f)); if(muted) drawLine(color,Offset(size.width*.63f,size.height*.32f),Offset(size.width*.94f,size.height*.68f),2f)
    } }
}
