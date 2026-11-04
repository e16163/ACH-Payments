package com.achpayments.transfer;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoutingController {

    public record Bank(String routingNo, String bankName) {
    }

    private final RoutingService routing;

    public RoutingController(RoutingService routing) {
        this.routing = routing;
    }

    @GetMapping("/api/routing/{routingNo}")
    public ResponseEntity<?> lookup(@PathVariable String routingNo) {
        return routing.bankName(routingNo)
                .<ResponseEntity<?>>map(name -> ResponseEntity.ok(new Bank(routingNo, name)))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", RoutingService.INVALID)));
    }
}
