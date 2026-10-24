package com.freecollector.server.module.captcha.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.captcha.entity.CaptchaConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 打码方案配置 Mapper
 */
@Mapper
public interface CaptchaConfigMapper extends BaseMapper<CaptchaConfig> {
}
