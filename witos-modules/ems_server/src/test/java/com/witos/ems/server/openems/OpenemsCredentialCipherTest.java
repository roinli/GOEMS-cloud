package com.witos.ems.server.openems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpenemsCredentialCipherTest
{
    private static final String KEY_PROPERTY = "ems.openems.test.credential.key";

    @BeforeEach
    void setUp()
    {
        System.setProperty(KEY_PROPERTY, "test-only-deployment-secret");
    }

    @AfterEach
    void tearDown()
    {
        System.clearProperty(KEY_PROPERTY);
    }

    @Test
    void encryptDecryptUsesDeploymentKeyAndDoesNotStorePlaintext()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ems.openems.credential-key-ref", "sys:" + KEY_PROPERTY);
        OpenemsCredentialCipher cipher = new OpenemsCredentialCipher(environment);

        String encrypted = cipher.encrypt("api-key-123");

        assertNotEquals("api-key-123", encrypted);
        assertEquals("api-key-123", cipher.decrypt(encrypted));
    }
}
