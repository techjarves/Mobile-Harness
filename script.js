/* Mobile Harness landing — progressive enhancement. No frameworks. */
(function () {
  "use strict";

  var REPO = "techjarves/Mobile-Harness";
  var API = "https://api.github.com/repos/" + REPO + "/releases/latest";
  var FALLBACK = {
    tag: "v1.0.3",
    date: "September 2026",
    online: {
      name: "mobile-harness-online-v1.0.3.apk",
      url: "https://github.com/techjarves/Mobile-Harness/releases/download/v1.0.3/mobile-harness-online-v1.0.3.apk",
      size: 44623505
    },
    offline: {
      name: "mobile-harness-offline-v1.0.3.apk",
      url: "https://github.com/techjarves/Mobile-Harness/releases/download/v1.0.3/mobile-harness-offline-v1.0.3.apk",
      size: 818539975
    }
  };

  function fmtMB(bytes) {
    if (!bytes) return null;
    var mb = bytes / (1024 * 1024);
    return (mb >= 100 ? Math.round(mb * 10) / 10 : Math.round(mb * 10) / 10).toFixed(1) + " MB";
  }

  function fmtCount(n) {
    if (n == null) return null;
    if (n >= 1000) return (Math.round(n / 100) / 10).toFixed(1).replace(/\.0$/, "") + "k";
    return String(n);
  }

  function fmtDate(iso) {
    try {
      return new Date(iso).toLocaleDateString("en-US", { month: "long", year: "numeric" });
    } catch (e) { return null; }
  }

  function setText(id, value) {
    var el = document.getElementById(id);
    if (el && value) el.textContent = value;
  }

  function applyRelease(rel) {
    var tag = rel.tag || FALLBACK.tag;
    setText("announce-version", tag);
    setText("hero-version", tag);
    setText("inline-version", tag);
    if (rel.date) setText("release-date", "Released " + rel.date);

    var on = document.getElementById("online-btn");
    var off = document.getElementById("offline-btn");
    if (on && rel.online.url) on.href = rel.online.url;
    if (off && rel.offline.url) off.href = rel.offline.url;

    var hero = document.getElementById("hero-download");
    if (hero && rel.online.url) hero.href = rel.online.url;
    var cta = document.getElementById("cta-download");
    if (cta && rel.online.url) cta.href = rel.online.url;

    if (rel.online.size) setText("online-size", fmtMB(rel.online.size));
    if (rel.offline.size) setText("offline-size", fmtMB(rel.offline.size));
    setText("online-meta", rel.online.name);
    setText("offline-meta", rel.offline.name);
    if (rel.downloads) setText("hero-downloads", rel.downloads);
  }

  function fetchRelease() {
    applyRelease({
      tag: FALLBACK.tag, date: FALLBACK.date,
      online: FALLBACK.online, offline: FALLBACK.offline, downloads: null
    });
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, 6000);
    fetch(API, { signal: ctrl.signal, headers: { Accept: "application/vnd.github+json" } })
      .then(function (r) { if (!r.ok) throw new Error("http " + r.status); return r.json(); })
      .then(function (json) {
        var assets = json.assets || [];
        function find(re) {
          for (var i = 0; i < assets.length; i++) {
            if (re.test(assets[i].name)) return assets[i];
          }
          return null;
        }
        var onA = find(/online.*\.apk$/i);
        var offA = find(/offline.*\.apk$/i);
        var total = assets.reduce(function (s, a) { return s + (a.download_count || 0); }, 0);
        applyRelease({
          tag: json.tag_name || FALLBACK.tag,
          date: fmtDate(json.published_at) || FALLBACK.date,
          online: onA
            ? { name: onA.name, url: onA.browser_download_url, size: onA.size }
            : FALLBACK.online,
          offline: offA
            ? { name: offA.name, url: offA.browser_download_url, size: offA.size }
            : FALLBACK.offline,
          downloads: total > 50 ? fmtCount(total) + "+" : null
        });
      })
      .catch(function () { /* fallback already applied */ })
      .finally(function () { clearTimeout(timer); });
  }

  /* ---- Accessible tabs ---- */
  function initTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));
    if (!tabs.length) return;
    function activate(tab, focus) {
      tabs.forEach(function (t) {
        var selected = t === tab;
        t.classList.toggle("is-active", selected);
        t.setAttribute("aria-selected", selected ? "true" : "false");
        t.tabIndex = selected ? 0 : -1;
        var panel = document.getElementById(t.getAttribute("aria-controls"));
        if (panel) panel.hidden = !selected;
      });
      if (focus) tab.focus();
    }
    tabs.forEach(function (tab, i) {
      tab.addEventListener("click", function () { activate(tab, false); });
      tab.addEventListener("keydown", function (e) {
        var next = null;
        if (e.key === "ArrowRight") next = tabs[(i + 1) % tabs.length];
        else if (e.key === "ArrowLeft") next = tabs[(i - 1 + tabs.length) % tabs.length];
        else if (e.key === "Home") next = tabs[0];
        else if (e.key === "End") next = tabs[tabs.length - 1];
        if (next) { e.preventDefault(); activate(next, true); }
      });
    });
  }

  /* ---- Scroll reveal ---- */
  function initReveal() {
    var els = document.querySelectorAll(".reveal");
    if (!("IntersectionObserver" in window)) {
      els.forEach(function (el) { el.classList.add("is-visible"); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add("is-visible");
          io.unobserve(en.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -6% 0px" });
    els.forEach(function (el) { io.observe(el); });
  }

  /* ---- Mobile menu ---- */
  function initMenu() {
    var btn = document.getElementById("menu-btn");
    var menu = document.getElementById("mobile-menu");
    if (!btn || !menu) return;
    btn.addEventListener("click", function () {
      var open = menu.hidden;
      menu.hidden = !open;
      btn.setAttribute("aria-expanded", open ? "true" : "false");
      btn.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    });
    menu.querySelectorAll("a").forEach(function (a) {
      a.addEventListener("click", function () {
        menu.hidden = true;
        btn.setAttribute("aria-expanded", "false");
      });
    });
  }

  /* ---- Facade video (loads YouTube only on click) ---- */
  function initVideo() {
    var facade = document.getElementById("video-facade");
    if (!facade) return;
    facade.addEventListener("click", function () {
      var wrap = facade.closest(".video");
      var iframe = document.createElement("iframe");
      iframe.src = "https://www.youtube-nocookie.com/embed/QzAau52Z7yQ?autoplay=1&rel=0";
      iframe.title = "Mobile Harness walkthrough and live demo";
      iframe.allow = "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share";
      iframe.allowFullscreen = true;
      iframe.loading = "lazy";
      wrap.innerHTML = "";
      wrap.appendChild(iframe);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    fetchRelease();
    initTabs();
    initReveal();
    initMenu();
    initVideo();
  });
})();
