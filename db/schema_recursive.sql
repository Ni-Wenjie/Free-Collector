-- 递归爬取 & 媒体下载 模块数据库扩展脚本
-- 增量迁移，在已执行 schema.sql 的基础上追加字段和表。
-- 运行方式：mysql -u root -p free_collector < db/schema_recursive.sql

USE free_collector;

-- 扩展 crawl_task 表：新增递归爬取配置字段
ALTER TABLE crawl_task
    ADD COLUMN crawl_mode          VARCHAR(16)  NOT NULL DEFAULT 'FLAT' COMMENT '爬取模式：FLAT扁平翻页(默认) / RECURSIVE递归爬取 / MEDIA_ONLY仅媒体下载' AFTER target_url,
    ADD COLUMN max_depth           INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '递归爬取最大深度（FLAT模式固定1）' AFTER crawl_mode,
    ADD COLUMN max_urls            INT UNSIGNED NOT NULL DEFAULT 1000 COMMENT '递归爬取最多访问URL数上限，防止无限扩散' AFTER max_depth,
    ADD COLUMN url_include_pattern VARCHAR(1024) NULL COMMENT 'URL包含正则（白名单）：仅匹配的URL才会入队，NULL表示不限制' AFTER max_urls,
    ADD COLUMN url_exclude_pattern VARCHAR(1024) NULL COMMENT 'URL排除正则（黑名单）：匹配的URL直接跳过，优先级高于include' AFTER url_include_pattern,
    ADD COLUMN domain_restrict      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否限制同域：1只爬取起始域名下的URL(默认) 0允许跨域' AFTER url_exclude_pattern,
    ADD COLUMN auto_discover       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '自动发现开关：1自动扫描页面图片/视频/文档 0仅提取规则内字段' AFTER domain_restrict,
    ADD COLUMN media_download      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '媒体下载开关：1自动下载发现的媒体文件 0只记录URL' AFTER auto_discover,
    ADD COLUMN media_types         VARCHAR(128) NULL DEFAULT 'image,video,audio,document' COMMENT '要下载的媒体类型列表（逗号分隔）' AFTER media_download;

-- 媒体文件表：记录发现并下载（或仅发现未下载）的媒体资源
CREATE TABLE IF NOT EXISTS crawl_media (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id       BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID，关联 crawl_task.id',
    batch_no      VARCHAR(64)   NULL COMMENT '执行批次号',
    source_url    VARCHAR(2048) NOT NULL COMMENT '媒体来源页面URL（该媒体在哪个页面被发现）',
    media_url     VARCHAR(2048) NOT NULL COMMENT '媒体资源直接URL',
    media_type    VARCHAR(32)   NOT NULL COMMENT '媒体类型：IMAGE图片 / VIDEO视频 / AUDIO音频 / DOCUMENT文档 / OTHER其他',
    file_ext      VARCHAR(16)   NULL COMMENT '文件扩展名（从URL或Content-Type推断）',
    file_size     BIGINT UNSIGNED NULL COMMENT '文件大小（字节），下载完成后回填',
    local_path    VARCHAR(1024) NULL COMMENT '本地存储绝对路径',
    download_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '下载状态：PENDING待下载 / DOWNLOADING下载中 / SUCCESS已下载 / FAILED下载失败 / SKIPPED跳过',
    error_msg     VARCHAR(512)  NULL COMMENT '下载失败时的错误信息',
    dedup_hash    CHAR(64)      NULL COMMENT '媒体URL的SHA-256去重指纹',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id),
    KEY idx_batch_no (batch_no),
    KEY idx_media_type (media_type),
    KEY idx_download_status (download_status),
    UNIQUE KEY uk_task_media_url (task_id, media_url(500))
) ENGINE = InnoDB COMMENT '媒体资源发现与下载记录表';

-- URL队列表：递归爬取的URL frontier，支持断点续爬
CREATE TABLE IF NOT EXISTS crawl_url_queue (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id       BIGINT UNSIGNED NOT NULL COMMENT '所属任务ID',
    url           VARCHAR(2048) NOT NULL COMMENT '待爬取URL',
    parent_url    VARCHAR(2048) NULL COMMENT '来源页面URL（哪个页面发现了这个链接）',
    depth         INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前URL距离起始URL的深度（起始页=0）',
    url_hash      CHAR(64)      NOT NULL COMMENT 'URL标准化后的SHA-256（去重用）',
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待处理 / PROCESSING处理中 / DONE已完成 / SKIPPED已跳过 / FAILED失败',
    priority      INT           NOT NULL DEFAULT 0 COMMENT '优先级（深度小的优先，同深度先入先出）',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态更新时间',
    PRIMARY KEY (id),
    KEY idx_task_status (task_id, status),
    UNIQUE KEY uk_task_url_hash (task_id, url_hash)
) ENGINE = InnoDB COMMENT '递归爬取URL队列（frontier）';
