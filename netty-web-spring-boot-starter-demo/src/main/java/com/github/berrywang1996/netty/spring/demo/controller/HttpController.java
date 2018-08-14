package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.PathVariable;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.PostMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;

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

}
