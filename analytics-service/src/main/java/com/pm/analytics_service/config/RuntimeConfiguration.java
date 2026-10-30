package com.pm.analytics_service.config;

import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;

/** Checks configuration before creating datasource, Flyway or network clients. Never includes values in errors. */
@Configuration(proxyBeanMethods=false)
public class RuntimeConfiguration {
    @Bean
    static BeanFactoryPostProcessor requiredConfiguration(Environment environment) {
        return factory->validate(environment);
    }

    static void validate(Environment environment) {
        var profiles=new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        var errors=new ArrayList<String>();
        if (profiles.contains("prod") && (profiles.contains("local") || profiles.contains("docker") || profiles.contains("test"))) {
            throw new IllegalStateException("prod cannot be combined with development/test profiles");
        }
        if(profiles.contains("aws")) {
            if(profiles.contains("local")||profiles.contains("docker")||profiles.contains("test")) {throw new IllegalStateException("aws cannot be combined with development/test profiles");}
            check(environment,errors,"KAFKA_USERNAME",RuntimeConfiguration::jaasValue);
            check(environment,errors,"KAFKA_PASSWORD",RuntimeConfiguration::jaasValue);
        }
        // Test settings are explicit in test resources; H2 is absent from production artifacts.
        if (profiles.contains("test")) { return; }
        check(environment,errors,"server.port",value->"0".equals(value) || port(value));
        check(environment,errors,"spring.kafka.bootstrap-servers",RuntimeConfiguration::brokers);
        check(environment,errors,"spring.datasource.url",RuntimeConfiguration::postgres);
        check(environment,errors,"spring.datasource.username",RuntimeConfiguration::nonblank);
        check(environment,errors,"spring.datasource.password",RuntimeConfiguration::nonblank);

        if (!errors.isEmpty()) { throw new IllegalStateException("Missing or invalid configuration keys: "+String.join(", ",errors)); }
    }

    private static boolean jaasValue(String value) {
        return nonblank(value) && !value.contains("\"") && !value.contains("\\") && !value.contains("\n") && !value.contains("\r");
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static String read(Environment environment,String key) {
        try { return environment.getProperty(key, ""); }
        catch (IllegalArgumentException ex) { return ""; }
    }
    private static void check(Environment environment,List<String> errors,String key,java.util.function.Predicate<String> rule) {
        String value=read(environment,key);
        try { if (!rule.test(value)) { errors.add(key); } }
        catch (RuntimeException ex) { errors.add(key); }
    }
    private static boolean port(String value) {
        try { int port=Integer.parseInt(value); return port>0 && port<=65535; }
        catch (NumberFormatException ex) { return false; }
    }


    private static boolean postgres(String value) {
        if (!value.startsWith("jdbc:postgresql://")) { return false; }
        var uri=java.net.URI.create(value.substring(5));
        return uri.getHost()!=null && uri.getPath()!=null && uri.getPath().length()>1
            && (uri.getPort()==-1 || (uri.getPort()>0 && uri.getPort()<=65535));
    }
    private static boolean brokers(String value) {
        if (!nonblank(value)) { return false; }
        for (String broker:value.split(",",-1)) {
            broker=broker.trim(); int separator=broker.lastIndexOf(':');
            if (separator<=0 || broker.contains("://") || broker.matches(".*\\s.*")
                || !port(broker.substring(separator+1))) { return false; }
        }
        return true;
    }

}
