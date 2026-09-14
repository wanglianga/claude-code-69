#!/usr/bin/env python3
# 角色/归属校验验收：
# 1) floorops / finance / 非本单商户 请求开工、完工 → 一律 403，装修单与验收项状态不变
# 2) 商户关闭 噪声 / 消防 / 临时改图 事件 → 403，事件状态保持 OPEN
# 3) 全部拒绝均写入 ACCESS_DENIED 审计事件
# 4) 正向对照：本单商户可开工/完工，责任部门可闭环事件
import json, os, sys, urllib.request, urllib.error

BASE = os.environ.get("BASE", "http://host.docker.internal:3069")
fails = []

def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode())
        except Exception: return e.code, {}

def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(extra)))
    if not cond: fails.append(name)

call("GET", "/health")
def login(u, p): return call("POST", "/api/auth/login", body={"username": u, "password": p})[1]["token"]
M1 = login("merchant1", "merchant123")
M2 = login("merchant2", "merchant123")
P, E, S, F, FIN, OPS = (login(*x) for x in [
    ("property","property123"), ("engineering","engineering123"),
    ("security","security123"), ("fire","fire123"),
    ("finance","finance123"), ("floorops","floorops123")])

def detail(oid, tok=M1):
    return call("GET", f"/api/orders/{oid}", tok)[1]

def audit_count(oid):
    return sum(1 for e in detail(oid)["events"] if e["eventType"] == "ACCESS_DENIED")

# ---- 建单 + 六方会签 + 缴押金（不开工）----
oid = call("POST", "/api/orders", M1, {
  "shopCode":"1F-108","scenario":"CLOSED_RENOVATION",
  "drawingDoc":"authz.pdf",
  "constructionStart":"2026-11-01T22:00:00+08:00","constructionEnd":"2026-11-09T22:00:00+08:00",
  "constructionCompany":"恒信装饰","hotWorkRequired":True,"nightWorkRequired":True,
  "enclosurePlan":"全封闭围挡",
  "workers":[{"name":"张","idCard":"310101199001011111"},{"name":"李","idCard":"310101199002022222"}],
  "materials":[{"name":"阻燃板","qty":"10张","declaredFlameRetardant":True}]})[1]["id"]
tasks = {t["dept"]: t["id"] for t in detail(oid)["tasks"]}
for dept, tok in [("FINANCE",FIN),("PROPERTY",P),("ENGINEERING",E),("FIRE",F),("SECURITY",S),("FLOOR_OPS",OPS)]:
    call("POST", f"/api/tasks/{tasks[dept]}/review", tok, {"approved": True})
call("POST", f"/api/orders/{oid}/deposit/pay", M1)

# ===== 一、开工越权：楼层运营 / 财务 / 非本单商户 =====
for who, tok in [("floorops", OPS), ("finance", FIN), ("merchant2(非本单)", M2)]:
    code, b = call("POST", f"/api/orders/{oid}/construction/start", tok)
    check(f"开工被 {who} 请求→403", code == 403, (code, b))
st = detail(oid)["summary"]["status"]
check("被拒后装修单仍为 PENDING_REVIEW", st == "PENDING_REVIEW", st)
check("开工拒绝写入3条 ACCESS_DENIED 审计", audit_count(oid) == 3, audit_count(oid))

# 正向：本单商户开工成功
code, b = call("POST", f"/api/orders/{oid}/construction/start", M1)
check("本单商户开工成功", code == 200 and detail(oid)["summary"]["status"] == "UNDER_CONSTRUCTION", (code, b))
# 已开工后 floorops 再调仍是 403（归属校验先于状态校验）
check("floorops 重复开工仍403", call("POST", f"/api/orders/{oid}/construction/start", OPS)[0] == 403)

# ===== 二、事件闭环越权：商户关闭 噪声/消防/临时改图 =====
def deny_incident(desc, report_tok, report_body, deny_toks, resolver_tok):
    iid = call("POST", f"/api/orders/{oid}/incidents", report_tok, report_body)[1]["id"]
    for who, tok, action in deny_toks:
        code, b = call("POST", f"/api/incidents/{iid}/{action}", tok)
        check(f"{desc} 被 {who} {action}→403", code == 403, (code, b))
    cur = [x for x in detail(oid)["incidents"] if x["id"] == iid][0]["status"]
    check(f"{desc} 拒绝后状态保持 OPEN", cur == "OPEN", cur)
    # 责任部门正向闭环
    call("POST", f"/api/incidents/{iid}/handling", resolver_tok)
    code, _ = call("POST", f"/api/incidents/{iid}/resolve", resolver_tok)
    check(f"{desc} 责任部门闭环成功", code == 200 and
          [x for x in detail(oid)["incidents"] if x["id"] == iid][0]["status"] == "RESOLVED", code)
    return iid

deny_incident("噪声事件", OPS,
    {"type":"NOISE","description":"电锤噪声被邻铺投诉","penalty":200},
    [("商户M1", M1, "handling"), ("商户M1", M1, "resolve"), ("非本单商户M2", M2, "resolve")],
    OPS)

deny_incident("消防喷淋事件", F,
    {"type":"SPRINKLER_MODIFICATION","description":"擅改喷淋支管","penalty":2000},
    [("商户M1", M1, "resolve"), ("物业P(非消防)", P, "resolve")],
    F)

# 临时改图会追加 4 个复核任务
iid3 = deny_incident("临时改图事件", P,
    {"type":"TEMP_DRAWING_CHANGE","description":"商户要求加灯槽"},
    [("商户M1", M1, "handling"), ("商户M1", M1, "resolve"), ("财务FIN(非责任)", FIN, "resolve")],
    E)
d = detail(oid)
change = [t for t in d["tasks"] if "临时改图" in t["title"]]
check("临时改图生成4方复核任务", len(change) == 4, len(change))
# 这些任务被拒关闭事件不受影响（已 RESOLVED）
check("临时改图事件已闭环", [x for x in d["incidents"] if x["id"] == iid3][0]["status"] == "RESOLVED")

# 消防扣罚需结清才能完工
for p_ in detail(oid)["penalties"]:
    if not p_["deducted"]:
        call("POST", f"/api/penalties/{p_['id']}/deduct", FIN)
# 复核任务会签
for t in change:
    tok = {"PROPERTY":P,"ENGINEERING":E,"FIRE":F,"FLOOR_OPS":OPS}[t["dept"]]
    call("POST", f"/api/tasks/{t['id']}/review", tok, {"approved": True})

# ===== 三、完工越权：楼层运营 / 财务 / 非本单商户 =====
for who, tok in [("floorops", OPS), ("finance", FIN), ("merchant2(非本单)", M2)]:
    code, b = call("POST", f"/api/orders/{oid}/complete", tok)
    check(f"完工被 {who} 请求→403", code == 403, (code, b))
st = detail(oid)["summary"]["status"]
check("被拒后装修单仍为 UNDER_CONSTRUCTION", st == "UNDER_CONSTRUCTION", st)
check("被拒后未生成验收项", len(detail(oid)["checkItems"]) == 0, len(detail(oid)["checkItems"]))

# 正向：本单商户完工报验成功
code, b = call("POST", f"/api/orders/{oid}/complete", M1)
check("本单商户完工报验成功", code == 200 and len(detail(oid)["checkItems"]) == 7, (code, b))

# 审计汇总
evs = detail(oid)["events"]
denied = [e for e in evs if e["eventType"] == "ACCESS_DENIED"]
print(f"ACCESS_DENIED 审计共 {len(denied)} 条：")
for e in denied: print("  -", e["detail"])
check("拒绝全部留审计(开工3+重复1+噪声3+喷淋2+改图3+完工3=15)", len(denied) == 15, len(denied))

print()
if fails:
    print(f"{len(fails)} FAILURES:"); [print(" -", x) for x in fails]; sys.exit(1)
print("ALL AUTHZ CHECKS PASSED")
