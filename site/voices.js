// The reviews page: each platform's posts lie in a stack. Clicking, swiping or the arrows send the
// top card to the back (or bring the back one to the top). Every card keeps its place as data-p,
// 0 on top, and the CSS turns that into the fanned stack.
(() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const OUT = reduced ? 160 : 200;   // how long a card stays thrown out before it tucks in

  document.querySelectorAll('.deck-col').forEach(col => {
    const deck = col.querySelector('.deck');
    const cards = [...deck.querySelectorAll('.vcard')];
    const count = col.querySelector('.deck-count');
    const n = cards.length;
    let top = 0, pending = null;

    const layout = () => {
      cards.forEach((c, i) => {
        const p = (i - top + n) % n;
        c.dataset.p = Math.min(p, 3);
        c.style.zIndex = n - p;
        // Only the top card's links are reachable; the ones behind are just a picture.
        c.inert = p !== 0;
        c.setAttribute('aria-hidden', p !== 0);
      });
      if (count) count.textContent = `${top + 1} / ${n}`;
    };

    // A second press while a card is still out finishes the first move at once, so rapid
    // presses step through the stack instead of queuing up.
    const flush = () => { if (pending) { clearTimeout(pending.t); pending.run(); } };
    const later = run => { pending = { run: () => { pending = null; run(); }, t: setTimeout(() => pending && pending.run(), OUT) }; };

    const next = (dir = 'r') => {
      if (n < 2) return;
      flush();
      const c = cards[top];
      c.classList.remove('dragging'); c.style.transform = '';
      c.classList.add('out-' + dir);
      later(() => { c.classList.remove('out-' + dir); top = (top + 1) % n; layout(); });
    };
    const prev = () => {
      if (n < 2) return;
      flush();
      // The back card slides out to the side while still behind, then comes forward on top.
      const c = cards[(top - 1 + n) % n];
      c.classList.add('out-l');
      later(() => { top = (top - 1 + n) % n; layout(); c.classList.remove('out-l'); });
    };

    col.querySelector('.next').addEventListener('click', () => next());
    col.querySelector('.prev').addEventListener('click', prev);
    deck.addEventListener('keydown', e => {
      if (e.key === 'ArrowRight') { e.preventDefault(); next(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); prev(); }
    });

    // Drag the top card sideways; a far enough or quick enough throw sends it to the back.
    let drag = null;
    deck.addEventListener('pointerdown', e => {
      if (e.button !== 0 || e.target.closest('a')) return;
      const c = cards[top];
      if (!c.contains(e.target)) return;
      flush();
      drag = { c, id: e.pointerId, x: e.clientX, y: e.clientY, t: performance.now(), dx: 0, moved: false };
    });
    deck.addEventListener('pointermove', e => {
      if (!drag || e.pointerId !== drag.id) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (!drag.moved) {
        if (Math.abs(dx) < 6) return;
        // Mostly vertical: the page is scrolling, leave the card alone.
        if (Math.abs(dy) > Math.abs(dx)) { drag = null; return; }
        drag.moved = true;
        drag.c.classList.add('dragging');
        deck.setPointerCapture(e.pointerId);
      }
      drag.dx = dx;
      drag.c.style.transform = `translateX(${dx}px) rotate(${dx / 24}deg)`;
    });
    const release = e => {
      if (!drag || e.pointerId !== drag.id) return;
      const { c, dx, moved, t } = drag;
      drag = null;
      if (!moved) { if (e.type === 'pointerup') next(); return; }
      const velocity = Math.abs(dx) / (performance.now() - t);
      if (Math.abs(dx) > 90 || velocity > 0.11) { next(dx < 0 ? 'l' : 'r'); return; }
      c.classList.remove('dragging'); c.style.transform = '';
    };
    deck.addEventListener('pointerup', release);
    deck.addEventListener('pointercancel', release);

    layout();
  });
})();
