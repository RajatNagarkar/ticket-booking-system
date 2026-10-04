package com.assignment.tickets.service;

import com.assignment.tickets.config.JwtProperties;
import com.assignment.tickets.config.SecurityConfig;
import com.assignment.tickets.dto.response.TokenResponse;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Development token issuer: mints a signed token for any user id. Admins can also act as users. */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenResponse issue(String userId, boolean admin) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(SecurityConfig.ROLES_CLAIM, admin ? List.of("ADMIN", "USER") : List.of("USER"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
    }
}
