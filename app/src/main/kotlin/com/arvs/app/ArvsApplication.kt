package com.arvs.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Composition root for the application.
 *
 * Hilt is the ratified dependency-injection framework (MASTER_SPECIFICATION_v3.0 §6.1,
 * Appendix B U-1). Per §157.1, the platform singletons this project is permitted to own
 * — the audio engine, and later the GL context holder — are injected from here rather
 * than reached for through bare `object`/static fields. That distinction (explicit,
 * owned, testable) is the rule §157.1 actually enforces.
 */
@HiltAndroidApp
class ArvsApplication : Application()
