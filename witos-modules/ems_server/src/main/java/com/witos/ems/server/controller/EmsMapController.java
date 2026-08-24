package com.witos.ems.server.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.core.utils.http.HttpUtils;
import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.ems.server.config.EmsQqMapProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/ems/map")
public class EmsMapController extends BaseController
{
    @Resource
    private EmsQqMapProperties qqMapProperties;

    @GetMapping("/config")
    public AjaxResult config()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", qqMapProperties.getKey());
        result.put("geocoderUrl", qqMapProperties.getGeocoderUrl());
        result.put("suggestionUrl", qqMapProperties.getSuggestionUrl());
        return success(result);
    }

    @GetMapping("/geocoder")
    public AjaxResult geocoder(@RequestParam("lat") String lat, @RequestParam("lng") String lng)
    {
        String requestUrl = qqMapProperties.getGeocoderUrl() + qqMapProperties.getKey() + "&location=" + lat + "," + lng + "&get_poi=1";
        return parseTencentResult(HttpUtils.sendGet(requestUrl), "逆地理编码失败");
    }

    @GetMapping("/suggestion")
    public AjaxResult suggestion(@RequestParam("keyword") String keyword, @RequestParam(value = "region", required = false) String region) throws UnsupportedEncodingException {
        StringBuilder requestUrl = new StringBuilder();
        requestUrl.append(qqMapProperties.getSuggestionUrl())
                .append(qqMapProperties.getKey())
                .append("&keyword=")
                .append(encode(keyword));
        if (StringUtils.isNotEmpty(region))
        {
            requestUrl.append("&region=").append(encode(region));
        }
        return parseTencentResult(HttpUtils.sendGet(requestUrl.toString()), "地点联想失败");
    }

    @GetMapping("/geocoder/address")
    public AjaxResult geocoderByAddress(@RequestParam("address") String address) throws UnsupportedEncodingException {
        String requestUrl = qqMapProperties.getGeocoderUrl() + qqMapProperties.getKey() + "&address=" + encode(address) + "&get_poi=0";
        return parseTencentResult(HttpUtils.sendGet(requestUrl), "地址解析失败");
    }

    private AjaxResult parseTencentResult(String response, String errorMessage)
    {
        if (StringUtils.isEmpty(response))
        {
            return AjaxResult.error(errorMessage);
        }
        JSONObject jsonObject = JSON.parseObject(response);
        if (jsonObject == null)
        {
            return AjaxResult.error(errorMessage);
        }
        Object status = jsonObject.get("status");
        if (status != null && "0".equals(String.valueOf(status)))
        {
            return success(jsonObject.get("result"));
        }
        return AjaxResult.error(errorMessage, jsonObject);
    }

    private String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(StringUtils.nvl(value, StringUtils.EMPTY), StandardCharsets.UTF_8.name());
    }
}
