-- 自由采集器数据库建表脚本（MySQL 8）
-- 所有表统一 InnoDB + utf8mb4，下划线命名。业务表带 deleted 逻辑删除字段。
-- 敏感字段（Cookie、代理、打码密钥）以 *_encrypted 命名，AES 加密后存储。
-- 运行方式：mysql -u root -p < db/schema.sql

CREATE DATABASE IF NOT EXISTS free_collector
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE free_collector;

-- 采集任务表
CREATE TABLE IF NOT EXISTS crawl_task (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_name             VARCHAR(128)  NOT NULL COMMENT '任务名称',
    target_url            VARCHAR(2048) NOT NULL COMMENT '目标起始网址',
    status                VARCHAR(32)   NOT NULL DEFAULT 'DRAFT' COMMENT '任务状态：DRAFT草稿 / READY待运行 / RUNNING运行中 / PAUSED已暂停 / STOPPED已终止 / FINISHED已完成 / FAILED失败',

    speed_level           VARCHAR(16)   NOT NULL DEFAULT 'SLOW' COMMENT '访问速度：SLOW慢(默认，低并发长间隔) / NORMAL普通 / FAST快',
    simulate_human        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '模拟真人浏览开关：0关 1开（随机停顿、滚动页面）',
    rotate_ua             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '自动轮换UA开关：0关 1开',
    proxy_url_encrypted   TEXT          NULL COMMENT '代理地址（可含账号密码），AES加密存储，禁止明文',

    advanced_config       JSON          NULL COMMENT '高阶参数JSON快照：并发数/超时/重试/自定义请求头/自定义JS注入/自定义等待逻辑等',
    cookie_encrypted      TEXT          NULL COMMENT '自定义Cookie，AES加密存储，禁止明文',
    captcha_config_id     BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '绑定的第三方打码方案ID，关联 captcha_config.id',

    checkpoint_cursor     VARCHAR(1024) NULL DEFAULT NULL COMMENT '断点游标：上次采集到的位置标识（页码/列表偏移等），重启后从此继续',
    total_count           INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计应采集条数（可预估）',
    success_count         INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '成功条数',
    fail_count            INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '失败条数',

    remark                VARCHAR(512)  NULL COMMENT '任务备注',
    create_time           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted               TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除（MyBatis-Plus @TableLogic）',
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT '采集任务主表';

-- 采集字段规则表：可视化框选操作解析后的元素定位规则
-- 高阶模式允许直接查看/编辑 locator_expression 底层表达式
CREATE TABLE IF NOT EXISTS crawl_field (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id             BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID，关联 crawl_task.id',
    field_name          VARCHAR(64)   NOT NULL COMMENT '采集字段名称（导出时的列名）',
    field_type          VARCHAR(32)   NOT NULL DEFAULT 'TEXT' COMMENT '字段类型：TEXT文本 / IMAGE图片 / LINK超链接 / LIST列表 / PAGINATION分页按钮 / CUSTOM自定义',
    locator_type        VARCHAR(16)   NOT NULL DEFAULT 'AUTO' COMMENT '定位方式：AUTO自动识别 / CSS / XPATH / TEXT文本匹配',
    locator_expression  VARCHAR(2048) NOT NULL COMMENT '元素定位表达式（CSS选择器或XPath），规则解析层生成',
    extract_attr        VARCHAR(64)   NOT NULL DEFAULT 'text' COMMENT '提取目标：text文本 / href / src / 其他自定义属性名',
    sample_value        VARCHAR(512)  NULL COMMENT '框选时的示例值，用于配置面板预览确认',
    sort_order          INT           NOT NULL DEFAULT 0 COMMENT '字段顺序，控制导出列顺序',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB COMMENT '采集字段规则表';

-- 采集结果表：统一暂存采集数据，导出模块从此表读取
-- （写入本地MySQL自定义表的功能在导出模块实现，与此表解耦）
CREATE TABLE IF NOT EXISTS crawl_result (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id      BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID，关联 crawl_task.id',
    batch_no     VARCHAR(64)   NOT NULL COMMENT '执行批次号：每次任务运行生成一个批次，区分多次运行数据',
    page_url     VARCHAR(2048) NULL COMMENT '该条数据来源页面URL，便于回溯核对',
    data_json    JSON          NOT NULL COMMENT '采集结果JSON：key为字段名，value为采集值',
    dedup_hash   CHAR(64)      NOT NULL COMMENT '数据去重指纹：data_json规范化后的SHA-256，数据清洗去重使用',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id),
    KEY idx_batch_no (batch_no),
    KEY idx_dedup_hash (dedup_hash)
) ENGINE = InnoDB COMMENT '采集结果暂存表';

-- 任务模板表：任务快照持久化，支持导入导出任务模板
CREATE TABLE IF NOT EXISTS task_template (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    template_name  VARCHAR(128)  NOT NULL COMMENT '模板名称',
    source_task_id BIGINT UNSIGNED NULL COMMENT '来源任务ID（从哪个任务保存为模板），仅记录不强制关联',
    template_json  JSON          NOT NULL COMMENT '模板完整快照：任务配置 + 字段规则 + 清洗规则，导出导入直接序列化此字段',
    remark         VARCHAR(512)  NULL COMMENT '模板备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '任务模板表';

-- 第三方打码方案表（策略模式的核心数据）
-- 软件不内置识别模型，只保存外部打码平台的HTTP转发配置；
-- 一条记录 = 一套打码策略，任务运行时按 captcha_config_id 选择切换
CREATE TABLE IF NOT EXISTS captcha_config (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    config_name         VARCHAR(128)  NOT NULL COMMENT '打码方案名称（用户自定义，便于切换识别）',
    api_url             VARCHAR(1024) NOT NULL COMMENT '第三方打码平台接口地址',
    http_method         VARCHAR(8)    NOT NULL DEFAULT 'POST' COMMENT '请求方法：GET / POST',
    request_headers     JSON          NULL COMMENT '自定义请求头JSON（非敏感部分）',
    param_template      TEXT          NOT NULL COMMENT '请求参数模板：支持占位符 {{image_base64}} / {{image_url}}，转发时替换',
    upload_mode         VARCHAR(16)   NOT NULL DEFAULT 'BASE64' COMMENT '图片上传方式：BASE64图片base64上传 / IMAGE_URL图片url上传',
    result_jsonpath     VARCHAR(256)  NOT NULL COMMENT '识别结果提取JSONPath，从平台响应中取出验证码文本',
    poll_interval_ms    INT UNSIGNED  NOT NULL DEFAULT 3000 COMMENT '轮询间隔毫秒（打码平台异步返回时轮询结果）',
    timeout_ms          INT UNSIGNED  NOT NULL DEFAULT 30000 COMMENT '打码请求独立超时毫秒，防止卡死爬虫主任务',
    retry_times         INT UNSIGNED  NOT NULL DEFAULT 3 COMMENT '打码接口重试次数',
    api_key_encrypted   TEXT          NULL COMMENT '打码平台密钥/Token，AES加密存储，禁止明文',
    enabled             TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用：0停用 1启用',
    remark              VARCHAR(512)  NULL COMMENT '方案备注',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '第三方打码方案配置表';

-- 数据清洗规则表
-- 普通模式：去重/剔除空值/文本截取/文本替换
-- 高阶模式：额外开放 REGEX 正则类型
CREATE TABLE IF NOT EXISTS clean_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id     BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID，关联 crawl_task.id',
    field_name  VARCHAR(64)   NULL COMMENT '作用的目标字段名；NULL表示作用于整条记录（如整体去重）',
    rule_type   VARCHAR(32)   NOT NULL COMMENT '规则类型：DEDUP去重 / REMOVE_EMPTY剔除空值 / SUBSTRING文本截取 / REPLACE文本替换 / REGEX正则（仅高阶）',
    rule_param  VARCHAR(1024) NULL COMMENT '规则参数：截取起止/替换源串与目标串/正则表达式等，按rule_type解析',
    sort_order  INT           NOT NULL DEFAULT 0 COMMENT '执行顺序，清洗规则按序串行执行',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB COMMENT '数据清洗规则表';

-- 采集失败日志表：任务监控面板展示失败信息，重启后仍可追溯
-- （原始请求日志、打码调用日志走文件日志，不入库，见后端日志模块）
CREATE TABLE IF NOT EXISTS crawl_fail_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id     BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID，关联 crawl_task.id',
    batch_no    VARCHAR(64)   NULL COMMENT '执行批次号',
    page_url    VARCHAR(2048) NULL COMMENT '失败页面URL',
    stage       VARCHAR(32)   NOT NULL COMMENT '失败阶段：LOAD页面加载 / EXTRACT元素提取 / CAPTCHA打码调用 / EXPORT数据导出',
    error_msg   TEXT          NULL COMMENT '失败信息摘要',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB COMMENT '采集失败日志表';

-- 全局系统配置表：键值对存储
-- encrypted=1 的值表示 AES 密文（如全局代理密码等）
CREATE TABLE IF NOT EXISTS sys_config (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    config_key   VARCHAR(64)   NOT NULL COMMENT '配置键',
    config_value TEXT          NULL COMMENT '配置值（encrypted=1时为AES密文Base64）',
    encrypted    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否加密存储：0明文 1密文',
    remark       VARCHAR(256)  NULL COMMENT '配置说明',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB COMMENT '全局系统配置表';

-- 写入系统默认配置：高阶能力默认全部关闭/隐藏，需用户手动开启
INSERT INTO sys_config (config_key, config_value, encrypted, remark) VALUES
('advanced_mode_enabled',   '0',    0, '高阶模式总开关：0关闭(默认) 1开启，开启后才解锁底层高级能力'),
('debug_log_enabled',       '0',    0, '开发者调试日志开关：0关闭(默认) 1开启'),
('risk_robots_check',       '1',    0, 'robots.txt校验开关：1开启(默认) 0关闭，测试用户可手动关闭'),
('risk_rate_limit',         '1',    0, '访问限速开关：1开启(默认) 0关闭'),
('risk_disclaimer_shown',   '0',    0, '免责弹窗是否已展示确认：0未确认(默认) 1已确认'),
('first_launch_guide_done', '0',    0, '新手引导向导是否已完成：0未完成(默认，首次启动弹引导) 1已完成'),
('aes_key_hint',            '',     0, 'AES密钥派生盐值占位（后端加密模块初始化时写入，密钥本体不入库）');
