package com.arvs.app.di

import android.content.ContentResolver
import android.content.Context
import com.arvs.audio.decoder.AudioDecoder
import com.arvs.audio.decoder.DecoderRouter
import com.arvs.audio.decoder.MediaCodecAudioDecoder
import com.arvs.audio.decoder.WavDecoder
import com.arvs.core.assets.AssetRegistry
import com.arvs.core.assets.AssetStorage
import com.arvs.core.assets.DefaultAssetRegistry
import com.arvs.core.assets.DerivedPreviewCache
import com.arvs.core.assets.SafAssetStorage
import com.arvs.core.diagnostics.LogEvent
import com.arvs.core.diagnostics.LogLevel
import com.arvs.core.diagnostics.LogSink
import com.arvs.core.diagnostics.Logger
import com.arvs.core.diagnostics.LoggingPolicy
import com.arvs.core.diagnostics.MemoryPressureDispatcher
import com.arvs.core.diagnostics.Subsystem
import com.arvs.core.diagnostics.TimingSpanRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The composition root's wiring (§6.1, §157.1).
 *
 * This is where the two Android bindings that `core:diagnostics` deliberately does not own
 * are installed: `android.util.Log` as a [LogSink], and (from the Activity)
 * `ComponentCallbacks2` levels translated into `MemoryPressureLevel`. §116.1 keeps the
 * diagnostics module pure Kotlin so the DSP modules downstream of it stay JVM-testable; the
 * platform lives here instead, injected rather than reached for.
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugHarnessModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = Logger(
        // The debug harness is a debug build by definition. §99's rate limit applies to
        // production; throttling a diagnostic session hides the thing being diagnosed.
        policy = LoggingPolicy.DEBUG,
    ).apply { addSink(AndroidLogSink()) }

    @Provides
    @Singleton
    fun provideTimingSpanRecorder(): TimingSpanRecorder = TimingSpanRecorder()

    @Provides
    @Singleton
    fun provideMemoryPressureDispatcher(logger: Logger): MemoryPressureDispatcher =
        MemoryPressureDispatcher(logger.forSubsystem(Subsystem.PROJECT))

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideAssetStorage(contentResolver: ContentResolver): AssetStorage =
        SafAssetStorage(contentResolver)

    @Provides
    @Singleton
    fun provideAssetRegistry(storage: AssetStorage, logger: Logger): AssetRegistry =
        DefaultAssetRegistry(storage = storage, logger = logger)

    /**
     * WAV first, deliberately.
     *
     * [DecoderRouter] takes the first registered decoder that claims the sniffed format, and
     * both of these claim WAV. Preferring the pure-Kotlin one means a WAV decodes identically
     * on device and in CI — the property the §9.1 determinism suite and the golden vectors
     * depend on, since hardware decoders are not bit-exact across vendors.
     */
    @Provides
    @Singleton
    fun provideAudioDecoder(contentResolver: ContentResolver): AudioDecoder =
        DecoderRouter(listOf(WavDecoder(), MediaCodecAudioDecoder(contentResolver)))

    @Provides
    @Singleton
    fun provideDerivedPreviewCache(
        @ApplicationContext context: Context,
        logger: Logger,
        timings: TimingSpanRecorder,
    ): DerivedPreviewCache = DerivedPreviewCache(
        // Tier 2 lives in the app cache directory: §82.1 makes it derived and regenerable, so
        // the OS is free to reclaim it. Nothing in the project depends on its presence.
        rootDirectory = File(context.cacheDir, "peaks"),
        logger = logger,
        timings = timings,
    )
}

/** §99's structured events, forwarded to logcat. The Android half of `core:diagnostics`. */
private class AndroidLogSink : LogSink {
    override fun write(event: LogEvent) {
        val tag = "ARVS/${event.subsystem.tag}"
        val message = event.format()
        when (event.level) {
            LogLevel.VERBOSE -> android.util.Log.v(tag, message)
            LogLevel.DEBUG -> android.util.Log.d(tag, message)
            LogLevel.INFO -> android.util.Log.i(tag, message)
            LogLevel.WARN -> android.util.Log.w(tag, message, event.throwable)
            LogLevel.ERROR -> android.util.Log.e(tag, message, event.throwable)
        }
    }
}
