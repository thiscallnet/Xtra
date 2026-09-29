package com.github.andreyasadchy.xtra.player.lowlatency

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

enum class StreamProxyRoute {
    PROXY,
    DIRECT,
    NOT_TARGETED,
    UNKNOWN,
}

data class StreamRequestObservation(
    val url: String,
    val requestType: String,
    val route: StreamProxyRoute,
    val proxyServer: String? = null,
)

/** Observes the route actually acquired by each HLS OkHttp call, including pooled connections. */
class StreamRequestRouteTracker {
    private val routes = ConcurrentHashMap<Call, Proxy>()

    fun instrument(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .eventListenerFactory { call ->
            object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    routes[call] = connection.route().proxy
                }

                override fun callEnd(call: Call) {
                    routes.remove(call)
                }

                override fun callFailed(call: Call, ioe: java.io.IOException) {
                    routes.remove(call)
                }
            }
        }
        .build()

    fun observation(url: String, requestType: String, call: Call): StreamRequestObservation {
        val proxy = routes[call] ?: return StreamRequestObservation(
            url = url,
            requestType = requestType,
            route = StreamProxyRoute.UNKNOWN,
        )
        val address = proxy.address() as? java.net.InetSocketAddress
        val endpoint = address?.let { "${it.hostString}:${it.port}" }
        return StreamRequestObservation(
            url = url,
            requestType = requestType,
            route = if (proxy.type() == Proxy.Type.DIRECT) StreamProxyRoute.DIRECT else StreamProxyRoute.PROXY,
            proxyServer = endpoint,
        )
    }
}
