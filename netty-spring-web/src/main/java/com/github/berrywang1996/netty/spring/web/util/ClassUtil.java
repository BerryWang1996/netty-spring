/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for reflective class loading and instantiation.
 *
 * <p>Provides methods to check whether a class is on the classpath and to create
 * new instances via the default (no-arg) constructor. All failures are logged
 * rather than thrown, returning {@code null} or {@code false} to the caller.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class ClassUtil {

    private static final Logger log = LoggerFactory.getLogger(ClassUtil.class);

    /**
     * Checks whether the given class name is available on the current classpath.
     *
     * @param className the fully qualified class name to check
     * @return {@code true} if the class can be loaded, {@code false} otherwise
     */
    public static boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a new instance of the class identified by the given fully qualified name
     * using its default (no-arg) constructor.
     *
     * @param className the fully qualified class name to instantiate
     * @return a new instance, or {@code null} if the class cannot be loaded or instantiated
     */
    public static Object newInstance(String className) {
        try {
            return Class.forName(className).newInstance();
        } catch (Exception e) {
            log.error("Failed to create instance for class: {}", className, e);
            return null;
        }
    }

    /**
     * Creates a new instance of the given class using its default (no-arg) constructor.
     *
     * @param <T> the type of the class to instantiate
     * @param clz the class to instantiate
     * @return a new instance, or {@code null} if instantiation fails
     */
    public static <T> T newInstance(Class<T> clz) {
        try {
            return clz.newInstance();
        } catch (Exception e) {
            log.error("Failed to create instance for class: {}", clz.getName(), e);
            return null;
        }
    }

}
