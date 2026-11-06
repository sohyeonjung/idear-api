package com.idear.backend.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import redis.embedded.RedisServer;

import java.io.IOException;

@TestConfiguration
public class EmbeddedRedisConfig {

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() throws IOException {
        // 테스트 컨텍스트가 여러 개(@MockitoBean 등)일 때 이미 떠 있는 서버를 재사용한다.
        try {
            redisServer = new RedisServer(6379);
            redisServer.start();
        } catch (Exception alreadyRunning) {
            redisServer = null;
        }
    }

    @PreDestroy
    public void stopRedis() throws IOException {
        if (redisServer != null) {
            redisServer.stop();
        }
    }
}
