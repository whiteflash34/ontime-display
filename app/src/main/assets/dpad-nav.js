// D-pad navigation for Ontime on Fire TV.
// Web pages only move focus with Tab; this makes the remote's arrows move focus
// to the nearest focusable element in that direction, and shows where focus is.
(function () {
  if (window.__ontimeTvNav) return;
  window.__ontimeTvNav = true;
  console.log('OntimeTV: d-pad navigation ready');

  var style = document.createElement('style');
  style.textContent =
    ':focus { outline: 3px solid #FFB84D !important; outline-offset: 2px !important; }';
  (document.head || document.documentElement).appendChild(style);

  var FOCUSABLE =
    'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), ' +
    'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), ' +
    '[role="button"], [role="menuitem"], [role="option"], [role="tab"], [role="switch"], [role="checkbox"]';

  var DIRS = {
    ArrowUp: { x: 0, y: -1 },
    ArrowDown: { x: 0, y: 1 },
    ArrowLeft: { x: -1, y: 0 },
    ArrowRight: { x: 1, y: 0 }
  };

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.pointerEvents !== 'none';
  }

  // When a dialog (like the navigation menu) is open, stay inside it.
  function scope() {
    var dialogs = document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"]');
    for (var i = dialogs.length - 1; i >= 0; i--) {
      if (visible(dialogs[i])) return dialogs[i];
    }
    return document;
  }

  // Used by the app's Back button: close an open menu/dialog before leaving the page.
  window.__ontimeTvHasDialog = function () {
    return scope() !== document;
  };

  function candidates(root) {
    return Array.prototype.filter.call(root.querySelectorAll(FOCUSABLE), visible);
  }

  function center(r) {
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  // Nearest element from point `a` in direction `dir`; with no direction, nearest overall.
  function best(a, list, dir, exclude) {
    var pick = null;
    var pickScore = Infinity;
    list.forEach(function (el) {
      if (exclude && (el === exclude || el.contains(exclude))) return;
      var b = center(el.getBoundingClientRect());
      var dx = b.x - a.x;
      var dy = b.y - a.y;
      var score;
      if (dir) {
        var along = dx * dir.x + dy * dir.y; // distance in the pressed direction
        if (along <= 1) return;
        var across = Math.abs(dx * dir.y) + Math.abs(dy * dir.x); // sideways drift
        score = along + across * 2;
      } else {
        score = Math.abs(dx) + Math.abs(dy);
      }
      if (score < pickScore) {
        pickScore = score;
        pick = el;
      }
    });
    return pick;
  }

  // Where focus last was, so we can carry on from there if that element disappears
  // (e.g. the Countdown "Add" button is replaced by the list it opens).
  var lastPoint = null;
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target.getBoundingClientRect) {
      lastPoint = center(e.target.getBoundingClientRect());
    }
  }, true);

  function isTextField(el) {
    if (!el) return false;
    if (el.tagName === 'TEXTAREA' || el.isContentEditable) return true;
    if (el.tagName !== 'INPUT') return false;
    return !/^(button|checkbox|radio|submit|reset|range|color|file|image)$/i.test(el.type);
  }

  // Widgets that use the arrow keys themselves.
  var ARROW_WIDGETS =
    'select, [role="listbox"], [role="slider"], [role="spinbutton"], [role="radiogroup"], ' +
    '[role="menu"], [role="combobox"], [role="tree"], [role="grid"]';

  function usesArrows(el, dir) {
    if (!el || !el.closest) return false;
    if (isTextField(el)) return dir.x !== 0; // left/right move the text cursor
    return !!el.closest(ARROW_WIDGETS);
  }

  // Capture phase: some Ontime panels stop arrow keys from bubbling, so we look first.
  window.addEventListener('keydown', function (e) {
    var dir = DIRS[e.key];
    if (!dir || e.altKey || e.ctrlKey || e.metaKey) return;

    var active = document.activeElement;
    if (usesArrows(active, dir)) return;

    var root = scope();
    var list = candidates(root);
    if (!list.length) return;

    var current = active && active !== document.body && root.contains(active) && visible(active) ? active : null;
    var next;
    if (current) {
      next = best(center(current.getBoundingClientRect()), list, dir, current);
    } else if (lastPoint) {
      // Focus was lost: continue from where it was, in the pressed direction if possible.
      next = best(lastPoint, list, dir) || best(lastPoint, list, null);
    } else {
      next = list[0];
    }
    if (!next) return; // nothing that way: let the page scroll

    e.preventDefault();
    e.stopPropagation();
    next.focus();
    if (next.scrollIntoView) next.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, true);

  // ---- Select button: avoid double activation --------------------------------
  // Fire TV's WebView turns Select (Enter) on a focused element into a click as well.
  // Ontime's custom buttons (e.g. Countdown items) already react to Enter, so the
  // extra click toggles them straight back. If the element changed in response to
  // the Enter key, drop the click that follows. Native buttons and links are left alone.
  var NATIVE_ACTIVATABLE = 'a[href], button, input, select, textarea, summary, label';
  var pendingEnter = null;

  window.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    var el = document.activeElement;
    if (!el || el === document.body || !el.matches || el.matches(NATIVE_ACTIVATABLE)) {
      pendingEnter = null;
      return;
    }
    pendingEnter = { el: el, html: el.outerHTML, time: Date.now() };
  }, true);

  window.addEventListener('click', function (e) {
    var p = pendingEnter;
    pendingEnter = null;
    if (!p || Date.now() - p.time > 1500) return;
    if (e.target !== p.el && !p.el.contains(e.target)) return;
    if (p.el.outerHTML !== p.html) {
      // The page already handled the Enter key; this click would undo it.
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }, true);
})();
