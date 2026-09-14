package com.mallrenovation.web

import com.mallrenovation.model.*
import com.mallrenovation.security.Security
import com.mallrenovation.service.ApiException
import com.mallrenovation.service.AppUser
import com.mallrenovation.service.Service
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.Json

private val PipelineContext<Unit, ApplicationCall>.me: AppUser get() = call.principal()!!

fun Application.configurePlugins() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(Authentication) {
        bearer("auth") {
            authenticate { credential ->
                Security.parseToken(credential.token)?.let { (uid, role) ->
                    try {
                        val row = org.jetbrains.exposed.sql.transactions.transaction { Service.userById(uid) }
                        AppUser(
                            id = uid,
                            username = row[com.mallrenovation.model.Users.username],
                            displayName = row[com.mallrenovation.model.Users.displayName],
                            role = role
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, ex ->
            call.respond(HttpStatusCode.fromValue(ex.code), mapOf("error" to (ex.message ?: "请求错误")))
        }
        exception<Throwable> { call, ex ->
            call.application.log.error("未处理异常", ex)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "服务器内部错误: ${ex.message}"))
        }
    }
}

fun Application.configureRouting() {
    routing {
        get("/health") { call.respond(mapOf("status" to "UP")) }

        post("/api/auth/login") {
            val req = call.receive<LoginReq>()
            call.respond(Service.login(req.username, req.password))
        }

        authenticate("auth") {
            get("/api/shops") { call.respond(Service.listShops()) }
            get("/api/dashboard") { call.respond(Service.dashboard(me)) }
            get("/api/notifications") { call.respond(Service.notifications(me)) }
            get("/api/orders") { call.respond(Service.listOrders(me)) }
            get("/api/orders/{id}") {
                call.respond(Service.getOrder(call.parameters["id"]!!.toLong()))
            }

            // 商户：提交装修申请
            post("/api/orders") {
                call.respond(HttpStatusCode.Created, Service.createOrder(call.receive(), me))
            }

            // 各部门：审批任务
            post("/api/tasks/{id}/review") {
                val req = call.receive<ReviewTaskReq>()
                call.respond(Service.reviewTask(call.parameters["id"]!!.toLong(), req.approved, req.comment, me))
            }

            // 押金缴纳
            post("/api/orders/{id}/deposit/pay") {
                call.respond(Service.payDeposit(call.parameters["id"]!!.toLong(), me))
            }

            // 物业确认会签完成 + 押金到账后开工（施工证生效）
            post("/api/orders/{id}/construction/start") {
                call.respond(Service.startConstruction(call.parameters["id"]!!.toLong(), me))
            }

            // 安保：进场核验（身份证/工牌/保险/工具/材料）
            post("/api/workers/verify") {
                call.respond(Service.verifyWorker(call.receive(), me))
            }
            post("/api/workers/{id}/admit") {
                call.respond(Service.admitWorker(call.parameters["id"]!!.toLong(), me))
            }

            // 门岗：材料出入
            post("/api/materials/gate") {
                call.respond(Service.materialGate(call.receive(), me))
            }

            // 专项作业票：动火/切割/喷漆/高空作业
            post("/api/orders/{id}/permits") {
                call.respond(HttpStatusCode.Created,
                    Service.applyPermit(call.parameters["id"]!!.toLong(), call.receive(), me))
            }
            post("/api/permits/{id}/decision") {
                val req = call.receive<PermitDecisionReq>()
                call.respond(Service.decidePermit(call.parameters["id"]!!.toLong(), req.approved, req.comment, me))
            }
            post("/api/permits/{id}/start") {
                call.respond(Service.permitLifecycle(call.parameters["id"]!!.toLong(), false, me))
            }
            post("/api/permits/{id}/finish") {
                call.respond(Service.permitLifecycle(call.parameters["id"]!!.toLong(), true, me))
            }
            // 动火/切割：安保现场复核五项条件
            post("/api/permits/{id}/site-review") {
                call.respond(Service.siteReviewPermit(call.parameters["id"]!!.toLong(), call.receive(), me))
            }
            // 动火期间烟感异常/监护人离岗 → 自动暂停
            post("/api/permits/{id}/abnormal") {
                call.respond(Service.reportPermitAbnormal(call.parameters["id"]!!.toLong(), call.receive(), me))
            }
            // 取消作业票（取消后不参与动火风险评估）
            post("/api/permits/{id}/cancel") {
                call.respond(Service.cancelPermit(call.parameters["id"]!!.toLong(), me))
            }

            // 施工过程事件与多方处置
            post("/api/orders/{id}/incidents") {
                call.respond(HttpStatusCode.Created,
                    Service.reportIncident(call.parameters["id"]!!.toLong(), call.receive(), me))
            }
            post("/api/incidents/{id}/handling") {
                call.respond(Service.incidentStatus(call.parameters["id"]!!.toLong(), false, me))
            }
            post("/api/incidents/{id}/resolve") {
                call.respond(Service.incidentStatus(call.parameters["id"]!!.toLong(), true, me))
            }

            // 完工报验与逐项验收
            post("/api/orders/{id}/complete") {
                call.respond(Service.completeConstruction(call.parameters["id"]!!.toLong(), me))
            }
            post("/api/orders/{id}/checks") {
                call.respond(Service.checkItem(call.parameters["id"]!!.toLong(), call.receive(), me))
            }
            post("/api/rectifications/submit") {
                val req = call.receive<RectifySubmitReq>()
                call.respond(Service.submitRectification(req, me))
            }

            // 财务：押金扣罚
            post("/api/penalties/{id}/deduct") {
                call.respond(Service.deductPenalty(call.parameters["id"]!!.toLong(), me))
            }

            // 物业：开业许可；商户：确认开业
            post("/api/orders/{id}/opening/grant") {
                call.respond(Service.grantOpening(call.parameters["id"]!!.toLong(), me))
            }
            post("/api/orders/{id}/open") {
                call.respond(Service.open(call.parameters["id"]!!.toLong(), me))
            }
        }
    }
}
