package com.freecollector.server.module.clean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.clean.entity.CleanRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 清洗规则 Mapper
 */
@Mapper
public interface CleanRuleMapper extends BaseMapper<CleanRule> {
}
