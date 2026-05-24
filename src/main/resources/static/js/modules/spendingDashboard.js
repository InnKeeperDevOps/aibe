import { api } from './api.js';

// Admin-only spending dashboard. Consumes /api/spending-dashboard and renders:
//   - Global cap status (current spend, limit, % used, reset window)
//   - Top spending suggestions table
//   - Trend over the last N days
//   - Recent expert reviews with their per-review cost
//   - Daily / monthly archived periods

function fmtTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
}

function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? String(iso) : d.toLocaleDateString();
}

function fmtDuration(ms) {
    if (ms == null) return '';
    return ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
}

function fmtNumber(n) {
    if (n == null) return '0';
    return n.toLocaleString();
}

function txt(tag, content, style) {
    const el = document.createElement(tag);
    if (style) el.style.cssText = style;
    el.textContent = (content == null) ? '' : String(content);
    return el;
}

function th(text) {
    const e = document.createElement('th');
    e.style.cssText = 'text-align:left;padding:0.5rem;border-bottom:2px solid var(--border);font-size:0.85rem';
    e.textContent = text;
    return e;
}

function td(text, extra) {
    const e = document.createElement('td');
    e.style.cssText = 'padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;'
        + (extra || '');
    e.textContent = (text == null) ? '' : String(text);
    return e;
}

function renderCapStatus(cap) {
    const wrap = document.getElementById('spendingCapStatus');
    if (!wrap) return;
    wrap.innerHTML = '';
    if (!cap || !cap.limitConfigured) {
        wrap.appendChild(txt('h3', 'Global cap', 'margin:0 0 0.5rem 0;font-size:1rem'));
        const p = txt('p',
            'No global spending cap configured. Set "Max total cost" on the Settings page to enforce one.',
            'margin:0;color:var(--text-muted);font-size:0.9rem');
        wrap.appendChild(p);
        if (cap && cap.displayCurrentSpend) {
            const cur = txt('p', 'Current spend so far: ' + cap.displayCurrentSpend,
                'margin:0.5rem 0 0 0;color:var(--text-muted);font-size:0.85rem');
            wrap.appendChild(cur);
        }
        return;
    }

    const pct = cap.percentUsed == null ? 0 : Math.min(100, cap.percentUsed);
    const danger = pct >= 90 ? 'var(--danger)' : (pct >= 75 ? '#b45309' : 'var(--primary)');

    wrap.appendChild(txt('h3', 'Global cap', 'margin:0 0 0.5rem 0;font-size:1rem'));

    const row = document.createElement('div');
    row.style.cssText = 'display:flex;justify-content:space-between;align-items:baseline;'
        + 'flex-wrap:wrap;gap:1rem;margin-bottom:0.5rem;font-size:0.9rem';
    row.appendChild(txt('span', cap.displayCurrentSpend + ' / ' + cap.displayLimit + ' ('
        + pct + '%)', 'font-weight:600;color:' + danger));
    row.appendChild(txt('span', 'Remaining: ' + cap.displayRemaining,
        'color:var(--text-muted)'));
    wrap.appendChild(row);

    // progress bar
    const bar = document.createElement('div');
    bar.style.cssText = 'background:var(--bg);border:1px solid var(--border);border-radius:4px;'
        + 'height:10px;overflow:hidden;margin-bottom:0.5rem';
    const fill = document.createElement('div');
    fill.style.cssText = 'background:' + danger + ';height:100%;width:' + pct + '%';
    bar.appendChild(fill);
    wrap.appendChild(bar);

    const meta = document.createElement('p');
    meta.style.cssText = 'margin:0;font-size:0.8rem;color:var(--text-muted)';
    const period = cap.resetPeriod || 'NEVER';
    meta.textContent = 'Reset window: ' + period
        + (cap.windowStart ? '  ·  window started ' + fmtTime(cap.windowStart) : '')
        + (cap.nextResetAt ? '  ·  next reset ' + fmtTime(cap.nextResetAt) : '');
    wrap.appendChild(meta);
}

function renderTopSuggestions(items) {
    const wrap = document.getElementById('spendingTopSuggestions');
    if (!wrap) return;
    wrap.innerHTML = '';
    wrap.appendChild(txt('h3', 'Top spending suggestions', 'margin:0 0 0.5rem 0;font-size:1rem'));
    if (!items || items.length === 0) {
        wrap.appendChild(txt('p', 'No suggestions have incurred expert-review costs yet.',
            'margin:0;color:var(--text-muted);font-size:0.9rem'));
        return;
    }
    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';
    const thead = document.createElement('thead');
    const tr = document.createElement('tr');
    ['Suggestion', 'Reviews', 'Cost', '% of per-suggestion cap', 'Remaining'].forEach(h => tr.appendChild(th(h)));
    thead.appendChild(tr);
    table.appendChild(thead);
    const tbody = document.createElement('tbody');
    items.forEach(it => {
        const row = document.createElement('tr');
        row.style.cursor = 'pointer';
        row.onclick = () => { window.location.hash = '#suggestion-' + it.suggestionId;
            window.app.navigate('detail', it.suggestionId); };
        row.appendChild(td('#' + it.suggestionId + ' — ' + (it.title || '(untitled)')));
        row.appendChild(td(fmtNumber(it.reviewCount)));
        row.appendChild(td(it.displayCostUsd));
        row.appendChild(td(it.percentUsed == null ? '—' : it.percentUsed + '%',
            it.percentUsed != null && it.percentUsed >= 90 ? 'color:var(--danger);font-weight:600' : ''));
        row.appendChild(td(it.displayRemaining || '—'));
        tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
}

function renderTrend(trend, windowDays) {
    const wrap = document.getElementById('spendingTrend');
    if (!wrap) return;
    wrap.innerHTML = '';
    wrap.appendChild(txt('h3',
        'Trend (last ' + (windowDays || (trend ? trend.length : 0)) + ' days)',
        'margin:0 0 0.5rem 0;font-size:1rem'));
    if (!trend || trend.length === 0) {
        wrap.appendChild(txt('p', 'No spending recorded in this window.',
            'margin:0;color:var(--text-muted);font-size:0.9rem'));
        return;
    }
    // Find the peak so bars can be normalized.
    let peak = 0;
    trend.forEach(p => {
        const v = parseFloat(p.totalCostUsd || '0');
        if (v > peak) peak = v;
    });
    const list = document.createElement('div');
    list.style.cssText = 'display:flex;flex-direction:column;gap:0.25rem';
    trend.forEach(p => {
        const v = parseFloat(p.totalCostUsd || '0');
        const pct = peak > 0 ? Math.max(2, Math.round((v / peak) * 100)) : 0;
        const row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;gap:0.6rem;font-size:0.8rem';

        const label = txt('span', fmtDate(p.date),
            'flex:0 0 6rem;color:var(--text-muted)');
        row.appendChild(label);

        const barWrap = document.createElement('div');
        barWrap.style.cssText = 'flex:1;background:var(--bg);border:1px solid var(--border);'
            + 'border-radius:3px;height:14px;overflow:hidden';
        const bar = document.createElement('div');
        bar.style.cssText = 'background:var(--primary);height:100%;width:' + pct + '%';
        barWrap.appendChild(bar);
        row.appendChild(barWrap);

        row.appendChild(txt('span', p.displayCostUsd + '  ·  ' + fmtNumber(p.reviewCount) + ' reviews',
            'flex:0 0 auto;color:var(--text-muted);min-width:10rem;text-align:right'));
        list.appendChild(row);
    });
    wrap.appendChild(list);
}

function renderRecentReviews(items) {
    const wrap = document.getElementById('spendingRecent');
    if (!wrap) return;
    wrap.innerHTML = '';
    wrap.appendChild(txt('h3', 'Recent expert reviews', 'margin:0 0 0.5rem 0;font-size:1rem'));
    if (!items || items.length === 0) {
        wrap.appendChild(txt('p', 'No expert reviews recorded yet.',
            'margin:0;color:var(--text-muted);font-size:0.9rem'));
        return;
    }
    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';
    const thead = document.createElement('thead');
    const tr = document.createElement('tr');
    ['When', 'Suggestion', 'Expert', 'Tokens', 'Cost', 'Duration'].forEach(h => tr.appendChild(th(h)));
    thead.appendChild(tr);
    table.appendChild(thead);
    const tbody = document.createElement('tbody');
    items.forEach(it => {
        const row = document.createElement('tr');
        row.appendChild(td(fmtTime(it.createdAt)));
        row.appendChild(td('#' + it.suggestionId + ' — ' + (it.suggestionTitle || '(untitled)')));
        row.appendChild(td(it.expertName));
        row.appendChild(td(fmtNumber(it.totalTokens)));
        row.appendChild(td(it.displayCostUsd));
        row.appendChild(td(fmtDuration(it.durationMs)));
        tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
}

function renderHistoryTable(title, items) {
    if (!items || items.length === 0) return null;
    const wrap = document.createElement('div');
    wrap.style.marginBottom = '1rem';
    wrap.appendChild(txt('h4', title, 'margin:0 0 0.35rem;font-size:0.95rem'));
    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';
    const thead = document.createElement('thead');
    const tr = document.createElement('tr');
    ['Period', 'Reviews', 'Cost', 'Cap at end', '% of cap'].forEach(h => tr.appendChild(th(h)));
    thead.appendChild(tr);
    table.appendChild(thead);
    const tbody = document.createElement('tbody');
    items.forEach(it => {
        const row = document.createElement('tr');
        row.appendChild(td(it.periodLabel || (fmtDate(it.periodStart) + ' — ' + fmtDate(it.periodEnd))));
        row.appendChild(td(fmtNumber(it.totalReviews)));
        row.appendChild(td(it.displayTotalCost));
        row.appendChild(td(it.displayLimit || '—'));
        row.appendChild(td(it.percentUsed == null ? '—' : it.percentUsed + '%',
            it.percentUsed != null && it.percentUsed >= 90 ? 'color:var(--danger);font-weight:600' : ''));
        tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
}

function renderHistory(daily, monthly) {
    const wrap = document.getElementById('spendingHistory');
    if (!wrap) return;
    wrap.innerHTML = '';
    wrap.appendChild(txt('h3', 'Archived periods', 'margin:0 0 0.5rem 0;font-size:1rem'));
    const d = renderHistoryTable('Daily', daily);
    const m = renderHistoryTable('Monthly', monthly);
    if (!d && !m) {
        wrap.appendChild(txt('p', 'No archived periods yet.',
            'margin:0;color:var(--text-muted);font-size:0.9rem'));
        return;
    }
    if (d) wrap.appendChild(d);
    if (m) wrap.appendChild(m);
}

export async function loadSpendingDashboard() {
    const errEl = document.getElementById('spendingError');
    if (errEl) errEl.style.display = 'none';

    // Show loading placeholders so the page doesn't look broken while fetching.
    const cap = document.getElementById('spendingCapStatus');
    if (cap) cap.innerHTML = '<p style="margin:0;color:var(--text-muted)">Loading…</p>';

    try {
        const snap = await api('/spending-dashboard');
        if (snap && snap.error) {
            if (errEl) { errEl.textContent = snap.error; errEl.style.display = ''; }
            return;
        }
        renderCapStatus(snap.globalCap);
        renderTopSuggestions(snap.topSuggestions);
        renderTrend(snap.trend, snap.trendWindowDays);
        renderRecentReviews(snap.recentReviews);
        renderHistory(snap.dailyHistory, snap.monthlyHistory);
    } catch (e) {
        if (errEl) {
            errEl.textContent = 'Could not load spending dashboard: ' + e.message;
            errEl.style.display = '';
        }
    }
}
