package it.unisa.performance.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

  @GetMapping({"/", "/regione"})
  public String home() {
    return "redirect:/ocr-assistant-campania.html";
  }
}
