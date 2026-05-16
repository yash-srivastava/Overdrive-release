// ROI Editor — Block-tap mode (10x7 grid matches backend exactly)
// Tap or drag across blocks to toggle them. What you see = what the backend uses.
// ES5 compatible. Toggle = UI only. Save = commits to backend.

var RoiEditor = (function() {
    'use strict';
    var canvas, ctx, currentQuadrant = 0;
    var GC = 10, GR = 7, TOTAL = 70;
    // Per-quadrant block masks: 1=active, 0=inactive. Default all active.
    var blocks = [null, null, null, null];
    var roiEnabledFlags = [false, false, false, false];
    var savedBlocks = [null, null, null, null];
    var savedEnabled = [false, false, false, false];
    var snapshotImages = [null, null, null, null];
    var canvasW = 640, canvasH = 480;
    function qName(q) {
        var keys = ['roi.cam_front', 'roi.cam_right', 'roi.cam_rear', 'roi.cam_left'];
        return BYD.i18n.t(keys[q]);
    }
    var painting = false, paintValue = 1; // 1=activate, 0=deactivate

    function initBlocks(q) {
        if (!blocks[q]) { blocks[q] = []; for (var i = 0; i < TOTAL; i++) blocks[q][i] = 1; }
    }

    function init() {
        canvas = document.getElementById('roiCanvas');
        if (!canvas) return;
        canvas.width = canvasW; canvas.height = canvasH;
        ctx = canvas.getContext('2d');
        for (var q = 0; q < 4; q++) initBlocks(q);

        canvas.addEventListener('mousedown', function(e) { startPaint(e.clientX, e.clientY); });
        canvas.addEventListener('mousemove', function(e) { if (painting) doPaint(e.clientX, e.clientY); });
        canvas.addEventListener('mouseup', function() { painting = false; });
        canvas.addEventListener('mouseleave', function() { painting = false; });
        canvas.addEventListener('touchstart', function(e) { e.preventDefault(); startPaint(e.touches[0].clientX, e.touches[0].clientY); });
        canvas.addEventListener('touchmove', function(e) { e.preventDefault(); if (painting) doPaint(e.touches[0].clientX, e.touches[0].clientY); });
        canvas.addEventListener('touchend', function(e) { e.preventDefault(); painting = false; });
        canvas.addEventListener('touchcancel', function() { painting = false; });

        loadConfig(); loadSnapshot(currentQuadrant);
    }

    function getBlock(cx, cy) {
        var r = canvas.getBoundingClientRect();
        var nx = (cx - r.left) / r.width, ny = (cy - r.top) / r.height;
        var bx = Math.floor(nx * GC), by = Math.floor(ny * GR);
        if (bx < 0 || bx >= GC || by < 0 || by >= GR) return -1;
        return by * GC + bx;
    }

    function startPaint(cx, cy) {
        if (!roiEnabledFlags[currentQuadrant]) return;
        var idx = getBlock(cx, cy); if (idx < 0) return;
        initBlocks(currentQuadrant);
        // Toggle: if block is active, paint deactivates; if inactive, paint activates
        paintValue = blocks[currentQuadrant][idx] ? 0 : 1;
        painting = true;
        blocks[currentQuadrant][idx] = paintValue;
        draw(); updateUnsaved();
    }

    function doPaint(cx, cy) {
        var idx = getBlock(cx, cy); if (idx < 0) return;
        initBlocks(currentQuadrant);
        blocks[currentQuadrant][idx] = paintValue;
        draw();
    }

    function toggleRoi() {
        var cb = document.getElementById('roiEnabled');
        roiEnabledFlags[currentQuadrant] = cb ? cb.checked : false;
        var c = document.getElementById('roiDrawingContent');
        if (c) c.style.display = roiEnabledFlags[currentQuadrant] ? 'block' : 'none';
        draw(); updateStatus(); updateUnsaved();
    }

    function selectCamera(q) {
        currentQuadrant = q;
        var tabs = document.querySelectorAll('#roiCameraTabs .btn-toggle');
        for (var i = 0; i < tabs.length; i++) tabs[i].className = 'btn-toggle' + (i === q ? ' active' : '');
        var cb = document.getElementById('roiEnabled');
        if (cb) cb.checked = roiEnabledFlags[q];
        var c = document.getElementById('roiDrawingContent');
        if (c) c.style.display = roiEnabledFlags[q] ? 'block' : 'none';
        loadSnapshot(q); updateStatus(); updateUnsaved();
    }

    function loadSnapshot(q) {
        var img = new Image(); img.crossOrigin = 'anonymous';
        img.onload = function() { snapshotImages[q] = img; draw(); };
        img.onerror = function() { snapshotImages[q] = null; draw(); };
        img.src = '/api/surveillance/snapshot/' + q + '?t=' + Date.now();
    }

    function draw() {
        if (!ctx) return;
        ctx.clearRect(0, 0, canvasW, canvasH);
        // Background
        var img = snapshotImages[currentQuadrant];
        if (img) { ctx.drawImage(img, 0, 0, canvasW, canvasH); }
        else {
            ctx.fillStyle = '#0d1117'; ctx.fillRect(0, 0, canvasW, canvasH);
            ctx.fillStyle = '#3a5a7a'; ctx.font = '13px Inter,sans-serif'; ctx.textAlign = 'center';
            ctx.fillText(qName(currentQuadrant) + ' \u2014 ' + BYD.i18n.t('roi.no_preview'), canvasW / 2, canvasH / 2);
        }
        if (!roiEnabledFlags[currentQuadrant]) {
            ctx.fillStyle = 'rgba(0,0,0,0.4)'; ctx.fillRect(0, 0, canvasW, canvasH);
            return;
        }
        // Block grid overlay
        initBlocks(currentQuadrant);
        var bw = canvasW / GC, bh = canvasH / GR;
        var b = blocks[currentQuadrant];
        for (var by = 0; by < GR; by++) {
            for (var bx = 0; bx < GC; bx++) {
                var idx = by * GC + bx;
                var x = bx * bw, y = by * bh;
                if (!b[idx]) {
                    // Inactive block — dim
                    ctx.fillStyle = 'rgba(0,0,0,0.5)';
                    ctx.fillRect(x, y, bw, bh);
                } else {
                    // Active block — subtle blue tint
                    ctx.fillStyle = 'rgba(59,130,246,0.08)';
                    ctx.fillRect(x, y, bw, bh);
                }
                // Grid lines
                ctx.strokeStyle = b[idx] ? 'rgba(59,130,246,0.25)' : 'rgba(255,255,255,0.08)';
                ctx.lineWidth = 0.5;
                ctx.strokeRect(x + 0.5, y + 0.5, bw - 1, bh - 1);
            }
        }
    }

    function activeCount() {
        initBlocks(currentQuadrant);
        var c = 0; for (var i = 0; i < TOTAL; i++) if (blocks[currentQuadrant][i]) c++;
        return c;
    }

    function updateStatus() {
        var el = document.getElementById('roiStatus'); if (!el) return;
        if (!roiEnabledFlags[currentQuadrant]) { el.textContent = BYD.i18n.t('roi.full_frame', {cam: qName(currentQuadrant)}); return; }
        var ac = activeCount();
        el.textContent = BYD.i18n.t('roi.blocks_active', {cam: qName(currentQuadrant), active: ac, total: TOTAL});
    }

    function isDirty(q) {
        if (roiEnabledFlags[q] !== savedEnabled[q]) return true;
        if (!blocks[q] || !savedBlocks[q]) return blocks[q] !== savedBlocks[q];
        for (var i = 0; i < TOTAL; i++) if (blocks[q][i] !== savedBlocks[q][i]) return true;
        return false;
    }
    function updateUnsaved() {
        var el = document.getElementById('roiUnsaved');
        if (el) el.style.display = isDirty(currentQuadrant) ? 'inline' : 'none';
        updateStatus();
    }

    function save() {
        initBlocks(currentQuadrant);
        var en = roiEnabledFlags[currentQuadrant];
        var qk = ['Q0', 'Q1', 'Q2', 'Q3'];
        var payload = {};
        // Always send blocks (even when disabling) so they persist for re-enable
        payload['roiBlocks_' + qk[currentQuadrant]] = blocks[currentQuadrant].slice();
        payload['roiEnabled_' + qk[currentQuadrant]] = en;
        fetch('/api/surveillance/config', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
            .then(function(r) { return r.json(); }).then(function() {
                savedBlocks[currentQuadrant] = blocks[currentQuadrant].slice();
                savedEnabled[currentQuadrant] = roiEnabledFlags[currentQuadrant];
                updateUnsaved();
                msg(BYD.i18n.t(en ? 'roi.zone_saved' : 'roi.roi_disabled', {cam: qName(currentQuadrant)}), 'success');
                // Re-read config to confirm persistence (catches backend issues early)
                loadConfig();
            }).catch(function() { msg(BYD.i18n.t('roi.save_failed'), 'error'); });
    }

    function selectAll() {
        initBlocks(currentQuadrant);
        for (var i = 0; i < TOTAL; i++) blocks[currentQuadrant][i] = 1;
        draw(); updateUnsaved();
    }
    function clear() {
        initBlocks(currentQuadrant);
        for (var i = 0; i < TOTAL; i++) blocks[currentQuadrant][i] = 0;
        draw(); updateUnsaved();
    }

    function msg(m, t) { if (typeof window.showToast === 'function') window.showToast(m, t); }

    function loadConfig() {
        fetch('/api/surveillance/config').then(function(r) { return r.json(); }).then(function(d) {
            var cfg = (d && d.config) ? d.config : d;  // Unwrap {config: ...} envelope
            var qk = ['Q0', 'Q1', 'Q2', 'Q3'];
            for (var q = 0; q < 4; q++) {
                if (cfg['roiEnabled_' + qk[q]] !== undefined) roiEnabledFlags[q] = !!cfg['roiEnabled_' + qk[q]];
                var ba = cfg['roiBlocks_' + qk[q]];
                if (ba && ba.length === TOTAL) {
                    blocks[q] = []; for (var i = 0; i < TOTAL; i++) blocks[q][i] = ba[i] ? 1 : 0;
                } else { initBlocks(q); }
                savedBlocks[q] = blocks[q].slice();
                savedEnabled[q] = roiEnabledFlags[q];
            }
            var cb = document.getElementById('roiEnabled');
            if (cb) cb.checked = roiEnabledFlags[currentQuadrant];
            var c = document.getElementById('roiDrawingContent');
            if (c) c.style.display = roiEnabledFlags[currentQuadrant] ? 'block' : 'none';
            draw(); updateStatus(); updateUnsaved();
        }).catch(function() {});
    }

    return { init: init, selectCamera: selectCamera, toggleRoi: toggleRoi, save: save, selectAll: selectAll, clear: clear };
})();


// Schedule Editor — ES5 compatible
// Toggle = UI only. Save = commits to backend.

var ScheduleEditor = (function() {
    'use strict';
    var rules = [];
    // i18n: derive short weekday names from Intl using a known week (Sun..Sat).
    var dayNames = (function() {
        var out = [];
        try {
            var fmt = new Intl.DateTimeFormat(BYD.i18n.getLang(), { weekday: 'short' });
            for (var w = 0; w < 7; w++) out.push(fmt.format(new Date(2024, 0, 7 + w)));
        } catch (e) {
            out = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
        }
        return out;
    })();
    function init() { loadConfig(); }
    function pad(n) { return n < 10 ? '0'+n : ''+n; }
    function msg(m,t) { if (typeof window.showToast === 'function') window.showToast(m,t); }

    function toggle() {
        var cb = document.getElementById('scheduleEnabled'), en = cb ? cb.checked : false;
        document.getElementById('scheduleContent').style.display = en ? 'block' : 'none';
        var badge = document.getElementById('scheduleBadge');
        if (badge) { badge.textContent = en ? BYD.i18n.t('roi.editing') : BYD.i18n.t('status.off'); badge.className = 'status-badge '+(en?'active':'inactive'); }
        updateSaveBtn(); updateSummary();
    }
    function addRule() {
        rules.push({ days:[1,2,3,4,5], startHour:18, startMin:0, endHour:8, endMin:0 });
        document.getElementById('scheduleContent').style.display = 'block';
        var cb = document.getElementById('scheduleEnabled'); if (cb) cb.checked = true;
        var badge = document.getElementById('scheduleBadge');
        if (badge) { badge.textContent = BYD.i18n.t('roi.editing'); badge.className = 'status-badge active'; }
        renderRules();
    }
    function removeRule(i) { rules.splice(i, 1); renderRules(); }
    function mkSel(vals, sel, ri, f, w) {
        var s = document.createElement('select'); s.className = 'select-control';
        s.style.cssText = 'width:'+w+'px;padding:4px 6px;font-size:13px;';
        for (var i = 0; i < vals.length; i++) {
            var o = document.createElement('option'); o.value = vals[i]; o.textContent = pad(vals[i]);
            if (vals[i] === sel) o.selected = true; s.appendChild(o);
        }
        (function(ri,f){ s.onchange = function(){ rules[ri][f] = parseInt(this.value,10); updateSummary(); updateSaveBtn(); }; })(ri, f);
        return s;
    }
    function hrs() { var a=[]; for(var i=0;i<24;i++) a.push(i); return a; }
    function mns() { return [0,15,30,45]; }
    function renderRules() {
        var c = document.getElementById('scheduleRulesList'); if (!c) return; c.innerHTML = '';
        for (var r = 0; r < rules.length; r++) {
            var rule = rules[r], div = document.createElement('div');
            div.style.cssText = 'background:var(--bg-secondary);border-radius:8px;padding:12px;margin-bottom:8px;border:1px solid var(--card-border);';
            var hdr = document.createElement('div'); hdr.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;';
            var lbl = document.createElement('span'); lbl.style.cssText = 'font-size:11px;font-weight:600;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.5px;'; lbl.textContent = BYD.i18n.t('roi.window_n', {n: r+1});
            hdr.appendChild(lbl);
            var rm = document.createElement('button'); rm.textContent = BYD.i18n.t('roi.remove');
            rm.style.cssText = 'background:none;border:1px solid rgba(239,68,68,0.3);color:#ef4444;font-size:11px;cursor:pointer;padding:2px 8px;border-radius:4px;';
            (function(ri){ rm.onclick = function(){ removeRule(ri); }; })(r); hdr.appendChild(rm); div.appendChild(hdr);
            var dr = document.createElement('div'); dr.style.cssText = 'display:flex;gap:3px;margin-bottom:10px;';
            for (var d = 0; d < 7; d++) {
                var btn = document.createElement('button'); btn.textContent = dayNames[d];
                var act = rule.days.indexOf(d) >= 0;
                btn.style.cssText = 'flex:1;padding:6px 0;border-radius:6px;font-size:11px;font-weight:500;border:1px solid '+(act?'var(--brand-primary)':'var(--card-border)')+';cursor:pointer;background:'+(act?'var(--brand-primary)':'transparent')+';color:'+(act?'#fff':'var(--text-muted)')+';';
                (function(ri,di){ btn.onclick = function(){ var idx=rules[ri].days.indexOf(di); if(idx>=0) rules[ri].days.splice(idx,1); else { rules[ri].days.push(di); rules[ri].days.sort(); } renderRules(); }; })(r,d);
                dr.appendChild(btn);
            }
            div.appendChild(dr);
            var tr = document.createElement('div'); tr.style.cssText = 'display:flex;align-items:center;gap:4px;flex-wrap:wrap;';
            function mkL(t){ var s=document.createElement('span'); s.textContent=t; s.style.cssText='font-size:11px;color:var(--text-muted);'; return s; }
            function mkC(){ var s=document.createElement('span'); s.textContent=':'; s.style.cssText='font-weight:bold;color:var(--text-muted);'; return s; }
            tr.appendChild(mkL(BYD.i18n.t('roi.from'))); tr.appendChild(mkSel(hrs(),rule.startHour,r,'startHour',58)); tr.appendChild(mkC()); tr.appendChild(mkSel(mns(),rule.startMin,r,'startMin',54));
            tr.appendChild(mkL(BYD.i18n.t('roi.to'))); tr.appendChild(mkSel(hrs(),rule.endHour,r,'endHour',58)); tr.appendChild(mkC()); tr.appendChild(mkSel(mns(),rule.endMin,r,'endMin',54));
            div.appendChild(tr);
            if (rule.days.length === 0) div.appendChild(mkW('\u26a0 ' + BYD.i18n.t('roi.err_select_day'),'#ef4444'));
            else if (rule.startHour===rule.endHour && rule.startMin===rule.endMin) div.appendChild(mkW('\u26a0 ' + BYD.i18n.t('roi.err_identical'),'#ef4444'));
            else if (rule.startHour>rule.endHour||(rule.startHour===rule.endHour&&rule.startMin>rule.endMin)) div.appendChild(mkW('\ud83c\udf19 ' + BYD.i18n.t('roi.overnight', {start: pad(rule.startHour)+':'+pad(rule.startMin), end: pad(rule.endHour)+':'+pad(rule.endMin)}),'var(--brand-primary)'));
            c.appendChild(div);
        }
        updateSummary(); updateSaveBtn();
    }
    function mkW(t,col) { var d=document.createElement('div'); d.style.cssText='font-size:11px;color:'+col+';margin-top:6px;'; d.textContent=t; return d; }
    function hasErr() { for(var i=0;i<rules.length;i++) { if(rules[i].days.length===0) return true; if(rules[i].startHour===rules[i].endHour&&rules[i].startMin===rules[i].endMin) return true; } return false; }
    function updateSaveBtn() {
        var btn = document.getElementById('scheduleSaveBtn'); if (!btn) return;
        var cb = document.getElementById('scheduleEnabled'), en = cb ? cb.checked : false;
        var bad = en && hasErr();
        btn.disabled = bad; btn.style.opacity = bad ? '0.4' : '1'; btn.style.cursor = bad ? 'not-allowed' : 'pointer';
    }
    function updateSummary() {
        var el = document.getElementById('scheduleSummary'); if (!el) return;
        var cb = document.getElementById('scheduleEnabled'), en = cb ? cb.checked : false;
        if (!en) { el.textContent = BYD.i18n.t('roi.schedule_off'); el.style.color = 'var(--text-muted)'; return; }
        if (rules.length === 0) { el.textContent = BYD.i18n.t('roi.add_time_window'); el.style.color = 'var(--text-muted)'; return; }
        if (hasErr()) { el.textContent = BYD.i18n.t('roi.fix_errors'); el.style.color = '#f59e0b'; return; }
        var parts = [];
        for (var i = 0; i < rules.length; i++) {
            var r = rules[i], ds = [];
            for (var d = 0; d < r.days.length; d++) ds.push(dayNames[r.days[d]]);
            var ts = pad(r.startHour)+':'+pad(r.startMin)+'\u2013'+pad(r.endHour)+':'+pad(r.endMin);
            if (r.startHour>r.endHour||(r.startHour===r.endHour&&r.startMin>r.endMin)) ts += ' ' + BYD.i18n.t('roi.next_day_paren');
            parts.push(ds.join(',')+' '+ts);
        }
        el.textContent = parts.join(' | '); el.style.color = '#22c55e';
    }
    function save() {
        var cb = document.getElementById('scheduleEnabled'), en = cb ? cb.checked : false;
        // If enabled with no rules, auto-disable instead of blocking save
        if (en && rules.length === 0) { en = false; if (cb) cb.checked = false; }
        if (en && hasErr()) { msg(BYD.i18n.t('roi.fix_errors_first'),'error'); return; }
        var payload = { scheduleEnabled: en, scheduleRules: [] };
        for (var i = 0; i < rules.length; i++) payload.scheduleRules.push({ days:rules[i].days, startHour:rules[i].startHour, startMin:rules[i].startMin, endHour:rules[i].endHour, endMin:rules[i].endMin });
        fetch('/api/surveillance/config', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)})
            .then(function(r){return r.json();}).then(function(){
                msg(BYD.i18n.t('roi.schedule_saved'),'success');
                var badge = document.getElementById('scheduleBadge');
                if (badge) { badge.textContent = en ? BYD.i18n.t('roi.badge_on') : BYD.i18n.t('roi.badge_off'); badge.className = 'status-badge '+(en?'active':'inactive'); }
            }).catch(function(){ msg(BYD.i18n.t('roi.save_failed'),'error'); });
    }
    function loadConfig() {
        fetch('/api/surveillance/config').then(function(r){return r.json();}).then(function(d) {
            var cfg = d.config || d;
            var en = cfg.scheduleEnabled || false; rules = [];
            var arr = cfg.scheduleRules;
            if (arr && arr.length) { for (var i=0;i<arr.length;i++) rules.push({days:arr[i].days||[],startHour:arr[i].startHour||0,startMin:arr[i].startMin||0,endHour:arr[i].endHour||23,endMin:arr[i].endMin||59}); }
            var cb = document.getElementById('scheduleEnabled'); if (cb) cb.checked = en;
            document.getElementById('scheduleContent').style.display = (en || rules.length > 0) ? 'block' : 'none';
            var badge = document.getElementById('scheduleBadge');
            if (badge) { badge.textContent = en ? BYD.i18n.t('roi.badge_on') : BYD.i18n.t('roi.badge_off'); badge.className = 'status-badge '+(en?'active':'inactive'); }
            renderRules();
        }).catch(function(){});
    }
    return { init:init, toggle:toggle, addRule:addRule, save:save };
})();
