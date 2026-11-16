/**
 * Combined storage-limit advisory — shared renderer.
 *
 * Recording, surveillance, trips and proximity each carry an independent MB
 * limit whose slider tops out at the FULL volume, and each picks its volume
 * independently. So Σ(limits landing on one volume) can exceed what that volume
 * holds, and until this existed nothing said so: each settings page only ever
 * saw its own limit. Runtime stays safe (each category reaps to its own cap, and
 * the physical-free emergency reaper cross-evicts when the volume runs dry), but
 * the configured retention quietly stops being honoured.
 *
 * The server computes the budget once (StorageManager.getStorageBudgetJson) and
 * every page renders it through here, so the three pages can't drift apart or
 * disagree about a category one of them doesn't know about.
 *
 * ADVISORY ONLY. Never blocks a save, never clamps a slider — overcommit is a
 * supported configuration (the per-category /N divisor was removed deliberately);
 * the defect was that it happened silently.
 *
 * Usage:
 *   BYD.storageBudget.render('recBudgetBanner', data.storageBudget, 'recordings');
 */
window.BYD = window.BYD || {};

BYD.storageBudget = {
    /**
     * Format an MB figure for display, promoting to GB past 1000 so a warning
     * about a big card doesn't read "121300 MB".
     *
     * UNITS: the server's *Mb figures are mebibytes (limitMb * 1024 * 1024, and
     * volumeCeilingMb divides bytes by 1024²), but the existing slider labels on all
     * three settings pages render them as `mb / 1000 + ' GB'`. This deliberately
     * matches that convention: both numbers in the warning come from the same MiB
     * unit, so the comparison the sentence makes is exact, and the "total" figure
     * reads identically to the slider label the user just dragged. It will differ
     * slightly from the byte-based `-- total` line (StorageManager.formatSize uses
     * decimal GB), which is a pre-existing inconsistency in the unit labels, not a
     * wrong comparison here.
     */
    fmt: function (mb) {
        return this.fmtPair(mb, null);
    },

    /**
     * Format `mb` for display at a precision shared with `other` (pass null when there
     * is no counterpart).
     *
     * TWO requirements, and the second is easy to lose sight of:
     *
     * 1. FAITHFUL. Each rendered number must not misstate its own value. Rounding to
     *    whole GB reported a 1 MB overshoot as "11 GB vs 10 GB" — a 1000x
     *    exaggeration that tells the user to free a gigabyte they don't need to, and
     *    contradicts the slider label right above it (sliders print `mb/1000 + ' GB'`,
     *    so 10500 reads "10.5 GB" there and must not read "11 GB" here).
     * 2. DISTINGUISHABLE. The warning fires on a strict `>` by as little as 1 MB and
     *    the sliders step by 100, so the two numbers are often close. Independent
     *    rounding printed "add up to 45 GB … only holds 45 GB" — self-refuting.
     *
     * Both are satisfied by picking, for THIS value, the fewest decimals (0..2) that
     * render it exactly AND separate it from the counterpart at the same precision.
     *
     * The two sides may therefore end up with different decimal counts ("12 GB … 9.6 GB",
     * "5 GB … ~4.4 GB") — fidelity is worth more than a matched pair, and the `~` marker
     * explains any asymmetry. An earlier version required exactness of BOTH values to
     * force a shared precision; because ceilings are derived and essentially never round,
     * that pushed ~90% of realistic pairs into the raw MB fallback ("58300 MB" beside a
     * slider reading "58.3 GB") — the very thing this function exists to prevent.
     *
     * Exactness is therefore required only of the value being RENDERED, never of the
     * counterpart. A value that isn't exactly representable at <=2 decimals is rendered
     * approximately by its own call (`~42.3 GB`, see {@link _gbApprox}), so no number
     * ever reads as exact when it isn't. Bare MB is the last resort, reached only when
     * the pair is close enough that no GB precision separates them.
     */
    fmtPair: function (mb, other) {
        var n = Number(mb) || 0;
        if (n < 1000) return this._mb(n);
        var o = other == null ? null : (Number(other) || 0);
        var pairInGb = (o != null && o >= 1000);

        for (var d = 0; d <= 2; d++) {
            var text = (n / 1000).toFixed(d);
            // Faithful for THIS value at this precision?
            if (!this._exact(n, d)) continue;
            // Distinguishable from the counterpart rendered at the same precision?
            if (!pairInGb || text !== (o / 1000).toFixed(d)) return this._gb(text);
        }
        // n itself isn't exactly representable at <=2 decimals, or the pair collides at
        // every precision that is. Try an APPROXIMATE GB render: still far more legible
        // than six digits of MB, and honest because it's marked as approximate.
        for (var a = 1; a <= 2; a++) {
            var atext = (n / 1000).toFixed(a);
            if (!pairInGb || atext !== (o / 1000).toFixed(a)) {
                return this._gbApprox(atext);
            }
        }
        // Nothing separates them in GB — MB is exact by construction (the server's
        // figures are whole MB) and unambiguous.
        return this._mb(n);
    },

    /** True when `mb` renders exactly as GB with `d` decimals (no rounding error).
     *  1 GB = 1000 MB here, matching the slider labels, so d decimals resolves
     *  10^(3-d) MB: d=0 needs a whole multiple of 1000, d=1 of 100, d=2 of 10. */
    _exact: function (mb, d) {
        var unit = d === 0 ? 1000 : (d === 1 ? 100 : 10);
        return (mb % unit) === 0;
    },

    /** GB label with a safe fallback: t() yields the raw key when the catalog
     *  is loaded but the key is missing, so test the sentinel, don't rely on `||`. */
    _gb: function (text) {
        var s = BYD.i18n.t('storage.unit_gb', { n: text });
        return (s == null || s === 'storage.unit_gb') ? (text + ' GB') : s;
    },

    /** MB label, same sentinel handling as {@link _gb}. */
    _mb: function (n) {
        var s = BYD.i18n.t('storage.unit_mb', { n: n });
        return (s == null || s === 'storage.unit_mb') ? (n + ' MB') : s;
    },

    /** Approximate GB label ("~58.3 GB") for a value that isn't exactly representable
     *  at this precision. Marked so a rounded figure never reads as exact. */
    _gbApprox: function (text) {
        var s = BYD.i18n.t('storage.unit_gb_approx', { n: text });
        return (s == null || s === 'storage.unit_gb_approx') ? ('~' + text + ' GB') : s;
    },

    /** Is this entry's volume SIZE known (the only fact overCapacity needs)? Falls back
     *  to `measurable` for a payload predating the split, which is what that older
     *  daemon's own overCapacity was gated on. */
    _capacityKnown: function (b) {
        if (!b) return false;
        return b.capacityKnown != null ? !!b.capacityKnown : !!b.measurable;
    },

    /**
     * Localized volume name for a budget entry.
     *
     * The server's `label` is deliberately English ("SD card" / "USB" / "Internal") —
     * volumeLabel() exists for log lines. Every other token in the banner is
     * translated, and this is the one that tells the user WHICH volume to fix, so
     * resolve it through the catalog off the machine-readable `storageType`. Reuses
     * the trip.settings.storage_* keys the three storage pickers on these same pages
     * already display, so no new translation work is needed. Falls back to the
     * server's English label if the key is missing.
     */
    volumeName: function (b) {
        var keys = {
            INTERNAL: 'trip.settings.storage_internal',
            SD_CARD:  'trip.settings.storage_sd',
            USB:      'trip.settings.storage_usb'
        };
        var key = keys[b && b.storageType];
        if (key) {
            var s = BYD.i18n.t(key);
            if (s != null && s !== key) return s;
        }
        return (b && b.label) || '';
    },

    /**
     * Translate the contributing-category keys into a localized, comma-joined
     * list ("recordings, sentry and trips"). `self` is the calling page's own
     * category — it's dropped from the list because the sentence already names
     * the page's own limit, and repeating it reads as a duplicate.
     *
     * An unknown category (server gained a 5th before this page's catalog did — live
     * state, since the catalogs are cached for 24h while the daemon can ship at any
     * time) is named GENERICALLY, not printed raw and not silently dropped:
     *   - raw is wrong because core.js t() returns the KEY on a loaded-but-missing
     *     lookup, so `|| c` was dead and the sentence read "…and storage.category_foo".
     *   - dropping is worse than it looks: an empty peer list is also what selects the
     *     `_solo` copy in render(), which attributes the ENTIRE volume sum to the
     *     page's own limit. With a 15 GB recordings limit next to an unnamed 30 GB
     *     peer, the banner would claim "your storage limit of 45.1 GB" and tell the
     *     user to lower it — false, and unactionable since zeroing it can't help.
     * A generic name keeps the plural copy (the accurate one) and stays honest about
     * there being another contributor.
     */
    others: function (categories, self, categoryLimitsMb) {
        var out = [];
        var unnamed = 0;
        for (var i = 0; i < (categories || []).length; i++) {
            var c = categories[i];
            if (c === self) continue;
            // A zero cap is the explicit No Limit sentinel, not a configured
            // retention budget and therefore not a contributor to this warning.
            if (categoryLimitsMb && Number(categoryLimitsMb[i]) === 0) continue;
            var key = 'storage.category_' + c;
            var name = BYD.i18n.t(key);
            if (name == null || name === key) { unnamed++; continue; }
            out.push(name);
        }
        if (unnamed > 0) {
            var gk = 'storage.category_other';
            var generic = BYD.i18n.t(gk, { count: unnamed });
            out.push((generic == null || generic === gk) ? 'other features' : generic);
        }
        if (!out.length) return '';
        if (out.length === 1) return out[0];
        var last = out.pop();
        var and = BYD.i18n.t('storage.list_and');
        if (and == null || and === 'storage.list_and') and = 'and';
        return out.join(', ') + ' ' + and + ' ' + last;
    },

    /**
     * Pick the budget entry the calling page should warn about: the volume that
     * page's own category is configured to write to. A page must not render a
     * peer volume's overcommit — the user would see a warning on the recording
     * page about a card recording doesn't even use.
     */
    forCategory: function (budget, category) {
        if (!budget || !budget.length) return null;
        for (var i = 0; i < budget.length; i++) {
            var b = budget[i];
            // The payload includes untargeted volumes (empty categories) so a pending
            // switch can be evaluated against them; those must never match here.
            if (b && b.categories && b.categories.indexOf(category) !== -1) {
                var index = b.categories.indexOf(category);
                if (b.categoryLimitsMb && Number(b.categoryLimitsMb[index]) === 0) return null;
                return b;
            }
        }
        return null;
    },

    /**
     * Entry for a specific volume, or null. Used when the user has switched the
     * storage-type picker but not yet saved: the server's grouping still has the
     * category on the OLD volume, and warning against that volume would be wrong
     * in both directions (stale capacity, stale peer list).
     */
    forVolume: function (budget, storageType) {
        if (!budget || !budget.length || !storageType) return null;
        for (var i = 0; i < budget.length; i++) {
            if (budget[i] && budget[i].storageType === storageType) return budget[i];
        }
        return null;
    },

    /**
     * Recompute a budget entry with `pendingMb` substituted for `category`'s stored
     * limit, so the banner tracks the slider live instead of only after Apply — the
     * moment you drag past the card's capacity is exactly when the warning is useful.
     *
     * Returns a shallow copy; the server payload is never mutated (it's re-read by
     * the 10s poll and must stay the authoritative baseline). Returns the original
     * entry unchanged when the category isn't found or the payload lacks the
     * per-category limits, so an older daemon degrades to save-time-only warnings.
     */
    withPending: function (b, category, pendingMb) {
        if (!b || !b.categories || !b.categoryLimitsMb) return b;
        var i = b.categories.indexOf(category);
        if (i === -1 || b.categoryLimitsMb[i] == null) return b;
        var delta = (Number(pendingMb) || 0) - Number(b.categoryLimitsMb[i]);
        if (!delta) return b;

        var c = {};
        for (var k in b) if (b.hasOwnProperty(k)) c[k] = b[k];
        c.configuredMb = b.configuredMb + delta;
        // The measured sum moves ONLY if this category is in the measured subset. It is
        // NOT enough that its limit is present: measurability depends on whether the
        // daemon can size the category cheaply, which is why the server publishes
        // measuredCategories rather than letting the client infer it. Moving the sum for
        // a category outside the subset both invented warnings that vanished on Apply and
        // silenced real ones — and the trips page pushes a pending value on every slider
        // frame, so it hit that path constantly.
        c.measuredConfiguredMb = this._clampMeasured(
            this._measuredBase(b) + (this._isMeasured(b, category) ? delta : 0),
            c.configuredMb);
        // Re-evaluate both thresholds against the new sums. reachableMb/usableMb are
        // properties of the volume, not of the limits, so they carry over untouched.
        // Mirror the server's two-gate split: overCapacity needs only the volume SIZE
        // (capacityKnown, which also carries its fabricated-ceiling guard), overReachable
        // needs a trustworthy free reading (measurable). Recomputing both off `measurable`
        // alone resurrected the fabricated-ceiling claim the server deliberately withholds.
        c.overCapacity = this._capacityKnown(c) && c.configuredMb > c.usableMb;
        c.overReachable = c.measurable && c.measuredConfiguredMb > c.reachableMb;
        return c;
    },

    /**
     * The entry's measured-configured sum, falling back to the full sum for a payload
     * from a daemon that predates the field. Without the fallback an older daemon would
     * report `undefined`, NaN-poison the comparison, and silently never warn.
     */
    _measuredBase: function (b) {
        if (b.measuredConfiguredMb != null) return Number(b.measuredConfiguredMb);
        // No sum published. Falling back to the FULL configuredMb is only coherent if
        // _isMeasured also treats every category as measured — which it does only when
        // measuredCategories is likewise absent. If membership IS published without a
        // sum, deriving the sum from that membership keeps the two helpers consistent;
        // otherwise a refused delta would leave the stale full sum in place and a user
        // dragging their own limit to zero could never clear the warning.
        if (b.measuredCategories && b.categories && b.categoryLimitsMb) {
            var sum = 0;
            for (var i = 0; i < b.categories.length; i++) {
                if (b.measuredCategories.indexOf(b.categories[i]) === -1) continue;
                var lim = b.categoryLimitsMb[i];
                if (lim != null) sum += Number(lim);
            }
            return sum;
        }
        return Number(b.configuredMb) || 0;
    },

    /**
     * Keep a recomputed measured sum inside [0, configuredMb]. It is a SUBSET sum, so
     * exceeding the total is as incoherent as going negative — and both ends are
     * reachable when the payload carries only one of measuredConfiguredMb /
     * measuredCategories (version skew), because _measuredBase then falls back to the
     * ORIGINAL configuredMb while the new total may have shrunk. Clamping here makes the
     * two recompute paths total instead of dependent on payload shape; out-of-range
     * values would otherwise read as a false alarm (inflated demand) or a false
     * all-clear, and could be printed into the banner.
     */
    _clampMeasured: function (measured, configured) {
        var m = Number(measured) || 0;
        var cap = Number(configured);
        if (isNaN(cap) || cap < 0) cap = 0;
        return Math.max(0, Math.min(m, cap));
    },

    /**
     * Is `category`'s byte usage known to the daemon, i.e. is it inside this entry's
     * measuredConfiguredMb / ourUsedMb?
     *
     * The default when `measuredCategories` is absent keys off `measuredConfiguredMb`,
     * NOT off nothing — the two fields shipped in different daemon versions and are
     * independent:
     *   - neither present: the measured sum falls back to the full sum
     *     (see _measuredBase), so every category IS in it → true, move the delta.
     *   - sum present, membership absent: the sum is real and SMALLER than the full sum,
     *     but we cannot tell whether this category is inside it. Moving the delta then
     *     both invents warnings that vanish on Apply and hides real ones (the round-3
     *     defect). Refuse → the category simply gets save-time-only feedback, which is
     *     the documented fallback posture.
     */
    _isMeasured: function (b, category) {
        if (!b) return false;
        if (!b.measuredCategories) return b.measuredConfiguredMb == null;
        return b.measuredCategories.indexOf(category) !== -1;
    },

    /**
     * Σ limits, on `b`'s volume, of categories that follow `leader`'s volume choice
     * (per the server's categoryFollows map). Proximity has no storage picker and
     * rides recordings' volume, so a pending recordings switch takes proximity's
     * budget along; without this the destination sum understates by that amount.
     * Returns 0 when the payload predates the map.
     */
    followerLimits: function (b, leader) {
        if (!b || !b.categoryFollows || !b.categories || !b.categoryLimitsMb) return 0;
        var sum = 0;
        for (var i = 0; i < b.categoryFollows.length; i++) {
            var f = b.categoryFollows[i];
            if (!f || f.followsVolumeOf !== leader) continue;
            var at = b.categories.indexOf(f.category);
            if (at !== -1 && b.categoryLimitsMb[at] != null) sum += Number(b.categoryLimitsMb[at]);
        }
        return sum;
    },

    /** Category keys on `b`'s volume that follow `leader`'s volume choice. */
    followerCategories: function (b, leader) {
        var out = [];
        if (!b || !b.categoryFollows || !b.categories) return out;
        for (var i = 0; i < b.categoryFollows.length; i++) {
            var f = b.categoryFollows[i];
            if (!f || f.followsVolumeOf !== leader) continue;
            if (b.categories.indexOf(f.category) !== -1) out.push(f.category);
        }
        return out;
    },

    /**
     * Recompute a budget entry with `addedMb` ADDED to its sum — for the case where
     * the user has repointed a category at a volume the server hasn't grouped it
     * under yet. Distinct from withPending, which SUBSTITUTES for a limit already
     * counted in the sum; using that here would silently drop the incoming category.
     *
     * `movedCategories` are the category keys arriving on this volume, appended to the
     * copy's list so the rendered peer list names them. When a key is ALREADY counted
     * here the server has it on this volume, so its stored limit is subtracted to keep
     * the sum from double-counting — the payload never does that today (each category
     * lands in exactly one entry), but a total function beats a relied-upon invariant.
     *
     * `categories` and `categoryLimitsMb` are kept INDEX-PARALLEL in the copy, the same
     * invariant the server's payload holds. Appending to one without the other left a
     * result that withPending()/followerLimits() would silently misread if anyone ever
     * chained the two — cheap to keep correct, expensive to debug later.
     */
    withAdded: function (b, addedMb, movedCategories) {
        if (!b) return b;
        var add = Number(addedMb) || 0;
        var c = {};
        for (var k in b) if (b.hasOwnProperty(k)) c[k] = b[k];

        var moved = movedCategories || [];
        var already = 0;         // subtracted from configuredMb
        var alreadyMeasured = 0; // subtracted from measuredConfiguredMb
        var names = (b.categories || []).slice();
        var lims = (b.categoryLimitsMb || []).slice();
        for (var i = 0; i < moved.length; i++) {
            var at = (b.categories || []).indexOf(moved[i]);
            if (at !== -1) {
                if (b.categoryLimitsMb && b.categoryLimitsMb[at] != null) {
                    var lim = Number(b.categoryLimitsMb[at]);
                    already += lim;
                    // Only subtract from the MEASURED sum what that sum actually
                    // contains. An unmeasured category's limit was never added to it, so
                    // subtracting it drove the sum negative — a false all-clear, and a
                    // negative figure that fmt() would happily print into the banner.
                    if (this._isMeasured(b, moved[i])) alreadyMeasured += lim;
                }
            } else {
                names.push(moved[i]);
                // Parallel slot for the appended name, but NULL — not 0. The moved
                // category's individual limit is genuinely unknown here (`add` is the
                // leader plus its followers, not separable per name), and null is what
                // withPending()/followerLimits() test for to bail out safely. A 0 passes
                // those guards and reads as "this category is configured for nothing",
                // which would let a future withPending(withAdded(...)) chain add a
                // pending value on top of a sum that already contains it.
                lims.push(null);
            }
        }
        c.categories = names;
        c.categoryLimitsMb = lims;
        // Floored for the same reason the measured sum is: a negative total is
        // incoherent and _mb() would happily print "-89999 MB" into the banner.
        c.configuredMb = Math.max(0, b.configuredMb + add - already);
        // Arriving categories are counted on the demand side of overReachable too. Their
        // BYTES aren't on this volume yet, so this leans toward over-warning — correct
        // here: the user is about to move that data in, and a warning before an
        // impossible move is the whole point of evaluating a pending switch. Everything
        // arriving is therefore treated as measured demand regardless of whether the
        // daemon can size it, and the arrivals join measuredCategories so a later
        // withPending on this copy stays consistent with that decision.
        c.measuredConfiguredMb = this._clampMeasured(
            this._measuredBase(b) + add - alreadyMeasured, c.configuredMb);
        var measured = (b.measuredCategories || []).slice();
        for (var j = 0; j < moved.length; j++) {
            if (measured.indexOf(moved[j]) === -1) measured.push(moved[j]);
        }
        c.measuredCategories = measured;
        // Mirror the server's two-gate split: overCapacity needs only the volume SIZE
        // (capacityKnown, which also carries its fabricated-ceiling guard), overReachable
        // needs a trustworthy free reading (measurable). Recomputing both off `measurable`
        // alone resurrected the fabricated-ceiling claim the server deliberately withholds.
        c.overCapacity = this._capacityKnown(c) && c.configuredMb > c.usableMb;
        c.overReachable = c.measurable && c.measuredConfiguredMb > c.reachableMb;
        return c;
    },

    /**
     * Render (or hide) the advisory banner for `category` into `elementId`.
     *
     * Renders only when the volume is measurable AND overcommitted. An unmounted
     * or unreadable volume yields measurable=false server-side and is skipped —
     * warning off a StatFs that momentarily read 0 would make the banner flap
     * with every FUSE hiccup, and the existing fallback banner already covers
     * "your card isn't there".
     *
     * Two severities, because they are different problems:
     *   overCapacity  — Σ limits exceed the card's total size. Impossible even on
     *                   an empty card; the user must lower a limit.
     *   overReachable — Σ limits fit the card in principle but not alongside what
     *                   is already on it (BYD's own CDR dashcam files, other apps).
     *                   Freeing foreign data is also a valid fix, so the copy says so.
     *
     * @param elementId Container element id; absent element = no-op (pages opt in).
     * @param budget    The `storageBudget` array from the settings response.
     * @param category  The calling page's own category key.
     * @param pendingMb Optional unsaved slider value for `category`; when given the
     *                  sum is recomputed with it in place of the stored limit.
     * @param pendingType Optional unsaved storage-type pick. When it differs from the
     *                  volume the server has this category on, the advisory retargets
     *                  to the destination volume and adds the pending limit to ITS sum
     *                  (the category isn't in that volume's list yet).
     * @returns true when a warning is showing.
     */
    render: function (elementId, budget, category, pendingMb, pendingType) {
        var el = document.getElementById(elementId);
        if (!el) return false;

        var b = this.forCategory(budget, category);
        // The caller's pending zero is No Limit, not a zero-byte budget. Suppress
        // this category's warning immediately, including while its volume is moving.
        if (pendingMb != null && Number(pendingMb) === 0) b = null;
        var movedVolumes = pendingType && b && b.storageType !== pendingType;
        if (movedVolumes) {
            // Retarget to the destination. If nothing is configured there yet the
            // server sent no entry for it, so there is no capacity to compare
            // against — suppress rather than guess.
            var dest = this.forVolume(budget, pendingType);
            if (!dest) {
                b = null;
            } else {
                // Categories with no picker of their own (proximity) ride this
                // category's volume, so they move with it. Carry their limits over
                // too, or the destination sum understates by their size.
                var moved = [category].concat(this.followerCategories(b, category));
                var incoming = (Number(pendingMb) || 0) + this.followerLimits(b, category);
                b = this.withAdded(dest, incoming, moved);
            }
        } else if (b && pendingMb != null) {
            b = this.withPending(b, category, pendingMb);
        }
        // Gate on the FLAGS, not on `measurable`: a 100%-full card reports 0 free and so
        // isn't `measurable`, but its overCapacity claim is still known-good (the server
        // computes it from the volume size alone). Requiring measurable here would have
        // suppressed the banner for exactly that user. Each flag already carries its own
        // knowledge gate, server-side and in the two recompute paths.
        if (!b || (!b.overCapacity && !b.overReachable)) {
            el.style.display = 'none';
            // Clear the text so a stale message can't flash on the next render
            // before the fresh numbers land.
            var stale = el.querySelector('[data-budget-text]');
            if (stale) stale.textContent = '';
            return false;
        }

        var others = this.others(b.categories, category, b.categoryLimitsMb);
        // Show the pair that the FIRED threshold actually compared, so the sentence is
        // arithmetically true as written. overCapacity weighs the full configured sum
        // against physical size; overReachable weighs only the measurable subset
        // against attainable space (see measurableConfiguredMb server-side).
        var ceiling = b.overCapacity ? b.usableMb : b.reachableMb;
        var total   = b.overCapacity ? b.configuredMb : this._measuredBase(b);
        // Format each against the other so a 100 MB overshoot can't render as
        // "45 GB … 45 GB" (see fmtPair).
        var vars = {
            volume: this.volumeName(b),
            total: this.fmtPair(total, ceiling),
            capacity: this.fmtPair(ceiling, total),
            others: others
        };

        // Key choice: `_solo` variants for the single-category case (proximity
        // rides recordings' volume, so a "solo" recordings page is still possible
        // only when trips/surveillance live elsewhere AND proximity is the sole
        // peer — the others list is what decides, not the category count).
        var key;
        if (b.overCapacity) {
            key = others ? 'storage.budget_over_capacity' : 'storage.budget_over_capacity_solo';
        } else {
            key = others ? 'storage.budget_over_free' : 'storage.budget_over_free_solo';
        }

        // No text sink means the page's markup is incomplete. Showing the empty
        // coloured banner would be a wordless alarm, so suppress instead of
        // fail-open — and skipping the i18n sentinel check below would too.
        var textEl = el.querySelector('[data-budget-text]');
        if (!textEl) {
            el.style.display = 'none';
            return false;
        }
        var msg = BYD.i18n.t(key, vars);
        // t() returns the raw key when the catalog is loaded but the key is
        // missing, and null while it's still loading. Neither should be
        // painted into a warning banner — suppress rather than show noise.
        if (msg == null || msg === key) {
            el.style.display = 'none';
            return false;
        }
        textEl.textContent = msg;
        el.style.display = 'flex';
        return true;
    }
};
