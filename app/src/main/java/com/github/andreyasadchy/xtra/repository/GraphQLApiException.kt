package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import java.io.IOException

/** A GraphQL response contained an API error or did not have the data its query requires. */
class GraphQLApiException(
    message: String,
    val operation: String? = null,
) : IOException(sanitizeLiveNotificationTechnicalMessage(message) ?: "GraphQL API error")
