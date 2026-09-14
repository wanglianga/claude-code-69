#!/usr/bin/env python3
# 动火作业现场复核闭环验证：
# 五项复核（动火证/灭火器/监护人/烟感保护/营业时段服务端判定）、
# 无复核禁开工、复核失败联动当晚施工许可、烟感异常自动暂停、
# 恢复必须重新复核（不能沿用原审批）、开始/结束/异常回写装修单
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
M  = login("merchant1","merchant123")
P  = login("property","property123")
E  = login("engineering","engineering123")
S  = login("security","security123")
F  = login("fire","fire123")
FIN= login("finance","finance123")
OPS= login("floorops","floorops123")

# ---- 建单 → 六方会签 → 押金 → 施工证 ----
oid = call("POST","/api/orders", M, {
  "shopCode":"1F-108","scenario":"CLOSED_RENOVATION","drawingDoc":"hw.pdf",
  "constructionStart":"2026-09-20T22:00:00+08:00","constructionEnd":"2026-09-30T22:00:00+08:00",
  "constructionCompany":"恒信装饰","hotWorkRequired":True,"nightWorkRequired":True,
  "enclosurePlan":"全封闭围挡",
  "workers":[{"name":"张焊","idCard":"310101199001011234"},{"name":"李辅","idCard":"310101199203034567"}],
  "materials":[{"name":"钢管","qty":"20根"}]}, expect=201)[1]["id"]
d = call("GET", f"/api/orders/{oid}", M, expect=200)[1]
tasks = {t["dept"]: t["id"] for t in d["tasks"]}
w1, w2 = d["workers"][0]["id"], d["workers"][1]["id"]
for role, tok in [("FINANCE",FIN),("PROPERTY",P),("ENGINEERING",E),("FIRE",F),("SECURITY",S),("FLOOR_OPS",OPS)]:
    call("POST", f"/api/tasks/{tasks[role]}/review", tok, {"approved":True}, expect=200)
call("POST", f"/api/orders/{oid}/deposit/pay", M, expect=200)
call("POST", f"/api/orders/{oid}/construction/start", M, expect=200)
for f in ["idCardOk","badgeOk","insuranceOk","toolsOk","materialsOk"]:
    call("POST","/api/workers/verify", S, {"workerId":w1,f:True}, expect=200)
call("POST", f"/api/workers/{w1}/admit", S, expect=200)

def permit_view(pid):
    d = call("GET", f"/api/orders/{oid}", M)[1]
    return next(p for p in d["permits"] if p["id"] == pid), d

def apply_permit(wtype, s, e, watcher="刘看火", ext=2):
    return call("POST", f"/api/orders/{oid}/permits", M, {
        "workType":wtype,"reason":"焊接/切割钢梁",
        "plannedStart":s,"plannedEnd":e,
        "fireWatcher":watcher,"extinguisherCount":ext}, expect=201)[1]["id"]

# ========== 场景 A：白天窗口动火 → 营业时段服务端否决 ==========
pidA = apply_permit("HOT_WORK","2026-09-21T13:00:00+08:00","2026-09-21T17:00:00+08:00")
call("POST", f"/api/permits/{pidA}/decision", F, {"approved":True}, expect=200)
# 未现场复核直接开工 → 拒绝
c, b = call("POST", f"/api/permits/{pidA}/start", M, expect=409)
check("无现场复核禁止开工", "现场复核" in b["error"], b)
# 非安保复核 → 403
call("POST", f"/api/permits/{pidA}/site-review", P,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=403)
# 四项人工条件全满足，但营业时段由服务端判定 → 仍失败
c, b = call("POST", f"/api/permits/{pidA}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=409)
check("营业时段内复核被服务端否决", "营业时段" in b["error"], b)
pv, d = permit_view(pidA)
check("失败复核记录可追溯落库", any(l["action"]=="SITE_CHECK_FAIL" and l["hoursOk"] is False for l in pv["siteLogs"]), pv["siteLogs"])
check("装修单当晚施工许可暂停", d["summary"]["nightWorkBlocked"] is True, d["summary"])
# 许可暂停期间门岗禁止再放人
for f in ["idCardOk","badgeOk","insuranceOk","toolsOk","materialsOk"]:
    call("POST","/api/workers/verify", S, {"workerId":w2,f:True}, expect=200)
c, b = call("POST", f"/api/workers/{w2}/admit", S, expect=409)
check("夜间许可暂停联动门岗禁入", "施工许可已暂停" in b["error"], b)
# 该白天动火票不再作业 → 取消后不参与风险评估，许可恢复
call("POST", f"/api/permits/{pidA}/cancel", M, expect=200)
_, d = permit_view(pidA)
check("取消复核失败票后夜间许可恢复", d["summary"]["nightWorkBlocked"] is False, d["summary"])

# ========== 场景 B：夜间窗口动火，缺监护人 → 恢复后通过 → 异常暂停 → 重新复核恢复 ==========
pidB = apply_permit("HOT_WORK","2026-09-22T23:00:00+08:00","2026-09-23T02:00:00+08:00")
call("POST", f"/api/permits/{pidB}/decision", F, {"approved":True}, expect=200)
# 监护人不在岗
c, b = call("POST", f"/api/permits/{pidB}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":False,"smokeProtected":True}, expect=409)
check("监护人离岗复核失败", "监护人" in b["error"], b)
# 五项齐备，且窗口在营业时段外 → 通过（第 2 轮）
c, b = call("POST", f"/api/permits/{pidB}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=200)
check("复核通过（第2轮）解除夜间许可", "现场复核通过" in b["message"], b)
pv, d = permit_view(pidB)
check("营业时段服务端判定 hoursOk=true", pv["siteLogs"][-1]["hoursOk"] is True, pv["siteLogs"])
check("夜间许可恢复", d["summary"]["nightWorkBlocked"] is False, d["summary"])
# 复核通过后被暂停前，门岗可放人
call("POST", f"/api/workers/{w2}/admit", S, expect=200)
# 开始动火（START 回写装修单）
call("POST", f"/api/permits/{pidB}/start", M, expect=200)
# 作业中烟感异常 → 自动暂停
c, b = call("POST", f"/api/permits/{pidB}/abnormal", S, {"type":"SMOKE_ALARM","detail":"防护罩脱落烟感报警"}, expect=200)
check("烟感异常自动暂停", "自动暂停" in b["message"], b)
pv, d = permit_view(pidB)
check("作业票状态 PAUSED 且记录原因", pv["status"]=="PAUSED" and pv["pausedReason"]=="SMOKE_ALARM", pv)
check("自动暂停回写装修单且当晚许可暂停", d["summary"]["nightWorkBlocked"] is True and
      any(l["action"]=="ABNORMAL_PAUSE" for l in pv["siteLogs"]), d["summary"])
# 安保收到复核通知
notes = call("GET","/api/notifications", S, expect=200)[1]
check("暂停通知安保", any("自动暂停" in n["message"] and "复核" in n["message"] for n in notes), notes[:3])
# 非法异常类型
call("POST", f"/api/permits/{pidB}/abnormal", S, {"type":"OTHER"}, expect=400)
# 未重新复核直接恢复 → 拒绝（不得沿用原审批）
c, b = call("POST", f"/api/permits/{pidB}/start", M, expect=409)
check("暂停后未重新复核禁止恢复", "重新现场复核" in b["error"], b)
# 重新现场复核（第 3 轮）
c, b = call("POST", f"/api/permits/{pidB}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True,"note":"烟感保护重新加固"}, expect=200)
check("恢复需重新复核且明确不沿用原审批", "重新复核" in b["message"], b)
pv, _ = permit_view(pidB)
check("复核轮次递增到3", pv["siteReviewRound"]==3 and len([l for l in pv["siteLogs"] if l["action"]=="SITE_CHECK_PASS"])==2, pv["siteReviewRound"])
# 恢复动火 → 完工
call("POST", f"/api/permits/{pidB}/start", M, expect=200)
pv, _ = permit_view(pidB)
check("恢复动作回写 RESUME_RECHECK_PASS", any(l["action"]=="RESUME_RECHECK_PASS" for l in pv["siteLogs"]), pv["siteLogs"])
call("POST", f"/api/permits/{pidB}/finish", M, expect=200)
pv, d = permit_view(pidB)
check("作业完工 FINISH 回写", pv["status"]=="FINISHED" and any(l["action"]=="FINISH" for l in pv["siteLogs"]), pv)
check("安全结束后当晚许可恢复", d["summary"]["nightWorkBlocked"] is False, d["summary"])

# ========== 场景 C：监护人离岗同样自动暂停 ==========
pidC = apply_permit("CUTTING","2026-09-24T23:00:00+08:00","2026-09-25T01:00:00+08:00")
call("POST", f"/api/permits/{pidC}/decision", F, {"approved":True}, expect=200)
call("POST", f"/api/permits/{pidC}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=200)
call("POST", f"/api/permits/{pidC}/start", M, expect=200)
call("POST", f"/api/permits/{pidC}/abnormal", F, {"type":"WATCHER_LEAVE","detail":"看火人接电话离开"}, expect=200)
pv, _ = permit_view(pidC)
check("切割作业监护人离岗自动暂停", pv["status"]=="PAUSED" and pv["pausedReason"]=="WATCHER_LEAVE", pv)

# ========== 场景 D：非动火作业（喷漆）不需要现场复核，沿用原流程 ==========
pidD = apply_permit("PAINTING","2026-09-25T22:30:00+08:00","2026-09-25T23:30:00+08:00")
call("POST", f"/api/permits/{pidD}/decision", F, {"approved":True}, expect=200)
c, b = call("POST", f"/api/permits/{pidD}/site-review", S,
     {"permitPresent":True,"extinguisherOk":True,"watcherPresent":True,"smokeProtected":True}, expect=409)
check("喷漆作业不走动火现场复核", "仅动火/切割" in b["error"], b)
call("POST", f"/api/permits/{pidD}/start", M, expect=200)

# 装修单事件流包含完整追溯
d = call("GET", f"/api/orders/{oid}", M)[1]
etypes = [e["eventType"] for e in d["events"]]
for t in ["HOTWORK_SITE_REVIEW_FAIL","HOTWORK_SITE_REVIEW_PASS","HOTWORK_AUTO_PAUSED",
          "PERMIT_RESUMED","NIGHT_WORK_RESTORED","PERMIT_STARTED","PERMIT_FINISHED"]:
    check(f"事件流含 {t}", t in etypes, etypes)

print()
if fails:
    print(f"{len(fails)} FAILURES:"); [print(" -", x) for x in fails]; sys.exit(1)
print("ALL HOT-WORK SITE-REVIEW CHECKS PASSED")
