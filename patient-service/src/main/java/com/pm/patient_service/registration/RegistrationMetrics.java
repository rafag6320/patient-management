package com.pm.patient_service.registration;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
@Component
public class RegistrationMetrics implements MeterBinder {
    private final RegistrationStore store;
    public RegistrationMetrics(RegistrationStore store) {this.store=store;}
    @Override public void bindTo(MeterRegistry registry) {
        for(String state:new String[]{"billingPending","requiresReview","eventsPending","eventsConfirmed","recoveriesConfirmed"}) {
            Gauge.builder("registration.work",store,value->{try{return value.summary().get(state).doubleValue();}catch(RuntimeException unavailable){return Double.NaN;}}).tag("state",state).register(registry);
        }
    }
}
