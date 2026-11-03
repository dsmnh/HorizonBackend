package com.dsmnh.mobile.backend.support;

import com.dsmnh.mobile.backend.net.FrameType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 协议测试客户端：阻塞 socket，按契约帧格式收发，供流程断言用。 */
public final class TestClient implements AutoCloseable {
    public static final int READ_TIMEOUT_MS = 10_000;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.ArrayDeque<JsonNode> pushQueue = new java.util.ArrayDeque<>();
    private long nextId;
    public String lastNonce;
    public JsonNode lastHelloAck;
    public JsonNode lastAuthOk;

    public TestClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
    }

    /** 发送 HELLO 并等待 HELLO_ACK，记录 nonce。 */
    public JsonNode hello(String plane, String app) throws IOException {
        return hello(plane, app, 1);
    }

    /** 只发送 HELLO 不等待应答（供 ERR 断言类用例使用）。 */
    public void helloRaw(String plane, String app, int version) throws IOException {
        ObjectNode hello = JsonNodeFactory.instance.objectNode();
        hello.put("v", version);
        hello.put("app", app);
        hello.put("plane", plane);
        send(FrameType.HELLO, hello);
    }

    public JsonNode hello(String plane, String app, int version) throws IOException {
        ObjectNode hello = JsonNodeFactory.instance.objectNode();
        hello.put("v", version);
        hello.put("app", app);
        hello.put("plane", plane);
        send(FrameType.HELLO, hello);
        JsonNode ack = expect(FrameType.HELLO_ACK);
        lastNonce = ack.path("nonce").asText(null);
        lastHelloAck = ack;
        return ack;
    }

    /** 正确签名鉴权；返回 AUTH_FAIL.code，成功时返回 null。 */
    public String auth(String token) throws IOException {
        JsonNode frame = authNode(token);
        return frame.has("code") ? frame.path("code").asText() : null;
    }

    /** 鉴权并返回 AUTH_OK（含 role）或 AUTH_FAIL 原始载荷。 */
    public JsonNode authNode(String token) throws IOException {
        return authWithNode(token, sign(token, lastNonce, System.currentTimeMillis() / 1000),
                System.currentTimeMillis() / 1000);
    }

    /** 自定义签名/时间的鉴权；返回 AUTH_FAIL.code，成功时返回 null。 */
    public String authWith(String token, String sig, long ts) throws IOException {
        return authWithNode(token, sig, ts).path("code").asText(null);
    }

    private JsonNode authWithNode(String token, String sig, long ts) throws IOException {
        ObjectNode auth = JsonNodeFactory.instance.objectNode();
        auth.put("kid", token.substring(0, Math.min(8, token.length())));
        auth.put("ts", ts);
        auth.put("sig", sig);
        send(FrameType.AUTH, auth);
        JsonNode frame = expectAny(FrameType.AUTH_OK, FrameType.AUTH_FAIL);
        if (!frame.has("code")) {
            lastAuthOk = frame;
        }
        return frame;
    }

    public JsonNode req(String op, ObjectNode p) throws IOException {
        long id = ++nextId;
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("id", id);
        request.put("op", op);
        request.set("p", p == null ? JsonNodeFactory.instance.objectNode() : p);
        send(FrameType.REQ, request);
        while (true) {
            FrameRx frame = readRaw();
            if (frame.type() == FrameType.RES) {
                if (frame.node().path("id").asLong() == id) {
                    return frame.node();
                }
                continue;
            }
            if (frame.type() == FrameType.PUSH) {
                pushQueue.add(frame.node());
                continue;
            }
            throw new IOException("期望 RES，收到 " + frame.type());
        }
    }

    /** 发送 SUB 并等待 SUB_OK；返回 {"s":…,"sid":…}。 */
    public JsonNode sub(String op, ObjectNode p) throws IOException {
        long id = ++nextId;
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("id", id);
        request.put("op", op);
        request.set("p", p == null ? JsonNodeFactory.instance.objectNode() : p);
        send(FrameType.SUB, request);
        while (true) {
            FrameRx frame = readRaw();
            if (frame.type() == FrameType.SUB_OK && frame.node().path("id").asLong() == id) {
                return frame.node();
            }
            if (frame.type() == FrameType.PUSH) {
                pushQueue.add(frame.node());
                continue;
            }
            throw new IOException("期望 SUB_OK，收到 " + frame.type());
        }
    }

    /** 读取下一帧 PUSH 的 f 节点；优先取 req 过程中缓存的推送。 */
    public JsonNode readPush() throws IOException {
        JsonNode buffered = pushQueue.poll();
        if (buffered != null) {
            return buffered.path("f");
        }
        return readFrame(FrameType.PUSH).path("f");
    }

    /** 读取一帧并校验类型；类型不符或连接被关闭时抛 IOException。 */
    public JsonNode expect(FrameType expected, FrameType... alternatives) throws IOException {
        FrameType[] all = new FrameType[alternatives.length + 1];
        all[0] = expected;
        System.arraycopy(alternatives, 0, all, 1, alternatives.length);
        return readFrame(all);
    }

    public JsonNode expectAny(FrameType... types) throws IOException {
        return readFrame(types);
    }

    /** 原始帧：类型与解析后的 JSON（空载荷解析为空对象）。 */
    private record FrameRx(FrameType type, JsonNode node) {}

    private FrameRx readRaw() throws IOException {
        int len = in.readInt();
        if (len < 2) {
            throw new IOException("非法帧长 " + len);
        }
        byte type = in.readByte();
        in.readByte(); // flags
        byte[] payload = new byte[len - 2];
        in.readFully(payload);
        JsonNode node = payload.length == 0
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(payload);
        FrameType parsed = FrameType.of(type).orElseThrow(
                () -> new IOException("未知帧类型 0x" + Integer.toHexString(type & 0xFF)));
        return new FrameRx(parsed, node);
    }

    /** 完整读一帧并校验类型：4B len + 1B type + 1B flags + payload。 */
    private JsonNode readFrame(FrameType... accepted) throws IOException {
        FrameRx frame = readRaw();
        for (FrameType t : accepted) {
            if (frame.type() == t) {
                return frame.node();
            }
        }
        throw new IOException("期望 " + java.util.Arrays.toString(accepted)
                + "，收到 " + frame.type());
    }

    public void send(FrameType type, ObjectNode node) throws IOException {
        byte[] payload = node == null ? new byte[0] : mapper.writeValueAsBytes(node);
        out.writeInt(2 + payload.length);
        out.writeByte(type.value);
        out.writeByte(0);
        out.write(payload);
        out.flush();
    }

    public void sendRaw(FrameType type, byte[] payload) throws IOException {
        out.writeInt(2 + payload.length);
        out.writeByte(type.value);
        out.writeByte(0);
        out.write(payload);
        out.flush();
    }

    public boolean isClosed() {
        return socket.isClosed() || !socket.isConnected();
    }

    /** 调整读超时（毫秒），用于「不应有推送」类负向断言。 */
    public void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static String sign(String token, String nonce, long ts) {
        try {
            String canonical = "HORIZON\n" + ts + "\n" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode obj(Object... kv) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] instanceof Integer n) {
                node.put((String) kv[i], n);
            } else if (kv[i + 1] instanceof Long l) {
                node.put((String) kv[i], l);
            } else if (kv[i + 1] instanceof String s) {
                node.put((String) kv[i], s);
            } else {
                throw new IllegalArgumentException("不支持的值类型");
            }
        }
        return node;
    }
}
