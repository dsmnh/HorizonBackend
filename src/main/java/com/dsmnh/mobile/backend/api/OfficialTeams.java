package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Plane;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 官方队伍投影（契约 1.6.5 §8.8）：接收节点上报的 GTNHLib 队伍快照，维护逐世界投影，
 * 提供中央队伍与官方队伍的人工映射。本类只读官方队伍，不产生任何官方写入。
 */
public final class OfficialTeams {
    /** 投影超过该秒数未刷新即视为过期（stale），与节点心跳 25 秒量级对齐。 */
    public static final long STALE_SEC = 180;
    /** 能力位：节点具备 GTNHLib 官方队伍适配器。 */
    public static final String CAP_GTNH = "gtnh";
    private static final String ROLE_OWNER = "owner";
    private static final String ROLE_OFFICER = "officer";

    private final ServerContext ctx;

    public OfficialTeams(ServerContext ctx) {
        this.ctx = ctx;
    }

    public Map<String, Dispatcher.OpHandler> nodeOps() {
        Map<String, Dispatcher.OpHandler> ops = new HashMap<>();
        ops.put("team.official.report", this::report);
        return ops;
    }

    public Map<String, Dispatcher.OpHandler> appOps() {
        Map<String, Dispatcher.OpHandler> ops = new HashMap<>();
        ops.put("team.official", this::official);
        ops.put("team.map", this::map);
        ops.put("team.map.set", this::mapSet);
        ops.put("team.map.clear", this::mapClear);
        return ops;
    }

    // ---------- 节点上报 ----------

    /** 官方队伍快照上报：按 (nodeId, worldRef) 的 gen/seq 守卫后整体替换投影。 */
    public ObjectNode report(Connection conn, JsonNode p) {
        long nodeId = conn.nodeId();
        if (conn.serverPlane() != Plane.NODE || nodeId < 0) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        if (!officialMode(nodeId)) {
            // 未声明 gtnh 的节点不得上报官方队伍（契约 §8.8 能力位）。
            throw new OpException(ErrorCodes.FORBIDDEN, "cap");
        }
        String worldRef = Dispatcher.text(p, "worldRef");
        long gen = Dispatcher.longValue(p, "gen");
        long seq = Dispatcher.longValue(p, "seq");
        boolean ready = p.hasNonNull("ready") && p.get("ready").asBoolean();
        JsonNode teams = p.path("teams");
        if (!teams.isArray()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "teams");
        }
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> state = ctx.db.queryOne(
                "SELECT gen, seq, ready FROM official_world_state WHERE node_id = ? AND world_ref = ?",
                nodeId, worldRef);
        if (state.isPresent()) {
            long lastGen = ((Number) state.get().get("gen")).longValue();
            long lastSeq = ((Number) state.get().get("seq")).longValue();
            if (gen < lastGen || (gen == lastGen && seq <= lastSeq)) {
                // 重复、乱序、低代次整帧丢弃：不写投影，也不删既有投影。
                return result(false, "stale", lastGen, lastSeq);
            }
        }
        if (state.isPresent()) {
            ctx.db.update("UPDATE official_world_state SET gen = ?, seq = ?, ready = ?, updated_at = ? "
                    + "WHERE node_id = ? AND world_ref = ?", gen, seq, ready ? 1 : 0, now, nodeId, worldRef);
        } else {
            ctx.db.update("INSERT INTO official_world_state(node_id, world_ref, gen, seq, ready, updated_at) "
                    + "VALUES(?,?,?,?,?,?)", nodeId, worldRef, gen, seq, ready ? 1 : 0, now);
        }
        if (!ready) {
            // 世界未加载完成或采集失败：保留既有投影，仅推进代次与序号。
            return result(true, null, gen, seq);
        }
        Map<String, String> before = fingerprints(nodeId, worldRef);
        replaceProjection(nodeId, worldRef, teams, gen, seq, now);
        Map<String, String> after = fingerprints(nodeId, worldRef);
        notifyChanged(nodeId, worldRef, before, after);
        return result(true, null, gen, seq);
    }

    private void replaceProjection(long nodeId, String worldRef, JsonNode teams,
                                   long gen, long seq, long now) {
        ctx.db.update("DELETE FROM official_team_snapshots WHERE node_id = ? AND world_ref = ?",
                nodeId, worldRef);
        ctx.db.update("DELETE FROM official_team_members WHERE node_id = ? AND world_ref = ?",
                nodeId, worldRef);
        for (JsonNode t : teams) {
            String officialTeamId = t.hasNonNull("officialTeamId") ? t.get("officialTeamId").asText() : "";
            if (officialTeamId.isEmpty()) {
                continue;
            }
            String name = t.hasNonNull("name") ? t.get("name").asText() : "";
            ArrayNode owners = distinct(t.path("owners"));
            ArrayNode officers = distinct(t.path("officers"));
            ArrayNode members = distinct(t.path("members"));
            ctx.db.update("INSERT OR REPLACE INTO official_team_snapshots(node_id, world_ref, "
                            + "official_team_id, name, owners, officers, members, gen, seq, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    nodeId, worldRef, officialTeamId, name, owners.toString(), officers.toString(),
                    members.toString(), gen, seq, now);
            Map<String, String> roles = new LinkedHashMap<>();
            for (JsonNode u : members) {
                roles.put(u.asText(), "member");
            }
            for (JsonNode u : officers) {
                roles.put(u.asText(), ROLE_OFFICER);
            }
            // 多 owner 合法：owner 覆盖 officer，但同一 UUID 仍保留在 members 中（契约 §8.8）。
            for (JsonNode u : owners) {
                roles.put(u.asText(), ROLE_OWNER);
            }
            for (Map.Entry<String, String> e : roles.entrySet()) {
                ctx.db.update("INSERT OR REPLACE INTO official_team_members(node_id, world_ref, "
                                + "official_team_id, uuid, role) VALUES(?,?,?,?,?)",
                        nodeId, worldRef, officialTeamId, e.getKey(), e.getValue());
            }
        }
    }

    private ArrayNode distinct(JsonNode arr) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual() && !n.asText().isEmpty()) {
                    seen.add(n.asText());
                }
            }
        }
        seen.forEach(out::add);
        return out;
    }

    private Map<String, String> fingerprints(long nodeId, String worldRef) {
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT official_team_id, name, owners, officers, members FROM official_team_snapshots "
                        + "WHERE node_id = ? AND world_ref = ?", nodeId, worldRef)) {
            out.put((String) row.get("official_team_id"),
                    row.get("name") + "|" + row.get("owners") + "|" + row.get("officers") + "|" + row.get("members"));
        }
        return out;
    }

    /** 已确认映射上投影有变化的中央队伍推 officialUpdated（不携带成员名单）。 */
    private void notifyChanged(long nodeId, String worldRef,
                               Map<String, String> before, Map<String, String> after) {
        Set<String> changed = new HashSet<>();
        for (String key : before.keySet()) {
            if (!key.equals(after.get(key)) || !after.containsKey(key)) {
                changed.add(key);
            }
        }
        for (String key : after.keySet()) {
            if (!key.equals(before.get(key))) {
                changed.add(key);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        for (Map<String, Object> link : ctx.db.query(
                "SELECT team_id, official_team_id FROM official_team_links "
                        + "WHERE node_id = ? AND world_ref = ?", nodeId, worldRef)) {
            if (!changed.contains((String) link.get("official_team_id"))) {
                continue;
            }
            long teamId = ((Number) link.get("team_id")).longValue();
            ObjectNode event = JsonNodeFactory.instance.objectNode();
            event.put("teamId", teamId);
            event.put("nodeId", nodeId);
            event.put("worldRef", worldRef);
            ctx.stream.publish(memberUuids(teamId), "officialUpdated", event);
        }
    }

    private ObjectNode result(boolean accepted, String reason, long gen, long seq) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("accepted", accepted);
        if (reason != null) {
            out.put("reason", reason);
        }
        out.put("gen", gen);
        out.put("seq", seq);
        return out;
    }

    // ---------- App 读取与映射 ----------

    public ObjectNode official(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long teamId = Dispatcher.longValue(p, "teamId");
        requireTeamAccess(conn, uuid, teamId);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode links = out.putArray("links");
        ArrayNode teams = out.putArray("teams");
        long now = System.currentTimeMillis() / 1000;
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, official_team_id, created_at FROM official_team_links "
                        + "WHERE team_id = ? ORDER BY node_id, world_ref", teamId)) {
            long nodeId = ((Number) row.get("node_id")).longValue();
            String worldRef = (String) row.get("world_ref");
            String officialTeamId = (String) row.get("official_team_id");
            long gen = 0;
            long seq = 0;
            boolean ready = false;
            long updatedAt = 0;
            Optional<Map<String, Object>> state = ctx.db.queryOne(
                    "SELECT gen, seq, ready, updated_at FROM official_world_state "
                            + "WHERE node_id = ? AND world_ref = ?", nodeId, worldRef);
            if (state.isPresent()) {
                gen = ((Number) state.get().get("gen")).longValue();
                seq = ((Number) state.get().get("seq")).longValue();
                ready = ((Number) state.get().get("ready")).longValue() != 0;
                updatedAt = ((Number) state.get().get("updated_at")).longValue();
            }
            // 过期判定：世界未就绪、从未上报、上报太久，或节点已不在役。
            boolean stale = !ready || updatedAt <= 0 || now - updatedAt > STALE_SEC || !nodeActive(nodeId);
            ObjectNode link = links.addObject();
            link.put("nodeId", nodeId);
            link.put("worldRef", worldRef);
            link.put("officialTeamId", officialTeamId);
            link.put("gen", gen);
            link.put("updatedAt", updatedAt);
            link.put("stale", stale);
            ObjectNode item = teams.addObject();
            item.put("nodeId", nodeId);
            item.put("worldRef", worldRef);
            item.put("officialTeamId", officialTeamId);
            item.put("gen", gen);
            item.put("seq", seq);
            item.put("ready", ready);
            item.put("stale", stale);
            Optional<Map<String, Object>> snap = ctx.db.queryOne(
                    "SELECT name, owners, officers, members FROM official_team_snapshots "
                            + "WHERE node_id = ? AND world_ref = ? AND official_team_id = ?",
                    nodeId, worldRef, officialTeamId);
            if (snap.isPresent()) {
                item.put("name", (String) snap.get().get("name"));
                item.set("owners", parseArray((String) snap.get().get("owners")));
                item.set("officers", parseArray((String) snap.get().get("officers")));
                item.set("members", parseArray((String) snap.get().get("members")));
            } else {
                // 映射指向的官方队伍已消失：返回空名单并由 stale 提示客户端重取。
                item.put("name", "");
                item.set("owners", JsonNodeFactory.instance.arrayNode());
                item.set("officers", JsonNodeFactory.instance.arrayNode());
                item.set("members", JsonNodeFactory.instance.arrayNode());
            }
        }
        return out;
    }

    public ObjectNode map(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long teamId = Dispatcher.longValue(p, "teamId");
        requireTeamAccess(conn, uuid, teamId);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode links = out.putArray("links");
        Set<String> linked = new HashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, official_team_id, created_at FROM official_team_links "
                        + "WHERE team_id = ?", teamId)) {
            ObjectNode item = links.addObject();
            item.put("nodeId", ((Number) row.get("node_id")).longValue());
            item.put("worldRef", (String) row.get("world_ref"));
            item.put("officialTeamId", (String) row.get("official_team_id"));
            item.put("createdAt", ((Number) row.get("created_at")).longValue());
            linked.add(row.get("node_id") + "/" + row.get("world_ref") + "/" + row.get("official_team_id"));
        }
        ArrayNode candidates = out.putArray("candidates");
        List<String> uuids = new ArrayList<>(memberUuids(teamId));
        String teamName = ctx.db.queryOne("SELECT name FROM teams WHERE id = ?", teamId)
                .map(row -> (String) row.get("name")).orElse("");
        Map<String, Integer> overlap = new HashMap<>();
        if (!uuids.isEmpty()) {
            for (Map<String, Object> row : ctx.db.query(
                    "SELECT node_id, world_ref, official_team_id, COUNT(*) AS overlap "
                            + "FROM official_team_members WHERE uuid IN ("
                            + placeholders(uuids.size()) + ") "
                            + "GROUP BY node_id, world_ref, official_team_id", uuids.toArray())) {
                overlap.put(row.get("node_id") + "/" + row.get("world_ref") + "/" + row.get("official_team_id"),
                        ((Number) row.get("overlap")).intValue());
            }
        }
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, official_team_id, name FROM official_team_snapshots "
                        + "ORDER BY node_id, world_ref, official_team_id")) {
            String key = row.get("node_id") + "/" + row.get("world_ref") + "/" + row.get("official_team_id");
            if (linked.contains(key)) {
                continue;
            }
            int hits = overlap.getOrDefault(key, 0);
            String reason = hits > 0 ? "uuid-overlap" : (teamName.equals(row.get("name")) ? "name-similar" : null);
            if (reason == null) {
                continue;
            }
            ObjectNode item = candidates.addObject();
            item.put("nodeId", ((Number) row.get("node_id")).longValue());
            item.put("worldRef", (String) row.get("world_ref"));
            item.put("officialTeamId", (String) row.get("official_team_id"));
            item.put("name", (String) row.get("name"));
            item.put("reason", reason);
        }
        return out;
    }

    public ObjectNode mapSet(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        requireSuper(conn);
        long teamId = Dispatcher.longValue(p, "teamId");
        long nodeId = Dispatcher.longValue(p, "nodeId");
        String worldRef = Dispatcher.text(p, "worldRef");
        String officialTeamId = Dispatcher.text(p, "officialTeamId");
        if (ctx.db.queryOne("SELECT id FROM teams WHERE id = ?", teamId).isEmpty()) {
            throw new OpException(ErrorCodes.TEAM_NOT_FOUND);
        }
        if (ctx.db.queryOne("SELECT official_team_id FROM official_team_snapshots "
                + "WHERE node_id = ? AND world_ref = ? AND official_team_id = ?",
                nodeId, worldRef, officialTeamId).isEmpty()) {
            // 映射只能指向已上报的官方队伍，避免把笔误固化成映射。
            throw new OpException(ErrorCodes.MAP_CONFLICT, "officialTeamId");
        }
        Optional<Map<String, Object>> sameSlot = ctx.db.queryOne(
                "SELECT official_team_id FROM official_team_links "
                        + "WHERE team_id = ? AND node_id = ? AND world_ref = ?", teamId, nodeId, worldRef);
        if (sameSlot.isPresent()
                && !officialTeamId.equals((String) sameSlot.get().get("official_team_id"))) {
            throw new OpException(ErrorCodes.MAP_CONFLICT, "slot");
        }
        Optional<Map<String, Object>> taken = ctx.db.queryOne(
                "SELECT team_id FROM official_team_links "
                        + "WHERE node_id = ? AND world_ref = ? AND official_team_id = ?",
                nodeId, worldRef, officialTeamId);
        if (taken.isPresent() && ((Number) taken.get().get("team_id")).longValue() != teamId) {
            throw new OpException(ErrorCodes.MAP_CONFLICT, "officialTeamId");
        }
        ctx.db.update("INSERT OR REPLACE INTO official_team_links(team_id, node_id, world_ref, "
                        + "official_team_id, created_by, created_at) VALUES(?,?,?,?,?,?)",
                teamId, nodeId, worldRef, officialTeamId, uuid, System.currentTimeMillis() / 1000);
        publishMapChanged(teamId, nodeId, worldRef);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode mapClear(Connection conn, JsonNode p) {
        identity(conn, p);
        requireSuper(conn);
        long teamId = Dispatcher.longValue(p, "teamId");
        long nodeId = Dispatcher.longValue(p, "nodeId");
        String worldRef = Dispatcher.text(p, "worldRef");
        ctx.db.update("DELETE FROM official_team_links WHERE team_id = ? AND node_id = ? AND world_ref = ?",
                teamId, nodeId, worldRef);
        publishMapChanged(teamId, nodeId, worldRef);
        return JsonNodeFactory.instance.objectNode();
    }

    private void publishMapChanged(long teamId, long nodeId, String worldRef) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("teamId", teamId);
        event.put("nodeId", nodeId);
        event.put("worldRef", worldRef);
        ctx.stream.publish(memberUuids(teamId), "officialMapChanged", event);
    }

    // ---------- 共用守卫 ----------

    /** 节点是否处于官方队伍模式（能力位 gtnh）。 */
    public boolean officialMode(long nodeId) {
        return ctx.db.queryOne("SELECT official_mode FROM nodes WHERE id = ?", nodeId)
                .map(row -> ((Number) row.get("official_mode")).intValue() != 0)
                .orElse(false);
    }

    /**
     * 目标身份是否「仅存在于官方队伍」：该 UUID 在至少一个官方模式节点上属于某官方队伍，
     * 且网络内没有非官方模式的活跃节点可承接中央队伍写入。
     */
    public static boolean officialOnly(ServerContext ctx, String uuid) {
        boolean onOfficial = !ctx.db.query(
                "SELECT 1 FROM official_team_members m JOIN nodes n ON n.id = m.node_id "
                        + "WHERE m.uuid = ? AND n.official_mode = 1 AND n.status = 'active' LIMIT 1", uuid)
                .isEmpty();
        if (!onOfficial) {
            return false;
        }
        return ctx.db.query(
                "SELECT 1 FROM nodes WHERE status = 'active' AND official_mode = 0 LIMIT 1").isEmpty();
    }

    private boolean nodeActive(long nodeId) {
        return ctx.db.queryOne("SELECT status FROM nodes WHERE id = ?", nodeId)
                .map(row -> "active".equals(row.get("status"))).orElse(false);
    }

    private void requireSuper(Connection conn) {
        String role = ctx.auth.roleOf(conn.accountId());
        if (!"owner".equals(role) && !"super".equals(role)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private void requireTeamAccess(Connection conn, String uuid, long teamId) {
        String role = ctx.auth.roleOf(conn.accountId());
        if ("owner".equals(role) || "super".equals(role)) {
            return;
        }
        if (ctx.db.queryOne("SELECT 1 FROM team_members WHERE team_id = ? AND uuid = ?", teamId, uuid)
                .isEmpty()) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private String identity(Connection conn, JsonNode p) {
        String uuid = Dispatcher.text(p, "uuid");
        if (!ctx.auth.isUuidBoundToAccount(uuid, conn.accountId())) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        return uuid;
    }

    private Set<String> memberUuids(long teamId) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid FROM team_members WHERE team_id = ?", teamId)) {
            out.add((String) row.get("uuid"));
        }
        return out;
    }

    private ArrayNode parseArray(String json) {
        try {
            JsonNode n = ctx.mapper.readTree(json == null ? "[]" : json);
            return n.isArray() ? (ArrayNode) n : JsonNodeFactory.instance.arrayNode();
        } catch (Exception e) {
            return JsonNodeFactory.instance.arrayNode();
        }
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }
}
