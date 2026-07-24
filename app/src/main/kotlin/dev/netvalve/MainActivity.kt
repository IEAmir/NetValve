package dev.netvalve

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import dev.netvalve.service.BatteryOptimizations
import dev.netvalve.service.VpnController
import dev.netvalve.ui.navigation.AppActions
import dev.netvalve.ui.navigation.NetValveNavGraph
import dev.netvalve.ui.theme.NetValveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose host. It owns the pieces that legitimately need an
 * Activity — the VPN-consent result contract, the notifications permission, and
 * intent launches — and passes them into the Compose tree as [AppActions] so the
 * screens/ViewModels stay free of Activity plumbing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var controller: VpnController

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) controller.start()
        }

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            NetValveTheme {
                val actions = remember { buildActions() }
                NetValveNavGraph(actions = actions)
            }
        }
    }

    private fun buildActions() = AppActions(
        toggleVpn = { enable ->
            if (enable) {
                val consent = controller.consentIntent()
                if (consent != null) consentLauncher.launch(consent) else controller.start()
            } else {
                controller.stop()
            }
        },
        restartTunnel = { controller.restart() },
        requestUsageAccess = {
            runCatching {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        requestBatteryExemption = {
            runCatching { startActivity(BatteryOptimizations.requestExemptionIntent(this)) }
        },
        openVendorSettings = {
            BatteryOptimizations.vendorHint(this)?.settingsIntent?.let { runCatching { startActivity(it) } }
        },
        exportLogs = { text -> shareText(text) },
    )

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NetValve logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Export logs")) }
    }
}
