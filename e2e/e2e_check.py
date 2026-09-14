#!/usr/bin/env python3
# 端到端业务流验证（在宿主侧通过映射端口访问）
import json, os, sys, urllib.request, urllib.error

BASE = os.environ.get("BASE", "http://host.docker.internal:3069")
fails = []

def call(method, path, token=None, body=None, expect=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            code, raw = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        code, raw = e.code, e.read().decode()
    try: parsed = json.loads(raw)
    except Exception: parsed = raw
    if expect is not None and code != expect:
        fails.append(f"{method} {path} expected {expect} got {code}: {raw[:200]}")
    return code, parsed

def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(extra)))
    if not cond: fails.append(name)

# ---- 0. health ----
code, b = call("GET", "/health", expect=200)
check("health UP", b.get("status") == "UP")

# ---- 1. 登录全部角色 ----
def login(u, p):
    c, b = call("POST", "/api/auth/login", body={"username": u, "password": p}, expect=200)
    return b["token"]
tokens = {}
for u, p, role in [
    ("merchant1","merchant123","MERCHANT"), ("merchant2","merchant123","MERCHANT2"),
    ("property","property123","PROPERTY"), ("engineering","engineering123","ENG"),
    ("security","security123","SEC"), ("fire","fire123","FIRE"),
    ("finance","finance123","FIN"), ("floorops","floorops123","OPS"),
]:
    tokens[role] = login(u, p)
c, b = call("POST", "/api/auth/login", body={"username":"merchant1","password":"wrong"}, expect=401)
check("错误密码拒绝(401)", c == 401)
M, P, E, S, F, FIN, OPS = (tokens[k] for k in ["MERCHANT","PROPERTY","ENG","SEC","FIRE","FIN","OPS"])

# ---- 2. 商户提交闭店装修（动火+夜间，8天跨营业时段）----
order_body = {
  "shopCode":"1F-108","scenario":"CLOSED_RENOVATION",
  "drawingDoc":"drawing-1F108-v3.pdf",
  "constructionStart":"2026-09-20T22:00:00+08:00","constructionEnd":"2026-09-28T22:00:00+08:00",
  "constructionCompany":"恒信装饰工程有限公司",
  "hotWorkRequired":True,"nightWorkRequired":True,
  "enclosurePlan":"双层石膏板全封闭围挡，高2.5m，留巡检小门，外置警示灯",
  "workers":[{"name":"张师傅","idCard":"310101199001011234"},{"name":"李小工","idCard":"310101199203034567"}],
  "materials":[{"name":"阻燃木工板","qty":"40张","declaredFlameRetardant":True},{"name":"乳胶漆","qty":"10桶"}]
}
c, b = call("POST","/api/orders", M, order_body, expect=201)
oid = b["id"]
check("申请已创建", b["status"]=="PENDING_REVIEW" and b["depositAmount"]==30000.0, b)
# 非商户禁止申报
call("POST","/api/orders", S, order_body, expect=403)
# 缺少人员/材料
bad = dict(order_body); bad["workers"]=[]
call("POST","/api/orders", M, bad, expect=400)

d = call("GET", f"/api/orders/{oid}", M, expect=200)[1]
dept_task = {t["dept"]: t["id"] for t in d["tasks"]}
check("六方审批任务生成", set(dept_task)=={"FINANCE","PROPERTY","ENGINEERING","FIRE","SECURITY","FLOOR_OPS"}, dept_task)
evs = " ".join(e["detail"] or "" for e in d["events"])
check("规则含噪声/材料/消防/邻里/营业时段", all(k in evs for k in ["噪声","材料","消防","邻近店铺","营业时段"]), evs[:300])

# 楼层运营收到邻里影响通知
n = call("GET","/api/notifications", OPS, expect=200)[1]
check("楼层运营收到邻里收入/动线通知", any("邻近商户" in x["message"] for x in n), n[:2])

# 未审批先开工 → 拦截
call("POST", f"/api/orders/{oid}/construction/start", M, expect=409)

# ---- 3. 六方会签（越权审核应 403）----
call("POST", f"/api/tasks/{dept_task['FINANCE']}/review", S, {"approved":True}, expect=403)
for role, tok in [("FINANCE",FIN),("PROPERTY",P),("ENGINEERING",E),("FIRE",F),("SECURITY",S),("FLOOR_OPS",OPS)]:
    c, b = call("POST", f"/api/tasks/{dept_task[role]}/review", tok,
                {"approved":True,"comment":f"{role}同意"}, expect=200)
# 重复审核
call("POST", f"/api/tasks/{dept_task['FINANCE']}/review", FIN, {"approved":True}, expect=409)

# ---- 4. 押金 → 施工证 ----
call("POST", f"/api/orders/{oid}/deposit/pay", S, expect=403)
call("POST", f"/api/orders/{oid}/deposit/pay", M, expect=200)
c, b = call("POST", f"/api/orders/{oid}/construction/start", M, expect=200)
check("施工证生效", "施工证已生效" in b["message"], b)

# ---- 5. 人员五项核验与放行 ----
w1, w2 = d["workers"][0]["id"], d["workers"][1]["id"]
# 核验不全 → 禁止进场
call("POST","/api/workers/verify", S, {"workerId":w1,"idCardOk":True,"badgeOk":True}, expect=200)
call("POST", f"/api/workers/{w1}/admit", S, expect=409)
call("POST","/api/workers/verify", S,
     {"workerId":w1,"idCardOk":True,"badgeOk":True,"insuranceOk":True,"toolsOk":True,"materialsOk":True}, expect=200)
c, b = call("POST", f"/api/workers/{w1}/admit", S, expect=200)
check("五项齐全后放行", "放行成功" in b["message"], b)
# 非安保核验
call("POST","/api/workers/verify", P, {"workerId":w2,"idCardOk":True}, expect=403)
for f in ["idCardOk","badgeOk","insuranceOk","toolsOk","materialsOk"]:
    call("POST","/api/workers/verify", S, {"workerId":w2,f:True}, expect=200)
call("POST", f"/api/workers/{w2}/admit", S, expect=200)

# ---- 6. 材料门岗（阻燃抽检）----
m1, m2 = d["materials"][0]["id"], d["materials"][1]["id"]
call("POST","/api/materials/gate", S, {"materialId":m1,"allow":True}, expect=409)  # 阻燃未抽检
call("POST","/api/materials/gate", S, {"materialId":m1,"allow":True,"flameRetardantVerified":True,"remark":"证书+抽检合格"}, expect=200)
call("POST","/api/materials/gate", S, {"materialId":m2,"allow":False,"remark":"清单不符，暂扣"}, expect=200)

# ---- 7. 动火专项作业票 ----
bad_permit = {"workType":"HOT_WORK","reason":"焊接钢梁","plannedStart":"2026-09-21T23:00:00+08:00",
              "plannedEnd":"2026-09-22T02:00:00+08:00","fireWatcher":"","extinguisherCount":0}
call("POST", f"/api/orders/{oid}/permits", M, bad_permit, expect=400)  # 无看火人/灭火器
pid = call("POST", f"/api/orders/{oid}/permits", M,
    {"workType":"HOT_WORK","reason":"焊接钢梁支架","plannedStart":"2026-09-21T23:00:00+08:00",
     "plannedEnd":"2026-09-22T02:00:00+08:00","fireWatcher":"刘看火","extinguisherCount":2}, expect=201)[1]["id"]
call("POST", f"/api/permits/{pid}/decision", P, {"approved":True}, expect=403)  # 物业无权批动火
call("POST", f"/api/permits/{pid}/decision", F, {"approved":True}, expect=200)
# 开工前安保现场复核五项（夜间窗口避开营业时段）
call("POST", f"/api/permits/{pid}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=200)
call("POST", f"/api/permits/{pid}/start", M, expect=200)
call("POST", f"/api/permits/{pid}/finish", M, expect=200)

# ---- 8. 事件多方协同 ----
i1 = call("POST", f"/api/orders/{oid}/incidents", OPS,
    {"type":"NOISE","description":"营业时段电锤噪声，邻铺1F-107投诉","penalty":500}, expect=201)[1]
check("噪声事件含多部门", set(i1["involvedParties"])=={"楼层运营","物业","安保"}, i1)
call("POST", f"/api/incidents/{i1['id']}/handling", P, expect=200)
call("POST", f"/api/incidents/{i1['id']}/resolve", P, expect=200)

i2 = call("POST", f"/api/orders/{oid}/incidents", F,
    {"type":"SPRINKLER_MODIFICATION","description":"吊顶单位擅自改动喷淋支管","penalty":3000}, expect=201)[1]
check("喷淋事件多方(消防/工程/物业/财务)", set(i2["involvedParties"])=={"消防维保","工程部","物业","财务"}, i2)
# 未闭环先完工 → 拦截
call("POST", f"/api/orders/{oid}/complete", M, expect=409)
# 财务扣罚噪声
d = call("GET", f"/api/orders/{oid}", M)[1]
pen = {p["reason"][:2]: p["id"] for p in d["penalties"]}
pen_ids = [p["id"] for p in d["penalties"]]
call("POST", f"/api/penalties/{pen_ids[0]}/deduct", P, expect=403)
call("POST", f"/api/penalties/{pen_ids[0]}/deduct", FIN, expect=200)
# 喷淋事件非消防不能闭环
call("POST", f"/api/incidents/{i2['id']}/resolve", P, expect=403)
call("POST", f"/api/incidents/{i2['id']}/resolve", F, expect=200)

# ---- 9. 临时改图 → 追加复核任务（第二个事件流程，随后闭环）----
i3 = call("POST", f"/api/orders/{oid}/incidents", P,
    {"type":"TEMP_DRAWING_CHANGE","description":"商户要求吊顶加灯槽，涉及喷淋位移"}, expect=201)[1]
d = call("GET", f"/api/orders/{oid}", M)[1]
change_tasks = [t for t in d["tasks"] if "临时改图" in t["title"]]
check("改图生成4方复核任务", len(change_tasks)==4, len(change_tasks))
# 有未闭环事件 + 未复核任务 → 完工仍被拦截
call("POST", f"/api/orders/{oid}/complete", M, expect=409)
call("POST", f"/api/incidents/{i3['id']}/handling", E, expect=200)
call("POST", f"/api/incidents/{i3['id']}/resolve", E, expect=200)
for t in change_tasks:
    tok = {"PROPERTY":P,"ENGINEERING":E,"FIRE":F,"FLOOR_OPS":OPS}[t["dept"]]
    call("POST", f"/api/tasks/{t['id']}/review", tok, {"approved":True,"comment":"改图复核通过"}, expect=200)

# ---- 10. 完工报验 + 7项验收 + 整改复验 ----
call("POST", f"/api/orders/{oid}/complete", M, expect=200)
d = call("GET", f"/api/orders/{oid}", M)[1]
check("生成7项验收", len(d["checkItems"])==7 and {x["category"] for x in d["checkItems"]}==
      {"FIRE","STRONG_ELECTRIC","WEAK_ELECTRIC","SMOKE_EXHAUST","DRAINAGE","STOREFRONT","PUBLIC_RESTORE"})
items = {x["category"]: x["id"] for x in d["checkItems"]}
# 越权验收
call("POST", f"/api/orders/{oid}/checks", P, {"itemId":items["FIRE"],"passed":True}, expect=403)
# 消防首次不合格 → 整改单
call("POST", f"/api/orders/{oid}/checks", F, {"itemId":items["FIRE"],"passed":False,"remark":"保压试验不合格"}, expect=200)
d = call("GET", f"/api/orders/{oid}", M)[1]
check("单据进入整改状态", any(x["status"] in ("OPEN",) for x in d["rectifications"]), d["rectifications"])
rid = d["rectifications"][0]["id"]
call("POST","/api/rectifications/submit", S, {"rectificationId":rid,"note":"x"}, expect=403)
call("POST","/api/rectifications/submit", M, {"rectificationId":rid,"note":"已换管保压30分钟无渗漏"}, expect=200)
call("POST", f"/api/orders/{oid}/checks", F, {"itemId":items["FIRE"],"passed":True,"remark":"复验合格"}, expect=200)
# 工程四项 + 物业两项
for cat in ["STRONG_ELECTRIC","WEAK_ELECTRIC","SMOKE_EXHAUST","DRAINAGE"]:
    call("POST", f"/api/orders/{oid}/checks", E, {"itemId":items[cat],"passed":True}, expect=200)
for cat in ["STOREFRONT","PUBLIC_RESTORE"]:
    call("POST", f"/api/orders/{oid}/checks", P, {"itemId":items[cat],"passed":True}, expect=200)

# 第二笔扣罚（喷淋）必须结清
d = call("GET", f"/api/orders/{oid}", M)[1]
unpaid = [p["id"] for p in d["penalties"] if not p["deducted"]]
for pid2 in unpaid:
    call("POST", f"/api/penalties/{pid2}/deduct", FIN, expect=200)
d = call("GET", f"/api/orders/{oid}", M)[1]
check("七项全过→ACCEPTED/消防复验通过", d["summary"]["status"]=="ACCEPTED"
      and d["summary"]["fireReinspectionPassed"] and d["summary"]["firePermitPassed"], d["summary"])
check("扣罚累计3500", d["summary"]["totalPenalty"]==3500.0, d["summary"])

# ---- 11. 开业防绕过 ----
call("POST", f"/api/orders/{oid}/opening/grant", M, expect=403)          # 商户无权发许可
c, b = call("POST", f"/api/orders/{oid}/open", M, expect=409)           # 物业未许可
check("无开业许可直接开业被拦截", "开业许可" in b["error"], b)
call("POST", f"/api/orders/{oid}/opening/grant", P, expect=200)
c, b = call("POST", f"/api/orders/{oid}/open", M, expect=200)
check("许可后开业成功并归档", "开业成功" in b["message"], b)
d = call("GET", f"/api/orders/{oid}", M)[1]
check("终态 OPENED", d["summary"]["status"]=="OPENED", d["summary"])
# 审计事件存在 OPENING_BLOCKED
check("拦截写入审计事件", any(e["eventType"]=="OPENING_BLOCKED" for e in d["events"]))

# ---- 12. 另一商户：局部维修（营业时段）规则差异 ----
c, b = call("POST","/api/orders", tokens["MERCHANT2"], {
  "shopCode":"4F-412","scenario":"PARTIAL_REPAIR",
  "drawingDoc":"4f412-repair.pdf",
  "constructionStart":"2026-09-25T13:00:00+08:00","constructionEnd":"2026-09-25T17:00:00+08:00",
  "constructionCompany":"快修班组","hotWorkRequired":False,"nightWorkRequired":False,
  "enclosurePlan":"软质围挡+隔音棉","workers":[{"name":"王焊","idCard":"310101198805056789"}],
  "materials":[{"name":"瓷砖","qty":"5箱"}]}, expect=201)
oid2 = b["id"]
check("局部维修押金1万", b["depositAmount"]==10000.0, b)
d2 = call("GET", f"/api/orders/{oid2}", tokens["MERCHANT2"])[1]
evs2 = " ".join(e["detail"] or "" for e in d2["events"])
check("局部维修规则含55dB/餐饮排烟/高空/营业时段加严",
      all(k in evs2 for k in ["55dB","排烟","高空作业","营业时段"]), evs2[:400])

print()
if fails:
    print(f"{len(fails)} FAILURES:"); [print(" -", x) for x in fails]; sys.exit(1)
print("ALL E2E CHECKS PASSED")
