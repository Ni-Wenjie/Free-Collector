package com.freecollector.server.module.captcha.service;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.captcha.factory.CaptchaStrategyFactory;
import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaSolveRequest;
import com.freecollector.server.module.captcha.model.CaptchaSolveResult;
import com.freecollector.server.module.captcha.strategy.CaptchaSolverStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 打码网关：异步执行 + 独立线程池 + 独立超时
 *
 * 设计目的（代码规范第3条）：
 *  - 打码接口慢/挂掉时不能卡死爬虫主任务；
 *  - 通过独立线程池与队列隔离，队列满时快速失败；
 *  - 调用方拿到 CompletableFuture，可继续处理其他页面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaGatewayService {

    private final CaptchaStrategyFactory strategyFactory;

    @Value("${free-collector.captcha.pool-size:2}")
    private int poolSize;

    @Value("${free-collector.captcha.queue-capacity:20}")
    private int queueCapacity;

    /** 打码专用线程池（与爬虫任务线程完全隔离） */
    private ThreadPoolExecutor executor;

    @PostConstruct
    public void init() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "captcha-worker-" + seq.getAndIncrement());
                t.setDaemon(true); // 不阻止后端退出
                return t;
            }
        };
        // 固定核心/最大线程数 + 有界队列 + 直接拒绝（快速失败，不堆积）
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        log.info("打码线程池初始化完成 poolSize={} queueCapacity={}", poolSize, queueCapacity);
    }

    /**
     * 异步打码：立即返回 Future，不阻塞调用线程
     */
    public CompletableFuture<CaptchaSolveResult> solveAsync(CaptchaConfigModel config,
                                                            CaptchaSolveRequest request) {
        if (config != null && Boolean.FALSE.equals(config.getEnabled())) {
            return CompletableFuture.completedFuture(
                    CaptchaSolveResult.fail("打码方案已停用", 0, 0, null));
        }
        CaptchaSolverStrategy strategy;
        try {
            strategy = strategyFactory.get(config == null ? null : config.getStrategyType());
        } catch (BizException e) {
            return CompletableFuture.completedFuture(
                    CaptchaSolveResult.fail(e.getMessage(), 0, 0, null));
        }

        try {
            return CompletableFuture.supplyAsync(() -> strategy.solve(config, request), executor);
        } catch (RejectedExecutionException e) {
            // 队列已满：快速失败，由爬虫任务决定是否稍后重试
            return CompletableFuture.completedFuture(
                    CaptchaSolveResult.fail("打码队列繁忙，请稍后重试", 0, 0, null));
        }
    }

    /**
     * 同步等待结果（仅供测试接口/需要立即结果的场景）
     * 等待上限 = 打码超时 + 5秒缓冲，避免调用方无限等待
     */
    public CaptchaSolveResult solveBlocking(CaptchaConfigModel config, CaptchaSolveRequest request) {
        int timeoutMs = config == null || config.getTimeoutMs() == null ? 30000 : config.getTimeoutMs();
        CompletableFuture<CaptchaSolveResult> future = solveAsync(config, request);
        try {
            return future.get(timeoutMs + 5000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return CaptchaSolveResult.fail("打码等待超时", 0, timeoutMs + 5000L, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CaptchaSolveResult.fail("打码等待被中断", 0, 0, null);
        } catch (Exception e) {
            return CaptchaSolveResult.fail("打码执行异常: " + e.getMessage(), 0, 0, null);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
