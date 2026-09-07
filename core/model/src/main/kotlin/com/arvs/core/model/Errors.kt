package com.arvs.core.model

/**
 * The error taxonomy of MASTER_SPECIFICATION_v3.0 §97.
 *
 * §97's requirement is blunt: *never display only "Something went wrong."* Every failure
 * path specified anywhere in the specification maps to one of these categories, and §97
 * states plainly that no failure path is permitted to bypass the taxonomy with a generic
 * message.
 *
 * The categories are modelled as a closed set rather than free-form strings so that a new
 * failure mode cannot be introduced without deciding, explicitly, which category it belongs
 * to — and so `when` expressions over it stay exhaustive as the codebase grows.
 */
public enum class ErrorCategory {
    IMPORT_ERROR,
    DECODER_ERROR,
    AUDIO_ANALYSIS_ERROR,
    GPU_ERROR,
    SHADER_ERROR,
    OUT_OF_MEMORY,
    EXPORT_ERROR,
    CODEC_ERROR,
    PROJECT_CORRUPTION,
    PERMISSION_ERROR,
    PLUGIN_ERROR,
    PLUGIN_INCOMPATIBILITY,
    PLUGIN_VALIDATION_ERROR,
    UNSUPPORTED_FORMAT,
}

/**
 * A categorised failure.
 *
 * [category] is what the system reasons about; [message] is the specific, actionable detail
 * §97 demands instead of a generic apology — the container and codec that failed, the asset
 * that could not be relinked, the permission that was revoked.
 *
 * [recoveryHint] carries the user-facing next step where one exists. §82.1's missing-asset
 * flow and §72's missing-plugin placeholder both depend on a failure being *recoverable and
 * described*, not fatal: the project must remain editable either way.
 */
public data class ArvsError(
    public val category: ErrorCategory,
    public val message: String,
    public val recoveryHint: String? = null,
    public val cause: Throwable? = null,
) {
    init {
        require(message.isNotBlank()) {
            "ArvsError requires a specific message — §97 forbids generic failure reporting."
        }
    }

    override fun toString(): String = buildString {
        append(category.name).append(": ").append(message)
        recoveryHint?.let { append(" (").append(it).append(")") }
    }
}

/**
 * Result of an operation that can fail with a categorised [ArvsError].
 *
 * Deliberately not `kotlin.Result`, which carries a bare `Throwable` and so cannot enforce
 * §97's requirement that every failure be categorised.
 */
public sealed interface Outcome<out T> {
    public data class Success<out T>(public val value: T) : Outcome<T>
    public data class Failure(public val error: ArvsError) : Outcome<Nothing>

    public val isSuccess: Boolean get() = this is Success

    public fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    public fun errorOrNull(): ArvsError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    public companion object {
        public fun <T> success(value: T): Outcome<T> = Success(value)

        public fun failure(
            category: ErrorCategory,
            message: String,
            recoveryHint: String? = null,
            cause: Throwable? = null,
        ): Outcome<Nothing> = Failure(ArvsError(category, message, recoveryHint, cause))
    }
}

/** Maps a success value, leaving a failure untouched. */
public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

/** Chains an operation that can itself fail. */
public inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> =
    when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Failure -> this
    }
