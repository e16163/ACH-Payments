package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.achpayments.common.AppLogRepository;

/**
 * Receives ACH_FILE_INGESTED_EVENT. the use case for memo posting received entries is still to come, and received
 * entries aren't OFAC screened (they were screened at origination), so for now this only records the event.
 */
@Component
public class IngestedFileListener {

    private final AppLogRepository appLog;

    public IngestedFileListener(AppLogRepository appLog) {
        this.appLog = appLog;
    }

    @EventListener
    public void onIngested(AchFileIngestedEvent event) {
        appLog.log(ACH_RCV, "INFO", "ACH_FILE_INGESTED_EVENT " + event.fileID() + ": entries stay UNPROCESSED until memo posting is built");
    }
}
