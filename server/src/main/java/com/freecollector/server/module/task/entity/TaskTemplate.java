package com.freecollector.server.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务模板实体，对应表 task_template
 * template_json 保存任务配置 + 字段规则快照，敏感信息仍为密文
 */
@Data
@TableName("task_template")
public class TaskTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称 */
    private String templateName;

    /** 来源任务ID */
    private Long sourceTaskId;

    /** 模板完整快照 JSON */
    private String templateJson;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
