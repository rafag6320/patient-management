package com.pm.analytics_service.analytics;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/analytics")
public class AnalyticsController {
    private final AnalyticsStore store;
    public AnalyticsController(AnalyticsStore store) { this.store=store; }
    @GetMapping("/summary") public Map<String,Long> summary() { return Map.of("patientsCreated",store.patientsCreated()); }
}
