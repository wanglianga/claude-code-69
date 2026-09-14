package com.mallrenovation.engine

import java.math.BigDecimal

/**
 * 根据商场规则、楼层业态、消防要求、邻近店铺与营业时段生成审批任务与管控要求。
 */
object RuleEngine {

    // 场景 → 押金基准（元）
    private val depositByScenario = mapOf(
        "CLOSED_RENOVATION" to 30_000,
        "NEW_OPEN" to 50_000,
        "PARTIAL_REPAIR" to 10_000,
        "FLASH_POPUP_WITHDRAW" to 10_000
    )

    val scenarioNames = mapOf(
        "CLOSED_RENOVATION" to "闭店装修",
        "NEW_OPEN" to "新店开业",
        "PARTIAL_REPAIR" to "局部维修",
        "FLASH_POPUP_WITHDRAW" to "品牌快闪撤场"
    )

    val categoryNames = mapOf(
        "FASHION" to "服饰", "RESTAURANT" to "餐饮", "BEAUTY" to "美妆",
        "KIDS" to "亲子", "ENTERTAINMENT" to "娱乐", "RETAIL" to "零售"
    )

    data class TaskSpec(val dept: String, val title: String, val seq: Int)

    data class RuleResult(
        val deposit: BigDecimal,
        val tasks: List<TaskSpec>,
        val noiseRule: String,
        val materialRule: String,
        val fireRule: String,
        val notes: List<String>
    )

    /**
     * @param scenario 装修场景
     * @param category 楼层业态
     * @param floor 楼层
     * @param adjacent 邻近铺位号列表
     * @param overlapsOperatingHours 施工周期是否与商场营业时段重叠
     * @param hotWork 申报动火
     * @param nightWork 申报夜间施工
     */
    fun evaluate(
        scenario: String,
        category: String,
        floor: Int,
        adjacent: List<String>,
        overlapsOperatingHours: Boolean,
        hotWork: Boolean,
        nightWork: Boolean
    ): RuleResult {
        val notes = mutableListOf<String>()
        val tasks = mutableListOf<TaskSpec>()

        // 1) 财务押金（先缴费、后进场）
        tasks += TaskSpec("FINANCE", "装修押金缴纳与施工证工本费核验（${scenarioNames[scenario]}）", 0)

        // 2) 物业综合审核
        val propTitle = buildString {
            append("物业综合审核：图纸/施工周期/施工单位/围挡方案")
            if (overlapsOperatingHours) append("；营业时段施工围挡与防尘降噪")
            if (adjacent.isNotEmpty()) append("；邻近店铺${adjacent.joinToString("、")}协同告知")
            if (nightWork) append("；夜间施工申请（22:00-次日10:00）")
        }
        tasks += TaskSpec("PROPERTY", propTitle, 1)

        // 3) 工程部
        var engTitle = "工程部审核：强弱电、结构安全、给排水接驳"
        if (floor >= 4) engTitle += "；高楼层材料垂直运输与荷载"
        if (scenario == "PARTIAL_REPAIR") engTitle += "；局部拆改不得破坏主管线"
        tasks += TaskSpec("ENGINEERING", engTitle, 2)

        // 4) 消防（业态相关要求）
        var fireTitle = when (category) {
            "RESTAURANT" -> "消防审核：厨房排烟、燃气报警、厨房灭火装置、疏散通道"
            "BEAUTY" -> "消防审核：喷漆/化工材料通风、危险品存放、烟感喷淋保护"
            "KIDS", "ENTERTAINMENT" -> "消防审核：儿童/娱乐业态阻燃材料、疏散宽度、应急照明"
            else -> "消防审核：疏散通道、应急照明、灭火器配置"
        }
        fireTitle += "；禁止擅自改动喷淋与遮挡烟感"
        if (hotWork) fireTitle += "；申报含动火，作业前必须单独办理动火证"
        tasks += TaskSpec("FIRE", fireTitle, 3)

        // 5) 安保
        var secTitle = "安保审核：围挡封闭、人员通道、工具登记、夜间值守"
        if (nightWork) secTitle += "；夜间施工人员登记与看护"
        if (overlapsOperatingHours) secTitle += "；营业期间材料运输路线"
        tasks += TaskSpec("SECURITY", secTitle, 4)

        // 6) 楼层运营（顾客动线 / 周边商户收入影响）
        var opsTitle = "楼层运营会签：顾客动线改道、周边商户营业影响告知"
        if (adjacent.isNotEmpty()) opsTitle += "（重点：${adjacent.joinToString("、")}）"
        if (overlapsOperatingHours) opsTitle += "；营业时段噪声与粉尘管控"
        tasks += TaskSpec("FLOOR_OPS", opsTitle, 5)

        // 场景化管控规则
        val noiseRule = when (scenario) {
            "CLOSED_RENOVATION" -> "闭店施工：营业时段（10:00-22:00）禁止电锤/切割等高噪声作业；22:00-次日10:00 可施工但须提前报备"
            "NEW_OPEN" -> "新店开业装修：营业时段禁止强噪声作业，卸货与切割限非营业时段"
            "PARTIAL_REPAIR" -> "局部维修：营业时段仅允许低噪声作业（≤55dB），切割/拆除限 22:00 后，必须双层隔音围挡"
            "FLASH_POPUP_WITHDRAW" -> "快闪撤场：撤场与进场一律安排在 22:00-次日 09:00，禁止营业时段噪声与占道"
            else -> "按商场装修管理规定执行"
        }

        val materialRule = buildString {
            append(when (scenario) {
                "CLOSED_RENOVATION" -> "大宗材料可在非营业时段批量进场，木作/软装需提供阻燃证明"
                "NEW_OPEN" -> "所有材料进场须与申报清单一致；"
                "PARTIAL_REPAIR" -> "局部维修材料限量进场、随用随清，不得占用公共通道"
                "FLASH_POPUP_WITHDRAW" -> "撤场材料清单逐车核销，成品保护到位后方可退押金"
                else -> ""
            })
            when (category) {
                "KIDS", "ENTERTAINMENT" -> append("；亲子/娱乐业态阻燃材料必须 100% 抽检合格")
                "RESTAURANT" -> append("；餐饮排烟风管/燃气管件须有合格证")
                "BEAUTY" -> append("；化工/喷漆材料按危险品双人双锁管理")
                else -> {}
            }
        }

        val fireRule = buildString {
            append(when (scenario) {
                "CLOSED_RENOVATION" -> "喷淋/烟感拆改须由消防维保实施并复验；动火办证、看火人到位"
                "NEW_OPEN" -> "开业前消防验收一票否决：消防/排烟不合格不得核发开业许可"
                "PARTIAL_REPAIR" -> "局部维修不得停用区域消防设施；确需停用须报批并采取临时措施"
                "FLASH_POPUP_WITHDRAW" -> "撤场不得损坏公共喷淋、烟感与消火栓；恢复后消防复验"
                else -> ""
            })
            if (category == "RESTAURANT") append("；餐饮厨房灭火与排烟为必验项")
        }

        if (overlapsOperatingHours && scenario in listOf("PARTIAL_REPAIR", "NEW_OPEN")) {
            notes += "施工周期与商场营业时段重叠：已加严围挡、噪声与顾客动线管控，并由楼层运营会签"
        }
        if (hotWork) notes += "申报含动火需求：施工阶段动火/切割仍须逐次办理专项作业票，审批通过不替代作业票"
        if (nightWork) notes += "含夜间施工：仅限低噪声工序，安保夜间值守并登记人员"
        if (adjacent.isNotEmpty()) notes += "邻近店铺 ${adjacent.joinToString("、")}：审批结果将同步楼层运营并告知周边商户"
        if (floor >= 4) notes += "高楼层（${floor}F）：高空作业必须单独办理高空作业票"

        return RuleResult(
            deposit = depositByScenario[scenario]?.let { BigDecimal(it) } ?: BigDecimal(20_000),
            tasks = tasks,
            noiseRule = noiseRule,
            materialRule = materialRule,
            fireRule = fireRule,
            notes = notes
        )
    }

    // 事件等级与涉及处置方（商户、物业、工程部、安保、消防维保、财务在同一装修单内联动）
    val incidentLevel = mapOf(
        "OVERTIME" to "BREACH",
        "NOISE" to "WARN",
        "CHANNEL_BLOCKAGE" to "BREACH",
        "SMOKE_COVERED" to "BREACH",
        "SPRINKLER_MODIFICATION" to "BREACH",
        "CUSTOMER_COMPLAINT" to "WARN",
        "TEMP_DRAWING_CHANGE" to "WARN"
    )

    val incidentNames = mapOf(
        "OVERTIME" to "施工超时",
        "NOISE" to "噪声扰民",
        "CHANNEL_BLOCKAGE" to "材料堆占通道",
        "SMOKE_COVERED" to "烟感被遮挡",
        "SPRINKLER_MODIFICATION" to "消防喷淋改动",
        "CUSTOMER_COMPLAINT" to "顾客投诉",
        "TEMP_DRAWING_CHANGE" to "商户临时改图"
    )

    val incidentParties = mapOf(
        "OVERTIME" to listOf("PROPERTY", "ENGINEERING", "SECURITY", "FINANCE"),
        "NOISE" to listOf("FLOOR_OPS", "PROPERTY", "SECURITY"),
        "CHANNEL_BLOCKAGE" to listOf("SECURITY", "FIRE", "FLOOR_OPS"),
        "SMOKE_COVERED" to listOf("FIRE", "SECURITY"),
        "SPRINKLER_MODIFICATION" to listOf("FIRE", "ENGINEERING", "PROPERTY", "FINANCE"),
        "CUSTOMER_COMPLAINT" to listOf("FLOOR_OPS", "PROPERTY", "FINANCE"),
        "TEMP_DRAWING_CHANGE" to listOf("PROPERTY", "ENGINEERING", "FIRE", "FLOOR_OPS")
    )

    // 验收项 → 主责部门角色
    val itemInspectorRole = mapOf(
        "FIRE" to "FIRE",
        "STRONG_ELECTRIC" to "ENGINEERING",
        "WEAK_ELECTRIC" to "ENGINEERING",
        "SMOKE_EXHAUST" to "ENGINEERING",
        "DRAINAGE" to "ENGINEERING",
        "STOREFRONT" to "PROPERTY",
        "PUBLIC_RESTORE" to "PROPERTY"
    )

    val itemNames = mapOf(
        "FIRE" to "消防（喷淋/烟感/疏散/灭火器）",
        "STRONG_ELECTRIC" to "强电",
        "WEAK_ELECTRIC" to "弱电",
        "SMOKE_EXHAUST" to "排烟",
        "DRAINAGE" to "排水",
        "STOREFRONT" to "门头",
        "PUBLIC_RESTORE" to "公共区域恢复"
    )

    val deptNames = mapOf(
        "PROPERTY" to "物业", "ENGINEERING" to "工程部", "FIRE" to "消防维保",
        "SECURITY" to "安保", "FINANCE" to "财务", "FLOOR_OPS" to "楼层运营"
    )

    val permitNames = mapOf(
        "HOT_WORK" to "动火作业", "CUTTING" to "切割作业",
        "PAINTING" to "喷漆作业", "HIGH_ALTITUDE" to "高空作业"
    )
}
