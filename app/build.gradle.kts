import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import javax.net.ssl.HttpsURLConnection

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val openh264Version = "2.6.0"

// SafeKeep sign-in app IDs for official builds: safekeep-oauth.properties at the repo root
// (gitignored) or -P / ~/.gradle/gradle.properties overrides.
// Empty in source builds, where users enter their own app registration instead.
val safeKeepOAuth = Properties().apply {
    val f = rootProject.file("safekeep-oauth.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun safeKeepOAuthField(key: String): String {
    val v = ((project.findProperty(key) as? String) ?: safeKeepOAuth.getProperty(key, "")).trim()
    return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

// Auto-download OpenH264 from Cisco's official binary releases
tasks.register("downloadOpenH264") {
    val openh264Dir = file("src/main/cpp/openh264")
    
    doLast {
        // Pins are for Cisco's unmodified 2.6.0 Android ARM64 binary. Keep the
        // download and cached build input tied to the same reviewed release.
        val archiveSha256 = "c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a"
        val librarySha256 = "4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07"
        val archiveBytes = 496662L
        val libraryBytes = 1276136L

        fun verifyInput(input: File, expectedBytes: Long, expectedSha256: String) {
            if (!Files.isRegularFile(input.toPath(), LinkOption.NOFOLLOW_LINKS)
                || input.length() != expectedBytes) {
                throw GradleException("Unexpected OpenH264 input type or size: ${input.name}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            input.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (actual != expectedSha256) {
                throw GradleException("OpenH264 SHA-256 mismatch: ${input.name}")
            }
        }

        // Only arm64-v8a is packaged for BYD cars.
        val abiMap = mapOf(
            "arm64-v8a" to "https://ciscobinary.openh264.org/libopenh264-${openh264Version}-android-arm64.8.so.bz2"
            // Removed armeabi-v7a to reduce APK size
        )
        
        abiMap.forEach { (abi, url) ->
            val libDir = file("${openh264Dir}/lib/${abi}")
            libDir.mkdirs()
            
            val soFile = file("${libDir}/libopenh264.so")
            if (Files.exists(soFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                verifyInput(soFile, libraryBytes, librarySha256)
            } else {
                println("Downloading OpenH264 ${openh264Version} for ${abi}...")
                val stagingDir = Files.createTempDirectory(libDir.toPath(), ".openh264-").toFile()
                try {
                    val bzFile = File(stagingDir, "download.so.bz2")
                    val unpacked = File(stagingDir, "libopenh264.so")
                    val connection = URI(url).toURL().openConnection() as HttpsURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            throw GradleException("OpenH264 download returned HTTP ${connection.responseCode}; redirects are not accepted")
                        }
                        connection.inputStream.use { input ->
                            bzFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > archiveBytes) {
                                        throw GradleException("OpenH264 download exceeds the expected archive size")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    verifyInput(bzFile, archiveBytes, archiveSha256)
                    ant.invokeMethod("bunzip2", mapOf("src" to bzFile.absolutePath, "dest" to unpacked.absolutePath))
                    verifyInput(unpacked, libraryBytes, librarySha256)
                    Files.move(
                        unpacked.toPath(), soFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    println("✓ OpenH264 verified for ${abi}")
                } finally {
                    if (!stagingDir.deleteRecursively()) {
                        throw GradleException("Could not clean OpenH264 staging directory: ${stagingDir.name}")
                    }
                }
            }
        }
        
        // Download headers from Cisco's GitHub
        val includeDir = file("${openh264Dir}/include/wels")
        includeDir.mkdirs()
        listOf("codec_api.h", "codec_app_def.h", "codec_def.h", "codec_ver.h").forEach { h ->
            val f = file("${includeDir}/${h}")
            if (!f.exists()) {
                try {
                    ant.invokeMethod("get", mapOf(
                        "src" to "https://raw.githubusercontent.com/cisco/openh264/v${openh264Version}/codec/api/wels/${h}",
                        "dest" to f.absolutePath
                    ))
                } catch (e: Exception) { }
            }
        }
    }
}

tasks.matching { it.name.contains("CMake") || it.name.contains("ExternalNative") }.configureEach {
    dependsOn("downloadOpenH264", "downloadOpenCV", "verifyFastCam")
}

// Hebrew: Android/Java still match the legacy `iw` qualifier. AppCompat already
// ships a sparse values-iw, which WINS over our values-he and leaves the UI
// in English. Mirror values-he into a generated values-iw overlay.
val hebrewIwResDir = layout.buildDirectory.dir("generated/res/iwAlias")
val syncHebrewIwResources by tasks.registering(Sync::class) {
    from("src/main/res/values-he")
    into(hebrewIwResDir.map { it.dir("values-iw") })
}
tasks.configureEach {
    if (name == "preBuild" || name.endsWith("PreBuild")
        || (name.contains("Merge") && name.contains("Resources"))
        || (name.startsWith("map") && name.contains("SourceSet"))) {
        dependsOn(syncHebrewIwResources)
    }
}

// OpenCV-mobile version for surveillance module (minimal build, ~3MB vs ~20MB)
// https://github.com/nihui/opencv-mobile
val opencvMobileVersion = "4.10.0"

// SECURITY (run-2 fix): SHA-256 pin for the opencv-mobile android zip. Any
// successfully downloaded zip MUST match this digest before extraction —
// a substituted artifact would be statically linked into libsurveillance.so.
// The upstream v4.10.0 asset is currently absent (404), in which case the build
// keeps its existing no-OpenCV fallback; a *successful* download with an empty
// or mismatching pin fails closed instead of building unverified code.
// Maintainers: set this to the SHA-256 of the reviewed opencv-mobile release zip.
val opencvMobileZipSha256 = ""
tasks.register("downloadOpenCV") {
    val opencvDir = file("src/main/cpp/opencv")
    
    doLast {
        val libDir = file("${opencvDir}/lib/arm64-v8a")
        libDir.mkdirs()
        val includeDir = file("${opencvDir}/include")
        
        // opencv-mobile uses static library (.a)
        val staticLib = file("${libDir}/libopencv_core.a")
        
        if (!staticLib.exists()) {
            println("Downloading opencv-mobile ${opencvMobileVersion} for Android...")
            
            // Correct URL format: /releases/download/vVERSION/
            val zipUrl = "https://github.com/nihui/opencv-mobile/releases/download/v${opencvMobileVersion}/opencv-mobile-${opencvMobileVersion}-android.zip"
            val zipFile = file("${opencvDir}/opencv-mobile-android.zip")
            
            try {
                // Download opencv-mobile
                println("Downloading from: $zipUrl")
                ant.invokeMethod("get", mapOf(
                    "src" to zipUrl,
                    "dest" to zipFile.absolutePath
                ))
                
                if (zipFile.exists() && zipFile.length() > 100000) {
                    // SECURITY (run-2 fix): digest gate before any byte is extracted.
                    val digest = MessageDigest.getInstance("SHA-256")
                    zipFile.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") {
                        (it.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    if (opencvMobileZipSha256.isBlank() || actual != opencvMobileZipSha256) {
                        zipFile.delete()
                        throw GradleException(
                            "SECURITY: opencv-mobile zip SHA-256 mismatch (got $actual). " +
                            "Refusing to link unverified native code. Set opencvMobileZipSha256 " +
                            "to the reviewed artifact's digest or restore a vendored copy."
                        )
                    }
                    println("✓ opencv-mobile zip digest verified")
                    println("Extracting opencv-mobile (${zipFile.length() / 1024 / 1024}MB)...")
                    
                    ant.invokeMethod("unzip", mapOf(
                        "src" to zipFile.absolutePath,
                        "dest" to opencvDir.absolutePath
                    ))
                    
                    // List extracted contents for debugging
                    opencvDir.listFiles()?.forEach { println("  Found: ${it.name}") }
                    
                    // opencv-mobile extracts to opencv-mobile-VERSION-android/
                    val extractedDir = file("${opencvDir}/opencv-mobile-${opencvMobileVersion}-android")
                    
                    if (extractedDir.exists()) {
                        // Copy arm64-v8a static libs
                        val extractedLibDir = file("${extractedDir}/arm64-v8a/lib")
                        if (extractedLibDir.exists()) {
                            extractedLibDir.listFiles()?.forEach { f ->
                                println("  Copying lib: ${f.name}")
                                f.copyTo(file("${libDir}/${f.name}"), overwrite = true)
                            }
                            println("✓ opencv-mobile libraries copied")
                        } else {
                            println("⚠ Lib dir not found: ${extractedLibDir}")
                        }
                        
                        // Copy headers
                        val extractedInclude = file("${extractedDir}/arm64-v8a/include")
                        if (extractedInclude.exists()) {
                            if (includeDir.exists()) includeDir.deleteRecursively()
                            extractedInclude.copyRecursively(includeDir, overwrite = true)
                            println("✓ opencv-mobile headers copied")
                        } else {
                            println("⚠ Include dir not found: ${extractedInclude}")
                        }
                        
                        // Cleanup
                        zipFile.delete()
                        extractedDir.deleteRecursively()
                        
                        println("✓ opencv-mobile ${opencvMobileVersion} installed (~3MB vs ~20MB)")
                    } else {
                        println("⚠ Extracted dir not found: ${extractedDir}")
                        println("  Available: ${opencvDir.listFiles()?.map { it.name }}")
                    }
                } else {
                    println("⚠ Download failed or file too small: ${zipFile.length()} bytes")
                }
            } catch (e: Exception) {
                println("⚠ opencv-mobile download failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("✓ opencv-mobile found at ${libDir}")
        }
    }
}

// Check surveillance dependencies before build
tasks.register("checkSurveillanceDeps") {
    dependsOn("downloadOpenCV", "verifyFastCam")
}

// Ensure fast_cam precompiled binaries are present before CMake build
tasks.register("verifyFastCam") {
    // SECURITY (run-2 fix): the fast_cam binaries are git-TRACKED and pinned by
    // SHA-256. The old task fetched a mutable tarball from a third-party GitHub
    // release with only a length>1000 gate and copied it verbatim into
    // jniLibs/assets (packaged into the APK and executed on device). There is no
    // network fallback anymore: if a pinned file is missing or its digest does
    // not match, the build fails and the file must be restored from git.
    val jniLib = file("src/main/jniLibs/arm64-v8a/libfast_cam_client.so")
    val assetBin = file("src/main/assets/dilink5/fast_cam_capture")
    val header = file("src/main/cpp/include/fast_cam_bridge.h")

    val expected = mapOf(
        jniLib to "a00600f5545832fa533bd8199bf1b8ebb462988d8a453052c2f772246d699df7",
        assetBin to "8ac709ce9b889e7f93f39519a695c66f0f65bb4022f9b2abf9c8f5c403239e5d",
        header to "29b492c5d0a32f34424a4d88c58ac4f2637d08d3904b653ba2aa2bccb05d8d2f"
    )

    fun sha256Of(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    doLast {
        for ((f, expectedHash) in expected) {
            if (!f.isFile) {
                throw GradleException(
                    "SECURITY: pinned fast_cam artifact is missing: ${f.path}. " +
                    "Restore it from git (git checkout -- ${f.path}); network download is disabled."
                )
            }
            val actual = sha256Of(f)
            if (actual != expectedHash) {
                throw GradleException(
                    "SECURITY: fast_cam artifact ${f.name} SHA-256 mismatch: expected $expectedHash, got $actual. " +
                    "Do not build with unverified binaries; restore from git."
                )
            }
        }
        println("✓ fast_cam pinned binaries verified (SHA-256)")
    }
}


// Keep the source compatibility symlink required by CONTRIBUTING, while packaging
// the canonical web/shared tree once. Depending on the producer (rather than
// another preBuild sibling) ensures fast_cam_capture is staged after extraction.
val packagedMainAssetsDir = layout.buildDirectory.dir("generated/packaged-assets/main")
val preparePackagedMainAssets = tasks.register<Sync>("preparePackagedMainAssets") {
    dependsOn("verifyFastCam")
    from("src/main/assets") {
        exclude("web/local/shared", "web/local/shared/**")
    }
    into(packagedMainAssetsDir)
}

// AGP turns asset source directories into plain paths and does not retain a
// TaskProvider's producer dependency. Order every variant's actual merge
// explicitly, so packaging cannot run before Sync creates its input files.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(preparePackagedMainAssets)
    }
}

// Unit packaging contracts inspect the same staged bytes that Android consumes.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(preparePackagedMainAssets)
    systemProperty("overdrive.packagedMainAssetsDir", packagedMainAssetsDir.get().asFile.absolutePath)
}


// Task to extract web assets to /data/local/tmp/web on device
// Run: ./gradlew :app:extractWebAssets
tasks.register("extractWebAssets") {
    description = "Extracts web assets from APK to /data/local/tmp/web on connected device"
    group = "deployment"
    
    doLast {
        val webSrcDir = file("src/main/assets/web")
        if (!webSrcDir.exists()) {
            println("⚠ No web assets found at ${webSrcDir}")
            return@doLast
        }
        
        println("Extracting web assets to device...")
        
        // Create target directory
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/shared")
        }
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/local")
        }
        
        // Push files. The device path has to use forward slashes whatever the
        // host separator is, or a Windows run pushes files literally named
        // "local\live-view.html" and never updates the real nested ones.
        webSrcDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(webSrcDir).invariantSeparatorsPath
            val targetPath = "/data/local/tmp/web/${relativePath}"
            println("  → ${relativePath}")
            exec {
                commandLine("adb", "push", file.absolutePath, targetPath)
            }
        }
        
        println("✓ Web assets extracted to /data/local/tmp/web/")
    }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
        }
    }
    namespace = "com.overdrive.app"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            res.srcDir(hebrewIwResDir)
            // The merge-task dependency above produces this directory before
            // AGP reads it; variant overlays remain unchanged.
            assets.setSrcDirs(listOf(packagedMainAssetsDir))
        }
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.overdrive.app"
        // minSdk=28 required for Image.getHardwareBuffer() — the ImageReader
        // zero-copy camera path uses it to bypass SurfaceFlinger throttling.
        // targetSdk pinned at 25 to keep app_process daemon behavior stable
        // (newer targetSdks tighten background restrictions).
        minSdk = 28
        targetSdk = 25
        // Release identity is the build's TRUE self-identity (BuildConfig), used
        // by AppUpdater.getInstalledVersion() => "<channel>-v<versionName>" and
        // every surface that falls back to it (About row, post-update toast,
        // /status when VERSION_FILE is absent). It MUST track the real shipped
        // release, otherwise BuildConfig goes stale against the GitHub builds
        // (the "About shows 26.0 / version mismatch" class of bugs). Driven by
        // Gradle properties so the release/braveheart pipeline stamps the real
        // value (e.g. `-PoverdriveVersionName=27.4 -PoverdriveVersionCode=12`)
        // without a source edit per release; the defaults track the current
        // rolling head so a plain local build is still accurate.
        versionCode = (project.findProperty("overdriveVersionCode") as? String)?.toIntOrNull() ?: 102
        versionName = (project.findProperty("overdriveVersionName") as? String) ?: "50.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Note: abiFilters removed - using splits.abi instead for size optimization

        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }

        // Default diagnostics fields (overridden per buildType). LOG_CAPTURE gates the
        // in-app log-upload UI; LOG_UPLOAD_URL is the Cloudflare Worker endpoint. Both
        // OFF/empty by default (release/debug); braveheart flips them on. Restored from
        // the working build's generated BuildConfig (default-config fields).
        buildConfigField("boolean", "LOG_CAPTURE", "false")
        buildConfigField("String", "LOG_UPLOAD_URL", "\"\"")
        // Hard-off for the in-car DiLink 5 BYD Cloud keep-alive poller in this
        // release. A future build must deliberately flip this before the
        // independent runtime config gate can enable network polling.
        buildConfigField("boolean", "DI5_CLOUD_KEEPALIVE_POLLER_ENABLED", "false")
        buildConfigField("String", "SAFEKEEP_GOOGLE_CLIENT_ID", safeKeepOAuthField("safekeep.google.clientId"))
        buildConfigField("String", "SAFEKEEP_GOOGLE_CLIENT_SECRET", safeKeepOAuthField("safekeep.google.clientSecret"))
        buildConfigField("String", "SAFEKEEP_MICROSOFT_CLIENT_ID", safeKeepOAuthField("safekeep.microsoft.clientId"))
        buildConfigField("String", "SAFEKEEP_DROPBOX_APP_KEY", safeKeepOAuthField("safekeep.dropbox.appKey"))
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    lint {
        // This stops the build from failing due to the old targetSdk 28
        checkReleaseBuilds = false
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Enable minification and shrinking for release builds
            isMinifyEnabled = true
            isShrinkResources = true
            
            // Auto-detect if DaemonLogConfig has any logging flags enabled.
            // When ALL flags are false (production): include proguard-rules-strip-logs.pro
            //   → R8 strips all log calls from bytecode
            // When ANY flag is true (debug build): exclude proguard-rules-strip-logs.pro
            //   → log calls stay in bytecode, DaemonLogConfig controls which tags write to disk
            val logConfigFile = file("src/main/java/com/overdrive/app/logging/DaemonLogConfig.java")
            val loggingEnabled = if (logConfigFile.exists()) {
                val content = logConfigFile.readText()
                val enableAllMatch = Regex("""public static final boolean ENABLE_ALL\s*=\s*true""").containsMatchIn(content)
                val anyFlagTrue = Regex("""public static final boolean (?!ANY_LOGGING_ENABLED)\w+\s*=\s*true""").containsMatchIn(content)
                enableAllMatch || anyFlagTrue
            } else false
            
            val proguardFilesList = mutableListOf(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            if (loggingEnabled) {
                // Logging enabled: do NOT include strip-logs → log calls survive R8
                println("⚠ DaemonLogConfig: Logging ENABLED — DaemonLogger file logging kept, console still stripped")
            } else {
                // Production: include strip-logs → R8 removes all log calls
                proguardFilesList.add(file("proguard-rules-strip-logs.pro"))
            }
            // Console logging (android.util.Log, System.out/err) is ALWAYS stripped in
            // release builds, independent of the DaemonLogConfig file-logging flags above.
            // (loggingEnabled only governs DaemonLogger FILE logging via strip-logs.)
            // Optional: this ruleset is not part of the open-source tree, so only add it
            // when present. Without it the build still succeeds — console calls simply
            // survive R8 instead of being stripped.
            val stripConsole = file("proguard-rules-strip-console.pro")
            if (stripConsole.exists()) proguardFilesList.add(stripConsole)
            proguardFiles(*proguardFilesList.toTypedArray())
            
            signingConfig = signingConfigs.getByName("release")
            
            // Update channel: "alpha" for release builds (checks alpha tag on GitHub)
            buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")
        }
        debug {
            isMinifyEnabled = false

            // Debug builds match the active braveheart channel
            buildConfigField("String", "UPDATE_CHANNEL", "\"braveheart\"")
        }
        // Braveheart: the rolling/bleeding-edge channel, shipped as a RELEASE build but
        // with diagnostics ON so braveheart customers can upload complete per-daemon
        // logs. initWith(release) inherits minify/shrink/signing; we reset proguardFiles
        // to the base rules WITHOUT the log-stripping file so logcat + DaemonLogger file
        // calls survive R8 regardless of DaemonLogConfig flags.
        create("braveheart") {
            initWith(getByName("release"))
            // initWith copies release's proguardFiles (which may include strip-logs).
            // Reset and re-add ONLY the base rules — WITHOUT strip-logs — so logging
            // survives R8 in braveheart. (proguard-rules.pro has no log-stripping rules.)
            setProguardFiles(emptyList<Any>())
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            // Release signing carries over via initWith; set explicitly so a future
            // release-block change can't silently drop it.
            signingConfig = signingConfigs.getByName("release")
            // Rolling channel + diagnostics on.
            buildConfigField("String", "UPDATE_CHANNEL", "\"braveheart\"")
            buildConfigField("boolean", "LOG_CAPTURE", "true")
            // Cloudflare Worker that stashes an uploaded daemon log and returns a short
            // retrieval code. Supplied at build time (-PoverdriveLogUploadUrl=…) rather
            // than hardcoded, so this tree carries no deployment-specific endpoint.
            // Empty → the in-app log upload is disabled, same as release/debug.
            buildConfigField("String", "LOG_UPLOAD_URL",
                "\"${project.findProperty("overdriveLogUploadUrl") as? String ?: ""}\"")
        }
    }
    
    // Split APKs by ABI - creates smaller APKs per architecture
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")  // Only arm64 for BYD
            isUniversalApk = false  // Don't create universal APK
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            // Tell Gradle to pick up .so files from your custom download folder
            jniLibs.srcDirs("src/main/cpp/openh264/lib")
        }
    }
    kotlinOptions { jvmTarget = "11" }
    
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
            // Both paho.mqttv3 and paho.mqttv5 jars ship the same i18n
            // bundle.properties — pick first to silence the merge conflict.
            // The file is OSGi metadata, never read at runtime in our setup.
            pickFirsts += listOf(
                "bundle.properties"
            )
        }
        // Exclude unnecessary native libs from dependencies
        jniLibs {
            // CRITICAL: Compresses .so files in the APK (saves ~20MB+)
            useLegacyPackaging = true

            // libtailscale.so is a UPX-packed Android executable intentionally
            // disguised as a JNI library so PackageManager extracts it. Running
            // llvm-strip after UPX rewrites the tested payload and removes UPX's
            // integrity metadata, so package this one file byte-for-byte.
            keepDebugSymbols += "**/libtailscale.so"
            // libwireguard.so (wgproxy) is UPX-packed the same way
            keepDebugSymbols += "**/libwireguard.so"

            // Keep only arm64-v8a (You already have this, but good to keep)
            excludes += listOf(
                "lib/armeabi-v7a/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
    }
}

/*
 * BYD SDK Stubs Architecture:
 * 
 * The classes in android.hardware.bydauto.* are compile-time stubs that allow
 * the code to compile without the actual BYD SDK JAR.
 * 
 * At runtime on BYD devices:
 * - The real BYD SDK classes are loaded by the boot classloader (higher priority)
 * - Our managers (RadarManager, BodyworkManager) use REFLECTION to get instances
 * - Class.forName() returns the real class from the system framework, not our stub
 * - The stubs in our APK are never actually instantiated
 * 
 * This works because:
 * 1. Boot classloader classes take precedence over app classes
 * 2. We use reflection: Class.forName("android.hardware.bydauto.radar.BYDAutoRadarDevice")
 * 3. getInstance() is called via reflection on the real class
 */

// ── DiLink BYD Auto compile stubs (built from SOURCE, not bundled into APK) ──────────
val bydautoStubsClasses = layout.buildDirectory.dir("bydauto-stubs/classes")
val compileBydautoStubs = tasks.register<JavaCompile>("compileBydautoStubs") {
    source(fileTree(rootProject.file("stubs-bydauto")) { include("android/**/*.java") })
    classpath = files(android.bootClasspath)
    options.release.set(17)
    destinationDirectory.set(bydautoStubsClasses)
}
val bydautoStubsJar = tasks.register<Jar>("bydautoStubsJar") {
    dependsOn(compileBydautoStubs)
    from(bydautoStubsClasses)
    archiveFileName.set("bydauto-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("bydauto-stubs"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Onboarding overlay motion. Both were transitive-only (pulled by
    // navigation-fragment + material); declared explicitly so a transitive
    // bump can't drop the edge the onboarding guide depends on.
    //   - dynamicanimation: SpringAnimation for the tip-card settle + wizard
    //     success scale-in (Choreographer-driven, no GL — cheap on Adreno 610).
    //   - transition: AutoTransition / MaterialFadeThrough for stepper + card
    //     content swaps (already used by SettingsFragment, was transitive).
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.transition)
    
    // QR Code generation
    implementation(libs.zxing.core)

    // MapLibre Native Android — GPU-accelerated vector map renderer (BSD, no key).
    // RoadSense map view: route search + hazard-icon plotting over OpenFreeMap tiles.
    // Pinned to the conservative 11.x line (minSdk 21 << our 28); ships an arm64-v8a
    // .so that rides the existing arm64-only split + useLegacyPackaging path.
    implementation(libs.maplibre.android.sdk)

    // NOTE: ferrostar:core was evaluated for turn-by-turn guidance and REJECTED —
    // 0.51.0 is compiled with Kotlin 2.3.0 (+ drags kotlin-stdlib 2.3.20, newer
    // okhttp/okio) which is incompatible with this project's Kotlin 2.0.21, and it
    // also requires core-library desugaring + a JNA/Rust .so per ABI. Bumping the
    // app-wide Kotlin toolchain for one feature is an unacceptable regression risk.
    // Instead the guidance state machine (Valhalla route parse, off-route detection,
    // step advancement, ETA) is implemented natively in com.overdrive.app.navmap.nav
    // against the Valhalla JSON — no heavy native dep, no toolchain change.
    
    // RTMP streaming client for pushing to MediaMTX
    implementation(libs.rtmp.client)
    
    // ADB client for daemon launching
    implementation(libs.dadb)
    
    // WebSocket server for zero-latency H.264 streaming
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    

    
    implementation(libs.androidx.work.runtime.ktx)
    
    // TensorFlow Lite for AI inference. CPU-only (XNNPACK) — GPU delegate
    // intentionally removed: on Adreno 610 (unified-memory SoC) concurrent
    // OpenCL inference and the H.265 hardware encoder share one DDR bus,
    // producing 200–300 ms eglSwapBuffers stalls during recording. CPU
    // inference is the only physical bypass for the bandwidth contention.
    // See YoloDetector.kt for the full mechanism comment.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    
    // OkHttp for Telegram HTTP client with proxy support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Encrypted SharedPreferences for secure token/owner storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SafeKeep SFTP destination (pure Java, BSD-style licence, no mandatory dependencies).
    implementation("com.github.mwiede:jsch:2.28.7")
    // SafeKeep age encryption uses Tink's X25519/HKDF. Already on the runtime classpath at this
    // exact version via security-crypto; declared so it is visible at compile time.
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    
    // H2 Database - Pure Java embedded SQL (no native dependencies, no .so files)
    // Works for UID 2000 because it's 100% Java bytecode - no Android framework needed
    implementation("com.h2database:h2:2.2.224")

    // Eclipse Paho MQTT - Pure Java MQTT client (no native dependencies)
    // mqttv3 used by HA/Mosquitto publish path (MqttPublisherService).
    // mqttv5 used by BydCloudMqttSubscriber — BYD's EMQ broker only pushes
    // vehicleInfo events to MQTT v5 subscribers; v3.1.1 connects fine but
    // gets zero messages.  The two lib jars use different packages so they
    // coexist cleanly.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    testImplementation(libs.junit)
    // Android's mockable org.json stubs throw in local JVM tests.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Compile against BYD Auto stubs without bundling dummy mocks into APK classes.dex
    compileOnly(files(bydautoStubsJar.flatMap { it.archiveFile }))
    testImplementation(files(bydautoStubsJar.flatMap { it.archiveFile }))
}

