package com.linuxops.inspection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the tab-separated, base64-valued protocol emitted by InspectionCommand. */
public final class InspectionParser {
    private InspectionParser() {}

    public static InspectionReport parse(String hostId, String hostName, String stdout, String collectionError) {
        Map<String, String> metrics = new LinkedHashMap<>();
        if (stdout != null) {
            for (String line : stdout.split("\\R")) {
                int split = line.indexOf('\t');
                if (split <= 0) continue;
                String key = line.substring(0, split).trim();
                String encoded = line.substring(split + 1).trim();
                try {
                    String value = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    metrics.put(key, value);
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed or unrelated lines; SSH banners must not break the report.
                }
            }
        }
        Map<String, InspectionReport.Finding> findings = classify(metrics);
        return new InspectionReport(hostId, hostName, Instant.now(), metrics, findings, collectionError);
    }

    private static Map<String, InspectionReport.Finding> classify(Map<String, String> m) {
        Map<String, InspectionReport.Finding> f = new LinkedHashMap<>();
        threshold(f, m, "root_disk_used_pct", "根分区使用率", 80, 95, "%");
        threshold(f, m, "load_1m", "1 分钟系统负载", 4, 8, "");
        threshold(f, m, "failed_services", "失败的 systemd 服务数", 1, 5, " 个");
        String firewall = m.getOrDefault("firewall", "unknown").toLowerCase();
        if (firewall.equals("inactive") || firewall.equals("not running"))
            f.put("firewall", finding(Severity.WARNING, "检测到防火墙未启用", m.get("firewall")));
        else if (firewall.equals("unknown") || firewall.isBlank())
            f.put("firewall", finding(Severity.WARNING, "无法确认防火墙状态", m.get("firewall")));
        else f.put("firewall", finding(Severity.NORMAL, "已检测到防火墙状态信息", m.get("firewall")));
        return f;
    }

    private static void threshold(Map<String, InspectionReport.Finding> out, Map<String, String> metrics,
                                  String key, String label, double warning, double critical, String unit) {
        String raw = metrics.get(key);
        if (raw == null || raw.isBlank() || raw.equals("unsupported")) {
            out.put(key, finding(Severity.WARNING, label + "数据不可用", raw));
            return;
        }
        try {
            double value = Double.parseDouble(raw);
            Severity severity = value >= critical ? Severity.CRITICAL : value >= warning ? Severity.WARNING : Severity.NORMAL;
            String message = severity == Severity.NORMAL ? label + "正常" : severity == Severity.WARNING ? label + "偏高" : label + "达到严重阈值";
            out.put(key, finding(severity, message, raw + unit));
        } catch (NumberFormatException ex) {
            out.put(key, finding(Severity.WARNING, label + "数据格式异常", raw));
        }
    }

    private static InspectionReport.Finding finding(Severity s, String message, String value) {
        return new InspectionReport.Finding(s, message, value);
    }
}
