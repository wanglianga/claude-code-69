package com.mallrenovation.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

object Db {
    lateinit var dataSource: HikariDataSource

    fun init() {
        val cfg = HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/renovation"
            username = System.getenv("DB_USER") ?: "renovation"
            password = System.getenv("DB_PASSWORD") ?: "renovation"
            maximumPoolSize = 8
            driverClassName = "org.postgresql.Driver"
            validationTimeout = 3000
        }
        dataSource = HikariDataSource(cfg)
        Database.connect(dataSource)
    }

    // 等待 postgres 就绪并初始化结构（幂等）
    fun migrate(retries: Int = 40) {
        var last: Exception? = null
        repeat(retries) {
            try {
                transaction {
                    val sql = Db::class.java.getResourceAsStream("/db/schema.sql")!!.bufferedReader().readText()
                    sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(3000)
            }
        }
        throw IllegalStateException("数据库初始化失败: ${last?.message}")
    }
}
