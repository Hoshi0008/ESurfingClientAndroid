package com.esurfing.client.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置文件（key=value，UTF-8）。
 *
 * <p>后端在启动服务时读取本文件；前端「设置」页逐项编辑后<b>立即写回磁盘</b>，
 * 提示用户「重启服务后生效」。键名与参考后端 esurfing.conf 保持一致，
 * 方便从 PC 端迁移配置。</p>
 */
public final class Config {

    /** 一个配置字段：键名、中文名、默认值、说明、可选项。 */
    public static final class Field {
        public final String key;
        public final String label;
        public final String def;
        public final String hint;
        public final String[] options;
        /** true = 应用级设置（主题、开机自启），不写进网络配置文件。 */
        public final boolean appLevel;
        /** 可选项的显示名（与 {@link #options} 一一对应，可为 null 表示直接显示原值）。 */
        public String[] labels;

        Field(String key, String label, String def, String hint, String... options) {
            this(key, label, def, hint, false, options);
        }

        Field(String key, String label, String def, String hint, boolean appLevel,
              String... options) {
            this.key = key;
            this.label = label;
            this.def = def;
            this.hint = hint;
            this.appLevel = appLevel;
            this.options = options == null || options.length == 0 ? null : options;
        }

        /** 给字段挂上中文显示名（下拉框与设置行都用它）。 */
        Field labels(String... names) {
            this.labels = names;
            return this;
        }

        /** 某个取值该显示成什么。 */
        public String display(String value) {
            if (options == null || labels == null) {
                return value;
            }
            for (int i = 0; i < options.length && i < labels.length; i++) {
                if (options[i].equalsIgnoreCase(value)) {
                    return labels[i];
                }
            }
            return value;
        }

        /** 某个取值在 options 里的下标，找不到返回 -1。 */
        public int indexOf(String value) {
            if (options == null) {
                return -1;
            }
            for (int i = 0; i < options.length; i++) {
                if (options[i].equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return i;
                }
            }
            return -1;
        }

        /** 校验用户输入；返回 null 表示合法，否则返回错误提示。 */
        public String validate(String value) {
            String v = value == null ? "" : value.trim();
            if (options != null) {
                for (String o : options) {
                    if (o.equalsIgnoreCase(v)) {
                        return null;
                    }
                }
                return "取值只能是 " + join(options);
            }
            if ("port".equals(key) || "interval_min".equals(key) || "net_check".equals(key)) {
                if (v.isEmpty()) {
                    return "不能为空";
                }
                try {
                    int n = Integer.parseInt(v);
                    if ("port".equals(key)) {
                        return n > 0 && n < 65536 ? null : "端口范围 1~65535";
                    }
                    return n >= 0 && n <= 86400 ? null : "范围 0~86400";
                } catch (NumberFormatException e) {
                    return "必须是整数";
                }
            }
            if ("campus_net".equals(key) || "eia_net".equals(key)) {
                if (v.isEmpty()) {
                    return null;
                }
                for (String part : v.split(",")) {
                    if (!IpUtil.isCidr(part.trim())) {
                        return "网段格式不对，例如 10.0.0.0/8";
                    }
                }
                return null;
            }
            if ("servers".equals(key)) {
                if (v.isEmpty()) {
                    return null;
                }
                for (String part : v.split(",")) {
                    String s = part.trim();
                    if (!s.isEmpty() && !IpUtil.isIpv4(s) && !s.matches("[A-Za-z0-9.-]{1,120}")) {
                        return "服务器地址不合法: " + s;
                    }
                }
                return null;
            }
            if ("acip".equals(key)) {
                return v.isEmpty() || IpUtil.isIpv4(v) ? null : "必须是 IPv4 地址";
            }
            if ("mac".equals(key)) {
                return v.isEmpty() || IpUtil.isMac(v) ? null : "格式如 02:11:22:33:44:55";
            }
            if ("eia_portal".equals(key)) {
                return v.isEmpty() || v.toLowerCase(Locale.US).startsWith("http") ? null : "需要以 http:// 开头";
            }
            return null;
        }

        private static String join(String[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(" / ");
                }
                sb.append(arr[i]);
            }
            return sb.toString();
        }
    }

    private static final List<Field> SCHEMA = new ArrayList<Field>();

    static {
        SCHEMA.add(new Field("user", "账号", "", "校园网账号（学号）"));
        SCHEMA.add(new Field("passwd", "密码", "", "校园网密码"));
        SCHEMA.add(new Field("channel", "通道", "android", "对外声明的客户端类型",
                "android", "windows", "linux", "macos", "ios"));
        SCHEMA.add(new Field("servers", "认证服务器", "", "BRAS 地址，逗号分隔；留空则自动探测"));
        SCHEMA.add(new Field("port", "门户端口", "7001", "认证门户端口"));
        SCHEMA.add(new Field("acip", "认证器", "", "自动探测失败时手动指定 AC 地址"));
        SCHEMA.add(new Field("campus_net", "天翼网段", "", "如 10.0.0.0/8；只有落在此网段才认证"));
        SCHEMA.add(new Field("eia_net", "准入网段", "", "落到这里说明本机 MAC 还没登记"));
        SCHEMA.add(new Field("eia_portal", "准入门户", "", "MAC 登记页地址，如 http://10.1.1.1"));
        SCHEMA.add(new Field("schoolid", "学校ID", "", "多数学校留空"));
        SCHEMA.add(new Field("domain", "域", "", "多数学校留空"));
        SCHEMA.add(new Field("area", "区域", "", "多数学校留空"));
        SCHEMA.add(new Field("interval_min", "心跳下限", "20", "单位秒，服务端给的间隔不会低于它"));
        SCHEMA.add(new Field("net_check", "外网校验", "300", "单位秒；0 = 关闭"));
        SCHEMA.add(new Field("force", "强制认证", "false", "探测到已在线也重新认证",
                "false", "true").labels("关闭", "开启"));
        SCHEMA.add(new Field("mac", "上报MAC", "", "留空则每次随机生成（服务端不校验真伪）"));
        SCHEMA.add(new Field("ssid", "校园WiFi", "", "仅作记录，便于确认连的是哪张网"));
    }

    /** 应用级设置：与网络配置分开保存、改完立即生效（不写进 esurfing.conf）。 */
    private static final List<Field> APP_FIELDS = new ArrayList<Field>();

    static {
        APP_FIELDS.add(new Field("theme", "主题", "auto", "深色 / 浅色 / 跟随系统", true,
                "auto", "light", "dark").labels("跟随系统", "浅色", "深色"));
        APP_FIELDS.add(new Field("auto_start", "开机自启", "true", "开机后自动启动认证服务", true,
                "true", "false").labels("开启", "关闭"));
    }

    private final File file;
    private final LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();

    private Config(File file) {
        this.file = file;
        for (Field f : SCHEMA) {
            values.put(f.key, f.def);
        }
    }

    public File file() {
        return file;
    }

    public static List<Field> fields() {
        return SCHEMA;
    }

    /** 应用级设置项（主题、开机自启），单独存在 AppPrefs 里。 */
    public static List<Field> appFields() {
        return APP_FIELDS;
    }

    public static Field field(String key) {
        for (Field f : SCHEMA) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        for (Field f : APP_FIELDS) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    /** 读取配置；文件不存在时返回一份默认配置（内存里，不落盘）。 */
    public static Config load(File f) {
        Config c = new Config(f);
        if (f == null || !f.isFile()) {
            return c;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = s.substring(0, eq).trim();
                String value = s.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    c.values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            // 读不到就当默认配置
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        return c;
    }

    public String get(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    public String get(String key, String def) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public int intOf(String key, int def) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean boolOf(String key, boolean def) {
        String v = get(key).trim();
        if (v.isEmpty()) {
            return def;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public Config set(String key, String value) {
        values.put(key, value == null ? "" : value.trim());
        return this;
    }

    /** 删掉某个键（升级迁移用），返回是否真的存在过。 */
    public boolean remove(String key) {
        return values.remove(key) != null;
    }

    /** 关键项齐全（账号 + 密码都非空）才算配置完成。 */
    public boolean ready() {
        return !get("user").trim().isEmpty() && !get("passwd").trim().isEmpty();
    }

    /** 写入磁盘，返回是否成功。 */
    public boolean save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# =============================================================\n");
        sb.append("#  ESurfing 校园网客户端配置\n");
        sb.append("#  在 App 的「设置」页里改，改完需要重启服务才生效。\n");
        sb.append("# =============================================================\n\n");
        for (Field f : SCHEMA) {
            sb.append("# ").append(f.label);
            if (!f.hint.isEmpty()) {
                sb.append("：").append(f.hint);
            }
            if (f.options != null) {
                sb.append("（").append(joinArr(f.options)).append("）");
            }
            sb.append('\n');
            sb.append(f.key).append('=').append(get(f.key)).append('\n').append('\n');
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (field(e.getKey()) == null) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            out = new FileOutputStream(file);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static String joinArr(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Field f : SCHEMA) {
            sb.append(f.key).append('=').append(get(f.key)).append('\n');
        }
        return sb.toString();
    }
}
