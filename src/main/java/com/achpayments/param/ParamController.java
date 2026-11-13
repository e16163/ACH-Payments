package com.achpayments.param;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Parameter UI API (UC-SYS-PARAM-01). Only the SYSTEM_CONFIG permission gets here; see SecurityConfig. */
@RestController
@RequestMapping("/api/params")
public class ParamController {

    /** lastModTm and lastModBy are deliberately absent: the backend sets them. */
    public record SaveRequest(String paramDesc, String paramRawType, String paramRawValue) {
    }

    public record App(String systemName, String appName) {
    }

    private final ParamRepository repo;
    private final ParamService service;

    public ParamController(ParamRepository repo, ParamService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping("/systems")
    public List<App> systems() {
        return ParamApps.APPS.entrySet().stream().map(e -> new App(e.getKey(), e.getValue())).toList();
    }

    @GetMapping
    public List<AppParam> list(@RequestParam String systemName) {
        return repo.findBySystem(systemName);
    }

    @PutMapping("/{systemName}/{paramName}")
    public AppParam save(@PathVariable String systemName, @PathVariable String paramName,
            @RequestBody SaveRequest body, Principal user) {
        return service.save(systemName, paramName, body.paramDesc(), body.paramRawType(), body.paramRawValue(),
                user.getName());
    }

    @ExceptionHandler(ParamService.InvalidParamException.class)
    public ResponseEntity<Map<String, String>> invalid(ParamService.InvalidParamException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
