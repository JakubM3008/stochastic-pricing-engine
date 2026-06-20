package com.quant.pricing.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class ViewController {

    @GetMapping(value = "/dynamic", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String getDynamicPage() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/dynamic.html");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/portfolio", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String getPortfolioPage() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/portfolio.html");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
