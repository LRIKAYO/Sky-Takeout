package com.sky.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.properties.WeChatProperties;
import com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class WeChatPayUtil {
    public static final String JSAPI = "https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi";
    public static final String REFUNDS = "https://api.mch.weixin.qq.com/v3/refund/domestic/refunds";
    @Autowired private WeChatProperties properties;

    private PrivateKey privateKey() throws Exception {
        try (InputStream input = Files.newInputStream(Paths.get(properties.getPrivateKeyFilePath()))) {
            return PemUtil.loadPrivateKey(input);
        }
    }

    CloseableHttpClient client() throws Exception {
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(Paths.get(properties.getWeChatPayCertFilePath()))) {
            certificate = PemUtil.loadCertificate(input);
        }
        return WechatPayHttpClientBuilder.create()
                .withMerchant(properties.getMchid(), properties.getMchSerialNo(), privateKey())
                .withWechatPay(List.of(certificate)).build();
    }

    JSONObject post(String url, JSONObject body) throws Exception {
        HttpPost request = new HttpPost(url);
        request.setConfig(RequestConfig.custom().setConnectTimeout(3000).setSocketTimeout(5000)
                .setConnectionRequestTimeout(3000).build());
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setEntity(new StringEntity(body.toJSONString(), ContentType.APPLICATION_JSON));
        try (CloseableHttpClient http = client(); CloseableHttpResponse response = http.execute(request)) {
            String text = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("微信支付 HTTP 状态：" + status);
            return JSON.parseObject(text);
        }
    }

    private int cents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("金额必须大于 0");
        return amount.movePointRight(2).intValueExact();
    }

    public JSONObject pay(String orderNum, BigDecimal total, String description, String openid) throws Exception {
        JSONObject body = new JSONObject();
        body.put("appid", properties.getAppid());
        body.put("mchid", properties.getMchid());
        body.put("description", description);
        body.put("out_trade_no", orderNum);
        body.put("notify_url", properties.getNotifyUrl());
        JSONObject amount = new JSONObject();
        amount.put("total", cents(total));
        amount.put("currency", "CNY");
        body.put("amount", amount);
        JSONObject payer = new JSONObject();
        payer.put("openid", openid);
        body.put("payer", payer);
        JSONObject response = post(JSAPI, body);
        String prepayId = response.getString("prepay_id");
        if (prepayId == null || prepayId.isBlank()) throw new IllegalStateException("微信未返回预支付 ID");

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String packageValue = "prepay_id=" + prepayId;
        String message = properties.getAppid() + "\n" + timestamp + "\n" + nonce + "\n" + packageValue + "\n";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        JSONObject result = new JSONObject();
        result.put("timeStamp", timestamp);
        result.put("nonceStr", nonce);
        result.put("package", packageValue);
        result.put("signType", "RSA");
        result.put("paySign", Base64.getEncoder().encodeToString(signer.sign()));
        return result;
    }

    // 保留原工具的退款方法签名；退款业务与通知处理留待后续订单管理阶段。
    public String refund(String outTradeNo, String outRefundNo, BigDecimal refund, BigDecimal total) throws Exception {
        if (refund == null || total == null || refund.compareTo(total) > 0) {
            throw new IllegalArgumentException("退款金额不能大于原订单金额");
        }
        JSONObject body = new JSONObject();
        body.put("out_trade_no", outTradeNo);
        body.put("out_refund_no", outRefundNo);
        body.put("notify_url", properties.getRefundNotifyUrl());
        JSONObject amount = new JSONObject();
        amount.put("refund", cents(refund));
        amount.put("total", cents(total));
        amount.put("currency", "CNY");
        body.put("amount", amount);
        return post(REFUNDS, body).toJSONString();
    }
}
