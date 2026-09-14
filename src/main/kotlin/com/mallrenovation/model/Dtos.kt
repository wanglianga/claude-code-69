package com.mallrenovation.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginReq(val username: String, val password: String)

@Serializable
data class LoginResp(val token: String, val username: String, val displayName: String, val role: String, val company: String? = null)

@Serializable
data class WorkerInput(val name: String, val idCard: String)

@Serializable
data class MaterialInput(val name: String, val qty: String, val declaredFlameRetardant: Boolean = false)

// 商户提交装修申请（图纸/施工周期/施工单位/人员名单/材料清单/动火/夜间施工/围挡方案）
@Serializable
data class CreateOrderReq(
    val shopCode: String,
    val scenario: String,                 // CLOSED_RENOVATION/NEW_OPEN/PARTIAL_REPAIR/FLASH_POPUP_WITHDRAW
    val drawingDoc: String,
    val constructionStart: String,        // ISO-8601
    val constructionEnd: String,
    val constructionCompany: String,
    val hotWorkRequired: Boolean = false,
    val nightWorkRequired: Boolean = false,
    val enclosurePlan: String,
    val workers: List<WorkerInput> = emptyList(),
    val materials: List<MaterialInput> = emptyList()
)

@Serializable
data class ReviewTaskReq(val approved: Boolean, val comment: String = "")

@Serializable
data class WorkerVerifyReq(
    val workerId: Long,
    val idCardOk: Boolean? = null,
    val badgeOk: Boolean? = null,
    val insuranceOk: Boolean? = null,
    val toolsOk: Boolean? = null,
    val materialsOk: Boolean? = null
)

@Serializable
data class MaterialGateReq(val materialId: Long, val allow: Boolean, val flameRetardantVerified: Boolean = false, val remark: String = "")

@Serializable
data class PermitApplyReq(
    val workType: String,                 // HOT_WORK/CUTTING/PAINTING/HIGH_ALTITUDE
    val reason: String,
    val plannedStart: String,
    val plannedEnd: String,
    val fireWatcher: String = "",
    val extinguisherCount: Int = 0
)

@Serializable
data class PermitDecisionReq(val approved: Boolean, val comment: String = "")

@Serializable
data class StartPermitWorkReq(val permitId: Long)

// 动火/切割现场复核：前四项由安保现场核验；营业时段由服务端按计划窗口与商场营业时段强制判定，不接受人为勾选
@Serializable
data class SiteReviewReq(
    val permitPresent: Boolean,       // 动火证在场
    val extinguisherOk: Boolean,       // 灭火器就位（数量/压力）
    val watcherPresent: Boolean,       // 监护人（看火人）在岗
    val smokeProtected: Boolean,       // 烟感保护到位（防护罩/隔离）
    val note: String = ""
)

// 作业异常：烟感异常 / 监护人离岗 → 自动暂停
@Serializable
data class AbnormalReq(val type: String, val detail: String = "")  // SMOKE_ALARM / WATCHER_LEAVE

@Serializable
data class SiteLogView(
    val id: Long, val reviewRound: Int, val action: String,
    val permitPresent: Boolean, val extinguisherOk: Boolean,
    val watcherPresent: Boolean, val smokeProtected: Boolean, val hoursOk: Boolean,
    val detail: String, val recordedBy: String? = null, val createdAt: String
)

@Serializable
data class IncidentReq(
    val type: String,                     // OVERTIME/NOISE/CHANNEL_BLOCKAGE/SMOKE_COVERED/SPRINKLER_MODIFICATION/CUSTOMER_COMPLAINT/TEMP_DRAWING_CHANGE
    val description: String,
    val penalty: Double = 0.0,
    val rectifyDeadline: String? = null
)

@Serializable
data class CheckItemReq(val itemId: Long, val passed: Boolean, val remark: String = "")

@Serializable
data class RectifySubmitReq(val rectificationId: Long, val note: String)

@Serializable
data class PayDepositReq(val amount: Double? = null)
@Serializable
data class DeductReq(val penaltyId: Long)

@Serializable
data class OrderSummary(
    val id: Long,
    val orderNo: String,
    val shopCode: String,
    val shopName: String,
    val floor: Int,
    val scenario: String,
    val status: String,
    val depositAmount: Double,
    val depositPaid: Boolean,
    val totalPenalty: Double,
    val fireReinspectionPassed: Boolean,
    val firePermitPassed: Boolean,
    val openingAllowed: Boolean,
    val nightWorkBlocked: Boolean = false,
    val nightBlockReason: String = "",
    val createdAt: String
)

@Serializable
data class TaskView(
    val id: Long, val dept: String, val title: String, val status: String,
    val reviewer: String? = null, val comment: String? = null, val seq: Int
)

@Serializable
data class WorkerView(
    val id: Long, val name: String, val idCard: String,
    val idCardOk: Boolean, val badgeOk: Boolean, val insuranceOk: Boolean,
    val toolsOk: Boolean, val materialsOk: Boolean,
    val readyForAdmit: Boolean, val admitted: Boolean, val admitTime: String? = null
)

@Serializable
data class MaterialView(
    val id: Long, val name: String, val qty: String,
    val declaredFlameRetardant: Boolean, val flameRetardantVerified: Boolean,
    val entryStatus: String, val gateRemark: String? = null
)

@Serializable
data class PermitView(
    val id: Long, val workType: String, val reason: String,
    val plannedStart: String, val plannedEnd: String, val status: String,
    val fireWatcher: String? = null, val extinguisherCount: Int = 0,
    val approver: String? = null, val decidedAt: String? = null,
    val siteReviewStatus: String = "NONE",
    val siteReviewRound: Int = 0,
    val siteReviewer: String? = null,
    val siteReviewedAt: String? = null,
    val pausedReason: String? = null,
    val siteLogs: List<SiteLogView> = emptyList()
)

@Serializable
data class IncidentView(
    val id: Long, val type: String, val level: String, val description: String,
    val reportedBy: String? = null, val penalty: Double,
    val rectifyDeadline: String? = null, val status: String,
    val involvedParties: List<String> = emptyList(), val createdAt: String
)

@Serializable
data class PenaltyView(
    val id: Long, val incidentId: Long? = null, val amount: Double,
    val reason: String, val deducted: Boolean
)

@Serializable
data class CheckItemView(
    val id: Long, val category: String, val status: String,
    val inspector: String? = null, val remark: String? = null, val checkedAt: String? = null
)

@Serializable
data class RectificationView(
    val id: Long, val itemId: Long? = null, val description: String,
    val deadline: String, val status: String, val submittedNote: String? = null,
    val createdAt: String, val resolvedAt: String? = null
)

@Serializable
data class EventView(val id: Long, val eventType: String, val detail: String? = null, val actor: String? = null, val createdAt: String)

@Serializable
data class NotificationView(val id: Long, val orderId: Long? = null, val targetRole: String, val message: String, val read: Boolean, val createdAt: String)

@Serializable
data class ShopView(val id: Long, val code: String, val name: String, val floor: Int, val category: String,
                    val adjacentShopCodes: String, val operatingStart: Int, val operatingEnd: Int)

@Serializable
data class OrderDetail(
    val summary: OrderSummary,
    val tasks: List<TaskView>,
    val workers: List<WorkerView>,
    val materials: List<MaterialView>,
    val permits: List<PermitView>,
    val incidents: List<IncidentView>,
    val penalties: List<PenaltyView>,
    val checkItems: List<CheckItemView>,
    val rectifications: List<RectificationView>,
    val events: List<EventView>
)

@Serializable
data class MessageResp(val message: String, val blockingReasons: List<String> = emptyList())

@Serializable
data class DashboardResp(
    val role: String,
    val pendingTasks: List<TaskView> = emptyList(),
    val orders: List<OrderSummary> = emptyList(),
    val notifications: List<NotificationView> = emptyList()
)
