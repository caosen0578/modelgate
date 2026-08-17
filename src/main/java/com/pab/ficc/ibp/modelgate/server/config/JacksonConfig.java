package com.pab.ficc.ibp.modelgate.server.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        JavaTimeModule module = new JavaTimeModule();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));

        return new ObjectMapper()
                .registerModule(module)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 该 Bean 被 @Primary 标记后，WebFlux 的 Jackson2JsonDecoder 会用它解码所有请求体。
                // 裸 new ObjectMapper() 默认开启 FAIL_ON_UNKNOWN_PROPERTIES（Spring Boot 自动装配的
                // ObjectMapper 默认是关闭的），前端多传一个 BO 未定义的字段就会解码失败并返回 400。
                // 此处显式关闭，与 Spring Boot 默认行为对齐，保证接口对请求体字段向前兼容。
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
