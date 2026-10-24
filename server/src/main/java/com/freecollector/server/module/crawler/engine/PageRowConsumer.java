package com.freecollector.server.module.crawler.engine;

import java.util.List;
import java.util.Map;

/**
 * 多页采集的行消费者回调
 * 由任务模块实现：负责把每一页提取到的行写入数据库、更新进度
 */
@FunctionalInterface
public interface PageRowConsumer {

    /**
     * 消费一页数据
     *
     * @param pageIndex 页码（从1开始）
     * @param pageUrl   该页最终URL
     * @param rows      该页提取到的数据行
     * @return true 继续翻页；false 立即停止（用户暂停/终止）
     */
    boolean accept(int pageIndex, String pageUrl, List<Map<String, String>> rows);
}
