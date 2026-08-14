package com.github.andreyasadchy.xtra.repository

import java.io.IOException

/** A notification API operation has no usable credentials for the path it needs. */
class MissingAuthenticationException(
    val operation: String,
) : IOException("Authentication is required for $operation")
