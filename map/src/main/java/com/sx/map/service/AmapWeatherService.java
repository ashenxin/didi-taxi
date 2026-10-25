package com.sx.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sx.map.ai.dto.WeatherForecastResponse;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.GeocodeDemoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 高德天气查询：{@code /v3/weather/weatherInfo}。
 *
 * 高德要求按 adcode 查询，因此先用现有地理编码把城市/区县文本解析为 adcode，再取预报。
 * 本类只返回结构化天气事实，不生成乘客话术，也不解释天气对路线或路况的影响。
 *
 * 文档：<a href="https://lbs.amap.com/api/webservice/guide/api/weatherinfo">天气查询</a>。
 */
@Service
@Slf4j
public class AmapWeatherService {

    private static final String WEATHER_PATH = "/v3/weather/weatherInfo";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAY_OFFSET = 3;
    private static final String GEOCODE_EMPTY_MESSAGE = "未返回地理编码结果";

    private final RestClient amapRestClient;
    private final AmapProperties amapProperties;
    private final AmapGeocodeService amapGeocodeService;

    public AmapWeatherService(RestClient amapRestClient,
                              AmapProperties amapProperties,
                              AmapGeocodeService amapGeocodeService) {
        this.amapRestClient = amapRestClient;
        this.amapProperties = amapProperties;
        this.amapGeocodeService = amapGeocodeService;
    }

    /**
     * 查询单城市、单日预报。dayOffset：0 今天、1 明天、2 后天、3 大后天；null 按今天处理。
     * 城市不存在或日期超出高德返回范围时返回稳定状态，不编造天气。
     */
    public WeatherForecastResponse forecast(String placeName, String placeRegion, Integer dayOffset) {
        ensureKey();
        if (!StringUtils.hasText(placeName)) {
            return WeatherForecastResponse.placeRequired();
        }
        int offset = dayOffset == null ? 0 : dayOffset;
        if (offset < 0 || offset > MAX_DAY_OFFSET) {
            return WeatherForecastResponse.dateOutOfRange(
                    "目前只能查询今天到后天、大后天的预报");
        }

        GeocodeDemoResponse geocode;
        try {
            geocode = amapGeocodeService.geocode(placeName.trim(), placeRegion);
        } catch (AmapApiException e) {
            // 只有“没有匹配结果”是地点问题；网络、配额和 Key 问题仍作为天气服务故障。
            if (e.getMessage() != null && e.getMessage().contains(GEOCODE_EMPTY_MESSAGE)) {
                return WeatherForecastResponse.placeNotFound(
                        "没有找到「" + placeName.trim() + "」对应的城市或区县");
            }
            throw e;
        } catch (RestClientException e) {
            throw new AmapApiException("高德地理编码暂时不可用");
        }
        if (!StringUtils.hasText(geocode.getAdcode())) {
            return WeatherForecastResponse.placeNotFound(
                    "没有找到「" + placeName.trim() + "」对应的城市或区县");
        }

        JsonNode root = fetchWeather(geocode.getAdcode().trim());
        assertStatusOk(root);
        JsonNode first = firstForecast(root);
        JsonNode selected = findCast(first, LocalDate.now(SHANGHAI).plusDays(offset));
        if (selected == null) {
            return WeatherForecastResponse.dateOutOfRange(
                    "高德预报暂未返回该日期，请稍后再试或换一个日期");
        }

        WeatherForecastResponse.Location location = new WeatherForecastResponse.Location(
                firstText(first, "city", geocode.getCityName(), placeName.trim()),
                blankToNull(firstText(first, "province", geocode.getProvince(), null)),
                blankToNull(firstText(first, "city", geocode.getCityName(), null)),
                blankToNull(geocode.getDistrict()),
                blankToNull(firstText(first, "adcode", geocode.getAdcode(), geocode.getAdcode())));
        WeatherForecastResponse.Forecast forecast = new WeatherForecastResponse.Forecast(
                blankToNull(text(selected, "date")),
                blankToNull(text(selected, "week")),
                blankToNull(text(selected, "dayweather")),
                blankToNull(text(selected, "nightweather")),
                intOrNull(selected, "daytemp"),
                intOrNull(selected, "nighttemp"),
                normalizeWindDirection(text(selected, "daywind")),
                normalizeWindPower(text(selected, "daypower")));

        log.info("高德天气查询成功 place={} adcode={} date={}", placeName.trim(),
                location.adcode(), forecast.date());
        return WeatherForecastResponse.ok(location, forecast);
    }

    private void ensureKey() {
        if (!StringUtils.hasText(amapProperties.getKey())) {
            throw new AmapApiException("未配置高德 Key：请设置环境变量 AMAP_KEY / MAP_AMAP_KEY，或 JVM 参数 -DAMAP_KEY / -Dmap.amap.key");
        }
    }

    private JsonNode fetchWeather(String adcode) {
        URI uri = UriComponentsBuilder.fromUriString(amapProperties.getBaseUrl())
                .path(WEATHER_PATH)
                .queryParam("key", amapProperties.getKey())
                .queryParam("city", adcode)
                .queryParam("extensions", "all")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        try {
            JsonNode body = amapRestClient.get().uri(uri).retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw new AmapApiException("高德天气查询失败 HTTP " + response.getStatusCode());
                    })
                    .body(JsonNode.class);
            if (body == null) {
                throw new AmapApiException("高德天气查询未返回内容");
            }
            return body;
        } catch (RestClientException e) {
            throw new AmapApiException("高德天气查询暂时不可用");
        }
    }

    private static void assertStatusOk(JsonNode root) {
        String status = text(root, "status");
        if (!"1".equals(status)) {
            String info = text(root, "info");
            String infocode = text(root, "infocode");
            throw new AmapApiException("高德天气查询失败"
                    + (StringUtils.hasText(info) ? "：" + info : "")
                    + (StringUtils.hasText(infocode) ? "（" + infocode + "）" : ""));
        }
    }

    private static JsonNode firstForecast(JsonNode root) {
        JsonNode forecasts = root.path("forecasts");
        if (!forecasts.isArray() || forecasts.isEmpty()) {
            throw new AmapApiException("高德未返回天气预报");
        }
        JsonNode first = forecasts.get(0);
        JsonNode casts = first.path("casts");
        if (!casts.isArray() || casts.isEmpty()) {
            throw new AmapApiException("高德天气预报缺少逐日数据");
        }
        return first;
    }

    private static JsonNode findCast(JsonNode firstForecast, LocalDate date) {
        JsonNode casts = firstForecast.path("casts");
        for (JsonNode cast : casts) {
            if (date.toString().equals(text(cast, "date"))) {
                return cast;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String field, String fallback, String lastFallback) {
        String value = blankToNull(text(node, field));
        if (value != null) {
            return value;
        }
        return blankToNull(fallback) != null ? fallback : lastFallback;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** 高德 `daywind` 常返回“东北”而非“东北风”，统一补齐可读单位。 */
    private static String normalizeWindDirection(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.contains("风") ? normalized : normalized + "风";
    }

    /** 高德 `daypower` 常返回“1-3”而非“1-3级”，统一补齐可读单位。 */
    private static String normalizeWindPower(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.contains("级") || normalized.contains("风")
                ? normalized : normalized + "级";
    }
}
