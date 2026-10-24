package com.freecollector.server.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.system.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_config 表 Mapper，继承 BaseMapper 即拥有单表 CRUD 能力
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
