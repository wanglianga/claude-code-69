-- 商场店铺装修进场审批与消防验收服务 · 数据库结构
-- 应用启动时执行（幂等：IF NOT EXISTS）

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) UNIQUE NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL,           -- MERCHANT/PROPERTY/ENGINEERING/SECURITY/FIRE/FINANCE/FLOOR_OPS/ADMIN
    password_hash   VARCHAR(256) NOT NULL,
    password_salt   VARCHAR(128) NOT NULL,
    company         VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS shops (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(32) UNIQUE NOT NULL,  -- 铺位号，如 1F-108
    name                VARCHAR(128) NOT NULL,
    floor               INT NOT NULL,
    category            VARCHAR(32) NOT NULL,         -- 业态：FASHION/RESTAURANT/BEAUTY/KIDS/ENTERTAINMENT/RETAIL
    adjacent_shop_codes VARCHAR(256) NOT NULL DEFAULT '',
    operating_start     INT NOT NULL DEFAULT 10,      -- 商场营业开始（时）
    operating_end       INT NOT NULL DEFAULT 22       -- 商场营业结束（时）
);

CREATE TABLE IF NOT EXISTS renovation_orders (
    id                    BIGSERIAL PRIMARY KEY,
    order_no              VARCHAR(40) UNIQUE NOT NULL,
    shop_id               BIGINT NOT NULL REFERENCES shops(id),
    merchant_user_id      BIGINT NOT NULL REFERENCES users(id),
    scenario              VARCHAR(24) NOT NULL,       -- CLOSED_RENOVATION/NEW_OPEN/PARTIAL_REPAIR/FLASH_POPUP_WITHDRAW
    status                VARCHAR(32) NOT NULL,       -- DRAFT/PENDING_REVIEW/APPROVED/REJECTED/UNDER_CONSTRUCTION/COMPLETED_PENDING_ACCEPTANCE/RECTIFICATION/ACCEPTED/LICENSED/OPENED/CANCELLED
    drawing_doc           VARCHAR(256) NOT NULL,
    construction_start    TIMESTAMP NOT NULL,
    construction_end      TIMESTAMP NOT NULL,
    construction_company  VARCHAR(128) NOT NULL,
    hot_work_required     BOOLEAN NOT NULL DEFAULT FALSE,
    night_work_required   BOOLEAN NOT NULL DEFAULT FALSE,
    enclosure_plan        TEXT NOT NULL,
    deposit_amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    deposit_paid          BOOLEAN NOT NULL DEFAULT FALSE,
    fire_reinspection_passed BOOLEAN NOT NULL DEFAULT FALSE,
    opening_allowed       BOOLEAN NOT NULL DEFAULT FALSE,
    fire_permit_passed    BOOLEAN NOT NULL DEFAULT FALSE,  -- 开业前消防许可（强电/排烟/喷淋等关键项全过）
    total_penalty         NUMERIC(12,2) NOT NULL DEFAULT 0,
    night_work_blocked    BOOLEAN NOT NULL DEFAULT FALSE,   -- 动火复核/异常联动：当晚施工许可是否被暂停
    night_block_reason    VARCHAR(256) NOT NULL DEFAULT '',
    deposit_refunded      BOOLEAN NOT NULL DEFAULT FALSE,   -- 押金是否已退还（复验通过前冻结）
    deposit_refund_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    deposit_refunded_at   TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now()
);

-- 审批任务：多部门在同一装修单内协同
CREATE TABLE IF NOT EXISTS approval_tasks (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL REFERENCES renovation_orders(id),
    dept         VARCHAR(24) NOT NULL,   -- PROPERTY/ENGINEERING/FIRE/SECURITY/FINANCE/FLOOR_OPS
    title        VARCHAR(160) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
    reviewer_id  BIGINT REFERENCES users(id),
    comment      VARCHAR(512),
    seq          INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    reviewed_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS workers (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES renovation_orders(id),
    name          VARCHAR(64) NOT NULL,
    id_card       VARCHAR(32) NOT NULL,
    id_card_ok    BOOLEAN NOT NULL DEFAULT FALSE,  -- 身份证核验
    badge_ok      BOOLEAN NOT NULL DEFAULT FALSE,  -- 工牌
    insurance_ok  BOOLEAN NOT NULL DEFAULT FALSE,  -- 保险
    tools_ok      BOOLEAN NOT NULL DEFAULT FALSE,  -- 工具登记/安检
    materials_ok  BOOLEAN NOT NULL DEFAULT FALSE,  -- 随身材料核验
    admitted      BOOLEAN NOT NULL DEFAULT FALSE,
    admit_time    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS material_items (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL REFERENCES renovation_orders(id),
    name           VARCHAR(128) NOT NULL,
    qty            VARCHAR(40) NOT NULL,
    declared_flame_retardant BOOLEAN NOT NULL DEFAULT FALSE, -- 申报阻燃
    flame_retardant_verified BOOLEAN NOT NULL DEFAULT FALSE, -- 进场抽检核验
    entry_status   VARCHAR(16) NOT NULL DEFAULT 'DECLARED',  -- DECLARED/ALLOWED/REJECTED
    gate_remark    VARCHAR(256)
);

-- 专项作业票：动火/切割/喷漆/高空作业必须单独申请
CREATE TABLE IF NOT EXISTS special_work_permits (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL REFERENCES renovation_orders(id),
    work_type      VARCHAR(16) NOT NULL,  -- HOT_WORK/CUTTING/PAINTING/HIGH_ALTITUDE
    reason         VARCHAR(256) NOT NULL,
    planned_start  TIMESTAMP NOT NULL,
    planned_end    TIMESTAMP NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'APPLIED',  -- APPLIED/APPROVED/REJECTED/IN_PROGRESS/FINISHED/PAUSED/CANCELLED
    fire_watcher   VARCHAR(64),
    extinguisher_count INT NOT NULL DEFAULT 0,
    approver_id    BIGINT REFERENCES users(id),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    decided_at     TIMESTAMP,
    -- 动火/切割现场（安保）复核
    site_review_status   VARCHAR(12) NOT NULL DEFAULT 'NONE', -- NONE/PASSED/FAILED
    site_review_round    INT NOT NULL DEFAULT 0,             -- 暂停后恢复必须 +1 重新复核，不能沿用原审批
    site_reviewer_id     BIGINT REFERENCES users(id),
    site_reviewed_at     TIMESTAMP,
    paused_reason        VARCHAR(48)                          -- SMOKE_ALARM/WATCHER_LEAVE/SITE_CHECK_FAILED
);

-- 动火现场复核与作业过程记录：复核项、开始、结束、异常暂停、恢复复核全部回写装修单
CREATE TABLE IF NOT EXISTS permit_site_logs (
    id               BIGSERIAL PRIMARY KEY,
    permit_id        BIGINT NOT NULL REFERENCES special_work_permits(id),
    order_id         BIGINT NOT NULL REFERENCES renovation_orders(id),
    review_round     INT NOT NULL DEFAULT 0,
    action           VARCHAR(24) NOT NULL,  -- SITE_CHECK_PASS/SITE_CHECK_FAIL/START/FINISH/ABNORMAL_PAUSE/RESUME_RECHECK_PASS/CANCEL
    permit_present   BOOLEAN NOT NULL DEFAULT FALSE,  -- 动火证在场
    extinguisher_ok  BOOLEAN NOT NULL DEFAULT FALSE,  -- 灭火器就位
    watcher_present  BOOLEAN NOT NULL DEFAULT FALSE,  -- 监护人在岗
    smoke_protected  BOOLEAN NOT NULL DEFAULT FALSE,  -- 烟感保护到位
    hours_ok         BOOLEAN NOT NULL DEFAULT FALSE,  -- 作业时段避开商场营业时段（服务端判定）
    detail           VARCHAR(512) NOT NULL DEFAULT '',
    recorded_by      BIGINT REFERENCES users(id),
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- 施工过程事件：超时/噪声/堆占通道/烟感遮挡/喷淋改动/顾客投诉/临时改图
CREATE TABLE IF NOT EXISTS incidents (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL REFERENCES renovation_orders(id),
    type         VARCHAR(28) NOT NULL,  -- OVERTIME/NOISE/CHANNEL_BLOCKAGE/SMOKE_COVERED/SPRINKLER_MODIFICATION/CUSTOMER_COMPLAINT/TEMP_DRAWING_CHANGE
    level        VARCHAR(8)  NOT NULL,  -- WARN/BREACH
    description  VARCHAR(512) NOT NULL,
    reported_by  BIGINT REFERENCES users(id),
    penalty      NUMERIC(12,2) NOT NULL DEFAULT 0,
    rectify_deadline TIMESTAMP,
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN/HANDLING/RESOLVED
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS penalties (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES renovation_orders(id),
    incident_id BIGINT REFERENCES incidents(id),
    amount      NUMERIC(12,2) NOT NULL,
    reason      VARCHAR(256) NOT NULL,
    deducted    BOOLEAN NOT NULL DEFAULT FALSE,
    rectification_id BIGINT, -- 扣罚依据：复验不通过的整改项（应用层关联，避免与整改表循环外键）
    created_by  BIGINT REFERENCES users(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 完工逐项验收：消防/强电/弱电/排烟/排水/门头/公共区域恢复
CREATE TABLE IF NOT EXISTS acceptance_check_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL REFERENCES renovation_orders(id),
    category     VARCHAR(24) NOT NULL, -- FIRE/STRONG_ELECTRIC/WEAK_ELECTRIC/SMOKE_EXHAUST/DRAINAGE/STOREFRONT/PUBLIC_RESTORE
    inspector_id BIGINT REFERENCES users(id),
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/PASSED/FAILED
    remark       VARCHAR(512),
    checked_at   TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rectifications (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL REFERENCES renovation_orders(id),
    item_id        BIGINT REFERENCES acceptance_check_items(id),
    description    VARCHAR(512) NOT NULL,
    deadline       TIMESTAMP NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- OPEN/RESUBMITTED/REINSPECT_READY/PASSED/OVERDUE
    submitted_note VARCHAR(512),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMP,
    -- 消防验收不通过专项整改
    violation_type       VARCHAR(32) NOT NULL DEFAULT '',  -- SPRINKLER_OCCLUDED 喷淋遮挡 / EXIT_SIGN_ERROR 疏散指示错误 / OTHER
    responsible_company  VARCHAR(128) NOT NULL DEFAULT '', -- 责任施工方/施工队
    drawing_ref          VARCHAR(256) NOT NULL DEFAULT '', -- 关联的具体图纸（问题图纸）
    reinspect_at         TIMESTAMP,                        -- 计划/安排的复验时间
    updated_drawing      VARCHAR(256) NOT NULL DEFAULT '', -- 整改后更新的图纸
    fire_confirmed       BOOLEAN NOT NULL DEFAULT FALSE,   -- 消防维保确认更新图纸
    fire_confirmed_by    BIGINT REFERENCES users(id),
    fire_confirmed_at    TIMESTAMP,
    eng_confirmed        BOOLEAN NOT NULL DEFAULT FALSE,   -- 工程部确认更新图纸
    eng_confirmed_by     BIGINT REFERENCES users(id),
    eng_confirmed_at     TIMESTAMP,
    reinspect_round      INT NOT NULL DEFAULT 0,           -- 复验次数（每轮失败 +1）
    reinspect_result     VARCHAR(16) NOT NULL DEFAULT 'NONE', -- NONE/PASSED/FAILED
    penalty_id           BIGINT                              -- 复验不通过生成的扣罚依据（应用层关联）
);

CREATE TABLE IF NOT EXISTS order_events (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES renovation_orders(id),
    event_type  VARCHAR(48) NOT NULL,
    detail      VARCHAR(512),
    actor_id    BIGINT REFERENCES users(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 审批结果同步给楼层运营及各相关方
CREATE TABLE IF NOT EXISTS notifications (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT REFERENCES renovation_orders(id),
    target_role VARCHAR(24) NOT NULL,
    message     VARCHAR(512) NOT NULL,
    read_flag   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tasks_order   ON approval_tasks(order_id);
CREATE INDEX IF NOT EXISTS idx_workers_order ON workers(order_id);
CREATE INDEX IF NOT EXISTS idx_materials_order ON material_items(order_id);
CREATE INDEX IF NOT EXISTS idx_permits_order ON special_work_permits(order_id);
CREATE INDEX IF NOT EXISTS idx_incidents_order ON incidents(order_id);
CREATE INDEX IF NOT EXISTS idx_items_order   ON acceptance_check_items(order_id);
CREATE INDEX IF NOT EXISTS idx_rect_order    ON rectifications(order_id);
CREATE INDEX IF NOT EXISTS idx_events_order  ON order_events(order_id);
CREATE INDEX IF NOT EXISTS idx_permit_logs   ON permit_site_logs(permit_id);

-- 兼容已存在的旧库：幂等补齐动火现场复核字段
ALTER TABLE renovation_orders ADD COLUMN IF NOT EXISTS night_work_blocked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE renovation_orders ADD COLUMN IF NOT EXISTS night_block_reason VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE special_work_permits ADD COLUMN IF NOT EXISTS site_review_status VARCHAR(12) NOT NULL DEFAULT 'NONE';
ALTER TABLE special_work_permits ADD COLUMN IF NOT EXISTS site_review_round INT NOT NULL DEFAULT 0;
ALTER TABLE special_work_permits ADD COLUMN IF NOT EXISTS site_reviewer_id BIGINT REFERENCES users(id);
ALTER TABLE special_work_permits ADD COLUMN IF NOT EXISTS site_reviewed_at TIMESTAMP;
ALTER TABLE special_work_permits ADD COLUMN IF NOT EXISTS paused_reason VARCHAR(48);

-- 消防验收不通过专项整改
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS violation_type VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS responsible_company VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS drawing_ref VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS reinspect_at TIMESTAMP;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS updated_drawing VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS fire_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS fire_confirmed_by BIGINT;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS fire_confirmed_at TIMESTAMP;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS eng_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS eng_confirmed_by BIGINT;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS eng_confirmed_at TIMESTAMP;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS reinspect_round INT NOT NULL DEFAULT 0;
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS reinspect_result VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE rectifications ADD COLUMN IF NOT EXISTS penalty_id BIGINT;
ALTER TABLE penalties ADD COLUMN IF NOT EXISTS rectification_id BIGINT;
ALTER TABLE renovation_orders ADD COLUMN IF NOT EXISTS deposit_refunded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE renovation_orders ADD COLUMN IF NOT EXISTS deposit_refund_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE renovation_orders ADD COLUMN IF NOT EXISTS deposit_refunded_at TIMESTAMP;
