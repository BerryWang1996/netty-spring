package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.*;
import com.github.berrywang1996.netty.spring.web.mvc.context.Cookie;
import com.github.berrywang1996.netty.spring.web.mvc.context.HttpRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Demo HTTP controller that exercises the Netty-based MVC mapping layer.
 *
 * <p>Provides sample endpoints for path-variable extraction, request method
 * differentiation (GET vs POST), cookie management, and JSON response rendering.
 * All endpoints are served under the {@code /http} base path.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/http")
public class HttpController {

    /**
     * Handles any HTTP method on {@code /http/user/{id}/{name}} and returns a
     * plain-text response that echoes the path variables.
     *
     * @param id   the user ID extracted from the URI path
     * @param name the user name extracted from the URI path
     * @return a string in the form {@code "get<id>-<name>"}
     * @throws InterruptedException if the thread is interrupted (declared for demo purposes)
     */
    @RequestMapping("/user/{id}/{name}")
    @ResponseBody
    public String get(@PathVariable("id") Long id, @PathVariable String name) throws InterruptedException {
        return "get" + id + "-" + name;
    }

    /**
     * Handles POST requests on {@code /http/user/{id}/{name}} and returns a
     * plain-text response that echoes the path variables.
     *
     * @param id   the user ID extracted from the URI path
     * @param name the user name extracted from the URI path
     * @return a string in the form {@code "post<id>-<name>"}
     */
    @PostMapping("/user/{id}/{name}")
    @ResponseBody
    public String post(@PathVariable("id") Long id, @PathVariable String name) {
        return "post" + id + "-" + name;
    }

    /**
     * Handles GET requests on {@code /http/get} and demonstrates cookie handling.
     *
     * <p>If the incoming request does not contain a {@code NETTY_SESSIONID} cookie,
     * a new session cookie is created with a random UUID value and a 5-second
     * expiry. The full map of request cookies is returned as a JSON response body.
     *
     * @param requestContext the Netty HTTP request context providing access to
     *                       request/response cookies and headers
     * @return the map of all cookies present in the incoming request
     */
    @GetMapping("/get")
    @ResponseBody
    public Object get(HttpRequestContext requestContext) {

        Map<String, String> requestCookies = requestContext.getRequestCookies();

        // If no session cookie exists, create one with a 5-second TTL
        if (!requestCookies.containsKey("NETTY_SESSIONID")) {

            Cookie c1 = new Cookie("NETTY_SESSIONID");
            c1.setValue(UUID.randomUUID().toString());
            c1.setPath("/");
            Calendar instance = Calendar.getInstance();
            instance.setTime(new Date());
            instance.add(Calendar.SECOND, 5);
            c1.setExpires(instance.getTime());
            c1.setSecure(false);
            c1.setHttpOnly(true);
            requestContext.getResponseCookies().add(c1);

        }

        return requestCookies;
    }

}
