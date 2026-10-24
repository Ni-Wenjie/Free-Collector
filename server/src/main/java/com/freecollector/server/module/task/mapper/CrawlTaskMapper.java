package com.freecollector.server.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.task.entity.CrawlTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlTaskMapper extends BaseMapper<CrawlTask> {
}
