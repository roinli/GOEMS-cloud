package com.witos.ems.server.openems;

import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class OpenemsCredentialCipher
{
    private static final String DEFAULT_KEY_REF = "env:EMS_OPENEMS_CREDENTIAL_KEY";
    private static final String CIPHER_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    public OpenemsCredentialCipher(Environment environment)
    {
        this.environment = environment;
    }

    public String encrypt(String plaintext)
    {
        if (StringUtils.isEmpty(plaintext))
        {
            throw new ServiceException("OpenEMS凭据不能为空");
        }
        try
        {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return CIPHER_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("OpenEMS凭据加密失败：" + ex.getMessage());
        }
    }

    public String decrypt(String ciphertext)
    {
        if (StringUtils.isEmpty(ciphertext) || !ciphertext.startsWith(CIPHER_PREFIX))
        {
            throw new ServiceException("OpenEMS凭据密文格式不合法");
        }
        try
        {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext.substring(CIPHER_PREFIX.length()));
            if (payload.length <= IV_LENGTH)
            {
                throw new ServiceException("OpenEMS凭据密文格式不合法");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("OpenEMS凭据解密失败，请检查部署密钥");
        }
    }

    private SecretKeySpec key() throws Exception
    {
        String keyRef = environment.getProperty("ems.openems.credential-key-ref", DEFAULT_KEY_REF);
        String value;
        if (keyRef != null && keyRef.startsWith("env:"))
        {
            value = System.getenv(keyRef.substring(4));
        }
        else if (keyRef != null && keyRef.startsWith("sys:"))
        {
            value = System.getProperty(keyRef.substring(4));
        }
        else
        {
            throw new ServiceException("OpenEMS凭据密钥只允许env:或sys:引用");
        }
        if (StringUtils.isEmpty(value))
        {
            throw new ServiceException("OpenEMS凭据密钥未配置：" + keyRef);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
