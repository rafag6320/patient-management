package com.pm.auth_service.config;

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
        if ((profiles.contains("prod") || profiles.contains("aws")) && (profiles.contains("local") || profiles.contains("docker") || profiles.contains("test"))) {
            throw new IllegalStateException("prod/aws cannot be combined with development/test profiles");
        }
        // Test settings are explicit in test resources; H2 is absent from production artifacts.
        if (profiles.contains("test")) { return; }
        check(environment,errors,"server.port",value->"0".equals(value) || port(value));
        check(environment,errors,"spring.datasource.url",RuntimeConfiguration::postgres);
        check(environment,errors,"spring.datasource.username",RuntimeConfiguration::nonblank);
        check(environment,errors,"spring.datasource.password",RuntimeConfiguration::nonblank);
        check(environment,errors,"jwt.secret",value->nonblank(value) && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>=32);
        if (!profiles.contains("local") && !profiles.contains("docker")
            && !"never".equals(read(environment,"spring.sql.init.mode"))) { errors.add("spring.sql.init.mode"); }

        if (!errors.isEmpty()) { throw new IllegalStateException("Missing or invalid configuration keys: "+String.join(", ",errors)); }
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


}
