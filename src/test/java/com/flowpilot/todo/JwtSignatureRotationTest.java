package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtSignatureRotationTest {
    @Test
    void verifiesRsaSignatureAndRefreshesJwksForNewKeyId() throws Exception {
        RSAKey firstKey = key("key-1");
        RSAKey secondKey = key("key-2");
        AtomicReference<String> jwks = new AtomicReference<>(publicJwks(firstKey));
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = jwks.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();

        try {
            String issuer = "https://issuer.example";
            String jwkSetUri = "http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort() + "/jwks";
            SecurityConfig configuration = new SecurityConfig();
            OAuth2TokenValidator<Jwt> validator =
                configuration.todoJwtValidator(issuer, "todo-api");
            JwtDecoder decoder = configuration.jwtDecoder(jwkSetUri, validator);

            assertThat(decoder.decode(token(firstKey, issuer)).getSubject())
                .isEqualTo("stable-user-id");
            jwks.set(publicJwks(secondKey));
            assertThat(decoder.decode(token(secondKey, issuer)).getSubject())
                .isEqualTo("stable-user-id");
        } finally {
            server.stop(0);
        }
    }

    private static RSAKey key(String keyId) throws Exception {
        return new RSAKeyGenerator(2048)
            .keyID(keyId)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
    }

    private static String publicJwks(RSAKey key) {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    private static String token(RSAKey key, String issuer) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("stable-user-id")
            .audience("todo-api")
            .issueTime(Date.from(now.minusSeconds(10)))
            .notBeforeTime(Date.from(now.minusSeconds(10)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key.toPrivateKey()));
        return token.serialize();
    }
}
