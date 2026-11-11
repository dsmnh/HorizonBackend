package com.dsmnh.mobile.backend.sub;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群组榜单（契约 1.6.0 §8.6、企划书 07）：节点上报落库，每日整包聚合，
 * 下行 rankings.apply 的载荷恒为完整 boards 快照。
 */
public final class RankingsService {
    private final ServerContext ctx;

    public RankingsService(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** 保存某节点最近一次上报的榜单。 */
    public void report(long nodeId, JsonNode boards) {
        ctx.db.update("INSERT OR REPLACE INTO rank_reports(node_id, boards, reported_at) VALUES(?,?,?)",
                nodeId, asArray(boards).toString(), now());
    }

    /**
     * 聚合全部节点上报为群组快照：同一 UUID 优先取统计主服（rank_home）的值，
     * 未登记主服时取各节点中的较大值；结果按 value 降序写入快照表。
     */
    public ArrayNode aggregate() {
        Map<String, Long> home = new HashMap<>();
        for (Map<String, Object> row : ctx.db.query("SELECT uuid, node_id FROM rank_home")) {
            home.put(String.valueOf(row.get("uuid")), ((Number) row.get("node_id")).longValue());
        }
        Map<String, BoardAcc> boards = new LinkedHashMap<>();
        for (Map<String, Object> row : ctx.db.query("SELECT node_id, boards FROM rank_reports")) {
            long nodeId = ((Number) row.get("node_id")).longValue();
            JsonNode reported = parse((String) row.get("boards"));
            if (!reported.isArray()) {
                continue;
            }
            for (JsonNode board : reported) {
                String id = board.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                BoardAcc acc = boards.computeIfAbsent(id,
                        key -> new BoardAcc(board.path("name").asText("")));
                JsonNode rows = board.path("rows");
                if (!rows.isArray()) {
                    continue;
                }
                for (JsonNode item : rows) {
                    String uuid = item.path("uuid").asText("");
                    if (uuid.isEmpty()) {
                        continue;
                    }
                    int priority = home.getOrDefault(uuid, -1L) == nodeId ? 1 : 0;
                    acc.accept(uuid, item, item.path("value").asDouble(), priority);
                }
            }
        }
        ArrayNode snapshot = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<String, BoardAcc> entry : boards.entrySet()) {
            ObjectNode board = snapshot.addObject();
            board.put("id", entry.getKey());
            board.put("name", entry.getValue().name);
            ArrayNode rows = board.putArray("rows");
            for (JsonNode item : entry.getValue().sortedRows()) {
                rows.add(item);
            }
        }
        ctx.db.update("INSERT INTO rankings_snapshots(boards, generated_at) VALUES(?,?)",
                snapshot.toString(), now());
        ctx.db.update("DELETE FROM rankings_snapshots WHERE id NOT IN "
                + "(SELECT id FROM rankings_snapshots ORDER BY id DESC LIMIT 7)");
        return snapshot;
    }

    /** 向所有 active 节点下发完整快照，返回下发节点数；离线节点入待执行队列。 */
    public int distribute(ArrayNode boards) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("boards", boards);
        int count = 0;
        for (Map<String, Object> row : ctx.db.query(
                "SELECT id FROM nodes WHERE status = 'active' ORDER BY id")) {
            ctx.opsStream.enqueue(((Number) row.get("id")).longValue(), "rankings.apply", payload);
            count++;
        }
        return count;
    }

    /** 每日任务：聚合最新上报并向所有节点分发。 */
    public int dailyAggregation() {
        return distribute(aggregate());
    }

    /** 最新群组快照；无快照时退回该节点最近上报，仍无则空数组。 */
    public ArrayNode latestBoards(long nodeId) {
        Optional<Map<String, Object>> snapshot = ctx.db.queryOne(
                "SELECT boards FROM rankings_snapshots ORDER BY id DESC LIMIT 1");
        if (snapshot.isPresent()) {
            return asArray(parse((String) snapshot.get().get("boards")));
        }
        Optional<Map<String, Object>> reported = ctx.db.queryOne(
                "SELECT boards FROM rank_reports WHERE node_id = ?", nodeId);
        if (reported.isPresent()) {
            return asArray(parse((String) reported.get().get("boards")));
        }
        return JsonNodeFactory.instance.arrayNode();
    }

    private ArrayNode asArray(JsonNode node) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                out.add(item);
            }
        }
        return out;
    }

    private JsonNode parse(String text) {
        try {
            return ctx.mapper.readTree(text);
        } catch (Exception e) {
            return JsonNodeFactory.instance.arrayNode();
        }
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static final class BoardAcc {
        final String name;
        final Map<String, Candidate> rows = new HashMap<>();

        BoardAcc(String name) {
            this.name = name;
        }

        void accept(String uuid, JsonNode row, double value, int priority) {
            Candidate current = rows.get(uuid);
            if (current == null || priority > current.priority
                    || (priority == current.priority && value > current.value)) {
                rows.put(uuid, new Candidate(row, value, priority));
            }
        }

        List<JsonNode> sortedRows() {
            List<Candidate> list = new ArrayList<>(rows.values());
            list.sort(Comparator.comparingDouble((Candidate c) -> c.value).reversed());
            List<JsonNode> out = new ArrayList<>();
            for (Candidate c : list) {
                out.add(c.row);
            }
            return out;
        }
    }

    private record Candidate(JsonNode row, double value, int priority) {}
}
