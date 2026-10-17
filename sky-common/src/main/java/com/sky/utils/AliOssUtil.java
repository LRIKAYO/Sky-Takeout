package com.sky.utils;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.AllArgsConstructor;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import java.io.ByteArrayInputStream;
import java.net.URI;

@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String bucketName;
    private final String region;
    private final String domain;

    /**
     * 文件上传
     *
     * @param bytes
     * @param objectName
     * @return
     */
    public String upload(byte[] bytes, String objectName) {

        if (!StringUtils.hasText(bucketName) || !StringUtils.hasText(accessKeyId)
                || !StringUtils.hasText(accessKeySecret)) {
            throw new IllegalStateException("请先在 .env.local 填写 OSS 的 Bucket 名称、AccessKey ID 和 Secret，再通过 VS Code 调试配置启动");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(region)) {
            throw new IllegalStateException("请填写 OSS Endpoint 和 Region");
        }

        boolean useCname = StringUtils.hasText(domain);
        String address = (useCname ? domain : endpoint).trim();
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            address = "https://" + address;
        }
        URI uri = URI.create(address);
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalStateException("OSS 地址只填写域名及可选的 http/https 协议，不包含文件路径、查询参数或凭据");
        }
        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();

        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        configuration.setSupportCname(useCname);
        OSS ossClient = OSSClientBuilder.create()
                .endpoint(baseUrl)
                .region(region)
                .credentialsProvider(new DefaultCredentialProvider(accessKeyId, accessKeySecret))
                .clientConfiguration(configuration)
                .build();

        try {
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes));
            String publicBaseUrl = useCname ? baseUrl
                    : uri.getScheme() + "://" + bucketName + "." + uri.getRawAuthority();
            String url = publicBaseUrl + "/" + objectName;
            log.info("文件上传成功：{}", url);
            return url;
        } catch (OSSException e) {
            log.error("OSS 拒绝上传，错误码：{}，请求 ID：{}", e.getErrorCode(), e.getRequestId());
            if ("PublicEndpointForbidden".equals(e.getErrorCode())) {
                throw new IllegalStateException("当前 OSS 账号需使用自定义域名，请先绑定域名并填写 SKY_OSS_DOMAIN", e);
            }
            throw new IllegalStateException("OSS 上传失败，请检查 Bucket、RAM 授权和 AccessKey 配置", e);
        } catch (ClientException e) {
            throw new IllegalStateException("无法连接 OSS，请检查网络、Endpoint 和自定义域名配置", e);
        } finally {
            ossClient.shutdown();
        }
    }
}
