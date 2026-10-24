package com.freecollector.server.module.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freecollector.server.module.system.entity.SysConfig;
import com.freecollector.server.module.system.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 系统配置 Service
 * 骨架阶段采用单类 Service（直接继承 ServiceImpl），后续模块复杂后再拆 interface + impl
 */
@Service
public class SysConfigService extends ServiceImpl<SysConfigMapper, SysConfig> {

    /** 按配置键查询，不存在返回 null */
    public SysConfig getByKey(String key) {
        return getOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, key));
    }

    /** 按配置键更新值，返回是否存在并更新成功 */
    public boolean updateValueByKey(String key, String value) {
        return update(Wrappers.<SysConfig>lambdaUpdate()
                .eq(SysConfig::getConfigKey, key)
                .set(SysConfig::getConfigValue, value));
    }

    /**
     * 按配置键写入值（upsert）：不存在则插入，存在则更新
     * 用于设置类开关（如高阶模式），首次写入时配置项可能尚不存在
     */
    public void upsertValueByKey(String key, String value, String remark) {
        SysConfig existing = getByKey(key);
        if (existing == null) {
            SysConfig config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setRemark(remark);
            save(config);
        } else {
            existing.setConfigValue(value);
            updateById(existing);
        }
    }
}
