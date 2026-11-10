package com.ts.lib.caradapter.body;

import android.os.IInterface;

/** Test-only verified ChargingAdapterManager field & method layout from ts-framework.jar. */
public final class ChargingAdapterManager {
    private IInterface mService;
    public int acChargingLimit;
    public int setReturnCode;
    public int writeCalls;

    public ChargingAdapterManager(IInterface service) {
        this.mService = service;
    }

    public void replaceService(IInterface replacement) {
        this.mService = replacement;
    }

    public int getAcChargingLimit() {
        return acChargingLimit;
    }

    public int setAcChargingLimit(int state) {
        writeCalls++;
        if (setReturnCode >= 0) {
            acChargingLimit = state;
        }
        return setReturnCode;
    }
}
