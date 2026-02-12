package com.curvecall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.ui.disclaimer.DisclaimerDialog
import com.curvecall.ui.navigation.CurveCallNavHost
import com.curvecall.ui.theme.CurveCallTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity that hosts all Compose UI via a NavHost.
 * Uses Hilt for dependency injection.
 *
 * On first launch, shows the safety disclaimer dialog (PRD Section 14.1).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CurveCallTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val disclaimerAccepted by userPreferences.disclaimerAccepted.collectAsState(initial = false)

                    if (!disclaimerAccepted) {
                        DisclaimerDialog(
                            onAccept = {
                                userPreferences.setDisclaimerAcceptedBlocking(true)
                            }
                        )
                    }

                    CurveCallNavHost(navController = navController)
                }
            }
        }
    }
}
