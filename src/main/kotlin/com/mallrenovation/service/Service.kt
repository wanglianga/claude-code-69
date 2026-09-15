package com.mallrenovation.service

import com.mallrenovation.engine.RuleEngine
import com.mallrenovation.model.*
import com.mallrenovation.security.Security
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ApiException(val code: Int, message: String) : RuntimeException(message)

data class AppUser(val id: Long, val username: String, val displayName: String, val role: String) : io.ktor.server.auth.Principal

private val MANAGEMENT_ROLES = listOf("PROPERTY", "ENGINEERING", "SECURITY", "FIRE", "FINANCE", "FLOOR_OPS")

object Service {

    private val cnZone = ZoneId.of("Asia/Shanghai")

    private fun ts(s: String): Instant = try {
        Instant.parse(s)
    } catch (e: Exception) {
        throw ApiException(400, "时间格式错误（需 ISO-8601，如 2026-09-20T22:00:00+08:00）: $s")
    }

    private fun money(v: Double) = BigDecimal(v).setScale(2)

    fun login(username: String, password: String): LoginResp = transaction {
        val u = Users.select { Users.username eq username }.firstOrNull()
            ?: throw ApiException(401, "用户名或密码错误")
        if (!Security.verifyPassword(password, u[Users.passwordSalt], u[Users.passwordHash]))
            throw ApiException(401, "用户名或密码错误")
        LoginResp(
            token = Security.issueToken(u[Users.id], u[Users.role]),
            username = u[Users.username],
            displayName = u[Users.displayName],
            role = u[Users.role],
            company = u[Users.company]
        )
    }

    fun userById(id: Long): ResultRow =
        Users.select { Users.id eq id }.firstOrNull() ?: throw ApiException(404, "用户不存在")

    // ---------- 事件与通知 ----------
    private fun event(orderId: Long, type: String, detail: String?, actorId: Long?) {
        OrderEvents.insert {
            it[OrderEvents.orderId] = orderId
            it[eventType] = type
            it[OrderEvents.detail] = detail
            it[OrderEvents.actorId] = actorId
            it[createdAt] = Instant.now()
        }
        RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[updatedAt] = Instant.now() }
    }

    private fun notify(orderId: Long?, roles: List<String>, message: String) {
        roles.distinct().forEach { role ->
            Notifications.insert {
                it[Notifications.orderId] = orderId
                it[targetRole] = role
                it[Notifications.message] = message
                it[readFlag] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    // ---------- 铺位 ----------
    fun listShops(): List<ShopView> = transaction {
        Shops.selectAll().orderBy(Shops.code).map {
            ShopView(
                it[Shops.id], it[Shops.code], it[Shops.name], it[Shops.floor],
                it[Shops.category], it[Shops.adjacentShopCodes],
                it[Shops.operatingStart], it[Shops.operatingEnd]
            )
        }
    }

    // ---------- 装修申请 ----------
    fun createOrder(req: CreateOrderReq, user: AppUser): OrderSummary = transaction {
        if (user.role != "MERCHANT") throw ApiException(403, "仅商户可提交装修申请")
        val shop = Shops.select { Shops.code eq req.shopCode }.firstOrNull()
            ?: throw ApiException(400, "铺位不存在: ${req.shopCode}")
        if (req.scenario !in RuleEngine.scenarioNames.keys) throw ApiException(400, "未知装修场景: ${req.scenario}")
        if (req.workers.isEmpty()) throw ApiException(400, "必须提交施工人员名单")
        if (req.materials.isEmpty()) throw ApiException(400, "必须提交材料清单")
        if (req.enclosurePlan.isBlank()) throw ApiException(400, "必须提交围挡方案")
        if (req.constructionCompany.isBlank()) throw ApiException(400, "必须填写施工单位")

        val start = ts(req.constructionStart)
        val end = ts(req.constructionEnd)
        if (!end.isAfter(start)) throw ApiException(400, "施工结束时间必须晚于开始时间")

        val adjacent = shop[Shops.adjacentShopCodes].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val overlap = overlapsOperatingHours(start, end, shop[Shops.operatingStart], shop[Shops.operatingEnd])

        val rule = RuleEngine.evaluate(
            scenario = req.scenario,
            category = shop[Shops.category],
            floor = shop[Shops.floor],
            adjacent = adjacent,
            overlapsOperatingHours = overlap,
            hotWork = req.hotWorkRequired,
            nightWork = req.nightWorkRequired
        )

        val now = Instant.now()
        val orderNo = "RG${java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(cnZone).format(now)}${(100..999).random()}"
        val orderId = RenovationOrders.insert {
            it[RenovationOrders.orderNo] = orderNo
            it[shopId] = shop[Shops.id]
            it[merchantUserId] = user.id
            it[scenario] = req.scenario
            it[status] = "PENDING_REVIEW"
            it[drawingDoc] = req.drawingDoc
            it[constructionStart] = start
            it[constructionEnd] = end
            it[constructionCompany] = req.constructionCompany
            it[hotWorkRequired] = req.hotWorkRequired
            it[nightWorkRequired] = req.nightWorkRequired
            it[enclosurePlan] = req.enclosurePlan
            it[depositAmount] = rule.deposit
            it[createdAt] = now
            it[updatedAt] = now
        } get RenovationOrders.id

        rule.tasks.forEach { spec ->
            ApprovalTasks.insert {
                it[ApprovalTasks.orderId] = orderId
                it[ApprovalTasks.dept] = spec.dept
                it[ApprovalTasks.title] = spec.title
                it[ApprovalTasks.seq] = spec.seq
                it[ApprovalTasks.status] = "PENDING"
                it[ApprovalTasks.createdAt] = now
            }
        }

        req.workers.forEach { w ->
            Workers.insert {
                it[Workers.orderId] = orderId
                it[Workers.name] = w.name
                it[Workers.idCard] = w.idCard
            }
        }
        req.materials.forEach { m ->
            MaterialItems.insert {
                it[MaterialItems.orderId] = orderId
                it[MaterialItems.name] = m.name
                it[MaterialItems.qty] = m.qty
                it[MaterialItems.declaredFlameRetardant] = m.declaredFlameRetardant
            }
        }

        event(orderId, "ORDER_SUBMITTED", "商户提交${RuleEngine.scenarioNames[req.scenario]}申请；规则引擎生成${rule.tasks.size}项审批任务，押金基准 ¥${rule.deposit}", user.id)
        rule.notes.forEach { event(orderId, "RULE_NOTE", it, null) }
        event(orderId, "RULE_NOISE", "噪声限制：${rule.noiseRule}", null)
        event(orderId, "RULE_MATERIAL", "材料管控：${rule.materialRule}", null)
        event(orderId, "RULE_FIRE", "消防要求：${rule.fireRule}", null)

        val roleMsg = "新装修单 $orderNo（${shop[Shops.name]} ${req.shopCode}）待审批"
        notify(orderId, listOf("PROPERTY", "ENGINEERING", "FIRE", "SECURITY", "FINANCE", "FLOOR_OPS"), roleMsg)
        if (overlap) {
            notify(orderId, listOf("FLOOR_OPS"), "装修单 $orderNo 施工与营业时段重叠，请评估对邻近商户${adjacent.joinToString("、")}收入及顾客动线影响")
        }

        summary(orderId)
    }

    /** 施工周期是否覆盖营业时段：超过 12 小时视为跨营业时段；否则按起止小时判断 */
    private fun overlapsOperatingHours(start: Instant, end: Instant, opStart: Int, opEnd: Int): Boolean {
        val hours = ChronoUnit.HOURS.between(start, end)
        if (hours >= 12) return true
        val s = start.atZone(cnZone).hour
        val e = end.atZone(cnZone).hour.coerceAtMost(23)
        return (s < opEnd && e > opStart)
    }

    // ---------- 查询 ----------
    private fun orderRow(id: Long): ResultRow =
        RenovationOrders.select { RenovationOrders.id eq id }.firstOrNull()
            ?: throw ApiException(404, "装修单不存在")

    private fun shopRow(id: Long): ResultRow = Shops.select { Shops.id eq id }.first()
    private fun userName(id: Long?): String? = id?.let { Users.select { Users.id eq it }.firstOrNull()?.get(Users.displayName) }

    fun listOrders(user: AppUser): List<OrderSummary> = transaction {
        val rows = if (user.role == "MERCHANT")
            RenovationOrders.select { RenovationOrders.merchantUserId eq user.id }.orderBy(RenovationOrders.id, SortOrder.DESC).toList()
        else
            RenovationOrders.selectAll().orderBy(RenovationOrders.id, SortOrder.DESC).toList()
        rows.map { summaryRow(it) }
    }

    private fun summaryRow(o: ResultRow): OrderSummary {
        val shop = shopRow(o[RenovationOrders.shopId])
        return OrderSummary(
            id = o[RenovationOrders.id],
            orderNo = o[RenovationOrders.orderNo],
            shopCode = shop[Shops.code],
            shopName = shop[Shops.name],
            floor = shop[Shops.floor],
            scenario = o[RenovationOrders.scenario],
            status = o[RenovationOrders.status],
            depositAmount = o[RenovationOrders.depositAmount].toDouble(),
            depositPaid = o[RenovationOrders.depositPaid],
            totalPenalty = o[RenovationOrders.totalPenalty].toDouble(),
            fireReinspectionPassed = o[RenovationOrders.fireReinspectionPassed],
            firePermitPassed = o[RenovationOrders.firePermitPassed],
            openingAllowed = o[RenovationOrders.openingAllowed],
            nightWorkBlocked = o[RenovationOrders.nightWorkBlocked],
            nightBlockReason = o[RenovationOrders.nightBlockReason],
            depositRefunded = o[RenovationOrders.depositRefunded],
            depositRefundAmount = o[RenovationOrders.depositRefundAmount].toDouble(),
            createdAt = o[RenovationOrders.createdAt].toString()
        )
    }

    private fun summary(id: Long): OrderSummary = summaryRow(orderRow(id))

    fun getOrder(id: Long): OrderDetail = transaction {
        val o = orderRow(id)
        val tasks = ApprovalTasks.select { ApprovalTasks.orderId eq id }.orderBy(ApprovalTasks.seq).map {
            TaskView(it[ApprovalTasks.id], it[ApprovalTasks.dept], it[ApprovalTasks.title],
                it[ApprovalTasks.status], userName(it[ApprovalTasks.reviewerId]), it[ApprovalTasks.comment], it[ApprovalTasks.seq])
        }
        val workers = Workers.select { Workers.orderId eq id }.orderBy(Workers.id).map {
            val ready = it[Workers.idCardOk] && it[Workers.badgeOk] && it[Workers.insuranceOk] && it[Workers.toolsOk] && it[Workers.materialsOk]
            WorkerView(it[Workers.id], it[Workers.name], it[Workers.idCard],
                it[Workers.idCardOk], it[Workers.badgeOk], it[Workers.insuranceOk],
                it[Workers.toolsOk], it[Workers.materialsOk], ready, it[Workers.admitted],
                it[Workers.admitTime]?.toString())
        }
        val materials = MaterialItems.select { MaterialItems.orderId eq id }.orderBy(MaterialItems.id).map {
            MaterialView(it[MaterialItems.id], it[MaterialItems.name], it[MaterialItems.qty],
                it[MaterialItems.declaredFlameRetardant], it[MaterialItems.flameRetardantVerified],
                it[MaterialItems.entryStatus], it[MaterialItems.gateRemark])
        }
        val permits = SpecialWorkPermits.select { SpecialWorkPermits.orderId eq id }.orderBy(SpecialWorkPermits.id).map { p ->
            val logs = PermitSiteLogs.select { PermitSiteLogs.permitId eq p[SpecialWorkPermits.id] }
                .orderBy(PermitSiteLogs.id).map { l ->
                    SiteLogView(l[PermitSiteLogs.id], l[PermitSiteLogs.reviewRound], l[PermitSiteLogs.action],
                        l[PermitSiteLogs.permitPresent], l[PermitSiteLogs.extinguisherOk],
                        l[PermitSiteLogs.watcherPresent], l[PermitSiteLogs.smokeProtected], l[PermitSiteLogs.hoursOk],
                        l[PermitSiteLogs.detail], userName(l[PermitSiteLogs.recordedBy]), l[PermitSiteLogs.createdAt].toString())
                }
            PermitView(p[SpecialWorkPermits.id], p[SpecialWorkPermits.workType], p[SpecialWorkPermits.reason],
                p[SpecialWorkPermits.plannedStart].toString(), p[SpecialWorkPermits.plannedEnd].toString(),
                p[SpecialWorkPermits.status], p[SpecialWorkPermits.fireWatcher], p[SpecialWorkPermits.extinguisherCount],
                userName(p[SpecialWorkPermits.approverId]), p[SpecialWorkPermits.decidedAt]?.toString(),
                siteReviewStatus = p[SpecialWorkPermits.siteReviewStatus],
                siteReviewRound = p[SpecialWorkPermits.siteReviewRound],
                siteReviewer = userName(p[SpecialWorkPermits.siteReviewerId]),
                siteReviewedAt = p[SpecialWorkPermits.siteReviewedAt]?.toString(),
                pausedReason = p[SpecialWorkPermits.pausedReason],
                siteLogs = logs)
        }
        val incidents = Incidents.select { Incidents.orderId eq id }.orderBy(Incidents.id).map {
            val parties = RuleEngine.incidentParties[it[Incidents.type]] ?: emptyList()
            IncidentView(it[Incidents.id], it[Incidents.type], it[Incidents.level], it[Incidents.description],
                userName(it[Incidents.reportedBy]), it[Incidents.penalty].toDouble(),
                it[Incidents.rectifyDeadline]?.toString(), it[Incidents.status],
                parties.map { p -> RuleEngine.deptNames[p] ?: p }, it[Incidents.createdAt].toString())
        }
        val violationNames = mapOf(
            "SPRINKLER_OCCLUDED" to "喷淋遮挡",
            "EXIT_SIGN_ERROR" to "疏散指示错误",
            "OTHER" to "其他消防问题"
        )
        val penalties = Penalties.select { Penalties.orderId eq id }.orderBy(Penalties.id).map { row ->
            val rid = row[Penalties.rectificationId]
            val rect = rid?.let { Rectifications.select { Rectifications.id eq it }.firstOrNull() }
            PenaltyView(row[Penalties.id], row[Penalties.incidentId], row[Penalties.amount].toDouble(),
                row[Penalties.reason], row[Penalties.deducted],
                rectificationId = rid,
                drawingRef = rect?.get(Rectifications.updatedDrawing)?.ifBlank { rect[Rectifications.drawingRef] },
                responsibleCompany = rect?.get(Rectifications.responsibleCompany))
        }
        val items = AcceptanceItems.select { AcceptanceItems.orderId eq id }.orderBy(AcceptanceItems.id).map {
            CheckItemView(it[AcceptanceItems.id], it[AcceptanceItems.category], it[AcceptanceItems.status],
                userName(it[AcceptanceItems.inspectorId]), it[AcceptanceItems.remark], it[AcceptanceItems.checkedAt]?.toString())
        }
        val rects = Rectifications.select { Rectifications.orderId eq id }.orderBy(Rectifications.id).map { r ->
            val isFire = r[Rectifications.violationType].isNotBlank()
            RectificationView(
                id = r[Rectifications.id], itemId = r[Rectifications.itemId],
                description = r[Rectifications.description],
                deadline = r[Rectifications.deadline].toString(), status = r[Rectifications.status],
                submittedNote = r[Rectifications.submittedNote],
                createdAt = r[Rectifications.createdAt].toString(),
                resolvedAt = r[Rectifications.resolvedAt]?.toString(),
                fireRectification = isFire,
                violationType = r[Rectifications.violationType],
                violationName = violationNames[r[Rectifications.violationType]] ?: "",
                responsibleCompany = r[Rectifications.responsibleCompany],
                drawingRef = r[Rectifications.drawingRef],
                reinspectAt = r[Rectifications.reinspectAt]?.toString(),
                updatedDrawing = r[Rectifications.updatedDrawing],
                fireConfirmed = r[Rectifications.fireConfirmed],
                engConfirmed = r[Rectifications.engConfirmed],
                fireConfirmedBy = userName(r[Rectifications.fireConfirmedBy]),
                engConfirmedBy = userName(r[Rectifications.engConfirmedBy]),
                reinspectRound = r[Rectifications.reinspectRound],
                reinspectResult = r[Rectifications.reinspectResult],
                penaltyId = r[Rectifications.penaltyId]
            )
        }
        val events = OrderEvents.select { OrderEvents.orderId eq id }.orderBy(OrderEvents.id).map {
            EventView(it[OrderEvents.id], it[OrderEvents.eventType], it[OrderEvents.detail],
                userName(it[OrderEvents.actorId]), it[OrderEvents.createdAt].toString())
        }
        OrderDetail(summaryRow(o), tasks, workers, materials, permits, incidents, penalties, items, rects, events)
    }

    // ---------- 审批 ----------
    fun reviewTask(taskId: Long, approved: Boolean, comment: String, user: AppUser): MessageResp = transaction {
        val t = ApprovalTasks.select { ApprovalTasks.id eq taskId }.firstOrNull()
            ?: throw ApiException(404, "审批任务不存在")
        if (t[ApprovalTasks.dept] != user.role && user.role != "ADMIN")
            throw ApiException(403, "该任务由 ${RuleEngine.deptNames[t[ApprovalTasks.dept]]} 审核")
        if (t[ApprovalTasks.status] != "PENDING") throw ApiException(409, "该任务已审核")
        val orderId = t[ApprovalTasks.orderId]
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] in listOf("CANCELLED", "OPENED"))
            throw ApiException(409, "装修单当前状态不可审核")

        ApprovalTasks.update({ ApprovalTasks.id eq taskId }) {
            it[status] = if (approved) "APPROVED" else "REJECTED"
            it[reviewerId] = user.id
            it[ApprovalTasks.comment] = comment
            it[reviewedAt] = Instant.now()
        }
        event(orderId, "TASK_REVIEWED",
            "${RuleEngine.deptNames[t[ApprovalTasks.dept]]}${if (approved) "通过" else "驳回"}：${t[ApprovalTasks.title]}" +
                (if (comment.isNotBlank()) "（$comment）" else ""), user.id)

        if (!approved) {
            RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[status] = "REJECTED" }
            event(orderId, "ORDER_REJECTED", "审批被驳回，商户修改后需重新申报", user.id)
            notify(orderId, listOf("MERCHANT"), "装修单 ${o[RenovationOrders.orderNo]} 被${RuleEngine.deptNames[t[ApprovalTasks.dept]]}驳回：$comment")
            return@transaction MessageResp("已驳回，装修单退回商户")
        }

        val pending = ApprovalTasks.select { (ApprovalTasks.orderId eq orderId) and (ApprovalTasks.status eq "PENDING") }.count()
        if (pending == 0L) {
            notify(orderId, listOf("MERCHANT", "FLOOR_OPS"),
                "装修单 ${o[RenovationOrders.orderNo]} 六方会签全部通过；商户缴纳押金后可办理施工证进场")
            event(orderId, "ALL_TASKS_APPROVED", "物业/工程/消防/安保/财务/楼层运营全部会签通过", user.id)
        } else {
            notify(orderId, listOf("MERCHANT"), "审批节点通过（${RuleEngine.deptNames[t[ApprovalTasks.dept]]}），剩余 $pending 项待审")
        }
        MessageResp("审核通过，剩余待审任务 $pending 项")
    }

    // ---------- 押金 / 施工证 ----------
    fun payDeposit(orderId: Long, user: AppUser): MessageResp = transaction {
        val o = orderRow(orderId)
        if (o[RenovationOrders.merchantUserId] != user.id) throw ApiException(403, "仅本单商户可缴纳押金")
        if (o[RenovationOrders.depositPaid]) throw ApiException(409, "押金已缴纳")
        RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[depositPaid] = true }
        event(orderId, "DEPOSIT_PAID", "商户缴纳装修押金 ¥${o[RenovationOrders.depositAmount]}，待财务确认到账", user.id)
        notify(orderId, listOf("FINANCE"), "装修单 ${o[RenovationOrders.orderNo]} 押金 ¥${o[RenovationOrders.depositAmount]} 待确认")
        MessageResp("押金缴纳凭证已提交，等待财务确认")
    }

    fun startConstruction(orderId: Long, user: AppUser): MessageResp {
        // 归属/角色校验先在独立事务中提交审计，再拒绝；保证越权不改状态且可追溯
        val denial = transaction {
            val o = orderRow(orderId)
            if (user.role != "ADMIN" && !(user.role == "MERCHANT" && o[RenovationOrders.merchantUserId] == user.id)) {
                event(orderId, "ACCESS_DENIED",
                    "用户 ${user.displayName}(${user.role}) 越权请求开工/施工证生效，已拒绝（仅本单授权商户可操作）", user.id)
                "无权开工：仅本装修单的授权商户可申请施工证生效"
            } else null
        }
        if (denial != null) throw ApiException(403, denial)
        return transaction {
            val o = orderRow(orderId)
            if (o[RenovationOrders.status] != "PENDING_REVIEW")
                throw ApiException(409, "当前状态 ${o[RenovationOrders.status]} 不可开工")
            val pending = ApprovalTasks.select { (ApprovalTasks.orderId eq orderId) and (ApprovalTasks.status neq "APPROVED") }.count()
            val reasons = mutableListOf<String>()
            if (pending > 0L) reasons += "尚有 $pending 项审批任务未通过（六方会签未完成）"
            if (!o[RenovationOrders.depositPaid]) reasons += "装修押金未缴纳"
            if (reasons.isNotEmpty()) throw ApiException(409, "不满足开工条件：${reasons.joinToString("；")}")

            RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[status] = "UNDER_CONSTRUCTION" }
            event(orderId, "CONSTRUCTION_STARTED", "施工证生效，施工单位 ${o[RenovationOrders.constructionCompany]} 凭证进场", user.id)
            notify(orderId, listOf("MERCHANT", "SECURITY", "FLOOR_OPS"),
                "装修单 ${o[RenovationOrders.orderNo]} 施工证已生效，安保门岗启动人员/材料核验")
            MessageResp("施工证已生效，可进场施工")
        }
    }

    // ---------- 人员进场核验 ----------
    fun verifyWorker(req: WorkerVerifyReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("SECURITY", "ADMIN")) throw ApiException(403, "仅安保可进行进场核验")
        val w = Workers.select { Workers.id eq req.workerId }.firstOrNull()
            ?: throw ApiException(404, "施工人员不存在")
        Workers.update({ Workers.id eq req.workerId }) {
            req.idCardOk?.let { v -> it[idCardOk] = v }
            req.badgeOk?.let { v -> it[badgeOk] = v }
            req.insuranceOk?.let { v -> it[insuranceOk] = v }
            req.toolsOk?.let { v -> it[toolsOk] = v }
            req.materialsOk?.let { v -> it[materialsOk] = v }
        }
        val fresh = Workers.select { Workers.id eq req.workerId }.first()
        val ready = fresh[Workers.idCardOk] && fresh[Workers.badgeOk] && fresh[Workers.insuranceOk] &&
            fresh[Workers.toolsOk] && fresh[Workers.materialsOk]
        event(fresh[Workers.orderId], "WORKER_VERIFIED",
            "安保核验人员 ${fresh[Workers.name]}：身份证=${yn(fresh[Workers.idCardOk])} 工牌=${yn(fresh[Workers.badgeOk])} " +
                "保险=${yn(fresh[Workers.insuranceOk])} 工具=${yn(fresh[Workers.toolsOk])} 材料=${yn(fresh[Workers.materialsOk])}", user.id)
        MessageResp(if (ready) "五项核验全部通过，可放行进场" else "核验信息已更新，尚有项目未通过，不可进场")
    }

    fun admitWorker(workerId: Long, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("SECURITY", "ADMIN")) throw ApiException(403, "仅安保可放行")
        val w = Workers.select { Workers.id eq workerId }.firstOrNull() ?: throw ApiException(404, "施工人员不存在")
        val orderId = w[Workers.orderId]
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] != "UNDER_CONSTRUCTION")
            throw ApiException(409, "装修单未在施工状态，施工证未生效，禁止进场")
        if (o[RenovationOrders.nightWorkBlocked])
            throw ApiException(409, "当晚施工许可已暂停（${o[RenovationOrders.nightBlockReason]}），动火重新复核合格前禁止人员进场")
        val ready = w[Workers.idCardOk] && w[Workers.badgeOk] && w[Workers.insuranceOk] && w[Workers.toolsOk] && w[Workers.materialsOk]
        if (!ready) throw ApiException(409, "身份证/工牌/保险/工具/材料五项核验未全部通过，禁止进场")
        if (w[Workers.admitted]) throw ApiException(409, "该人员已进场")
        Workers.update({ Workers.id eq workerId }) {
            it[admitted] = true
            it[admitTime] = Instant.now()
        }
        event(orderId, "WORKER_ADMITTED", "施工人员 ${w[Workers.name]} 核验合格放行进场", user.id)
        MessageResp("放行成功：${w[Workers.name]} 已进场")
    }

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    // ---------- 材料出入 ----------
    fun materialGate(req: MaterialGateReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("SECURITY", "FIRE", "ADMIN")) throw ApiException(403, "仅安保/消防可核验材料")
        val m = MaterialItems.select { MaterialItems.id eq req.materialId }.firstOrNull()
            ?: throw ApiException(404, "材料不存在")
        if (req.allow && m[MaterialItems.declaredFlameRetardant] && !req.flameRetardantVerified)
            throw ApiException(409, "申报阻燃材料必须现场抽检合格后方可进场")
        MaterialItems.update({ MaterialItems.id eq req.materialId }) {
            it[entryStatus] = if (req.allow) "ALLOWED" else "REJECTED"
            it[flameRetardantVerified] = req.flameRetardantVerified || m[MaterialItems.flameRetardantVerified]
            it[gateRemark] = req.remark.ifBlank { if (req.allow) "核验放行" else "禁止进场" }
        }
        event(m[MaterialItems.orderId], "MATERIAL_GATE",
            "材料 ${m[MaterialItems.name]}（${m[MaterialItems.qty]}）${if (req.allow) "放行进场" else "被门岗拒绝进场"}" +
                (if (req.remark.isNotBlank()) "：${req.remark}" else ""), user.id)
        if (!req.allow) notify(m[MaterialItems.orderId], listOf("MERCHANT"),
            "材料 ${m[MaterialItems.name]} 被禁止入场：${req.remark}")
        MessageResp(if (req.allow) "材料已放行" else "材料已拦截")
    }

    // ---------- 专项作业票 ----------
    fun applyPermit(orderId: Long, req: PermitApplyReq, user: AppUser): PermitView = transaction {
        if (user.role !in listOf("MERCHANT", "PROPERTY", "ADMIN")) throw ApiException(403, "仅商户/物业可申请专项作业票")
        if (req.workType !in RuleEngine.permitNames.keys) throw ApiException(400, "未知作业类型")
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] != "UNDER_CONSTRUCTION")
            throw ApiException(409, "仅施工中可办理专项作业票（动火/切割/喷漆/高空作业必须单独申请）")
        val start = ts(req.plannedStart); val end = ts(req.plannedEnd)
        if (!end.isAfter(start)) throw ApiException(400, "作业结束时间必须晚于开始时间")
        if (req.workType in listOf("HOT_WORK", "CUTTING") && (req.fireWatcher.isBlank() || req.extinguisherCount < 2))
            throw ApiException(400, "动火/切割作业必须指定看火人且配置不少于 2 具灭火器")

        val id = SpecialWorkPermits.insert {
            it[SpecialWorkPermits.orderId] = orderId
            it[workType] = req.workType
            it[reason] = req.reason
            it[plannedStart] = start
            it[plannedEnd] = end
            it[fireWatcher] = req.fireWatcher.ifBlank { null }
            it[extinguisherCount] = req.extinguisherCount
            it[status] = "APPLIED"
            it[createdAt] = Instant.now()
        } get SpecialWorkPermits.id
        event(orderId, "PERMIT_APPLIED", "${RuleEngine.permitNames[req.workType]}票申请：${req.reason}", user.id)
        notify(orderId, listOf("FIRE", "SECURITY"), "装修单 ${o[RenovationOrders.orderNo]} 申请${RuleEngine.permitNames[req.workType]}票，待审批")
        PermitView(id, req.workType, req.reason, start.toString(), end.toString(), "APPLIED",
            req.fireWatcher.ifBlank { null }, req.extinguisherCount)
    }

    fun decidePermit(permitId: Long, approved: Boolean, comment: String, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("FIRE", "SECURITY", "ADMIN")) throw ApiException(403, "专项作业票由消防维保/安保审批")
        val p = SpecialWorkPermits.select { SpecialWorkPermits.id eq permitId }.firstOrNull()
            ?: throw ApiException(404, "作业票不存在")
        if (p[SpecialWorkPermits.status] != "APPLIED") throw ApiException(409, "作业票已审批")
        SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
            it[status] = if (approved) "APPROVED" else "REJECTED"
            it[approverId] = user.id
            it[decidedAt] = Instant.now()
        }
        event(p[SpecialWorkPermits.orderId], "PERMIT_DECIDED",
            "${RuleEngine.permitNames[p[SpecialWorkPermits.workType]]}票${if (approved) "批准" else "驳回"}" +
                (if (comment.isNotBlank()) "：$comment" else ""), user.id)
        notify(p[SpecialWorkPermits.orderId], listOf("MERCHANT", "SECURITY"),
            "${RuleEngine.permitNames[p[SpecialWorkPermits.workType]]}票${if (approved) "已批准，作业前现场确认消防措施" else "被驳回：$comment"}")
        MessageResp(if (approved) "作业票已批准" else "作业票已驳回")
    }

    // ---------- 动火现场复核 ----------
    private val hotWorkTypes = listOf("HOT_WORK", "CUTTING")

    private fun siteLog(
        permitId: Long, orderId: Long, round: Int, action: String,
        permitPresent: Boolean, extinguisherOk: Boolean, watcherPresent: Boolean,
        smokeProtected: Boolean, hoursOk: Boolean, detail: String, userId: Long?
    ) {
        PermitSiteLogs.insert {
            it[PermitSiteLogs.permitId] = permitId
            it[PermitSiteLogs.orderId] = orderId
            it[PermitSiteLogs.reviewRound] = round
            it[PermitSiteLogs.action] = action
            it[PermitSiteLogs.permitPresent] = permitPresent
            it[PermitSiteLogs.extinguisherOk] = extinguisherOk
            it[PermitSiteLogs.watcherPresent] = watcherPresent
            it[PermitSiteLogs.smokeProtected] = smokeProtected
            it[PermitSiteLogs.hoursOk] = hoursOk
            it[PermitSiteLogs.detail] = detail
            it[PermitSiteLogs.recordedBy] = userId
            it[PermitSiteLogs.createdAt] = Instant.now()
        }
    }

    /** 计划作业窗口是否避开商场营业时段（按铺位营业起止小时逐日判定） */
    private fun windowOutsideOperatingHours(start: Instant, end: Instant, shop: ResultRow): Boolean {
        val opStart = shop[Shops.operatingStart]
        val opEnd = shop[Shops.operatingEnd]
        var day = start.atZone(cnZone).toLocalDate()
        val lastDay = end.atZone(cnZone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val op1 = day.atTime(opStart, 0).atZone(cnZone).toInstant()
            val op2 = day.atTime(opEnd, 0).atZone(cnZone).toInstant()
            if (start.isBefore(op2) && end.isAfter(op1)) return false
            day = day.plusDays(1)
        }
        return true
    }

    private fun setNightBlock(orderId: Long, blocked: Boolean, reason: String) {
        RenovationOrders.update({ RenovationOrders.id eq orderId }) {
            it[nightWorkBlocked] = blocked
            it[nightBlockReason] = reason
        }
    }

    /**
     * 综合判断同一装修单内所有动火/切割票的风险是否仍存在。
     * 仅统计已授权进入现场环节的票（APPLIED/REJECTED/CANCELLED 尚未授权或已作废，不计）：
     *  - PAUSED：暂停之后必须存在新一轮 SITE_CHECK_PASS（有效重新复核）才算解除，不能沿用原审批；
     *  - APPROVED：现场复核未通过（FAILED/NONE）仍阻断；
     *  - IN_PROGRESS：能进入进行中必先有效复核，不阻断；
     *  - FINISHED：安全结束，不阻断。
     * 返回 (是否仍应暂停当晚施工许可, 综合原因)。
     */
    private fun evaluateNightBlock(orderId: Long): Pair<Boolean, String> {
        val permits = SpecialWorkPermits.select {
            (SpecialWorkPermits.orderId eq orderId) and (SpecialWorkPermits.workType inList hotWorkTypes)
        }.toList()
        val blockers = mutableListOf<String>()
        for (p in permits) {
            val pid = p[SpecialWorkPermits.id]
            val typeName = RuleEngine.permitNames[p[SpecialWorkPermits.workType]]
            when (p[SpecialWorkPermits.status]) {
                "PAUSED" -> {
                    val lastPause = PermitSiteLogs.select {
                        (PermitSiteLogs.permitId eq pid) and (PermitSiteLogs.action eq "ABNORMAL_PAUSE")
                    }.maxByOrNull { it[PermitSiteLogs.id] }
                    val rechecked = lastPause == null || PermitSiteLogs.select {
                        (PermitSiteLogs.permitId eq pid) and
                            (PermitSiteLogs.action eq "SITE_CHECK_PASS") and
                            (PermitSiteLogs.id greater lastPause!![PermitSiteLogs.id])
                    }.count() > 0L
                    if (!rechecked) blockers += "${typeName}票 #$pid 暂停后尚未重新现场复核"
                }
                "APPROVED" -> {
                    if (p[SpecialWorkPermits.siteReviewStatus] != "PASSED")
                        blockers += "${typeName}票 #$pid 已批准但现场复核未通过/未完成"
                }
                else -> { /* IN_PROGRESS 持有效复核；FINISHED 安全结束；APPLIED/REJECTED/CANCELLED 未授权 */ }
            }
        }
        return if (blockers.isEmpty()) false to ""
        else true to "动火风险未解除：${blockers.joinToString("、")}"
    }

    /**
     * 重算同单动火/切割风险并联动当晚施工许可；返回是否仍处于暂停。
     * 只有所有风险票均完成有效复核或安全结束时才解除，避免一张票的结束/复核误清另一张暂停票的限制。
     */
    private fun reevaluateNightWork(orderId: Long, actorId: Long?): Boolean {
        val (blocked, reason) = evaluateNightBlock(orderId)
        val currentlyBlocked = orderRow(orderId)[RenovationOrders.nightWorkBlocked]
        if (blocked) {
            if (reason != orderRow(orderId)[RenovationOrders.nightBlockReason] || !currentlyBlocked) {
                setNightBlock(orderId, true, reason)
            }
        } else if (currentlyBlocked) {
            setNightBlock(orderId, false, "")
            event(orderId, "NIGHT_WORK_RESTORED",
                "同单所有动火/切割作业票均已完成有效复核或安全结束，当晚施工许可恢复", actorId)
            notify(orderId, listOf("MERCHANT", "SECURITY"), "同单动火风险全部解除，当晚施工许可恢复")
        }
        return blocked
    }

    /** 安保现场复核动火/切割条件：动火证、灭火器、监护人、烟感保护、营业时段 */
    fun siteReviewPermit(permitId: Long, req: SiteReviewReq, user: AppUser): MessageResp {
        // 复核失败也必须把记录/夜间许可联动落库，因此事务先提交、再在事务外抛出 409
        val outcome: Pair<Boolean, String> = transaction {
            if (user.role !in listOf("SECURITY", "ADMIN")) throw ApiException(403, "动火现场复核由安保执行")
            val p = SpecialWorkPermits.select { SpecialWorkPermits.id eq permitId }.firstOrNull()
                ?: throw ApiException(404, "作业票不存在")
            if (p[SpecialWorkPermits.workType] !in hotWorkTypes)
                throw ApiException(409, "仅动火/切割作业需要现场复核")
            if (p[SpecialWorkPermits.status] !in listOf("APPROVED", "PAUSED"))
                throw ApiException(409, "仅已批准或已暂停的动火作业可进行现场复核（当前 ${p[SpecialWorkPermits.status]}）")
            val orderId = p[SpecialWorkPermits.orderId]
            val o = orderRow(orderId)
            if (o[RenovationOrders.status] != "UNDER_CONSTRUCTION") throw ApiException(409, "装修单不在施工状态")

            // 暂停后恢复：必须存在暂停之后的全新复核，不能沿用原审批
            val resuming = p[SpecialWorkPermits.status] == "PAUSED"
            if (resuming) {
                val lastPause = PermitSiteLogs
                    .select { (PermitSiteLogs.permitId eq permitId) and (PermitSiteLogs.action eq "ABNORMAL_PAUSE") }
                    .maxByOrNull { it[PermitSiteLogs.id] }
                if (lastPause != null) {
                    val recheck = PermitSiteLogs.select {
                        (PermitSiteLogs.permitId eq permitId) and
                            (PermitSiteLogs.action eq "SITE_CHECK_PASS") and
                            (PermitSiteLogs.id greater lastPause[PermitSiteLogs.id])
                    }.count()
                    if (recheck > 0L) throw ApiException(409, "已完成恢复复核，直接申请恢复作业即可")
                }
            }

            val shop = shopRow(o[RenovationOrders.shopId])
            val hoursOk = windowOutsideOperatingHours(p[SpecialWorkPermits.plannedStart], p[SpecialWorkPermits.plannedEnd], shop)
            val round = p[SpecialWorkPermits.siteReviewRound] + 1
            val failedItems = buildList {
                if (!req.permitPresent) add("动火证不在场")
                if (!req.extinguisherOk) add("灭火器未就位")
                if (!req.watcherPresent) add("监护人（看火人）离岗")
                if (!req.smokeProtected) add("烟感保护不到位")
                if (!hoursOk) add("作业窗口处于商场营业时段")
            }
            val passed = failedItems.isEmpty()
            val typeName = RuleEngine.permitNames[p[SpecialWorkPermits.workType]]

            siteLog(
                permitId, orderId, round, if (passed) "SITE_CHECK_PASS" else "SITE_CHECK_FAIL",
                req.permitPresent, req.extinguisherOk, req.watcherPresent, req.smokeProtected, hoursOk,
                (if (passed) "现场复核通过（第 $round 轮）" else "现场复核不合格：${failedItems.joinToString("、")}") +
                    if (req.note.isNotBlank()) "；${req.note}" else "",
                user.id
            )

            if (!passed) {
                SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
                    it[siteReviewStatus] = "FAILED"
                    it[siteReviewRound] = round
                    it[siteReviewerId] = user.id
                    it[siteReviewedAt] = Instant.now()
                }
                event(orderId, "HOTWORK_SITE_REVIEW_FAIL",
                    "${typeName}票 #$permitId 第 $round 轮现场复核未通过：${failedItems.joinToString("、")}", user.id)
                notify(orderId, listOf("MERCHANT", "SECURITY", "FIRE", "FLOOR_OPS"),
                    "${typeName}现场复核未通过，已暂停当晚施工许可，整改后须重新复核")
                // 与同单其他动火/切割票综合重算（保留其他暂停票的门岗限制）
                reevaluateNightWork(orderId, user.id)
                false to "现场复核未通过：${failedItems.joinToString("、")}；当晚施工许可已暂停"
            } else {
                SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
                    it[siteReviewStatus] = "PASSED"
                    it[siteReviewRound] = round
                    it[siteReviewerId] = user.id
                    it[siteReviewedAt] = Instant.now()
                    it[pausedReason] = null
                }
                event(orderId, "HOTWORK_SITE_REVIEW_PASS",
                    "${typeName}票 #$permitId 第 $round 轮现场复核通过：动火证/灭火器/监护人/烟感保护齐备且避开营业时段", user.id)
                // 必须同单所有动火/切割票风险解除才恢复，不能因本票复核通过而误清其他暂停票
                reevaluateNightWork(orderId, user.id)
                true to (
                    if (resuming) "恢复复核通过（第 $round 轮，重新复核而非沿用原审批），可恢复动火作业"
                    else "现场复核通过（第 $round 轮），可开始动火作业"
                )
            }
        }
        if (!outcome.first) throw ApiException(409, outcome.second)
        return MessageResp(outcome.second)
    }

    /** 动火期间异常（烟感异常/监护人离岗）：自动暂停并通知安保复核 */
    fun reportPermitAbnormal(permitId: Long, req: AbnormalReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("SECURITY", "FIRE", "PROPERTY", "ADMIN"))
            throw ApiException(403, "仅安保/消防/物业可上报动火异常")
        if (req.type !in listOf("SMOKE_ALARM", "WATCHER_LEAVE"))
            throw ApiException(400, "异常类型必须为 SMOKE_ALARM（烟感异常）或 WATCHER_LEAVE（监护人离岗）")
        val p = SpecialWorkPermits.select { SpecialWorkPermits.id eq permitId }.firstOrNull()
            ?: throw ApiException(404, "作业票不存在")
        if (p[SpecialWorkPermits.status] != "IN_PROGRESS")
            throw ApiException(409, "仅进行中的动火作业可登记异常")
        val orderId = p[SpecialWorkPermits.orderId]
        val typeName = RuleEngine.permitNames[p[SpecialWorkPermits.workType]]
        val reasonCn = if (req.type == "SMOKE_ALARM") "烟感异常报警" else "监护人（看火人）离岗"

        SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
            it[status] = "PAUSED"
            it[pausedReason] = req.type
            it[siteReviewStatus] = "NONE"
        }
        siteLog(permitId, orderId, p[SpecialWorkPermits.siteReviewRound], "ABNORMAL_PAUSE",
            false, false, req.type != "WATCHER_LEAVE", req.type != "SMOKE_ALARM", true,
            "${reasonCn}，系统自动暂停作业${if (req.detail.isNotBlank()) "：${req.detail}" else ""}", user.id)
        // 暂停本票并与同单其他动火/切割票综合重算
        reevaluateNightWork(orderId, user.id)
        event(orderId, "HOTWORK_AUTO_PAUSED",
            "${typeName}票 #$permitId 作业中${reasonCn}，系统自动暂停；恢复动火须安保重新现场复核，不得沿用原审批", user.id)
        notify(orderId, listOf("SECURITY", "FIRE", "MERCHANT"),
            "${typeName}作业中${reasonCn}已自动暂停，请安保立即到场复核；当晚施工许可同步暂停")
        MessageResp("已自动暂停动火作业并通知安保现场复核，当晚施工许可暂停")
    }

    fun permitLifecycle(permitId: Long, finish: Boolean, user: AppUser): MessageResp = transaction {
        val p = SpecialWorkPermits.select { SpecialWorkPermits.id eq permitId }.firstOrNull()
            ?: throw ApiException(404, "作业票不存在")
        val orderId = p[SpecialWorkPermits.orderId]
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] != "UNDER_CONSTRUCTION") throw ApiException(409, "装修单不在施工状态")
        val typeName = RuleEngine.permitNames[p[SpecialWorkPermits.workType]]
        if (!finish) {
            // 动火/切割：必须有与当前状态匹配的现场复核通过记录
            if (p[SpecialWorkPermits.workType] in hotWorkTypes) {
                when (p[SpecialWorkPermits.status]) {
                    "APPROVED" -> if (p[SpecialWorkPermits.siteReviewStatus] != "PASSED")
                        throw ApiException(409, "动火/切割开工前必须由安保完成现场复核（动火证/灭火器/监护人/烟感保护/营业时段）")
                    "PAUSED" -> {
                        val lastPause = PermitSiteLogs
                            .select { (PermitSiteLogs.permitId eq permitId) and (PermitSiteLogs.action eq "ABNORMAL_PAUSE") }
                            .maxByOrNull { it[PermitSiteLogs.id] }
                        val recheck = if (lastPause == null) 0L else PermitSiteLogs.select {
                            (PermitSiteLogs.permitId eq permitId) and
                                (PermitSiteLogs.action eq "SITE_CHECK_PASS") and
                                (PermitSiteLogs.id greater lastPause[PermitSiteLogs.id])
                        }.count()
                        if (recheck == 0L)
                            throw ApiException(409, "动火被暂停后必须重新现场复核合格方可恢复，不得沿用原审批")
                    }
                    else -> throw ApiException(409, "当前状态 ${p[SpecialWorkPermits.status]} 不可开始/恢复动火作业")
                }
            } else {
                if (p[SpecialWorkPermits.status] != "APPROVED") throw ApiException(409, "仅已批准作业票可开工")
            }
            val resuming = p[SpecialWorkPermits.status] == "PAUSED"
            SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
                it[status] = "IN_PROGRESS"
                it[pausedReason] = null
            }
            siteLog(permitId, orderId, p[SpecialWorkPermits.siteReviewRound],
                if (resuming) "RESUME_RECHECK_PASS" else "START",
                true, true, true, true, true,
                if (resuming) "重新复核合格后恢复动火作业" else "动火作业开始，消防措施就位", user.id)
            event(orderId, if (resuming) "PERMIT_RESUMED" else "PERMIT_STARTED",
                "${typeName}${if (resuming) "经重新现场复核后恢复作业" else "开始作业，监护人与灭火器材就位"}", user.id)
            notify(orderId, listOf("FIRE", "SECURITY"), "${typeName}正在进行，请加强巡查")
            MessageResp(if (resuming) "动火作业已恢复" else "作业已开始")
        } else {
            if (p[SpecialWorkPermits.status] != "IN_PROGRESS") throw ApiException(409, "仅进行中的作业票可完工")
            SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) { it[status] = "FINISHED" }
            siteLog(permitId, orderId, p[SpecialWorkPermits.siteReviewRound], "FINISH",
                true, true, true, true, true, "作业结束，现场清理并确认无火种", user.id)
            event(orderId, "PERMIT_FINISHED", "${typeName}结束，现场清理并确认无火种", user.id)
            // 仅动火/切割票结束才参与当晚施工许可重算；喷漆/高空等非动火票结束不得清除动火风险限制
            if (p[SpecialWorkPermits.workType] in hotWorkTypes) {
                reevaluateNightWork(orderId, user.id)
            }
            MessageResp("作业已完工，现场已确认安全")
        }
    }

    /** 取消作业票（已批准未安全结束前，商户/物业可取消；取消后不参与动火风险评估，并重算当晚许可） */
    fun cancelPermit(permitId: Long, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("MERCHANT", "PROPERTY", "ADMIN"))
            throw ApiException(403, "仅商户/物业可取消作业票")
        val p = SpecialWorkPermits.select { SpecialWorkPermits.id eq permitId }.firstOrNull()
            ?: throw ApiException(404, "作业票不存在")
        if (p[SpecialWorkPermits.status] == "FINISHED") throw ApiException(409, "作业已安全结束，无需取消")
        if (p[SpecialWorkPermits.status] == "CANCELLED") throw ApiException(409, "作业票已取消")
        if (p[SpecialWorkPermits.status] == "IN_PROGRESS")
            throw ApiException(409, "作业进行中不能直接取消，须先安全结束")
        val orderId = p[SpecialWorkPermits.orderId]
        if (user.role == "MERCHANT" && orderRow(orderId)[RenovationOrders.merchantUserId] != user.id)
            throw ApiException(403, "仅本装修单商户可取消其作业票")
        SpecialWorkPermits.update({ SpecialWorkPermits.id eq permitId }) {
            it[status] = "CANCELLED"
            it[pausedReason] = null
        }
        siteLog(permitId, orderId, p[SpecialWorkPermits.siteReviewRound], "CANCEL",
            false, false, false, false, true, "作业票取消，不再纳入动火风险", user.id)
        event(orderId, "PERMIT_CANCELLED",
            "${RuleEngine.permitNames[p[SpecialWorkPermits.workType]]}票 #$permitId 已取消", user.id)
        if (p[SpecialWorkPermits.workType] in hotWorkTypes) reevaluateNightWork(orderId, user.id)
        MessageResp("作业票已取消")
    }

    // ---------- 施工事件（多方协同） ----------
    fun reportIncident(orderId: Long, req: IncidentReq, user: AppUser): IncidentView = transaction {
        if (user.role !in listOf("PROPERTY", "ENGINEERING", "SECURITY", "FIRE", "FLOOR_OPS", "ADMIN"))
            throw ApiException(403, "仅物业/工程/安保/消防/楼层运营可上报施工事件")
        if (req.type !in RuleEngine.incidentLevel.keys) throw ApiException(400, "未知事件类型")
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] !in listOf("UNDER_CONSTRUCTION", "COMPLETED_PENDING_ACCEPTANCE", "RECTIFICATION"))
            throw ApiException(409, "仅施工/验收阶段可登记事件")
        val level = RuleEngine.incidentLevel[req.type]!!
        val deadline = req.rectifyDeadline?.let { ts(it) }
            ?: if (level == "BREACH") Instant.now().plus(3, ChronoUnit.DAYS) else null
        val amount = money(req.penalty)

        val id = Incidents.insert {
            it[Incidents.orderId] = orderId
            it[type] = req.type
            it[Incidents.level] = level
            it[description] = req.description
            it[reportedBy] = user.id
            it[penalty] = amount
            it[rectifyDeadline] = deadline
            it[status] = "OPEN"
            it[createdAt] = Instant.now()
        } get Incidents.id

        if (amount > BigDecimal.ZERO) {
            Penalties.insert {
                it[Penalties.orderId] = orderId
                it[incidentId] = id
                it[Penalties.amount] = amount
                it[reason] = "${RuleEngine.incidentNames[req.type]}扣罚：${req.description}"
                it[deducted] = false
                it[createdBy] = user.id
                it[createdAt] = Instant.now()
            }
            RenovationOrders.update({ RenovationOrders.id eq orderId }) {
                it[updatedAt] = Instant.now()
            }
        }

        event(orderId, "INCIDENT_REPORTED",
            "【${RuleEngine.incidentNames[req.type]}】${if (level == "BREACH") "（违约）" else ""}${req.description}" +
                (if (amount > BigDecimal.ZERO) "；拟扣罚 ¥$amount（待财务执行）" else "") +
                (deadline?.let { d -> "；整改期限 $d" } ?: ""), user.id)

        val parties = RuleEngine.incidentParties[req.type] ?: emptyList()
        notify(orderId, parties + "MERCHANT",
            "装修单 ${o[RenovationOrders.orderNo]} 发生【${RuleEngine.incidentNames[req.type]}】，多部门联合处置：${req.description}")

        // 临时改图 → 生成改图复核任务，未复核通过不得完工
        if (req.type == "TEMP_DRAWING_CHANGE") {
            listOf(Triple("PROPERTY", "临时改图复核：物业确认围挡与公共区域方案", 1),
                Triple("ENGINEERING", "临时改图复核：工程部确认强弱电/管线影响", 2),
                Triple("FIRE", "临时改图复核：消防确认喷淋烟感与疏散影响", 3),
                Triple("FLOOR_OPS", "临时改图复核：楼层运营确认动线与邻里影响", 5)).forEach { (dept, title, seq) ->
                ApprovalTasks.insert {
                    it[ApprovalTasks.orderId] = orderId
                    it[ApprovalTasks.dept] = dept
                    it[ApprovalTasks.title] = title
                    it[ApprovalTasks.seq] = seq
                    it[status] = "PENDING"
                    it[createdAt] = Instant.now()
                }
            }
            event(orderId, "CHANGE_CONTROL", "临时改图触发变更管控：物业/工程/消防/楼层运营须重新复核，未通过不得完工", user.id)
        }

        // 烟感遮挡/喷淋改动 → 消防开业许可作废，须修复复验
        if (req.type in listOf("SMOKE_COVERED", "SPRINKLER_MODIFICATION")) {
            RenovationOrders.update({ RenovationOrders.id eq orderId }) {
                it[firePermitPassed] = false
                it[openingAllowed] = false
                it[fireReinspectionPassed] = false
            }
            event(orderId, "FIRE_PERMIT_SUSPENDED", "消防设施受影响，消防许可冻结，修复并经消防维保复验前禁止开业", user.id)
            notify(orderId, listOf("MERCHANT", "PROPERTY"), "消防许可已冻结：${RuleEngine.incidentNames[req.type]}必须先整改复验")
        }

        IncidentView(id, req.type, level, req.description, user.displayName, amount.toDouble(),
            deadline?.toString(), "OPEN",
            parties.map { RuleEngine.deptNames[it] ?: it }, Instant.now().toString())
    }

    fun incidentStatus(id: Long, resolve: Boolean, user: AppUser): MessageResp {
        // 归属/角色校验：事件只能由责任部门（或管理员）推进与闭环；独立事务先落审计再拒绝
        val denial = transaction {
            val inc = Incidents.select { Incidents.id eq id }.firstOrNull() ?: throw ApiException(404, "事件不存在")
            val orderId = inc[Incidents.orderId]
            if (user.role == "ADMIN") {
                null
            } else if (user.role !in MANAGEMENT_ROLES) {
                event(orderId, "ACCESS_DENIED",
                    "用户 ${user.displayName}(${user.role}) 非管理角色，请求事件【${RuleEngine.incidentNames[inc[Incidents.type]]}】${if (resolve) "闭环" else "处置"}被拒绝", user.id)
                "事件仅可由其责任部门处置/闭环，商户等非管理角色无权操作"
            } else if (resolve) {
                val owners = RuleEngine.incidentParties[inc[Incidents.type]] ?: emptyList()
                // 消防设施事件必须由消防维保闭环；其他事件由其涉及的责任部门闭环
                val strictFire = inc[Incidents.type] in listOf("SMOKE_COVERED", "SPRINKLER_MODIFICATION")
                val allowed = if (strictFire) user.role == "FIRE" else user.role in owners
                if (allowed) null else {
                    val reason = if (strictFire)
                        "涉及消防设施（烟感/喷淋）的事件必须由消防维保闭环"
                    else "非该事件责任部门（${owners.joinToString("、") { RuleEngine.deptNames[it] ?: it }}）"
                    event(orderId, "ACCESS_DENIED",
                        "用户 ${user.displayName}(${user.role}) 请求闭环事件【${RuleEngine.incidentNames[inc[Incidents.type]]}】被拒绝：$reason", user.id)
                    "该事件只能由其责任部门${if (strictFire) "（涉及消防设施须消防维保）" else ""}闭环"
                }
            } else null
        }
        if (denial != null) throw ApiException(403, denial)

        return transaction {
            val inc = Incidents.select { Incidents.id eq id }.first()
            val orderId = inc[Incidents.orderId]
            if (!resolve) {
                if (inc[Incidents.status] != "OPEN") throw ApiException(409, "事件已在处置中")
                Incidents.update({ Incidents.id eq id }) { it[status] = "HANDLING" }
                event(orderId, "INCIDENT_HANDLING", "【${RuleEngine.incidentNames[inc[Incidents.type]]}】进入多部门联合处置", user.id)
                MessageResp("事件已进入处置流程")
            } else {
                Incidents.update({ Incidents.id eq id }) { it[status] = "RESOLVED" }
                event(orderId, "INCIDENT_RESOLVED",
                    "【${RuleEngine.incidentNames[inc[Incidents.type]]}】整改完成并关闭" +
                        (if (user.role == "FIRE") "（消防维保确认）" else "（${RuleEngine.deptNames[user.role] ?: user.role}确认）"), user.id)
                notify(orderId, listOf("MERCHANT", "PROPERTY"),
                    "事件【${RuleEngine.incidentNames[inc[Incidents.type]]}】已整改关闭")
                MessageResp("事件已闭环")
            }
        }
    }

    // ---------- 完工与逐项验收 ----------
    fun completeConstruction(orderId: Long, user: AppUser): MessageResp {
        // 归属校验：仅本单授权商户（或管理员）可报验完工；独立事务先落审计再拒绝
        val denial = transaction {
            val o = orderRow(orderId)
            if (user.role != "ADMIN" && !(user.role == "MERCHANT" && o[RenovationOrders.merchantUserId] == user.id)) {
                event(orderId, "ACCESS_DENIED",
                    "用户 ${user.displayName}(${user.role}) 越权请求完工报验，已拒绝（仅本单授权商户可操作）", user.id)
                "无权报验完工：仅本装修单的授权商户可提交完工报验"
            } else null
        }
        if (denial != null) throw ApiException(403, denial)

        return transaction {
            val o = orderRow(orderId)
            if (o[RenovationOrders.status] != "UNDER_CONSTRUCTION")
                throw ApiException(409, "仅施工中装修单可报验完工")
        val reasons = mutableListOf<String>()
        ApprovalTasks.select { (ApprovalTasks.orderId eq orderId) and (ApprovalTasks.status neq "APPROVED") }.count()
            .let { if (it > 0L) reasons += "存在 $it 项未通过的审批/改图复核任务" }
        val openIncidents = Incidents.select { (Incidents.orderId eq orderId) and (Incidents.status neq "RESOLVED") }.count()
        if (openIncidents > 0L) reasons += "存在 $openIncidents 起未闭环的施工事件"
        val activePermits = SpecialWorkPermits.select {
            (SpecialWorkPermits.orderId eq orderId) and
                (SpecialWorkPermits.status inList listOf("APPLIED", "APPROVED", "IN_PROGRESS", "PAUSED"))
        }.count()
        if (activePermits > 0L) reasons += "存在 $activePermits 张未完工/暂停中/未撤回的专项作业票"
        if (reasons.isNotEmpty()) throw ApiException(409, "不具备完工报验条件：${reasons.joinToString("；")}")

        val categories = listOf("FIRE", "STRONG_ELECTRIC", "WEAK_ELECTRIC", "SMOKE_EXHAUST", "DRAINAGE", "STOREFRONT", "PUBLIC_RESTORE")
        categories.forEach { cat ->
            AcceptanceItems.insert {
                it[AcceptanceItems.orderId] = orderId
                it[category] = cat
                it[status] = "PENDING"
            }
        }
        RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[status] = "COMPLETED_PENDING_ACCEPTANCE" }
        event(orderId, "CONSTRUCTION_COMPLETED", "施工完成报验，生成 7 项逐项验收（消防/强电/弱电/排烟/排水/门头/公共区域恢复）", user.id)
        notify(orderId, listOf("FIRE", "ENGINEERING", "PROPERTY", "MERCHANT"),
            "装修单 ${o[RenovationOrders.orderNo]} 进入逐项验收")
        MessageResp("已进入完工逐项验收（7 项）")
        }
    }

    fun checkItem(orderId: Long, req: CheckItemReq, user: AppUser): MessageResp = transaction {
        val o = orderRow(orderId)
        if (o[RenovationOrders.status] !in listOf("COMPLETED_PENDING_ACCEPTANCE", "RECTIFICATION"))
            throw ApiException(409, "当前状态不可验收")
        val item = AcceptanceItems.select { (AcceptanceItems.id eq req.itemId) and (AcceptanceItems.orderId eq orderId) }.firstOrNull()
            ?: throw ApiException(404, "验收项不存在")
        val requiredRole = RuleEngine.itemInspectorRole[item[AcceptanceItems.category]]
        if (user.role != requiredRole && user.role != "ADMIN")
            throw ApiException(403, "该验收项主责部门为 ${RuleEngine.deptNames[requiredRole]}")
        if (item[AcceptanceItems.status] == "PASSED") throw ApiException(409, "该验收项已通过")

        AcceptanceItems.update({ AcceptanceItems.id eq req.itemId }) {
            it[status] = if (req.passed) "PASSED" else "FAILED"
            it[inspectorId] = user.id
            it[remark] = req.remark
            it[checkedAt] = Instant.now()
        }
        event(orderId, "ITEM_CHECKED",
            "${RuleEngine.itemNames[item[AcceptanceItems.category]]}验收${if (req.passed) "通过" else "不合格"}" +
                (if (req.remark.isNotBlank()) "：${req.remark}" else ""), user.id)

        if (!req.passed) {
            val deadline = Instant.now().plus(3, ChronoUnit.DAYS)
            val isFireItem = item[AcceptanceItems.category] == "FIRE"
            val violation = req.violationType?.takeIf { it.isNotBlank() } ?: ""
            val reinspectAt = req.reinspectAt?.let { ts(it) } ?: deadline
            if (violation.isNotBlank()) {
                if (!isFireItem) throw ApiException(400, "违规类型（喷淋遮挡/疏散指示错误）仅适用于消防验收项")
                if (violation !in listOf("SPRINKLER_OCCLUDED", "EXIT_SIGN_ERROR", "OTHER"))
                    throw ApiException(400, "消防违规类型必须为 SPRINKLER_OCCLUDED/EXIT_SIGN_ERROR/OTHER")
            }
            val isFireRect = isFireItem && violation.isNotBlank()
            val responsible = req.responsibleCompany?.takeIf { it.isNotBlank() }
                ?: o[RenovationOrders.constructionCompany]
            val drawing = req.drawingRef?.takeIf { it.isNotBlank() } ?: o[RenovationOrders.drawingDoc]

            val rid = Rectifications.insert {
                it[Rectifications.orderId] = orderId
                it[Rectifications.itemId] = req.itemId
                it[Rectifications.description] = "${RuleEngine.itemNames[item[AcceptanceItems.category]]}整改：${req.remark}"
                it[Rectifications.deadline] = deadline
                it[Rectifications.status] = "OPEN"
                it[Rectifications.createdAt] = Instant.now()
                it[Rectifications.violationType] = violation
                it[Rectifications.responsibleCompany] = if (isFireRect) responsible else ""
                it[Rectifications.drawingRef] = if (isFireRect) drawing else ""
                it[Rectifications.reinspectAt] = if (isFireRect) reinspectAt else null
            } get Rectifications.id
            RenovationOrders.update({ RenovationOrders.id eq orderId }) {
                it[status] = "RECTIFICATION"
                it[firePermitPassed] = false
                it[openingAllowed] = false
                it[fireReinspectionPassed] = false
            }
            if (isFireRect) {
                val vname = when (violation) {
                    "SPRINKLER_OCCLUDED" -> "喷淋遮挡"
                    "EXIT_SIGN_ERROR" -> "疏散指示错误"
                    else -> "消防问题"
                }
                event(orderId, "FIRE_RECTIFICATION_ISSUED",
                    "消防验收不合格（$vname）：${req.remark}；整改清单 #$rid，责任施工方：$responsible，" +
                        "关联图纸：$drawing，计划复验 $reinspectAt；复验通过前开业许可冻结、押金冻结", user.id)
                notify(orderId, listOf("MERCHANT", "PROPERTY", "FINANCE", "ENGINEERING"),
                    "消防整改清单 #$rid（$vname，责任方 $responsible）已开具，复验前开业许可与押金退还冻结")
                return@transaction MessageResp("消防验收不合格，已生成整改清单（责任方/图纸/复验时间），复验通过前开业许可与押金退还冻结")
            }
            event(orderId, "RECTIFICATION_ISSUED", "开具整改单 #$rid，期限 $deadline，逾期影响押金退还与开业许可", user.id)
            notify(orderId, listOf("MERCHANT", "FLOOR_OPS"),
                "${RuleEngine.itemNames[item[AcceptanceItems.category]]}验收不合格，整改期限 $deadline")
            return@transaction MessageResp("验收不合格，已开具整改期限至 $deadline")
        }

        // 消防专项整改必须走「图纸更新→双确认→消防复验」接口，不允许在此直接关闭
        val unclosedFireRect = Rectifications.select {
            (Rectifications.orderId eq orderId) and (Rectifications.itemId eq req.itemId) and
                (Rectifications.violationType.neq("")) and (Rectifications.status neq "PASSED")
        }.count()
        if (item[AcceptanceItems.category] == "FIRE" && unclosedFireRect > 0L)
            throw ApiException(409, "存在未复验通过的消防专项整改，须更新图纸并经消防维保/工程确认后由消防复验")

        // 通过时关闭对应整改单（复验通过，仅限非消防专项）
        Rectifications.update({
            (Rectifications.orderId eq orderId) and (Rectifications.itemId eq req.itemId) and
                (Rectifications.violationType eq "") and
                (Rectifications.status inList listOf("OPEN", "RESUBMITTED"))
        }) {
            it[status] = "PASSED"
            it[resolvedAt] = Instant.now()
        }

        finalizeAcceptanceIfDone(orderId, user.id)
        MessageResp("验收通过")
    }

    private fun finalizeAcceptanceIfDone(orderId: Long, actorId: Long?) {
        val total = AcceptanceItems.select { AcceptanceItems.orderId eq orderId }.count()
        val passed = AcceptanceItems.select { (AcceptanceItems.orderId eq orderId) and (AcceptanceItems.status eq "PASSED") }.count()
        val openRects = Rectifications.select {
            (Rectifications.orderId eq orderId) and (Rectifications.status inList listOf("OPEN", "RESUBMITTED", "OVERDUE", "REINSPECT_READY"))
        }.count()
        if (total == 7L && passed == 7L && openRects == 0L) {
            val fireIncidentsOpen = Incidents.select {
                (Incidents.orderId eq orderId) and
                    (Incidents.type inList listOf("SMOKE_COVERED", "SPRINKLER_MODIFICATION")) and
                    (Incidents.status neq "RESOLVED")
            }.count()
            val fireOk = fireIncidentsOpen == 0L
            RenovationOrders.update({ RenovationOrders.id eq orderId }) {
                it[status] = "ACCEPTED"
                it[fireReinspectionPassed] = true
                it[firePermitPassed] = fireOk
            }
            event(orderId, "ALL_ITEMS_ACCEPTED",
                "七项逐项验收全部通过，消防复验${if (fireOk) "通过" else "未通过（消防设施事件未闭环）"}，进入开业许可环节", actorId)
            notify(orderId, listOf("MERCHANT", "PROPERTY", "FINANCE", "FLOOR_OPS"),
                "装修单完工验收全部通过；物业凭消防复验结论与押金/扣罚结清情况核发开业许可")
        }
    }

    fun submitRectification(req: RectifySubmitReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("MERCHANT", "ADMIN")) throw ApiException(403, "仅商户可提交整改复验")
        val r = Rectifications.select { Rectifications.id eq req.rectificationId }.firstOrNull()
            ?: throw ApiException(404, "整改单不存在")
        if (r[Rectifications.violationType].isNotBlank())
            throw ApiException(409, "消防专项整改须先更新整改图纸，再经消防维保与工程部分别确认并复验")
        if (r[Rectifications.status] !in listOf("OPEN", "OVERDUE")) throw ApiException(409, "该整改单当前状态不可提交")
        val orderId = r[Rectifications.orderId]
        Rectifications.update({ Rectifications.id eq req.rectificationId }) {
            it[status] = "RESUBMITTED"
            it[submittedNote] = req.note
        }
        r[Rectifications.itemId]?.let { itemId ->
            AcceptanceItems.update({ AcceptanceItems.id eq itemId }) { it[status] = "PENDING" }
        }
        event(orderId, "RECTIFICATION_RESUBMITTED", "商户提交整改复验：${req.note}", user.id)
        val cat = r[Rectifications.itemId]?.let { itemId ->
            AcceptanceItems.select { AcceptanceItems.id eq itemId }.firstOrNull()?.get(AcceptanceItems.category)
        }
        val role = cat?.let { RuleEngine.itemInspectorRole[it] } ?: "PROPERTY"
        notify(orderId, listOf(role), "整改单 #${req.rectificationId} 已提交，请安排复验")
        MessageResp("整改已提交，等待复验")
    }

    // ---------- 消防专项整改：图纸更新 → 消防/工程双确认 → 复验 ----------
    private fun fireRectRow(id: Long): ResultRow =
        Rectifications.select { Rectifications.id eq id }.firstOrNull()
            ?: throw ApiException(404, "整改清单不存在")

    /** 商户更新整改图纸（关联问题图纸与责任施工方，更新图纸是双确认与复验的前提） */
    fun updateRectifyDrawing(req: RectifyDrawingUpdateReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("MERCHANT", "ADMIN")) throw ApiException(403, "仅商户可更新整改图纸")
        val r = fireRectRow(req.rectificationId)
        if (r[Rectifications.violationType].isBlank()) throw ApiException(409, "该整改单不是消防专项整改")
        val orderId = r[Rectifications.orderId]
        if (orderRow(orderId)[RenovationOrders.merchantUserId] != user.id)
            throw ApiException(403, "仅本装修单商户可更新整改图纸")
        if (r[Rectifications.status] == "PASSED") throw ApiException(409, "整改已复验通过")
        if (req.updatedDrawing.isBlank()) throw ApiException(400, "必须提供更新后的整改图纸")
        // 每次重新提交都重置双确认，必须重新分别确认
        Rectifications.update({ Rectifications.id eq req.rectificationId }) {
            it[updatedDrawing] = req.updatedDrawing
            it[submittedNote] = req.note
            it[status] = "RESUBMITTED"
            it[fireConfirmed] = false
            it[engConfirmed] = false
            it[fireConfirmedBy] = null
            it[engConfirmedBy] = null
            it[fireConfirmedAt] = null
            it[engConfirmedAt] = null
        }
        event(orderId, "FIRE_RECTIFY_DRAWING_UPDATED",
            "消防整改 #${req.rectificationId} 更新图纸：${req.updatedDrawing}（问题图纸 ${r[Rectifications.drawingRef]}，" +
                "责任施工方 ${r[Rectifications.responsibleCompany]}），待消防维保与工程部分别确认", user.id)
        notify(orderId, listOf("FIRE", "ENGINEERING"),
            "消防整改 #${req.rectificationId} 已更新图纸（责任方 ${r[Rectifications.responsibleCompany]}），请分别确认")
        MessageResp("整改图纸已更新，等待消防维保与工程部分别确认")
    }

    /** 消防维保 / 工程部分别确认更新图纸，两方都确认后进入消防复验 */
    fun confirmRectifyDrawing(rectificationId: Long, user: AppUser): MessageResp = transaction {
        val r = fireRectRow(rectificationId)
        if (r[Rectifications.violationType].isBlank()) throw ApiException(409, "该整改单不是消防专项整改")
        val orderId = r[Rectifications.orderId]
        if (r[Rectifications.status] == "PASSED") throw ApiException(409, "整改已复验通过")
        if (r[Rectifications.updatedDrawing].isBlank())
            throw ApiException(409, "商户尚未更新整改图纸，无可确认内容")
        val isFire = user.role == "FIRE" || user.role == "ADMIN"
        val isEng = user.role == "ENGINEERING" || user.role == "ADMIN"
        if (!isFire && !isEng) throw ApiException(403, "仅消防维保或工程部可确认整改图纸")
        val current = fireRectRow(rectificationId)
        if (isFire && !current[Rectifications.fireConfirmed]) {
            Rectifications.update({ Rectifications.id eq rectificationId }) {
                it[fireConfirmed] = true; it[fireConfirmedBy] = user.id; it[fireConfirmedAt] = Instant.now()
            }
            event(orderId, "FIRE_RECTIFY_CONFIRMED", "消防维保确认整改 #$rectificationId 更新图纸", user.id)
        }
        if (isEng && !current[Rectifications.engConfirmed]) {
            Rectifications.update({ Rectifications.id eq rectificationId }) {
                it[engConfirmed] = true; it[engConfirmedBy] = user.id; it[engConfirmedAt] = Instant.now()
            }
            event(orderId, "ENG_RECTIFY_CONFIRMED", "工程部确认整改 #$rectificationId 更新图纸", user.id)
        }
        val fresh = fireRectRow(rectificationId)
        if (fresh[Rectifications.fireConfirmed] && fresh[Rectifications.engConfirmed] &&
            fresh[Rectifications.status] != "REINSPECT_READY") {
            Rectifications.update({ Rectifications.id eq rectificationId }) { it[status] = "REINSPECT_READY" }
            event(orderId, "FIRE_RECTIFY_READY",
                "消防整改 #$rectificationId 经消防维保与工程部分别确认，安排消防复验", user.id)
            notify(orderId, listOf("FIRE"), "消防整改 #$rectificationId 双部门确认完成，请安排消防复验")
            return@transaction MessageResp("两方确认完成，可安排消防复验")
        }
        MessageResp(if (isFire && user.role != "ADMIN") "消防维保已确认" else "工程部已确认")
    }

    /** 消防维保复验：通过则解冻；不通过则依据问题图纸与责任施工方生成押金扣罚，须重新更新图纸确认 */
    fun reinspectFire(req: ReinspectReq, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("FIRE", "ADMIN")) throw ApiException(403, "消防复验由消防维保执行")
        val r = fireRectRow(req.rectificationId)
        if (r[Rectifications.violationType].isBlank()) throw ApiException(409, "该整改单不是消防专项整改")
        val orderId = r[Rectifications.orderId]
        if (r[Rectifications.status] == "PASSED") throw ApiException(409, "整改已复验通过")
        if (!r[Rectifications.fireConfirmed] || !r[Rectifications.engConfirmed])
            throw ApiException(409, "整改图纸须经消防维保与工程部分别确认后方可复验")
        val round = r[Rectifications.reinspectRound] + 1
        val vname = when (r[Rectifications.violationType]) {
            "SPRINKLER_OCCLUDED" -> "喷淋遮挡"
            "EXIT_SIGN_ERROR" -> "疏散指示错误"
            else -> "消防问题"
        }

        if (!req.passed) {
            // 复验不通过 → 明确扣罚依据（关联更新图纸 + 责任施工方）
            var penaltyId = r[Rectifications.penaltyId]
            if (req.penaltyAmount > 0) {
                penaltyId = Penalties.insert {
                    it[Penalties.orderId] = orderId
                    it[amount] = money(req.penaltyAmount)
                    it[reason] = "消防复验第 $round 轮不通过（$vname，责任方 ${r[Rectifications.responsibleCompany]}，" +
                        "依据图纸 ${r[Rectifications.updatedDrawing].ifBlank { r[Rectifications.drawingRef] }}）：${req.remark}"
                    it[deducted] = false
                    it[rectificationId] = req.rectificationId
                    it[createdBy] = user.id
                    it[createdAt] = Instant.now()
                } get Penalties.id
            }
            Rectifications.update({ Rectifications.id eq req.rectificationId }) {
                it[reinspectRound] = round
                it[reinspectResult] = "FAILED"
                it[status] = "OPEN"
                it[fireConfirmed] = false
                it[engConfirmed] = false
                if (penaltyId != null) it[Rectifications.penaltyId] = penaltyId
            }
            event(orderId, "FIRE_REINSPECT_FAILED",
                "消防整改 #${req.rectificationId} 第 $round 轮复验不通过（$vname），责任施工方 ${r[Rectifications.responsibleCompany]}，" +
                    "关联图纸 ${r[Rectifications.updatedDrawing].ifBlank { r[Rectifications.drawingRef] }}" +
                        (if (penaltyId != null) "，已生成扣罚依据 #$penaltyId" else ""), user.id)
            notify(orderId, listOf("MERCHANT", "FINANCE", "PROPERTY"),
                "【复验不通过】消防整改 #${req.rectificationId}（$vname）第 $round 轮复验仍不合格，须更新图纸并重新经两方确认；开业许可与押金退还继续冻结" +
                    (if (penaltyId != null) "；扣罚依据 #$penaltyId 待财务执行" else ""))
            return@transaction MessageResp("复验不通过，已关联图纸/施工队${if (penaltyId != null) "并生成扣罚依据" else ""}，开业与押金继续冻结")
        }

        // 复验通过
        Rectifications.update({ Rectifications.id eq req.rectificationId }) {
            it[reinspectRound] = round
            it[reinspectResult] = "PASSED"
            it[status] = "PASSED"
            it[resolvedAt] = Instant.now()
        }
        r[Rectifications.itemId]?.let { itemId ->
            AcceptanceItems.update({ AcceptanceItems.id eq itemId }) {
                it[status] = "PASSED"; it[inspectorId] = user.id
                it[remark] = "第 $round 轮消防复验通过" + if (req.remark.isNotBlank()) "：${req.remark}" else ""
                it[checkedAt] = Instant.now()
            }
        }
        event(orderId, "FIRE_REINSPECT_PASSED",
            "消防整改 #${req.rectificationId} 第 $round 轮复验通过（$vname，责任方 ${r[Rectifications.responsibleCompany]}）", user.id)
        notify(orderId, listOf("MERCHANT"),
            "【复验通过】消防整改 #${req.rectificationId}（$vname）第 $round 轮复验合格，相关开业限制按整改进度解除")
        finalizeAcceptanceIfDone(orderId, user.id)
        MessageResp("消防复验通过")
    }

    /** 财务退还押金（扣除已执行扣罚后的余额）；复验/整改未完成时冻结 */
    fun refundDeposit(orderId: Long, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("FINANCE", "ADMIN")) throw ApiException(403, "仅财务可退还押金")
        val o = orderRow(orderId)
        if (!o[RenovationOrders.depositPaid]) throw ApiException(409, "押金未缴纳")
        if (o[RenovationOrders.depositRefunded]) throw ApiException(409, "押金已退还")
        val unresolved = Rectifications.select {
            (Rectifications.orderId eq orderId) and
                (Rectifications.status neq "PASSED")
        }.count()
        val reasons = mutableListOf<String>()
        if (unresolved > 0L) reasons += "存在 $unresolved 项未通过复验的整改（含消防整改）"
        if (!o[RenovationOrders.fireReinspectionPassed]) reasons += "消防复验尚未全部通过"
        val unpaid = Penalties.select { (Penalties.orderId eq orderId) and (Penalties.deducted eq false) }.count()
        if (unpaid > 0L) reasons += "存在 $unpaid 笔扣罚未执行（复验不通过的扣罚依据须先处理）"
        if (reasons.isNotEmpty()) throw ApiException(409, "押金退还冻结：${reasons.joinToString("；")}")

        val refundable = (o[RenovationOrders.depositAmount] - o[RenovationOrders.totalPenalty]).coerceAtLeast(BigDecimal.ZERO)
        RenovationOrders.update({ RenovationOrders.id eq orderId }) {
            it[depositRefunded] = true
            it[depositRefundAmount] = refundable
            it[depositRefundedAt] = Instant.now()
        }
        event(orderId, "DEPOSIT_REFUNDED",
            "财务退还押金 ¥$refundable（押金 ¥${o[RenovationOrders.depositAmount]} - 已扣罚 ¥${o[RenovationOrders.totalPenalty]}）", user.id)
        notify(orderId, listOf("MERCHANT", "PROPERTY"), "押金余额 ¥$refundable 已退还")
        MessageResp("押金 ¥$refundable 已退还（扣罚 ¥${o[RenovationOrders.totalPenalty]}）")
    }

    // ---------- 押金扣罚 / 开业许可联动 ----------
    fun deductPenalty(penaltyId: Long, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("FINANCE", "ADMIN")) throw ApiException(403, "仅财务可执行押金扣罚")
        val p = Penalties.select { Penalties.id eq penaltyId }.firstOrNull() ?: throw ApiException(404, "扣罚记录不存在")
        if (p[Penalties.deducted]) throw ApiException(409, "该扣罚已执行")
        val orderId = p[Penalties.orderId]
        val o = orderRow(orderId)
        if (!o[RenovationOrders.depositPaid]) throw ApiException(409, "押金尚未缴纳，无法扣罚")
        if (p[Penalties.amount] > o[RenovationOrders.depositAmount])
            throw ApiException(409, "扣罚金额超过押金余额，需补缴后处理")
        Penalties.update({ Penalties.id eq penaltyId }) { it[deducted] = true }
        val newTotal = o[RenovationOrders.totalPenalty] + p[Penalties.amount]
        RenovationOrders.update({ RenovationOrders.id eq orderId }) {
            it[totalPenalty] = newTotal
        }
        event(orderId, "PENALTY_DEDUCTED", "财务自押金扣罚 ¥${p[Penalties.amount]}：${p[Penalties.reason]}", user.id)
        notify(orderId, listOf("MERCHANT", "PROPERTY"), "押金扣罚 ¥${p[Penalties.amount]} 已执行")
        MessageResp("扣罚 ¥${p[Penalties.amount]} 已从押金中执行")
    }

    private fun openingBlockers(o: ResultRow, orderId: Long): List<String> {
        val reasons = mutableListOf<String>()
        if (o[RenovationOrders.status] != "ACCEPTED") reasons += "完工七项验收未全部通过（当前 ${o[RenovationOrders.status]}）"
        if (!o[RenovationOrders.fireReinspectionPassed]) reasons += "消防复验未通过"
        if (!o[RenovationOrders.firePermitPassed]) reasons += "消防开业许可未放行（烟感遮挡/喷淋改动等事件须先整改复验）"
        if (!o[RenovationOrders.depositPaid]) reasons += "装修押金未缴纳"
        val unpaid = Penalties.select { (Penalties.orderId eq orderId) and (Penalties.deducted eq false) }.count()
        if (unpaid > 0L) reasons += "存在 $unpaid 笔押金扣罚未经财务执行"
        val openRects = Rectifications.select {
            (Rectifications.orderId eq orderId) and (Rectifications.status inList listOf("OPEN", "RESUBMITTED", "OVERDUE", "REINSPECT_READY"))
        }.count()
        if (openRects > 0L) reasons += "存在 $openRects 项未闭环整改"
        return reasons
    }

    fun grantOpening(orderId: Long, user: AppUser): MessageResp = transaction {
        if (user.role !in listOf("PROPERTY", "ADMIN")) throw ApiException(403, "开业许可由物业核发")
        val o = orderRow(orderId)
        val blockers = openingBlockers(o, orderId)
        if (blockers.isNotEmpty()) throw ApiException(409, "不能核发开业许可：${blockers.joinToString("；")}")
        RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[openingAllowed] = true }
        event(orderId, "OPENING_GRANTED", "物业核发开业许可（消防复验通过、验收合格、押金扣罚已结清）", user.id)
        notify(orderId, listOf("MERCHANT", "FLOOR_OPS", "FINANCE"), "装修单 ${o[RenovationOrders.orderNo]} 开业许可已核发，可以开业")
        MessageResp("开业许可已核发")
    }

    fun open(orderId: Long, user: AppUser): MessageResp {
        // 先在事务中完成校验与拦截审计写入并提交，再抛出 409，避免审计事件随异常回滚
        val outcome: Pair<List<String>, Boolean> = transaction {
            val o = orderRow(orderId)
            if (o[RenovationOrders.merchantUserId] != user.id)
                throw ApiException(403, "仅本单商户可确认开业")
            val blockers = openingBlockers(o, orderId).toMutableList()
            if (!o[RenovationOrders.openingAllowed]) blockers += "物业尚未核发开业许可，商户不得绕过物业直接开业"
            if (blockers.isNotEmpty()) {
                event(orderId, "OPENING_BLOCKED", "商户尝试开业被系统拦截：${blockers.joinToString("；")}", user.id)
                blockers to false
            } else {
                RenovationOrders.update({ RenovationOrders.id eq orderId }) { it[status] = "OPENED" }
                event(orderId, "OPENED", "店铺正式开业，档案归档（验收记录/扣罚/整改/许可齐全）", user.id)
                notify(orderId, listOf("PROPERTY", "FLOOR_OPS", "FINANCE", "FIRE"), "店铺已开业，装修单归档")
                emptyList<String>() to true
            }
        }
        if (!outcome.second) throw ApiException(409, "开业被拦截：${outcome.first.joinToString("；")}")
        return MessageResp("开业成功，装修档案已归档")
    }

    // ---------- 通知 / 看板 ----------
    fun notifications(user: AppUser): List<NotificationView> = transaction {
        val q = Notifications.selectAll().orderBy(Notifications.id, SortOrder.DESC).limit(200)
        q.filter { n ->
            val roleOk = n[Notifications.targetRole] == user.role || user.role == "ADMIN"
            if (!roleOk) return@filter false
            if (user.role != "MERCHANT") return@filter true
            val oid = n[Notifications.orderId] ?: return@filter false
            RenovationOrders.select { (RenovationOrders.id eq oid) and (RenovationOrders.merchantUserId eq user.id) }.count() > 0
        }.map {
            NotificationView(it[Notifications.id], it[Notifications.orderId], it[Notifications.targetRole],
                it[Notifications.message], it[Notifications.readFlag], it[Notifications.createdAt].toString())
        }
    }

    fun dashboard(user: AppUser): DashboardResp = transaction {
        val orders = listOrders(user)
        val myTasks = if (user.role in RuleEngine.deptNames.keys)
            ApprovalTasks.select { (ApprovalTasks.dept eq user.role) and (ApprovalTasks.status eq "PENDING") }
                .orderBy(ApprovalTasks.seq).map {
                    TaskView(it[ApprovalTasks.id], it[ApprovalTasks.dept], it[ApprovalTasks.title],
                        it[ApprovalTasks.status], null, null, it[ApprovalTasks.seq])
                }
        else emptyList()
        DashboardResp(user.role, myTasks, orders.take(50), notifications(user))
    }
}
