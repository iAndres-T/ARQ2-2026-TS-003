package com.medconnect.identity_service.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.medconnect.common.web.ApiException;

/** At least 10 characters with upper, lower, digit and symbol (D10). */
@Component
public class PasswordPolicy {

	public void check(String password) {
		boolean ok = password != null && password.length() >= 10
				&& password.chars().anyMatch(Character::isUpperCase)
				&& password.chars().anyMatch(Character::isLowerCase)
				&& password.chars().anyMatch(Character::isDigit)
				&& password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
		if (!ok) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Weak password",
					"Password must have at least 10 characters with upper case, lower case, a digit and a symbol");
		}
	}
}
