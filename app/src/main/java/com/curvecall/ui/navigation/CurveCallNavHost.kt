package com.curvecall.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.curvecall.ui.about.AboutScreen
import com.curvecall.ui.home.HomeScreen
import com.curvecall.ui.session.SessionScreen
import com.curvecall.ui.settings.SettingsScreen

/**
 * Top-level navigation host for CurveCall.
 * Routes: Home -> Session, Home -> Settings, Settings -> About.
 */
@Composable
fun CurveCallNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME,
        modifier = modifier
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateToSession = {
                    navController.navigate(NavRoutes.SESSION)
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                }
            )
        }

        composable(NavRoutes.SESSION) {
            SessionScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToAbout = {
                    navController.navigate(NavRoutes.ABOUT)
                }
            )
        }

        composable(NavRoutes.ABOUT) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
