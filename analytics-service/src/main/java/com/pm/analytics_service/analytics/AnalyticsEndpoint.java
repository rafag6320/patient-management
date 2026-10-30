package com.pm.analytics_service.analytics;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.actuate.endpoint.annotation.*;
@Component @Endpoint(id="analyticswork")
public class AnalyticsEndpoint {
    private final AnalyticsStore store;
    public AnalyticsEndpoint(AnalyticsStore store) {this.store=store;}
    @ReadOperation public Map<String,Long> summary() {return Map.of("patientsCreated",store.patientsCreated(),"quarantined",store.quarantined());}
}
