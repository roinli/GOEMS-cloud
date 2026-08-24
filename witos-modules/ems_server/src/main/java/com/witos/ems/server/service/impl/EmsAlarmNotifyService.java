package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsAlarmEvent;
import com.witos.ems.server.domain.entity.EmsAlarmMailConfig;
import com.witos.ems.server.domain.entity.EmsAlarmNotifyLog;
import com.witos.ems.server.domain.entity.EmsAlarmRule;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsAlarmMailConfigMapper;
import com.witos.ems.server.mapper.EmsAlarmNotifyLogMapper;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

@Service
public class EmsAlarmNotifyService
{
    private static final String CHANNEL_EMAIL = "EMAIL";
    private static final SimpleDateFormat DATE_FORMAT = buildDateFormat();

    @Resource
    private EmsAlarmMailConfigMapper alarmMailConfigMapper;

    @Resource
    private EmsAlarmNotifyLogMapper alarmNotifyLogMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsDeviceMapper deviceMapper;

    public void notifyNewAlarm(EmsAlarmEvent event, EmsAlarmRule rule)
    {
        if (event == null || rule == null || !"1".equals(rule.getNotifyEnabled()))
        {
            return;
        }
        List<String> channels = splitValues(rule.getNotifyChannels());
        if (!channels.contains(CHANNEL_EMAIL))
        {
            recordLog(event, rule, CHANNEL_EMAIL, "", "SKIPPED", "", "", "未启用邮件通知", null);
            return;
        }
        List<String> receivers = splitValues(rule.getNotifyTargets());
        if (receivers.isEmpty())
        {
            recordLog(event, rule, CHANNEL_EMAIL, "", "SKIPPED", "", "", "未配置邮件接收人", null);
            return;
        }
        EmsAlarmMailConfig mailConfig = resolveMailConfig(event.getTenantId(), event.getCompanyId());
        if (mailConfig == null)
        {
            for (String receiver : receivers)
            {
                recordLog(event, rule, CHANNEL_EMAIL, receiver, "FAILED", "", "", "未配置可用的邮件服务器", null);
            }
            return;
        }

        String subject = buildSubject(event);
        String content = buildContent(event, rule);
        for (String receiver : receivers)
        {
            Date now = new Date();
            try
            {
                JavaMailSenderImpl sender = buildMailSender(mailConfig);
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(resolveFromAddress(mailConfig));
                message.setTo(receiver);
                message.setSubject(subject);
                message.setText(content);
                sender.send(message);
                recordLog(event, rule, CHANNEL_EMAIL, receiver, "SUCCESS", subject, content, null, now);
            }
            catch (Exception ex)
            {
                recordLog(event, rule, CHANNEL_EMAIL, receiver, "FAILED", subject, content, safeError(ex), null);
            }
        }
    }

    private EmsAlarmMailConfig resolveMailConfig(Long tenantId, Long companyId)
    {
        List<EmsAlarmMailConfig> rows = alarmMailConfigMapper.selectList(
                new LambdaQueryWrapper<EmsAlarmMailConfig>()
                        .eq(EmsAlarmMailConfig::getTenantId, tenantId)
                        .eq(EmsAlarmMailConfig::getEnabled, "1")
                        .and(wrapper -> wrapper.eq(EmsAlarmMailConfig::getCompanyId, companyId == null ? 0L : companyId)
                                .or()
                                .eq(EmsAlarmMailConfig::getCompanyId, 0L))
                        .orderByDesc(EmsAlarmMailConfig::getCompanyId)
                        .orderByDesc(EmsAlarmMailConfig::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JavaMailSenderImpl buildMailSender(EmsAlarmMailConfig mailConfig)
    {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailConfig.getSmtpHost());
        sender.setPort(mailConfig.getSmtpPort() == null ? 25 : mailConfig.getSmtpPort());
        sender.setUsername(mailConfig.getSmtpUsername());
        sender.setPassword(mailConfig.getSmtpPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", booleanValue(mailConfig.getAuthEnabled(), true) ? "true" : "false");
        properties.put("mail.smtp.starttls.enable", booleanValue(mailConfig.getStarttlsEnabled(), false) ? "true" : "false");
        properties.put("mail.smtp.ssl.enable", booleanValue(mailConfig.getSslEnabled(), false) ? "true" : "false");
        properties.put("mail.debug", "false");
        return sender;
    }

    private String resolveFromAddress(EmsAlarmMailConfig mailConfig)
    {
        if (StringUtils.isNotEmpty(mailConfig.getFromAddress()))
        {
            return mailConfig.getFromAddress();
        }
        return mailConfig.getSmtpUsername();
    }

    private void recordLog(EmsAlarmEvent event,
                           EmsAlarmRule rule,
                           String channelType,
                           String receiver,
                           String sendStatus,
                           String subject,
                           String content,
                           String errorMessage,
                           Date sentAt)
    {
        EmsAlarmNotifyLog log = new EmsAlarmNotifyLog();
        log.setTenantId(event.getTenantId());
        log.setCompanyId(event.getCompanyId());
        log.setStationId(event.getStationId());
        log.setDeviceId(event.getDeviceId());
        log.setEventId(event.getId());
        log.setRuleId(rule.getId());
        log.setRuleName(rule.getRuleName());
        log.setChannelType(channelType);
        log.setReceiver(receiver);
        log.setSendStatus(sendStatus);
        log.setSubject(subject);
        log.setContent(content);
        log.setErrorMessage(errorMessage);
        log.setTriggeredAt(new Date());
        log.setSentAt(sentAt);
        alarmNotifyLogMapper.insert(log);
    }

    private String buildSubject(EmsAlarmEvent event)
    {
        return "[EMS告警][" + severityText(event.getSeverity()) + "] " + alarmTitle(event);
    }

    private String buildContent(EmsAlarmEvent event, EmsAlarmRule rule)
    {
        EmsCompany company = event.getCompanyId() == null ? null : companyMapper.selectById(event.getCompanyId());
        EmsStation station = event.getStationId() == null ? null : stationMapper.selectById(event.getStationId());
        EmsDevice device = event.getDeviceId() == null ? null : deviceMapper.selectById(event.getDeviceId());
        List<String> lines = new ArrayList<String>();
        lines.add("告警时间：" + formatDate(event.getFirstTime()));
        lines.add("告警级别：" + severityText(event.getSeverity()));
        lines.add("告警名称：" + alarmTitle(event));
        lines.add("告警编码：" + defaultText(event.getAlarmCode()));
        lines.add("所属公司：" + (company == null ? "-" : defaultText(company.getCompanyName())));
        lines.add("所属电站：" + (station == null ? "-" : defaultText(station.getStationName())));
        lines.add("所属设备：" + (device == null ? "-" : defaultText(device.getDeviceName())));
        lines.add("规则名称：" + defaultText(rule.getRuleName()));
        lines.add("发生次数：" + String.valueOf(event.getOccurrenceCount() == null ? 1 : event.getOccurrenceCount()));
        lines.add("来源类型：" + defaultText(event.getSourceType()));
        return String.join("\n", lines);
    }

    private List<String> splitValues(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            return new ArrayList<String>();
        }
        Set<String> values = new LinkedHashSet<String>();
        Arrays.stream(value.split("[,;\\n\\r]+"))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .forEach(values::add);
        return new ArrayList<String>(values);
    }

    private boolean booleanValue(String value, boolean defaultValue)
    {
        if (StringUtils.isEmpty(value))
        {
            return defaultValue;
        }
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "Y".equalsIgnoreCase(value);
    }

    private String safeError(Exception ex)
    {
        String message = ex.getMessage();
        if (StringUtils.isEmpty(message))
        {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private String alarmTitle(EmsAlarmEvent event)
    {
        if (StringUtils.isNotEmpty(event.getAlarmName()))
        {
            return event.getAlarmName();
        }
        return defaultText(event.getAlarmCode());
    }

    private String formatDate(Date value)
    {
        if (value == null)
        {
            return "-";
        }
        synchronized (DATE_FORMAT)
        {
            return DATE_FORMAT.format(value);
        }
    }

    private String severityText(String severity)
    {
        if ("LOW".equalsIgnoreCase(severity))
        {
            return "低";
        }
        if ("MEDIUM".equalsIgnoreCase(severity))
        {
            return "中";
        }
        if ("HIGH".equalsIgnoreCase(severity))
        {
            return "高";
        }
        if ("CRITICAL".equalsIgnoreCase(severity))
        {
            return "紧急";
        }
        return defaultText(severity);
    }

    private String defaultText(String value)
    {
        return StringUtils.isEmpty(value) ? "-" : value;
    }

    private static SimpleDateFormat buildDateFormat()
    {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getDefault());
        return format;
    }
}
