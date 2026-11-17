/* Keep published walkthroughs linked to the actual car and browser menus. */
import assert from "node:assert/strict";
import { readFile, readdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

export async function validateSetupGuide(root, guide, reference) {
    const { chapters, features, credentials, daemons } = guide;
    const routes = new Set(["overview", "reference", "credentials"]);
    const platforms = new Set(["di3", "di4", "di5", "camera2"]);
    const unique = (values, label) => assert.equal(new Set(values).size, values.length, `${label} must be unique`);
    unique(chapters.map(item => item.id), "Chapter IDs");
    unique(features.map(item => item.name), "Feature names");
    unique(credentials.map(item => item.id), "Credential IDs");
    unique(reference.options.map(item => item.id), "Option IDs");
    for (const chapter of chapters) {
        assert.ok(chapter.intro && chapter.subtitle && chapter.sections.length, `${chapter.id}: purpose and instructions required`);
        unique(chapter.sections.map(item => item.id), `${chapter.id} section IDs`);
        routes.add(chapter.id);
        for (const section of chapter.sections) {
            routes.add(`${chapter.id}.${section.id}`);
            assert.ok(section.title && section.body, `${chapter.id}.${section.id}: instructions required`);
            assert.ok(section.platforms.every(id => platforms.has(id)), `${chapter.id}.${section.id}: unknown vehicle path`);
        }
    }
    const requireRoute = (route, origin) => assert.ok(routes.has(route), `${origin}: missing walkthrough ${route}`);
    for (const feature of features) {
        assert.ok(feature.caption && feature.meaning && feature.location, `${feature.name}: caption, purpose and settings path required`);
        requireRoute(feature.route, feature.name);
    }
    for (const option of reference.options) {
        assert.ok(option.label && option.description && option.path, `${option.id}: label, explanation and location required`);
        requireRoute(option.chapter, option.label);
    }
    const mounts = chapters.flatMap(chapter => chapter.sections.flatMap(section =>
        [...section.body.matchAll(/data-credentials="([^"]+)"/g)].flatMap(match => match[1].split(","))));
    unique(mounts, "Mounted credential walkthroughs");
    assert.deepEqual([...mounts].sort(), Array.from(credentials, item => item.id).sort(), "Every credential needs a walkthrough in the printable manual");

    const source = async path => readFile(resolve(root, "app/src/main", path), "utf8");
    const surfaces = new Map();
    for (const feature of features) {
        if (!surfaces.has(feature.surface)) surfaces.set(feature.surface, []);
        surfaces.get(feature.surface).push(feature.route);
    }
    const coveredSurface = (surface, origin) => {
        const mapped = surfaces.get(surface);
        assert.ok(mapped?.length, `${origin}: ${surface} is missing from the feature library`);
        return mapped;
    };
    const shell = await source("assets/web/shared/app-shell.js");
    const navigation = shell.slice(shell.indexOf("var NAV_ITEMS = [")).split("];")[0];
    const browser = [...navigation.matchAll(/href:\s*'([^']+)'/g)].map(match =>
        ({ page: match[1], routes: coveredSurface(match[1], "Browser navigation") }));
    assert.ok(browser.length > 20, "Browser navigation inventory is empty or incomplete");

    // Native fragments that wrap a page inherit that page's feature walkthroughs.
    const nativeRoutes = {
        DashboardFragment: ["personalise.dashboard"],
        RecordingsFragment: ["recording.library"],
        ProjectionFragment: ["personalise.projection"],
        IntegrationsFragment: ["integrations"],
        RoadSenseFragment: ["maps.roadsense"],
        WidgetsFragment: ["personalise.widgets"],
        NetworkFragment: ["tunnels.network-routing"],
        DiagnosticsFragment: ["diagnostics"],
        AdbConsoleFragment: ["diagnostics.shell-runner"],
        SettingsFragment: ["personalise.appearance", "recording", "sentry", "storage", "adb", "first-launch.security", "daemons", "maintenance.privacy"],
        DaemonsFragment: ["daemons"],
        SettingsAboutFragment: ["maintenance.updates"],
        SettingsAdbFragment: ["adb"],
        SettingsSecurityFragment: ["first-launch.security"],
        VideoPlayerFragment: ["recording.library"],
        FactoryDvrFragment: ["recording.factory-dvr"],
    };
    const attributes = text => Object.fromEntries([...text.matchAll(/([\w:]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
    const graph = await source("res/navigation/nav_graph.xml");
    const native = [...graph.matchAll(/<fragment\b([^>]*?)(?:\/>|>([\s\S]*?)<\/fragment>)/g)].map(match => {
        const attrs = attributes(match[1]);
        const name = attrs["android:name"].split(".").pop();
        const page = [...(match[2] || "").matchAll(/<argument\b([^>]*)\/>/g)]
            .map(item => attributes(item[1])).find(item => item["android:name"] === "page_path")?.["android:defaultValue"];
        const mapped = page ? coveredSurface(`${page.replace(/^\//, "")}.html`, name) : nativeRoutes[name];
        assert.ok(mapped?.length, `Native menu: ${name} needs a walkthrough`);
        mapped.forEach(route => requireRoute(route, name));
        return { destination: attrs["android:id"].split("/").pop(), name, page: page || null, routes: mapped };
    });
    assert.equal(native.length, [...graph.matchAll(/<fragment\b/g)].length, "Every native destination must be inspected");
    assert.ok(native.length > 25, "Native navigation inventory is empty or incomplete");

    const railRoutes = {
        ASSISTANT: "ai", LIVE: "cameras.live-view", RECORDINGS: "recording.library", PARKING: "everyday.parking",
        VEHICLE: "vehicle-control", PROXIMITY: "proximity", SEAT_POSITIONS: "vehicle-control.seats",
        PROJECTION: "personalise.projection", TRIPS: "everyday.trips", CHARGING: "everyday.charging",
        AUTOMATIONS: "automations", KEY_MAPPING: "automations.keys", INTEGRATIONS: "integrations",
        ROAD_SENSE: "maps", MAP: "maps.navigation", WIDGETS: "personalise.widgets",
        NETWORK: "tunnels.network-routing", DIAGNOSTICS: "diagnostics",
    };
    const railSource = await source("java/com/overdrive/app/ui/navigation/NavigationRailCatalog.kt");
    const rail = [...railSource.matchAll(/NavigationRailOption\(([A-Z_]+),/g)].map(match => {
        const route = railRoutes[match[1]];
        requireRoute(route, `Customizable navigation: ${match[1]}`);
        return { key: match[1], route };
    });
    assert.ok(rail.length > 10, "Customizable navigation inventory is empty or incomplete");
    const settingsRoutes = {
        APPEARANCE: "personalise.appearance", RECORDING: "recording", SURVEILLANCE: "sentry", STORAGE: "storage",
        OVERLAY: "personalise.overlays", ADB: "adb", SECURITY: "first-launch.security",
        DAEMONS: "daemons", PRIVACY: "maintenance.privacy",
    };
    const settingsSource = await source("java/com/overdrive/app/ui/fragment/SettingsFragment.kt");
    const enumSource = settingsSource.split("private enum class Section")[1]?.split("private var currentSection")[0] || "";
    const settings = [...enumSource.matchAll(/^\s*([A-Z_]+)\(R\.string\./gm)].map(match => {
        const route = settingsRoutes[match[1]];
        requireRoute(route, `Settings: ${match[1]}`);
        return { section: match[1], route };
    });
    assert.ok(settings.length >= 9, "Settings inventory is empty or incomplete");

    // Development fixtures do not represent a menu or user feature.
    const excluded = { "vehicle-control-3d-test.html": "Development fixture" };
    const extraPages = (await readdir(resolve(root, "app/src/main/assets/web/local")))
        .filter(file => file.endsWith(".html") && !excluded[file] && !browser.some(item => item.page === file))
        .map(page => ({ page, routes: coveredSurface(page, "Additional user page") }));
    const report = {
        sourceVersion: reference.sourceVersion,
        chapters: chapters.length, features: features.length, options: reference.count,
        daemons: daemons.length, credentialWalkthroughs: credentials.length,
        scope: "Every browser navigation entry, native navigation fragment, customizable rail entry and Settings section has a valid walkthrough. Additional user pages are checked against the feature library.",
        limits: [
            "Source coverage validates documentation links and menus; it does not verify a vehicle, firmware, live credential or provider account.",
            "The separate option coverage report describes the static-control indexing scope.",
        ],
        browser, native, rail, settings, extraPages, excluded,
        featureWalkthroughs: features.map(({ name, route, location, surface, platforms }) => ({ name, route, location, surface, platforms })),
    };
    await writeFile(resolve(root, "docs/setup-guide-feature-coverage.json"), `${JSON.stringify(report, null, 2)}\n`);
    console.log(`Validated guide coverage: ${browser.length} browser menu entries, ${native.length} native destinations, ${rail.length} rail choices and ${settings.length} Settings sections.`);
    return report;
}
