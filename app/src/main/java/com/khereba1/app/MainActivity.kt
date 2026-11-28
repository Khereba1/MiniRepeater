package com.khereba1.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF062825),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF161D24),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1C252E),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF25303B),
    error = Color(0xFFE06C75),
    onError = Color(0xFFFFFFFF)
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startRepeaterService()
        } else {
            Toast.makeText(this, R.string.msg_grant_perm, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MiniRepeaterScreen(
                        context = this,
                        darkTheme = dark,
                        onStartRequest = { ensurePermissionsAndStart() },
                        onStopRequest = { stopRepeaterService() },
                    onReapplyRouting = { reapplyRoutingService() }
                    )
                }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRepeaterService()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startRepeaterService() {
        val intent = Intent(this, RepeaterService::class.java).apply {
            action = RepeaterService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRepeaterService() {
        val intent = Intent(this, RepeaterService::class.java).apply {
            action = RepeaterService.ACTION_STOP
        }
        startService(intent)
    }

    private fun reapplyRoutingService() {
        val intent = Intent(this, RepeaterService::class.java).apply {
            action = RepeaterService.ACTION_REAPPLY_ROUTING
        }
        startService(intent)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String, toastMsg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
}

@Composable
private fun MiniRepeaterScreen(
    context: Context,
    darkTheme: Boolean,
    onStartRequest: () -> Unit,
    onStopRequest: () -> Unit,
    onReapplyRouting: () -> Unit
) {
    val uiState by RepeaterState.state.collectAsState()
    val scope = rememberCoroutineScope()

    var ssid by remember { mutableStateOf(Prefs.getSsid(context)) }
    var password by remember { mutableStateOf(Prefs.getPassword(context)) }
    var autoStart by remember { mutableStateOf(Prefs.isAutoStart(context)) }
    var routeViaVpn by remember { mutableStateOf(Prefs.isRouteViaVpn(context)) }
    var passVisible by remember { mutableStateOf(false) }
    var advExpanded by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var ssidError by remember { mutableStateOf<String?>(null) }
    var passError by remember { mutableStateOf<String?>(null) }

    val errSsidMsg = stringResource(R.string.err_ssid_prefix)
    val errPassMsg = stringResource(R.string.err_password_len)
    val pmfDoneMsg = stringResource(R.string.msg_pmf_done)
    val pmfFailMsg = stringResource(R.string.msg_pmf_fail)
    val copiedMsg = stringResource(R.string.msg_copied)

    val isActive = uiState.status == ServiceStatus.RUNNING || uiState.status == ServiceStatus.STARTING
    val statusText = when (uiState.status) {
        ServiceStatus.RUNNING -> stringResource(R.string.status_running)
        ServiceStatus.STARTING -> stringResource(R.string.status_starting)
        ServiceStatus.STOPPED -> stringResource(R.string.status_stopped)
    }
    val badgeColor = when (uiState.status) {
        ServiceStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ServiceStatus.STARTING -> Color(0xFFF59E0B)
        ServiceStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeBg = when (uiState.status) {
        ServiceStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ServiceStatus.STARTING -> Color(0xFFF59E0B).copy(alpha = 0.15f)
        ServiceStatus.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val logBg = if (darkTheme) Color(0xFF0B1015) else MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Header with inline status badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor
                    )
                }
            }

            // Hero Control Card
            FlatCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = uiState.ssid.ifBlank { ssid.ifBlank { "DIRECT-MR-WiFi" } },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.badge_band),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val qrSsidNow = uiState.ssid.ifBlank { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ssid.trim() else "" }
                        val qrPassNow = uiState.passphrase.ifBlank { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) password.trim() else "" }
                        if (qrSsidNow.isNotBlank() && qrPassNow.isNotBlank()) {
                            OutlinedButton(
                                onClick = { showQrDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_qr),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox(
                        label = stringResource(R.string.label_clients),
                        value = uiState.clients.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatBox(
                        label = stringResource(R.string.label_iface),
                        value = uiState.iface,
                        modifier = Modifier.weight(1f)
                    )
                    StatBox(
                        label = stringResource(R.string.label_upstream),
                        value = uiState.upstream,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        if (isActive) {
                            onStopRequest()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cleanSsid = ssid.trim()
                            val cleanPass = password.trim()
                            val validSsid = Regex("^DIRECT-[A-Za-z0-9]{2}-.+").matches(cleanSsid)
                            val validPass = cleanPass.length >= 8
                            ssidError = if (validSsid) null else errSsidMsg
                            passError = if (validPass) null else errPassMsg
                            if (validSsid && validPass) {
                                Prefs.saveSsid(context, cleanSsid)
                                Prefs.savePassword(context, cleanPass)
                                onStartRequest()
                            }
                        } else {
                            onStartRequest()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (isActive) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }

            // Network Settings Card
            FlatCard {
                Text(
                    text = stringResource(R.string.section_config),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                val supportsCustomP2p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val displaySsid = if (supportsCustomP2p) ssid else uiState.ssid.ifBlank { "—" }
                val displayPass = if (supportsCustomP2p) password else uiState.passphrase.ifBlank { "—" }

                if (!supportsCustomP2p) {
                    Text(
                        text = stringResource(R.string.hint_legacy_p2p),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = displaySsid,
                    onValueChange = { if (supportsCustomP2p) { ssid = it; ssidError = null } },
                    readOnly = !supportsCustomP2p,
                    enabled = if (supportsCustomP2p) !isActive else true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_ssid)) },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val toCopy = displaySsid.trim()
                                if (toCopy.isNotEmpty() && toCopy != "—") {
                                    copyToClipboard(context, "SSID", toCopy, copiedMsg)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.btn_copy), fontSize = 12.sp)
                        }
                    },
                    isError = supportsCustomP2p && ssidError != null,
                    supportingText = if (supportsCustomP2p) ssidError?.let { msg -> { Text(msg) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = displayPass,
                    onValueChange = { if (supportsCustomP2p) { password = it; passError = null } },
                    readOnly = !supportsCustomP2p,
                    enabled = if (supportsCustomP2p) !isActive else true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_password)) },
                    visualTransformation = if (passVisible || displayPass == "—") VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { passVisible = !passVisible },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = if (passVisible) stringResource(R.string.btn_hide) else stringResource(R.string.btn_show),
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    val toCopy = displayPass.trim()
                                    if (toCopy.isNotEmpty() && toCopy != "—") {
                                        copyToClipboard(context, "Password", toCopy, copiedMsg)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text(stringResource(R.string.btn_copy), fontSize = 12.sp)
                            }
                        }
                    },
                    isError = supportsCustomP2p && passError != null,
                    supportingText = if (supportsCustomP2p) passError?.let { msg -> { Text(msg) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.switch_autostart),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            Prefs.saveAutoStart(context, it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.switch_route_vpn),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.hint_route_vpn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = routeViaVpn,
                        onCheckedChange = {
                            routeViaVpn = it
                            Prefs.saveRouteViaVpn(context, it)
                            if (isActive) onReapplyRouting()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                // Collapsible Advanced section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advExpanded = !advExpanded }
                        .padding(top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.section_advanced),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (advExpanded) "▾" else "▸",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }

                if (advExpanded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { RootRouter.patchPmf() }
                                if (res.exitCode == 0 && res.output.contains("PATCHED")) {
                                    AppLog.log("PMF", "Patched p2p_supplicant.conf (pmf=0, ch=6, no_group_iface=1)")
                                    Toast.makeText(context, pmfDoneMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    AppLog.log("PMF", "Patch result: ${res.output}")
                                    Toast.makeText(context, pmfFailMsg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(stringResource(R.string.btn_fix_pmf))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.hint_pmf),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Activity Log Card
            FlatCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.error_log),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val text = uiState.logs.joinToString("\n")
                                if (text.isNotBlank()) copyToClipboard(context, "MiniRepeater Log", text, copiedMsg)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.btn_copy), fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { AppLog.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_clear_log),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val logBody = if (uiState.logs.isEmpty()) {
                    stringResource(R.string.log_empty)
                } else {
                    uiState.logs.joinToString("\n")
                }
                Text(
                    text = logBody,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(logBg)
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.footer_version),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showQrDialog) {
        val qrSsid = uiState.ssid.ifBlank { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ssid.trim() else "" }
        val qrPass = uiState.passphrase.ifBlank { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) password.trim() else "" }
        val qrBitmap = remember(qrSsid, qrPass) { generateWifiQrBitmap(qrSsid, qrPass) }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.qr_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = stringResource(R.string.qr_title),
                                modifier = Modifier.size(210.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = qrSsid,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
}

@Composable
private fun FlatCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


private fun escapeWifiQr(value: String): String =
    value.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")
        .replace("\"", "\\\"")

private fun generateWifiQrBitmap(ssid: String, password: String, sizePx: Int = 512): ImageBitmap? {
    if (ssid.isBlank()) return null
    val payload = "WIFI:T:WPA;S:${escapeWifiQr(ssid)};P:${escapeWifiQr(password)};;"
    return try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
