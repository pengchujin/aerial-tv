// Shared by the Chinese (/) and English (/en/) pages. Each page sets window.T with its own words.
(() => {
  const T = window.T;
  // The site serves the install script, the agent guide and the APKs itself (tools/deploy-site.sh),
  // since GitHub is slow or blocked in China. Previews elsewhere point at the live site.
  const onSite = location.protocol.startsWith('http') && !/github|localhost|127\.0\.0\.1/.test(location.hostname);
  const SITE = onSite ? location.origin : 'https://aerial.qust.me';
  const SCRIPT = `${SITE}/install.sh`;
  const GUIDE = SITE + T.guide;

  const $ = s => document.querySelector(s);
  const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
                  set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} } };

  // Picking a language by hand outranks the browser's language from then on.
  document.querySelectorAll('a[data-lang]').forEach(a => a.addEventListener('click', () => store.set('aerial-lang', a.dataset.lang)));

  function render() {
    const ip = $('#ip').value.trim() || T.ip;
    const env = ($('#keep').checked ? 'KEEP_ADB=1 ' : '') + `TV=${ip}`;
    const cmd = `curl -fsSL ${SCRIPT} | ${env} bash`;
    $('#cmd').textContent = cmd;
    $('#prompt').textContent = T.prompt(ip, $('#keep').checked, GUIDE);
    $('#restoreprompt').textContent = T.restore(ip, GUIDE);
    $('#manualcmd').textContent =
`adb connect ${ip}:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set app.orchard.tv APP_AUTO_START allow   # ${T.tclOnly}
adb shell am start -n app.orchard.tv/.MainActivity`;
    $('#adaptprompt').textContent = T.adapt(ip, SITE + T.adaptGuide);
  }

  $('#ip').value = store.get('aerial-ip') || '';
  $('#ip').addEventListener('input', () => { store.set('aerial-ip', $('#ip').value.trim()); render(); });
  $('#keep').addEventListener('change', render);
  render();

  // Each tab row switches only the panels that follow it, up to the next heading.
  document.querySelectorAll('.tabs').forEach(tabs => {
    const panels = [];
    for (let el = tabs.nextElementSibling; el && el.classList.contains('panel'); el = el.nextElementSibling) panels.push(el);
    tabs.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
      tabs.querySelectorAll('button').forEach(x => x.setAttribute('aria-selected', x === b ? 'true' : 'false'));
      panels.forEach(p => p.classList.toggle('on', p.dataset.panel === b.dataset.tab));
    }));
  });

  document.querySelectorAll('.copy').forEach(b => b.addEventListener('click', async () => {
    const text = document.getElementById(b.dataset.copy).textContent;
    try { await navigator.clipboard.writeText(text); }
    catch (e) {
      const t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select();
      try { document.execCommand('copy'); } catch (_) {} t.remove();
    }
    b.textContent = T.copied; clearTimeout(b.timer); b.timer = setTimeout(() => b.textContent = T.copy, 1600);
  }));

  // Before/after: the TV's own launcher on the right of the line, Aerial on the left. The line
  // follows the pointer while it is over the TV, eased so it glides rather than jumps.
  (() => {
    const box = $('#compare');
    const divider = box.querySelector('.divider');
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    let x = 0, target = 0, raf = 0, touched = false;
    const set = v => {
      x = v;
      box.style.setProperty('--x', (x * 100).toFixed(2) + '%');
      box.setAttribute('aria-valuenow', Math.round(x * 100));
      // At either end the line and its knob would hang half off the screen; let them go.
      divider.style.opacity = x > 0.03 && x < 0.97 ? 1 : 0;
      box.classList.toggle('done', x >= 0.999);
    };
    const tick = () => {
      const d = target - x;
      if (Math.abs(d) < 0.0005) { set(target); raf = 0; return; }
      set(x + d * 0.14);
      raf = requestAnimationFrame(tick);
    };
    const go = v => { target = Math.min(1, Math.max(0, v)); if (reduced) set(target); else if (!raf) raf = requestAnimationFrame(tick); };
    const fromEvent = e => { const r = box.getBoundingClientRect(); return (e.clientX - r.left) / r.width; };

    // Timed wipes for the intro and the buttons. While one runs, small mouse movements do not
    // take over; a deliberate move (more than ~30 px) does.
    let tween = 0, tweenUntil = 0, held = null;
    const ease = t => t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    const wipe = (to, duration) => {
      cancelAnimationFrame(raf); raf = 0;
      if (reduced) { set(to); target = to; return; }
      const from = x, t0 = performance.now(), id = ++tween;
      tweenUntil = t0 + duration; held = null;
      const step = now => {
        if (id !== tween) return;
        const t = Math.min(1, (now - t0) / duration);
        set(from + (to - from) * ease(t)); target = x;
        if (t < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    const follow = e => {
      if (performance.now() < tweenUntil || held) {
        held = held || { x: e.clientX };
        if (Math.abs(e.clientX - held.x) < 30) return;
        held = null; tweenUntil = 0;
      }
      tween++; touched = true; go(fromEvent(e));
    };

    // Only over the TV itself, so reaching for the buttons underneath leaves the line alone.
    box.addEventListener('pointermove', e => { if (e.pointerType === 'mouse') follow(e); });
    box.addEventListener('pointerdown', e => { tween++; tweenUntil = 0; held = null; touched = true; box.setPointerCapture(e.pointerId); go(fromEvent(e)); });
    box.addEventListener('pointermove', e => { if (e.pointerType !== 'mouse' && box.hasPointerCapture(e.pointerId)) go(fromEvent(e)); });
    box.addEventListener('keydown', e => {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { e.preventDefault(); tween++; touched = true; go(target + (e.key === 'ArrowRight' ? 0.1 : -0.1)); }
    });
    $('.replay').addEventListener('click', () => {
      touched = true;
      if (reduced) { set(0); setTimeout(() => set(1), 600); return; }
      wipe(0, 450);
      setTimeout(() => wipe(1, 2200), 750);
    });

    set(0);
    // The opening wipe takes the TV's launcher away and rests on Aerial.
    if (reduced) { set(1); return; }
    const start = () => setTimeout(() => { if (!touched) wipe(1, 2200); }, 600);
    const seen = new IntersectionObserver(es => { if (es[0].isIntersecting) { seen.disconnect(); start(); } }, { threshold: 0.5 });
    seen.observe(box);
  })();

  // GitHub stars beside the GitHub links. The API allows 60 anonymous calls an hour per IP and is
  // slow or blocked in China, so the count is kept for an hour, and when the API fails the copy
  // tools/deploy-site.sh saved with the site is used. With neither, the links stay plain.
  (() => {
    const spans = document.querySelectorAll('.stars');
    const show = n => {
      if (!(n > 0)) return;
      const short = n < 1000 ? String(n) : (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
      spans.forEach(s => { s.textContent = '★ ' + short; s.title = T.stars(n); s.setAttribute('aria-label', T.stars(n)); s.hidden = false; });
    };
    let cached = null;
    try { cached = JSON.parse(store.get('aerial-stars')); } catch (e) {}
    if (cached) show(cached.n);
    if (cached && Date.now() - cached.t < 3600e3) return;
    const get = (url, pick) => fetch(url, { signal: AbortSignal.timeout ? AbortSignal.timeout(5000) : undefined })
      .then(r => r.ok ? r.json() : Promise.reject(r.status)).then(pick)
      .then(n => n > 0 ? n : Promise.reject('no count'));
    get('https://api.github.com/repos/pengchujin/aerial-tv', j => j.stargazers_count)
      .then(n => { store.set('aerial-stars', JSON.stringify({ n, t: Date.now() })); return n; })
      .catch(() => get('/download/stars.json', j => j.stars))
      .then(show, () => {});
  })();

  const io = 'IntersectionObserver' in window ? new IntersectionObserver(es => es.forEach(e => {
    if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }), { rootMargin: '0px 0px -10% 0px' }) : null;
  document.querySelectorAll('.reveal').forEach(el => io ? io.observe(el) : el.classList.add('in'));
})();
