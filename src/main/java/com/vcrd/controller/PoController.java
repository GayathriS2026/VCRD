package com.vcrd.controller;

import com.vcrd.dto.PoResponse;
import com.vcrd.service.PoService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos")
public class PoController {

    private final PoService poService;

    public PoController(PoService poService) {
        this.poService = poService;
    }

    @GetMapping("/{poNumber}")
    public PoResponse getPo(@PathVariable String poNumber,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return poService.getPo(poNumber, refresh);
    }
}
