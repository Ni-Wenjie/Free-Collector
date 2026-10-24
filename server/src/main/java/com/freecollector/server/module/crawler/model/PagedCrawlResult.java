package com.freecollector.server.module.crawler.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多页采集执行结果
 */
@Data
public class PagedCrawlResult {

    /** 实际访问的页数（含断点前跳过的页） */
    private int pagesVisited;

    /** 实际写入数据的页数 */
    private int pagesSaved;

    /** 是否自然结束（没有下一页或达到最大页数） */
    private boolean finished;

    /** 最后一页页码 */
    private int lastPage;

    /** 最后一页URL */
    private String lastPageUrl;

    /** 告警信息 */
    private List<String> warnings = new ArrayList<>();
}
