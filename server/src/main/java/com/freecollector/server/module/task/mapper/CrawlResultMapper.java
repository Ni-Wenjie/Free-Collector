package com.freecollector.server.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.task.entity.CrawlResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlResultMapper extends BaseMapper<CrawlResult> {
}
