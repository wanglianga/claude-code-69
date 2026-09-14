package com.mallrenovation.db

import com.mallrenovation.model.Shops
import com.mallrenovation.model.Users
import com.mallrenovation.security.Security
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object Seed {
    fun run() = transaction {
        if (Users.selectAll().count() > 0L) return@transaction

        fun user(username: String, display: String, role: String, password: String, company: String? = null) {
            val (hash, salt) = Security.hashPassword(password)
            Users.insert {
                it[Users.username] = username
                it[Users.displayName] = display
                it[Users.role] = role
                it[Users.passwordHash] = hash
                it[Users.passwordSalt] = salt
                it[Users.company] = company
            }
        }

        user("admin", "系统管理员", "ADMIN", "admin123")
        user("merchant1", "王商户（星潮服饰）", "MERCHANT", "merchant123", "星潮服饰")
        user("merchant2", "李商户（川味小厨）", "MERCHANT", "merchant123", "川味小厨")
        user("property", "赵物业（客服主管）", "PROPERTY", "property123", "商场物业部")
        user("engineering", "钱工（工程部）", "ENGINEERING", "engineering123", "商场工程部")
        user("security", "孙安保（门岗领班）", "SECURITY", "security123", "商场安保部")
        user("fire", "周工（消防维保）", "FIRE", "fire123", "消防维保单位")
        user("finance", "吴财务", "FINANCE", "finance123", "商场财务部")
        user("floorops", "郑楼层运营（1F/4F）", "FLOOR_OPS", "floorops123", "楼层运营部")

        fun shop(code: String, name: String, floor: Int, category: String, adjacent: String) {
            Shops.insert {
                it[Shops.code] = code
                it[Shops.name] = name
                it[Shops.floor] = floor
                it[Shops.category] = category
                it[Shops.adjacentShopCodes] = adjacent
            }
        }

        shop("1F-108", "星潮服饰", 1, "FASHION", "1F-107,1F-109")
        shop("4F-412", "川味小厨", 4, "RESTAURANT", "4F-411,4F-413")
        shop("1F-A01", "中庭快闪展位", 1, "RETAIL", "1F-101,1F-102,中庭服务台")
        shop("2F-205", "美颜工坊", 2, "BEAUTY", "2F-204,2F-206")
    }
}
