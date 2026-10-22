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
    wrap.setAttribute('style', 'position:fixed;inset:0;z-index:2147483647;background:#0f1216;' +
      'color:#e6e9f0;overflow:auto;-webkit-overflow-scrolling:touch;');
    wrap.innerHTML =
      '<div style="max-width:860px;margin:0 auto;padding:36px 36px 90px;' +
      'font:19px/1.9 -apple-system,\'Microsoft YaHei\',sans-serif">' +
      '<div style="display:flex;align-items:center;justify-content:space-between;' +
      'font-size:15px;color:#8b95a5;margin-bottom:22px">' +
      '<span>阅读模式</span>' +
      '<button id="__kb_reader_close" style="background:#1c222b;color:#e6e9f0;' +
      'border:1px solid #2a3442;border-radius:999px;padding:6px 18px;font-size:15px">退出</button>' +
      '</div>' +
      '<h1 style="font-size:30px;line-height:1.35;margin:0 0 20px;color:#f2f4f8">' + title + '</h1>' +
      '<div id="__kb_reader_body">' + html + '</div></div>';
    document.body.appendChild(wrap);

    var st = document.createElement('style');
    st.textContent =
      '#__kb_reader img{max-width:100%;height:auto}' +
      '#__kb_reader a{color:#6fa8ff}' +
      '#__kb_reader p{margin:0 0 1.1em}' +
      '#__kb_reader h2,#__kb_reader h3{color:#f2f4f8;margin:1.4em 0 .6em}' +
      '#__kb_reader pre{background:#161b22;padding:14px;border-radius:8px;overflow:auto}' +
      '#__kb_reader blockquote{border-left:3px solid #2a3442;margin:1em 0;padding:.2em 1em;color:#9aa6b4}';
    wrap.appendChild(st);

    function closeReader() {
      if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
      window.__kbReaderOpen = false;
      document.documentElement.style.overflow = '';
    }
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
