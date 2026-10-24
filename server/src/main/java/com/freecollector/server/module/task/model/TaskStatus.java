package com.freecollector.server.module.task.model;

/**
 * 任务状态常量与流转说明
 *
 * DRAFT    草稿：已创建但从未运行
 * READY    待运行：已入队，等待工作线程领取
 * RUNNING  运行中
 * PAUSED   已暂停：当前页采集完成后停下，断点保留，可继续
 * STOPPED  已终止：用户手动终止
 * FINISHED 已完成：没有下一页或达到最大页数
 * FAILED   失败：连续重试后仍失败
 */
public final class TaskStatus {

    public static final String DRAFT = "DRAFT";
    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String STOPPED = "STOPPED";
    public static final String FINISHED = "FINISHED";
    public static final String FAILED = "FAILED";

    private TaskStatus() {
    }

    /** 是否允许删除：运行中禁止删除，避免工作线程访问到已删任务 */
    public static boolean canDelete(String status) {
        return !RUNNING.equals(status) && !READY.equals(status);
    }

    /** 是否允许启动（重新开始，从第1页）；READY 已排队，不重复入队 */
    public static boolean canStart(String status) {
        return DRAFT.equals(status) || PAUSED.equals(status)
                || STOPPED.equals(status) || FINISHED.equals(status) || FAILED.equals(status);
    }

    /** 是否允许暂停 */
    public static boolean canPause(String status) {
        return RUNNING.equals(status) || READY.equals(status);
    }

    /** 是否允许继续（断点续爬） */
    public static boolean canResume(String status) {
        return PAUSED.equals(status);
    }

    /** 是否允许终止 */
    public static boolean canStop(String status) {
        return RUNNING.equals(status) || READY.equals(status) || PAUSED.equals(status);
    }
}
