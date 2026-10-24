package com.freecollector.server.module.captcha.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.captcha.model.CaptchaConfigSaveRequest;
import com.freecollector.server.module.captcha.model.CaptchaConfigVO;
import com.freecollector.server.module.captcha.service.CaptchaConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 打码方案配置接口（高阶配置面板）
 */
@RestController
@RequestMapping("/api/captcha/config")
@RequiredArgsConstructor
public class CaptchaConfigController {

    private final CaptchaConfigService configService;

    /** 方案列表（脱敏） */
    @GetMapping("/list")
    public Result<List<CaptchaConfigVO>> list() {
        return Result.ok(configService.list());
    }

    /** 保存方案（id 空新建，非空编辑） */
    @PostMapping
    public Result<Long> save(@RequestBody CaptchaConfigSaveRequest req) {
        return Result.ok(configService.save(req));
    }

    /** 删除方案 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return Result.ok();
    }
}
