package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 官方队伍映射的只读报告（开发计划阶段 5）：把中央队伍、各服官方队伍投影与人工映射
 * 摆在一起，逐项列出成员差异、未映射项与待核对项。
 *
 * <p>本类只读取数据库，不写任何表，也不产生下行指令；输出供维护者在灰度前人工确认，
 * 不参与运行期判定。任务计数取决于 TaskNH 存档，不在后端可见范围内，报告中留空。</p>
 */
public final class OfficialMapReport {
    /** 成员差异超过该比例即列入待核对，避免大队里个别人离线造成噪声。 */
    private static final int MAX_LISTED = 50;

    private final ServerContext ctx;

    public OfficialMapReport(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** 纯文本报告，供控制台直接打印。 */
    public String render() {
        ObjectNode data = build();
        StringBuilder sb = new StringBuilder();
        sb.append("官方队伍映射只读报告\n");
        sb.append("中央队伍 ").append(data.path("teams").size())
                .append(" 支，已映射 ").append(data.path("links").size())
                .append(" 条，投影队伍 ").append(data.path("officials").size()).append(" 支\n");

        List<ObjectNode> links = list(data.path("links"));
        if (links.isEmpty()) {
            sb.append("\n[已确认映射] 无\n");
        } else {
            sb.append("\n[已确认映射]\n");
            for (ObjectNode link : links) {
                sb.append("- ").append(text(link, "teamName")).append("(#").append(link.path("teamId").asLong())
                        .append(") → 节点 ").append(link.path("nodeId").asLong())
                        .append(" / ").append(text(link, "worldRef"))
                        .append(" / ").append(text(link, "officialName"))
                        .append("(").append(text(link, "officialTeamId")).append(")")
                        .append(" 状态=").append(text(link, "state"));
                int centralOnly = link.path("centralOnly").size();
                int officialOnly = link.path("officialOnly").size();
                if (centralOnly > 0 || officialOnly > 0) {
                    sb.append(" 成员差异=中央独有 ").append(centralOnly)
                            .append(" / 官方独有 ").append(officialOnly);
                }
                sb.append('\n');
            }
        }

        sb.append("\n[待核对项] ").append(data.path("issues").size()).append(" 条\n");
        for (ObjectNode issue : list(data.path("issues"))) {
            sb.append("- ").append(text(issue, "kind")).append("：").append(text(issue, "detail")).append('\n');
        }

        sb.append("\n[未映射的中央队伍]\n");
        List<ObjectNode> unmapped = list(data.path("unmappedTeams"));
        if (unmapped.isEmpty()) {
            sb.append("- 无\n");
        } else {
            for (ObjectNode row : unmapped) {
                sb.append("- ").append(text(row, "teamName")).append("(#").append(row.path("teamId").asLong())
                        .append(") 成员 ").append(row.path("members").size()).append(" 人\n");
            }
        }

        sb.append("\n[未被任何中央队伍引用的官方队伍]\n");
        List<ObjectNode> orphans = list(data.path("orphanOfficials"));
        if (orphans.isEmpty()) {
            sb.append("- 无\n");
        } else {
            for (ObjectNode row : orphans) {
                sb.append("- 节点 ").append(row.path("nodeId").asLong())
                        .append(" / ").append(text(row, "worldRef"))
                        .append(" / ").append(text(row, "name"))
                        .append("(").append(text(row, "officialTeamId")).append(")\n");
            }
        }

        sb.append("\n[投影新鲜度]\n");
        List<ObjectNode> worlds = list(data.path("worlds"));
        if (worlds.isEmpty()) {
            sb.append("- 尚无节点上报官方队伍投影\n");
        } else {
            for (ObjectNode row : worlds) {
                sb.append("- 节点 ").append(row.path("nodeId").asLong())
                        .append(" / ").append(text(row, "worldRef"))
                        .append(" 代次=").append(row.path("gen").asLong())
                        .append(" 序号=").append(row.path("seq").asLong())
                        .append(" 就绪=").append(row.path("ready").asBoolean() ? "是" : "否")
                        .append(" 延迟=").append(row.path("ageSec").asLong()).append(" 秒\n");
            }
        }

        sb.append("\n[官方节点被作废的旧下行指令] ")
                .append(data.path("abandonedWrites").asInt()).append(" 条\n");

        sb.append("\n[候选建议（按成员 UUID 重合）]\n");
        List<ObjectNode> candidates = list(data.path("candidates"));
        if (candidates.isEmpty()) {
            sb.append("- 无\n");
        } else {
            for (ObjectNode row : candidates) {
                sb.append("- ").append(text(row, "teamName")).append("(#").append(row.path("teamId").asLong())
                        .append(") ⇢ 节点 ").append(row.path("nodeId").asLong())
                        .append(" / ").append(text(row, "worldRef"))
                        .append(" / ").append(text(row, "name"))
                        .append("(").append(text(row, "officialTeamId")).append(")")
                        .append(" 重合 ").append(row.path("overlap").asInt()).append(" 人\n");
            }
        }
        return sb.toString();
    }

    /** 结构化报告，供测试与其他调用方断言。 */
    public ObjectNode build() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode teams = out.putArray("teams");
        ArrayNode links = out.putArray("links");
        ArrayNode officials = out.putArray("officials");
        ArrayNode issues = out.putArray("issues");
        ArrayNode unmapped = out.putArray("unmappedTeams");
        ArrayNode orphans = out.putArray("orphanOfficials");
        ArrayNode candidates = out.putArray("candidates");
        ArrayNode worlds = out.putArray("worlds");

        long now = System.currentTimeMillis() / 1000;
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, gen, seq, ready, updated_at FROM official_world_state "
                        + "ORDER BY node_id, world_ref")) {
            ObjectNode node = worlds.addObject();
            node.put("nodeId", ((Number) row.get("node_id")).longValue());
            node.put("worldRef", (String) row.get("world_ref"));
            node.put("gen", ((Number) row.get("gen")).longValue());
            node.put("seq", ((Number) row.get("seq")).longValue());
            node.put("ready", ((Number) row.get("ready")).intValue() != 0);
            long updatedAt = row.get("updated_at") == null ? 0 : ((Number) row.get("updated_at")).longValue();
            node.put("ageSec", updatedAt <= 0 ? -1 : Math.max(0, now - updatedAt));
        }
        out.put("abandonedWrites", ctx.db.query(
                "SELECT 1 FROM node_ops WHERE status = 'abandoned' AND type IN ('team.apply','eu.join')").size());

        Map<Long, String> teamNames = new LinkedHashMap<>();
        Map<Long, Set<String>> teamMembers = new LinkedHashMap<>();
        for (Map<String, Object> row : ctx.db.query("SELECT id, name FROM teams ORDER BY id")) {
            long id = ((Number) row.get("id")).longValue();
            teamNames.put(id, row.get("name") == null ? "" : (String) row.get("name"));
            teamMembers.put(id, new LinkedHashSet<>());
        }
        for (Map<String, Object> row : ctx.db.query("SELECT team_id, uuid FROM team_members ORDER BY team_id")) {
            Set<String> set = teamMembers.get(((Number) row.get("team_id")).longValue());
            if (set != null) {
                set.add((String) row.get("uuid"));
            }
        }
        for (Map.Entry<Long, String> e : teamNames.entrySet()) {
            ObjectNode node = teams.addObject();
            node.put("teamId", e.getKey());
            node.put("teamName", e.getValue());
            ArrayNode members = node.putArray("members");
            for (String uuid : teamMembers.get(e.getKey())) {
                members.add(uuid);
            }
        }

        // 投影队伍：按 (nodeId, worldRef, officialTeamId) 平铺，成员另查扁平表。
        Map<String, ObjectNode> officialIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, official_team_id, name, gen, seq FROM official_team_snapshots "
                        + "ORDER BY node_id, world_ref, official_team_id")) {
            ObjectNode node = officials.addObject();
            node.put("nodeId", ((Number) row.get("node_id")).longValue());
            node.put("worldRef", (String) row.get("world_ref"));
            node.put("officialTeamId", (String) row.get("official_team_id"));
            node.put("name", row.get("name") == null ? "" : (String) row.get("name"));
            node.put("gen", ((Number) row.get("gen")).longValue());
            node.put("seq", ((Number) row.get("seq")).longValue());
            node.putArray("members");
            officialIndex.put(key(node.path("nodeId").asLong(), node.path("worldRef").asText(),
                    node.path("officialTeamId").asText()), node);
        }
        for (Map<String, Object> row : ctx.db.query(
                "SELECT node_id, world_ref, official_team_id, uuid FROM official_team_members "
                        + "ORDER BY node_id, world_ref, official_team_id")) {
            ObjectNode node = officialIndex.get(key(((Number) row.get("node_id")).longValue(),
                    (String) row.get("world_ref"), (String) row.get("official_team_id")));
            if (node != null) {
                ((ArrayNode) node.path("members")).add((String) row.get("uuid"));
            }
        }

        Set<String> referenced = new LinkedHashSet<>();
        Set<Long> mappedTeams = new LinkedHashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT l.team_id, l.node_id, l.world_ref, l.official_team_id, l.created_at "
                        + "FROM official_team_links l ORDER BY l.team_id, l.node_id, l.world_ref")) {
            long teamId = ((Number) row.get("team_id")).longValue();
            long nodeId = ((Number) row.get("node_id")).longValue();
            String worldRef = (String) row.get("world_ref");
            String officialTeamId = (String) row.get("official_team_id");
            mappedTeams.add(teamId);
            referenced.add(key(nodeId, worldRef, officialTeamId));

            ObjectNode link = links.addObject();
            link.put("teamId", teamId);
            link.put("teamName", teamNames.getOrDefault(teamId, ""));
            link.put("nodeId", nodeId);
            link.put("worldRef", worldRef);
            link.put("officialTeamId", officialTeamId);
            ObjectNode official = officialIndex.get(key(nodeId, worldRef, officialTeamId));
            link.put("officialName", official == null ? "" : official.path("name").asText());
            link.put("state", official == null ? "missing" : "ok");
            Set<String> central = teamMembers.getOrDefault(teamId, new LinkedHashSet<>());
            Set<String> local = new LinkedHashSet<>();
            if (official != null) {
                for (var n : official.path("members")) {
                    local.add(n.asText());
                }
            }
            ArrayNode centralOnly = link.putArray("centralOnly");
            for (String uuid : central) {
                if (!local.contains(uuid)) {
                    centralOnly.add(uuid);
                }
            }
            ArrayNode officialOnly = link.putArray("officialOnly");
            for (String uuid : local) {
                if (!central.contains(uuid)) {
                    officialOnly.add(uuid);
                }
            }
            if (official == null) {
                issue(issues, "映射指向的官方队伍已不存在",
                        "中央队伍 " + teamNames.getOrDefault(teamId, Long.toString(teamId))
                                + "(#" + teamId + ") 在节点 " + nodeId + " / " + worldRef
                                + " 上的官方队伍 " + officialTeamId + " 未出现在最新投影中");
            } else if (centralOnly.size() > 0 || officialOnly.size() > 0) {
                issue(issues, "成员差异",
                        "中央队伍 " + teamNames.getOrDefault(teamId, Long.toString(teamId))
                                + "(#" + teamId + ") 与节点 " + nodeId + " / " + worldRef
                                + " 的官方队伍 " + officialTeamId + " 相差 "
                                + centralOnly.size() + " 名中央独有、" + officialOnly.size() + " 名官方独有成员");
            }
        }

        for (Map.Entry<Long, String> e : teamNames.entrySet()) {
            if (mappedTeams.contains(e.getKey())) {
                continue;
            }
            Set<String> members = teamMembers.getOrDefault(e.getKey(), new LinkedHashSet<>());
            if (members.isEmpty()) {
                continue;
            }
            ObjectNode row = unmapped.addObject();
            row.put("teamId", e.getKey());
            row.put("teamName", e.getValue());
            ArrayNode arr = row.putArray("members");
            for (String uuid : members) {
                arr.add(uuid);
            }
            issue(issues, "未映射的中央队伍",
                    e.getValue() + "(#" + e.getKey() + ") 有 " + members.size()
                            + " 名成员但没有官方队伍映射，灰度前需人工指定或保持只读");
        }

        for (Map.Entry<String, ObjectNode> e : officialIndex.entrySet()) {
            if (referenced.contains(e.getKey())) {
                continue;
            }
            ObjectNode node = orphans.addObject();
            node.put("nodeId", e.getValue().path("nodeId").asLong());
            node.put("worldRef", e.getValue().path("worldRef").asText());
            node.put("officialTeamId", e.getValue().path("officialTeamId").asText());
            node.put("name", e.getValue().path("name").asText());
        }

        // 候选：中央队伍成员与官方队伍成员的 UUID 重合数，仅作提示。
        Set<String> mappedOfficial = new LinkedHashSet<>(referenced);
        for (Map.Entry<Long, Set<String>> e : teamMembers.entrySet()) {
            if (mappedTeams.contains(e.getKey()) || e.getValue().isEmpty()) {
                continue;
            }
            List<String> uuids = new ArrayList<>(e.getValue());
            if (uuids.size() > MAX_LISTED) {
                uuids = uuids.subList(0, MAX_LISTED);
            }
            String placeholders = placeholders(uuids.size());
            List<Map<String, Object>> rows = ctx.db.query(
                    "SELECT node_id, world_ref, official_team_id, COUNT(*) AS overlap "
                            + "FROM official_team_members WHERE uuid IN (" + placeholders + ") "
                            + "GROUP BY node_id, world_ref, official_team_id ORDER BY overlap DESC",
                    uuids.toArray());
            for (Map<String, Object> row : rows) {
                long nodeId = ((Number) row.get("node_id")).longValue();
                String worldRef = (String) row.get("world_ref");
                String officialTeamId = (String) row.get("official_team_id");
                if (mappedOfficial.contains(key(nodeId, worldRef, officialTeamId))) {
                    continue;
                }
                ObjectNode official = officialIndex.get(key(nodeId, worldRef, officialTeamId));
                ObjectNode item = candidates.addObject();
                item.put("teamId", e.getKey());
                item.put("teamName", teamNames.getOrDefault(e.getKey(), ""));
                item.put("nodeId", nodeId);
                item.put("worldRef", worldRef);
                item.put("officialTeamId", officialTeamId);
                item.put("name", official == null ? "" : official.path("name").asText());
                item.put("overlap", ((Number) row.get("overlap")).intValue());
            }
        }

        out.put("generatedAt", System.currentTimeMillis() / 1000);
        return out;
    }

    private static void issue(ArrayNode issues, String kind, String detail) {
        ObjectNode node = issues.addObject();
        node.put("kind", kind);
        node.put("detail", detail);
    }

    private static String key(long nodeId, String worldRef, String officialTeamId) {
        return nodeId + "/" + worldRef + "/" + officialTeamId;
    }

    private static String text(ObjectNode node, String field) {
        return node.path(field).asText("");
    }

    private static List<ObjectNode> list(com.fasterxml.jackson.databind.JsonNode array) {
        List<ObjectNode> out = new ArrayList<>();
        for (var node : array) {
            if (node.isObject()) {
                out.add((ObjectNode) node);
            }
        }
        return out;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }
}
