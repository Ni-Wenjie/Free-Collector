package com.freecollector.server.module.task.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务运行时状态（Redis 中的实时快照，配合数据库状态使用）
 */
@Data
public class TaskRuntimeVO {

    private Long taskId;

    /** 运行状态：QUEUED / RUNNING / PAUSING / PAUSED / STOPPING / STOPPED / FINISHED / FAILED */
    private String status;

    /** 当前处理页码 */
    private int currentPage;

    /** 最大页数 */
    private int maxPages;

    /** 本次运行已成功写入行数 */
    private int successCount;

    /** 本次运行失败行数 */
    private int failCount;

    /** 本次执行批次号 */
    private String batchNo;

    /** 状态描述（面向用户） */
    private String message;

    private LocalDateTime updateTime;
}
