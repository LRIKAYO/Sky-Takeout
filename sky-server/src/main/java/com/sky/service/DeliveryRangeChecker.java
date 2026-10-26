package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.entity.AddressBook;
import com.sky.exception.OrderBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DeliveryRangeChecker {
    @Value("${SKY_DELIVERY_RANGE_ENABLED:true}")
    private boolean enabled;
    @Value("${SKY_SHOP_ADDRESS:}")
    private String shopAddress;
    @Value("${SKY_BAIDU_AK:}")
    private String ak;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public void check(AddressBook address) {
        if (!enabled) return; // 仅用于先学习订单模块；关闭时不能算配送校验验收通过。
        if (shopAddress.isBlank() || ak.isBlank()) fail("尚未配置门店地址与百度地图 AK");
        if (address == null) fail("收货地址不能为空");
        String destination = text(address.getProvinceName()) + text(address.getCityName()) +
                text(address.getDistrictName()) + text(address.getDetail());
        if (destination.isBlank()) fail("收货地址不能为空");
        String origin = coordinate(shopAddress);
        String target = coordinate(destination);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", origin);
        params.put("destination", target);
        params.put("coord_type", "bd09ll");
        params.put("steps_info", "0");
        JSONObject result = get("https://api.map.baidu.com/directionlite/v1/driving", params)
                .getJSONObject("result");
        JSONArray routes = result == null ? null : result.getJSONArray("routes");
        if (routes == null || routes.isEmpty() || routes.getJSONObject(0) == null) fail("没有可用配送路线");
        checkDistance(routes.getJSONObject(0).getLong("distance"));
    }

    // 独立纯函数便于验证 5000 米边界；按课程参考使用首条驾车路线的道路距离。
    public static void checkDistance(Long meters) {
        if (meters == null || meters < 0) fail("配送距离结果异常");
        if (meters > 5000) fail("超出配送范围（5公里内）");
    }

    private String coordinate(String address) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", address);
        params.put("ret_coordtype", "bd09ll");
        JSONObject result = get("https://api.map.baidu.com/geocoding/v3/", params).getJSONObject("result");
        JSONObject location = result == null ? null : result.getJSONObject("location");
        if (location == null || location.getBigDecimal("lat") == null || location.getBigDecimal("lng") == null) {
            fail("地址解析缺少经纬度");
        }
        // 路线规划入参顺序是纬度,经度，不能按常见的 lng,lat 直接拼接。
        return location.getBigDecimal("lat").toPlainString() + "," + location.getBigDecimal("lng").toPlainString();
    }

    private JSONObject get(String url, Map<String, String> params) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("ak", ak).queryParam("output", "json");
            params.forEach((key, value) -> builder.queryParam(key, value));
            URI uri = builder.build().encode().toUri();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) fail("地图服务暂不可用");
            JSONObject json = JSON.parseObject(response.body());
            if (json == null || !"0".equals(json.getString("status"))) fail("地图解析或路线规划失败");
            return json;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OrderBusinessException("地图请求已中断");
        } catch (OrderBusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // 不回传完整 URL 或原始异常，避免 AK 进入前端或日志。
            throw new OrderBusinessException("地图请求失败，请稍后重试");
        }
    }

    private String text(String value) { return value == null ? "" : value; }
    private static void fail(String message) { throw new OrderBusinessException(message); }
}