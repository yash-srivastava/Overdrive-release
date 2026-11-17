package android.hardware.bydauto;

import android.content.Context;
import com.ts.lib.caradapter.body.ChargingAdapterManager;
import com.ts.lib.caradapter.cabin.CarCabinAdapterManager;
import com.ts.lib.caradapter.selfstudy.StudyAdapterManager;

/** Test-only verified TS manager API with independently replaceable cabin/charging/study bindings. */
public final class TsManagerImpl {
    public static TsManagerImpl testInstance;

    private CarCabinAdapterManager cabin;
    private ChargingAdapterManager charging;
    private StudyAdapterManager study;
    public boolean connected = true;
    public boolean carServiceConnected = true;
    public Runnable afterServiceReady;

    public TsManagerImpl(CarCabinAdapterManager cabin) {
        this.cabin = cabin;
    }

    public static TsManagerImpl getInstance(Context context) {
        return testInstance;
    }

    public boolean isConnected() { return connected; }

    public boolean isCarServiceConnect() {
        boolean ready = carServiceConnected;
        if (afterServiceReady != null) afterServiceReady.run();
        return ready;
    }

    public CarCabinAdapterManager getCarCabinAdapterManager() { return cabin; }

    public ChargingAdapterManager getChargingAdapterManager() { return charging; }

    public StudyAdapterManager getStudyAdapterManager() { return study; }

    public void replaceCabin(CarCabinAdapterManager replacement) { cabin = replacement; }

    public void replaceCharging(ChargingAdapterManager replacement) { charging = replacement; }

    public void replaceStudy(StudyAdapterManager replacement) { study = replacement; }
}
