package com.github.berrywang1996.netty.spring.demo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.demo.domain.Department;
import com.github.berrywang1996.netty.spring.demo.domain.User;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.GetMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.PathVariable;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestParam;
import com.github.berrywang1996.netty.spring.web.mvc.context.HttpRequestContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.springframework.stereotype.Controller;

import java.util.Arrays;
import java.util.Map;

@Controller
@RequestMapping("/http")
public class HttpController {

    @RequestMapping("/test")
    public void get(HttpRequestContext requestContext) {
        System.out.println(requestContext.getRequestUri());
    }

    @GetMapping("/test1")
    public void test1(HttpHeaders headers) throws JsonProcessingException {
        System.out.println(new ObjectMapper().writeValueAsString(headers));
    }

    @GetMapping("/test2")
    public void test2(HttpRequest httpRequest) {
        System.out.println(httpRequest.method());
    }

    @GetMapping("/test3")
    public void test3(ChannelHandlerContext ctx) {
        ByteBuf content = null;
        FullHttpResponse response = null;
        // html response
        content = Unpooled.copiedBuffer("<html><head><title>this is template page</title></head><body>this is " +
                "test3</body></html>", CharsetUtil.UTF_8);
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");

        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        ctx.writeAndFlush(response);
    }

    @GetMapping("/user")
    public void user(User user) throws JsonProcessingException {
        System.out.println(new ObjectMapper().writeValueAsString(user));
    }

    @GetMapping("/dept")
    public void dept(Department department) throws JsonProcessingException {
        System.out.println(new ObjectMapper().writeValueAsString(department));
    }

    @GetMapping("/userAndDept")
    public void userAndDept(HttpRequestContext requestContext, String flag, User user, Department department) throws JsonProcessingException {
        Map<String, String> requestParameters = requestContext.getRequestParameters();
        for (Map.Entry<String, String> entry : requestParameters.entrySet()) {
            System.out.println(entry.getKey() + "\t\t" + entry.getValue());
        }
        System.out.println(new ObjectMapper().writeValueAsString(Arrays.asList(flag, user, department)));
    }

    @GetMapping("/request")
    public void request(@RequestParam("id") Byte id, @RequestParam String cmd) {
        System.out.println("id:" + id + ",cmd=" + cmd);
    }

    @GetMapping("/user/{id}/{cmd}")
    public void restUrl(@PathVariable("id") Long id, @PathVariable("cmd") String dsa) {
        System.out.println("id:" + id + ",cmd=" + dsa);
    }

}
