package com.khereba1.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF042F2E),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF273449),
    error = Color(0xFFEF4444)
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626)
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themePref by remember { mutableStateOf(Prefs.getTheme(this)) }
            val dark = when (themePref) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MiniRepeaterScreen(
                        context = this,
                        themePref = themePref,
                        onThemeChange = { newTheme ->
                            themePref = newTheme
                            Prefs.saveTheme(this, newTheme)
                        },
                        onLangChange = { newLang ->
                            Prefs.saveLang(this, newLang)
                            recreate()
                        },
                        onStartRequest = { ensurePermissionsAndStart() },
                        onStopRequest = { stopRepeaterService() }
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
}

@Composable
private fun MiniRepeaterScreen(
    context: Context,
    themePref: String,
    onThemeChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onStartRequest: () -> Unit,
    onStopRequest: () -> Unit
) {
    val uiState by RepeaterState.state.collectAsState()
    val scope = rememberCoroutineScope()

    var ssid by remember { mutableStateOf(Prefs.getSsid(context)) }
    var password by remember { mutableStateOf(Prefs.getPassword(context)) }
    var autoStart by remember { mutableStateOf(Prefs.isAutoStart(context)) }
    var ssidError by remember { mutableStateOf<String?>(null) }
    var passError by remember { mutableStateOf<String?>(null) }

    val errSsidMsg = stringResource(R.string.err_ssid_prefix)
    val errPassMsg = stringResource(R.string.err_password_len)
    val pmfDoneMsg = stringResource(R.string.msg_pmf_done)
    val pmfFailMsg = stringResource(R.string.msg_pmf_fail)

    val isActive = uiState.status == ServiceStatus.RUNNING || uiState.status == ServiceStatus.STARTING
    val statusText = when (uiState.status) {
        ServiceStatus.RUNNING -> stringResource(R.string.status_running)
        ServiceStatus.STARTING -> stringResource(R.string.status_starting)
        ServiceStatus.STOPPED -> stringResource(R.string.status_stopped)
    }
    val dotColor = when (uiState.status) {
        ServiceStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ServiceStatus.STARTING -> Color(0xFFF59E0B)
        ServiceStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Status Card
        FlatCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell(stringResource(R.string.label_iface), uiState.iface)
                StatCell(stringResource(R.string.label_upstream), uiState.upstream)
                StatCell(stringResource(R.string.label_clients), uiState.clients.toString())
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isActive) {
                        onStopRequest()
                    } else {
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
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isActive) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // Network Settings Card
        FlatCard {
            Text(
                text = stringResource(R.string.section_config),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it; ssidError = null },
                enabled = !isActive,
                singleLine = true,
                label = { Text(stringResource(R.string.label_ssid)) },
                isError = ssidError != null,
                supportingText = ssidError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; passError = null },
                enabled = !isActive,
                singleLine = true,
                label = { Text(stringResource(R.string.label_password)) },
                isError = passError != null,
                supportingText = passError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.switch_autostart),
                    style = MaterialTheme.typography.bodyMedium
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

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { RootRouter.patchPmf() }
                        if (res.exitCode == 0 && res.output.contains("PATCHED")) {
                            AppLog.log("PMF", "Patched p2p_supplicant.conf (pmf=0, ch=6)")
                            Toast.makeText(context, pmfDoneMsg, Toast.LENGTH_LONG).show()
                        } else {
                            AppLog.log("PMF", "Patch result: ${res.output}")
                            Toast.makeText(context, pmfFailMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.btn_fix_pmf))
            }
        }

        // Theme & Language Card
        FlatCard {
            Text(
                text = stringResource(R.string.section_prefs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.theme_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChipButton(stringResource(R.string.theme_system), themePref == "system") { onThemeChange("system") }
                ChoiceChipButton(stringResource(R.string.theme_dark), themePref == "dark") { onThemeChange("dark") }
                ChoiceChipButton(stringResource(R.string.theme_light), themePref == "light") { onThemeChange("light") }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.language_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            val curLang = Prefs.getLang(context)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChipButton(stringResource(R.string.lang_system), curLang == "system") { onLangChange("system") }
                ChoiceChipButton(stringResource(R.string.lang_arabic), curLang == "ar") { onLangChange("ar") }
                ChoiceChipButton(stringResource(R.string.lang_english), curLang == "en") { onLangChange("en") }
            }
        }

        // Logs Card
        FlatCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.error_log),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { AppLog.clear() }) {
                    Text(stringResource(R.string.btn_clear_log))
                }
            }
            val logBody = if (uiState.logs.isEmpty()) {
                stringResource(R.string.log_empty)
            } else {
                uiState.logs.joinToString("\n")
            }
            Text(
                text = logBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = stringResource(R.string.developer_label) + ": " + stringResource(R.string.developer_name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FlatCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ChoiceChipButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
            Text(text, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
            Text(text, fontSize = 12.sp)
        }
    }
}
