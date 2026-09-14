#!/usr/bin/env python3
# 多张专项作业票并存时的当晚施工许可联动验证：
# 一张动火/切割票暂停待复核期间，结束/复核另一张票不得解除 nightWorkBlocked；
# 只有同单所有动火/切割票完成有效复核或安全结束后才恢复；门岗放行与事件流一致。
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
        fails.append(f"{method} {path} expected {expect} got {code}: {raw[:220]}")
    return code, parsed

def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(extra)[:300]))
    if not cond: fails.append(name)

call("GET", "/health", expect=200)
def login(u, p):
    return call("POST", "/api/auth/login", body={"username": u, "password": p}, expect=200)[1]["token"]
M, P, E, S, F, FIN, OPS = (login(u, p) for u, p in [
    ("merchant1","merchant123"),("property","property123"),("engineering","engineering123"),
    ("security","security123"),("fire","fire123"),("finance","finance123"),("floorops","floorops123")])

# ---- 建单 → 会签 → 开工；准备 3 名施工人员 ----
oid = call("POST","/api/orders", M, {
  "shopCode":"1F-108","scenario":"CLOSED_RENOVATION","drawingDoc":"multi.pdf",
  "constructionStart":"2026-09-20T22:00:00+08:00","constructionEnd":"2026-10-05T22:00:00+08:00",
  "constructionCompany":"恒信装饰","hotWorkRequired":True,"nightWorkRequired":True,
  "enclosurePlan":"全封闭围挡",
  "workers":[{"name":f,"idCard":f"31010119900101{i:04d}"} for i,f in enumerate(["张焊","李切","王喷"], start=1)],
  "materials":[{"name":"钢管","qty":"20根"},{"name":"油漆","qty":"5桶"}]}, expect=201)[1]["id"]
d = call("GET", f"/api/orders/{oid}", M)[1]
tasks = {t["dept"]: t["id"] for t in d["tasks"]}
workers = {w["name"]: w["id"] for w in d["workers"]}
for role, tok in [("FINANCE",FIN),("PROPERTY",P),("ENGINEERING",E),("FIRE",F),("SECURITY",S),("FLOOR_OPS",OPS)]:
    call("POST", f"/api/tasks/{tasks[role]}/review", tok, {"approved":True}, expect=200)
call("POST", f"/api/orders/{oid}/deposit/pay", M, expect=200)
call("POST", f"/api/orders/{oid}/construction/start", M, expect=200)

def verify_admit(name, should_admit=True):
    wid = workers[name]
    for fld in ["idCardOk","badgeOk","insuranceOk","toolsOk","materialsOk"]:
        call("POST","/api/workers/verify", S, {"workerId":wid,fld:True}, expect=200)
    c, b = call("POST", f"/api/workers/{wid}/admit", S)
    if should_admit:
        check(f"门岗放行 {name}", c == 200, b)
    else:
        check(f"门岗拦截 {name}（当晚许可暂停）", c == 409 and "施工许可已暂停" in b["error"], b)

verify_admit("张焊", True)  # 初始未阻断

def order_state():
    return call("GET", f"/api/orders/{oid}", M)[1]["summary"]
def permit(pid):
    return next(p for p in call("GET", f"/api/orders/{oid}", M)[1]["permits"] if p["id"] == pid)
def events():
    return [e["eventType"] for e in call("GET", f"/api/orders/{oid}", M)[1]["events"]]

def issue(wtype, s, e, watcher="刘看火", ext=2):
    pid = call("POST", f"/api/orders/{oid}/permits", M,
        {"workType":wtype,"reason":"钢梁作业","plannedStart":s,"plannedEnd":e,
         "fireWatcher":watcher,"extinguisherCount":ext}, expect=201)[1]["id"]
    call("POST", f"/api/permits/{pid}/decision", F, {"approved":True}, expect=200)
    return pid
def site_ok(pid, note=""):
    return call("POST", f"/api/permits/{pid}/site-review", S,
        {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,
         "smokeProtected":True,"note":note}, expect=200)

# 票A：CUTTING 夜间窗口，复核通过→开工→监护人离岗自动暂停（未重新复核）
pidA = issue("CUTTING","2026-09-22T23:00:00+08:00","2026-09-23T02:00:00+08:00")
site_ok(pidA)
call("POST", f"/api/permits/{pidA}/start", M, expect=200)
call("POST", f"/api/permits/{pidA}/abnormal", F, {"type":"WATCHER_LEAVE","detail":"看火人离岗"}, expect=200)
check("票A暂停待复核 → 当晚许可暂停", permit(pidA)["status"]=="PAUSED" and order_state()["nightWorkBlocked"], permit(pidA)["status"])
verify_admit("李切", False)  # 门岗联动拦截

# 票B：HOT_WORK 另一张，复核通过并安全结束 —— 不得解除许可（票A仍暂停待复核）
pidB = issue("HOT_WORK","2026-09-24T23:00:00+08:00","2026-09-25T02:00:00+08:00")
site_ok(pidB)
call("POST", f"/api/permits/{pidB}/start", M, expect=200)
call("POST", f"/api/permits/{pidB}/finish", M, expect=200)
st = order_state()
check("结束票B不解除许可（票A仍暂停）", st["nightWorkBlocked"] is True, st)
check("未误写 NIGHT_WORK_RESTORED", events().count("NIGHT_WORK_RESTORED") == 0, events())

# 票C：CUTTING 复核失败 —— 仍不解除
pidC = issue("CUTTING","2026-09-25T23:00:00+08:00","2026-09-26T01:00:00+08:00")
c, b = call("POST", f"/api/permits/{pidC}/site-review", S,
    {"permitPresent":True,"extinguisherOk":True,"watcherPresent":False,"smokeProtected":True}, expect=409)
check("票C复核失败许可仍暂停", order_state()["nightWorkBlocked"] is True, order_state())

# 票D：PAINTING 复核通过、开始、结束 —— 非动火票完全不得影响该标志
pidD = issue("PAINTING","2026-09-26T22:30:00+08:00","2026-09-26T23:30:00+08:00")
call("POST", f"/api/permits/{pidD}/start", M, expect=200)
call("POST", f"/api/permits/{pidD}/finish", M, expect=200)
check("结束喷漆票不解除动火限制", order_state()["nightWorkBlocked"] is True, order_state())

# 票C 重新复核通过 —— 票A仍暂停，故仍不解除
site_ok(pidC, "监护人已归位")
check("票C复核通过但票A待复核 → 仍暂停", order_state()["nightWorkBlocked"] is True, order_state())

# 票A 完成有效重新复核 → 票C已通过、票B已结束，此刻全部解除
c, b = site_ok(pidA, "看火人重新到岗，现场条件复核")
check("票A重新复核通过提示", "重新复核" in b["message"], b)
st = order_state()
check("所有风险票解除 → 当晚许可恢复", st["nightWorkBlocked"] is False, st)
check("恢复仅写一次综合 NIGHT_WORK_RESTORED", events().count("NIGHT_WORK_RESTORED") == 1,
      [x for x in events() if x=="NIGHT_WORK_RESTORED"])
verify_admit("王喷", True)  # 门岗恢复放行

# 恢复票A后开始并安全结束，状态保持不阻断
call("POST", f"/api/permits/{pidA}/start", M, expect=200)
call("POST", f"/api/permits/{pidA}/finish", M, expect=200)
check("票A安全结束后许可仍为恢复态", order_state()["nightWorkBlocked"] is False, order_state())

# ---- 反向场景：一张 IN_PROGRESS、另一张暂停，结束进行中的票也不得解除 ----
pidE = issue("HOT_WORK","2026-09-27T23:00:00+08:00","2026-09-28T02:00:00+08:00")
site_ok(pidE); call("POST", f"/api/permits/{pidE}/start", M, expect=200)
pidG = issue("CUTTING","2026-09-28T23:00:00+08:00","2026-09-29T01:00:00+08:00")
site_ok(pidG); call("POST", f"/api/permits/{pidG}/start", M, expect=200)
call("POST", f"/api/permits/{pidG}/abnormal", S, {"type":"SMOKE_ALARM","detail":"烟感报警"}, expect=200)
check("票G暂停 → 许可再次暂停", order_state()["nightWorkBlocked"] is True, order_state())
# 结束仍在进行中的票E
call("POST", f"/api/permits/{pidE}/finish", M, expect=200)
check("结束进行中票E不解除（票G暂停待复核）", order_state()["nightWorkBlocked"] is True, order_state())
# 票G重新复核后恢复开工再结束
site_ok(pidG, "烟感保护重新加固")
check("票G重新复核后许可恢复", order_state()["nightWorkBlocked"] is False, order_state())
call("POST", f"/api/permits/{pidG}/start", M, expect=200)
call("POST", f"/api/permits/{pidG}/finish", M, expect=200)
# 开工过的票 A/B/E/G 全部安全结束；票C复核通过未开工（APPROVED+有效复核，同样不阻断）
pc = permit(pidC)
check("最终全部安全结束", order_state()["nightWorkBlocked"] is False and
      all(permit(x)["status"]=="FINISHED" for x in [pidA,pidB,pidE,pidG]) and
      pc["status"]=="APPROVED" and pc["siteReviewStatus"]=="PASSED", order_state())

print()
if fails:
    print(f"{len(fails)} FAILURES:"); [print(" -", x) for x in fails]; sys.exit(1)
print("ALL MULTI-PERMIT NIGHT-BLOCK CHECKS PASSED")
