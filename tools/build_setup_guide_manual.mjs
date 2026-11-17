#!/usr/bin/env node
/* Build a complete, readable fallback with no JavaScript dependency. */
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, dirname } from "node:path";
import vm from "node:vm";
import { createHash } from "node:crypto";
import { validateSetupGuide } from "./validate_setup_guide.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const guideDirectory = resolve(root, "vps/web/landing/guide");
const assetNames = ["content.js", "reference.js", "components.js", "guide.js", "guide.css"];
const reads = await Promise.allSettled(assetNames.map(file => readFile(resolve(guideDirectory, file), "utf8")));
const assets = new Map(reads.map((result, index) => {
    if (result.status === "rejected") throw result.reason;
    return [assetNames[index], result.value];
}));
const assetUrls = new Map(assetNames.map(file => [
    file, `${file}?v=${createHash("sha256").update(assets.get(file)).digest("hex").slice(0, 12)}`,
]));
const context = vm.createContext({ window: {} });
for (const file of ["content.js", "reference.js", "components.js"]) {
    vm.runInContext(assets.get(file), context, { filename: file });
}
const { chapters, credentials } = context.window.OVERDRIVE_GUIDE;
const reference = context.window.OVERDRIVE_REFERENCE;
await validateSetupGuide(root, context.window.OVERDRIVE_GUIDE, reference);
const { escape, icon, credentialCard, optionCard } = context.window.OVERDRIVE_COMPONENTS;
const credentialMap = new Map(credentials.map(credential => [credential.id, credential]));
const credentialAnchors = new Map();
let body = chapters.map((chapter, index) => {
    const sections = chapter.sections.map(section => {
        let html = section.body.replace(/<div class="credential-mount" data-credentials="([^"]+)"><\/div>/g, (_, ids) =>
            `<div class="credential-mount">${ids.split(",").map(id => {
                credentialAnchors.set(id, `credential-${chapter.id}-${id}`);
                return credentialCard(credentialMap.get(id), chapter.id, true);
            }).join("")}</div>`);
        html = html.replace(/<h3\b/g, "<h4").replace(/<\/h3>/g, "</h4>");
        return `<section class="article-section" id="${chapter.id}.${section.id}"><h3>${escape(section.title)}</h3>
            ${section.platforms.length ? `<p class="section-platform-label">${section.platforms.map(id => id.toUpperCase()).join(" / ")} setup</p>` : ""}${html}</section>`;
    }).join("\n");
    return `<article class="manual-chapter" id="${chapter.id}" aria-labelledby="title-${chapter.id}">
        <header class="chapter-header"><p class="eyebrow">CHAPTER ${String(index + 1).padStart(2, "0")} · ${escape(chapter.group)}</p>
        <h2 id="title-${chapter.id}">${escape(chapter.title)}</h2><p>${escape(chapter.subtitle)}</p></header>
        <div class="article-body">${chapter.intro ? `<div class="feature-introduction"><p class="quiet-label">WHAT IT DOES</p><p>${escape(chapter.intro)}</p></div>` : ""}${sections}</div>
        <section class="chapter-checklist"><h3>Check your setup</h3><ul>${chapter.checklist.map(text => `<li>${escape(text)}</li>`).join("")}</ul></section>
    </article>`;
}).join("\n");
body = body
    .replace(/<details\b(?![^>]*\bopen\b)([^>]*)>/g, "<details$1 open>")
    .replace(/<button[^>]*class="zoom-image"[^>]*>(.*?)<\/button>/gs, "$1")
    .replace(/<button[^>]*class="copy-code"[^>]*>.*?<\/button>/gs, "")
    .replace(/<span([^>]*\bdata-icon="([^"]+)"[^>]*)><\/span>/g, (_, attributes, name) => `<span${attributes}>${icon(name)}</span>`);
const optionReference = reference.categories.map(category => `<section class="manual-option-category">
    <h3>${escape(category)}</h3><div class="option-results">${reference.options.filter(option => option.category === category)
        .map(option => optionCard(option, "", "h4")).join("")}</div></section>`).join("\n");
const html = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="theme-color" content="#0b1113">
    <title>Complete setup manual · OverDrive</title>
    <meta name="description" content="The complete printable OverDrive guide: DI3, DI4 and DI5 setup, nine daemons, tunnel and API credentials, and the app option reference.">
    <link rel="canonical" href="https://www.overdrive.qd.je/guide/manual.html">
    <link rel="icon" type="image/webp" href="../app-icon.webp"><link rel="stylesheet" href="${assetUrls.get("guide.css")}">
</head>
<body>
<a class="skip-link" href="#manual-contents">Skip to contents</a>
<main class="manual-page">
    <a class="manual-back-link text-link" href="./">${icon("arrow-left")}Open the interactive guide</a>
    <header id="overview"><p class="eyebrow">THE OVERDRIVE FIELD GUIDE</p><h1>Set up your BYD.<br>Make it yours.</h1>
    <p>The complete manual for DiLink 3, 4 and 5. All vehicle paths appear here; follow your detected hardware, installed build and firmware.</p>
    <p>${chapters.length} chapters · ${reference.count} indexed controls · 9 daemons · ${credentials.length} credential walkthroughs · source v${escape(reference.sourceVersion)}</p>
    <p>Use your browser’s Print action to print or save a PDF. This manual works without JavaScript.</p></header>
    <nav class="manual-toc" id="manual-contents" aria-label="Manual contents">
        ${chapters.map((chapter, index) => `<a href="#${chapter.id}"><span>${String(index + 1).padStart(2, "0")}</span>${escape(chapter.title)}</a>`).join("")}
        <a href="#credentials"><span>KEYS</span>Token &amp; key index</a><a href="#reference"><span>INDEX</span>Complete option reference</a>
    </nav>
    <section id="credentials" class="manual-credential-index"><h2>Find a token or key</h2>
        <p>Each link opens the full walkthrough inside its setup chapter. Create credentials on the provider’s official page and enter them in OverDrive on the car.</p>
        <nav class="manual-toc" aria-label="Credential walkthroughs">${credentials.map(credential =>
            `<a href="#${credentialAnchors.get(credential.id)}">${escape(credential.name)}<span>${escape(credential.kind)}</span></a>`).join("")}</nav>
    </section>
    ${body}
    <section class="manual-chapter" id="reference"><header class="chapter-header"><p class="eyebrow">THE OPTION REFERENCE</p><h2>Controls &amp; choices</h2>
        <p>This index follows the app’s static forms, saved button choices and selected native settings/setup dialogs. Some controls appear only after a parent feature is enabled. App-loaded saved values take precedence.</p>
        <p>Controls can depend on hardware, firmware and build. This index does not certify vehicle compatibility. Advanced dynamic parameters are explained in the relevant setup chapters.</p></header>
        ${optionReference}
    </section>
    <footer class="guide-footer"><a href="../" class="footer-brand">OverDrive</a><span>Source v${escape(reference.sourceVersion)}</span><a href="./">Back to the interactive guide${icon("arrow")}</a></footer>
</main>
</body>
</html>
`;
const indexPath = resolve(guideDirectory, "index.html");
const index = await readFile(indexPath, "utf8");
const revisedIndex = index.replace(/\b(href|src)="(guide\.css|content\.js|reference\.js|components\.js|guide\.js)(?:\?[^"]*)?"/g,
    (_, attribute, file) => `${attribute}="${assetUrls.get(file)}"`);
if (revisedIndex !== index) await writeFile(indexPath, revisedIndex, "utf8");
await writeFile(resolve(guideDirectory, "manual.html"), html.replace(/[ \t]+$/gm, ""), "utf8");
console.log(`Built complete manual: ${chapters.length} chapters, ${credentialAnchors.size} credential walkthroughs, ${reference.count} controls.`);
