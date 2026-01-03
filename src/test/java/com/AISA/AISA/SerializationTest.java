package com.AISA.AISA;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.junit.jupiter.api.Assertions;

public class SerializationTest {

    @Test
    public void testSerialization() {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(java.util.Set.of("email", "profile"))
                .state("state")
                .build();

        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

        try {
            byte[] serialized = serializer.serialize(request);
            System.out.println("Serialization successful. Size: " + serialized.length);

            Object deserialized = serializer.deserialize(serialized);
            Assertions.assertTrue(deserialized instanceof OAuth2AuthorizationRequest);
            System.out.println("Deserialization successful.");
        } catch (Exception e) {
            e.printStackTrace();
            Assertions.fail("Serialization failed: " + e.getMessage());
        }
    }
}
