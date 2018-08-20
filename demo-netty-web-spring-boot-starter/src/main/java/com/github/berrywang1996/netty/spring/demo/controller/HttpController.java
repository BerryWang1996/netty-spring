package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.*;
import com.github.berrywang1996.netty.spring.web.mvc.context.Cookie;
import com.github.berrywang1996.netty.spring.web.mvc.context.HttpRequestContext;
import org.springframework.stereotype.Controller;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/http")
public class HttpController {

    @RequestMapping("/user/{id}/{name}")
    @ResponseBody
    public String get(@PathVariable("id") Long id, @PathVariable String name) {
        return "get" + id + "-" + name;
    }

    @PostMapping("/user/{id}/{name}")
    @ResponseBody
    public String post(@PathVariable("id") Long id, @PathVariable String name) {
        return "post" + id + "-" + name;
    }

    @GetMapping("/get")
    @ResponseBody
    public Object get(HttpRequestContext requestContext) {

        Map<String, String> requestCookies = requestContext.getRequestCookies();

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
