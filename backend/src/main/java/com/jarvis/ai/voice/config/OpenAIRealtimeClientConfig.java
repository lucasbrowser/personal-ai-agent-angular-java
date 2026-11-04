package com.jarvis.ai.voice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAIRealtimeClientConfig {
    @Bean
    RestClient.Builder openAIRealtimeRestClientBuilder() { return RestClient.builder(); }

    @Bean
    RestClient openAIRealtimeRestClient(RestClient.Builder builder, OpenAIRealtimeProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.responseTimeout());
        return builder.requestFactory(requestFactory).baseUrl("https://api.openai.com/v1/realtime").build();
    }
}
