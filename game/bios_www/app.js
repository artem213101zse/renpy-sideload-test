function biosLog(text) {
  var log = document.getElementById("log");
  if (!log || text === undefined || text === null) {
    return;
  }
  log.textContent += String(text);
  log.scrollTop = log.scrollHeight;
}

function biosProgress(text, pct) {
  var line = document.getElementById("progressLine");
  var bar = document.getElementById("bar");
  if (line) {
    line.textContent = text ? String(text) : "";
  }
  if (!bar) {
    return;
  }
  if (pct === undefined || pct === null || pct < 0) {
    bar.style.width = "100%";
    bar.style.opacity = "0.45";
  } else {
    var n = pct;
    if (n > 100) {
      n = 100;
    }
    if (n < 0) {
      n = 0;
    }
    bar.style.opacity = "1";
    bar.style.width = n + "%";
  }
}

function callBridge(name) {
  try {
    if (!window.BiosBridge || !BiosBridge[name]) {
      biosLog("\nмост не готов");
      return;
    }
    BiosBridge[name]();
  } catch (e) {
    biosLog("\nкнопка не сработала");
  }
}
