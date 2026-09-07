package com.arvs.core.diagnostics

/**
 * The logging subsystems named in MASTER_SPECIFICATION_v3.0 §99.
 *
 * §99 fixes this set exactly: Renderer, Audio, Analyzer, Reactive, Project, Assets,
 * Plugins, Exporter. All eight are declared here even though Phase 1 only reaches four
 * ([AUDIO], [ANALYZER], [ASSETS], [PROJECT]) — the same reasoning as §97's error
 * taxonomy in `core:model`. A closed set means a later subsystem cannot quietly invent
 * its own tag, and it means §100.1's per-subsystem CPU breakdown has a stable set of
 * buckets rather than whatever strings happened to be passed in.
 *
 * These are *diagnostic attribution* labels, not threads. §101 lists a different (and
 * deliberately different) split of execution threads; a single thread may emit spans
 * for more than one subsystem, and a single subsystem may run on more than one thread.
 */
public enum class Subsystem {
    RENDERER,
    AUDIO,
    ANALYZER,
    REACTIVE,
    PROJECT,
    ASSETS,
    PLUGINS,
    EXPORTER,
    ;

    /** Short, stable tag used in log output and report headings. */
    public val tag: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
