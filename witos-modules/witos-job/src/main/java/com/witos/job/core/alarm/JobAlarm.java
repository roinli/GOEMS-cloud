package com.witos.job.core.alarm;

import com.witos.job.domain.XxlJobInfo;
import com.witos.job.domain.XxlJobLog;

public interface JobAlarm {

    /**
     * job alarm
     *
     * @param info
     * @param jobLog
     * @return
     */
    boolean doAlarm(XxlJobInfo info, XxlJobLog jobLog);

}
