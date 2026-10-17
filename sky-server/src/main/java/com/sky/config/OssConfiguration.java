package com.sky.config;

import com.sky.properties.AliOssProperties;
import com.sky.utils.AliOssUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration 
public class OssConfiguration {
    
    @Bean 
    @ConditionalOnMissingBean 
    public AliOssUtil aliOssUtil(AliOssProperties properties) {
        return new AliOssUtil(
            properties.getEndpoint(), 
            properties.getAccessKeyId(),
            properties.getAccessKeySecret(),
            properties.getBucketName(),
            properties.getRegion(),
            properties.getDomain());
    }
    
}
