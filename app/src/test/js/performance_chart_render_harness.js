// Run with: node app/src/test/js/performance_chart_render_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/performance.js'), 'utf8');
const BYD = { i18n: { t: key => key } };
vm.runInNewContext(source, { BYD, window: { BYD } });
const perf = BYD.performance;

// A full refresh uses the same series, colors and labels regardless of hover.
const expected = [
    ['cpu', [[perf.history.cpuSystem, perf.colors.system, 'performance.legend_system'],
        [perf.history.cpuApp, perf.colors.app, 'performance.legend_app']]],
    ['mem', [[perf.history.memSystem, perf.colors.system, 'performance.legend_system'],
        [perf.history.memApp, perf.colors.app, 'performance.legend_app']]],
    ['gpu', [[perf.history.gpu, perf.colors.gpu, 'performance.card_gpu']]]
];
const realRenderChart = perf.renderChart;
for (const [type] of expected) perf.charts[type] = { type };
for (const hovered of [null, 'cpu', 'mem', 'gpu', 'soc']) {
    const calls = [];
    perf.tooltip.chartId = hovered;
    perf.renderChart = (chart, series) => calls.push([chart.type,
        Array.from(series, s => [s.data, s.color, s.label])]);
    perf.renderAllCharts();
    assert.deepStrictEqual(calls, expected);
}
perf.renderChart = realRenderChart;

// Capture actual rendering calls rather than a copy of the shared helper.
function canvas(type, width, height) {
    const lines = [], labels = [];
    const ctx = {
        clearRect() {}, beginPath() {}, stroke() {},
        moveTo(x, y) { lines.push(['from', x, y]); },
        lineTo(x, y) { lines.push(['to', x, y]); },
        fillText(text, x, y) { labels.push([text, x, y, this.textAlign]); }
    };
    return { chart: { ctx, type, width, height }, lines, labels };
}
function assertGrid(record, left, right, top, step) {
    const lines = [], labels = [];
    for (let i = 0; i <= 4; i++) {
        const y = top + i * step;
        lines.push(['from', left, y], ['to', right, y]);
        labels.push([(100 - i * 25) + '%', left - 8, y + 3, 'right']);
    }
    assert.deepStrictEqual(record.lines, lines);
    assert.deepStrictEqual(record.labels.slice(0, 5), labels);
}
const live = canvas('cpu', 300, 115);
perf.tooltip.visible = true;
perf.tooltip.chartId = 'cpu';
let tooltips = 0;
perf.drawCrosshairAndTooltip = () => { tooltips++; };
perf.renderChart(live.chart, []);
assertGrid(live, 40, 290, 10, 20);
assert.strictEqual(tooltips, 1);

const soc = canvas('soc', 300, 125);
perf.charts.soc = soc.chart;
perf.socData.history = [{ t: 100, soc: 30 }, { t: 200, soc: 40 }];
perf.getTimeLabels = () => [];
perf.drawChargingRegions = () => {};
perf.drawSocLine = () => {};
perf.renderSocChart();
assertGrid(soc, 45, 285, 15, 20);
assert.strictEqual(tooltips, 1);
console.log('Performance chart rendering contract passed');
