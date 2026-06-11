package com.curvecall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.curvecall.MainActivity
import com.curvecall.companion.CompanionState
import com.curvecall.companion.CompanionUiState
import com.curvecall.ui.companion.CompanionBubble
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service for companion mode.
 *
 * Keeps GPS tracking and TTS narration alive while the user interacts with
 * another navigation app (Google Maps, Waze, etc.). Displays a floating
 * overlay bubble that shows the next curve preview and allows quick controls.
 *
 * Separate from [SessionForegroundService] to allow independent lifecycle
 * management and different notification channels.
 */
class CompanionForegroundService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null

    companion object {
        const val CHANNEL_ID = "curvecue_companion"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.curvecall.action.START_COMPANION"
        const val ACTION_STOP = "com.curvecall.action.STOP_COMPANION"
        const val ACTION_UPDATE_STATE = "com.curvecall.action.UPDATE_COMPANION_STATE"

        /** Shared state flow for communication between ViewModel and Service. */
        private val _companionUiState = MutableStateFlow(CompanionUiState())
        val companionUiState: StateFlow<CompanionUiState> = _companionUiState.asStateFlow()

        /** Callback for stop action from the overlay or notification. */
        var onStopRequested: (() -> Unit)? = null

        /** Callback for verbosity cycle from the overlay. */
        var onVerbosityCycleRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, CompanionForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CompanionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateState(state: CompanionUiState) {
            _companionUiState.value = state
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = createNotification("CurveCue Companion active")
                startForeground(NOTIFICATION_ID, notification)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                showBubbleOverlay()
            }
            ACTION_STOP -> {
                removeBubbleOverlay()
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubbleOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Companion Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when CurveCue is running in companion mode"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CompanionForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CurveCue Companion")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showBubbleOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CompanionForegroundService)
            setViewTreeSavedStateRegistryOwner(this@CompanionForegroundService)

            setContent {
                val state by _companionUiState.collectAsState()
                CompanionBubble(
                    uiState = state,
                    onStop = {
                        onStopRequested?.invoke()
                    },
                    onCycleVerbosity = {
                        onVerbosityCycleRequested?.invoke()
                    }
                )
            }
        }

        bubbleView = composeView
        windowManager?.addView(composeView, params)
    }

    private fun removeBubbleOverlay() {
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        bubbleView = null
    }
}
