package com.idear.backend.global.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	/**
	 * 기존에는 타임아웃이 없어(무한 대기) 게이트웨이가 응답하지 않으면 요청 스레드가 묶일 수 있었다.
	 */
	@Bean
	public RestTemplate restTemplate(
			@Value("${blockchain.gateway.connect-timeout-ms:3000}") long connectTimeoutMs,
			@Value("${blockchain.gateway.read-timeout-ms:10000}") long readTimeoutMs
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
		factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		return new RestTemplate(factory);
	}
}
