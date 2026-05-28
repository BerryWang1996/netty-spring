package com.github.berrywang1996.netty.spring.demo.domain;

import java.util.Date;

/**
 * Simple domain object representing a user in the demo application.
 *
 * <p>Illustrates how the Netty HTTP layer handles JSON serialization and
 * deserialization of nested POJOs. Contains basic profile fields and a
 * nested {@link Department} reference.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @see Department
 */
public class User {

    /** The user's display name. */
    private String name;

    /** The user's password (plain-text for demo purposes only). */
    private String password;

    /** The user's age. */
    private Integer age;

    /** The department the user belongs to. */
    private Department department;

    /** The date the user registered. */
    private Date registerDate;

    /**
     * Returns the user's display name.
     *
     * @return the name, or {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the user's display name.
     *
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the user's password.
     *
     * @return the password, or {@code null} if not set
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the user's password.
     *
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the user's age.
     *
     * @return the age, or {@code null} if not set
     */
    public Integer getAge() {
        return age;
    }

    /**
     * Sets the user's age.
     *
     * @param age the age to set
     */
    public void setAge(Integer age) {
        this.age = age;
    }

    /**
     * Returns the department the user belongs to.
     *
     * @return the department, or {@code null} if not set
     */
    public Department getDepartment() {
        return department;
    }

    /**
     * Sets the department the user belongs to.
     *
     * @param department the department to set
     */
    public void setDepartment(Department department) {
        this.department = department;
    }

    /**
     * Returns the date when the user registered.
     *
     * @return the registration date, or {@code null} if not set
     */
    public Date getRegisterDate() {
        return registerDate;
    }

    /**
     * Sets the date when the user registered.
     *
     * @param registerDate the registration date to set
     */
    public void setRegisterDate(Date registerDate) {
        this.registerDate = registerDate;
    }
}
