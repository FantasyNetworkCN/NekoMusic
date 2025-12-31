package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Random;

public class EmailService {
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final ConfigManager configManager;
    private final String emailTemplate;

    public EmailService(ConfigManager configManager) {
        this.configManager = configManager;
        this.emailTemplate = loadEmailTemplate();
    }

    /**
     * 加载邮件模板
     */
    private String loadEmailTemplate() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("email.html")) {
            if (is == null) {
                logger.error("无法加载邮件模板 email.html");
                return getDefaultEmailTemplate();
            }
            return new String(is.readAllBytes(), "UTF-8");
        } catch (IOException e) {
            logger.error("加载邮件模板失败", e);
            return getDefaultEmailTemplate();
        }
    }

    /**
     * 默认邮件模板
     */
    private String getDefaultEmailTemplate() {
        return "<!DOCTYPE html>" +
               "<html><head><meta charset='UTF-8'><title>验证码</title></head>" +
               "<body>" +
               "<h2>您好，</h2>" +
               "<p>感谢您注册 NekoMusic！</p>" +
               "<p>您的验证码是：<strong>{{verificationCode}}</strong></p>" +
               "<p>此验证码将在5分钟内有效，请尽快使用。</p>" +
               "</body></html>";
    }

    /**
     * 发送验证码邮件
     */
    public boolean sendVerificationCode(String toEmail, String username, String verificationCode) {
        String subject = "NekoMusic - 验证码";
        String content = emailTemplate.replace("{{verificationCode}}", verificationCode);

        return sendEmail(toEmail, subject, content);
    }

    /**
     * 发送邮件
     */
    private boolean sendEmail(String to, String subject, String content) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", configManager.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(configManager.getSmtpPort()));
            props.put("mail.smtp.auth", "true");

            // 根据配置设置SSL或TLS
            if (configManager.isSmtpSsl()) {
                props.put("mail.smtp.ssl.enable", "true");
            } else if (configManager.isSmtpTls()) {
                props.put("mail.smtp.starttls.enable", "true");
            }

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                        configManager.getSmtpUsername(),
                        configManager.getSmtpPassword()
                    );
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(configManager.getSmtpUsername()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setContent(content, "text/html; charset=utf-8");

            Transport.send(message);
            logger.info("验证码邮件已发送至: {}", to);
            return true;
        } catch (Exception e) {
            logger.error("发送邮件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成随机验证码
     */
    public String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }
}