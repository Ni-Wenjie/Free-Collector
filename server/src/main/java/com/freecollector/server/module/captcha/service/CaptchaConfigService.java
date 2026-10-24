package com.freecollector.server.module.captcha.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.captcha.entity.CaptchaConfig;
import com.freecollector.server.module.captcha.mapper.CaptchaConfigMapper;
import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaConfigSaveRequest;
import com.freecollector.server.module.captcha.model.CaptchaConfigVO;
import com.freecollector.server.util.AesCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 打码方案配置服务：CRUD + 密钥加解密 + 运行时模型转换
 *
 * 安全约束：
 *  - apiKey 明文只在请求/运行时内存中存在，入库必须 AES 加密；
 *  - 列表/详情接口只返回 apiKeyConfigured 布尔，绝不返回密钥本身；
 *  - 打码请求由本机主动向外发起，软件不作为中转服务器（模块三网关已保证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaConfigService {

    private final CaptchaConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    /** 方案列表（按创建时间倒序），脱敏返回 */
    public List<CaptchaConfigVO> list() {
        return configMapper.selectList(new LambdaQueryWrapper<CaptchaConfig>()
                        .orderByDesc(CaptchaConfig::getCreateTime))
                .stream().map(CaptchaConfigVO::from).collect(Collectors.toList());
    }

    /** 保存方案：id 为空新建，非空编辑；apiKey 为 null 保持、空串清除、非空加密更新 */
    public Long save(CaptchaConfigSaveRequest req) {
        validate(req);

        CaptchaConfig entity = req.getId() == null ? new CaptchaConfig() : requireConfig(req.getId());
        entity.setConfigName(req.getConfigName().trim());
        entity.setApiUrl(req.getApiUrl().trim());
        entity.setHttpMethod(req.getHttpMethod().trim().toUpperCase());
        entity.setRequestHeaders(req.getRequestHeaders());
        entity.setParamTemplate(req.getParamTemplate());
        entity.setUploadMode(req.getUploadMode().trim().toUpperCase());
        entity.setResultJsonpath(req.getResultJsonPath().trim());
        entity.setPollIntervalMs(req.getPollIntervalMs());
        entity.setTimeoutMs(req.getTimeoutMs());
        entity.setRetryTimes(req.getRetryTimes());
        entity.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        entity.setRemark(req.getRemark());

        // 密钥：null 保持不变（编辑留空不清除）；空串清除；非空 AES 加密更新
        if (req.getApiKey() != null) {
            if (req.getApiKey().isBlank()) {
                entity.setApiKeyEncrypted(null);
            } else {
                entity.setApiKeyEncrypted(AesCryptoUtil.encrypt(req.getApiKey().trim()));
            }
        }

        if (req.getId() == null) {
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
            // updateById 默认忽略 null 字段，"空串清除密钥"需显式置空才能落库
            if (req.getApiKey() != null && req.getApiKey().isBlank()) {
                configMapper.update(null, Wrappers.<CaptchaConfig>lambdaUpdate()
                        .eq(CaptchaConfig::getId, entity.getId())
                        .set(CaptchaConfig::getApiKeyEncrypted, null));
            }
        }
        log.info("打码方案已保存 id={} name={}", entity.getId(), entity.getConfigName());
        return entity.getId();
    }

    /** 删除方案（逻辑删除） */
    public void delete(Long id) {
        requireConfig(id);
        configMapper.deleteById(id);
        log.info("打码方案已删除 id={}", id);
    }

    /**
     * 读取方案并转换为运行时模型（解密密钥）
     * 供爬虫执行时按 captcha_config_id 加载使用
     */
    public CaptchaConfigModel getRuntimeModel(Long id) {
        CaptchaConfig entity = requireConfig(id);
        if (entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "打码方案已停用，请先启用");
        }

        CaptchaConfigModel model = new CaptchaConfigModel();
        model.setId(entity.getId());
        model.setConfigName(entity.getConfigName());
        model.setApiUrl(entity.getApiUrl());
        model.setHttpMethod(entity.getHttpMethod());
        model.setRequestHeaders(entity.getRequestHeaders());
        model.setParamTemplate(entity.getParamTemplate());
        model.setUploadMode(entity.getUploadMode());
        model.setResultJsonPath(entity.getResultJsonpath());
        model.setPollIntervalMs(entity.getPollIntervalMs());
        model.setTimeoutMs(entity.getTimeoutMs());
        model.setRetryTimes(entity.getRetryTimes());
        model.setEnabled(true);

        // 解密密钥（仅内存使用，禁止落日志）
        if (entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank()) {
            try {
                model.setApiKey(AesCryptoUtil.decrypt(entity.getApiKeyEncrypted()));
            } catch (Exception e) {
                log.error("打码密钥解密失败 id={}", id);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "打码密钥解密失败，请重新配置密钥");
            }
        }
        return model;
    }

    /** 保存前校验：地址协议、方法、上传方式、JSON 合法性等 */
    private void validate(CaptchaConfigSaveRequest req) {
        if (req.getConfigName() == null || req.getConfigName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "方案名称不能为空");
        }
        if (req.getApiUrl() == null || req.getApiUrl().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "接口地址不能为空");
        }
        String url = req.getApiUrl().trim().toLowerCase();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "接口地址仅支持 http/https");
        }
        String method = req.getHttpMethod() == null ? "POST" : req.getHttpMethod().trim().toUpperCase();
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求方法仅支持 GET/POST");
        }
        String mode = req.getUploadMode() == null ? "BASE64" : req.getUploadMode().trim().toUpperCase();
        if (!mode.equals("BASE64") && !mode.equals("IMAGE_URL")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "上传方式仅支持 BASE64/IMAGE_URL");
        }
        if (req.getParamTemplate() == null || req.getParamTemplate().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数模板不能为空");
        }
        if (req.getResultJsonPath() == null || req.getResultJsonPath().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结果提取JSONPath不能为空");
        }
        // 请求头必须是合法JSON对象（允许为空）
        if (req.getRequestHeaders() != null && !req.getRequestHeaders().isBlank()) {
            try {
                if (!objectMapper.readTree(req.getRequestHeaders()).isObject()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "请求头必须是JSON对象");
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_ERROR, "请求头不是合法JSON");
            }
        }
        if (req.getTimeoutMs() == null || req.getTimeoutMs() < 1000) {
            throw new BizException(ErrorCode.PARAM_ERROR, "超时时间不能小于 1000 毫秒");
        }
    }

    private CaptchaConfig requireConfig(Long id) {
        CaptchaConfig entity = configMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "打码方案不存在或已删除");
        }
        return entity;
    }
}
