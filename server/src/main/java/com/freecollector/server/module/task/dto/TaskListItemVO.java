package com.freecollector.server.module.task.dto;

import com.freecollector.server.module.task.entity.CrawlTask;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务列表项：列表页只返回展示所需字段
 */
@Data
public class TaskListItemVO {

    private Long id;
    private String taskName;
    private String targetUrl;
    private String status;
    private String speedLevel;
    private int maxPages;
    private Integer successCount;
    private Integer failCount;
    private String checkpointCursor;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String remark;

    public static TaskListItemVO from(CrawlTask task, int maxPages) {
        TaskListItemVO vo = new TaskListItemVO();
        vo.setId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setTargetUrl(task.getTargetUrl());
        vo.setStatus(task.getStatus());
        vo.setSpeedLevel(task.getSpeedLevel());
        vo.setMaxPages(maxPages);
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailCount(task.getFailCount());
        vo.setCheckpointCursor(task.getCheckpointCursor());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        vo.setRemark(task.getRemark());
        return vo;
    }
}
