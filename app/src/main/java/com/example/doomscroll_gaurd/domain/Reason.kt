package com.example.doomscroll_gaurd.domain

/**
 * The reason a user provides when claiming a purposeful-use extension on the overlay.
 *
 * No Android imports — this enum is part of the pure domain layer and can be
 * instantiated and tested on the JVM without any Android runtime.
 */
enum class Reason {
    MESSAGING,
    WORK_RELATED,
    OTHER,
}
