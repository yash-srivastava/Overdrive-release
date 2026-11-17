package com.ts.lib.caradapter.selfstudy;

import android.os.IInterface;

/** Test-only verified StudyAdapterManager field & method layout from ts-framework.jar. */
public final class StudyAdapterManager {
    private IInterface mService;
    public int supportAcChargingLimit;

    public StudyAdapterManager(IInterface service) {
        this.mService = service;
    }

    public void replaceService(IInterface replacement) {
        this.mService = replacement;
    }

    public int getSupportAcChargingLimit() {
        return supportAcChargingLimit;
    }
}
