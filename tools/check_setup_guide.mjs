#!/usr/bin/env node
/* Browser checks for the public setup guide; no car or external account is used. */
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const origin = (process.argv[2] || "http://127.0.0.1:8765").replace(/\/$/, "");
const output = resolve(process.argv[3] || join(tmpdir(), "overdrive-guide-preview"));
const profile = await mkdtemp(join(tmpdir(), "overdrive-guide-browser-"));
await mkdir(output, { recursive: true });
const browser = await puppeteer.launch({ headless: true, userDataDir: profile });
const errors = [], missing = [];
let checks = 0;
function check(condition, description) {
    assert.ok(condition, description);
    checks++;
}
const page = await browser.newPage();
page.on("pageerror", error => errors.push(String(error)));
page.on("response", response => {
    if (response.url().startsWith(origin) && response.status() >= 400) missing.push(`${response.status()} ${response.url()}`);
});
page.setDefaultTimeout(10000);
await page.emulateMediaFeatures([{ name: "prefers-reduced-motion", value: "reduce" }]);
async function visit(route = "", query = "") {
    await page.goto(`${origin}/guide/${query}${route ? `#${route}` : ""}`, { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => window.OVERDRIVE_GUIDE?.chapters.length === document.querySelectorAll("[data-chapter]").length);
}
async function navigate(route) {
    await page.evaluate(route => { location.hash = route; }, route);
    const name = route.split(/[.?]/)[0];
    await page.waitForFunction(name => {
        const view = ["overview", "reference", "credentials"].includes(name) ? `${name}-view` : "chapter-view";
        return !document.getElementById(view).hidden && document.title.startsWith(
            window.OVERDRIVE_GUIDE.chapters.find(chapter => chapter.id === name)?.title ||
            { overview: "Set up your BYD", reference: "Option reference", credentials: "Tokens & keys" }[name]);
    }, {}, name);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
async function assertNoOverflow(label) {
    const result = await page.evaluate(() => ({
        delta: document.documentElement.scrollWidth - innerWidth,
        headings: [...document.querySelectorAll("main h1")].filter(element => element.getClientRects().length).length,
    }));
    check(result.delta <= 1, `${label}: page does not overflow horizontally (${result.delta}px)`);
    check(result.headings === 1, `${label}: one visible main heading`);
}
async function takePreview(filename) {
    await page.waitForFunction(() => !document.getElementById("guide-toast")?.classList.contains("is-visible"));
    await page.evaluate(() => {
        document.activeElement?.blur();
        window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.screenshot({ path: join(output, filename), fullPage: true });
}
async function assertDaemonConnections(label) {
    const connected = await page.evaluate(() => {
        const box = (element, pseudo) => {
            const bounds = element.getBoundingClientRect(), host = getComputedStyle(element);
            const style = getComputedStyle(element, pseudo);
            const width = parseFloat(style.width), height = parseFloat(style.height);
            const left = parseFloat(style.left);
            const top = style.top === "auto"
                ? bounds.height - parseFloat(host.borderTopWidth) - parseFloat(host.borderBottomWidth) - parseFloat(style.bottom) - height
                : parseFloat(style.top);
            return {
                x: bounds.x + parseFloat(host.borderLeftWidth) + left,
                y: bounds.y + parseFloat(host.borderTopWidth) + top, width, height,
            };
        };
        const touches = (a, b) => a.x + a.width >= b.x - 1 && b.x + b.width >= a.x - 1 &&
            a.y + a.height >= b.y - 1 && b.y + b.height >= a.y - 1;
        const node = document.querySelector(".dependency-node");
        const branch = document.querySelector(".dependency-branches");
        const children = [...branch.children];
        const root = box(node, "::after"), stems = children.map(child => box(child, "::before"));
        if (getComputedStyle(branch, "::before").display !== "none") {
            const bar = box(branch, "::before");
            return touches(root, bar) && stems.every(stem => touches(stem, bar));
        }
        const trunks = children.map(child => box(child, "::after"));
        return touches(root, trunks[0]) && trunks.every((trunk, i) => touches(trunk, stems[i])) &&
            trunks.slice(1).every((trunk, i) => touches(trunk, trunks[i]));
    });
    check(connected, `${label}: daemon connectors form a continuous path to every branch`);
}

try {
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    await page.goto(`${origin}/`, { waitUntil: "domcontentloaded" });
    check(await page.$$eval('a[href="guide/"]', links => links.length >= 3), "Landing navigation, guide card and footer link to the guide");
    await page.click(".nav-setup-guide");
    await page.waitForSelector("#start-guide");
    await page.waitForFunction(() => document.getElementById("option-count").textContent !== "—");
    const count = await page.evaluate(() => window.OVERDRIVE_REFERENCE.count);
    const chapterCount = await page.evaluate(() => window.OVERDRIVE_GUIDE.chapters.length);
    const credentialCount = await page.evaluate(() => window.OVERDRIVE_GUIDE.credentials.length);
    check(count > 500, "Source option index is populated");
    check(await page.$eval("#option-count", element => Number(element.textContent)) === count, "Displayed count follows source data");
    check(!await page.$(".car-blueprint, .hero-visual"), "Dummy car illustration is removed");
    check(await page.$eval("#feature-count", element => Number(element.textContent)) ===
        await page.evaluate(() => window.OVERDRIVE_GUIDE.features.length), "Feature count follows the explained feature library");
    check(await page.$eval(".guide-library-preview > div > p:not(.eyebrow)", element =>
        parseFloat(getComputedStyle(element).fontSize)) >= 19, "Overview option-library explanation uses the full reading size");
    await takePreview("overview-desktop.png");
    await assertNoOverflow("Desktop overview");

    for (const [vehicle, included, excluded] of [
        ["di3", "cameras.di3-encoder", ["cameras.di4-compatibility", "cameras.di5-order"]],
        ["di4", "cameras.di4-compatibility", ["cameras.di3-encoder", "cameras.di5-order"]],
        ["di5", "cameras.di5-order", ["cameras.camera-mapping", "cameras.di3-encoder", "cameras.di4-compatibility"]],
    ]) {
        await navigate("overview");
        await page.click(`input[name="vehicle"][value="${vehicle}"]`);
        await navigate("cameras");
        const visible = await page.$$eval(".article-section", elements => elements.filter(element => !element.hidden).map(element => element.id));
        check(visible.includes(included) && excluded.every(id => !visible.includes(id)), `${vehicle}: distinct configuration sections are shown`);
        check(await page.$eval("#chapter-platform-copy", (element, selected) => element.textContent.startsWith(selected.toUpperCase()), vehicle), `${vehicle}: selected-path explanation matches the actual instructions`);
        await takePreview(`cameras-${vehicle}-desktop.png`);
        await navigate("sentry.usb-keep-alive");
        const powerPaths = await page.$$eval('[id="sentry.usb-keep-alive"] .menu-path strong', elements =>
            elements.filter(element => element.getClientRects().length).map(element => element.textContent));
        const powerControl = vehicle === "di5" ? "Keep head unit awake while parked" : "Keep USB powered while parked";
        check(powerPaths.length === 1 && powerPaths[0].endsWith(powerControl), `${vehicle}: parked keep-alive shows its actual control path`);
    }
    await navigate("overview");
    await page.click('input[name="vehicle"][value="di5"]');
    await navigate("cameras");
    check(await page.$$eval('[data-platform]:not([hidden])', elements => elements.every(element => element.dataset.platform.includes("di5"))), "DI5 shows its camera path");
    await page.click('[data-check="0"]');
    await page.click('[data-check="1"]');
    check(await page.$eval("#checklist-count", element => element.textContent) === "2 / 3", "Checklist records completed steps");
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => document.getElementById("checklist-count").textContent === "2 / 3");
    check(await page.$eval("#change-vehicle", element => element.textContent.includes("DiLink 5")), "Vehicle and checklist survive reload");
    await navigate("overview");
    await page.click('input[name="vehicle"][value="di3"]');
    await navigate("cameras");
    check(await page.$eval("#checklist-count", element => element.textContent) === "0 / 3", "Vehicle paths have separate checklists");
    await page.click('[data-check="0"]');
    await navigate("overview");
    await page.click('input[name="vehicle"][value="di5"]');
    await navigate("cameras");
    await page.click("#reset-progress");
    check(await page.$eval("#reset-dialog", element => element.open), "Reset uses a confirmation dialog");
    await page.click("#cancel-reset");
    check(await page.$eval("#checklist-count", element => element.textContent) === "2 / 3", "Cancelling reset keeps progress");
    await page.click("#reset-progress");
    await page.click("#confirm-reset");
    check(await page.$eval("#checklist-count", element => element.textContent) === "0 / 3", "Reset clears selected vehicle progress");
    await navigate("overview");
    await page.click('input[name="vehicle"][value="di3"]');
    await navigate("cameras");
    check(await page.$eval("#checklist-count", element => element.textContent) === "1 / 3", "Reset leaves the other vehicle checklist intact");
    await navigate("overview");
    await page.click("[data-clear-vehicle]");

    await navigate("reference");
    check(await page.$$("article.option-card").then(elements => elements.length) === 24, "Option reference is paginated");
    await page.click("#more-options");
    check(await page.$$("article.option-card").then(elements => elements.length) === 48, "More options reveals another page");
    await page.type("#option-search", "pre-event");
    await page.waitForFunction(() => document.querySelectorAll("#option-results mark").length > 0);
    check(await page.$$("article.option-card").then(elements => elements.length) > 0, "Option search finds recording controls");
    await page.click("#clear-options");
    await page.select("#option-category", "MQTT & Home Assistant");
    await page.waitForFunction(() => [...document.querySelectorAll(".option-card-category")].every(element => element.textContent === "MQTT & Home Assistant"));
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => document.getElementById("option-category").value === "MQTT & Home Assistant");
    check(await page.$$("article.option-card").then(elements => elements.length) >= 15, "Category deep links preserve ampersands");
    await page.type("#option-search", "zz-no-such-option");
    await page.waitForFunction(() => !document.getElementById("option-empty").hidden);
    check(await page.$eval("#more-options", element => element.hidden), "Empty result hides pagination");
    await page.click("#reset-options");
    await page.type("#option-search", '<img src=x onerror="window.bad=true">');
    await page.waitForFunction(() => !document.getElementById("option-empty").hidden);
    check(!await page.evaluate(() => window.bad), "Search input is rendered as text");

    await page.click("#open-search");
    await page.type("#guide-search", "zrok");
    await page.waitForFunction(() => document.querySelector(".search-result")?.getAttribute("href") === "#credentials.zrok");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => document.getElementById("credential-library-zrok")?.open);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    check(await page.$eval("#search-dialog", element => !element.open), "Search opens the zrok walkthrough and closes its dialog");
    check(await page.$eval("#credential-library-zrok", element => element.textContent.includes("enable token")), "Zrok walkthrough explains the token type");
    await page.$eval("#credential-filters", element => element.scrollIntoView({ block: "center", behavior: "instant" }));
    await page.click('[data-credential-filter="AI"]');
    await page.waitForFunction(() => document.querySelector('[data-credential-filter="AI"]')?.getAttribute("aria-pressed") === "true");
    check(await page.$$("#credential-library .credential-card").then(elements => elements.length) === 4, "AI category shows four provider walkthroughs");
    await navigate("credentials.google-maps");
    await page.waitForFunction(() => document.getElementById("credential-library-google-maps")?.open);
    check(await page.$eval("#credential-library-google-maps", element => element.textContent.includes("billing")), "Maps walkthrough includes project/billing prerequisites");
    await takePreview("maps-credentials-desktop.png");

    await navigate("daemons");
    check(await page.$$(".daemon-card").then(elements => elements.length) === 9, "All nine daemons are explained");
    await page.click(".daemon-card summary");
    check(await page.$eval(".daemon-card", element => element.open && element.textContent.includes("If you stop it")), "Daemon detail explains effects and verification");
    await assertDaemonConnections("Desktop");
    await takePreview("daemons-desktop.png");
    await navigate("adb.connect");
    await page.waitForFunction(() => document.activeElement?.id === "heading-adb-connect");
    check(await page.$eval('[data-toc="adb.connect"]', element => Boolean(element)), "Chapter section deep links work");
    await page.evaluate(() => Object.defineProperty(navigator, "clipboard", {
        configurable: true, value: { writeText: async text => { window.guideCopiedText = text; } },
    }));
    await page.click('[id="adb.connect"] .copy-code');
    await page.waitForFunction(() => window.guideCopiedText?.includes("adb connect <CAR_IP>:5555"));
    check(await page.evaluate(() => window.guideCopiedText.endsWith("adb devices")), "Copy command preserves placeholders and line breaks");
    await navigate("first-launch");
    check(await page.$$(".workflow-diagram").then(elements => elements.length) === 1, "First launch uses an explanatory workflow diagram");
    check(await page.$$eval('#article-body img', images => images.length === 0), "Guide does not use outdated app screenshots");
    check(await page.$$eval(".article-section", sections => sections[0].id === "first-launch.vehicle" &&
        sections[1].id === "first-launch.traffic-manager"), "BYD Traffic Monitor follows vehicle setup immediately");
    check(await page.$eval('[id="first-launch.battery-capacity"]', element => /type.*Capacity/i.test(element.textContent) ||
        /type.*kWh|enter.*manually|edit.*Capacity/i.test(element.textContent)), "Vehicle setup explains manual capacity entry");
    check(await page.$eval('[id="first-launch.driver-side"]', element => /Notifications → Vehicle layout/.test(element.textContent) &&
        /Left-hand drive/.test(element.textContent) && /Right-hand drive/.test(element.textContent) &&
        /saves immediately/.test(element.textContent)), "Vehicle setup explains LHD/RHD, its Notifications path and immediate save");
    check(await page.$eval('[id="first-launch.tyre-pressure"]', element => /Notifications → Tyre pressure limits/.test(element.textContent) &&
        /kPa, PSI or bar/.test(element.textContent) && /always remain in kPa/.test(element.textContent) &&
        /Tap Save/.test(element.textContent)), "Tyre setup distinguishes display units from saved pressure limits");
    await takePreview("vehicle-setup-desktop.png");
    const setupSequence = await page.evaluate(() => window.OVERDRIVE_GUIDE.chapters.slice(0, 5).map(chapter => chapter.id));
    check(setupSequence.join(",") === "before-start,adb,install,first-launch,cameras", "ADB precedes APK installation in guide navigation");
    await navigate("features");
    const featureCoverage = await page.evaluate(() => {
        const { chapters, features } = window.OVERDRIVE_GUIDE;
        return features.filter(feature => {
            const [id, section] = feature.route.split(".");
            const chapter = chapters.find(chapter => chapter.id === id);
            return !feature.meaning || !feature.caption || !feature.location || !chapter ||
                (section && !chapter.sections.some(item => item.id === section));
        }).map(feature => feature.name);
    });
    check(featureCoverage.length === 0, `All features have purpose, caption, path and working walkthrough: ${featureCoverage.join(", ")}`);
    for (const route of ["sentry.usb-keep-alive", "sentry.mobile-data-keep-alive", "everyday.parking-signage"]) {
        await navigate("features");
        await page.click(`.feature-directory a[data-route="${route}"]`);
        await page.waitForFunction(route => location.hash === `#${route}` &&
            document.getElementById(route)?.getClientRects().length > 0, {}, route);
        check(await page.$eval(`[id="${route}"]`, element => element.querySelector(".menu-path")?.getClientRects().length > 0),
            `${route}: feature card opens the detailed setup and visible settings path`);
    }
    for (const route of ["briefing", "voice", "proximity", "communicate", "vehicle-control", "diagnostics", "byd-cloud", "sentry"]) {
        await navigate(route);
        check(await page.$eval(".feature-introduction", element => element.textContent.trim().length > 70), `${route}: begins with a plain feature explanation`);
        check(await page.$$(".menu-path").then(elements => elements.length) > 0, `${route}: gives settings paths`);
    }
    await navigate("vehicle-control");
    check(await page.$eval('[id="vehicle-control.charging-controls"]', element => /Charge limit on/.test(element.textContent) &&
        /AC charging current/.test(element.textContent)), "Charging controls explain enabling the charge limit and current choices");
    check(await page.$eval('[id="vehicle-control.control-safety"]', element => /Driving safety guards/.test(element.textContent) &&
        /cannot be turned off/.test(element.textContent) && element.querySelectorAll("tbody tr").length === 9),
        "Vehicle controls explain all nine shared driving guards and permanent parked requirements");
    await navigate("maps.navigation");
    check(await page.$eval('[id="maps.navigation"]', element => /main menu → Map/.test(element.textContent) &&
        /Save place/.test(element.textContent) && /End navigation/.test(element.textContent)), "On-car Map includes search, saved places and navigation");
    await navigate("byd-cloud");
    check(await page.$eval('[id="byd-cloud.save-account"]', element => /Country/.test(element.textContent) &&
        /VIN/.test(element.textContent) && /Control Pin/.test(element.textContent)), "BYD Cloud setup covers country, credentials and vehicle verification");
    check(await page.$eval('[id="byd-cloud.test-account"]', element => /Flash Lights/.test(element.textContent)), "BYD connection test explains its actual vehicle action");
    await takePreview("byd-cloud-desktop.png");
    await navigate("sentry");
    check(await page.$eval('[id="sentry.screen-deterrent"]', element => /without a BYD Cloud/.test(element.textContent) &&
        /MP4/.test(element.textContent) && /3–30/.test(element.textContent)), "Screen deterrent covers independent setup, duration and custom media");
    check(await page.$eval('[id="sentry.cloud-deterrent"]', element => /Fire on/.test(element.textContent) &&
        /Deterrent zone/.test(element.textContent) && /Only at set hours/.test(element.textContent)), "Cloud deterrent covers object trigger, zone and hours");
    check(await page.$eval('[id="sentry.impact"]', element => /does not create a clip/.test(element.textContent)), "Impact notification is distinguished from video capture");
    await takePreview("parked-surveillance-desktop.png");
    await navigate("adb.qr-code");
    check(await page.$$eval(".qr-route-summary > div", elements => elements.length === 2), "BYD QR-code gate offers two setup paths");
    check(await page.$eval(".qr-route-summary", element => /Paid service/.test(element.textContent) && /not affiliated with OverDrive/.test(element.textContent)), "Paid assistance is labelled independent of OverDrive");
    check(await page.$eval(".article-body", element => parseFloat(getComputedStyle(element).fontSize)) >= 19, "Guide body text is at least 19px");
    check(await page.$eval(".instruction-list p", element => parseFloat(getComputedStyle(element).fontSize)) >= 18, "Instruction text is at least 18px");
    await page.waitForFunction(() => !document.getElementById("guide-toast")?.classList.contains("is-visible"));
    await page.$eval('[id="adb.qr-code"]', element => element.querySelectorAll("details").forEach(detail => { detail.open = true; }));
    await page.evaluate(() => document.activeElement?.blur());
    await (await page.$('[id="adb.qr-code"]')).screenshot({ path: join(output, "adb-qr-options-desktop.png") });
    console.log("Desktop navigation, progress, search, credentials and QR-code instructions passed.");

    for (const width of [1280, 1024, 860, 768, 390, 360, 320]) {
        await page.setViewport({ width, height: 850, deviceScaleFactor: 1 });
        for (const route of ["overview", "adb", "first-launch", "daemons", "features", "sentry", "everyday", "byd-cloud", "vehicle-control", "maps", "credentials.google-maps", "reference?q=API"]) {
            await navigate(route);
            await assertNoOverflow(`${width}px ${route}`);
        }
    }
    await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
    await navigate("overview");
    await takePreview("overview-mobile.png");
    await page.click("#mobile-menu");
    check(await page.$eval("#guide-sidebar", element => element.classList.contains("is-open")), "Mobile chapters open");
    check(await page.$eval("#main-content", element => element.inert), "Mobile drawer isolates keyboard focus");
    await page.keyboard.press("Escape");
    check(await page.$eval("#mobile-menu", element => document.activeElement === element), "Closing the mobile drawer returns focus");
    await page.click("#mobile-menu");
    await page.click("#sidebar-vehicle");
    await page.waitForFunction(() => location.hash === "#overview");
    check(await page.$eval("#guide-sidebar", element => !element.classList.contains("is-open")), "Vehicle shortcut closes drawer and opens selector");
    await page.click("#mobile-menu");
    await page.click('[data-chapter="daemons"]');
    await page.waitForFunction(() => document.title.startsWith("Meet the background"));
    check(await page.$eval("#mobile-menu", element => element.getAttribute("aria-expanded") === "false"), "Chapter navigation closes mobile drawer");
    await assertDaemonConnections("Mobile");
    await takePreview("daemons-mobile.png");
    await navigate("sentry.usb-keep-alive");
    await page.evaluate(() => document.activeElement?.blur());
    await (await page.$('[id="sentry.usb-keep-alive"]')).screenshot({ path: join(output, "parked-usb-keep-alive-mobile.png") });
    await navigate("sentry.mobile-data-keep-alive");
    await (await page.$('[id="sentry.mobile-data-keep-alive"]')).screenshot({ path: join(output, "parked-mobile-data-mobile.png") });
    await navigate("everyday.parking-signage");
    await (await page.$('[id="everyday.parking-signage"]')).screenshot({ path: join(output, "parking-signage-mobile.png") });
    await page.click("#open-search");
    await page.type("#guide-search", "no-such-page-zz");
    await page.waitForSelector(".search-empty");
    await page.keyboard.press("Escape");
    check(await page.$eval("#search-dialog", element => !element.open), "Mobile search has empty and Escape states");
    await visit("constructor", "?vehicle=__proto__");
    check(await page.$eval("#overview-view", element => !element.hidden), "Unknown routes and vehicle paths safely use overview");
    await navigate("tunnels");
    await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 1 });
    await page.pdf({ path: join(output, "tunnel-chapter.pdf"), format: "A4", printBackground: true });
    check((await readFile(join(output, "tunnel-chapter.pdf"))).length > 20000, "Chapter can be saved as PDF with expanded walkthroughs");

    const ids = await page.evaluate(() => window.OVERDRIVE_GUIDE.chapters.map(chapter => chapter.id));
    for (const id of ids) {
        await navigate(id);
        const unresolved = await page.evaluate(() => {
            const elements = [...document.querySelectorAll("main [aria-labelledby], main [aria-describedby]")];
            return elements.flatMap(element => ["aria-labelledby", "aria-describedby"].flatMap(attribute =>
                (element.getAttribute(attribute) || "").split(/\s+/).filter(Boolean).filter(id => !document.getElementById(id))));
        });
        check(unresolved.length === 0, `${id}: accessible labels resolve`);
    }
    await page.setJavaScriptEnabled(false);
    await page.goto(`${origin}/guide/manual.html`, { waitUntil: "domcontentloaded" });
    check(await page.$$(".manual-chapter").then(elements => elements.length) === chapterCount + 1, "Static manual includes all chapters and reference without JavaScript");
    check(await page.$$(".credential-card").then(elements => elements.length) === credentialCount, "Static manual includes every credential walkthrough");
    check(await page.$$(".option-card").then(elements => elements.length) === count, "Static manual includes the complete generated index");
    check(await page.$$eval('a[href^="#"]', links => links.every(link => document.getElementById(link.getAttribute("href").slice(1)))), "Every manual internal link resolves");
    check(await page.$$eval("details", details => details.every(element => element.open)), "Static manual content is expanded");
    const duplicateIds = await page.$$eval("[id]", elements => {
        const ids = elements.map(element => element.id);
        return ids.filter((id, index) => ids.indexOf(id) !== index);
    });
    check(duplicateIds.length === 0, `Static manual IDs are unique: ${duplicateIds.join(", ")}`);
    check(errors.length === 0, `No uncaught browser errors: ${errors.join("; ")}`);
    check(missing.length === 0, `No missing local resources: ${missing.join("; ")}`);
    console.log(`Passed ${checks} checks. Browser previews and a sample PDF: ${output}`);
} catch (error) {
    console.error(`Guide check failed after ${checks} checks: ${error.message}`);
    throw error;
} finally {
    const closeTimer = setTimeout(() => browser.process()?.kill("SIGTERM"), 5000);
    await browser.close();
    clearTimeout(closeTimer);
    await rm(profile, { recursive: true, force: true });
}
