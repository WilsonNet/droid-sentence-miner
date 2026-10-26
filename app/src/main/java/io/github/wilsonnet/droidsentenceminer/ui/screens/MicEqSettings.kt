package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.audio.MicCalibration
import io.github.wilsonnet.droidsentenceminer.audio.MicEq
import io.github.wilsonnet.droidsentenceminer.audio.MicEqProfile
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln

/** Calibrate button, status and measured/corrected curves for the microphone fallback EQ. */
@Composable
internal fun MicEqCalibration(container: AppContainer, profile: MicEqProfile?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (profile == null) {
                "Not calibrated: only the rumble filter is on. Calibrate plays 6 s of noise through the speaker " +
                    "and records it back — keep the media volume where you play, and the room quiet."
            } else {
                "Calibrated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(profile.createdAtMs)) +
                    ". Recalibrate if you mostly play with a different speaker or case."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (profile != null && profile.measuredDb.isNotEmpty()) MicEqGraph(profile)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        val noiseSuppression = container.prefs.audioNoiseSuppression.first()
                        val result = withContext(Dispatchers.IO) {
                            MicCalibration.run(context, TextLog.SAMPLE_RATE, noiseSuppression)
                        }
                        result.profile?.let { container.prefs.setAudioMicEqProfile(it) }
                        error = result.error
                        running = false
                    }
                },
            ) { Text(if (profile == null) "Calibrate" else "Recalibrate") }
            if (running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Measuring…", style = MaterialTheme.typography.bodySmall)
            } else if (profile != null) {
                TextButton(onClick = { scope.launch { container.prefs.setAudioMicEqProfile(null) } }) { Text("Reset") }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Measured speaker → mic response (grey) and what the fallback hears after the EQ (accent), ±24 dB. */
@Composable
private fun MicEqGraph(profile: MicEqProfile) {
    val measured = profile.measuredDb
    val freqs = MicEq.plotFreqs(measured.size)
    val curve = MicEq.curveDb(profile.gainsDb.toDoubleArray(), TextLog.SAMPLE_RATE, freqs)
    val raw = MaterialTheme.colorScheme.outline
    val fixed = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val lo = ln(freqs.first())
            val hi = ln(freqs.last())
            fun x(f: Double) = ((ln(f) - lo) / (hi - lo) * size.width).toFloat()
            fun y(db: Double) = (size.height / 2 - db.coerceIn(-24.0, 24.0) / 24 * size.height / 2).toFloat()
            for (f in listOf(100.0, 1000.0, 10_000.0)) drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height))
            drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
            fun line(values: (Int) -> Double, color: androidx.compose.ui.graphics.Color) {
                val path = Path()
                freqs.indices.forEach { i ->
                    if (i == 0) path.moveTo(x(freqs[i]), y(values(i))) else path.lineTo(x(freqs[i]), y(values(i)))
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            line({ measured[it] }, raw)
            line({ measured[it] + curve[it] }, fixed)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("100 Hz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Grey: speaker as heard · Colour: corrected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("10 kHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
