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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

/** 契约 §9.1：默认生成并持久化自签证书；配置自有证书时改用 PEM 证书链与私钥。 */
public final class TlsSupport {
    private static final String ALIAS = "horizon";

    private TlsSupport() {}

    public record Material(SslContext context, String fingerprint, String source) {}

    /** 优先使用自有证书；未配置时加载或生成 PKCS12 自签证书。 */
    public static Material loadOrCreate(Config config) throws Exception {
        if (hasText(config.tlsCertPath) || hasText(config.tlsKeyPath)) {
            return loadExternal(config);
        }
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
        return new Material(sslContext, fingerprint(cert), "自签");
    }

    /** 自有证书：PEM 证书链与私钥必须同时配置，文件缺失即启动失败，避免静默降级到自签。 */
    private static Material loadExternal(Config config) throws Exception {
        if (!hasText(config.tlsCertPath) || !hasText(config.tlsKeyPath)) {
            throw new IllegalStateException("tlsCertPath 与 tlsKeyPath 必须同时配置");
        }
        Path certFile = Path.of(config.tlsCertPath);
        Path keyFile = Path.of(config.tlsKeyPath);
        if (!Files.isRegularFile(certFile)) {
            throw new IllegalStateException("TLS 证书文件不存在：" + certFile);
        }
        if (!Files.isRegularFile(keyFile)) {
            throw new IllegalStateException("TLS 私钥文件不存在：" + keyFile);
        }
        String keyPassword = hasText(config.tlsKeyPassword) ? config.tlsKeyPassword : null;
        SslContext context = SslContextBuilder.forServer(certFile.toFile(), keyFile.toFile(), keyPassword).build();
        return new Material(context, fingerprint(readLeaf(certFile)), "自有");
    }

    /** PEM 可能含证书链，取第一张叶子证书计算指纹。 */
    private static X509Certificate readLeaf(Path certFile) throws Exception {
        try (InputStream in = Files.newInputStream(certFile)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(in);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
