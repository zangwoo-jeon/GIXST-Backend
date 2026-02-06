package com.AISA.AISA.global.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
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
                template.setKeySerializer(new StringRedisSerializer());
                template.setValueSerializer(new StringRedisSerializer());
                return template;
        }

        @Bean("oAuth2RedisTemplate")
        public RedisTemplate<String, Object> oAuth2RedisTemplate(RedisConnectionFactory connectionFactory) {
                RedisTemplate<String, Object> template = new RedisTemplate<>();
                template.setConnectionFactory(connectionFactory);
                template.setKeySerializer(new StringRedisSerializer());
                template.setValueSerializer(new JdkSerializationRedisSerializer());
                template.setHashKeySerializer(new StringRedisSerializer());
                template.setHashValueSerializer(new JdkSerializationRedisSerializer());
                return template;
        }

        @Bean
        public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
                RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofHours(1));

                RedisCacheConfiguration longTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofHours(24));

                RedisCacheConfiguration shortTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofMinutes(1));

                RedisCacheConfiguration mediumTermConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofMinutes(30));

                RedisCacheConfiguration sevenDayConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                                .disableCachingNullValues()
                                .entryTtl(Duration.ofDays(7));

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
                configMap.put("stockChart", longTermConfig);
                configMap.put("stockChartJson", longTermConfig);
                configMap.put("stockChartToday", shortTermConfig);
                configMap.put("stockChartTodayJson", shortTermConfig);
                configMap.put("stockPrice", shortTermConfig);
                configMap.put("stockMetrics", longTermConfig);
                configMap.put("stockDividend", longTermConfig);
                configMap.put("stockDividendDetail", longTermConfig);
                configMap.put("stockFinancial", longTermConfig);
                configMap.put("financialRank", longTermConfig);
                configMap.put("dividendRank", longTermConfig);
                configMap.put("ecosBondYield", longTermConfig);
                configMap.put("portfolioDiagnosis", mediumTermConfig);
                configMap.put("portfolioDiagnosis", mediumTermConfig);
                configMap.put("staticAnalysis", sevenDayConfig);
                configMap.put("overseasStaticAnalysis", sevenDayConfig);
                configMap.put("marketValuation", longTermConfig);
                configMap.put("overseasIndexStatus", shortTermConfig);
                configMap.put("exchangeRateStatus", shortTermConfig);
                configMap.put("indexStatus", shortTermConfig);
                configMap.put("exchangeRateMap", shortTermConfig);
                configMap.put("overseasMarketCapRank", longTermConfig);
                configMap.put("overseasShareholderReturnRank", longTermConfig);

                return RedisCacheManager.builder(connectionFactory)
                                .cacheDefaults(defaultConfig)
                                .withInitialCacheConfigurations(configMap)
                                .build();
        }
}
