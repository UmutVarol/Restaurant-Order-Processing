// Applies the saved (or system) colour theme before first paint to avoid a flash of the wrong theme.
(function () {
  var theme = 'light';
  try {
    var saved = JSON.parse(localStorage.getItem('sofra.theme') || 'null');
    if (saved === 'light' || saved === 'dark') theme = saved;
    else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) theme = 'dark';
  } catch (e) { /* storage unavailable */ }
  document.documentElement.setAttribute('data-theme', theme);
})();
