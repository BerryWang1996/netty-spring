package com.github.berrywang1996.netty.spring.web.databind;

import com.github.berrywang1996.netty.spring.web.util.ClassUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for binding HTTP request parameters (string key-value pairs) to Java objects.
 *
 * <p>Supports binding to:
 * <ul>
 *   <li>Boxed primitive types (Byte, Short, Integer, Long, Boolean, Double, Float, Character)</li>
 *   <li>{@link Date} fields with configurable format patterns via the {@link DateFormat} annotation</li>
 *   <li>Nested POJO structures using dot-separated keys (e.g. "address.city")</li>
 * </ul>
 *
 * <p>This class uses Java Beans introspection with a thread-safe cache to discover
 * property descriptors, and Spring-style parameter names for the target type.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
public class DataBindUtil {

    private static final Logger log = LoggerFactory.getLogger(DataBindUtil.class);

    private static final Class<DateFormat> DATE_FORMAT_ANNOTATION = DateFormat.class;

    /** Default date format pattern used when no {@link DateFormat} annotation is present. */
    private static final String DEFAULT_DATEFORMAT_PATTERN = "yy-M-d ah:mm";

    /** Thread-safe cache of BeanInfo instances keyed by class, avoiding repeated introspection. */
    private static final Map<Class, BeanInfo> beanInfoMap = new ConcurrentHashMap<>();

    /**
     * Parses a map of string key-value pairs into an instance of the specified target type.
     *
     * <p>Dot-separated keys (e.g. "address.city") are used to set nested object properties.
     * Each value is converted to the appropriate property type using reflection.
     *
     * @param <T>          the target type
     * @param dataMap      the key-value parameter map from the HTTP request
     * @param targetTypeClz the class of the target object to create and populate
     * @return a new instance of the target type with properties set, or {@code null} if
     *         the map is empty, the class is null, or instantiation fails
     */
    public static <T> T parseStringToObject(Map<String, String> dataMap, Class<T> targetTypeClz) {

        if (dataMap.size() == 0 || targetTypeClz == null) {
            return null;
        }

        // Instantiate the target object via no-arg constructor
        T target = ClassUtil.newInstance(targetTypeClz);
        if (target == null) {
            return null;
        }

        // Set each property by splitting dot-separated keys for nested access
        for (Map.Entry<String, String> dataEntity : dataMap.entrySet()) {
            setObjectProperties(target,
                    new LinkedList<>(Arrays.asList(dataEntity.getKey().split("\\."))),
                    dataEntity.getValue());
        }

        return target;

    }

    /**
     * Checks whether the given class is a boxed primitive type supported for direct string parsing.
     *
     * @param targetTypeClz the class to check
     * @return {@code true} if the class is a boxed primitive type (Byte, Short, Integer, Long,
     *         Boolean, Double, Float, or Character)
     */
    public static boolean isBasicType(Class targetTypeClz) {

        return targetTypeClz == Byte.class ||
                targetTypeClz == Short.class ||
                targetTypeClz == Integer.class ||
                targetTypeClz == Long.class ||
                targetTypeClz == Boolean.class ||
                targetTypeClz == Double.class ||
                targetTypeClz == Float.class ||
                targetTypeClz == Character.class;

    }

    /**
     * Parses a string value into the specified boxed primitive type.
     *
     * @param <T>          the target type
     * @param data         the string value to parse
     * @param targetTypeClz the target boxed primitive class
     * @return the parsed value, or {@code null} if the input is blank or parsing fails
     */
    public static <T> T parseStringToBasicType(String data, Class<T> targetTypeClz) {

        if (StringUtil.isBlank(data)) {
            return null;
        }

        try {
            if (targetTypeClz == Byte.class) {
                return (T) Byte.valueOf(data);
            } else if (targetTypeClz == Short.class) {
                return (T) Short.valueOf(data);
            } else if (targetTypeClz == Integer.class) {
                return (T) Integer.valueOf(data);
            } else if (targetTypeClz == Long.class) {
                return (T) Long.valueOf(data);
            } else if (targetTypeClz == Boolean.class) {
                return (T) Boolean.valueOf(data);
            } else if (targetTypeClz == Double.class) {
                return (T) Double.valueOf(data);
            } else if (targetTypeClz == Float.class) {
                return (T) Float.valueOf(data);
            } else if (targetTypeClz == Character.class) {
                return (T) Character.valueOf(data.charAt(0));
            } else {
                // For String and other types, return the raw string value
                return (T) data;
            }

        } catch (Exception e) {
            log.error("Failed to parse '{}' to type {}", data, targetTypeClz.getSimpleName(), e);
        }
        return null;

    }

    /**
     * Parses a date string using the specified pattern.
     *
     * <p>First attempts to parse as a {@link LocalDate} using {@link DateTimeFormatter},
     * then falls back to {@link SimpleDateFormat} for time-inclusive patterns that
     * cannot be parsed as date-only.
     *
     * @param data    the date string to parse
     * @param pattern the date format pattern (e.g. "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss")
     * @return the parsed {@link Date}, or {@code null} if the input is blank or parsing fails
     */
    public static Date parseStringToDate(String data, String pattern) {

        if (StringUtil.isBlank(data)) {
            return null;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDate localDate = LocalDate.parse(data, formatter);
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            // Fallback to SimpleDateFormat for time-inclusive patterns
            try {
                return new SimpleDateFormat(pattern).parse(data);
            } catch (ParseException ex) {
                log.error("Failed to parse date '{}' with pattern '{}': {}", data, pattern, ex.getMessage());
            }
        }

        return null;

    }

    /**
     * Recursively sets properties on the target object using a chain of dot-separated keys.
     *
     * <p>For leaf properties (keys.size() <= 2), the value is parsed and set directly.
     * For nested properties (keys.size() > 2), a child object is created if needed
     * and the remaining keys are applied recursively.
     *
     * @param <T>    the target type
     * @param target the object to set properties on
     * @param keys   the remaining property name chain (e.g. ["address", "city"])
     * @param value  the string value to set
     */
    private static <T> void setObjectProperties(T target, LinkedList<String> keys, String value) {

        BeanInfo beanInfo = getBeanInfo(target.getClass());
        PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();

        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            Method writeMethod = propertyDescriptor.getWriteMethod();
            if (writeMethod == null) {
                continue;
            }
            for (String key : keys) {
                // Match the setter method name against the current key
                if (("set" + key.substring(0, 1).toUpperCase() + key.substring(1)).equals(writeMethod.getName())) {
                    Class<?>[] parameterTypes = writeMethod.getParameterTypes();
                    if (parameterTypes.length == 0) {
                        continue;
                    }
                    Class parameterType = parameterTypes[0];
                    if (keys.size() <= 2) {
                        // Leaf property: parse and set the value directly
                        try {
                            if (parameterType == Date.class) {
                                // Use @DateFormat annotation pattern if present, otherwise default
                                String parseDatePattern;
                                if (writeMethod.isAnnotationPresent(DATE_FORMAT_ANNOTATION)) {
                                    parseDatePattern = writeMethod.getAnnotation(DATE_FORMAT_ANNOTATION).pattern();
                                } else {
                                    parseDatePattern = DEFAULT_DATEFORMAT_PATTERN;
                                }
                                writeMethod.invoke(target, parseStringToDate(value, parseDatePattern));
                            } else {
                                writeMethod.invoke(target, parseStringToBasicType(value, parameterType));
                            }

                        } catch (IllegalAccessException | InvocationTargetException e) {
                            log.error("Failed to set property '{}' on {}", key, target.getClass().getSimpleName(), e);
                        }
                    } else {
                        // Nested property: create child object and recurse
                        Object childTarget = ClassUtil.newInstance(parameterTypes[0]);
                        if (childTarget == null) {
                            continue;
                        }
                        try {
                            // Only set a new child instance if the getter returns null
                            Method readMethod = propertyDescriptor.getReadMethod();
                            if (readMethod.invoke(target) == null) {
                                writeMethod.invoke(target, childTarget);
                            }
                            // Remove the first key and recurse into the child object
                            LinkedList<String> newKeys = new LinkedList<>(keys);
                            newKeys.poll();
                            setObjectProperties(childTarget, newKeys, value);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            log.error("Failed to set nested property '{}' on {}", key, target.getClass().getSimpleName(), e);
                        }
                    }
                }
            }
        }

    }

    /**
     * Returns the cached {@link BeanInfo} for the given class, introspecting it on first access.
     *
     * @param clz the class to introspect
     * @return the {@link BeanInfo} for the class, or {@code null} if introspection fails
     */
    private static BeanInfo getBeanInfo(Class clz) {

        BeanInfo beanInfo = null;
        try {
            beanInfo = beanInfoMap.get(clz);
            if (beanInfo == null) {
                beanInfo = Introspector.getBeanInfo(clz, Object.class);
                beanInfoMap.put(clz, beanInfo);
            }
        } catch (IntrospectionException e) {
            log.error("Failed to introspect bean class: {}", clz.getName(), e);
        }
        return beanInfo;

    }
}
