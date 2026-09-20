package com.fkurevija.pingring.coordination

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

/**
 * Entry point for the self-hostable coordination-service.
 *
 * Reads PORT and COORDINATION_STATE_FILE environment variables.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val stateFile = System.getenv("COORDINATION_STATE_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        ?: Path.of("coordination-state.json")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        coordinationModule(EventCoordinationService(storageFile = stateFile))
    }.start(wait = true)
}
