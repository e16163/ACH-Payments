package com.achpayments.param;

import java.util.LinkedHashMap;
import java.util.Map;

/** The apps (systemName) parameters can belong to: one per UI in the design diagram (docs/USE-CASES.md P3). */
public final class ParamApps {

    public static final Map<String, String> APPS = new LinkedHashMap<>();

    static {
        APPS.put("ACH_ORIG", "ACH Origination UI");
        APPS.put("ACH_ORIG_PROCESS", "ACH Origination Processing"); // Ba, 2026-11-12
        APPS.put("ACH_ORIG_RETURN", "ACH Origination Return UI");
        APPS.put("ACH_ORIG_REVERSAL", "ACH Origination Reversal UI");
        APPS.put("ACH_ORIG_RESUB", "ACH Origination Resubmission UI");
        APPS.put("ACH_ORIG_NOC", "ACH Origination NOC UI");
        APPS.put("ACH_RCV", "ACH Receiving UI");
        APPS.put("ACH_RECV_PROCESS", "ACH Receiving Processing"); // Ba, 2026-11-12
        APPS.put("ACH_RCV_RETURN", "ACH Receiving Return UI");
        APPS.put("ACH_RCV_REVERSAL", "ACH Receiving Reversal UI");
        APPS.put("ACH_RCV_NOC", "ACH Receiving NOC UI");
    }

    private ParamApps() {
    }
}
