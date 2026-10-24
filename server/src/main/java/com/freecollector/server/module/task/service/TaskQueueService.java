package com.freecollector.server.module.task.service;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.task.dto.TaskRuntimeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 任务队列服务（Redis List 实现，禁止使用 Kafka/RocketMQ 等重型中间件）
 *
 * 数据结构：
 *  - fc:task:queue               List，任务ID队列，FIFO，工作线程 leftPop 领取
 *  - fc:task:control:{taskId}    String，控制指令 PAUSE / STOP，工作线程在页间检查
 *  - fc:task:runtime:{taskId}    Hash，运行时进度快照（供前端轮询展示）
 *  - fc:task:run:{taskId}        String，运行令牌：每次启动/继续生成新值，
 *                                工作线程收尾时校验，旧运行令牌失效后不得再写状态，
 *                                解决"暂停后立即继续"时旧运行收尾覆盖新运行状态的竞态
 *
 * 安全说明：Redis 仅连接本机 127.0.0.1，队列中只存任务ID，不存任何业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueService {

    /** 任务队列键 */
    private static final String QUEUE_KEY = "fc:task:queue";
    /** 控制指令键前缀 */
    private static final String CONTROL_PREFIX = "fc:task:control:";
    /** 运行时状态键前缀 */
    private static final String RUNTIME_PREFIX = "fc:task:runtime:";
    /** 运行令牌键前缀 */
    private static final String RUN_TOKEN_PREFIX = "fc:task:run:";

    /** 控制指令有效期：防止异常退出后残留指令永久生效 */
    private static final Duration CONTROL_TTL = Duration.ofHours(2);
    /** 运行时状态有效期：1天，过期后前端回落读取数据库状态 */
    private static final Duration RUNTIME_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;

    /** 任务入队（队尾入，队头出，保证先来先跑） */
    public void enqueue(Long taskId) {
        try {
            redisTemplate.opsForList().rightPush(QUEUE_KEY, String.valueOf(taskId));
            log.info("任务已入队 taskId={}", taskId);
        } catch (RedisConnectionFailureException e) {
            throw new BizException(ErrorCode.REDIS_ERROR, "Redis 未启动，无法入队，请先启动本机 Redis");
        }
    }

    /**
     * 生成新的运行令牌（启动/继续时在入队前调用）
     *
     * 工作线程开始执行时读取当前令牌作为自己的身份；收尾时再次读取比对，
     * 若令牌已变更说明本次运行已被新的启动/继续取代，收尾必须静默退出。
     */
    public void newRunToken(Long taskId) {
        try {
            String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            redisTemplate.opsForValue().set(RUN_TOKEN_PREFIX + taskId, token, Duration.ofDays(1));
        } catch (RedisConnectionFailureException e) {
            throw new BizException(ErrorCode.REDIS_ERROR, "Redis 未启动，无法启动任务");
        }
    }

    /** 读取当前运行令牌，不存在返回 null */
    public String getRunToken(Long taskId) {
        try {
            return redisTemplate.opsForValue().get(RUN_TOKEN_PREFIX + taskId);
        } catch (RedisConnectionFailureException e) {
            return null;
        }
    }

    /**
     * 阻塞出队
     *
     * @param timeoutSeconds 阻塞超时秒数，超时返回 null
     */
    public Long poll(int timeoutSeconds) {
        try {
            String value = redisTemplate.opsForList()
                    .leftPop(QUEUE_KEY, timeoutSeconds, TimeUnit.SECONDS);
            return value == null ? null : Long.parseLong(value);
        } catch (RedisConnectionFailureException e) {
            throw new BizException(ErrorCode.REDIS_ERROR, "Redis 连接失败");
        }
    }

    /** 当前队列长度 */
    public long queueSize() {
        try {
            Long size = redisTemplate.opsForList().size(QUEUE_KEY);
            return size == null ? 0 : size;
        } catch (RedisConnectionFailureException e) {
            return 0;
        }
    }

    /** 下发暂停指令 */
    public void requestPause(Long taskId) {
        setControl(taskId, "PAUSE");
    }

    /** 下发终止指令 */
    public void requestStop(Long taskId) {
        setControl(taskId, "STOP");
    }

    /** 读取控制指令，无指令返回 null */
    public String getControl(Long taskId) {
        try {
            return redisTemplate.opsForValue().get(CONTROL_PREFIX + taskId);
        } catch (RedisConnectionFailureException e) {
            return null;
        }
    }

    /** 清除控制指令（启动/继续时调用） */
    public void clearControl(Long taskId) {
        try {
            redisTemplate.delete(CONTROL_PREFIX + taskId);
        } catch (RedisConnectionFailureException e) {
            log.debug("Redis 不可用，跳过清除控制指令 taskId={}", taskId);
        }
    }

    /** 保存运行时状态快照 */
    public void saveRuntime(TaskRuntimeVO vo) {
        try {
            String key = RUNTIME_PREFIX + vo.getTaskId();
            Map<String, String> map = new HashMap<>();
            map.put("taskId", String.valueOf(vo.getTaskId()));
            map.put("status", vo.getStatus());
            map.put("currentPage", String.valueOf(vo.getCurrentPage()));
            map.put("maxPages", String.valueOf(vo.getMaxPages()));
            map.put("successCount", String.valueOf(vo.getSuccessCount()));
            map.put("failCount", String.valueOf(vo.getFailCount()));
            map.put("batchNo", vo.getBatchNo() == null ? "" : vo.getBatchNo());
            map.put("message", vo.getMessage() == null ? "" : vo.getMessage());
            map.put("updateTime", LocalDateTime.now().toString());
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key, RUNTIME_TTL);
        } catch (RedisConnectionFailureException e) {
            log.debug("Redis 不可用，跳过保存运行时状态 taskId={}", vo.getTaskId());
        }
    }

    /** 读取运行时状态快照，不存在返回 null */
    public TaskRuntimeVO getRuntime(Long taskId) {
        try {
            Map<Object, Object> map = redisTemplate.opsForHash().entries(RUNTIME_PREFIX + taskId);
            if (map == null || map.isEmpty()) {
                return null;
            }
            TaskRuntimeVO vo = new TaskRuntimeVO();
            vo.setTaskId(taskId);
            vo.setStatus(str(map.get("status")));
            vo.setCurrentPage(intVal(map.get("currentPage")));
            vo.setMaxPages(intVal(map.get("maxPages")));
            vo.setSuccessCount(intVal(map.get("successCount")));
            vo.setFailCount(intVal(map.get("failCount")));
            vo.setBatchNo(str(map.get("batchNo")));
            vo.setMessage(str(map.get("message")));
            String time = str(map.get("updateTime"));
            if (!time.isEmpty()) {
                vo.setUpdateTime(LocalDateTime.parse(time));
            }
            return vo;
        } catch (RedisConnectionFailureException e) {
            return null;
        } catch (Exception e) {
            log.debug("运行时状态解析失败 taskId={}", taskId);
            return null;
        }
    }

    /** 删除运行时状态（删除任务时调用） */
    public void deleteRuntime(Long taskId) {
        try {
            redisTemplate.delete(RUNTIME_PREFIX + taskId);
        } catch (RedisConnectionFailureException e) {
            log.debug("Redis 不可用，跳过删除运行时状态 taskId={}", taskId);
        }
    }

    private void setControl(Long taskId, String command) {
        try {
            redisTemplate.opsForValue().set(CONTROL_PREFIX + taskId, command, CONTROL_TTL);
        } catch (RedisConnectionFailureException e) {
            throw new BizException(ErrorCode.REDIS_ERROR, "Redis 未启动，无法下发控制指令");
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intVal(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
