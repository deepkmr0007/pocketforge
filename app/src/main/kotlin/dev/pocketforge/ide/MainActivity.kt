package dev.pocketforge.ide

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pocketforge.ide.feature.ai.AiScreen
import dev.pocketforge.ide.feature.builds.BuildScreen
import dev.pocketforge.ide.feature.builds.TestScreen
import dev.pocketforge.ide.feature.editor.EditorScreen
import dev.pocketforge.ide.feature.help.HelpScreen
import dev.pocketforge.ide.feature.home.HomeScreen
import dev.pocketforge.ide.feature.privacy.PrivacyScreen
import dev.pocketforge.ide.feature.projects.NewProjectScreen
import dev.pocketforge.ide.feature.projects.ProjectScreen
import dev.pocketforge.ide.feature.search.SearchScreen
import dev.pocketforge.ide.feature.settings.SettingsScreen
import dev.pocketforge.ide.feature.snapshots.SnapshotScreen
import dev.pocketforge.ide.ui.ConsentDialog
import dev.pocketforge.ide.ui.PocketForgeTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as PocketForgeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ignore touches while another app draws over us (protects consent and install taps).
        window.decorView.filterTouchesWhenObscured = true
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            PocketForgeTheme(settings.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                    ConsentHost()
                }
            }
        }
    }

    override fun onStart() { super.onStart(); container.consentBroker.onUiVisible() }
    override fun onStop() { container.consentBroker.onUiHidden(); super.onStop() }
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PocketForgeApp).container

/** Renders whichever consent request is pending, wherever the user is in the app. */
@Composable
private fun ConsentHost() {
    val c = appContainer()
    val pending by c.consentBroker.pending.collectAsStateWithLifecycle()
    pending?.let { p -> ConsentDialog(p.request) { d -> p.answer(d) } }
}

object Routes {
    const val HOME = "home"
    const val NEW = "new"
    fun project(pid: String) = "project/$pid"
    fun editor(pid: String, path: String? = null, line: Int = 0) = "editor/$pid?path=${Uri.encode(path ?: "")}&line=$line"
    fun search(pid: String) = "search/$pid"
    fun build(pid: String) = "build/$pid"
    fun tests(pid: String) = "tests/$pid"
    fun ai(pid: String) = "ai/$pid"
    fun snapshots(pid: String) = "snapshots/$pid"
    const val PRIVACY = "privacy"
    const val SETTINGS = "settings"
    const val HELP = "help"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val pidArg = listOf(navArgument("pid") { type = NavType.StringType })
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(go) }
        composable(Routes.NEW) { NewProjectScreen(onCreated = { pid -> nav.popBackStack(); go(Routes.project(pid)) }, onBack = back) }
        composable("project/{pid}", pidArg) { ProjectScreen(it.arguments!!.getString("pid")!!, go, back) }
        composable(
            "editor/{pid}?path={path}&line={line}",
            pidArg + listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("line") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) {
            val a = it.arguments!!
            EditorScreen(a.getString("pid")!!, a.getString("path").orEmpty().ifEmpty { null }, a.getInt("line"), go, back)
        }
        composable("search/{pid}", pidArg) { SearchScreen(it.arguments!!.getString("pid")!!, go, back) }
        composable("build/{pid}", pidArg) { BuildScreen(it.arguments!!.getString("pid")!!, go, back) }
        composable("tests/{pid}", pidArg) { TestScreen(it.arguments!!.getString("pid")!!, go, back) }
        composable("ai/{pid}", pidArg) { AiScreen(it.arguments!!.getString("pid")!!, go, back) }
        composable("snapshots/{pid}", pidArg) { SnapshotScreen(it.arguments!!.getString("pid")!!, back) }
        composable(Routes.PRIVACY) { PrivacyScreen(back) }
        composable(Routes.SETTINGS) { SettingsScreen(back) }
        composable(Routes.HELP) { HelpScreen(back) }
    }
}
