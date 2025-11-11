package dev.goquick.kprofiles

/**
 * Defines how the overlay task reacts when a profile resource collides with a shared one.
 */
enum class CollisionPolicy {
    WARN,
    FAIL,
    SILENT
}
