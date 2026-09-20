package com.fkurevija.pingring.coordination

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entry point for the self-hostable coordination-service.
 *
 * Reads the listen port from the PORT environment variable (defaults to 8080).
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        coordinationModule()
    }.start(wait = true)
}
