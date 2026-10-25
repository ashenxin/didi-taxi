package com.sx.passengerapi.service;

import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Forecast;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Location;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把地图服务返回的天气事实组织成乘客可读的完整回答和结构化 payload。
 * 风向、风力单位在展示层再做一次幂等归一化；不补造天气、温度或风力。
 */
final class WeatherAnswerFormatter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日");

    private WeatherAnswerFormatter() {
    }

    static String text(WeatherForecastResponse response, int dayOffset, String requestedPlaceName) {
        Location location = response.location();
        Forecast forecast = response.forecast();
        String place = firstText(requestedPlaceName,
                location == null ? null : location.name(),
                location == null ? null : location.city(), "当地");
        String weather = weatherText(forecast);
        String temperature = temperatureText(forecast);
        String week = weekText(forecast == null ? null : forecast.week());
        StringBuilder text = new StringBuilder(place)
                .append(dayLabel(dayOffset));
        if (forecast != null && hasText(forecast.date())) {
            text.append("（").append(formatDate(forecast.date()));
            if (week != null) {
                text.append("，").append(week);
            }
            text.append("）");
        } else if (week != null) {
            text.append("（").append(week).append("）");
        }
        text.append(weather).append("，").append(temperature);
        String wind = windText(forecast);
        if (wind != null) {
            text.append("，").append(wind);
        }
        text.append("。");
        String tip = tipText(forecast);
        if (tip != null) {
            text.append(tip);
        }
        return text.toString();
    }

    static Map<String, Object> payload(WeatherForecastResponse response) {
        Map<String, Object> weather = new LinkedHashMap<>();
        Location location = response.location();
        if (location != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            putIfText(view, "name", location.name());
            putIfText(view, "province", location.province());
            putIfText(view, "city", location.city());
            putIfText(view, "district", location.district());
            putIfText(view, "adcode", location.adcode());
            weather.put("location", view);
        }
        Forecast forecast = response.forecast();
        if (forecast != null) {
            putIfText(weather, "date", forecast.date());
            putIfText(weather, "week", weekText(forecast.week()));
            putIfText(weather, "dayWeather", forecast.dayWeather());
            putIfText(weather, "nightWeather", forecast.nightWeather());
            if (forecast.dayTemp() != null) {
                weather.put("dayTemp", forecast.dayTemp());
            }
            if (forecast.nightTemp() != null) {
                weather.put("nightTemp", forecast.nightTemp());
            }
            putIfText(weather, "windDirection",
                    normalizeWindDirection(forecast.windDirection()));
            putIfText(weather, "windPower", normalizeWindPower(forecast.windPower()));
        }
        weather.put("source", hasText(response.source()) ? response.source() : "amap");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weather", weather);
        return payload;
    }

    private static String weatherText(Forecast forecast) {
        if (forecast == null) {
            return "天气信息暂缺";
        }
        String day = trimToNull(forecast.dayWeather());
        String night = trimToNull(forecast.nightWeather());
        if (day == null && night == null) {
            return "天气信息暂缺";
        }
        if (day == null) {
            return "夜间" + night;
        }
        if (night == null) {
            return "白天" + day;
        }
        if (night.equals(day)) {
            return "全天" + day;
        }
        return "白天" + day + "，夜间" + night;
    }

    private static String temperatureText(Forecast forecast) {
        Integer day = forecast == null ? null : forecast.dayTemp();
        Integer night = forecast == null ? null : forecast.nightTemp();
        if (day == null && night == null) {
            return "气温暂缺";
        }
        if (day == null) {
            return "气温 " + night + "℃";
        }
        if (night == null || night.equals(day)) {
            return "气温 " + day + "℃";
        }
        return "气温 " + Math.min(day, night) + "~" + Math.max(day, night) + "℃";
    }

    private static String windText(Forecast forecast) {
        if (forecast == null) {
            return null;
        }
        String direction = normalizeWindDirection(forecast.windDirection());
        String power = normalizeWindPower(forecast.windPower());
        if (direction == null && power == null) {
            return null;
        }
        if (direction == null) {
            return power;
        }
        return power == null ? direction : direction + " " + power;
    }

    /** 只根据天气现象和温度给通用提示，不推断路况、拥堵或 ETA。 */
    private static String tipText(Forecast forecast) {
        if (forecast == null) {
            return null;
        }
        String day = trimToNull(forecast.dayWeather());
        String night = trimToNull(forecast.nightWeather());
        boolean dayRain = day != null && day.contains("雨");
        boolean nightRain = night != null && night.contains("雨");
        if (dayRain && nightRain) {
            return "有雨，出门记得带伞。";
        }
        if (nightRain) {
            return "夜间有雨，出门记得带伞。";
        }
        if (dayRain) {
            return "白天有雨，出门记得带伞。";
        }
        if ((day != null && day.contains("雪")) || (night != null && night.contains("雪"))) {
            return "有雪，注意保暖。";
        }
        Integer high = forecast.dayTemp();
        Integer low = forecast.nightTemp();
        if (high == null && low == null) {
            return null;
        }
        int max = high == null ? low : high;
        int min = low == null ? high : low;
        if (max >= 35) {
            return "气温较高，注意防暑。";
        }
        if (min <= 0) {
            return "气温较低，注意保暖。";
        }
        return null;
    }

    private static String weekText(String week) {
        String value = trimToNull(week);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "1" -> "星期一";
            case "2" -> "星期二";
            case "3" -> "星期三";
            case "4" -> "星期四";
            case "5" -> "星期五";
            case "6" -> "星期六";
            case "7" -> "星期日";
            default -> value;
        };
    }

    private static String normalizeWindDirection(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.contains("风") ? normalized : normalized + "风";
    }

    private static String normalizeWindPower(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.contains("级") || normalized.contains("风")
                ? normalized : normalized + "级";
    }

    private static String dayLabel(int dayOffset) {
        return switch (dayOffset) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            case 3 -> "大后天";
            default -> "";
        };
    }

    private static String formatDate(String date) {
        try {
            return LocalDate.parse(date).format(DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return date;
        }
    }

    private static String firstText(String first, String second, String third, String fallback) {
        String value = trimToNull(first);
        if (value != null) {
            return value;
        }
        value = trimToNull(second);
        if (value != null) {
            return value;
        }
        value = trimToNull(third);
        return value != null ? value : fallback;
    }

    private static void putIfText(Map<String, Object> target, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.strip() : null;
    }
}
