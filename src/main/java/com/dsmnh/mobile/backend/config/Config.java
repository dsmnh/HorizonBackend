package com.dsmnh.mobile.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    private static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static {
        FIELD_LABELS.put("appPort", "App 平面端口");
        FIELD_LABELS.put("nodePort", "节点平面端口");
        FIELD_LABELS.put("dbPath", "SQLite 文件路径");
        FIELD_LABELS.put("networkName", "网络名");
    }

    public static Config loadOrCreate(Path file, boolean nonInteractive) throws IOException {
        if (Files.exists(file)) {
            ObjectMapper mapper = new ObjectMapper();
            Config config = mapper.treeToValue(mapper.readTree(file.toFile()), Config.class);
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
