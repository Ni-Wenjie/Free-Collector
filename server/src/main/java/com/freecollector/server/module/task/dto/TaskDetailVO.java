package com.freecollector.server.module.task.dto;

import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.task.entity.CrawlTask;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务详情视图：返回给前端，敏感配置只返回是否已配置
 */
@Data
public class TaskDetailVO {

    private Long id;
    private String taskName;
    private String targetUrl;
    private String status;
    private String speedLevel;
    private boolean simulateHuman;
    private boolean rotateUa;

    /** 是否配置了代理（不返回密文） */
    private boolean proxyConfigured;

    /** 是否配置了Cookie（不返回密文） */
    private boolean cookieConfigured;

    /** 最大翻页数 */
    private int maxPages;

    /** 打码方案ID（null 表示未绑定） */
    private Long captchaConfigId;

    /** 高阶配置（advanced_config JSON 解析后的键值对，不含 maxPages） */
    private java.util.Map<String, Object> advancedConfig;

    private String checkpointCursor;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 字段规则 */
    private List<ExtractRule> fields;

    /** 从实体转换（不含敏感字段） */
    public static TaskDetailVO from(CrawlTask task) {
        TaskDetailVO vo = new TaskDetailVO();
        vo.setId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setTargetUrl(task.getTargetUrl());
        vo.setStatus(task.getStatus());
        vo.setSpeedLevel(task.getSpeedLevel());
        vo.setSimulateHuman(task.getSimulateHuman() != null && task.getSimulateHuman() == 1);
        vo.setRotateUa(task.getRotateUa() != null && task.getRotateUa() == 1);
        vo.setProxyConfigured(task.getProxyUrlEncrypted() != null && !task.getProxyUrlEncrypted().isBlank());
        vo.setCookieConfigured(task.getCookieEncrypted() != null && !task.getCookieEncrypted().isBlank());
        vo.setCaptchaConfigId(task.getCaptchaConfigId());
        vo.setCheckpointCursor(task.getCheckpointCursor());
        vo.setTotalCount(task.getTotalCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailCount(task.getFailCount());
        vo.setRemark(task.getRemark());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        return vo;
    }
}
