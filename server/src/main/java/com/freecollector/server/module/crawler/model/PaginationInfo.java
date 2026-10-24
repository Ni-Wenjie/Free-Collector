package com.freecollector.server.module.crawler.model;

import lombok.Data;

/**
 * 分页识别结果
 */
@Data
public class PaginationInfo {

    /** 是否识别到下一页入口 */
    private boolean found;

    /** 命中的元素定位表达式（供规则持久化） */
    private String selector;

    /** 按钮/链接文本，如"下一页" */
    private String text;

    /** 目标链接（若有） */
    private String href;

    /** 是否可继续翻页（按钮未禁用） */
    private boolean hasNext;

    public static PaginationInfo none() {
        PaginationInfo info = new PaginationInfo();
        info.setFound(false);
        return info;
    }
}
