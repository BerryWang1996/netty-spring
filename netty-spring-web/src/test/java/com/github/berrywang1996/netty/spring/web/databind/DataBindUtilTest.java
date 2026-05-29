package com.github.berrywang1996.netty.spring.web.databind;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataBindUtilTest {

    // ---- Basic type parsing ----

    @Test
    void parseStringToBasicTypeHandlesAllBoxedTypes() {
        assertEquals(42, DataBindUtil.parseStringToBasicType("42", Integer.class));
        assertEquals(42L, DataBindUtil.parseStringToBasicType("42", Long.class));
        assertEquals(true, DataBindUtil.parseStringToBasicType("true", Boolean.class));
        assertEquals(3.14, DataBindUtil.parseStringToBasicType("3.14", Double.class));
        assertEquals(2.5f, DataBindUtil.parseStringToBasicType("2.5", Float.class));
        assertEquals((byte) 7, DataBindUtil.parseStringToBasicType("7", Byte.class));
        assertEquals((short) 100, DataBindUtil.parseStringToBasicType("100", Short.class));
        assertEquals('A', DataBindUtil.parseStringToBasicType("ABC", Character.class));
    }

    @Test
    void parseStringToBasicTypeHandlesPrimitiveTypes() {
        assertEquals(42, DataBindUtil.parseStringToBasicType("42", int.class));
        assertEquals(42L, DataBindUtil.parseStringToBasicType("42", long.class));
        assertEquals(true, DataBindUtil.parseStringToBasicType("true", boolean.class));
        assertEquals(3.14, DataBindUtil.parseStringToBasicType("3.14", double.class));
    }

    @Test
    void parseStringToBasicTypeReturnsDefaultForBlankPrimitive() {
        // Primitive types should return defaults instead of null to prevent NPE during unboxing
        assertEquals(0, DataBindUtil.parseStringToBasicType(null, int.class));
        assertEquals(0L, DataBindUtil.parseStringToBasicType("", long.class));
        assertEquals(false, DataBindUtil.parseStringToBasicType("  ", boolean.class));
    }

    @Test
    void parseStringToBasicTypeReturnsNullForBlankBoxed() {
        assertNull(DataBindUtil.parseStringToBasicType(null, Integer.class));
        assertNull(DataBindUtil.parseStringToBasicType("", Long.class));
    }

    @Test
    void isBasicTypeRecognizesPrimitivesAndBoxed() {
        assertTrue(DataBindUtil.isBasicType(int.class));
        assertTrue(DataBindUtil.isBasicType(Integer.class));
        assertTrue(DataBindUtil.isBasicType(boolean.class));
        assertTrue(DataBindUtil.isBasicType(Boolean.class));
        assertFalse(DataBindUtil.isBasicType(String.class));
        assertFalse(DataBindUtil.isBasicType(Object.class));
    }

    // ---- Flat object binding ----

    @Test
    void parseStringToObjectBindsFlatProperties() {
        Map<String, String> data = new HashMap<>();
        data.put("name", "Alice");
        data.put("age", "30");

        Person person = DataBindUtil.parseStringToObject(data, Person.class);

        assertNotNull(person);
        assertEquals("Alice", person.getName());
        assertEquals(30, person.getAge());
    }

    // ---- v1.7.0 Fix #2: Nested property binding ----

    @Test
    void parseStringToObjectBindsNestedProperties() {
        Map<String, String> data = new HashMap<>();
        data.put("name", "Bob");
        data.put("address.city", "Shanghai");
        data.put("address.zip", "200000");

        Person person = DataBindUtil.parseStringToObject(data, Person.class);

        assertNotNull(person);
        assertEquals("Bob", person.getName());
        assertNotNull(person.getAddress(), "Nested address should be created");
        assertEquals("Shanghai", person.getAddress().getCity());
        assertEquals("200000", person.getAddress().getZip());
    }

    @Test
    void parseStringToObjectDoesNotCrossBindNestedKeyToRootProperty() {
        // If root object has a "city" property and the data key is "address.city",
        // only the nested address.city should be set, NOT the root city.
        Map<String, String> data = new HashMap<>();
        data.put("address.city", "Beijing");

        PersonWithCity person = DataBindUtil.parseStringToObject(data, PersonWithCity.class);

        assertNotNull(person);
        // Root "city" must NOT be set by the "address.city" key
        assertNull(person.getCity(), "Root 'city' must not be set by nested key 'address.city'");
        assertNotNull(person.getAddress());
        assertEquals("Beijing", person.getAddress().getCity());
    }

    @Test
    void parseStringToObjectReturnsNullForEmptyMap() {
        assertNull(DataBindUtil.parseStringToObject(new HashMap<>(), Person.class));
    }

    @Test
    void parseStringToObjectReturnsNullForNullClass() {
        Map<String, String> data = new HashMap<>();
        data.put("name", "test");
        assertNull(DataBindUtil.parseStringToObject(data, null));
    }

    // ---- Test DTOs ----

    public static class Person {
        private String name;
        private int age;
        private Address address;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }
    }

    public static class PersonWithCity {
        private String city;
        private Address address;

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }
    }

    public static class Address {
        private String city;
        private String zip;

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getZip() { return zip; }
        public void setZip(String zip) { this.zip = zip; }
    }
}
