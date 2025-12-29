package com.AISA.AISA.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

        @Bean
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
                RedisTemplate<String, Object> template = new RedisTemplate<>();
                template.setConnectionFactory(connectionFactory);

                // Key는 String으로 직렬화
                template.setKeySerializer(new StringRedisSerializer());

                // Value는 String으로 직렬화 (간단한 문자열 저장을 위해)
                template.setValueSerializer(new StringRedisSerializer());

                return template;
        }

        @Bean
        public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
                RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(
                                                RedisSerializationContext.SerializationPair
                                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofHours(1)); // 기본 1시간

                // 24시간 캐싱 (장기 데이터)
                RedisCacheConfiguration longTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(
                                                RedisSerializationContext.SerializationPair
                                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofHours(24));

                // 1분 캐싱 (초단기 데이터 - 실시간 차트 부하 방지)
                RedisCacheConfiguration shortTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(
                                                RedisSerializationContext.SerializationPair
                                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofMinutes(1));

                // 중기 데이터 (30분) - 포트폴리오 진단 등
                RedisCacheConfiguration mediumTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(
                                                RedisSerializationContext.SerializationPair
                                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofMinutes(30));

                Map<String, RedisCacheConfiguration> configMap = new HashMap<>();
                configMap.put("indexChart", longTermConfig);
                configMap.put("overseasIndex", longTermConfig);
                configMap.put("overseasIndexKrw", longTermConfig);
                configMap.put("macroM2", longTermConfig);
                configMap.put("macroBaseRate", longTermConfig);
                configMap.put("macroCPI", longTermConfig);
                configMap.put("macroBond", longTermConfig);
                configMap.put("macroExchangeRate", longTermConfig);
                configMap.put("kospiUsdRatio", longTermConfig);
                configMap.put("kosdaqUsdRatio", longTermConfig);
                configMap.put("stockChart", longTermConfig); // 주식 차트 과거 데이터
                configMap.put("stockChartJson", longTermConfig); // 주식 차트 과거 데이터 (JSON 문자열)

                // 초단기 데이터 (1분)
                configMap.put("stockChartToday", shortTermConfig);
                configMap.put("stockChartTodayJson", shortTermConfig); // 주가 (1분) (JSON 문자열)
                configMap.put("stockPrice", shortTermConfig); // 주가 (1분)

                // 추가 장기 데이터 (24시간)
                configMap.put("stockMetrics", longTermConfig);
                configMap.put("stockDividend", longTermConfig);
                configMap.put("stockDividendDetail", longTermConfig);
                configMap.put("stockFinancial", longTermConfig);

                // Phase 2 추가: 순위 데이터 (24시간)
                configMap.put("financialRank", longTermConfig);
                configMap.put("dividendRank", longTermConfig);

                // Phase 2 추가: 포트폴리오 진단 (30분)
                configMap.put("portfolioDiagnosis", mediumTermConfig);

                return RedisCacheManager.builder(connectionFactory)
                                .cacheDefaults(defaultConfig)
                                .withInitialCacheConfigurations(configMap)
                                .build();
        }
}
