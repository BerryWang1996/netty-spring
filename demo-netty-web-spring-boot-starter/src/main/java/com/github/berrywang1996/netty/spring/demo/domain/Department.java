package com.github.berrywang1996.netty.spring.demo.domain;

/**
 * Simple domain object representing a department.
 *
 * <p>Used in the demo application to illustrate nested object serialization
 * within the {@link User} domain model when JSON responses are returned
 * from the Netty HTTP controller.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
public class Department {

    /** The display name of the department. */
    private String name;

    /**
     * Returns the department name.
     *
     * @return the department name, or {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the department name.
     *
     * @param name the department name to set
     */
    public void setName(String name) {
        this.name = name;
    }
}
