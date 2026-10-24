-- 建表结果验证脚本
-- 用法：先执行 schema.sql，再执行本脚本：mysql -u root -p < db/test_verify.sql
-- 预期：每个 SELECT 的断言均成立，最后自动清理测试数据

USE free_collector;

-- 用例1：8张表全部创建成功
-- 断言：返回 8 行，包含下方列出的全部表名
SHOW TABLES;

-- 用例2：系统默认配置已写入
-- 断言：advanced_mode_enabled=0（高阶模式默认关闭）、debug_log_enabled=0（调试日志默认关闭）、风控开关默认=1
SELECT config_key, config_value FROM sys_config ORDER BY config_key;

-- 用例3：任务 + 字段规则 关联写入/查询
-- 模拟：新建一个采集任务，并为其添加两个采集字段
INSERT INTO crawl_task (task_name, target_url, status, speed_level, simulate_human, rotate_ua)
VALUES ('测试任务_商品列表', 'https://example.com/list', 'DRAFT', 'SLOW', 1, 1);
SET @task_id = LAST_INSERT_ID();

INSERT INTO crawl_field (task_id, field_name, field_type, locator_type, locator_expression, extract_attr, sort_order)
VALUES
(@task_id, '商品标题', 'TEXT', 'CSS', '.product-item .title', 'text', 1),
(@task_id, '商品链接', 'LINK', 'CSS', '.product-item a', 'href', 2);

-- 断言：能联表查出该任务的2个字段，且字段顺序正确
SELECT t.task_name, f.field_name, f.locator_expression, f.sort_order
FROM crawl_task t JOIN crawl_field f ON f.task_id = t.id
WHERE t.id = @task_id ORDER BY f.sort_order;

-- 用例4：采集结果写入 + 去重指纹索引可用
INSERT INTO crawl_result (task_id, batch_no, page_url, data_json, dedup_hash)
VALUES
(@task_id, 'BATCH-TEST-001', 'https://example.com/list?page=1',
 JSON_OBJECT('商品标题', '测试商品A', '商品链接', 'https://example.com/item/1'),
 'a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3'),
(@task_id, 'BATCH-TEST-001', 'https://example.com/list?page=1',
 JSON_OBJECT('商品标题', '测试商品B', '商品链接', 'https://example.com/item/2'),
 'b3a8e0e1f9ab1bfe3a36f231f676f78bb30a519d2b21e6c530c0eee8ebb4a5d0');

-- 断言：按指纹可命中单条记录（验证清洗去重查询路径）
SELECT id, JSON_UNQUOTE(data_json->'$.商品标题') AS title
FROM crawl_result
WHERE dedup_hash = 'a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3';

-- 用例5：打码方案 + 清洗规则 + 模板 写入
INSERT INTO captcha_config (config_name, api_url, param_template, result_jsonpath)
VALUES ('测试打码方案', 'https://captcha.example.com/api',
        '{"image":"{{image_base64}}","key":"ENCRYPTED_PLACEHOLDER"}', '$.data.result');
SET @captcha_id = LAST_INSERT_ID();

-- 断言：任务可绑定打码方案
UPDATE crawl_task SET captcha_config_id = @captcha_id WHERE id = @task_id;
SELECT t.task_name, c.config_name FROM crawl_task t
JOIN captcha_config c ON c.id = t.captcha_config_id WHERE t.id = @task_id;

INSERT INTO clean_rule (task_id, field_name, rule_type, rule_param, sort_order)
VALUES
(@task_id, NULL, 'DEDUP', NULL, 1),
(@task_id, '商品标题', 'REMOVE_EMPTY', NULL, 2),
(@task_id, '商品标题', 'REPLACE', '包邮->免运费', 3);

INSERT INTO task_template (template_name, source_task_id, template_json)
SELECT CONCAT(t.task_name, '_模板'), t.id,
       JSON_OBJECT('task', JSON_OBJECT('name', t.task_name, 'url', t.target_url),
                   'fields', (SELECT JSON_ARRAYAGG(JSON_OBJECT('name', f.field_name, 'expr', f.locator_expression))
                              FROM crawl_field f WHERE f.task_id = t.id))
FROM crawl_task t WHERE t.id = @task_id;

-- 断言：模板快照包含字段数组
SELECT template_name, JSON_LENGTH(template_json, '$.fields') AS field_count FROM task_template;

-- 用例6：失败日志 + 逻辑删除
INSERT INTO crawl_fail_log (task_id, batch_no, page_url, stage, error_msg)
VALUES (@task_id, 'BATCH-TEST-001', 'https://example.com/list?page=2', 'LOAD', '页面加载超时（测试数据）');

-- 逻辑删除任务后，普通查询应查不到（MyBatis-Plus 自动拼 deleted=0）
UPDATE crawl_task SET deleted = 1 WHERE id = @task_id;
-- 断言：返回 0 行
SELECT * FROM crawl_task WHERE id = @task_id AND deleted = 0;

-- 清理：删除全部测试数据，恢复干净环境
DELETE FROM crawl_fail_log WHERE task_id = @task_id;
DELETE FROM clean_rule      WHERE task_id = @task_id;
DELETE FROM crawl_result    WHERE task_id = @task_id;
DELETE FROM task_template;
DELETE FROM crawl_field     WHERE task_id = @task_id;
DELETE FROM crawl_task      WHERE id = @task_id;
DELETE FROM captcha_config  WHERE id = @captcha_id;

-- 断言：清理后各业务表为空
SELECT
  (SELECT COUNT(*) FROM crawl_task)     AS task_cnt,
  (SELECT COUNT(*) FROM crawl_field)    AS field_cnt,
  (SELECT COUNT(*) FROM crawl_result)   AS result_cnt,
  (SELECT COUNT(*) FROM captcha_config) AS captcha_cnt;
