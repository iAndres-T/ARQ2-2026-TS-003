package com.medconnect.common;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * No component scan on purpose: it would pick up the library's nested auto-configuration classes as regular
 * configurations (services live in other packages, so they only see them through auto-configuration).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {
}
