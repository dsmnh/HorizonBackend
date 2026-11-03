package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Plane;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 队伍域操作（登记表 §4.2，两平面共用；节点平面以 actor 代 uuid）与 team 流全量帧构建。 */
public final class TeamOps {
    public static final int INVITE_TTL_SEC = 7 * 24 * 3600;
    public static final String ROLE_LEADER = "leader";
    public static final String ROLE_VICE = "vice";
    public static final String ROLE_MEMBER = "member";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ServerContext ctx;
    private final ScheduledExecutorService sweeper;

    public TeamOps(ServerContext ctx) {
        this.ctx = ctx;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepExpired, 60, 60, TimeUnit.SECONDS);
    }

    public void close() {
        sweeper.shutdownNow();
    }

    public Map<String, Dispatcher.OpHandler> ops() {
        record Entry(String name, Dispatcher.OpHandler handler) {}
        return Map.ofEntries(
                Map.entry("team.create", this::create),
                Map.entry("team.disband", this::disband),
                Map.entry("team.info", this::info),
                Map.entry("team.list", this::list),
                Map.entry("team.invite", this::invite),
                Map.entry("team.acceptInvite", this::acceptInvite),
                Map.entry("team.declineInvite", this::declineInvite),
                Map.entry("team.apply", this::apply),
                Map.entry("team.approveApply", this::approveApply),
                Map.entry("team.denyApply", this::denyApply),
                Map.entry("team.leave", this::leave),
                Map.entry("team.kick", this::kick),
                Map.entry("team.setRole", this::setRole),
                Map.entry("team.setJoinMode", this::setJoinMode),
                Map.entry("team.transfer", this::transfer));
    }

    // ---------- 操作实现 ----------

    private ObjectNode create(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String name = validName(p);
        if (teamIdOf(uuid).isPresent()) {
            throw new OpException(ErrorCodes.ALREADY_IN_TEAM);
        }
        if (!ctx.db.queryOne("SELECT id FROM teams WHERE name = ?", name).isEmpty()) {
            throw new OpException(ErrorCodes.TEAM_NAME_TAKEN);
        }
        long now = System.currentTimeMillis() / 1000;
        long teamId = ctx.db.insert(
                "INSERT INTO teams(name, join_mode, created_at) VALUES(?, 'invite', ?)", name, now);
        ctx.db.update("INSERT INTO team_members(team_id, uuid, role, joined_at) VALUES(?,?,?,?)",
                teamId, uuid, ROLE_LEADER, now);
        publish(Set.of(uuid), "memberJoined", teamId,
                node -> node.put("uuid", uuid).put("role", ROLE_LEADER));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("teamId", teamId);
        return out;
    }

    private ObjectNode disband(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_LEADER);
        Set<String> members = memberUuids(teamId);
        ctx.db.update("DELETE FROM team_members WHERE team_id = ?", teamId);
        ctx.db.update("DELETE FROM invites WHERE team_id = ?", teamId);
        ctx.db.update("DELETE FROM applies WHERE team_id = ?", teamId);
        ctx.db.update("DELETE FROM teams WHERE id = ?", teamId);
        publish(members, "disbanded", teamId, node -> node);
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode info(Connection conn, JsonNode p) {
        long teamId;
        if (p.hasNonNull("teamId") && p.get("teamId").canConvertToLong()) {
            teamId = p.get("teamId").asLong();
        } else if (p.hasNonNull("uuid")) {
            String uuid = identity(conn, p);
            teamId = requireTeamOf(conn, uuid, p);
        } else {
            throw new OpException(ErrorCodes.BAD_PARAMS, "teamId");
        }
        Map<String, Object> team = teamRow(teamId).orElseThrow(() -> new OpException(ErrorCodes.TEAM_NOT_FOUND));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("team", teamJson(teamId, team));
        return out;
    }

    private ObjectNode list(Connection conn, JsonNode p) {
        ArrayNode teams = JsonNodeFactory.instance.objectNode().putArray("teams");
        for (Map<String, Object> team : ctx.db.query(
                "SELECT id, name, join_mode, created_at FROM teams ORDER BY name")) {
            long teamId = ((Number) team.get("id")).longValue();
            long count = ctx.db.queryOne(
                    "SELECT COUNT(*) AS c FROM team_members WHERE team_id = ?", teamId)
                    .map(row -> ((Number) row.get("c")).longValue()).orElse(0L);
            ObjectNode item = teams.addObject();
            item.put("teamId", teamId);
            item.put("name", (String) team.get("name"));
            item.put("memberCount", count);
            item.put("joinMode", (String) team.get("join_mode"));
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("teams", teams);
        return out;
    }

    private ObjectNode invite(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = validUuid(p, "target");
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        if (teamIdOf(target).isPresent()) {
            throw new OpException(ErrorCodes.ALREADY_IN_TEAM);
        }
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> existing = ctx.db.queryOne(
                "SELECT id, expires_at FROM invites WHERE team_id = ? AND target_uuid = ? "
                        + "AND status = 'pending' AND expires_at > ?", teamId, target, now);
        if (existing.isPresent()) {
            return inviteResult(existing.get());
        }
        long expiresAt = now + INVITE_TTL_SEC;
        String teamName = teamName(teamId);
        long inviteId = ctx.db.insert(
                "INSERT INTO invites(team_id, from_uuid, target_uuid, status, created_at, expires_at) "
                        + "VALUES(?,?,?,'pending',?,?)", teamId, uuid, target, now, expiresAt);
        publish(Set.of(target), "inviteReceived", teamId, node -> node
                .put("inviteId", inviteId).put("from", uuid).put("teamId", teamId)
                .put("teamName", teamName).put("expiresAt", expiresAt));
        return inviteResult(Map.of("id", inviteId, "expires_at", expiresAt));
    }

    private ObjectNode inviteResult(Map<String, Object> row) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("inviteId", ((Number) row.get("id")).longValue());
        out.put("expiresAt", ((Number) row.get("expires_at")).longValue());
        return out;
    }

    private ObjectNode acceptInvite(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long inviteId = Dispatcher.longValue(p, "inviteId");
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> invite = ctx.db.queryOne(
                "SELECT * FROM invites WHERE id = ?", inviteId)
                .orElseThrow(() -> new OpException(ErrorCodes.NO_INVITE));
        if (!uuid.equals(invite.get("target_uuid")) || !"pending".equals(invite.get("status"))) {
            throw new OpException(ErrorCodes.NO_INVITE);
        }
        long teamId = ((Number) invite.get("team_id")).longValue();
        if (teamRow(teamId).isEmpty()) {
            throw new OpException(ErrorCodes.NO_INVITE);
        }
        if (((Number) invite.get("expires_at")).longValue() <= now) {
            ctx.db.update("UPDATE invites SET status = 'expired' WHERE id = ?", inviteId);
            publish(Set.of(uuid), "inviteResolved", teamId, node -> node
                    .put("inviteId", inviteId).put("reason", "expired"));
            throw new OpException(ErrorCodes.INVITE_EXPIRED);
        }
        if (teamIdOf(uuid).isPresent()) {
            throw new OpException(ErrorCodes.ALREADY_IN_TEAM);
        }
        joinMember(teamId, uuid, ROLE_MEMBER);
        ctx.db.update("UPDATE invites SET status = 'accepted' WHERE id = ?", inviteId);
        publish(Set.of(uuid), "inviteResolved", teamId, node -> node
                .put("inviteId", inviteId).put("reason", "accepted"));
        publishMemberJoined(teamId, uuid);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("teamId", teamId);
        return out;
    }

    private ObjectNode declineInvite(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long inviteId = Dispatcher.longValue(p, "inviteId");
        Map<String, Object> invite = ctx.db.queryOne(
                "SELECT * FROM invites WHERE id = ?", inviteId)
                .orElseThrow(() -> new OpException(ErrorCodes.NO_INVITE));
        if (!uuid.equals(invite.get("target_uuid")) || !"pending".equals(invite.get("status"))) {
            throw new OpException(ErrorCodes.NO_INVITE);
        }
        ctx.db.update("UPDATE invites SET status = 'declined' WHERE id = ?", inviteId);
        long teamId = ((Number) invite.get("team_id")).longValue();
        publish(Set.of(uuid), "inviteResolved", teamId, node -> node
                .put("inviteId", inviteId).put("reason", "declined"));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode apply(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long teamId = Dispatcher.longValue(p, "teamId");
        teamRow(teamId).orElseThrow(() -> new OpException(ErrorCodes.TEAM_NOT_FOUND));
        String joinMode = joinMode(teamId);
        if (!"open".equals(joinMode)) {
            throw new OpException(ErrorCodes.TEAM_NOT_OPEN);
        }
        if (teamIdOf(uuid).isPresent()) {
            throw new OpException(ErrorCodes.ALREADY_IN_TEAM);
        }
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> existing = ctx.db.queryOne(
                "SELECT id, expires_at FROM applies WHERE team_id = ? AND uuid = ? "
                        + "AND status = 'pending' AND expires_at > ?", teamId, uuid, now);
        if (existing.isPresent()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("applyId", ((Number) existing.get().get("id")).longValue());
            out.put("expiresAt", ((Number) existing.get().get("expires_at")).longValue());
            return out;
        }
        long expiresAt = now + INVITE_TTL_SEC;
        long applyId = ctx.db.insert(
                "INSERT INTO applies(team_id, uuid, status, created_at, expires_at) "
                        + "VALUES(?,?,'pending',?,?)", teamId, uuid, now, expiresAt);
        publish(leaderRecipients(teamId), "applyReceived", teamId, node -> node
                .put("applyId", applyId).put("uuid", uuid).put("teamId", teamId)
                .put("expiresAt", expiresAt));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("applyId", applyId);
        out.put("expiresAt", expiresAt);
        return out;
    }

    private ObjectNode approveApply(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long applyId = Dispatcher.longValue(p, "applyId");
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> applyRow = ctx.db.queryOne("SELECT * FROM applies WHERE id = ?", applyId)
                .orElseThrow(() -> new OpException(ErrorCodes.NO_APPLY));
        if (((Number) applyRow.get("team_id")).longValue() != teamId
                || !"pending".equals(applyRow.get("status"))) {
            throw new OpException(ErrorCodes.NO_APPLY);
        }
        String applicant = (String) applyRow.get("uuid");
        if (((Number) applyRow.get("expires_at")).longValue() <= now) {
            ctx.db.update("UPDATE applies SET status = 'expired' WHERE id = ?", applyId);
            publish(leaderRecipients(teamId), "applyResolved", teamId, node -> node
                    .put("applyId", applyId).put("reason", "expired"));
            throw new OpException(ErrorCodes.APPLY_EXPIRED);
        }
        if (teamIdOf(applicant).isPresent()) {
            ctx.db.update("UPDATE applies SET status = 'denied' WHERE id = ?", applyId);
            publish(leaderRecipients(teamId), "applyResolved", teamId, node -> node
                    .put("applyId", applyId).put("reason", "denied"));
            throw new OpException(ErrorCodes.ALREADY_IN_TEAM);
        }
        joinMember(teamId, applicant, ROLE_MEMBER);
        ctx.db.update("UPDATE applies SET status = 'approved' WHERE id = ?", applyId);
        publish(leaderRecipients(teamId), "applyResolved", teamId, node -> node
                .put("applyId", applyId).put("reason", "approved"));
        publishMemberJoined(teamId, applicant);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("teamId", teamId);
        return out;
    }

    private ObjectNode denyApply(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long applyId = Dispatcher.longValue(p, "applyId");
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        Map<String, Object> applyRow = ctx.db.queryOne("SELECT * FROM applies WHERE id = ?", applyId)
                .orElseThrow(() -> new OpException(ErrorCodes.NO_APPLY));
        if (((Number) applyRow.get("team_id")).longValue() != teamId
                || !"pending".equals(applyRow.get("status"))) {
            throw new OpException(ErrorCodes.NO_APPLY);
        }
        ctx.db.update("UPDATE applies SET status = 'denied' WHERE id = ?", applyId);
        publish(leaderRecipients(teamId), "applyResolved", teamId, node -> node
                .put("applyId", applyId).put("reason", "denied"));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode leave(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_MEMBER);
        if (ROLE_LEADER.equals(roleOf(teamId, uuid))) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ctx.db.update("DELETE FROM team_members WHERE team_id = ? AND uuid = ?", teamId, uuid);
        Set<String> recipients = memberUuids(teamId);
        recipients.add(uuid);
        publish(recipients, "memberLeft", teamId, node -> node
                .put("uuid", uuid).put("reason", "left"));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode kick(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = validUuid(p, "target");
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        String targetRole = roleOf(teamId, target);
        if (targetRole == null) {
            throw new OpException(ErrorCodes.NOT_IN_TEAM);
        }
        if (ROLE_LEADER.equals(targetRole)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ctx.db.update("DELETE FROM team_members WHERE team_id = ? AND uuid = ?", teamId, target);
        Set<String> recipients = memberUuids(teamId);
        recipients.add(target);
        publish(recipients, "memberLeft", teamId, node -> node
                .put("uuid", target).put("reason", "kicked"));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode setRole(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = validUuid(p, "target");
        String role = Dispatcher.text(p, "role");
        if (!ROLE_MEMBER.equals(role) && !ROLE_VICE.equals(role)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "role");
        }
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        String targetRole = roleOf(teamId, target);
        if (targetRole == null) {
            throw new OpException(ErrorCodes.NOT_IN_TEAM);
        }
        if (ROLE_LEADER.equals(targetRole)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ctx.db.update("UPDATE team_members SET role = ? WHERE team_id = ? AND uuid = ?",
                role, teamId, target);
        publish(memberUuids(teamId), "roleChanged", teamId, node -> node
                .put("uuid", target).put("role", role));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode setJoinMode(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String mode = Dispatcher.text(p, "mode");
        if (!"invite".equals(mode) && !"open".equals(mode)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "mode");
        }
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_VICE);
        ctx.db.update("UPDATE teams SET join_mode = ? WHERE id = ?", mode, teamId);
        publish(memberUuids(teamId), "joinModeChanged", teamId, node -> node.put("mode", mode));
        return JsonNodeFactory.instance.objectNode();
    }

    private ObjectNode transfer(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = validUuid(p, "target");
        long teamId = requireTeamOf(conn, uuid, p);
        requireRoleAtLeast(conn, teamId, uuid, ROLE_LEADER);
        String targetRole = roleOf(teamId, target);
        if (targetRole == null) {
            throw new OpException(ErrorCodes.NOT_IN_TEAM);
        }
        if (ROLE_LEADER.equals(targetRole)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "target");
        }
        ctx.db.update("UPDATE team_members SET role = ? WHERE team_id = ? AND uuid = ?",
                ROLE_LEADER, teamId, target);
        ctx.db.update("UPDATE team_members SET role = ? WHERE team_id = ? AND uuid = ?",
                ROLE_MEMBER, teamId, uuid);
        publish(memberUuids(teamId), "leaderChanged", teamId, node -> node.put("uuid", target));
        publish(memberUuids(teamId), "roleChanged", teamId, node -> node
                .put("uuid", uuid).put("role", ROLE_MEMBER));
        return JsonNodeFactory.instance.objectNode();
    }

    // ---------- 事件与全量帧 ----------

    private void publishMemberJoined(long teamId, String uuid) {
        Set<String> recipients = memberUuids(teamId);
        publish(recipients, "memberJoined", teamId, node -> node
                .put("uuid", uuid).put("role", ROLE_MEMBER));
    }

    private void publish(Set<String> recipients, String k, long teamId,
                         java.util.function.UnaryOperator<ObjectNode> extra) {
        ObjectNode event = extra.apply(JsonNodeFactory.instance.objectNode());
        ctx.stream.publish(recipients, k, event);
    }

    /** team 流全量帧（登记表 §5.1）；boot 与 seq 由 TeamStream 填充。 */
    public ObjectNode fullFrame(String uuid) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("t", "f");
        f.put("uuid", uuid);
        Optional<Long> teamId = teamIdOf(uuid);
        if (teamId.isPresent()) {
            f.set("team", teamJson(teamId.get(), teamRow(teamId.get()).orElseThrow()));
        } else {
            f.putNull("team");
        }
        long now = System.currentTimeMillis() / 1000;
        ArrayNode invites = f.putArray("invites");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT i.id, i.from_uuid, i.team_id, i.expires_at, t.name AS team_name "
                        + "FROM invites i JOIN teams t ON t.id = i.team_id "
                        + "WHERE i.target_uuid = ? AND i.status = 'pending' AND i.expires_at > ?",
                uuid, now)) {
            ObjectNode item = invites.addObject();
            item.put("inviteId", ((Number) row.get("id")).longValue());
            item.put("from", (String) row.get("from_uuid"));
            item.put("teamId", ((Number) row.get("team_id")).longValue());
            item.put("teamName", (String) row.get("team_name"));
            item.put("expiresAt", ((Number) row.get("expires_at")).longValue());
        }
        ArrayNode applies = f.putArray("applies");
        List<Map<String, Object>> led = ctx.db.query(
                "SELECT team_id FROM team_members WHERE uuid = ? AND role IN (?, ?)",
                uuid, ROLE_LEADER, ROLE_VICE);
        for (Map<String, Object> ledRow : led) {
            long ledTeamId = ((Number) ledRow.get("team_id")).longValue();
            for (Map<String, Object> row : ctx.db.query(
                    "SELECT id, uuid, expires_at FROM applies "
                            + "WHERE team_id = ? AND status = 'pending' AND expires_at > ?", ledTeamId, now)) {
                ObjectNode item = applies.addObject();
                item.put("applyId", ((Number) row.get("id")).longValue());
                item.put("uuid", (String) row.get("uuid"));
                item.put("teamId", ledTeamId);
                item.put("expiresAt", ((Number) row.get("expires_at")).longValue());
            }
        }
        return f;
    }

    /** 过期清扫：把滞留的过期邀请/申请标记终结并广播事件。 */
    public void sweepExpired() {
        try {
            long now = System.currentTimeMillis() / 1000;
            for (Map<String, Object> row : ctx.db.query(
                    "SELECT id, team_id, target_uuid FROM invites "
                            + "WHERE status = 'pending' AND expires_at <= ?")) {
                long inviteId = ((Number) row.get("id")).longValue();
                long teamId = ((Number) row.get("team_id")).longValue();
                ctx.db.update("UPDATE invites SET status = 'expired' WHERE id = ?", inviteId);
                publish(Set.of((String) row.get("target_uuid")), "inviteResolved", teamId,
                        node -> node.put("inviteId", inviteId).put("reason", "expired"));
            }
            for (Map<String, Object> row : ctx.db.query(
                    "SELECT id, team_id, uuid FROM applies WHERE status = 'pending' AND expires_at <= ?")) {
                long applyId = ((Number) row.get("id")).longValue();
                long teamId = ((Number) row.get("team_id")).longValue();
                ctx.db.update("UPDATE applies SET status = 'expired' WHERE id = ?", applyId);
                publish(leaderRecipients(teamId), "applyResolved", teamId, node -> node
                        .put("applyId", applyId).put("reason", "expired"));
            }
        } catch (Exception e) {
            // 清扫失败不影响服务，下个周期重试
        }
    }

    // ---------- 查询与校验辅助 ----------

    private ObjectNode teamJson(long teamId, Map<String, Object> team) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("teamId", teamId);
        node.put("name", (String) team.get("name"));
        node.put("joinMode", (String) team.get("join_mode"));
        node.put("createdAt", ((Number) team.get("created_at")).longValue());
        ArrayNode members = node.putArray("members");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid, role FROM team_members WHERE team_id = ? ORDER BY joined_at", teamId)) {
            ObjectNode member = members.addObject();
            member.put("uuid", (String) row.get("uuid"));
            member.put("role", (String) row.get("role"));
        }
        return node;
    }

    private String identity(Connection conn, JsonNode p) {
        if (conn.serverPlane() == Plane.APP) {
            String uuid = Dispatcher.text(p, "uuid");
            if (!ctx.auth.isUuidBoundToAccount(uuid, conn.accountId())) {
                throw new OpException(ErrorCodes.FORBIDDEN);
            }
            return uuid;
        }
        return Dispatcher.text(p, "actor");
    }

    private String validName(JsonNode p) {
        String name = Dispatcher.text(p, "name");
        if (name.length() > ctx.config.maxNameLength || name.indexOf('§') >= 0
                || name.chars().anyMatch(c -> c < 0x20)) {
            throw new OpException(ErrorCodes.BAD_NAME);
        }
        return name;
    }

    private String validUuid(JsonNode p, String field) {
        String value = Dispatcher.text(p, field);
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, field);
        }
        return value;
    }

    private Optional<Long> teamIdOf(String uuid) {
        return ctx.db.queryOne("SELECT team_id FROM team_members WHERE uuid = ?", uuid)
                .map(row -> ((Number) row.get("team_id")).longValue());
    }

    /** 操作者的队伍；服主/超管不在队时可用 p.teamId 指定目标队伍代管（契约 1.1.0）。 */
    private long requireTeamOf(Connection conn, String uuid, JsonNode p) {
        Optional<Long> own = teamIdOf(uuid);
        if (own.isPresent()) {
            return own.get();
        }
        if (isSuper(conn) && p != null && p.hasNonNull("teamId")
                && p.get("teamId").canConvertToLong()) {
            long teamId = p.get("teamId").asLong();
            teamRow(teamId).orElseThrow(() -> new OpException(ErrorCodes.TEAM_NOT_FOUND));
            return teamId;
        }
        throw new OpException(ErrorCodes.NOT_IN_TEAM);
    }

    private Optional<Map<String, Object>> teamRow(long teamId) {
        return ctx.db.queryOne("SELECT id, name, join_mode, created_at FROM teams WHERE id = ?", teamId);
    }

    private String teamName(long teamId) {
        return teamRow(teamId).map(row -> (String) row.get("name")).orElse(null);
    }

    private String joinMode(long teamId) {
        return teamRow(teamId).map(row -> (String) row.get("join_mode")).orElse("invite");
    }

    private String roleOf(long teamId, String uuid) {
        return ctx.db.queryOne(
                "SELECT role FROM team_members WHERE team_id = ? AND uuid = ?", teamId, uuid)
                .map(row -> (String) row.get("role")).orElse(null);
    }

    /** 校验操作者角色达到要求；服主/超管账号按队长级代管（契约 1.1.0），其余按队伍角色校验。 */
    private void requireRoleAtLeast(Connection conn, long teamId, String uuid, String required) {
        if (isSuper(conn)) {
            return;
        }
        String role = roleOf(teamId, uuid);
        if (role == null) {
            throw new OpException(ErrorCodes.NOT_IN_TEAM);
        }
        int have = rank(role);
        int need = rank(required);
        if (have < need) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    /** 操作者账号为服主或超管。 */
    private boolean isSuper(Connection conn) {
        String role = ctx.auth.roleOf(conn.accountId());
        return "owner".equals(role) || "super".equals(role);
    }

    private int rank(String role) {
        return switch (role) {
            case ROLE_LEADER -> 3;
            case ROLE_VICE -> 2;
            default -> 1;
        };
    }

    private Set<String> memberUuids(long teamId) {
        Set<String> uuids = new HashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid FROM team_members WHERE team_id = ?", teamId)) {
            uuids.add((String) row.get("uuid"));
        }
        return uuids;
    }

    private Set<String> leaderRecipients(long teamId) {
        Set<String> uuids = new HashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid FROM team_members WHERE team_id = ? AND role IN (?, ?)",
                teamId, ROLE_LEADER, ROLE_VICE)) {
            uuids.add((String) row.get("uuid"));
        }
        return uuids;
    }

    private void joinMember(long teamId, String uuid, String role) {
        ctx.db.update("INSERT INTO team_members(team_id, uuid, role, joined_at) VALUES(?,?,?,?)",
                teamId, uuid, role, System.currentTimeMillis() / 1000);
    }
}
