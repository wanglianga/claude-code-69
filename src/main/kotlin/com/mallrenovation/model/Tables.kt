package com.mallrenovation.model

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 64).uniqueIndex()
    val displayName = varchar("display_name", 128)
    val role = varchar("role", 32)
    val passwordHash = varchar("password_hash", 256)
    val passwordSalt = varchar("password_salt", 128)
    val company = varchar("company", 128).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Shops : Table("shops") {
    val id = long("id").autoIncrement()
    val code = varchar("code", 32).uniqueIndex()
    val name = varchar("name", 128)
    val floor = integer("floor")
    val category = varchar("category", 32)
    val adjacentShopCodes = varchar("adjacent_shop_codes", 256)
    val operatingStart = integer("operating_start").default(10)
    val operatingEnd = integer("operating_end").default(22)
    override val primaryKey = PrimaryKey(id)
}

object RenovationOrders : Table("renovation_orders") {
    val id = long("id").autoIncrement()
    val orderNo = varchar("order_no", 40).uniqueIndex()
    val shopId = long("shop_id")
    val merchantUserId = long("merchant_user_id")
    val scenario = varchar("scenario", 24)
    val status = varchar("status", 32)
    val drawingDoc = varchar("drawing_doc", 256)
    val constructionStart = timestamp("construction_start")
    val constructionEnd = timestamp("construction_end")
    val constructionCompany = varchar("construction_company", 128)
    val hotWorkRequired = bool("hot_work_required").default(false)
    val nightWorkRequired = bool("night_work_required").default(false)
    val enclosurePlan = text("enclosure_plan")
    val depositAmount = decimal("deposit_amount", 12, 2)
    val depositPaid = bool("deposit_paid").default(false)
    val fireReinspectionPassed = bool("fire_reinspection_passed").default(false)
    val openingAllowed = bool("opening_allowed").default(false)
    val firePermitPassed = bool("fire_permit_passed").default(false)
    val totalPenalty = decimal("total_penalty", 12, 2).default(0.toBigDecimal())
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ApprovalTasks : Table("approval_tasks") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val dept = varchar("dept", 24)
    val title = varchar("title", 160)
    val status = varchar("status", 16).default("PENDING")
    val reviewerId = long("reviewer_id").nullable()
    val comment = varchar("comment", 512).nullable()
    val seq = integer("seq").default(0)
    val createdAt = timestamp("created_at")
    val reviewedAt = timestamp("reviewed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Workers : Table("workers") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val name = varchar("name", 64)
    val idCard = varchar("id_card", 32)
    val idCardOk = bool("id_card_ok").default(false)
    val badgeOk = bool("badge_ok").default(false)
    val insuranceOk = bool("insurance_ok").default(false)
    val toolsOk = bool("tools_ok").default(false)
    val materialsOk = bool("materials_ok").default(false)
    val admitted = bool("admitted").default(false)
    val admitTime = timestamp("admit_time").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MaterialItems : Table("material_items") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val name = varchar("name", 128)
    val qty = varchar("qty", 40)
    val declaredFlameRetardant = bool("declared_flame_retardant").default(false)
    val flameRetardantVerified = bool("flame_retardant_verified").default(false)
    val entryStatus = varchar("entry_status", 16).default("DECLARED")
    val gateRemark = varchar("gate_remark", 256).nullable()
    override val primaryKey = PrimaryKey(id)
}

object SpecialWorkPermits : Table("special_work_permits") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val workType = varchar("work_type", 16)
    val reason = varchar("reason", 256)
    val plannedStart = timestamp("planned_start")
    val plannedEnd = timestamp("planned_end")
    val status = varchar("status", 16).default("APPLIED")
    val fireWatcher = varchar("fire_watcher", 64).nullable()
    val extinguisherCount = integer("extinguisher_count").default(0)
    val approverId = long("approver_id").nullable()
    val createdAt = timestamp("created_at")
    val decidedAt = timestamp("decided_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Incidents : Table("incidents") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val type = varchar("type", 28)
    val level = varchar("level", 8)
    val description = varchar("description", 512)
    val reportedBy = long("reported_by").nullable()
    val penalty = decimal("penalty", 12, 2).default(0.toBigDecimal())
    val rectifyDeadline = timestamp("rectify_deadline").nullable()
    val status = varchar("status", 16).default("OPEN")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Penalties : Table("penalties") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val incidentId = long("incident_id").nullable()
    val amount = decimal("amount", 12, 2)
    val reason = varchar("reason", 256)
    val deducted = bool("deducted").default(false)
    val createdBy = long("created_by").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AcceptanceItems : Table("acceptance_check_items") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val category = varchar("category", 24)
    val inspectorId = long("inspector_id").nullable()
    val status = varchar("status", 16).default("PENDING")
    val remark = varchar("remark", 512).nullable()
    val checkedAt = timestamp("checked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Rectifications : Table("rectifications") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val itemId = long("item_id").nullable()
    val description = varchar("description", 512)
    val deadline = timestamp("deadline")
    val status = varchar("status", 16).default("OPEN")
    val submittedNote = varchar("submitted_note", 512).nullable()
    val createdAt = timestamp("created_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object OrderEvents : Table("order_events") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val eventType = varchar("event_type", 48)
    val detail = varchar("detail", 512).nullable()
    val actorId = long("actor_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Notifications : Table("notifications") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id").nullable()
    val targetRole = varchar("target_role", 24)
    val message = varchar("message", 512)
    val readFlag = bool("read_flag").default(false)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
