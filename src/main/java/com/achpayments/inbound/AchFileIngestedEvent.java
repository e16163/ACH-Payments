package com.achpayments.inbound;

/** UC-ACH-RCV-01 step 6: ACH_FILE_INGESTED_EVENT. A file was validated and its entries saved. */
public record AchFileIngestedEvent(String fileID) {
}
