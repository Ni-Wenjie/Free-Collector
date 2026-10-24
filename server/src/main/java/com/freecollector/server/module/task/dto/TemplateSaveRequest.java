package com.freecollector.server.module.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 保存为模板请求
 */
@Data
public class TemplateSaveRequest {

    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    private String remark;
}
