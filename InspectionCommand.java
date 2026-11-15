package com.linuxops.inspection;

/** Builds a POSIX-shell inspection script. Values are base64 encoded remotely to preserve delimiters/newlines. */
public final class InspectionCommand {
    private InspectionCommand() {}

    public static String build() {
        return """
                set +e
                emit() { printf '%s\\t' "$1"; printf '%s' "$2" | base64 | tr -d '\\n'; printf '\\n'; }
                read_os() { . /etc/os-release 2>/dev/null; printf '%s' "${PRETTY_NAME:-${NAME:-unknown}}"; }
                emit os_name "$(read_os)"
                emit kernel "$(uname -r 2>/dev/null)"
                emit hostname "$(hostname 2>/dev/null)"
                emit uptime_seconds "$(awk '{print int($1)}' /proc/uptime 2>/dev/null)"
                emit cpu_cores "$(getconf _NPROCESSORS_ONLN 2>/dev/null)"
                emit load_1m "$(awk '{print $1}' /proc/loadavg 2>/dev/null)"
                emit memory_total_mb "$(awk '/MemTotal:/ {print int($2/1024)}' /proc/meminfo 2>/dev/null)"
                emit memory_available_mb "$(awk '/MemAvailable:/ {print int($2/1024)}' /proc/meminfo 2>/dev/null)"
                emit root_disk_used_pct "$(df -P / 2>/dev/null | awk 'NR==2 {gsub(/%/,\"\",$5); print $5}')"
                if command -v systemctl >/dev/null 2>&1; then
                  emit failed_services "$(systemctl --failed --no-legend --no-pager 2>/dev/null | wc -l | tr -d ' ')"
                else emit failed_services "unsupported"; fi
                if command -v ufw >/dev/null 2>&1; then
                  emit firewall "$(ufw status 2>/dev/null | head -1)"
                elif command -v firewall-cmd >/dev/null 2>&1; then
                  emit firewall "$(firewall-cmd --state 2>/dev/null)"
                elif command -v nft >/dev/null 2>&1; then
                  emit firewall "nftables-present"
                else emit firewall "unknown"; fi
                if command -v apt-get >/dev/null 2>&1; then emit package_manager apt;
                elif command -v dnf >/dev/null 2>&1; then emit package_manager dnf;
                elif command -v yum >/dev/null 2>&1; then emit package_manager yum;
                elif command -v zypper >/dev/null 2>&1; then emit package_manager zypper;
                else emit package_manager unknown; fi
                """;
    }
}
