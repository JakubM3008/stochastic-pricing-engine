package com.quant.pricing.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class ViewController {

    @GetMapping("/demo")
    public RedirectView demo() {
        return new RedirectView("/index.html?demo");
    }
}
