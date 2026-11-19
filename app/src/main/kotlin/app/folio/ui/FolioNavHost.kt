package app.folio.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.folio.appContainer
import app.folio.ui.about.AboutScreen
import app.folio.ui.home.HomeScreen
import app.folio.ui.organize.OrganizeScreen
import app.folio.ui.viewer.ViewerScreen

/** Something handed to Folio by another app: one PDF to view, or files to combine. */
sealed interface IncomingDocument {
    data class View(val uri: Uri) : IncomingDocument
    data class Organize(val uris: List<Uri>) : IncomingDocument
}

private object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer?uri={uri}"
    const val ORGANIZE = "organize?files={files}&split={split}"
    const val ABOUT = "about"
    fun viewer(uri: Uri) = "viewer?uri=${Uri.encode(uri.toString())}"
    fun organize(handoffId: String, split: Boolean = false) = "organize?files=$handoffId&split=$split"
}

@Composable
fun FolioNavHost(incomingDocument: IncomingDocument?, onIncomingConsumed: () -> Unit) {
    val nav = rememberNavController()
    val container = LocalContext.current.appContainer

    // Note: no launchSingleTop for documents — it matches on the route pattern, not the document,
    // so it would silently keep showing the previous one.
    LaunchedEffect(incomingDocument) {
        when (incomingDocument) {
            is IncomingDocument.View -> nav.navigate(Routes.viewer(incomingDocument.uri))
            is IncomingDocument.Organize -> nav.navigate(Routes.organize(container.handoff.put(incomingDocument.uris)))
            null -> return@LaunchedEffect
        }
        onIncomingConsumed()
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(initialOffsetX = { it / 8 }) + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 8 }) + fadeOut() },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenDocument = { nav.navigate(Routes.viewer(it)) },
                onOrganize = { id -> nav.navigate(Routes.organize(id)) },
                onAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
            )
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            ViewerScreen(
                uri = uri,
                onBack = { nav.popBackStack() },
                // Replace this viewer with the newly saved copy.
                onOpenDocument = { newUri ->
                    nav.navigate(Routes.viewer(newUri)) {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                },
                onOrganize = { split -> nav.navigate(Routes.organize(container.handoff.put(listOf(uri)), split)) },
            )
        }
        composable(
            Routes.ORGANIZE,
            arguments = listOf(
                navArgument("files") { type = NavType.StringType; defaultValue = "" },
                navArgument("split") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            OrganizeScreen(
                handoffId = entry.arguments?.getString("files").orEmpty(),
                openSplit = entry.arguments?.getBoolean("split") == true,
                onBack = { nav.popBackStack() },
                onOpenDocument = { saved -> nav.navigate(Routes.viewer(saved)) { popUpTo(entry.destination.id) { inclusive = true } } },
            )
        }
    }
}
