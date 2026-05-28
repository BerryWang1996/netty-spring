package com.github.berrywang1996.netty.spring.web.util;

import com.github.berrywang1996.netty.spring.web.exception.DuplicateKeyException;

import java.util.Map;

/**
 * Utility class for map-related operations used during URL mapping registration.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class MapUtil {

    /**
     * Checks that no keys in {@code map1} are also present in {@code map2}.
     * Throws a {@link DuplicateKeyException} if any overlap is found, indicating
     * that two mapping resolvers are trying to register the same URL pattern.
     *
     * @param <K>  the key type
     * @param map1 the first map (existing registrations)
     * @param map2 the second map (new registrations to merge)
     * @throws DuplicateKeyException if any key exists in both maps
     */
    public static <K> void checkDuplicateKey(Map<K, ?> map1, Map<K, ?> map2) {
        for (Object key : map1.keySet()) {
            if (map2.containsKey(key)) {
                throw new DuplicateKeyException("Duplicate uri: " + key);
            }
        }
    }

}
