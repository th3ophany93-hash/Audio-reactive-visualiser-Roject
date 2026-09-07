package com.arvs.app.debug

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.arvs.audio.decoder.AssetDecodeSource
import com.arvs.audio.decoder.AudioDecoder
import com.arvs.core.assets.AssetRegistry
import com.arvs.core.assets.AssetStorage
import com.arvs.core.assets.DerivedPreviewCache
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.MemoryPressureDispatcher
import com.arvs.core.diagnostics.MemoryPressureLevel
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.model.AssetUri
import com.arvs.core.model.Outcome
import com.arvs.core.assets.WaveformPeaks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Phase 1 debug harness (§129: Phase 1 ships only a debug harness inside `:app`).
 *
 * It exists to prove the import path end to end on a real device — pick a file, take a
 * persistable SAF permission, hash it, decode it, build §82.1's tier-2 peaks, draw them —
 * and to be the place `onTrimMemory` is translated into §98.1's platform-neutral levels.
 * It is not the Trim editor of §16; that is Phase 2 (§103), and no `ui` module exists yet.
 */
@AndroidEntryPoint
class DebugHarnessActivity : ComponentActivity() {

    @Inject lateinit var registry: AssetRegistry
    @Inject lateinit var storage: AssetStorage
    @Inject lateinit var decoder: AudioDecoder
    @Inject lateinit var previewCache: DerivedPreviewCache
    @Inject lateinit var memoryPressure: MemoryPressureDispatcher
    @Inject lateinit var logger: Logger

    private var status by mutableStateOf("Pick an audio file to import.")
    private var peaks by mutableStateOf<WaveformPeaks?>(null)

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            status = "Import cancelled."
        } else {
            importAndBuildPeaks(AssetUri(uri.toString()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen(
                        status = status,
                        peaks = peaks,
                        onPick = { pickAudio.launch(arrayOf("audio/*")) },
                    )
                }
            }
        }
    }

    /**
     * §17.1's stage 1, end to end.
     *
     * Import, hash and peak-building all happen off the main thread; §14.1's prohibition on
     * blocking is about audio, but a UI thread stalled on a 300 MB hash is its own defect.
     */
    private fun importAndBuildPeaks(uri: AssetUri) {
        lifecycleScope.launch {
            status = "Importing…"
            val ref = when (val imported = registry.import(uri)) {
                is Outcome.Failure -> {
                    // §97: the category and the specific message, never "something went wrong".
                    status = "${imported.error.category}: ${imported.error.message}\n" +
                        (imported.error.recoveryHint ?: "")
                    return@launch
                }
                is Outcome.Success -> imported.value
            }

            status = "Decoding ${ref.displayName ?: "asset"}…"
            val source = withContext(Dispatchers.IO) {
                decoder.decodeAll(AssetDecodeSource(ref, storage))
            }
            when (source) {
                is Outcome.Failure -> {
                    status = "${source.error.category}: ${source.error.message}\n" +
                        (source.error.recoveryHint ?: "")
                    return@launch
                }
                is Outcome.Success -> Unit
            }

            status = "Building waveform…"
            when (val built = withContext(Dispatchers.Default) {
                previewCache.peaksFor(ref.hash, source.value)
            }) {
                is Outcome.Failure -> status = "${built.error.category}: ${built.error.message}"
                is Outcome.Success -> {
                    peaks = built.value
                    val format = source.value.format
                    status = "${ref.displayName ?: ref.uri}\n" +
                        "${format.sampleRateHz} Hz · ${format.channelCount} ch · " +
                        "${"%.1f".format(built.value.duration.seconds)} s · " +
                        "${built.value.levels.size} pyramid levels"
                }
            }
        }
    }

    /**
     * §98.1's platform boundary.
     *
     * `ComponentCallbacks2`'s levels are translated here into the three
     * [MemoryPressureLevel]s, so `core:diagnostics` stays free of Android (§116.1). The
     * mapping takes the *more* severe reading where it is ambiguous: over-reacting costs
     * regeneration work, which every registrable resource can do by construction, while
     * under-reacting ends with the process killed.
     *
     * **Platform note.** §98.1 names `ComponentCallbacks2.onTrimMemory` specifically, and on
     * this project's ratified `minSdk 35` (§6.1) every level except `TRIM_MEMORY_UI_HIDDEN`
     * is deprecated — modern Android may deliver only that one. The deprecated constants are
     * still the only names for the levels an older-behaving platform can send, so they are
     * used deliberately and the callback is written to be exhaustive rather than to assume
     * which levels arrive. The suppression is scoped to this function and is not a decision
     * to ignore the deprecation: whether §98.1's mechanism should move to
     * `ActivityManager.getMyMemoryState` polling on API 35+ is a specification question,
     * flagged rather than answered here.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressureLevel.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressureLevel.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressureLevel.MODERATE
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressureLevel.MODERATE
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.LOW
        }
        memoryPressure.dispatch(pressure)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logger.forSubsystem(Subsystem.PROJECT).debug("configuration-changed", "Configuration changed")
    }
}

@Composable
private fun HarnessScreen(status: String, peaks: WaveformPeaks?, onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Phase 1 debug harness", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onPick) { Text("Import audio") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        peaks?.let { WaveformView(peaks = it) }
    }
}
