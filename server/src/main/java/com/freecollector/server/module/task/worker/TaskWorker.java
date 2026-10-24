package com.freecollector.server.module.task.worker;

import com.freecollector.server.module.task.config.TaskProperties;
import com.freecollector.server.module.task.service.TaskExecutionService;
import com.freecollector.server.module.task.service.TaskQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务工作线程池：从 Redis List 阻塞领取任务并串行执行
 *
 * 线程数即"同时运行的浏览器会话数"，默认2，避免单机资源被打满。
 * 使用 Redis 阻塞出队，空闲时不空转 CPU。关闭时置停止标记并中断线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWorker {

    private final TaskQueueService queueService;
    private final TaskExecutionService executionService;
    private final TaskProperties taskProperties;

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = false;

    @PostConstruct
    public void start() {
        running = true;
        int count = Math.max(1, taskProperties.getWorkerThreads());
        for (int i = 1; i <= count; i++) {
            Thread thread = new Thread(this::loop, "fc-task-worker-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        log.info("任务工作线程已启动，线程数={}", count);
    }

    /**
     * 工作循环：阻塞出队 → 执行 → 继续
     */
    private void loop() {
        while (running) {
            try {
                Long taskId = queueService.poll(taskProperties.getPopTimeoutSeconds());
                if (taskId != null && running) {
                    log.info("工作线程领取任务 thread={} taskId={}",
                            Thread.currentThread().getName(), taskId);
                    executionService.execute(taskId);
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                // Redis 临时不可用时短暂退避，避免日志刷屏
                log.warn("任务工作线程异常，1秒后重试: {}", e.getMessage());
                sleepOneSecond();
            }
        }
        log.info("工作线程退出 thread={}", Thread.currentThread().getName());
    }

    @PreDestroy
    public void stop() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        log.info("任务工作线程已停止");
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
