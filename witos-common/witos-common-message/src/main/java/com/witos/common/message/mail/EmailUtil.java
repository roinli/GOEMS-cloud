package com.witos.common.message.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EmailUtil {

    @Value("${ems.mail.from:${spring.mail.username:}}")
    private String emailFrom;

    @Autowired
    private JavaMailSender mailSender;

    public void sendSimpleMail(String subject, String content, String mail)
    {
        try
        {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(mail);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);
        }
        catch (MailException exception)
        {
            log.error("邮件发送失败，收件人={}，异常类型={}", maskMail(mail), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String maskMail(String mail)
    {
        if (mail == null || !mail.contains("@"))
        {
            return "***";
        }
        String[] parts = mail.split("@", 2);
        String prefix = parts[0].isEmpty() ? "*" : parts[0].substring(0, 1) + "***";
        return prefix + "@" + parts[1];
    }
}
