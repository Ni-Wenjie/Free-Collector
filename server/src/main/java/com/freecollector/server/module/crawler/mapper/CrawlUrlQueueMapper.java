package com.freecollector.server.module.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.crawler.entity.CrawlUrlQueue;
import org.apache.ibatis.annotations.Mapper;

/**
 * URL队列 Mapper
 */
@Mapper
public interface CrawlUrlQueueMapper extends BaseMapper<CrawlUrlQueue> {
}
