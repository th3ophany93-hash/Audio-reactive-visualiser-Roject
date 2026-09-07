// Root build script.
//
// Its primary job in Phase 1 is NOT to build anything — it is to enforce the module
// dependency graph ratified in MASTER_SPECIFICATION_v3.0 §116.1, which requires that
// this boundary "is established BEFORE Phase 1 code lands, not retrofitted later —
// it is the concrete mechanism that prevents the 'gradual erosion into a monolith'
// failure mode §157 warns against."
//
// The check runs at configuration time, so ANY Gradle invocation fails on a violation.
// A `verifyModuleGraph` task is additionally wired into `check` for explicit CI gating.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// §116.1 — the enforced dependency graph.
//
// Direction is strictly downward. A module may depend ONLY on the modules listed
// for it here. No back-edges. Adding an edge is an architectural decision and must
// be made here, deliberately, not by adding a line to a module's build file.
// ---------------------------------------------------------------------------
val allowedEdges: Map<String, Set<String>> = mapOf(
    // core/model is the root of the stack and has no Android or GL dependencies (§116.1).
    // core/time sits beneath it: the Core Data Model (§10) stores trimIn/trimOut as
    // AudioSourceTime values, so the time domain is more primitive than the model.
    ":core:time" to emptySet(),
    ":core:model" to setOf(":core:time"),
    ":core:diagnostics" to setOf(":core:time", ":core:model"),
    ":core:assets" to setOf(":core:time", ":core:model", ":core:diagnostics"),
    ":core:project" to setOf(":core:time", ":core:model", ":core:diagnostics"),

    // audio/* (§116). Analysis writes into the cache, so analysis depends on cache —
    // never the reverse. The shared identity types (AnalysisConfig, FeatureId,
    // AnalysisCacheKey) live in core:model, because §10 already makes
    // analysisConfigHash part of the Core Data Model; that keeps the edge acyclic.
    ":audio:cache" to setOf(":core:time", ":core:model", ":core:diagnostics"),
    ":audio:beat" to setOf(":core:time", ":core:model"),
    ":audio:decoder" to setOf(":core:time", ":core:model", ":core:diagnostics", ":core:assets"),
    // audio:analysis consumes PCM, not a codec. It deliberately does NOT depend on
    // audio:decoder: the DSP must stay unit-testable on the JVM against the synthetic
    // fixtures of §119, and §15 requires these components to be independent. The
    // PcmSource/AudioFormatInfo contracts live in core:model; app wires decoder → analysis.
    ":audio:analysis" to setOf(
        ":core:time", ":core:model", ":core:diagnostics",
        ":audio:beat", ":audio:cache",
    ),
    ":audio:playback" to setOf(
        ":core:time", ":core:model", ":core:diagnostics", ":core:assets", ":audio:decoder",
    ),

    // app is the composition root (§6.1 Hilt) and is the only module permitted to
    // depend on everything. It owns the platform singletons §157.1 allows.
    ":app" to setOf(
        ":core:time", ":core:model", ":core:diagnostics", ":core:assets", ":core:project",
        ":audio:cache", ":audio:beat", ":audio:decoder", ":audio:analysis", ":audio:playback",
    ),

    // testing/* depend downward onto what they exercise and are depended on by nothing.
    ":testing:audio" to setOf(":core:time", ":core:model"),
    ":testing:golden" to setOf(":core:time", ":core:model"),
    ":testing:performance" to setOf(":core:time", ":core:model", ":core:diagnostics"),
)

// Modules belonging to later phases. Their presence in Phase 1 would be premature
// architecture (§130–§137), so their absence is asserted rather than assumed.
val modulesReservedForLaterPhases = mapOf(
    "reactive" to "Phase 3 (§131)",
    "renderer" to "Phase 2 (§130)",
    "layers" to "Phase 2 (§130)",
    "effects" to "Phase 5 (§133)",
    "plugins" to "Phase 7 (§135)",
    "timeline" to "Phase 6 (§134)",
    "export" to "Phase 8 (§136)",
    "ui" to "Phase 2+ (§103) — Phase 1 ships only a debug harness inside :app",
)

// Modules that must remain free of Android dependencies, so the DSP, data model and
// time domain stay unit-testable on the JVM without a device or Robolectric.
// core:diagnostics is pure Kotlin: structured logging, timing spans and the §97 error
// taxonomy are platform-neutral concepts. The Android bindings (android.util.Log,
// ComponentCallbacks2 for §98.1 memory pressure) are sinks installed by :app at the
// composition root — §157.1's "injected, not reached for" rule applied to logging.
val pureKotlinModules = setOf(
    ":core:time", ":core:model", ":core:diagnostics", ":core:project",
    ":audio:cache", ":audio:beat", ":audio:analysis",
    ":testing:audio", ":testing:golden",
)

// Configurations that put a module's code into a *shipped* artifact. These are governed by
// `allowedEdges` with no exception whatsoever — this is the §116.1 production graph.
val productionDependencyConfigurations = setOf(
    "api", "implementation", "compileOnly", "runtimeOnly",
)

// Configurations that exist only to compile and run tests. Nothing declared here reaches a
// production artifact. Subject to the scoped exception in decision D-2 below.
val testDependencyConfigurations = setOf(
    "testImplementation", "androidTestImplementation",
)

/**
 * Decision D-2 (ratified) — the test-only dependency exception.
 *
 * §119's audio fixtures live in `:testing:audio` precisely so that one deterministic
 * generator serves every module that needs them. Before this exception the guard treated
 * `testImplementation` exactly like `implementation`, and `allowedEdges` granted no module an
 * edge to `:testing:*` — so the fixtures compiled but nothing could consume them, and the
 * §9.1 determinism suite had no way to reach them.
 *
 * The exception is deliberately narrow, and each clause is enforced separately below:
 *
 *  - It applies **only** to the configurations in [testDependencyConfigurations].
 *  - It permits an edge **only** to a module under `:testing:`.
 *  - A production configuration depending on `:testing:*` is its own named violation, so the
 *    rule cannot be defeated by adding a testing path to `allowedEdges`.
 *  - A *test* edge to any non-testing module remains bound by `allowedEdges` exactly as
 *    before. The exception is for test fixtures, not a back door around the module graph.
 *
 * The production graph §116.1 governs is unchanged by this.
 */
fun String.isTestingModule(): Boolean = startsWith(":testing:")

gradle.projectsEvaluated {
    val violations = mutableListOf<String>()

    // 1. No module may declare a dependency outside its allowed edge set.
    //
    // Gradle materialises an intermediate project for each path segment (":core",
    // ":audio", ":testing"). Those are structural containers, not modules: they carry
    // no build script. They are held to a stricter rule instead — they must stay empty.
    subprojects.forEach { module ->
        val isStructuralContainer = !module.buildFile.exists()
        if (isStructuralContainer) {
            val containerDependencies = module.configurations
                .flatMap { configuration -> configuration.dependencies }
                .map { it.toString() }
            if (containerDependencies.isNotEmpty()) {
                violations += "${module.path} is a structural container (no build script) but " +
                    "declares dependencies — containers must stay empty."
            }
            return@forEach
        }
        val allowed = allowedEdges[module.path]
        if (allowed == null) {
            violations += "${module.path} is not declared in allowedEdges — every module must " +
                "have an explicit, reviewed position in the §116.1 graph."
            return@forEach
        }
        fun edgesFrom(configurationNames: Set<String>): Set<String> = module.configurations
            .filter { it.name in configurationNames }
            .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java) }
            .map { it.path }
            .toSet()

        val productionEdges = edgesFrom(productionDependencyConfigurations)
        val testEdges = edgesFrom(testDependencyConfigurations)

        // 1a. Production configurations are bound by allowedEdges, unconditionally.
        (productionEdges - allowed).sorted().forEach { disallowed ->
            violations += "${module.path} → $disallowed is not an allowed edge (§116.1)."
        }

        // 1b. Test fixtures must never reach a shipped artifact. Checked by name rather than
        //     left to allowedEdges, so the rule survives someone adding a :testing: path there.
        productionEdges.filter { it.isTestingModule() }.sorted().forEach { shipped ->
            violations += "${module.path} → $shipped is declared on a production configuration. " +
                ":testing:* modules are test fixtures and must never ship (decision D-2)."
        }

        // 1c. Test configurations: :testing:* is the D-2 exception; everything else is still
        //     bound by allowedEdges, so the exception cannot be used as a back door.
        (testEdges.filterNot { it.isTestingModule() }.toSet() - allowed).sorted().forEach { disallowed ->
            violations += "${module.path} → $disallowed is not an allowed edge (§116.1). The " +
                "test-only exception (D-2) covers :testing:* modules only."
        }
    }

    // 2. Pure-Kotlin modules must not acquire an Android plugin.
    subprojects.filter { it.path in pureKotlinModules }.forEach { module ->
        if (module.plugins.hasPlugin("com.android.base")) {
            violations += "${module.path} applies an Android plugin but is declared pure-Kotlin — " +
                "keeping it JVM-only is what makes it testable without a device."
        }
    }

    // 3. Modules reserved for later phases must not exist yet.
    modulesReservedForLaterPhases.forEach { (name, owningPhase) ->
        if (rootProject.file(name).isDirectory) {
            violations += "Directory '$name/' exists but belongs to $owningPhase — " +
                "creating it during Phase 1 is premature architecture."
        }
    }

    if (violations.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("Module dependency graph violates MASTER_SPECIFICATION_v3.0 §116.1:")
                violations.sorted().forEach { appendLine("  • $it") }
                appendLine()
                appendLine("Adding an edge is an architectural decision. If the edge is genuinely")
                appendLine("correct, change allowedEdges in the root build script deliberately —")
                appendLine("do not work around this check.")
            },
        )
    }
}

// Explicit CI gate. Configuration already fails on violation; this makes the check a
// named, greppable step in build logs rather than an invisible side effect.
val verifyModuleGraph by tasks.registering {
    group = "verification"
    description = "Verifies the module dependency graph against MASTER_SPECIFICATION_v3.0 §116.1."
    val moduleCount = allowedEdges.size
    val edgeCount = allowedEdges.values.sumOf { it.size }
    doLast {
        logger.lifecycle(
            "§116.1 module graph OK — $moduleCount modules, $edgeCount allowed production edges, " +
                "no back-edges; test-only edges into :testing:* permitted per decision D-2.",
        )
    }
}

tasks.register("check") {
    group = "verification"
    description = "Aggregate verification entry point for the root project."
    dependsOn(verifyModuleGraph)
}
