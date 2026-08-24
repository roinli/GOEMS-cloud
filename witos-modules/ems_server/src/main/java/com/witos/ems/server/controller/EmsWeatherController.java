package com.witos.ems.server.controller;

import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.ems.server.service.EmsWeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ems/weather")
public class EmsWeatherController extends BaseController
{
    @Resource
    private EmsWeatherService weatherService;

    @GetMapping("/stations/{stationId}")
    public AjaxResult stationWeather(@PathVariable("stationId") Long stationId)
    {
        return success(weatherService.stationWeather(stationId));
    }
}
