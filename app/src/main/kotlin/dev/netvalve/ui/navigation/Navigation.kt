package dev.netvalve.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.netvalve.ui.appdetail.AppDetailScreen
import dev.netvalve.ui.apps.AppsScreen
import dev.netvalve.ui.dashboard.DashboardScreen
import dev.netvalve.ui.logs.LogsScreen
import dev.netvalve.ui.stats.StatsScreen

/**
 * Activity-provided callbacks that require an Activity/Context (VPN consent,
 * settings intents, share sheet). Passed down so ViewModels/screens stay pure.
 */
data class AppActions(
    val toggleVpn: (Boolean) -> Unit,
    val restartTunnel: () -> Unit,
    val requestUsageAccess: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val openVendorSettings: () -> Unit,
    val exportLogs: (String) -> Unit,
)

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Destination("dashboard", "Dashboard", Icons.Filled.Shield)
    data object Apps : Destination("apps", "Apps", Icons.Filled.Apps)
    data object Stats : Destination("stats", "Stats", Icons.Filled.QueryStats)
    data object Logs : Destination("logs", "Logs", Icons.Filled.Article)

    companion object {
        val bottomBar = listOf(Dashboard, Apps, Stats, Logs)
        fun appDetail(pkg: String) = "appdetail/$pkg"
        const val APP_DETAIL_ROUTE = "appdetail/{package}"
    }
}

@Composable
fun NetValveNavGraph(actions: AppActions) {
    val nav = rememberNavController()
    Scaffold(
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar {
                Destination.bottomBar.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    actions = actions,
                    onOpenApps = { nav.navigate(Destination.Apps.route) },
                    onOpenApp = { pkg -> nav.navigate(Destination.appDetail(pkg)) },
                )
            }
            composable(Destination.Apps.route) {
                AppsScreen(onOpenApp = { pkg -> nav.navigate(Destination.appDetail(pkg)) })
            }
            composable(Destination.Stats.route) { StatsScreen() }
            composable(Destination.Logs.route) { LogsScreen(actions = actions) }
            composable(
                route = Destination.APP_DETAIL_ROUTE,
                arguments = listOf(navArgument("package") { type = NavType.StringType }),
            ) { entry ->
                AppDetailScreen(
                    packageName = entry.arguments?.getString("package").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
