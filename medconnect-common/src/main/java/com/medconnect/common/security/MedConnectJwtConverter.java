package com.medconnect.common.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Claims {@code role} and {@code scopes} (spec §2) → {@code ROLE_<role>} and {@code SCOPE_<scope>} authorities. */
public class MedConnectJwtConverter implements Converter<Jwt, JwtAuthenticationToken> {

	@Override
	public JwtAuthenticationToken convert(Jwt jwt) {
		List<GrantedAuthority> authorities = new ArrayList<>();
		String role = jwt.getClaimAsString(CurrentUser.ROLE_CLAIM);
		if (role != null) {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
		}
		List<String> scopes = jwt.getClaimAsStringList(CurrentUser.SCOPES_CLAIM);
		if (scopes != null) {
			scopes.forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
		}
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}
}
