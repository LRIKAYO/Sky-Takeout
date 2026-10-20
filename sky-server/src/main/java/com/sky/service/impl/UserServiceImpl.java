package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service 
public class UserServiceImpl implements UserService {
    private static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired 
    private UserMapper userMapper;

    @Autowired 
    private  WeChatProperties weChatProperties;

    @Override 
    public User wxLogin(UserLoginDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getCode())) {
            throw new LoginFailedException("微信登录 code 不能为空");
        }

        if (!StringUtils.hasText(weChatProperties.getAppid())
                || ! StringUtils.hasText(weChatProperties.getSecret())) {
            throw new LoginFailedException("请先配置自己的微信 appID 和 Secret");
        }

        String openid;
        try {
            JSONObject result = JSON.parseObject(requestWechat(dto.getCode()));
            if (result == null || result.getIntValue("errcode") != 0
                    || !StringUtils.hasText(result.getString("openid"))) {
                throw new LoginFailedException("微信登录失败，请重新获取 code 后重试");
            }
            openid = result.getString("openid");
        } catch (LoginFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            // 不把含 AppSecret 的 URL、微信响应或 session_key 输出到日志或客户端。
            throw new LoginFailedException("微信登录服务请求失败，请稍后重试");
        }

        User user = userMapper.getByOpenid(openid);

        if (user == null) {
            user = User.builder().openid(openid).createTime(LocalDateTime.now()).build();
            userMapper.insert(user); // XML 回填 user.id，随后用本地主键签发 JWT。
        }
        return user;
    }

    protected String requestWechat(String code) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", weChatProperties.getAppid());
        params.put("secret", weChatProperties.getSecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");
        return HttpClientUtil.doGet(WX_LOGIN, params);
    }

    
}