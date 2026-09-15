#!/usr/bin/env python3
# 消防验收不通过整改闭环：
# 整改清单（违规类型/责任施工方/关联图纸/复验时间）→ 图纸更新 → 消防+工程双确认 → 消防复验；
# 复验通过前开业许可冻结、押金退还冻结；复验不通过生成带图纸/施工队依据的扣罚；结论推送商户负责人
import json, os, sys, urllib.request, urllib.error

BASE = os.environ.get("BASE", "http://host.docker.internal:3069")
fails = []

def call(method, path, token=None, body=None, expect=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
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
        fails.append(f"{method} {path} expected {expect} got {code}: {raw[:240]}")
    return code, parsed

def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(extra)[:320]))
    if not cond: fails.append(name)

call("GET", "/health", expect=200)
def login(u, p):
    return call("POST", "/api/auth/login", body={"username": u, "password": p}, expect=200)[1]["token"]
M, P, E, S, F, FIN, OPS = (login(u, p) for u, p in [
    ("merchant1","merchant123"),("property","property123"),("engineering","engineering123"),
    ("security","security123"),("fire","fire123"),("finance","finance123"),("floorops","floorops123")])

def order_detail(oid, tok=M):
    return call("GET", f"/api/orders/{oid}", tok, expect=200)[1]

def submit_order(shop, scenario, dep_amount):
    oid = call("POST","/api/orders", M, {
      "shopCode":shop,"scenario":scenario,"drawingDoc":"drawing-v1.pdf",
      "constructionStart":"2026-09-20T22:00:00+08:00","constructionEnd":"2026-09-30T22:00:00+08:00",
      "constructionCompany":"恒信装饰工程有限公司","hotWorkRequired":False,"nightWorkRequired":True,
      "enclosurePlan":"全封闭围挡",
      "workers":[{"name":"张工","idCard":"310101199001011234"}],
      "materials":[{"name":"阻燃板","qty":"20张","declaredFlameRetardant":True}]}, expect=201)[1]["id"]
    d = order_detail(oid)
    tasks = {t["dept"]: t["id"] for t in d["tasks"]}
    toks = {"FINANCE":FIN,"PROPERTY":P,"ENGINEERING":E,"FIRE":F,"SECURITY":S,"FLOOR_OPS":OPS}
    for dept, tid in tasks.items():
        call("POST", f"/api/tasks/{tid}/review", toks[dept], {"approved":True}, expect=200)
    call("POST", f"/api/orders/{oid}/deposit/pay", M, expect=200)
    call("POST", f"/api/orders/{oid}/construction/start", M, expect=200)
    call("POST", f"/api/orders/{oid}/complete", M, expect=200)
    d = order_detail(oid)
    return oid, {x["category"]: x["id"] for x in d["checkItems"]}, dep_amount

def pass_non_fire(oid, items, exclude=()):
    for cat in ["STRONG_ELECTRIC","WEAK_ELECTRIC","SMOKE_EXHAUST","DRAINAGE","STOREFRONT","PUBLIC_RESTORE"]:
        if cat in exclude: continue
        tok = E if cat in ["STRONG_ELECTRIC","WEAK_ELECTRIC","SMOKE_EXHAUST","DRAINAGE"] else P
        call("POST", f"/api/orders/{oid}/checks", tok, {"itemId":items[cat],"passed":True}, expect=200)

def merchant_notif():
    return call("GET","/api/notifications", M, expect=200)[1]

# ========== 场景 1：喷淋遮挡，首轮复验不通过 → 扣罚依据 → 二轮通过 ==========
oid, items, dep = submit_order("1F-108", "CLOSED_RENOVATION", 30000)

# 消防验收不通过：喷淋遮挡
c, b = call("POST", f"/api/orders/{oid}/checks", F,
    {"itemId":items["FIRE"],"passed":False,"remark":"吊顶遮挡喷淋头，溅水盘距顶板超标",
     "violationType":"SPRINKLER_OCCLUDED","reinspectAt":"2026-10-03T10:00:00+08:00"}, expect=200)
check("消防不合格生成整改清单提示", "整改清单" in b["message"], b)
d = order_detail(oid)
check("单据进入 RECTIFICATION", d["summary"]["status"]=="RECTIFICATION", d["summary"])
fr = next(r for r in d["rectifications"] if r["fireRectification"])
rid = fr["id"]
check("违规类型=喷淋遮挡", fr["violationType"]=="SPRINKLER_OCCLUDED" and fr["violationName"]=="喷淋遮挡", fr)
check("责任施工方已记录", fr["responsibleCompany"]=="恒信装饰工程有限公司", fr)
check("关联问题图纸已记录", fr["drawingRef"]=="drawing-v1.pdf", fr)
check("复验时间已安排", fr["reinspectAt"] is not None, fr)
check("初始双确认均为否", fr["fireConfirmed"] is False and fr["engConfirmed"] is False, fr)

# 冻结检查
c, b = call("POST", f"/api/orders/{oid}/opening/grant", P, expect=409)
check("复验通过前开业许可冻结", "开业许可" in b["error"] or "整改" in b["error"], b)
c, b = call("POST", f"/api/orders/{oid}/deposit/refund", FIN, expect=409)
check("复验通过前押金退还冻结", "押金退还冻结" in b["error"], b)
# 不能走普通整改提交
call("POST","/api/rectifications/submit", M, {"rectificationId":rid,"note":"我改好了"}, expect=409)
# 未更新图纸不能确认
call("POST", f"/api/rectifications/{rid}/confirm", F, expect=409)

# 商户更新图纸
call("POST","/api/rectifications/drawing", M,
    {"rectificationId":rid,"updatedDrawing":"drawing-v2-sprinkler.pdf","note":"喷淋抬高改位图纸"}, expect=200)
# 仅消防确认，工程未确认 → 不可复验
call("POST", f"/api/rectifications/{rid}/confirm", F, expect=200)
call("POST", f"/api/rectifications/{rid}/confirm", P, expect=403)   # 物业无权
c, b = call("POST","/api/rectifications/reinspect", F,
    {"rectificationId":rid,"passed":True}, expect=409)
check("缺工程确认不可复验", "分别确认" in b["error"], b)
call("POST", f"/api/rectifications/{rid}/confirm", E, expect=200)
fr = next(r for r in order_detail(oid)["rectifications"] if r["id"]==rid)
check("双确认完成进入 REINSPECT_READY", fr["status"]=="REINSPECT_READY" and fr["fireConfirmed"] and fr["engConfirmed"], fr)

# 第一轮复验不通过 → 生成扣罚依据
c, b = call("POST","/api/rectifications/reinspect", F,
    {"rectificationId":rid,"passed":False,"remark":"改位后喷淋仍遮挡梁下空间","penaltyAmount":1000}, expect=200)
check("复验不通过提示含依据", "扣罚依据" in b["message"], b)
d = order_detail(oid)
fr = next(r for r in d["rectifications"] if r["id"]==rid)
pen = next((p for p in d["penalties"] if p.get("rectificationId")==rid), None)
check("复验次数=1且结果FAILED", fr["reinspectRound"]==1 and fr["reinspectResult"]=="FAILED", fr)
check("扣罚关联整改/图纸/施工队", pen is not None and pen["drawingRef"]=="drawing-v2-sprinkler.pdf"
      and pen["responsibleCompany"]=="恒信装饰工程有限公司" and not pen["deducted"], pen)
check("复验不通过双确认被重置", fr["fireConfirmed"] is False and fr["engConfirmed"] is False, fr)
check("复验不通过结论推送商户", any("复验不通过" in n["message"] for n in merchant_notif()), merchant_notif()[:4])
# 押金与开业仍冻结
call("POST", f"/api/orders/{oid}/deposit/refund", FIN, expect=409)
call("POST", f"/api/orders/{oid}/opening/grant", P, expect=409)
# 财务执行扣罚
call("POST", f"/api/penalties/{pen['id']}/deduct", FIN, expect=200)

# 第二轮：更新图纸 → 双确认 → 复验通过
call("POST","/api/rectifications/drawing", M,
    {"rectificationId":rid,"updatedDrawing":"drawing-v3-sprinkler.pdf","note":"二次整改图纸"}, expect=200)
call("POST", f"/api/rectifications/{rid}/confirm", F, expect=200)
call("POST", f"/api/rectifications/{rid}/confirm", E, expect=200)
call("POST","/api/rectifications/reinspect", F,
    {"rectificationId":rid,"passed":True,"remark":"溅水盘高度与间距合格"}, expect=200)
d = order_detail(oid)
fr = next(r for r in d["rectifications"] if r["id"]==rid)
check("第二轮复验通过", fr["status"]=="PASSED" and fr["reinspectRound"]==2 and fr["reinspectResult"]=="PASSED", fr)
check("消防验收项随之通过", next(x for x in d["checkItems"] if x["category"]=="FIRE")["status"]=="PASSED", d["checkItems"])
check("复验通过结论推送商户", any("复验通过" in n["message"] for n in merchant_notif()), merchant_notif()[:6])

# 其余六项通过 → ACCEPTED → 许可 → 押金退还（30000-1000=29000）
pass_non_fire(oid, items)
d = order_detail(oid)
check("全部验收通过 ACCEPTED", d["summary"]["status"]=="ACCEPTED" and d["summary"]["fireReinspectionPassed"], d["summary"])
call("POST", f"/api/orders/{oid}/opening/grant", P, expect=200)
c, b = call("POST", f"/api/orders/{oid}/deposit/refund", FIN, expect=200)
check("退还押金=押金-扣罚=29000", "29000" in b["message"], b)
d = order_detail(oid)
check("退还状态落库", d["summary"]["depositRefunded"] and d["summary"]["depositRefundAmount"]==29000.0, d["summary"])
call("POST", f"/api/orders/{oid}/deposit/refund", FIN, expect=409)  # 不可重复退还

# ========== 场景 2：疏散指示错误，复验一次通过 ==========
oid2, items2, dep2 = submit_order("4F-412", "PARTIAL_REPAIR", 10000)
call("POST", f"/api/orders/{oid2}/checks", F,
    {"itemId":items2["FIRE"],"passed":False,"remark":"疏散指示方向错误，应急照明照度不足",
     "violationType":"EXIT_SIGN_ERROR"}, expect=200)
d2 = order_detail(oid2)
fr2 = next(r for r in d2["rectifications"] if r["fireRectification"])
check("疏散指示错误入清单", fr2["violationType"]=="EXIT_SIGN_ERROR" and fr2["violationName"]=="疏散指示错误", fr2)
# 复验前退还冻结
call("POST", f"/api/orders/{oid2}/deposit/refund", FIN, expect=409)
call("POST","/api/rectifications/drawing", M,
    {"rectificationId":fr2["id"],"updatedDrawing":"drawing-exitsign-v2.pdf"}, expect=200)
# 工程部先确认、消防后确认，顺序不敏感
call("POST", f"/api/rectifications/{fr2['id']}/confirm", E, expect=200)
call("POST", f"/api/rectifications/{fr2['id']}/confirm", F, expect=200)
call("POST","/api/rectifications/reinspect", F,
    {"rectificationId":fr2["id"],"passed":True,"remark":"指示方向与照度合格"}, expect=200)
pass_non_fire(oid2, items2)
d2 = order_detail(oid2)
check("场景2 ACCEPTED", d2["summary"]["status"]=="ACCEPTED", d2["summary"])
call("POST", f"/api/orders/{oid2}/opening/grant", P, expect=200)
# 无扣罚 → 全额退还 10000
c, b = call("POST", f"/api/orders/{oid2}/deposit/refund", FIN, expect=200)
check("无扣罚全额退还10000", "10000" in b["message"], b)

print()
if fails:
    print(f"{len(fails)} FAILURES:"); [print(" -", x) for x in fails]; sys.exit(1)
print("ALL FIRE-RECTIFICATION CHECKS PASSED")
