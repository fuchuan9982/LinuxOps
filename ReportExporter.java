package com.linuxops.report;

import com.linuxops.inspection.InspectionReport;
import com.linuxops.inspection.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exports structured inspection reports as standalone HTML or UTF-8 CSV. */
public final class ReportExporter {
    private ReportExporter() {}

    public static void exportHtml(List<InspectionReport> reports, Path target) throws IOException {
        StringBuilder b = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><title>LinuxOps 巡检报告</title>");
        b.append("<style>body{font:14px system-ui;margin:28px;background:#f5f7fa;color:#202938}table{border-collapse:collapse;width:100%;margin:12px 0 28px}td,th{border:1px solid #ccd4df;padding:8px;text-align:left}th{background:#e9eef5}.CRITICAL{color:#b42318}.WARNING{color:#9a6700}.NORMAL{color:#16794b}</style><h1>LinuxOps 巡检报告</h1>");
        b.append("<p>生成时间：").append(escape(java.time.Instant.now().toString())).append("</p>");
        for (InspectionReport r : reports) {
            b.append("<h2>").append(escape(r.hostName())).append("（").append(escape(r.hostId())).append("）</h2>");
            b.append("<p class=\"").append(r.overallSeverity()).append("\">总体状态：").append(r.overallSeverity()).append("；正常 ").append(r.count(Severity.NORMAL)).append("，警告 ").append(r.count(Severity.WARNING)).append("，严重 ").append(r.count(Severity.CRITICAL)).append("</p>");
            if (r.collectionError() != null) b.append("<p class=\"CRITICAL\">采集错误：").append(escape(r.collectionError())).append("</p>");
            b.append("<table><tr><th>指标</th><th>采集值</th><th>级别</th><th>说明</th></tr>");
            for (Map.Entry<String, String> e : r.metrics().entrySet()) {
                InspectionReport.Finding f = r.findings().get(e.getKey());
                b.append("<tr><td>").append(escape(e.getKey())).append("</td><td>").append(escape(e.getValue())).append("</td><td class=\"").append(f == null ? "" : f.severity()).append("\">").append(f == null ? "—" : f.severity()).append("</td><td>").append(f == null ? "" : escape(f.message())).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append("</html>");
        Files.writeString(target, b.toString(), StandardCharsets.UTF_8);
    }

    public static void exportCsv(List<InspectionReport> reports, Path target) throws IOException {
        StringBuilder b = new StringBuilder("host_id,host_name,inspected_at,overall_severity,metric,value,severity,message\r\n");
        for (InspectionReport r : reports) {
            if (r.metrics().isEmpty()) row(b, r.hostId(), r.hostName(), r.inspectedAt().toString(), r.overallSeverity().name(), "", "", "CRITICAL", r.collectionError());
            for (Map.Entry<String, String> e : r.metrics().entrySet()) {
                InspectionReport.Finding f = r.findings().get(e.getKey());
                row(b, r.hostId(), r.hostName(), r.inspectedAt().toString(), r.overallSeverity().name(), e.getKey(), e.getValue(), f == null ? "" : f.severity().name(), f == null ? "" : f.message());
            }
        }
        Files.writeString(target, "\uFEFF" + b, StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder b, String... cells) {
        for (int i = 0; i < cells.length; i++) { if (i > 0) b.append(','); b.append('"').append((cells[i] == null ? "" : cells[i]).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")).append('"'); }
        b.append("\r\n");
    }
    private static String escape(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
