package com.freecollector.server.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局系统配置实体，对应表 sys_config
 * 字段与数据库下划线列自动映射（map-underscore-to-camel-case）
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键 */
    private String configKey;

    /** 配置值（encrypted=1 时为 AES 密文） */
    private String configValue;

    /** 是否加密存储：0明文 1密文 */
    private Integer encrypted;

    /** 配置说明 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
