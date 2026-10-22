package io.github.lukepopwell.tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Starts a real local HTTP server (`com.sun.net.httpserver`, built into the JDK) rather than a
 * mocking framework, so [Client]/[DeliveryQueue]/[Reporter]/[ForgeOpsTracker] tests exercise
 * actual bytes/headers over an actual socket. Mirrors `sdks/java`'s own `ClientTest.startServer`,
 * factored out here since several Kotlin test files need it, not just one.
 */
fun startTestHttpServer(path: String = "/api/v1/events", handler: (exchange: HttpExchange, body: String) -> Unit): HttpServer {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext(path) { exchange ->
        val body = exchange.requestBody.readAllBytes().decodeToString()
        handler(exchange, body)
        exchange.close()
    }
    server.start()
    return server
}
