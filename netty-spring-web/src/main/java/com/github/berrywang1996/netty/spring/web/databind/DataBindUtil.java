package com.github.berrywang1996.netty.spring.web.databind;

import com.github.berrywang1996.netty.spring.web.util.ClassUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class DataBindUtil {

    private static final Class<DateFormat> DATE_FORMAT_ANNOTATION = DateFormat.class;

    private static final String DEFAULT_DATEFORMAT_PATTERN = new SimpleDateFormat().toPattern();

    private static final Map<Class, BeanInfo> beanInfoMap = new ConcurrentHashMap<>();

    public static <T> T parseStringToObject(Map<String, String> dataMap, Class<T> targetTypeClz) {

        // if data is not object
        if (dataMap.size() == 0 || targetTypeClz == null) {
            return null;
        }

        // if create new instance failed, return null
        T target = ClassUtil.newInstance(targetTypeClz);
        if (target == null) {
            return null;
        }

        for (Map.Entry<String, String> dataEntity : dataMap.entrySet()) {
            setObjectProperties(target,
                    new LinkedList<>(Arrays.asList(dataEntity.getKey().split("\\."))),
                    dataEntity.getValue());
        }

        return target;

    }

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
                return (T) data;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;

    }

    public static Date parseStringToDate(String data, String pattern) {

        if (StringUtil.isBlank(data)) {
            return null;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(pattern);
        try {
            return dateFormat.parse(data);
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return null;

    }

    private static <T> void setObjectProperties(T target, LinkedList<String> keys, String value) {

        BeanInfo beanInfo = getBeanInfo(target.getClass());
        PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();

        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            Method writeMethod = propertyDescriptor.getWriteMethod();
            if (writeMethod == null) {
                continue;
            }
            for (String key : keys) {
                if (("set" + key.substring(0, 1).toUpperCase() + key.substring(1)).equals(writeMethod.getName())) {
                    // get set method parameter type
                    Class<?>[] parameterTypes = writeMethod.getParameterTypes();
                    if (parameterTypes.length == 0) {
                        continue;
                    }
                    Class parameterType = parameterTypes[0];
                    if (keys.size() <= 2) {
                        try {

                            /*
                             parse value, apply type:
                                Byte
                                Short
                                Integer
                                Long
                                Boolean
                                Double
                                Float
                                Character
                                Date
                            */
                            if (parameterType == Date.class) {
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
                            e.printStackTrace();
                        }
                    } else {
                        // create new instance
                        Object childTarget = ClassUtil.newInstance(parameterTypes[0]);
                        if (childTarget == null) {
                            continue;
                        }
                        try {
                            // check read method contains instance
                            Method readMethod = propertyDescriptor.getReadMethod();
                            if (readMethod.invoke(target) == null) {
                                // set new instance
                                writeMethod.invoke(target, childTarget);
                            }
                            // set child properties
                            LinkedList<String> newKeys = new LinkedList<>(keys);
                            newKeys.poll();
                            setObjectProperties(childTarget, newKeys, value);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

    }

    private static BeanInfo getBeanInfo(Class clz) {

        BeanInfo beanInfo = null;
        try {
            beanInfo = beanInfoMap.get(clz);
            if (beanInfo == null) {
                beanInfo = Introspector.getBeanInfo(clz, Object.class);
                beanInfoMap.put(clz, beanInfo);
            }
        } catch (IntrospectionException e) {
            e.printStackTrace();
        }
        return beanInfo;

    }
}
