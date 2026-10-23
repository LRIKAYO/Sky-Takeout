package com.sky.test;

import com.alibaba.fastjson.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/** 仅用于本地测试的临时密钥/证书，非商户材料；用完精确删除所创建的文件。 */
public final class PaymentCryptoFixture implements AutoCloseable {
    public static final String API_KEY = "0123456789abcdef0123456789abcdef";
    public final Path directory;
    public final Path privateKeyFile;
    public final Path certificateFile;
    public final PrivateKey privateKey;
    public final X509Certificate certificate;
    public final String serial;

    private PaymentCryptoFixture() throws Exception {
        directory = Files.createTempDirectory("day08-payment-test-");
        Path store = directory.resolve("test.p12");
        privateKeyFile = directory.resolve("private.pem");
        certificateFile = directory.resolve("platform.pem");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Day08 Local Test Only",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "reference-test", "-noprompt")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { keys.load(input, "reference-test".toCharArray()); }
        privateKey = (PrivateKey) keys.getKey("test", "reference-test".toCharArray());
        certificate = (X509Certificate) keys.getCertificate("test");
        serial = certificate.getSerialNumber().toString(16).toUpperCase();
        Files.writeString(privateKeyFile, pem("PRIVATE KEY", privateKey.getEncoded()), StandardCharsets.UTF_8);
        Files.writeString(certificateFile, pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.UTF_8);
        Files.delete(store);
    }

    public static PaymentCryptoFixture create() {
        try { return new PaymentCryptoFixture(); }
        catch (Exception ex) { throw new IllegalStateException("创建本地支付测试证书失败", ex); }
    }

    public String sign(String message) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    public String notification(JSONObject transaction, String event) throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        JSONObject resource = new JSONObject();
        resource.put("algorithm", "AEAD_AES_256_GCM");
        resource.put("original_type", "transaction");
        resource.put("nonce", nonce);
        resource.put("associated_data", "transaction");
        resource.put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(transaction.toJSONString().getBytes(StandardCharsets.UTF_8))));
        JSONObject notification = new JSONObject();
        notification.put("id", UUID.randomUUID().toString());
        notification.put("event_type", event);
        notification.put("create_time", OffsetDateTime.now().toString());
        notification.put("summary", "本地签名通知测试");
        notification.put("resource_type", "encrypt-resource");
        notification.put("resource", resource);
        return notification.toJSONString();
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n";
    }

    @Override
    public void close() throws Exception {
        Files.deleteIfExists(privateKeyFile);
        Files.deleteIfExists(certificateFile);
        Files.deleteIfExists(directory.resolve("test.p12"));
        Files.deleteIfExists(directory);
    }
}
