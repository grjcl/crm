package com.crm.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigurationTest {
    @Test
    void defaultsToDatabaseNine() throws IOException {
        assertThat(redisProperties(new MockEnvironment()).getDatabase()).isEqualTo(9);
    }

    @Test
    void allowsDatabaseOverride() throws IOException {
        assertThat(redisProperties(new MockEnvironment().withProperty("REDIS_DATABASE", "4"))
                .getDatabase()).isEqualTo(4);
    }

    private RedisProperties redisProperties(MockEnvironment environment) throws IOException {
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("spring.data.redis", Bindable.of(RedisProperties.class)).get();
    }
}
