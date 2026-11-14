/*
 * 阅读模式：从页面里挑一块「最像正文」的容器，铺成全屏阅读层。
 * 不追求 Instapaper 那种排版质量，先把导航/侧栏/广告挡掉、正文放大、背景压暗。
 * 由 MainActivity 用 evaluateJavascript 注入；再调一次就是退出。
 */
(function () {
  try {
    if (window.__kbReaderOpen) {
      var old = document.getElementById('__kb_reader');
      if (old && old.parentNode) old.parentNode.removeChild(old);
      window.__kbReaderOpen = false;
      document.documentElement.style.overflow = '';
      return 'off';
    }

    var sel = 'article,main,[role=main],.article,.post,.content,#content,.markdown-body,.entry-content';
    var cands = document.querySelectorAll(sel);
    var best = null, bestLen = 0;
    for (var i = 0; i < cands.length; i++) {
      var l = (cands[i].innerText || '').replace(/\s+/g, '').length;
      if (l > bestLen) { bestLen = l; best = cands[i]; }
    }
    if (!best || bestLen < 200) {
      // 没有语义标签就按「谁的 <p> 文字最多」来选
      var ps = document.querySelectorAll('p');
      var score = new Map();
      for (var j = 0; j < ps.length; j++) {
        var p = ps[j], k = p.parentNode;
        score.set(k, (score.get(k) || 0) + (p.innerText || '').length);
      }
      var bk = null, bc = 0;
      score.forEach(function (v, key) { if (v > bc) { bc = v; bk = key; } });
      if (bk && bc > 150) { best = bk; bestLen = bc; }
    }
    if (!best) {
      alert('这个页面没有可提取的正文');
      return 'none';
    }

    var title = (document.title || '').replace(/</g, '&lt;');
    var html = best.innerHTML;
    var wrap = document.createElement('div');
    wrap.id = '__kb_reader';
    // 阅读偏好跟着页面走：字号与主题调过一次，本次会话里再进阅读模式还是这个设置
    var prefs = window.__kbReaderPrefs || (window.__kbReaderPrefs = { scale: 1, dark: true });
    var btnStyle = 'background:#1c222b;color:#e6e9f0;border:1px solid #2a3442;' +
      'border-radius:999px;padding:.5vw .9vw;font-size:1.15vw;margin-left:.35vw;min-width:4vw;min-height:4vw';
    wrap.setAttribute('style', 'position:fixed;inset:0;z-index:2147483647;background:#0f1216;' +
      'color:#e6e9f0;overflow:auto;-webkit-overflow-scrolling:touch;');
    wrap.innerHTML =
      '<div id="__kb_reader_inner" style="max-width:70vw;margin:0 auto;padding:1.4vw 1.4vw 3.5vw;' +
      'font-size:' + (1.25 * prefs.scale).toFixed(3) + 'vw;line-height:1.9;' +
      'font-family:-apple-system,\'Microsoft YaHei\',sans-serif">' +
      '<div style="display:flex;align-items:center;justify-content:space-between;' +
      'font-size:1.1vw;color:#aeb9c6;margin-bottom:1vw">' +
      '<span>阅读模式</span>' +
      '<span style="display:flex;align-items:center">' +
      '<button id="__kb_reader_smaller" style="' + btnStyle + '">A-</button>' +
      '<button id="__kb_reader_bigger" style="' + btnStyle + '">A+</button>' +
      '<button id="__kb_reader_theme" style="' + btnStyle + '">' + (prefs.dark ? '日间' : '夜间') + '</button>' +
      '<button id="__kb_reader_close" style="' + btnStyle + '">退出</button>' +
      '</span></div>' +
      '<h1 style="font-size:2vw;line-height:1.35;margin:0 0 1vw;color:#f2f4f8">' + title + '</h1>' +
      '<div id="__kb_reader_body">' + html + '</div></div>';
    if (!prefs.dark) wrap.classList.add('__kb-reader-light');
    document.body.appendChild(wrap);

    // 正文开头常带站点 logo / 小图标，抠进阅读层会叠在标题上。只收拾正文最上面那一段里的，
    // 正文中间的配图哪怕小也得留着。
    try {
      var bodyEl = wrap.querySelector('#__kb_reader_body');
      var bodyTop = bodyEl.getBoundingClientRect().top;
      var lead = bodyEl.querySelectorAll('img, svg');
      for (var k = 0; k < lead.length && k < 4; k++) {
        var r0 = lead[k].getBoundingClientRect();
        if (r0.height < 96 && (r0.top - bodyTop) < 160) lead[k].style.display = 'none';
      }
      // 正文自带的 h1 和阅读层标题重复时，删掉正文那一个
      var firstH = bodyEl.querySelector('h1, h2');
      if (firstH && document.title &&
          firstH.textContent.replace(/\s+/g, '') === document.title.replace(/\s+/g, '')) {
        firstH.parentNode.removeChild(firstH);
      }
    } catch (e2) { /* 忽略 */ }
    // 正文图片懒加载：长文一次铺完太吃内存，滚到哪张再取哪张；坏图直接收掉不留白框
    try {
      var bodyImgs = wrap.querySelectorAll('#__kb_reader_body img');
      for (var m = 0; m < bodyImgs.length; m++) {
        bodyImgs[m].setAttribute('loading', 'lazy');
        bodyImgs[m].style.maxWidth = '100%';
        bodyImgs[m].style.height = 'auto';
        bodyImgs[m].onerror = function () { this.style.display = 'none'; };
      }
    } catch (e3) { /* 忽略 */ }
    var st = document.createElement('style');
    st.textContent =
      '#__kb_reader img{max-width:100%;height:auto;border-radius:8px}' +
      '#__kb_reader a{color:#6fa8ff}' +
      '#__kb_reader p{margin:0 0 1.1em}' +
      '#__kb_reader h2,#__kb_reader h3{color:#f2f4f8;margin:1.4em 0 .6em}' +
      '#__kb_reader pre{background:#161b22;padding:14px;border-radius:8px;overflow:auto}' +
      '#__kb_reader pre code{font:1.05vw/1.6 ui-monospace,Consolas,monospace;color:#c9d1d9;background:none;padding:0}' +
      '#__kb_reader :not(pre)>code{background:#161b22;color:#e6e9f0;padding:1px 6px;border-radius:5px;font-size:.9em}' +
      '#__kb_reader blockquote{border-left:3px solid #2a3442;margin:1em 0;padding:.2em 1em;color:#9aa6b4}' +
      '#__kb_reader.__kb-reader-light{background:#fafafa!important;color:#1c1f26!important}' +
      '#__kb_reader.__kb-reader-light h1,#__kb_reader.__kb-reader-light h2,#__kb_reader.__kb-reader-light h3{color:#111!important}' +
      '#__kb_reader.__kb-reader-light pre{background:#f1f2f4!important}' +
      '#__kb_reader.__kb-reader-light pre code{color:#222!important}' +
      '#__kb_reader.__kb-reader-light :not(pre)>code{background:#eef0f3!important;color:#222!important}' +
      '#__kb_reader.__kb-reader-light a{color:#0b62d6!important}' +
      '#__kb_reader.__kb-reader-light blockquote{color:#5a6472!important;border-left-color:#d7dbe0!important}';
    wrap.appendChild(st);

    function closeReader() {
      if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
      window.__kbReaderOpen = false;
      document.documentElement.style.overflow = '';
    }
    var bSmaller = document.getElementById('__kb_reader_smaller');
    var bBigger = document.getElementById('__kb_reader_bigger');
    var bTheme = document.getElementById('__kb_reader_theme');
    function applyReaderScale() {
      var inner = document.getElementById('__kb_reader_inner');
      if (inner) inner.style.fontSize = (1.25 * prefs.scale).toFixed(3) + 'vw';
    }
    if (bSmaller) bSmaller.onclick = function () {
      prefs.scale = Math.max(0.85, Math.round((prefs.scale - 0.15) * 100) / 100);
      applyReaderScale();
    };
    if (bBigger) bBigger.onclick = function () {
      prefs.scale = Math.min(1.8, Math.round((prefs.scale + 0.15) * 100) / 100);
      applyReaderScale();
    };
    if (bTheme) bTheme.onclick = function () {
      prefs.dark = !prefs.dark;
      wrap.classList.toggle('__kb-reader-light', !prefs.dark);
      bTheme.textContent = prefs.dark ? '日间' : '夜间';
    };
    document.getElementById('__kb_reader_close').onclick = closeReader;
    // 兜底：点阅读层的空白处也能退出（有些页面的 button 会被自己的脚本吃掉）
    wrap.addEventListener('click', function (e) {
      if (e.target === wrap) closeReader();
    });
    window.__kbReaderOpen = true;
    document.documentElement.style.overflow = 'hidden';
    return 'on';
  } catch (e) {
    return 'err:' + e;
  }
})();
