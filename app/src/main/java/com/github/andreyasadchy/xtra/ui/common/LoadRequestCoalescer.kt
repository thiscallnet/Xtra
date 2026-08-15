package com.github.andreyasadchy.xtra.ui.common

internal class LoadRequestCoalescer<Request>(
    private val start: (Request) -> Unit,
) {

    private var active = false
    private var pending: Request? = null

    fun request(request: Request, revalidate: Boolean = false) {
        if (active) {
            if (revalidate) {
                pending = request
            }
            return
        }
        active = true
        start(request)
    }

    fun complete() {
        if (!active) return
        val next = pending
        pending = null
        if (next == null) {
            active = false
        } else {
            start(next)
        }
    }
}
