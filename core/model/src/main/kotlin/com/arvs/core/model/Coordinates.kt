package com.arvs.core.model

/**
 * The resolution-independent coordinate system ratified in MASTER_SPECIFICATION_v3.0 §13.1.
 *
 * §13.1's binding requirement is that layer transforms, positions and sizes are *never*
 * expressed or stored in raw device or export pixels. Canvas space is normalised to canvas
 * height, and a single explicit scalar — [Canvas.pixelsPerLogicalUnit] — is threaded
 * through the one place that genuinely needs a true pixel radius: blur kernels, glow radii,
 * pixelation cell size, scanline spacing.
 *
 * The consequence §13.1 is protecting is concrete: a project authored at draft resolution
 * and exported at 4K must produce identical composition geometry and identical *relative*
 * effect scale — a "medium blur" must not become four times sharper at export.
 *
 * No renderer exists yet (that is Phase 2, §130). §129 nonetheless requires this primitive
 * to be built and tested in Phase 1, so Phase 2 inherits it proven rather than inventing it
 * under deadline.
 */
public data class Canvas(
    public val logicalWidth: Double,
    public val logicalHeight: Double,
) {
    init {
        require(logicalWidth > 0.0) { "logicalWidth must be positive: $logicalWidth" }
        require(logicalHeight > 0.0) { "logicalHeight must be positive: $logicalHeight" }
    }

    /** Width ÷ height. Independent of pixel resolution, by construction. */
    public val aspectRatio: Double get() = logicalWidth / logicalHeight

    /**
     * §13.1: `pixelsPerLogicalUnit = actualRenderResolution / logicalResolution`.
     *
     * Derived from the *height*, because §13.1 normalises canvas space to height. Deriving
     * it from width would silently distort every pixel-domain effect whenever the render
     * target's aspect ratio differs from the canvas's.
     */
    public fun pixelsPerLogicalUnit(actualRenderPixelHeight: Int): Double {
        require(actualRenderPixelHeight > 0) {
            "actualRenderPixelHeight must be positive: $actualRenderPixelHeight"
        }
        return actualRenderPixelHeight / logicalHeight
    }

    /** Converts a length in logical units to pixels at a given render height. */
    public fun logicalToPixels(logicalLength: Double, actualRenderPixelHeight: Int): Double =
        logicalLength * pixelsPerLogicalUnit(actualRenderPixelHeight)

    /** Converts a pixel length back to logical units. Inverse of [logicalToPixels]. */
    public fun pixelsToLogical(pixelLength: Double, actualRenderPixelHeight: Int): Double =
        pixelLength / pixelsPerLogicalUnit(actualRenderPixelHeight)

    public companion object {
        /**
         * The height-normalised convention §13.1 states first: canvas height = 1.0.
         *
         * §13.1 permits an equivalent fixed logical resolution (e.g. 1080 units tall); both
         * satisfy the binding requirement. This project uses height = 1.0 so that
         * `pixelsPerLogicalUnit` is exactly the render height, leaving no scale factor to
         * misremember.
         */
        public fun ofAspectRatio(widthOverHeight: Double): Canvas {
            require(widthOverHeight > 0.0) { "aspect ratio must be positive: $widthOverHeight" }
            return Canvas(logicalWidth = widthOverHeight, logicalHeight = 1.0)
        }

        /** 16:9 landscape. */
        public val LANDSCAPE_16_9: Canvas = ofAspectRatio(16.0 / 9.0)

        /** 9:16 vertical — the §109 social default. */
        public val VERTICAL_9_16: Canvas = ofAspectRatio(9.0 / 16.0)

        /** 1:1 square. */
        public val SQUARE_1_1: Canvas = ofAspectRatio(1.0)
    }
}

/**
 * The unit a numeric parameter is expressed in (§13.1).
 *
 * §13.1 makes this **mandatory metadata** on every Float/Vec2/Vec3 parameter, core and
 * plugin alike, and §63.1 requires the Plugin Validator to reject a manifest that omits it.
 * It is declared here, in the Core Data Model, so there is exactly one definition for both.
 */
public enum class ParameterUnit {
    /** Canvas-relative; scales with `pixelsPerLogicalUnit`. The default for geometry. */
    LOGICAL_UNIT,

    /** A 0–1 quantity: opacity, mix amounts, normalised levels. Resolution-independent. */
    NORMALIZED_01,

    /**
     * Deliberately resolution-dependent. §13.1 marks this rare and requires the choice to be
     * explicitly justified in the effect's or plugin's documentation.
     */
    RAW_PIXEL,
}

/**
 * Whether a parameter may differ between draft preview and export (§25, §86.1).
 *
 * §86.1 draws the line precisely: a **quality knob** changes how precisely something is
 * rendered, a **content knob** changes *what is rendered*. Reducing a content knob for
 * draft performance is not a quality reduction — it is rendering different content, which
 * is the "looks correct in editor, different after export" failure §9 forbids.
 *
 * Particle count and any procedural seed are the canonical content knobs (§33).
 */
public enum class KnobClass {
    /** Safe to reduce in draft preview; always restored at export and in Ultra preview. */
    QUALITY_KNOB,

    /** Must be bit-identical across every preview quality mode and export. */
    CONTENT_KNOB,
}
