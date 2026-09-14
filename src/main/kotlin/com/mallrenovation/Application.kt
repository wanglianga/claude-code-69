package com.mallrenovation

import com.mallrenovation.db.Db
import com.mallrenovation.db.Seed
import com.mallrenovation.web.configurePlugins
import com.mallrenovation.web.configureRouting
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    Db.init()
    Db.migrate()
    Seed.run()

    val port = (System.getenv("HTTP_PORT") ?: "8080").toInt()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configurePlugins()
        configureRouting()
    }.start(wait = true)
}
