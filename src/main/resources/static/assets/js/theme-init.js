// Applies the saved (or system) colour theme before first paint to avoid a flash of the wrong theme.
// Precedence: the user's saved choice, then a theme already set by an embedding host, then the OS setting.
(function () {
  var root = document.documentElement;
  var theme = root.getAttribute('data-theme');
  try {
    var saved = JSON.parse(localStorage.getItem('sofra.theme') || 'null');
    if (saved === 'light' || saved === 'dark') theme = saved;
  } catch (e) { /* storage unavailable */ }
  if (theme !== 'light' && theme !== 'dark') {
    theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  root.setAttribute('data-theme', theme);
})();
