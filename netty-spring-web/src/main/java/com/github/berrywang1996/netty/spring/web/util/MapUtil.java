package com.github.berrywang1996.netty.spring.web.util;

import com.github.berrywang1996.netty.spring.web.exception.DuplicateKeyException;

import java.util.Map;

/**
 * @Author: 王伯瑞
 * @Date: 2018/8/11 15:30
 */
public class MapUtil {

    public static <K> void checkDuplicateKey(Map<K, ?> map1, Map<K, ?> map2) {
        for (Object key : map1.keySet()) {
            if (map2.containsKey(key)) {
                throw new DuplicateKeyException("Duplicate uri: " + key);
            }
        }
    }

}
