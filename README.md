# 商场店铺装修进场审批与消防验收服务

基于 **Kotlin + Ktor + PostgreSQL + Exposed** 的商场装修全流程协同审批服务。覆盖商户申报、六方会签审批、进场核验、专项作业票、施工事件多方处置、完工逐项验收、整改复验、押金扣罚与开业许可状态联动，核心保证：**消防复验未通过、物业未核发开业许可时，商户无法绕过物业直接开业**。

## 原始需求

> 开发商场店铺装修进场审批与消防验收服务，可采用 Kotlin、Ktor 和 PostgreSQL。商户提交装修图纸、施工周期、施工单位、人员名单、材料清单、动火需求、夜间施工和围挡方案后，服务根据商场规则、楼层业态、消防要求、邻近店铺和营业时段生成审批任务。物业审核通过后，施工人员进场需要核验身份证、工牌、保险、工具和材料；动火、切割、喷漆和高空作业必须单独申请。若施工超时、噪声扰民、材料堆占通道、烟感被遮挡、消防喷淋改动、顾客投诉或商户临时改图，服务要把商户、物业、工程部、安保、消防维保和财务放在同一装修单中处理。完工后，消防、强弱电、排烟、排水、门头和公共区域恢复要逐项验收，押金扣罚、整改期限和开业许可进入档案。服务还要区分闭店装修、新店开业、局部维修和品牌快闪撤场，不同场景的材料进场、噪声限制和消防要求不同。商场营业期间的装修会影响周边商户收入和顾客动线，审批结果必须同步给楼层运营。装修押金、施工证、材料出入、消防整改和开业许可之间要形成状态联动，商户不能在消防复验未通过时绕过物业直接开业。

## 技术栈

- Kotlin 1.9 / Ktor 2.3（Netty）
- PostgreSQL 16 + Exposed ORM + HikariCP
- PBKDF2 密码哈希 + HMAC Bearer Token 鉴权
- Gradle 多阶段 Docker 构建，运行镜像非 root 用户、内置 HEALTHCHECK

## 一键启动（宿主 docker compose）

```bash
cp .env.example .env          # CC_PUBLISH_PORT 已按本任务环境预置为 3069
docker compose up -d --build
```

- 只把应用服务 **8080** 端口发布到宿主 `${CC_PUBLISH_PORT}`；PostgreSQL 不发布端口，仅在 compose 内部网络通过服务名 `db` 访问。
- 健康检查：应用容器 `HEALTHCHECK` 调用 `GET /health`；可等待容器 healthy。
- 取映射端口并访问（不要硬编码端口）：

```bash
docker compose port app 8080
# 输出示例 0.0.0.0:3069 → 用 http://host.docker.internal:3069/health 访问
curl -s http://host.docker.internal:$(docker compose port app 8080 | cut -d: -f2)/health
```

- 停止与清理：

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 同时删除数据库卷
```

## 测试账号（逐角色）

| 用户名 | 密码 | 角色 | 权限 |
|---|---|---|---|
| `admin` | `admin123` | 系统管理员 ADMIN | 全权（可代任意部门审核/验收） |
| `merchant1` | `merchant123` | 商户 MERCHANT（星潮服饰，铺位 1F-108） | 提交申请、缴押金、办作业票、提交整改、确认开业 |
| `merchant2` | `merchant123` | 商户 MERCHANT（川味小厨，铺位 4F-412） | 同上（餐饮业态，排烟/燃气为消防重点） |
| `property` | `property123` | 物业 PROPERTY | 综合审核、门头/公共区域恢复验收、核发开业许可 |
| `engineering` | `engineering123` | 工程部 ENGINEERING | 强弱电/排烟/排水验收、事件处置 |
| `security` | `security123` | 安保 SECURITY | 门岗人员五项核验放行、材料出入、作业票审批 |
| `fire` | `fire123` | 消防维保 FIRE | 消防审批与验收、动火票审批、消防设施事件闭环 |
| `finance` | `finance123` | 财务 FINANCE | 押金节点会签、押金扣罚执行 |
| `floorops` | `floorops123` | 楼层运营 FLOOR_OPS | 顾客动线/邻里影响会签、投诉处置、审批结果同步 |

种子铺位：`1F-108` 星潮服饰（FASHION，邻铺 1F-107/1F-109）、`4F-412` 川味小厨（RESTAURANT）、`1F-A01` 中庭快闪展位、`2F-205` 美颜工坊（BEAUTY）。

## 业务规则与状态联动

### 1. 申报 → 规则引擎生成任务
商户提交图纸、施工周期、施工单位、人员名单、材料清单、动火/夜间施工、围挡方案后，系统按以下维度生成审批任务与管控要求（写入装修单事件流）：

- **四种场景区分**（押金/噪声/材料/消防要求不同）：
  - `CLOSED_RENOVATION` 闭店装修（押金 3 万；营业时段禁止高噪声）
  - `NEW_OPEN` 新店开业（押金 5 万；消防一票否决）
  - `PARTIAL_REPAIR` 局部维修（押金 1 万；营业时段 ≤55dB，切割限 22:00 后）
  - `FLASH_POPUP_WITHDRAW` 品牌快闪撤场（押金 1 万；撤场限 22:00–09:00）
- **楼层业态**：餐饮强制排烟/燃气/厨房灭火审核与验收；美妆喷漆危险品双锁；亲子/娱乐阻燃 100% 抽检。
- **消防要求**：禁止擅自改喷淋、遮挡烟感；申报动火只影响审批，作业时仍须逐次办动火证。
- **邻近店铺 + 营业时段**：施工周期与 10:00–22:00 重叠时，自动加严围挡/降噪要求并强制楼层运营会签，向楼层运营推送邻里收入与顾客动线影响通知。
- 自动生成 **财务（押金）→ 物业 → 工程 → 消防 → 安保 → 楼层运营** 六方审批任务，审批结果以通知同步楼层运营。

### 2. 施工证 / 进场
- 六方会签全部通过 **且** 押金缴纳后，`/construction/start` 才生效施工证。
- 进场人员必须通过安保 **身份证、工牌、保险、工具、材料** 五项核验，任一未过不可放行；装修单未在施工状态也不可进场。
- 材料门岗核验：申报阻燃材料必须现场抽检合格才放行，否则拦截并通知商户。

### 3. 专项作业票
动火 `HOT_WORK`、切割 `CUTTING`、喷漆 `PAINTING`、高空 `HIGH_ALTITUDE` 必须单独申请；动火/切割强制看火人 + ≥2 具灭火器；消防/安保审批，作业有 申请→批准→进行中→完工 生命周期。

#### 动火/切割现场复核闭环（安保）
纸票审批通过不等于可动火，动火/切割开工前必须由安保完成**现场五项复核**：

| 复核项 | 判定方式 |
|---|---|
| 动火证在场 | 安保现场核验 |
| 灭火器就位（数量/压力） | 安保现场核验（申请阶段已强制 ≥2 具、指定看火人） |
| 监护人（看火人）在岗 | 安保现场核验 |
| 烟感保护到位（防护罩/隔离） | 安保现场核验 |
| 作业避开商场营业时段 | **服务端按计划窗口与铺位营业时段（10:00–22:00）逐日强制判定**，不接受人为勾选 |

- 复核五项全部满足才允许开工；任一项不合格返回 409，复核记录仍落库可追溯，并**暂停当晚施工许可**（门岗联动：许可暂停期间禁止再放行进施工人员）。
- 开始（含恢复）、结束、异常全部写入 `permit_site_logs` 并回写装修单事件流。
- **作业中烟感异常（`SMOKE_ALARM`）或监护人离岗（`WATCHER_LEAVE`）→ 系统自动暂停作业**（状态 `PAUSED`），通知安保立即到场复核，同步暂停当晚施工许可。
- **暂停后恢复动火必须重新现场复核（复核轮次 +1），不能沿用原审批**；仅在存在"暂停之后"的新一轮复核通过记录时才允许恢复（`RESUME_RECHECK_PASS`）。
- 作业安全结束或复核通过后，当晚施工许可自动恢复。

相关接口：`POST /api/permits/{id}/site-review`（安保现场复核）、`POST /api/permits/{id}/abnormal`（烟感异常/监护人离岗，自动暂停）、`/api/permits/{id}/start|finish`。

### 4. 事件多方协同（同一装修单）
超时、噪声、材料堆占通道、烟感被遮挡、喷淋改动、顾客投诉、临时改图 7 类事件：自动判定告警/违约等级、整改期限，通知涉及部门联合处置，可生成押金扣罚记录。
- **临时改图**：自动追加物业/工程/消防/楼层运营改图复核任务，未通过不能完工报验。
- **烟感遮挡/喷淋改动**：消防许可立即冻结，必须消防维保确认整改并复验后才能进入开业环节。

### 5. 逐项验收 + 整改复验
完工报验生成 7 项：消防 FIRE（消防）、强电、弱电、排烟、排水（工程）、门头、公共区域恢复（物业）。任一不合格自动开具整改单与期限，商户提交复验、主责部门复验通过闭环。

### 6. 押金扣罚 → 开业许可 → 开业（防绕过）
`/opening/grant`（物业核发许可）与 `/open`（商户开业）均执行同一组硬性拦截：

1. 七项验收未全过；2. 消防复验未通过；3. 消防许可未放行；4. 押金未缴；5. 存在未执行扣罚；6. 整改未闭环；7. 物业未核发许可。

任一不满足，商户直接调 `/open` 会返回 **409 + 具体拦截原因** 并写入 `OPENING_BLOCKED` 审计事件。

## 全流程接口演示（curl）

设 `BASE=http://host.docker.internal:$(docker compose port app 8080 | cut -d: -f2)`。

```bash
# 登录取 token
TOKEN=$(curl -s -XPOST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"merchant1","password":"merchant123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
auth="Authorization: Bearer $TOKEN"; ct='Content-Type: application/json'

# 1) 商户提交闭店装修（含动火、夜间施工）
curl -s -XPOST $BASE/api/orders -H "$auth" -H "$ct" -d '{
  "shopCode":"1F-108","scenario":"CLOSED_RENOVATION",
  "drawingDoc":"drawing-1F108-v3.pdf",
  "constructionStart":"2026-09-20T22:00:00+08:00","constructionEnd":"2026-09-28T22:00:00+08:00",
  "constructionCompany":"恒信装饰工程有限公司",
  "hotWorkRequired":true,"nightWorkRequired":true,
  "enclosurePlan":"双层石膏板全封闭围挡，高2.5m，留巡检小门，外置警示灯",
  "workers":[{"name":"张师傅","idCard":"310101199001011234"},{"name":"李小工","idCard":"310101199203034567"}],
  "materials":[{"name":"阻燃木工板","qty":"40张","declaredFlameRetardant":true},{"name":"乳胶漆","qty":"10桶"}]}'
OID=1   # 取返回 id
curl -s $BASE/api/orders/$OID -H "$auth"     # 查看六方任务、规则说明（events）

# 2) 六方依次审批（用各角色 token；taskId 取自 /api/orders/{id} 或 /api/dashboard）
#    finance / property / engineering / fire / security / floorops
curl -s -XPOST $BASE/api/tasks/1/review -H "Authorization: Bearer $FIN_TOKEN" -H "$ct" -d '{"approved":true,"comment":"押金标准已核定"}'
# ...其余 5 个任务同理

# 3) 商户缴押金 → 开工（施工证生效）
curl -s -XPOST $BASE/api/orders/$OID/deposit/pay -H "$auth"
curl -s -XPOST $BASE/api/orders/$OID/construction/start -H "$auth"

# 4) 安保五项核验并放行（逐项更新；五项齐才能 admit）
curl -s -XPOST $BASE/api/workers/verify -H "Authorization: Bearer $SEC_TOKEN" -H "$ct" \
  -d '{"workerId":1,"idCardOk":true,"badgeOk":true,"insuranceOk":true,"toolsOk":true,"materialsOk":true}'
curl -s -XPOST $BASE/api/workers/1/admit -H "Authorization: Bearer $SEC_TOKEN"

# 材料：阻燃板抽检合格放行；无证明的材料可 allow=false 拦截
curl -s -XPOST $BASE/api/materials/gate -H "Authorization: Bearer $SEC_TOKEN" -H "$ct" \
  -d '{"materialId":1,"allow":true,"flameRetardantVerified":true,"remark":"阻燃证书+抽检合格"}'

# 5) 动火专项作业票（无看火人/灭火器会被 400 拒绝）
curl -s -XPOST $BASE/api/orders/$OID/permits -H "$auth" -H "$ct" -d '{
  "workType":"HOT_WORK","reason":"焊接钢梁支架",
  "plannedStart":"2026-09-21T23:00:00+08:00","plannedEnd":"2026-09-22T02:00:00+08:00",
  "fireWatcher":"刘看火","extinguisherCount":2}'
curl -s -XPOST $BASE/api/permits/1/decision -H "Authorization: Bearer $FIRE_TOKEN" -H "$ct" -d '{"approved":true}'
curl -s -XPOST $BASE/api/permits/1/start -H "$auth"
curl -s -XPOST $BASE/api/permits/1/finish -H "$auth"

# 6) 事件：噪声扰民（告警）；喷淋擅改（违约 → 消防许可冻结）
curl -s -XPOST $BASE/api/orders/$OID/incidents -H "Authorization: Bearer $FLOOR_TOKEN" -H "$ct" \
  -d '{"type":"NOISE","description":"营业时段电锤噪声，邻铺1F-107投诉","penalty":500}'
curl -s -XPOST $BASE/api/incidents/1/handling -H "Authorization: Bearer $PROP_TOKEN"
curl -s -XPOST $BASE/api/incidents/1/resolve  -H "Authorization: Bearer $PROP_TOKEN"
curl -s -XPOST $BASE/api/orders/$OID/incidents -H "Authorization: Bearer $FIRE_TOKEN" -H "$ct" \
  -d '{"type":"SPRINKLER_MODIFICATION","description":"吊顶单位擅自改动喷淋支管","penalty":3000}'
# 财务执行扣罚
curl -s -XPOST $BASE/api/penalties/1/deduct -H "Authorization: Bearer $FIN_TOKEN"
# 消防维保确认整改闭环（事件2，须 FIRE 角色 resolve）
curl -s -XPOST $BASE/api/incidents/2/resolve -H "Authorization: Bearer $FIRE_TOKEN"

# 7) 完工报验 → 7 项逐项验收（FIRE/ENGINEERING×4/PROPERTY×2，取 itemId）
curl -s -XPOST $BASE/api/orders/$OID/complete -H "$auth"
curl -s -XPOST $BASE/api/orders/$OID/checks -H "Authorization: Bearer $FIRE_TOKEN" -H "$ct" \
  -d '{"itemId":1,"passed":false,"remark":"喷淋支管复位后保压试验不合格"}'
# 商户整改 → 消防复验通过
curl -s -XPOST $BASE/api/rectifications/submit -H "$auth" -H "$ct" \
  -d '{"rectificationId":1,"note":"已更换支管并保压30分钟无渗漏"}'
curl -s -XPOST $BASE/api/orders/$OID/checks -H "Authorization: Bearer $FIRE_TOKEN" -H "$ct" \
  -d '{"itemId":1,"passed":true,"remark":"复验合格"}'
# 其余 6 项 passed=true（角色见上）

# 8) 物业核发开业许可；商户尝试直接开业
curl -s -XPOST $BASE/api/orders/$OID/open -H "$auth"
#    → 409：物业尚未核发开业许可，商户不得绕过物业直接开业
curl -s -XPOST $BASE/api/orders/$OID/opening/grant -H "Authorization: Bearer $PROP_TOKEN"
curl -s -XPOST $BASE/api/orders/$OID/open -H "$auth"   # → 开业成功，装修单归档
```

## 自动化端到端验证

`e2e/e2e_check.py` 覆盖：错误密码拒绝、申报规则引擎（六方任务/押金/噪声材料消防规则/邻里通知）、越权拦截、六方会签、押金→施工证、五项核验放行、阻燃材料抽检拦截、动火票（看火人/灭火器强制、审批权限）、两类事件多方协同与扣罚、临时改图复核、完工七项验收+整改复验、扣罚累计、**无开业许可直接开业 409 拦截并写审计**、许可后开业归档、局部维修场景差异化规则。

`e2e/e2e_authz.py` 覆盖关键状态变更的角色/归属校验（越权均 **403**，装修单、验收项、事件状态保持不变，并写 `ACCESS_DENIED` 审计）：

- 开工/施工证生效、完工报验：**仅本装修单授权商户或 ADMIN**；楼层运营、财务、非本单商户请求一律 403；
- 事件处置（handling）：仅管理角色（物业/工程/安保/消防/财务/楼层运营）；
- 事件闭环（resolve）：仅该事件的责任部门（如噪声→楼层运营/物业/安保，临时改图→物业/工程/消防/楼层运营）；**烟感遮挡/喷淋改动必须消防维保 FIRE 闭环**；商户（含本单与非本单）一律 403。

```bash
BASE=http://host.docker.internal:$(docker compose port app 8080 | cut -d: -f2) python3 e2e/e2e_check.py
BASE=http://host.docker.internal:$(docker compose port app 8080 | cut -d: -f2) python3 e2e/e2e_authz.py
BASE=http://host.docker.internal:$(docker compose port app 8080 | cut -d: -f2) python3 e2e/e2e_hotwork.py
```

## 主要接口一览

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录获取 Bearer Token |
| GET | `/health` | 公开 | 健康检查 |
| GET | `/api/dashboard` | 全部 | 本角色待办任务/装修单/通知 |
| GET | `/api/shops` `/api/orders` `/api/orders/{id}` `/api/notifications` | 全部 | 铺位/单据/详情/通知 |
| POST | `/api/orders` | 商户 | 提交装修申请（触发规则引擎） |
| POST | `/api/tasks/{id}/review` | 对应部门 | 审批（任一驳回整单退回） |
| POST | `/api/orders/{id}/deposit/pay` | 商户 | 缴押金 |
| POST | `/api/orders/{id}/construction/start` | **仅本单商户** | 六方通过+押金后开工（楼层运营/财务/非本单商户 403） |
| POST | `/api/workers/verify` `/api/workers/{id}/admit` | 安保 | 五项核验、放行 |
| POST | `/api/materials/gate` | 安保/消防 | 材料放行/拦截（阻燃抽检） |
| POST | `/api/orders/{id}/permits` `/api/permits/{id}/decision|start|finish` | 商户/消防安保 | 专项作业票全生命周期 |
| POST | `/api/permits/{id}/site-review` | 安保 | 动火/切割现场五项复核（营业时段服务端判定） |
| POST | `/api/permits/{id}/abnormal` | 安保/消防/物业 | 烟感异常/监护人离岗 → 自动暂停，恢复须重新复核 |
| POST | `/api/orders/{id}/incidents` `/api/incidents/{id}/handling|resolve` | 各管理部门 | 事件登记与多方闭环；闭环限事件责任部门（消防事件须 FIRE），商户 403 |
| POST | `/api/orders/{id}/complete` `/api/orders/{id}/checks` | **仅本单商户**报验/主责部门验收 | 完工报验（非本单商户/管理角色 403）、7 项验收 |
| POST | `/api/rectifications/submit` | 商户 | 整改提交复验 |
| POST | `/api/penalties/{id}/deduct` | 财务 | 押金扣罚 |
| POST | `/api/orders/{id}/opening/grant` | 物业 | 核发开业许可（联动校验） |
| POST | `/api/orders/{id}/open` | 商户 | 确认开业（防绕过拦截） |

所有写操作均记录在 `order_events` 事件流，通知写入 `notifications`，与押金、施工证、材料出入、消防整改、开业许可形成状态联动，最终随开业归档。
