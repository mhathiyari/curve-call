package com.curvecall.ui.settings

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.curvecall.engine.types.DrivingMode
import com.curvecall.engine.types.SpeedUnit
import com.curvecall.ui.theme.CurveCallPrimary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings screen composable implementing ALL settings from PRD Section 8.1.
 *
 * Organized into sections:
 * - General (Mode, Units, Verbosity)
 * - Speed Advisories (Lateral G, Look-ahead)
 * - Audio (TTS voice, speech rate, narrate straights, audio ducking)
 * - Motorcycle-only (Lean angle narration, Surface warnings)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Initialize TTS to enumerate available voices
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.let { engine ->
                    val voices = engine.voices
                        ?.filter { it.locale.language == Locale.getDefault().language }
                        ?.sortedBy { it.name }
                        ?.toList() ?: emptyList()
                    viewModel.updateAvailableVoices(voices)
                }
            }
        }
        ttsEngine = tts
        onDispose {
            tts.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ===== GENERAL SECTION =====
            SectionHeader("General")

            // Mode: Car / Motorcycle
            SettingLabel("Driving Mode")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = uiState.drivingMode == DrivingMode.CAR,
                    onClick = { viewModel.setDrivingMode(DrivingMode.CAR) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Car")
                }
                SegmentedButton(
                    selected = uiState.drivingMode == DrivingMode.MOTORCYCLE,
                    onClick = { viewModel.setDrivingMode(DrivingMode.MOTORCYCLE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Motorcycle")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Units: mph / km/h
            SettingLabel("Speed Units")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = uiState.speedUnits == SpeedUnit.KMH,
                    onClick = { viewModel.setSpeedUnits(SpeedUnit.KMH) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("km/h")
                }
                SegmentedButton(
                    selected = uiState.speedUnits == SpeedUnit.MPH,
                    onClick = { viewModel.setSpeedUnits(SpeedUnit.MPH) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("mph")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Verbosity: Minimal / Standard / Detailed
            SettingLabel("Narration Verbosity")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = uiState.verbosity == 1,
                    onClick = { viewModel.setVerbosity(1) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) {
                    Text("Minimal")
                }
                SegmentedButton(
                    selected = uiState.verbosity == 2,
                    onClick = { viewModel.setVerbosity(2) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) {
                    Text("Standard")
                }
                SegmentedButton(
                    selected = uiState.verbosity == 3,
                    onClick = { viewModel.setVerbosity(3) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) {
                    Text("Detailed")
                }
            }

            SettingDivider()

            // ===== SPEED ADVISORIES SECTION =====
            SectionHeader("Speed Advisories")

            // Lateral G threshold slider (0.20-0.50)
            SettingLabel(
                "Lateral G Threshold",
                description = "Higher = faster speed advisories. " +
                    "Default: ${if (uiState.drivingMode == DrivingMode.CAR) "0.35g (car)" else "0.25g (motorcycle)"}"
            )
            SliderWithValue(
                value = uiState.lateralG.toFloat(),
                onValueChange = { viewModel.setLateralG(it.toDouble()) },
                valueRange = 0.20f..0.50f,
                steps = 29,
                valueLabel = { String.format("%.2fg", it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Look-ahead time slider (5-15 seconds)
            SettingLabel(
                "Look-Ahead Time",
                description = "How far ahead to announce curves. " +
                    "Default: ${if (uiState.drivingMode == DrivingMode.CAR) "8s (car)" else "10s (motorcycle)"}"
            )
            SliderWithValue(
                value = uiState.lookAheadTime.toFloat(),
                onValueChange = { viewModel.setLookAheadTime(it.toDouble()) },
                valueRange = 5.0f..15.0f,
                steps = 9,
                valueLabel = { "${it.roundToInt()}s" }
            )

            SettingDivider()

            // ===== AUDIO SECTION =====
            SectionHeader("Audio")

            // TTS Voice Picker
            if (uiState.availableVoices.isNotEmpty()) {
                SettingLabel("TTS Voice")
                VoicePicker(
                    voices = uiState.availableVoices,
                    selectedVoiceName = uiState.ttsVoiceName,
                    onVoiceSelected = { viewModel.setTtsVoiceName(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // TTS Speech Rate slider (0.5x - 2.0x)
            SettingLabel("Speech Rate")
            SliderWithValue(
                value = uiState.ttsSpeechRate,
                onValueChange = { viewModel.setTtsSpeechRate(it) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                valueLabel = { String.format("%.1fx", it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Narrate Straights toggle
            SettingToggle(
                label = "Narrate Straights",
                description = "Announce straight sections between curves",
                checked = uiState.narrateStraights,
                onCheckedChange = { viewModel.setNarrateStraights(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Audio Ducking toggle
            SettingToggle(
                label = "Audio Ducking",
                description = "Lower other app audio during narration",
                checked = uiState.audioDucking,
                onCheckedChange = { viewModel.setAudioDucking(it) }
            )

            // ===== MOTORCYCLE-ONLY SECTION =====
            if (uiState.drivingMode == DrivingMode.MOTORCYCLE) {
                SettingDivider()
                SectionHeader("Motorcycle")

                // Lean angle narration toggle
                SettingToggle(
                    label = "Lean Angle Narration",
                    description = "Announce lean angle with curve calls",
                    checked = uiState.leanAngleNarration,
                    onCheckedChange = { viewModel.setLeanAngleNarration(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Surface warnings toggle
                SettingToggle(
                    label = "Surface Warnings",
                    description = "Warn about non-paved surfaces using OSM data",
                    checked = uiState.surfaceWarnings,
                    onCheckedChange = { viewModel.setSurfaceWarnings(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ===== Reusable setting components =====

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = CurveCallPrimary,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
    )
}

@Composable
private fun SettingLabel(
    label: String,
    description: String? = null
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground
    )
    if (description != null) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    } else {
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun SettingToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = CurveCallPrimary
            )
        )
    }
}

@Composable
private fun SliderWithValue(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = CurveCallPrimary,
                activeTrackColor = CurveCallPrimary
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = valueLabel(value),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(56.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(
    voices: List<android.speech.tts.Voice>,
    selectedVoiceName: String?,
    onVoiceSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedVoice = voices.find { it.name == selectedVoiceName }
    val displayName = selectedVoice?.name ?: "System Default"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.name) },
                    onClick = {
                        onVoiceSelected(voice.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingDivider() {
    Divider(
        modifier = Modifier.padding(vertical = 20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    )
}
