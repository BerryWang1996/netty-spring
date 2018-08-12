package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.GetMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import org.springframework.stereotype.Controller;

@Controller
public class HttpController {

    @RequestMapping(value = "/test", port = 1230, method = HttpRequestMethod.HEAD)
    public void test() {

    }

    @GetMapping(value = {"/test2", "/test"})
    public void test2() {

    }

}
