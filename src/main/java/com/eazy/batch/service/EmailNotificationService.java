package com.eazy.batch.service;

import com.eazy.batch.autoconfigure.BatchProcessorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Service for sending email notifications
 */
@Slf4j
@Service
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final boolean enabled;

    public EmailNotificationService(BatchProcessorProperties properties) {
        this.fromEmail = properties.getFromEmail();
        this.enabled = properties.isEmailNotificationsEnabled();

        if (enabled) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(properties.getSmtpHost());
            sender.setPort(properties.getSmtpPort());
            sender.setUsername(properties.getSmtpUsername());
            sender.setPassword(properties.getSmtpPassword());

            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            this.mailSender = sender;
            log.info("Email notification service enabled");
        } else {
            this.mailSender = null;
            log.info("Email notification service disabled");
        }
    }

    public void sendJobCompletionEmail(String jobName, String[] recipients,
                                       long itemsProcessed, long itemsSkipped,
                                       String duration) {
        if (!enabled || mailSender == null) return;

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipients);
            message.setSubject("Batch Job Completed: " + jobName);
            message.setText(String.format(
                    "Batch Job '%s' completed successfully.%n%n" +
                            "Statistics:%n" +
                            "- Items Processed: %d%n" +
                            "- Items Skipped: %d%n" +
                            "- Duration: %s%n",
                    jobName, itemsProcessed, itemsSkipped, duration
            ));

            mailSender.send(message);
            log.info("Completion email sent for job: {}", jobName);
        } catch (Exception e) {
            log.error("Failed to send completion email for job {}: {}",
                    jobName, e.getMessage());
        }
    }

    public void sendJobFailureEmail(String jobName, String[] recipients,
                                    String errorMessage) {
        if (!enabled || mailSender == null) return;

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipients);
            message.setSubject("Batch Job Failed: " + jobName);
            message.setText(String.format(
                    "Batch Job '%s' failed.%n%n" +
                            "Error:%n%s%n",
                    jobName, errorMessage
            ));

            mailSender.send(message);
            log.info("Failure email sent for job: {}", jobName);
        } catch (Exception e) {
            log.error("Failed to send failure email for job {}: {}",
                    jobName, e.getMessage());
        }
    }
}