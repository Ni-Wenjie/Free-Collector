package com.freecollector.server.module.system.controller;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.common.Result;
import com.freecollector.server.module.system.entity.SysConfig;
import com.freecollector.server.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统配置接口（骨架联调示例：验证 MVC 全链路 + 统一响应体 + MyBatis-Plus）
 */
@RestController
@RequestMapping("/api/sys-config")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    /** 查询全部配置（前端设置面板使用） */
    @GetMapping("/list")
    public Result<List<SysConfig>> list() {
        return Result.ok(sysConfigService.list());
    }

    /** 按 key 查询单个配置，如 /api/sys-config/advanced_mode_enabled */
    @GetMapping("/{key}")
    public Result<SysConfig> getByKey(@PathVariable String key) {
        SysConfig config = sysConfigService.getByKey(key);
        if (config == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "配置不存在: " + key);
        }
        return Result.ok(config);
    }

    /** 按 key 更新配置值，请求体：{"value": "1"} */
    @PutMapping("/{key}")
    public Result<Void> update(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求体缺少 value 字段");
        }
        // upsert：设置类开关首次写入时配置项可能尚不存在
        sysConfigService.upsertValueByKey(key, value, body.get("remark"));
        return Result.ok();
    }
}
