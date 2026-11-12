package com.dsmnh.mobile.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/** 后端配置：默认值对应契约 1.0.0；配置文件缺省时由命令行向导生成。 */
public final class Config {
    public int appPort = 44444;
    public int nodePort = 44443;
    public String dbPath = "horizon.db";
    public String networkName = "Horizon";
    public String backendVersion = "0.1.0";
    public int maxConnectionsPerPlane = 32;
    public int bindCodeTtlSec = 600;
    public int nodeCodeTtlSec = 900;
    public int minPasswordLength = 8;
    public int minNameLength = 2;
    public int maxNameLength = 24;
    public int loginLockBaseSec = 30;
    public int loginLockThreshold = 5;
    public int loginLockMaxSec = 900;
    public int sessionIdleTimeoutSec = 0;
    public int chatCooldownMs = 2000;
    /** TLS 为可选外层（契约 §9.1）；App 平面默认开启，本地明文联调需显式关闭。 */
    public boolean appTlsEnabled = true;
    public boolean nodeTlsEnabled = false;
    public String tlsStorePath = "horizon-tls.p12";
    public String tlsStorePassword = "changeit";

    private static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static {
        FIELD_LABELS.put("appPort", "App 平面端口");
        FIELD_LABELS.put("nodePort", "节点平面端口");
        FIELD_LABELS.put("dbPath", "SQLite 文件路径");
        FIELD_LABELS.put("networkName", "网络名");
        FIELD_LABELS.put("appTlsEnabled", "App 平面启用 TLS（公网部署应开启，本地明文联调可关闭）");
    }

    public static Config loadOrCreate(Path file, boolean nonInteractive) throws IOException {
        if (Files.exists(file)) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file.toFile());
            Config config = mapper.treeToValue(root, Config.class);
            // 旧配置没有该键时按默认开启；显式提示，避免升级后明文客户端无法连接而无从判断。
            if (!root.has("appTlsEnabled")) {
                System.out.println("提示：配置未包含 appTlsEnabled，已按默认开启 App 平面 TLS。"
                        + "本地明文联调请显式设为 false 后重启。");
            }
            return config;
        }
        Config config = new Config();
        if (!nonInteractive) {
            config.wizard();
        }
        config.save(file);
        return config;
    }

    private void wizard() {
        System.out.println("首次运行向导：直接回车使用默认值。");
        Scanner scanner = new Scanner(System.in);
        appPort = askInt(scanner, FIELD_LABELS.get("appPort"), appPort);
        nodePort = askInt(scanner, FIELD_LABELS.get("nodePort"), nodePort);
        System.out.print(FIELD_LABELS.get("dbPath") + " [" + dbPath + "]: ");
        String db = scanner.nextLine().trim();
        if (!db.isEmpty()) {
            dbPath = db;
        }
        System.out.print(FIELD_LABELS.get("networkName") + " [" + networkName + "]: ");
        String name = scanner.nextLine().trim();
        if (!name.isEmpty()) {
            networkName = name;
        }
        appTlsEnabled = askBool(scanner, FIELD_LABELS.get("appTlsEnabled"), appTlsEnabled);
    }

    private boolean askBool(Scanner scanner, String label, boolean defaultValue) {
        System.out.print(label + " [" + (defaultValue ? "y" : "n") + "]: ");
        String line = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
        if (line.isEmpty()) {
            return defaultValue;
        }
        return "y".equals(line) || "yes".equals(line) || "true".equals(line) || "1".equals(line);
    }

    private int askInt(Scanner scanner, String label, int defaultValue) {
        System.out.print(label + " [" + defaultValue + "]: ");
        String line = scanner.nextLine().trim();
        if (line.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("不是整数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    private void save(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.valueToTree(this);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }

    public JsonNode toJsonView() {
        return new ObjectMapper().valueToTree(this);
    }
}
