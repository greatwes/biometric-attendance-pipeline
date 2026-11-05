/* ═══════════════════════════════════════════════════════════════════════
   Staff Attendance - Dataiku web app frontend
   Paste into the "JS" tab.
   ═══════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Dataiku mounts the Python backend at a project- and webapp-specific path
  // that is not known until runtime. getWebAppBackendUrl() is injected by
  // Dataiku and is the only correct way to build these URLs - a hardcoded
  // '/api/...' resolves against the DSS root and 404s.
  function backendUrl(path) {
    if (typeof getWebAppBackendUrl === 'function') return getWebAppBackendUrl(path);
    return path;   // standalone preview outside Dataiku
  }

  var POLL_MS = 8000;
  var state = {
    tab: 'presence',
    presenceQuery: '', presenceFilter: '',
    streamQuery: '', streamFrom: '', streamTo: '', streamAll: false,
    presenceDistrict: '', streamDistrict: '', districts: [],
    rosterQuery: '',
    editingPin: null,
    voidingLogId: null,
    timer: null,
    inFlight: false
  };

  var $ = function (id) { return document.getElementById(id); };

  // Names originate from the terminal, where anyone with enrolment rights
  // types free text. Never interpolate them as HTML.
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function showError(msg) {
    var el = $('error-banner');
    if (!msg) { el.hidden = true; el.textContent = ''; return; }
    el.hidden = false;
    el.textContent = msg;
  }

  function showOk(msg) {
    var el = $('ok-banner');
    if (!msg) { el.hidden = true; el.textContent = ''; return; }
    el.hidden = false;
    el.textContent = msg;
    window.setTimeout(function () { el.hidden = true; }, 6000);
  }

  function request(path, options) {
    return fetch(backendUrl(path), Object.assign({
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
      credentials: 'same-origin'
    }, options || {})).then(function (r) {
      return r.text().then(function (body) {
        var data;
        try { data = JSON.parse(body); }
        catch (e) {
          // A Dataiku session timeout returns an HTML login page with a 200,
          // which would otherwise surface as a baffling JSON parse error.
          throw new Error(
            r.status === 200
              ? 'Backend returned HTML instead of JSON - your DSS session has probably expired. Reload the page.'
              : 'HTTP ' + r.status + ': ' + body.slice(0, 200)
          );
        }
        if (!r.ok || data.ok === false) throw new Error(data.error || ('HTTP ' + r.status));
        return data;
      });
    });
  }

  function getJSON(path) { return request(path, { method: 'GET' }); }
  function postJSON(path, payload) {
    return request(path, { method: 'POST', body: JSON.stringify(payload || {}) });
  }

  // Snowflake dates arrive as full ISO timestamps and times carry a
  // milliseconds tail, so "today" rendered as 2026-09-05T00:00:00.000Z and a
  // punch as 15:28:49.000. Both are just serialisation showing through; strip
  // it for display only, never for anything sent back.
  function fmtDate(v) {
    if (!v) return '';
    var s = String(v);
    var m = s.match(/^(\d{4}-\d{2}-\d{2})/);
    return m ? m[1] : s;
  }

  function fmtStamp(v) {
    if (!v) return '';
    return String(v).replace('T', ' ').replace(/\.\d+Z?$/, '').replace(/Z$/, '');
  }

  // One place builds the attendance filter, and both the table and the CSV
  // export read from it. If they were built separately the export would
  // eventually disagree with the screen it was launched from - and a CSV that
  // quietly differs from what an admin was looking at is worse than none.
  function streamParams() {
    return {
      q:        state.streamQuery,
      district: state.streamDistrict,
      from:     state.streamFrom,
      to:       state.streamTo,
      all:      state.streamAll ? '1' : ''
    };
  }

  function loadDistricts() {
    return getJSON('/api/districts').then(function (d) {
      state.districts = d.districts || [];
      ['presence-district', 'stream-district'].forEach(function (id) {
        var sel = $(id);
        var keep = sel.value;
        sel.innerHTML = '<option value="">All districts</option>' +
          state.districts.map(function (name) {
            return '<option value="' + esc(name) + '">' + esc(name) + '</option>';
          }).join('');
        // Preserve the selection across the refresh that rebuilds the list.
        if (keep) sel.value = keep;
      });
    });
  }

  function qs(params) {
    var parts = [];
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v !== '' && v !== null && v !== undefined && v !== false) {
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
      }
    });
    return parts.length ? ('?' + parts.join('&')) : '';
  }

  // ── Presence ────────────────────────────────────────────────────────────
  // One place decides how a state is styled and labelled, so a state added to
  // the view later shows up as itself rather than silently taking the default.
  function badgeFor(presence) {
    var map = {
      'In the Office':             'badge-in',
      'Temporarily Out of Office': 'badge-step',
      'Left the Office':           'badge-out',
      'Not in Today':              'badge-none'
    };
    var cls = map[presence] || 'badge-none';
    return '<span class="badge ' + cls + '">' + esc(presence || 'Unknown') + '</span>';
  }

  function renderPresence(data) {
    var body = $('presence-body');
    var rows = data.rows || [];
    if (state.presenceFilter) {
      rows = rows.filter(function (r) { return r.presence === state.presenceFilter; });
    }

    $('presence-count').textContent =
      rows.length + (rows.length === 1 ? ' person' : ' people');

    if (!rows.length) {
      body.innerHTML = '<tr class="row-empty"><td colspan="6">' +
        (state.presenceQuery
          ? 'Nobody matches &ldquo;' + esc(state.presenceQuery) + '&rdquo;'
          : 'No staff enrolled yet') + '</td></tr>';
      return;
    }

    body.innerHTML = rows.map(function (r) {
      var last = r.last_punch
        ? esc(r.last_punch) + ' at ' + esc(r.last_time || '')
        : '<span class="muted">no punch today</span>';
      return '<tr>' +
        '<td class="strong">' + esc(r.name) + '</td>' +
        '<td class="num">' + esc(r.sf_id) + '</td>' +
        '<td>' + esc(r.department || '—') + '</td>' +
        '<td>' + esc(r.district || '—') + '</td>' +
        '<td>' + badgeFor(r.presence) + '</td>' +
        '<td>' + last + '</td>' +
        '</tr>';
    }).join('');
  }

  // ── Attendance records ──────────────────────────────────────────────────
  function renderStream(data) {
    var body = $('stream-body');
    var rows = data.rows || [];
    $('stream-count').textContent =
      rows.length + (rows.length === 1 ? ' record' : ' records');

    if (!rows.length) {
      body.innerHTML = '<tr class="row-empty"><td colspan="7">No records match</td></tr>';
      return;
    }

    body.innerHTML = rows.map(function (r) {
      return '<tr>' +
        '<td class="num">' + esc(r.time || r.timestamp) + '</td>' +
        '<td class="strong">' + esc(r.name) + '</td>' +
        '<td class="num">' + esc(r.pin) + '</td>' +
        // The real punch type from PUNCH_TYPES, not a guess from row order.
        '<td>' + esc(r.punch_name || ('Type ' + r.punch_type)) + '</td>' +
        '<td>' + esc(r.district || '—') + '</td>' +
        '<td>' + esc(r.verify || '') + '</td>' +
        '<td><button class="btn btn-quiet btn-sm" type="button" ' +
              'data-void="' + esc(r.log_id) + '" ' +
              'data-who="' + esc(r.name) + '" ' +
              'data-when="' + esc(r.time || r.timestamp) + '">Void</button></td>' +
        '</tr>';
    }).join('');
  }

  // ── Staff ───────────────────────────────────────────────────────────────
  function renderRoster(data) {
    var body = $('roster-body');
    var rows = data.rows || [];
    $('roster-count').textContent =
      rows.length + (rows.length === 1 ? ' person' : ' people');

    if (!rows.length) {
      body.innerHTML = '<tr class="row-empty"><td colspan="6">No staff match</td></tr>';
      return;
    }

    body.innerHTML = rows.map(function (r) {
      var bio = r.template_count
        ? esc(r.template_count) + ' enrolled'
        : '<span class="badge badge-out">none</span>';
      return '<tr>' +
        '<td class="strong">' + esc(r.name) + '</td>' +
        '<td class="num">' + esc(r.pin) + '</td>' +
        '<td>' + esc(r.department || '—') + '</td>' +
        '<td>' + bio + '</td>' +
        '<td>' + esc(fmtStamp(r.last_punch_ts) || '—') + '</td>' +
        '<td><button class="btn btn-quiet btn-sm" type="button" ' +
              'data-edit="' + esc(r.pin) + '" ' +
              'data-name="' + esc(r.name) + '" ' +
              'data-dept="' + esc(r.department || '') + '">Edit</button></td>' +
        '</tr>';
    }).join('');
  }

  function renderSummary(s) {
    $('stat-in').textContent      = s.in_office_now;
    $('stat-step').textContent    = s.stepped_out_now;
    $('stat-left').textContent    = s.left_today;
    $('stat-punches').textContent = s.punches_today;
    $('stat-unseen').textContent  = s.not_seen_today;
    $('stat-present-foot').textContent = s.present_today + ' staff seen today';
    $('stat-bio-foot').textContent =
      s.total_enrolled + ' enrolled · ' + s.with_biometrics + ' with fingerprints';
    $('last-updated').textContent =
      'Report date ' + fmtDate(s.report_date) +
      (s.last_punch_ts ? ' · last punch ' + fmtStamp(s.last_punch_ts) : '') +
      (s.last_sync_at ? ' · last sync ' + fmtStamp(s.last_sync_at) : '');
  }

  function renderDevices(data) {
    var pill = $('device-pill');
    var text = $('device-text');
    var rows = data.rows || [];
    pill.className = 'pill';
    if (!rows.length) {
      pill.classList.add('pill-muted');
      text.textContent = 'no device has reported';
      return;
    }
    var newest = rows[0];
    // The backend calls this seconds_since, not seconds_since_seen. Reading
    // the wrong key gave undefined, which describeAge() rendered as "never" -
    // a live terminal reported as one that has never checked in.
    var secs = newest.seconds_since;
    // A C6 with Realtime=1 touches the server constantly. Minutes of silence
    // means the link is down, not that nobody has punched.
    if (secs === null || secs === undefined) { pill.classList.add('pill-muted'); }
    else if (secs < 300)  { pill.classList.add('pill-ok'); }
    else if (secs < 3600) { pill.classList.add('pill-warn'); }
    else                  { pill.classList.add('pill-bad'); }
    text.textContent = rows.length === 1
      ? esc(newest.device_sn) + ' · seen ' + describeAge(secs)
      : rows.length + ' devices · newest ' + describeAge(secs);
  }

  function describeAge(secs) {
    if (secs === null || secs === undefined) return 'never';
    if (secs < 90) return 'just now';
    if (secs < 3600) return Math.round(secs / 60) + ' min ago';
    if (secs < 86400) return Math.round(secs / 3600) + ' h ago';
    return Math.round(secs / 86400) + ' d ago';
  }

  // ── Loading ─────────────────────────────────────────────────────────────
  function refresh() {
    if (state.inFlight) return;
    state.inFlight = true;

    var jobs = [
      getJSON('/api/dashboard/summary').then(renderSummary),
      getJSON('/api/devices').then(renderDevices)
    ];

    if (state.tab === 'presence') {
      jobs.push(getJSON('/api/presence' + qs({
        q: state.presenceQuery, district: state.presenceDistrict
      })).then(renderPresence));
    } else if (state.tab === 'stream') {
      jobs.push(getJSON('/api/attendance/logs' + qs(streamParams())).then(renderStream));
    } else {
      jobs.push(getJSON('/api/employees' + qs({ q: state.rosterQuery })).then(renderRoster));
    }

    Promise.all(jobs)
      .then(function () { showError(''); })
      .catch(function (e) { showError(e.message || String(e)); })
      .then(function () { state.inFlight = false; });
  }

  function schedule() {
    if (state.timer) window.clearInterval(state.timer);
    if ($('auto-refresh').checked) state.timer = window.setInterval(refresh, POLL_MS);
  }

  // ── Modals ──────────────────────────────────────────────────────────────
  function openModal(id) { $(id).hidden = false; }
  function closeModal(id) { $(id).hidden = true; }

  function openEdit(pin, name, dept) {
    state.editingPin = pin;
    $('edit-sub').textContent = name + ' · SF ID ' + pin;
    // The API is the authority on splitting; this is only a convenience
    // pre-fill, and the admin corrects it. Notably it gets surname-first
    // names wrong, which is exactly why the fields are editable.
    var parts = String(name || '').split(' ');
    $('edit-first').value = parts.shift() || '';
    $('edit-last').value = parts.join(' ');
    $('edit-dept').value = dept || '';
    $('edit-gender').value = '';
    openModal('edit-modal');
    $('edit-first').focus();
  }

  function saveEdit() {
    var payload = {
      first_name: $('edit-first').value.trim(),
      last_name:  $('edit-last').value.trim(),
      department: $('edit-dept').value.trim(),
      gender:     $('edit-gender').value
    };
    if (!payload.first_name || !payload.last_name) {
      showError('First and last name are both required.');
      return;
    }
    $('edit-save').disabled = true;
    postJSON('/api/employees/' + encodeURIComponent(state.editingPin), payload)
      .then(function (r) {
        closeModal('edit-modal');
        showOk(r.note || 'Saved.');
        refresh();
      })
      .catch(function (e) { showError(e.message || String(e)); })
      .then(function () { $('edit-save').disabled = false; });
  }

  function openVoid(logId, who, when) {
    state.voidingLogId = logId;
    $('void-sub').textContent = who + ' · ' + when;
    $('void-reason').value = '';
    openModal('void-modal');
    $('void-reason').focus();
  }

  function confirmVoid() {
    var reason = $('void-reason').value.trim();
    if (!reason) { showError('A reason is required.'); return; }
    $('void-confirm').disabled = true;
    postJSON('/api/attendance/' + encodeURIComponent(state.voidingLogId) + '/void',
             { reason: reason })
      .then(function () {
        closeModal('void-modal');
        showOk('Punch voided. It is excluded from every count and can be restored.');
        refresh();
      })
      .catch(function (e) { showError(e.message || String(e)); })
      .then(function () { $('void-confirm').disabled = false; });
  }

  // ── Wiring ──────────────────────────────────────────────────────────────
  function debounce(fn, ms) {
    var t;
    return function () {
      window.clearTimeout(t);
      t = window.setTimeout(fn, ms);
    };
  }

  function init() {
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
      tab.addEventListener('click', function () {
        state.tab = tab.getAttribute('data-tab');
        Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) {
          t.classList.toggle('is-active', t === tab);
        });
        Array.prototype.forEach.call(document.querySelectorAll('.panel'), function (p) {
          p.classList.toggle('is-active', p.id === 'panel-' + state.tab);
        });
        refresh();
      });
    });

    var onPresence = debounce(function () {
      state.presenceQuery = $('presence-search').value.trim();
      refresh();
    }, 250);
    $('presence-search').addEventListener('input', onPresence);
    $('presence-filter').addEventListener('change', function () {
      state.presenceFilter = $('presence-filter').value;
      refresh();
    });

    $('stream-search').addEventListener('input', debounce(function () {
      state.streamQuery = $('stream-search').value.trim();
      refresh();
    }, 250));
    $('stream-from').addEventListener('change', function () {
      state.streamFrom = $('stream-from').value;
      refresh();
    });
    $('stream-to').addEventListener('change', function () {
      state.streamTo = $('stream-to').value;
      refresh();
    });
    $('stream-all-dates').addEventListener('change', function () {
      state.streamAll = $('stream-all-dates').checked;
      // A date range and "all dates" contradict each other. Clearing the range
      // makes the winning filter visible instead of leaving two controls set
      // and letting the backend silently pick one.
      if (state.streamAll) {
        state.streamFrom = ''; state.streamTo = '';
        $('stream-from').value = ''; $('stream-to').value = '';
      }
      refresh();
    });
    $('stream-district').addEventListener('change', function () {
      state.streamDistrict = $('stream-district').value;
      refresh();
    });
    $('presence-district').addEventListener('change', function () {
      state.presenceDistrict = $('presence-district').value;
      refresh();
    });

    // Export is a plain navigation, not fetch: the browser then handles the
    // Content-Disposition and saves the file. Fetching it would land the CSV
    // in memory with no way to hand it to the user.
    $('export-btn').addEventListener('click', function () {
      window.location.href = backendUrl('/api/attendance/export' + qs(streamParams()));
    });

    $('roster-search').addEventListener('input', debounce(function () {
      state.rosterQuery = $('roster-search').value.trim();
      refresh();
    }, 250));

    $('refresh-btn').addEventListener('click', refresh);
    $('auto-refresh').addEventListener('change', schedule);

    // Delegated, because both tables are re-rendered on every poll and
    // per-row listeners would be rebound constantly.
    document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.getAttribute) return;

      var closeId = t.getAttribute('data-close');
      if (closeId) { closeModal(closeId); return; }

      var editPin = t.getAttribute('data-edit');
      if (editPin) {
        openEdit(editPin, t.getAttribute('data-name'), t.getAttribute('data-dept'));
        return;
      }

      var voidId = t.getAttribute('data-void');
      if (voidId) {
        openVoid(voidId, t.getAttribute('data-who'), t.getAttribute('data-when'));
      }
    });

    $('edit-save').addEventListener('click', saveEdit);
    $('void-confirm').addEventListener('click', confirmVoid);

    document.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') { closeModal('edit-modal'); closeModal('void-modal'); }
    });

    loadDistricts().catch(function () { /* non-fatal: filters just stay empty */ });
    refresh();
    schedule();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
