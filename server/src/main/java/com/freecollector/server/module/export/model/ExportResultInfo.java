package com.freecollector.server.module.export.model;

import lombok.Data;

/**
 * 导出结果信息：单机本地程序直接落盘到本机目录，返回路径给前端展示
 */
@Data
public class ExportResultInfo {

    /** 导出文件完整路径 */
    private String filePath;

    /** 文件名 */
    private String fileName;

    /** 导出格式：csv / json / xlsx */
    private String format;

    /** 导出行数（清洗后实际写入行数） */
    private int rows;

    /** 是否应用了清洗规则 */
    private boolean cleaned;

    /** 清洗/导出过程告警 */
    private java.util.List<String> warnings;
}
