package com.dsmnh.mobile.backend.net;

import com.dsmnh.mobile.backend.config.Config;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;

/** 契约 §9.1：首次启用时经 JDK keytool 生成并持久化自签证书，输出 SHA-256 指纹。 */
public final class TlsSupport {
    private static final String ALIAS = "horizon";

    private TlsSupport() {}

    public record Material(SslContext context, String fingerprint) {}

    /** 加载或生成 PKCS12 存储，构建服务端 SslContext 并计算证书指纹。 */
    public static Material loadOrCreate(Config config) throws Exception {
        Path store = Path.of(config.tlsStorePath);
        if (!Files.exists(store)) {
            generate(config, store);
        }
        char[] password = config.tlsStorePassword.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(ALIAS);
        SslContext sslContext = SslContextBuilder.forServer(kmf).build();
        return new Material(sslContext, fingerprint(cert));
    }

    private static void generate(Config config, Path store) throws Exception {
        Path parent = store.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "keytool.exe" : "keytool";
        String keytool = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        ProcessBuilder builder = new ProcessBuilder(keytool,
                "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", config.tlsStorePassword, "-keypass", config.tlsStorePassword,
                "-dname", "CN=Horizon", "-noprompt");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("生成自签证书失败：" + output.trim());
        }
    }

    /** SHA-256 指纹以冒号分隔的十六进制大写形式输出，供客户端固定信任比对。 */
    public static String fingerprint(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder out = new StringBuilder();
        for (byte b : digest) {
            if (out.length() > 0) {
                out.append(':');
            }
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }
}
