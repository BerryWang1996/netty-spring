package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.GetMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequestMapping("/http")
public class HttpController {

    @RequestMapping("/user/{id}")
    public void get() {
    }

    @GetMapping("/user/{id}")
    public void delete() {
    }

}
