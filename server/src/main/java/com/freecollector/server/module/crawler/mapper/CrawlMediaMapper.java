package com.freecollector.server.module.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecollector.server.module.crawler.entity.CrawlMedia;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体资源记录 Mapper
 */
@Mapper
public interface CrawlMediaMapper extends BaseMapper<CrawlMedia> {
}
