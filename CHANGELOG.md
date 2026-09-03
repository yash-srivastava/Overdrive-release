# Changelog

Tutte le modifiche e gli sviluppi in corso vengono tracciati in questo file e versionati in corrispondenza delle release ufficiali o dei Version Bump.

## [In corso / Unreleased]

- **Allineamento con Upstream `origin/main` & Risoluzione Conflitti PR**:
  - Eseguito il merge dei commit più recenti di `origin/main` (`ead978a4`).
  - Risolto il conflitto `modify/delete` causato dalla dismissione upstream della cartella `dilink-probe/`.
  - Verificata la completa compatibilità di compilazione con Gradle e Corretto 17 (`BUILD SUCCESSFUL`).

- **Architettura Ibrida Dual-Pipeline 4K Ultra-HD & Streaming Web 720p (`video_Improve.md`, `qcarcam_bridge.cpp`, `GpuPipelineConfig.java`, `GpuSurveillancePipeline.java`, `DiLink5QCarCamBackend.java`, `CMakeLists.txt`)**:
  - **Integrazione Nuovi Binari Precompilati `fast_cam_capture` & `libfast_cam_client.so`**: Sostituiti gli asset e le librerie native con la build ARM64 compilata da `frame_grabber_light/fast_cam_capture/` (binario 21.912 bytes con supporto `--all --time 0` e libreria client da 8.344 bytes con `SONAME: libfast_cam_client.so`).
  - **Doppia Pipeline Indipendente & Zero-Copy**:
    - **Storage Locale Dashcam/Sentinella (4K Ultra-HD HEVC)**: Implementata la codifica hardware a piena risoluzione nativa dei sensori ($3840 \times 2600$ o $3840 \times 2160$ a 30 FPS) tramite encoder hardware `c2.qti.hevc.encoder` (H.265) a 12 Mbps, garantendo il 100% dei pixel nativi senza downsampling per targhe e dettagli forensi.
    - **Streaming Remoto Web & Display Pad (720p H.264)**: Mantenuta in parallelo la pipeline `streamEncoder` a 720p ($1280 \times 720$) H.264 a 1.5 Mbps per la fruizione fluida via WebSocket/JMuxer su reti 4G/Web a basso consumo dati.
  - **Compositore Nativo Mosaico 4K & Geometria Dinamica (`qcarcam_bridge.cpp`)**: Aggiornato il thread client IPC per invocare `FastCamClient::compose4K` (Mode 5) su buffer 4K dedicati e `FastCamClient::compose2x2` (Mode 4 per 1080p), con adattamento dinamico runtime della geometria del buffer `ANativeWindow` (`ANativeWindow_setBuffersGeometry`).
  - **Supporto Configurazione & Profilo `ULTRA_4K` (`GpuPipelineConfig.java`, `GpuSurveillancePipeline.java`, `DiLink5QCarCamBackend.java`)**: Aggiunto l'enum `RecordingQuality.ULTRA_4K` (12 Mbps HEVC / 18 Mbps H.264) e il metodo `is4K()`. All'attivazione del profilo, la pipeline alloca dinamicamente il canvas 4K e attiva la modalità 4K sul backend nativo (`DiLink5QCarCamBackend.set4KUltraEnabled(true)`).
  - **Archivio Release & Task Gradle `downloadFastCam` per Gestione PR (`app/build.gradle.kts`, `overdrive_fast_cam_release.tar.gz`, `PR_DESCRIPTION.md`)**: Posizionato l'archivio precompilato in `frame_grabber_light/release/overdrive_fast_cam_release.tar.gz` (SHA-256: `ec116567243135e9d2fcea58386e1483920cb1248f00cda60b2f2bfd81d9e7ba`) e registrato il task automatico `:app:downloadFastCam` in Gradle, garantendo a chiunque effettui il clone della PR o la build in CI/CD l'estrazione automatica di `libfast_cam_client.so`, `fast_cam_capture` e `fast_cam_bridge.h` per una compilazione out-of-the-box immediata.

- **Interblocco Ricarica Hardware & Soppressione Falso Movimento GPS in Ricarica (`GearMonitor.java`, `RecordingModeManager.java`)**:
  - **Blocco Hardware Marcia in Ricarica (`GearMonitor.java`)**: Integrato il controllo prioritario `ChargingDetector.getInstance().isCharging()`. Quando la vettura è in ricarica (cavo inserito / potenza attiva), la marcia viene bloccata tassativamente su `GEAR_P` (Park), eliminando all'origine qualsiasi lettura spuria.
  - **Soppressione Promozione Marcia da Drift/Jitter GPS (`RecordingModeManager.java`)**: Inibito il fallback di velocità GPS in caso di ricarica attiva, impedendo che le oscillazioni delle coordinate GPS all'interno del box/garage promuovano erroneamente la marcia da `P` a `D`.
  - **Disattivazione Automatica Registrazione di Guida in Carica (`RecordingModeManager.java`)**: Sottoscritto `FusedStateListener` di `ChargingDetector`. All'avvio della sessione di ricarica o al risveglio del display, le modalità di registrazione di guida (`CONTINUOUS` e `DRIVE_MODE`) vengono automaticamente disattivate e soppresse, evitando sprechi di memoria, cicli di scrittura inutili su SD e falsi allarmi di guida a vettura parcheggiata alla colonnina/wallbox.

- **Integrazione Demone Nativo Zero-Copy `fast_cam_capture` & `libfast_cam_client.so` su DiLink 5.0 (`qcarcam_bridge.cpp`, `DiLink5QCarCamBackend.java`, `DiLink5PowerDiagnostics.java`, `CMakeLists.txt`)**:
  - **Sostituzione Pipeline Hardware Legacy (`qcarcam_test` + `libhook_qcarcam.so`)**: Dismesso l'utilizzo del binario diagnostico vendor `/vendor/bin/qcarcam_test`, dell'iniezione LD_PRELOAD `libhook_qcarcam.so` e dei file di configurazione XML (`4cam.xml`, `1cam.xml`).
  - **Composizione Nativa Mosaico 2x2 & Switch Canali (`qcarcam_bridge.cpp`)**: Implementato in C++ l'algoritmo di composizione 2x2 grid in UYVY (`compose_2x2_uyvy`), assemblando contemporaneamente le 4 telecamere (Front, Right, Rear, Left) in un unico frame 1920x1300 a 30 FPS quando la pipeline richiede la modalità mosaico (`desired_cam == 4`), oltre al routing istantaneo a singola camera a piena risoluzione per i canali 0, 1, 2, 3.
  - **Supporto Abstract Socket UNIX (`@fast_cam.sock`)**: Superata la restrizione di sicurezza di Android SELinux (che blocca con `Permission denied (13)` i socket di tipo filesystem creati in `/data/local/tmp/`), introducendo il binding e la connessione ad abstract domain socket (`@fast_cam.sock`) sia nel server daemon nativo che nel client bridge C++.
  - **Link Diretto e Autonomo `fast_cam_bridge.cpp` in `libsurveillance.so`**: Eliminata la dipendenza dinamica esterna `DT_NEEDED` e i potenziali conflitti di soname compilando direttamente il codice del client IPC nel target `surveillance`, rendendo la libreria totalmente autonoma e priva di path host.
  - **Supervisore Hardware & Auto-Deploy (`DiLink5QCarCamBackend.java`)**: Confezionato il binario `fast_cam_capture` in `assets/dilink5/` con estrazione in `/data/local/tmp/fast_cam_capture` (`chmod 755`), flag `--all --time 0` per acquisizione continuativa senza timeout e drain asincrono dello stdout.
  - **Allineamento Diagnostica (`DiLink5PowerDiagnostics.java`)**: Monitoraggio runtime del processo `fast_cam_capture` al posto di `qcarcam_test`.

- **Fix Priorità Rilevamento ACC / Sentinella & Integrazione `CarBodyManager.getShiftMode()` su DiLink 5.0 (`AccMonitor.java`, `GearMonitor.java`, `BydDataCollector.java`)**:
  - **Inversione Priorità nel Probe ACC (`AccMonitor.java`)**: Assegnata priorità primaria all'enum hardware `dumpsys car_service PowerMode` (`4=PowerMode Standby`, `8=PowerMode Sleep`, `5=PowerMode Str`, `0=PowerMode Off`, `9=PowerMode Str Suspending`, `1=PowerMode Pre StartUp`) e allo stato interattivo del display. Il controllo di velocità telemetrica ora interviene solo se il veicolo non è in Standby/Sleep e non è in marcia P (`GEAR_P`), eliminando i falsi positivi da jitter del sensore GPS/velocità a vettura spenta e ferma che bloccavano l'attivazione della modalità Sentinella.
  - **Integrazione `CarBodyManager.getShiftMode()` (`GearMonitor.java`, `BydDataCollector.java`)**: Allineata la lettura della marcia su DiLink 5.0 (BYD Sealion 7) al pattern di `abrp-telemetry-sl7`, interrogando il manager `"body"` (`CarBodyManager`) e la proprietà `getShiftMode()` (`1=P`, `2=R`, `3=N`, `4=D`, `0=Charging/Park`) con gestione del reset di connessione `isCarServiceBound()`.

- **Fix Crash Loop TelegramBotDaemon, Watchdog Guard & Self-Healing Case Sensitivity (`DaemonLauncher.kt`, `TelegramDaemonLauncher.java`, `AccSentryDaemon.java`)**:
  - **Risoluzione Dinamica APK & Watchdog PID Guard (`DaemonLauncher.kt`)**: Introdotta la query runtime `pm path com.overdrive.app` ad ogni ciclo watchdog (`start_telegram.sh`) e aggiunto il controllo di processo attivo su `/data/local/tmp/telegram_watchdog.pid` per impedire a priori lo spawn di watchdog concorrenti.
  - **Fix Case Sensitivity in Self-Healing (`AccSentryDaemon.java`)**: Corretto il controllo `isProcessRunning("telegram_bot_daemon")` (precedentemente cercava con case errato `"TelegramBotDaemon"`, fallendo sistematicamente su Linux `pgrep` e scatenando il rilancio continuo del watchdog ogni 60 secondi).
  - **Rafforzamento `isRunning()` (`TelegramDaemonLauncher.java`)**: Verifica congiunta del processo e dello script supervisore per evitare duplicati.

- **Fix Rilevamento Marcia (D/R), Dashcam Automatica a 30 FPS & Dashboard di Guida in Tempo Reale (`GearMonitor.java`, `RecordingModeManager.java`, `DashboardStatusParser.kt`, `DashboardFragment.kt`, `DiLink5QCarCamBackend.java`)**:
  - **Bypass Sicurezza Android 11 per `GearMonitor`**: Corretta l'istanziazione di `BYDAutoGearboxDevice` tramite `BydDeviceHelper.getDevice(...)` e `callGetter(...)`, risolvendo il `SecurityException` (UID 2000 mismatch) su DiLink 5.0 e consentendo la lettura istantanea delle marce `D`, `R`, `P`, `N`.
  - **Promozione Automatica Dashcam su Movimento GPS**: Integrato in `RecordingModeManager` il fallback di movimento (`speed >= 3 km/h` o `isMoving = true`), garantendo l'attivazione immediata della registrazione continua a 30 FPS ad alta risoluzione anche durante la marcia.
  - **Auto-Sovrascrittura Automatica Librerie Native**: `DiLink5QCarCamBackend` verifica la corrispondenza di dimensione/versione e sovrascrive automaticamente file obsoleti o corrotti in `/data/local/tmp/libhook_qcarcam.so` impostando permessi `755`, eliminando la necessità di interventi manuali via ADB per i tester.
  - **Dashboard Dinamica in Tempo Reale**: Aggiornato `DashboardFragment` e `DashboardStatusParser` per mostrare sul display centrale lo stato reale del veicolo (`In Guida (D) · XX km/h (REC)`, `In Retromarcia (R)`, `Pronta / Parcheggiata (P)`, `In Ricarica (X.X kW)`, `Sentinella Attiva`).

- **Hardening Persistenza ADB Wireless & Self-Healing h24 dei Demoni (`AccSentryDaemon.java`, `AdbShellExecutor.kt`, `BootReceiver.kt`)**:
  - **Auto-Enforcement Periodico Impostazioni Globali**: Implementato in `AccSentryDaemon` (UID 2000 shell) l'aggiornamento forzato e continuo ogni 60 secondi di `adb_enabled = 1`, `adb_wifi_enabled = 1`, `adb_allowed_connection_time = 0`, `development_settings_enabled = 1` e `stay_on_while_plugged_in = 7` tramite `SettingsProvider`, impedendo a BYD di disattivare il debug wireless durante i cicli di standby o le soste prolungate.
  - **Self-Healing Nativo dei Demoni Compagni**: `AccSentryDaemon` monitora costantemente i processi `CameraDaemon` (`byd_cam_daemon`) e `TelegramBotDaemon`; in caso di morte inattesa o crash, ne esegue il respawn automatico tramite gli script watchdog in `/data/local/tmp/` senza richiedere alcun comando ADB esterno o riapertura dell'app.
  - **Auto-Recovery in Avvio & Boot**: Integrato l'auto-ripristino preventivo delle chiavi di debug in `BootReceiver` e in `AdbShellExecutor` su tentativi di connessione locale a `127.0.0.1:5555`, eliminando la necessità di riattivare manualmente ADB tramite la procedura degli 8 tap.

- **Risoluzione Blocco "Connecting" Telecamere & Estrazione Nativa Multi-Livello `libhook_qcarcam.so` su DiLink 5.0 (`DiLink5QCarCamBackend.java`, `UnifiedConfigManager.kt`)**:
  - **Risoluzione Bug Ricerca APK in `app_process`**: Sostituita la lettura di `System.getProperty("java.class.path")` con `System.getenv("CLASSPATH")`, garantendo che il demone autonomo individui sempre `base.apk` per estrarre la libreria nativa `libhook_qcarcam.so` in `/data/local/tmp/`.
  - **Supporto Struttura Cartelle Android 11 (`~~hash/`)**: Implementata la scansione ricorsiva di `/data/app` fino a 3 livelli e il fallback su `pm path com.overdrive.app` e `Context.getPackageCodePath()`, superando le directory con hash casuali tipiche di Android 11 Automotive (`msmnile`).
  - **Prevenzione Deadlock Pipe Processo Hardware (`qcarcam-test-drainer`)**: Aggiunto un thread di background per consumare e registrare in tempo reale lo stream combinato di `stdout/stderr` di `/vendor/bin/qcarcam_test`, evitando il riempimento del buffer di pipe (64 KB) e il conseguente stallo del demone.
  - **Auto-Rilevamento Modello `sealion7` su Piattaforme DiLink 5.0**: Configurato il default del modello veicolo in `UnifiedConfigManager.kt` su `"sealion7"` (invece del generico `"seal"`) quando viene rilevata la presenza dell'architettura hardware DiLink 5.0 (`DiLink5QCarCamBackend.isSupported()`).

- **Ottimizzazione Failover Rete 4G / LTE & Instradamento Proxy `sing-box` (`TelegramBotDaemon.java`)**:
  - Integrato il rilevamento automatico del proxy `sing-box` (porta 8119) e `Tailscale` (porta 8539) tramite `ProxyHelper` in `TelegramBotDaemon`, instradando tutto il traffico Telegram e gli alert attraverso il tunnel VLESS quando la connettività 4G del veicolo è attiva a display spento.
  - Abilitato `retryOnConnectionFailure(true)` su tutti i client HTTP e ridotti i timeout di connessione (15s connect) per accelerare il failover da Wi-Fi locale a rete cellulare 4G.
  - Implementata l'eviction automatica e immediata del connection pool (`httpClient.connectionPool().evictAll()`) al primo errore di socket/rete, eliminando le socket orfane dell'interfaccia Wi-Fi spenta e consentendo al bot Telegram di riaprire istantaneamente le connessioni sul modem 4G LTE permanente del veicolo.

- **Sentry Keep-Awake h24 & Supporto `WifiLock` per DiLink 5.0 / Android 11 (`AccSentryDaemon.java`)**:
  - Eliminato l'invio di `KEYCODE_SLEEP` (223) e `goToSleep` nello spegnimento dello schermo in Sentry Mode, prevenendo la transizione di `mWakefulness` ad `Asleep` e la conseguente attivazione di `mHalAutoSuspendModeEnabled`.
  - Implementato lo spegnimento display via Backlight-Off puro (`screen_brightness 0` / `StealthPanel.turnOff()`) con mantenimento dei `Suspend Blockers` del kernel (`mWakefulness=Awake`).
  - Implementata la reflection per il metodo a 3 argomenti `PowerManager.userActivity(long when, int event, int flags)` di Android 11+ con flag `USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS = 1`, azzerando il timer di inattività a schermo spento.
  - Integrato `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) coordinato con il `PARTIAL_WAKE_LOCK` di sistema in `AccSentryDaemon`, prevenendo la disattivazione della scheda Wi-Fi/4G e garantendo la raggiungibilità h24 del tunnel Cloudflare, dei bot Telegram e della Dashboard Web a veicolo spento.

- **Risoluzione Crash Loop `acc_sentry_daemon` & Stabilizzazione Connessione Veicolo (`AccSentryDaemon.java`)**:
  - Risolto il crash fatale ricorrente (`FATAL EXCEPTION: BodyworkRegistration-1` causato da `NoClassDefFoundError: AbsBYDAutoBodyworkListener` / `AbsBYDAutoPowerListener`) sui sistemi DiLink dove le classi listener dell'SDK non sono esposte nel classpath shell.
  - Implementato il controllo runtime preventivo `isBodyworkSupported()` / `isPowerListenerSupported()` e l'isolamento dell'istanziazione dei listener in registrar dedicati (`BodyworkListenerRegistrar` / `PowerListenerRegistrar`), con gestione sicura di `LinkageError` / `Throwable`.
  - Abilitato il fallback immediato sull'heartbeat ACC per garantire il mantenimento ininterrotto dei WakeLock e del monitoraggio energetico, impedendo il deep-sleep non controllato dell'headunit e le conseguenti disconnessioni dell'interfaccia Wi-Fi / ADB (`device offline`).

- **Correzione Mappatura Hardware Stato Blocco Porte (`VehicleControlApiHandler.java`, `LauncherApiHandler.java`, `AccMonitor.java`)**:
  - Risolta l'inversione dello stato delle portiere tra la convenzione BYD SDK (`1 = UNLOCKED, 2 = LOCKED`) e l'API/Frontend Web (`1 = LOCKED, 2 = UNLOCKED`).
  - Mappati correttamente i valori telemetrici hardware tramite `cloudLockToApi()`, ripristinando la visualizzazione immediata di vettura **Bloccata** (`overall = 1`) con lucchetto chiuso.

- **Risoluzione Falso Blocco "Veicolo in movimento" sui Comandi Rapidi (`DrivingSafetyGuard.java`)**:
  - Aggiunto il fallback sul canale telemetrico `BydVehicleData.gearMode` in `resolveGear()` nel caso in cui `GearMonitor` sia inattivo o in sosta prolungata, impedendo che lo stato marcia sconosciuto blocchi erroneamente i comandi di controllo con l'avviso *"Questa azione non è disponibile mentre il veicolo è in movimento"*.

- **Prevenzione Falsi Stati di Ricarica al Boot & Sanity Check Dashboard (`ChargingApiHandler.java`, `index.html`)**:
  - `ChargingApiHandler.java`: `effectiveCharging` richiede ora un campionamento live validato nel ciclo di vita corrente del processo (`after.observedAtMs > 0`), impedendo che vecchi stati di carica salvati nella cache SQLite o su disco prima del riavvio/crash dei servizi vengano serviti come attivi.
  - `index.html`: La card della Dashboard richiede potenza attiva (>0 kW) o connettore fisicamente inserito (`plugged === true`) prima di attivare la modalità *"Charging in progress"*, prevenendo sfarfallii o stati incoerenti all'apertura della pagina.

- **Auto-Estrazione Nativa `libhook_qcarcam.so` su Nuove Installazioni DiLink 5.0 (`DiLink5QCarCamBackend.java`)**:
  - Aggiunta la funzione `ensureHookLibraryExtracted()` che su qualsiasi nuova installazione pulita dell'app estrae automaticamente la libreria `libhook_qcarcam.so` da `base.apk` o dalla directory native-libs dell'APK verso `/data/local/tmp/` e ne imposta i permessi di esecuzione (`chmod 755`), eliminando il problema dello schermo nero per i nuovi utenti/tester senza richiedere interventi manuali da ADB.

- **Orientamento Video Dritto su Pipeline GPU Shader (`GpuStreamScaler.java`, `GpuMosaicRecorder.java`)**:
  - Abilitato il parametro `uApplyManualYFlip = 1.0f` nello shader fragment OpenGL per DiLink 5.0 in modo da eseguire l'orientamento verticale corretto in un unico passaggio su GPU a costo zero di CPU, preservando al contempo `qcarcam_bridge.cpp` con l'indirizzamento lineare sequenziale standard `y * stride`.

- **Risoluzione Rilevamento Stato ACC su DiLink 5.0 (Sealion 7) & Sicurezza Screen Deterrent in Guida**:
  - `AccMonitor.java`: Risolto il bug di parsing su `dumpsys car_service PowerMode` che matchava la stringa statica `All items` (contenente sempre i termini `Standby`/`Sleep`/`4=`), causando un perenne falso positivo `accOn=false / sentryMode=true` durante la marcia. Il comando ora isola la riga specifica `current` (riconoscendo correttamente `Pre StartUp`, `StartUp`, `DisPlay on`).
  - **Fail-Safe Telemetrico di Marcia**: Aggiunto in `AccMonitor.java` il controllo proattivo su velocità veicolo (`speed.kmh > 0`) e marcia inserita (`gearMode` in D/R/N/M/S) per forzare immediatamente lo stato `accOn=true / inSentryMode=false`.
  - **Protezione Anti-Blocco Schermo (Screen Deterrent)**:
    - `ScreenDeterrent.java`: Integrato il controllo `isVehicleActive()` in `onMotionDetected()`, `fire()` e `shouldStop()` per impedire l'attivazione o interrompere all'istante l'overlay se il veicolo è in marcia o attivo.
    - `DeterrentActivity.java`: Aggiunto il controllo in `onCreate()` e `shouldFinishNow()` per auto-chiudere immediatamente l'Activity a tutto schermo qualora il veicolo sia acceso o in movimento, ripristinando all'istante l'uso del touchscreen del pad.

- **Modalità Sentinella Autonoma (`armMode: power`) & Periodo di Grazia (15s)**:
  - **Sganciamento Totale da Dipendenze Cloud / Stato Serrature**: Configurato il default di `armMode` su `"power"` in `UnifiedConfigManager.kt` e nella configurazione on-device. Su DiLink 5.0 (Snapdragon SA8155P), l'armamento della Sentinella avviene direttamente e localmente sul segnale hardware `dumpsys car_service` `PowerMode STANDBY/SLEEP`, senza dipendere dalla ricezione del blocco porte via Cloud API/MQTT (che in garage interrati o con segnale 4G debole impediva l'attivazione).
  - **Periodo di Grazia (15 secondi)**: Introdotto in `CameraDaemon.java` un timer di grazia (`PowerArmGraceThread`) di 15 secondi dopo lo spegnimento/standby del veicolo (`ACC OFF`). Questo consente a conducente e passeggeri di scendere e chiudere le porte prima che la Sentinella armi i deterrenti visivi e la rilevazione di movimento basata su AI.
  - **Risoluzione Falsi Heartbeat `ACC ON` in `AccSentryDaemon.java`**: Corretta `readPowerLevel()` verificando preventivamente `AccMonitor.isAccOn()`. Su DiLink 5.0 l'HAL legacy restituiva costantemente `POWER_LEVEL_ON (2)`, annullando prematuramente lo standby di `CameraDaemon`.
  - **Risoluzione Dinamica APK nel Watchdog (`start_acc_sentry.sh` / `DaemonLauncher.kt`)**: Lo script di watchdog della sentinella risolve dinamicamente il percorso di `base.apk` tramite `pm path com.overdrive.app` a ogni avvio/respawn, evitando crash-loop in caso di aggiornamenti APK.
- **Integrazione Telemetria Hardware Diretta & Iniezione Runtime SDK DiLink 5.0 (Sealion 7)**:
  - **Architettura Compile-Only Stubs**: Estratti tutti gli stub fittizi `android.hardware.bydauto.*` da `app/src/main/java` verso il modulo `stubs-bydauto` compilato in un JAR `compileOnly`. Questo elimina completamente il problema dell'oscuramento delle classi OEM reali nel DEX dell'APK finale.
  - **Iniezione Runtime DEX (`Dilink5SdkInjector.java`)**: Implementata l'iniezione dinamica del DEX di sistema `/system/app/BydDataCollect/BydDataCollect.apk` in tutti i ClassLoader pertinenti (`PathClassLoader`, `ContextClassLoader`, `SystemClassLoader`).
  - **Bridge di Telemetria in Processo App & Sincronizzazione Multi-Path (`DaemonKeepaliveService` / `BydDataCollector`)**:
    - Su Android 11 (DiLink 5), l'accesso all'HAL richiede il binding del servizio di sistema `com.ts.appservice.caradapter.CarAdapterService`. Questo binding viene eseguito con successo nel processo applicativo registrato `com.overdrive.app`.
    - `BydDataCollector` viene inizializzato in `DaemonKeepaliveService.kt`, effettua il polling continuo dei 24 driver HAL (pressione TPMS pneumatici, contachilometri, tensione 12V, ecc.) e scrive atomicamente lo snapshot nei percorsi condivisi (`/storage/emulated/0/Android/data/com.overdrive.app/files/byd_telemetry_snap.json` e `/data/local/tmp/`).
    - Il daemon `byd_cam_daemon` (UID 2000) legge e unisce all'istante lo snapshot telemetrico restituendolo attraverso `/api/vehicle/state` alla UI web, Home Assistant, MQTT e ABRP.
  - **Inizializzazione Main Looper**: Chiamata esplicita a `Looper.prepareMainLooper()` in `CameraDaemon.main` per garantire che i singleton HAL che istanziano `Handler(Looper.getMainLooper())` (come `BYDAutoOtaDevice`, `BYDAutoSpeedDevice`, `BYDAutoAcDevice`) abbiano sempre una `MessageQueue` valida.
  - **Adattamento Builder Telemetria (`BydDataCollector`)**: Corretti i richiami per l'aggiornamento dinamico di pressione pneumatici (conversione bar/kPa), velocità motori (`rearMotorSpeed`), potenza istantanea (`enginePowerKw`) ed energia residua (`remainKwh`).

- **Risoluzione Aggiornamento Stato Batteria (SoC %) & Autonomia a Veicolo Spento / in Ricarica**:
  - `BydDataCollector.java`: Introdotto il tracciamento puntuale del successo di lettura HAL nel ciclo di polling (`socHalSucceeded`, `rangeHalSucceeded`, `fuelHalSucceeded`).
  - Risolto il problema del congelamento del SoC (es. bloccato al 67% durante la ricarica notturna): a veicolo spento / in sosta (ACC OFF) o quando l'HAL locale DiLink 5 restituisce `0.0` (unpopulated), i fallback e il merge dei dati da BYD Cloud/MQTT (`cs.socPercent`, `cs.elecRangeKm`, `cs.fuelPercent`, `cs.fuelRangeKm`) vengono ora applicati tempestivamente invece di essere bloccati dallo snapshot ereditato da `toBuilder()`.
  - `BydCloudDataMergeSocTest.java`: Aggiunta suite di test unitari a copertura del merge di SoC, autonomia elettrica e carburante (PHEV) in sosta e in marcia.
- **Risoluzione Risoluzione Video & Aspect Ratio su DiLink 5.0 (Full HD 1080p / 16:9 Standard)**:
  - `CameraProfile.java` & `CameraProfiles.java`: Introdotto il supporto per dimensioni encoder personalizzate. Impostata la risoluzione encoder di `dilink5_sealion7` a **1920×1080 @ 30 FPS** nativa (eliminando il calcolo legacy 4-strip che causava l'anomalo 960×2600 e lo stiramento verticale 2.7:1).
  - `GpuSurveillancePipeline.java`: Il costruttore e il supervisore della pipeline acquisiscono direttamente la risoluzione encoder risolta dal profilo (1920×1080 su DiLink 5).
  - `GpuMosaicRecorder.java`: Configurato il vertex/fragment shader OpenGL per eseguire il crop centrato 16:9 del sensore raw 1920×1300 verso canvas 1920×1080, preservando le proporzioni naturali dei sensori fisheye senza deformazioni.
- **Motore di Streaming DMA a 30.0 FPS Hardware & Eliminazione Tearing DMA (`hook_qcarcam.cpp`)**:
  - Sostituita l'intercettazione instabile di `clock_gettime` con un thread di streaming dedicato a 30.00 FPS con temporizzazione nanometrica su `CLOCK_MONOTONIC`.
  - Risolti i cali di framerate (~3.7 FPS) e il tearing orizzontale, garantendo un flusso video fluido a 30.0 FPS stabili verso MediaCodec e client di streaming.
- **Keep-Alive & Risveglio Sottosistema AVM per Modalità Sentinella su DiLink 5.0**:
  - `DiLink5QCarCamBackend.java` & `TsAvmCoordinator.java`: Integrato il binding e l'avvio preventivo del servizio di sistema `com.ts.avm.AvmAndroidService` (`startAvm()`) all'apertura del backend di cattura e all'armamento della Sentinella ad auto spenta, mantenendo alimentati i sensori fisici QCarCam.
- **Persistenza ADB (Wireless 5555 & USB) & Sblocco Alimentazione Periferiche su DiLink 5.0**:
  - `DiLink5PowerDiagnostics`: Aggiunta l'impostazione automatica persistente delle property di sistema (`persist.adb.tcp.port 5555`, `service.adb.tcp.port 5555`, `persist.sys.usb.config mtp,adb` e `adb_enabled 1`) per evitare la disattivazione del debug ADB al riavvio/sleep.
  - `AccSentryDaemon`: Rimosso il vincolo esclusivo DiLink 4 su `BYDAutoSpecialDevice`, abilitando il mantenimento dell'alimentazione dei rail USB / modem anche su DiLink 5.0 (Snapdragon SA8155P).
  - `RecordingModeManager`: Esteso il keep-alive delle telecamere e del backend nativo a basso livello ad auto spenta (ACC OFF) per DiLink 5.0.
- **Supporto Audio & AVAS su DiLink 5.0 / Android Automotive**:
  - `AvasController`: Aggiunto fallback con risoluzione binder VHAL (`CarPropertyBridge`) quando `getSystemService("auto")` non è registrato a livello di sistema operativo, abilitando la corretta esecuzione dei pattern sonori e prevenendo l'errore ingannevole di servizio non disponibile.
- **Stato Veicolo & Telemetria Istantanea (/api/vehicle/state)**:
  - `VehicleControlApiHandler`: Implementata la sintesi iniziale e il fallback dinamico da Cloud/VHAL in `handleGetState()` quando il collector locale non ha ancora completato il primo polling hardware, evitando risposte di errore "Dati veicolo non disponibili" al caricamento della dashboard.
- **Rilevamento Stato di Ricarica (Charging Status)**:
  - `BydDataCollector`: Se il cloud conferma lo stato di ricarica attiva (`cs.getChargingStateAsSdk() == 1`) con cavo collegato (`gunState >= 2`), promuove `b.chargingState` a `CHARGING (1)` e notifica `ChargingDetector` anche se l'hardware DiLink 5 locale restituisce `READY(0)` o `UNAVAILABLE`.
  - Popolato il tempo stimato residuo (`chargingRestTimeHours` / `chargingRestTimeMinutes`) dal cloud snapshot quando non fornito dall'hardware.
  - Corretta la localizzazione italiana in `it.json`: `"state_plugged": "Collegato"` (anziché la traduzione letterale `"Collegato in"`).
- **Gating Hotspot DVR Telecamere**:
  - `UnifiedConfigManager.resolveOemDashcamId()`: Eliminata l'assegnazione automatica di un canale DVR (`0/1`) quando il veicolo non ha una dashcam OEM installata/configurata (tipico dei veicoli per il mercato europeo), evitando la comparsa ingannevole dell'hotspot DVR sul selettore telecamere.
- **Rilevamento ACC OFF & Armamento Sentinella su DiLink 5.0**:
  - `AccMonitor`: Integrato il controllo dello stato di blocco porte (veicolo chiuso/bloccato) e display spento su DiLink 5.0 per determinare in modo affidabile lo stato di veicolo parcheggiato e consentire l'armamento della Sentinella.
- **Modello Veicolo `sealion7` (BYD Sealion 7)**:
  - Aggiunto `sealion7` nel catalogo `manifest.json` dei modelli 3D con batteria LFP Blade da 82.5 kWh nominali.
- **Integrazione Telemetria & VHAL DiLink 5.0**:
  - `CarPropertyBridge`: Aggiunta risoluzione diretta binder tramite `ServiceManager` per interrogare i servizi VHAL Android Automotive nativi (`car_service`).
  - `BydDataCollector`: Gestito il valore grezzo `0.0` di `StatisticDevice` come non popolato per permettere il fallback tempestivo su cloud/VHAL.
  - Esteso `mergeCloudData`: Merge automatico in assenza di segnale hardware per finestrini (LF, RF, LR, RR, tettuccio), portellone/bagagliaio e serrature.
  - `VehicleControlApiHandler`: Overlay a strati (SDK -> VHAL -> Cloud Snapshot) per restituire lo stato veritiero di batteria (SOC), autonomia, finestrini, serrature e climatizzazione nell'interfaccia utente.

## [50.0] - 2026-08-27

### Aggiunte (Added)
- **Supporto Completo BYD DiLink 5.0 (Snapdragon SA8155P / Sealion 7)**:
  - **Driver 4-Telecamere Hardware AIS/QCarCam**: Inizializzazione contemporanea dei 4 canali fisici (Anteriore, Destra, Posteriore, Sinistra) a risoluzione 1920x1300 @ 30 FPS.
  - **Compositore Griglia 2x2 Nativo C++**: Vista "Tutte le telecamere" con griglia 2x2 fluida e senza artefatti di slicing.
  - **Commutazione Istantanea Telecamere Hardware**: Selezione diretta dal diagramma dell'auto tra Anteriore, Destra, Posteriore e Sinistra con orientamento verticale naturale corretto.
  - **Architettura Multi-Client Socket (`hook_qcarcam.cpp`)**: Supporto fino a 8 client concorrenti con sincronizzazione a byte stream senza collisioni.
- **Nuovo modulo autonomo `dilink-probe` (DiLink 5 Camera Dumper & Diagnostic Probe)**:
  - App APK standalone per raccogliere informazioni hardware, kernel, HAL e APK su infotainment BYD DiLink 5.0 (Sealion 7 / Snapdragon 8155).
  - Dump automatico di proprietà di sistema (`getprop`), `dumpsys` (`media.camera`, `evs`, `ServiceManager`), e HAL (`lshal`, EVS 1.0/1.1, QCarCam).
  - Ispezione nodi V4L2 `/dev/video*`, `/dev/media*` e configurazioni XML vendor (`/vendor/etc/qcarcam`, `/vendor/etc/camera`, `/vendor/etc/evs`).
  - Classloader & Reflection probe per identificare i punti di ingresso Java/AIDL/HIDL disponibili su DiLink 5.
  - Test interattivo Android Camera2 API (`CameraManager.getCameraIdList()`, apertura `CameraDevice`, `ImageReader` continuo e calcolo FPS in tempo reale a schermo).
  - Test interattivo e client AIDL per il servizio di sistema `com.ts.avm.AvmAndroidService` (`getAvmStatus()`, `startAvm()`, `stopAvm()`, `IAvmServiceListener`).
  - Acquisizione ed estrazione diretta con successo del fotogramma hardware raw (1920x1300 @ 30 FPS YUV422) tramite Qualcomm QCarCam / AIS Client nativo (`libais_client.so`), convalidando la fattibilità al 100% della registrazione video Dashcam e Sentry Mode su DiLink 5.
  - Estrazione automatica e backup degli APK OEM di fabbrica (`com.byd.avm`, `com.ts.avm`, `com.byd.cameramanager`, ecc.) e relative librerie native `.so`.
  - Salvataggio automatico del dump ZIP sia su memoria interna (`/sdcard/Download/`) sia su tutte le chiavette e schede SD USB rimovibili connesse.
- **Supporto Nativo BYD DiLink 5.0 (Snapdragon SA8155P / Sealion 7) in OverDrive**:
  - **Sidecar Nativo (`dilink5_cam_sidecar`)**: Demone binario C++ standalone che bypassa le restrizioni del linker namespace Android (`classloader-namespace`) e trasmette i fotogrammi a 30 FPS su socket UNIX astratto `@dilink5_cam`.
  - **Bridge Nativo C++ (`qcarcam_bridge.cpp`)**: Riceve i frame dal sidecar e li inietta direttamente nella `ANativeWindow` / `SurfaceTexture` di Android per la pipeline OpenGL ES e MediaCodec.
  - **Backend Driver Java (`DiLink5QCarCamBackend.java`)**: Driver integrato nel ciclo di vita delle telecamere di OverDrive per Dashcam, Sentry e pipeline EGL/MediaCodec.
  - **Coordinatore AIDL TS AVM (`TsAvmCoordinator.java`)**: Gestione e risveglio del sottosistema Surround View 360° senza conflitti tramite `com.ts.avm.AvmAndroidService`.
    - **Profilo Hardware (`CameraProfiles.java`)**: Aggiunto profilo dedicato `dilink5_sealion7` con risoluzione nativa 1920x1300 e auto-rilevamento intelligente.
    - **Pipeline EGL & Dashcam Sicura (`EGLCore.java`, `OemDashcamPipeline.java`, `PanoramicCameraGpu.java`, `GpuStreamScaler.java`)**: Aggiunto fallback automatico GLES2 per contesti headless EGL, layout 1:1 passthrough a schermo intero su DiLink 5, supporto risoluzione nativa 1920x1300 e flusso H.264 attivo su WebSocket porta 8887.
    - **Iniezione Hook Hardware Zero-Copy (`hook_qcarcam.cpp`, `qcarcam_bridge.cpp`)**: Intercettazione diretta dei puntatori DMA a 30.0 FPS dalla pipeline nativa Qualcomm AIS (`/vendor/bin/qcarcam_test`) tramite disassembly degli offset a 64-bit (`0x7764`), con conversione UYVY->RGBA (correzione packing Little-Endian canali Rosso/Verde/Blu) e instradamento in tempo reale sul socket astratto `@dilink5_cam`.
    - **Supervisore Automatico Hardware (`DiLink5QCarCamBackend.java`)**: Gestione autonoma del ciclo di vita del processo di cattura hardware `qcarcam_test` con `libhook_qcarcam.so` e `1cam.xml` inclusi come asset e libreria condivisa nativa, con auto-restart trasparente e zero dipendenza da sessioni ADB manuali.
    - **Diagnostica Energetica & Sentry Keep-Alive (`DiLink5PowerDiagnostics.java`)**: Modulo di monitoraggio continuo e tracciamento su file flash (`/sdcard/Overdrive/sentry_power_test.log`), con acquisizione preventiva di `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`, policy `WIFI_SLEEP_POLICY_NEVER` e whitelist Doze per verificare la persistenza di CPU, Wi-Fi e telecamere durante lo spegnimento del veicolo (ACC OFF).
