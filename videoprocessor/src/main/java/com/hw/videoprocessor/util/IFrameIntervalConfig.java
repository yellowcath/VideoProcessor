package com.hw.videoprocessor.util;

import android.os.Build;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by huangwei on 2018/4/3 0003.
 */

public class IFrameIntervalConfig {
    private static final int DEFAULT_ALL_KEYFRAME_INTERVAL = 0;
    private static final Map<String, Integer> brandMap = new HashMap<>();
    private static final Map<String, Integer> modelMap = new HashMap<>();

    static {
        brandMap.put("google", -1);
    }

    public static void addConfig(String brand, int all_keyframe_interval) {
        brandMap.put(brand.toLowerCase(), all_keyframe_interval);
    }

    public static int getAllKeyframeInterval() {
        if (modelMap.containsKey(Build.MODEL.toLowerCase())) {
            return modelMap.get(Build.MODEL.toLowerCase());
        }
        if (brandMap.containsKey(Build.BRAND.toLowerCase())) {
            return brandMap.get(Build.BRAND.toLowerCase());
        }
        return DEFAULT_ALL_KEYFRAME_INTERVAL;
    }
}
