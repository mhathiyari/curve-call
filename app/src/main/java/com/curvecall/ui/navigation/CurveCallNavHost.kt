package com.curvecall.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.curvecall.ui.about.AboutScreen
import com.curvecall.ui.destination.DestinationScreen
import com.curvecall.ui.home.HomeScreen
import com.curvecall.ui.preview.RoutePreviewScreen
import com.curvecall.ui.regions.RegionScreen
import com.curvecall.ui.session.SessionScreen
import com.curvecall.ui.settings.SettingsScreen

/**
 * Top-level navigation host for CurveCall.
 * Routes: Home -> Session, Home -> Settings, Settings -> About.
 * Includes enter/exit transitions for a polished feel.
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
        composable(
            NavRoutes.HOME,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(200)) }
        ) {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToDestination = {
                    navController.navigate(NavRoutes.DESTINATION)
                },
                onNavigateToRoutePreview = {
                    navController.navigate(NavRoutes.ROUTE_PREVIEW)
                }
            )
        }

        composable(
            NavRoutes.SESSION,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(350)
                ) + fadeIn(tween(300))
            },
            exitTransition = { fadeOut(tween(200)) },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            SessionScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            NavRoutes.SETTINGS,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToAbout = {
                    navController.navigate(NavRoutes.ABOUT)
                },
                onNavigateToRegions = {
                    navController.navigate(NavRoutes.REGIONS)
                }
            )
        }

        composable(
            NavRoutes.ABOUT,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            NavRoutes.REGIONS,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            RegionScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            NavRoutes.DESTINATION,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(350)
                ) + fadeIn(tween(300))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            DestinationScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onDestinationConfirmed = { _, _, _ ->
                    navController.navigate(NavRoutes.ROUTE_PREVIEW)
                },
                onLoadGpx = {
                    // Navigate back to home where the GPX picker lives
                    navController.popBackStack()
                }
            )
        }

        composable(
            NavRoutes.ROUTE_PREVIEW,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(350)
                ) + fadeIn(tween(300))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(300)
                ) + fadeOut(tween(200))
            }
        ) {
            RoutePreviewScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onStartSession = {
                    navController.navigate(NavRoutes.SESSION) {
                        // Clear back stack up to home so "back" from session goes home
                        popUpTo(NavRoutes.HOME)
                    }
                }
            )
        }
    }
}
