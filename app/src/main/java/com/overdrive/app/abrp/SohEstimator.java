package com.overdrive.app.abrp;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.VehicleDataMonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Estimates battery State of Health (SOH) for ABRP telemetry.
 *
 * Three detection methods for nominal capacity (priority order):
 * 1. BMS direct: BYDAutoBodyworkDevice.getBatteryCapacity() (Ah → KWh mapping)
 * 2. SOC heuristic: remainingKwh / SOC → match to nearest known BYD pack
 * 3. Model string: ro.product.model → mapCarTypeToCapacity()
 *
 * Rolling window primed on init to prevent jumps after reboot.
 */
public class SohEstimator {

    private static final String TAG = "SohEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Nominal capacity — 0 means "not detected yet". SOH estimation is blocked until
    // autoDetectCarModel() successfully identifies the pack size from the BYD SDK.
    // No hardcoded default — wrong nominal capacity produces wrong SOH.
    private double nominalCapacityKwh = 0;
    private static final String SOH_FILE = "/data/local/tmp/abrp_soh_estimate.properties";

    private static final String PROP_SOH_PERCENT = "soh_percent";
    private static final String PROP_ESTIMATION_METHOD = "estimation_method";
    private static final String PROP_LAST_UPDATED = "last_updated";
    private static final String PROP_SAMPLE_COUNT = "sample_count";
    private static final String PROP_NOMINAL_CAPACITY = "nominal_capacity_kwh";

    private static final String METHOD_INSTANTANEOUS = "instantaneous";
    private static final String METHOD_CALIBRATION = "calibration";

    private double currentSoh = -1;
    private String estimationMethod = METHOD_INSTANTANEOUS;
    private int sampleCount = 0;

    public void setNominalCapacityKwh(double capacityKwh) {
        if (capacityKwh > 10 && capacityKwh < 200) {
            this.nominalCapacityKwh = capacityKwh;
            logger.info("Nominal capacity set to " + capacityKwh + " KWh");
            persistEstimate();  // Save immediately so it survives restarts
            
            // Trigger seed now that we have capacity — autoDetect may have
            // set this after the initial seedInitialEstimate call returned early
            if (!hasEstimate()) {
                seedInitialEstimate();
            }
        }
    }

    public double getNominalCapacityKwh() {
        return nominalCapacityKwh;
    }

    /**
     * Detect capacity directly from pack voltage and cell voltage.
     * Called from BydDataCollector when the first HV pack voltage event arrives.
     * This always overrides any previous detection (SOC heuristic is unreliable).
     */
    public void autoDetectFromPackVoltage(double packVoltage, BydVehicleData vd) {
        if (packVoltage < 200 || packVoltage > 900) return;
        
        double cellVoltage = 3.2;
        if (vd != null && !Double.isNaN(vd.highCellVoltage) && vd.highCellVoltage > 2.5 && vd.highCellVoltage < 3.7) {
            cellVoltage = vd.highCellVoltage;
        } else if (vd != null && !Double.isNaN(vd.lowCellVoltage) && vd.lowCellVoltage > 2.5 && vd.lowCellVoltage < 3.7) {
            cellVoltage = vd.lowCellVoltage;
        }
        
        int cellCount = (int) Math.round(packVoltage / cellVoltage);
        double capacity = mapCellCountToCapacity(cellCount);
        
        if (capacity > 0) {
            setNominalCapacityKwh(capacity);
            logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                String.format("%.1f", packVoltage) + "V, cellV=" + String.format("%.3f", cellVoltage) +
                "V, cells≈" + cellCount + "s)");
        } else {
            logger.debug("Pack voltage " + String.format("%.1f", packVoltage) + "V → " + 
                cellCount + " cells — no matching BYD pack");
        }
    }

    // ==================== AUTO-DETECT ====================

    /**
     * Detect nominal battery capacity from BYD SDK data.
     *
     * Priority order:
     * 1. SOC heuristic: remainKwh / SOC → snap to nearest known pack.
     *    Works on every vehicle that reports both values. At high SOC (>95%),
     *    remainKwh ≈ nominal capacity directly.
     * 2. BMS direct: getBatteryCapacity() Ah → mapAhToKwh() lookup.
     *    Fallback for vehicles where remainKwh isn't available.
     * 3. Model string: ro.product.model → table lookup. Last resort.
     */
    public void autoDetectCarModel(android.content.Context context) {
        // Priority order:
        // 0. Pack voltage: derive cell count from HV voltage → map to known BYD pack
        // 1. BMS direct: getBatteryCapacity() Ah -> mapAhToKwh() lookup
        // 2. SOC heuristic: remainKwh / SOC -> snap to nearest known pack
        // 3. Model string: ro.product.model -> table lookup
        // 4. Persisted capacity from previous successful detection

        // Method 0: Pack voltage → cell count → capacity lookup
        // BYD Blade cells are LFP. Use actual cell voltage from BMS if available,
        // otherwise fall back to 3.2V nominal. Using the real cell voltage gives
        // accurate cell count regardless of SOC (e.g., 496V at 3.31V/cell = 150s Seal,
        // not 155s which would wrongly match the Han EV).
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BydVehicleData vd = vdm != null ? vdm.getVd() : null;
            if (vd != null && !Double.isNaN(vd.hvPackVoltage) && vd.hvPackVoltage > 200) {
                double voltage = vd.hvPackVoltage;
                // Use actual cell voltage from BMS for accurate cell count
                double cellVoltage = 3.2;  // default nominal
                if (!Double.isNaN(vd.highCellVoltage) && vd.highCellVoltage > 2.5 && vd.highCellVoltage < 3.7) {
                    cellVoltage = vd.highCellVoltage;
                } else if (!Double.isNaN(vd.lowCellVoltage) && vd.lowCellVoltage > 2.5 && vd.lowCellVoltage < 3.7) {
                    cellVoltage = vd.lowCellVoltage;
                }
                int cellCount = (int) Math.round(voltage / cellVoltage);
                double capacity = mapCellCountToCapacity(cellCount);
                if (capacity > 0) {
                    setNominalCapacityKwh(capacity);
                    logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" + 
                        String.format("%.1f", voltage) + "V, cellV=" + String.format("%.3f", cellVoltage) + 
                        "V, cells≈" + cellCount + "s)");
                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("Pack voltage capacity lookup failed: " + e.getMessage());
        }

        // Method 1: BMS direct
        if (context != null) {
            try {
                Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                Object device = cls.getMethod("getInstance", android.content.Context.class).invoke(null, context);
                if (device != null) {
                    Method getBatteryCapacity = cls.getMethod("getBatteryCapacity");
                    Number capNum = (Number) getBatteryCapacity.invoke(device);
                    int capacityRaw = capNum != null ? capNum.intValue() : 0;
                    if (capacityRaw > 0) {
                        double capacityKwh = 0;
                        if (capacityRaw > 1000) {
                            capacityKwh = capacityRaw / 1000.0;
                        } else {
                            double fromAh = mapAhToKwh(capacityRaw);
                            if (fromAh > 0) {
                                capacityKwh = fromAh;
                            } else if (capacityRaw >= 10 && capacityRaw <= 120) {
                                capacityKwh = capacityRaw;
                            }
                        }
                        if (capacityKwh > 10 && capacityKwh < 200) {
                            setNominalCapacityKwh(capacityKwh);
                            logger.info("BMS Capacity: " + capacityKwh + " kWh (raw=" + capacityRaw + ")");
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("BMS capacity lookup failed: " + e.getMessage());
            }
        }

        // Method 2: SOC heuristic (fallback when BMS unavailable)
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            double remainingKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (remainingKwh > 5 && socData != null && socData.socPercent > 20) {
                double estimatedCapacity = remainingKwh / (socData.socPercent / 100.0);
                // Detect BYD PHEV firmware bug: BMS returns SOC% value as kWh
                // (e.g. 50% SOC → getBatteryRemainPowerKwh() returns 50.0 kWh).
                // In this case the two numbers are numerically almost equal.
                if (Math.abs(remainingKwh - socData.socPercent) < 3.0) {
                    logger.info("SOC heuristic skipped: remainKwh (" +
                        String.format("%.1f", remainingKwh) + ") ≈ socPercent (" +
                        String.format("%.1f", socData.socPercent) + ") — likely SOC-as-kWh firmware bug");
                } else {
                    double matched = matchNearestCapacity(estimatedCapacity);
                    if (matched > 0) {
                        setNominalCapacityKwh(matched);
                        logger.info("SOC-Estimated Capacity: " + String.format("%.1f", estimatedCapacity)
                            + " kWh, matched to " + matched + " kWh"
                            + " (SOC=" + String.format("%.1f", socData.socPercent) + "%, remain="
                            + String.format("%.1f", remainingKwh) + " kWh)");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("SOC heuristic failed: " + e.getMessage());
        }
        // Method 3: System property model string — last resort
        try {
            String carType = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "");
            if (carType != null && !carType.isEmpty()) {
                double mapped = mapCarTypeToCapacity(carType);
                if (mapped > 0) {
                    setNominalCapacityKwh(mapped);
                    logger.info("Model-Mapped Capacity (" + carType + "): " + mapped + " kWh");
                    return;
                }
            }
        } catch (Exception e) { /* ignore */ }

        logger.warn("Capacity detection failed" + 
            (nominalCapacityKwh > 0 ? " — using previously saved capacity: " + nominalCapacityKwh + " kWh" 
                                    : " — SOH estimation disabled until capacity is identified"));
    }

    /**
     * Seed the initial SOH estimate immediately after capacity detection.
     * Called once after autoDetectCarModel() so the web UI has a value
     * before the first SocHistoryDatabase tick (2 min) or ABRP upload (5 sec).
     */
    public void seedInitialEstimate() {
        if (hasEstimate()) return;  // Already have an estimate from persisted file
        if (nominalCapacityKwh <= 0) return;  // Need capacity first

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            double remainingKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (socData != null && socData.socPercent > 20 && socData.socPercent <= 85) {
                // Try raw kWh first
                if (remainingKwh > 0) {
                    updateFromInstantaneous(remainingKwh, socData.socPercent);
                }
                // If raw kWh didn't produce an estimate (stale/stuck value on PHEVs),
                // seed directly from SOC × nominal capacity.
                // This gives SOH = 100% as a starting point, which is reasonable for
                // a new install — calibration charges will refine it over time.
                if (!hasEstimate()) {
                    double computedKwh = (socData.socPercent / 100.0) * nominalCapacityKwh;
                    logger.info("Raw kWh (" + String.format("%.1f", remainingKwh) +
                        ") gave no estimate — seeding SOH from SOC × nominal: " +
                        String.format("%.1f", socData.socPercent) + "% × " +
                        String.format("%.1f", nominalCapacityKwh) + " = " +
                        String.format("%.1f", computedKwh) + " kWh");
                    // This will compute SOH ≈ 100% (since computedKwh/SOC = nominal)
                    // which is the correct starting assumption
                    currentSoh = 100.0;
                    sampleCount = 1;
                    estimationMethod = METHOD_INSTANTANEOUS;
                    persistEstimate();
                    logger.info("SOH seeded at 100% (nominal baseline — will refine from charge calibration)");
                }
            }
        } catch (Exception e) {
            logger.debug("Initial SOH seed failed: " + e.getMessage());
        }
    }

    // ==================== LIFECYCLE ====================

    public void init() {
        try {
            File sohFile = new File(SOH_FILE);
            if (!sohFile.exists()) return;

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(sohFile)) {
                props.load(fis);
            }

            String sohStr = props.getProperty(PROP_SOH_PERCENT);
            if (sohStr != null) currentSoh = Double.parseDouble(sohStr);

            String method = props.getProperty(PROP_ESTIMATION_METHOD);
            if (method != null) estimationMethod = method;

            String countStr = props.getProperty(PROP_SAMPLE_COUNT);
            if (countStr != null) sampleCount = Integer.parseInt(countStr);

            // Restore nominal capacity — this survives bad remainKwh readings
            // that would otherwise cause autoDetectCarModel to fail.
            String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
            if (capStr != null) {
                double savedCap = Double.parseDouble(capStr);
                if (savedCap > 10 && savedCap < 200 && nominalCapacityKwh <= 0) {
                    nominalCapacityKwh = savedCap;
                    logger.info("Restored nominal capacity: " + savedCap + " kWh");
                }
            }

            // Sanity check — reject persisted values outside realistic range
            // Allow up to 110% for factory over-provisioned new packs
            if (currentSoh > 110.0 || currentSoh < 60.0) {
                logger.info("Clearing invalid persisted SOH: " + currentSoh + "%");
                currentSoh = -1;
                sampleCount = 0;
            } else {
                // EMA doesn't need window priming — the persisted value IS the EMA state
                logger.info("Restored SOH: " + currentSoh + "% (samples: " + sampleCount + ")");
            }
        } catch (Exception e) {
            logger.error("Failed to load SOH: " + e.getMessage());
        }
    }

    // ==================== SOTA SOH UPDATES ====================

    /**
     * BYD Display SOC correction factor.
     * BYD hides ~2.5% at bottom and ~2.5% at top for battery protection.
     * Display 0-100% maps to approximately Absolute 2.5-97.5%.
     * absoluteSoc ≈ displaySoc * 0.95 + 2.5
     */
    private static double correctDisplaySoc(double displaySoc) {
        return displaySoc * 0.95 + 2.5;
    }

    /**
     * Confidence-Weighted Exponential Moving Average (EMA).
     * Replaces the naive rolling window. Prevents volatile swings from noisy readings
     * while allowing high-confidence calibration data to shift the estimate quickly.
     *
     * @param newSohEstimate The new SOH value to incorporate
     * @param confidenceWeight How much this reading should influence the average (0.0 - 1.0)
     */
    private void applyWeightedSoh(double newSohEstimate, double confidenceWeight) {
        // Allow 60-110% to track factory over-provisioning degradation curve.
        // A brand-new BYD pack is typically 102-104% of rated nominal capacity.
        // Clamping to 100% would hide the first 2+ years of degradation.
        if (newSohEstimate < 60.0 || newSohEstimate > 110.0) return;

        if (currentSoh < 0) {
            // First estimate — accept directly
            currentSoh = newSohEstimate;
        } else {
            // EMA: current = (new * weight) + (current * (1 - weight))
            currentSoh = (newSohEstimate * confidenceWeight) + (currentSoh * (1.0 - confidenceWeight));
        }

        sampleCount++;
        persistEstimate();
    }

    /**
     * Update SOH from instantaneous remainingKwh / SOC readings.
     *
     * This method is ONLY used for the initial seed when no SOH estimate
     * exists yet. Once we have an estimate (from this seed or from calibration),
     * further instantaneous readings are ignored — they're too noisy for a value
     * that changes 1-2% per year. Ongoing SOH updates come exclusively from
     * charge session calibration (updateFromCalibration).
     *
     * @param remainingKwh Battery remaining energy from BMS
     * @param displaySocPercent Display SOC from dashboard (0-100)
     */
    public void updateFromInstantaneous(double remainingKwh, double displaySocPercent) {
        // Once we have an estimate, stop accepting instantaneous readings.
        // SOH should only change from calibration data (charge sessions).
        if (hasEstimate()) return;

        // Need nominal capacity to compute SOH — skip if not yet detected
        if (nominalCapacityKwh <= 0) return;

        if (displaySocPercent <= 5 || displaySocPercent > 100.0) return;
        if (remainingKwh <= 1.0) return;

        // Prefer mid-range SOC (20-85%) where BMS readings are most stable.
        // Extremes (<20% or >85%) have nonlinear voltage curves on LFP.
        if (displaySocPercent < 20 || displaySocPercent > 85) {
            return;
        }

        // Sanity check: implied capacity must be in a plausible range for ANY BYD model.
        // Use absolute bounds (25-120 kWh) rather than nominal-relative bounds,
        // because nominal detection may have failed (wrong default).
        // Also check against nominal capacity — if implied is >2x or <0.5x nominal,
        // the remainKwh reading is likely garbage (PHEV returning SOC as kWh).
        double impliedCapacity = remainingKwh / (displaySocPercent / 100.0);
        if (impliedCapacity < 10.0 || impliedCapacity > 120.0) {
            logger.debug("Rejecting seed: implied capacity " + String.format("%.1f", impliedCapacity) +
                " kWh outside BYD range (10-120 kWh)");
            return;
        }
        if (nominalCapacityKwh > 0) {
            double ratio = impliedCapacity / nominalCapacityKwh;
            if (ratio < 0.5 || ratio > 1.5) {
                logger.debug("Rejecting seed: implied capacity " + String.format("%.1f", impliedCapacity) +
                    " kWh is " + String.format("%.0f", ratio * 100) + "% of nominal " +
                    String.format("%.1f", nominalCapacityKwh) + " kWh — likely bad remainKwh reading");
                return;
            }
        }

        double absSoc = correctDisplaySoc(displaySocPercent);
        double currentTotalCap = remainingKwh / (absSoc / 100.0);
        double instantaneousSoh = (currentTotalCap / nominalCapacityKwh) * 100.0;

        if (instantaneousSoh < 60.0 || instantaneousSoh > 110.0) return;

        // Accept as initial seed
        currentSoh = instantaneousSoh;
        sampleCount = 1;
        estimationMethod = METHOD_INSTANTANEOUS;
        persistEstimate();
        logger.info("SOH seeded: " + String.format("%.1f", currentSoh) + "% (from " +
            String.format("%.1f", remainingKwh) + " kWh / " +
            String.format("%.1f", displaySocPercent) + "% SOC, implied cap=" +
            String.format("%.1f", impliedCapacity) + " kWh)");
    }

    /**
     * SOTA: Update SOH from a charge calibration session.
     *
     * Only accepts slow AC charging at optimal battery temperatures.
     * DC Fast Charging introduces thermal loss and early voltage tapering,
     * making Coulomb-counting unreliable. Cold temperatures temporarily
     * reduce available chemical capacity, skewing SOH low.
     *
     * @param energyEnteredBatteryKwh Energy that entered the battery (after charging losses)
     * @param socDelta SOC change during charge session (Display SOC delta)
     * @param packTempCelsius Average battery temperature during the charge
     * @param isAcCharge True if using slow AC charging, False if DC Fast Charging
     */
    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge) {
        // Need nominal capacity to compute SOH
        if (nominalCapacityKwh <= 0) {
            logger.debug("Calibration rejected: nominal capacity not yet detected");
            return;
        }

        // 1. DC Fast Charging introduces thermal loss and early voltage tapering.
        //    It is not reliable for Coulomb-counting SOH.
        if (!isAcCharge) {
            logger.debug("Calibration rejected: DC Fast Charging is too volatile for accurate SOH math.");
            return;
        }

        // 2. Cold temperatures temporarily reduce available chemical capacity.
        //    Only accept calibration at optimal chemical temperatures (15°C to 35°C).
        if (packTempCelsius < 15.0 || packTempCelsius > 35.0) {
            logger.debug("Calibration rejected: Pack temperature (" +
                String.format("%.1f", packTempCelsius) + "°C) outside optimal SOH window (15-35°C).");
            return;
        }

        // 3. Reject shallow charges — LFP flat voltage curve makes them unreliable
        if (socDelta < 25.0) {
            logger.debug("Calibration rejected: SOC delta " + String.format("%.1f", socDelta) +
                "% < 25% minimum for LFP accuracy");
            return;
        }

        // Correct the SOC delta from Display to Absolute scale
        // A 50% display delta ≈ 47.5% absolute delta (0.95 factor)
        double absSocDelta = socDelta * 0.95;

        double actualCapacity = energyEnteredBatteryKwh / (absSocDelta / 100.0);
        double calibratedSoh = (actualCapacity / nominalCapacityKwh) * 100.0;

        if (calibratedSoh < 60.0 || calibratedSoh > 110.0) {
            logger.warn("Calibration SOH out of range: " + String.format("%.1f", calibratedSoh) + "% — rejected");
            return;
        }

        // Dynamic confidence weight based on charge delta size:
        // 25% delta → 0.15 weight (moderate confidence)
        // 50% delta → 0.30 weight (good confidence)
        // 75%+ delta → 0.50 weight (high confidence)
        double confidenceWeight = 0.15 + (((Math.min(socDelta, 75.0) - 25.0) / 50.0) * 0.35);

        applyWeightedSoh(calibratedSoh, confidenceWeight);
        estimationMethod = METHOD_CALIBRATION;

        logger.info("Calibration SOH: " + String.format("%.1f", calibratedSoh) + "% " +
            "(weight=" + String.format("%.2f", confidenceWeight) + ", temp=" +
            String.format("%.1f", packTempCelsius) + "°C) " +
            "[" + String.format("%.1f", energyEnteredBatteryKwh) + " kWh / " +
            String.format("%.1f", socDelta) + "% display delta → " +
            String.format("%.1f", absSocDelta) + "% absolute]");
    }

    /**
     * Legacy overload for callers that don't have temperature/charge type info.
     * Assumes AC charging at optimal temperature (backward compatible).
     */
    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, 25.0, true);
    }

    // ==================== GETTERS ====================

    public double getCurrentSoh() { return currentSoh; }
    public boolean hasEstimate() { return currentSoh > 0; }

    public double getEstimatedCapacityKwh() {
        if (!hasEstimate()) return -1;
        return (currentSoh / 100.0) * nominalCapacityKwh;
    }

    // ==================== PERSISTENCE ====================

    private void persistEstimate() {
        try {
            Properties props = new Properties();
            props.setProperty(PROP_SOH_PERCENT, String.valueOf(currentSoh));
            props.setProperty(PROP_ESTIMATION_METHOD, estimationMethod);
            props.setProperty(PROP_LAST_UPDATED, String.valueOf(System.currentTimeMillis()));
            props.setProperty(PROP_SAMPLE_COUNT, String.valueOf(sampleCount));
            if (nominalCapacityKwh > 0) {
                props.setProperty(PROP_NOMINAL_CAPACITY, String.valueOf(nominalCapacityKwh));
            }

            try (FileOutputStream fos = new FileOutputStream(SOH_FILE)) {
                props.store(fos, "ABRP SOH Estimate");
            }
        } catch (Exception e) {
            logger.error("Failed to persist SOH: " + e.getMessage());
        }
    }

    // ==================== MAPPINGS ====================

    private static double mapAhToKwh(int ah) {
        switch (ah) {
            case 150: return 60.48;   // Atto 3 / Yuan Plus
            case 153: return 82.56;   // Seal Premium/Performance
            case 157: return 61.44;   // Seal Dynamic RWD
            case 140: return 71.8;    // Seal U (71.8 kWh variant)
            case 170: return 87.0;    // Seal U (87 kWh variant)
            case 166: return 85.44;   // Han EV
            case 120: return 44.9;    // Dolphin Standard
            case 135: return 60.48;   // Dolphin Extended
            case 100: return 38.0;    // Seagull (38 kWh variant)
            case 80:  return 30.08;   // Seagull (30 kWh) / Atto 1 Essential
            case 200: return 108.8;   // Tang
            case 176: return 56.4;    // Qin Plus EV
            case 180: return 91.3;    // Sealion 7
            case 110: return 43.2;    // Atto 1 Premium / Atto 2
            case 50:  return 18.3;    // Sealion 6 DM-i (PHEV)
            case 56:  return 18.3;    // Sealion 6 DM-i (PHEV) — some units report 56 Ah
            default:  return 0;       // Unknown — don't guess
        }
    }

    private static double matchNearestCapacity(double estimated) {
        double[] known = {
            18.3,   // Sealion 6 DM-i (PHEV)
            30.08,  // Seagull 30 / Atto 1 Essential
            38.0,   // Seagull 38
            43.2,   // Atto 1 Premium
            44.9,   // Dolphin Standard / Atto 2
            56.4,   // Qin Plus EV
            60.48,  // Atto 3 / Dolphin Extended
            61.44,  // Seal Dynamic RWD
            71.7,   // E6
            71.8,   // Seal U / Song Plus EV
            82.56,  // Seal
            85.44,  // Han EV
            87.0,   // Seal U (87 kWh)
            91.3,   // Sealion 7
            108.8   // Tang
        };
        double bestMatch = 0;
        double bestDiff = Double.MAX_VALUE;
        for (double k : known) {
            double diff = Math.abs(estimated - k);
            // Use 20% tolerance for small packs (<40 kWh) because BMS readings
            // on PHEVs have higher relative error at low SOC. Standard 10% for larger packs.
            double tolerance = k < 40 ? 0.20 : 0.10;
            if (diff / k < tolerance && diff < bestDiff) {
                bestDiff = diff;
                bestMatch = k;
            }
        }
        return bestMatch;
    }

    /**
     * Map HV pack cell count (series) to known BYD battery capacity.
     * BYD Blade cells are LFP (3.2V nominal). Cell count is derived from
     * pack voltage / 3.2V and uniquely identifies the pack across all models.
     *
     * Known BYD Blade pack configurations:
     * - 96s:  ~307V nominal → Seagull 30 kWh / Sealion 6 DM-i 18.3 kWh
     * - 104s: ~333V nominal → Dolphin Standard 44.9 kWh
     * - 120s: ~384V nominal → Atto 3 60.48 kWh / Dolphin Extended
     * - 126s: ~403V nominal → Seal Dynamic 61.44 kWh
     * - 138s: ~442V nominal → Seal U 71.8 kWh / Song Plus EV
     * - 150s: ~480V nominal → Seal 82.5 kWh
     * - 156s: ~499V nominal → Han EV 85.44 kWh
     * - 166s: ~531V nominal → Seal U 87 kWh
     * - 170s: ~544V nominal → Sealion 7 91.3 kWh
     * - 192s: ~614V nominal → Tang 108.8 kWh
     */
    private static double mapCellCountToCapacity(int cellCount) {
        // Allow ±3 cells tolerance (voltage measurement noise, SOC-dependent cell voltage)
        if (cellCount >= 93 && cellCount <= 99) return 30.08;    // Seagull 30 / Atto 1
        if (cellCount >= 101 && cellCount <= 107) return 44.9;   // Dolphin Standard
        if (cellCount >= 117 && cellCount <= 123) return 60.48;  // Atto 3 / Dolphin Extended
        if (cellCount >= 123 && cellCount <= 129) return 61.44;  // Seal Dynamic RWD
        if (cellCount >= 135 && cellCount <= 141) return 71.8;   // Seal U / Song Plus EV
        if (cellCount >= 147 && cellCount <= 153) return 82.56;  // Seal
        if (cellCount >= 153 && cellCount <= 159) return 85.44;  // Han EV
        if (cellCount >= 163 && cellCount <= 169) return 87.0;   // Seal U 87 kWh
        if (cellCount >= 167 && cellCount <= 173) return 91.3;   // Sealion 7
        if (cellCount >= 189 && cellCount <= 195) return 108.8;  // Tang
        return 0;
    }

    private static double mapCarTypeToCapacity(String carType) {
        String ct = carType.toUpperCase();
        // Order matters: check more specific patterns first
        if (ct.contains("SEALION 6") || ct.contains("SEALION6") || ct.contains("SEA LION 6")) return 18.3;
        if (ct.contains("SEALION") || ct.contains("SEA LION")) return 91.3;  // Sealion 7
        if (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U") || ct.contains("S7")) return 71.8;
        if (ct.contains("SEAL")) return 82.56;
        if (ct.contains("HAN") || ct.contains("DM-P")) return 85.44;
        if (ct.contains("TANG")) return 108.8;
        if (ct.contains("ATTO 3") || ct.contains("ATTO3") || ct.contains("YUAN PLUS")) return 60.48;
        if (ct.contains("ATTO 2") || ct.contains("ATTO2")) return 44.9;
        if (ct.contains("ATTO 1") || ct.contains("ATTO1")) return 30.08;  // Essential (safer default)
        if (ct.contains("YUAN PRO")) return 38.0;
        if (ct.contains("YUAN")) return 60.48;  // Yuan Plus fallback
        if (ct.contains("DOLPHIN MINI") || ct.contains("SEAGULL")) return 38.0;
        if (ct.contains("DOLPHIN")) return 44.9;  // Standard range default
        if (ct.contains("E6")) return 71.7;
        if (ct.contains("SONG")) return 71.8;
        if (ct.contains("QIN")) return 56.4;
        return 0;
    }
}
