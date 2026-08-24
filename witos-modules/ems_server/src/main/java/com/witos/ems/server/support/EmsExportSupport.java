package com.witos.ems.server.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.witos.common.core.utils.StringUtils;

/**
 * 轻量导出支持。当前阶段用于前端下载按钮可用，后续可替换为真正的 Excel 导出。
 */
public final class EmsExportSupport
{
    private EmsExportSupport()
    {
    }

    public static void writeTable(HttpServletResponse response, String fileName, List<String> headers,
                                  List<String> keys, List<Map<String, Object>> rows) throws IOException
    {
        response.reset();
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/octet-stream;charset=UTF-8");
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encodedName);

        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", headers)).append("\r\n");
        for (Map<String, Object> row : rows)
        {
            for (int i = 0; i < keys.size(); i++)
            {
                if (i > 0)
                {
                    sb.append('\t');
                }
                Object value = row.get(keys.get(i));
                sb.append(value == null ? "" : String.valueOf(value));
            }
            sb.append("\r\n");
        }

        OutputStream out = response.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static String safeFileName(String prefix)
    {
        if (StringUtils.isEmpty(prefix))
        {
            prefix = "ems_export";
        }
        return prefix + ".xlsx";
    }
}
