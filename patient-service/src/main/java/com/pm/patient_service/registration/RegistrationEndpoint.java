package com.pm.patient_service.registration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.actuate.endpoint.annotation.*;
@Component @Endpoint(id="registrations")
public class RegistrationEndpoint {
    private final RegistrationStore store;
    public RegistrationEndpoint(RegistrationStore store) {this.store=store;}
    @ReadOperation public Map<String,Long> summary() {return store.summary();}
}
