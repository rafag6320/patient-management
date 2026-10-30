package com.pm.api_gateway.config;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.http.client.reactive.*;
import reactor.netty.http.client.HttpClient;
@Configuration(proxyBeanMethods=false)
public class UpstreamHttpConfiguration {
    private static HttpClient dns(HttpClient client) {
        return client.disableRetry(true).resolver(spec->spec.cacheMaxTimeToLive(Duration.ofSeconds(5)).cacheMinTimeToLive(Duration.ZERO).cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
    @Bean HttpClientCustomizer gatewayDns() {return UpstreamHttpConfiguration::dns;}
    @Bean ClientHttpConnector authConnector() {return new ReactorClientHttpConnector(dns(HttpClient.create()));}
}
