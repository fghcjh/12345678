/* 忆聊 · 界面逻辑。所有网络/存储都在原生层，这里只负责渲染与交互。 */
(function () {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.prototype.slice.call(document.querySelectorAll(s));

  const callState = {
    stream: null, loop: null, tick: null, guard: null, busy: false,
    frames: 0, seq: 0, t0: 0, voice: true, facing: 'environment', running: false, lines: [],
    mic: false, resumeMic: false, pip: null, darkAt: 0, framing: 'full'
  };

  const state = {
    sid: null,
    settings: null,
    character: null,
    memCount: 0,
    media: null,
    autoSend: false,
    sessions: [],
    streaming: false,
    reqId: null,
    buffer: '',
    think: '',
    cur: null,
    lastRecall: [],
    voice: 'idle',
    openSheet: null,
    pinned: true,
    query: '',
    saveTimer: null
  };

  /* ---------------- native bridge ---------------- */

  function call(name) {
    try {
      if (typeof Native === 'undefined' || !Native[name]) return null;
      return Native[name].apply(Native, Array.prototype.slice.call(arguments, 1));
    } catch (e) {
      console.error('native call failed:', name, e);
      return null;
    }
  }

  function callJson(name) {
    const r = call.apply(null, arguments);
    if (!r || typeof r !== 'string') return null;
    try { return JSON.parse(r); } catch (e) { return null; }
  }

  /* ---------------- utils ---------------- */

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function timeStr(ts) {
    const d = new Date(ts || Date.now());
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return hh + ':' + mm;
  }

  function relTime(ts) {
    const diff = Date.now() - ts;
    if (diff < 60e3) return '刚刚';
    if (diff < 3600e3) return Math.floor(diff / 60e3) + ' 分钟前';
    if (diff < 86400e3) return Math.floor(diff / 3600e3) + ' 小时前';
    if (diff < 7 * 86400e3) return Math.floor(diff / 86400e3) + ' 天前';
    const d = new Date(ts);
    return (d.getMonth() + 1) + '月' + d.getDate() + '日';
  }

  function greetWord() {
    const h = new Date().getHours();
    if (h < 5) return '夜深了';
    if (h < 9) return '早上好';
    if (h < 12) return '上午好';
    if (h < 14) return '中午好';
    if (h < 18) return '下午好';
    if (h < 23) return '晚上好';
    return '夜深了';
  }

  const ICON = {
    copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2.5"/><path d="M5 15V6a2 2 0 0 1 2-2h8"/></svg>',
    speaker: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9H4Z"/><path d="M16.5 8.5a5 5 0 0 1 0 7"/><path d="M19 6a8.5 8.5 0 0 1 0 12"/></svg>',
    trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6.5 7l1 12a1.5 1.5 0 0 0 1.5 1.4h6a1.5 1.5 0 0 0 1.5-1.4l1-12"/></svg>',
    brain: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M12 3.4l1.9 5.4 5.4 1.9-5.4 1.9-1.9 5.4-1.9-5.4L4.7 10.7l5.4-1.9L12 3.4Z"/></svg>',
    chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12.5a7.5 7.5 0 0 1-7.5 7.5H8l-4 3v-4.4A7.5 7.5 0 0 1 12.5 5 7.5 7.5 0 0 1 20 12.5Z"/></svg>'
  };

  function normalizeSettings(raw) {
    if (!raw) return {};
    if (typeof raw === 'string') {
      try { return JSON.parse(raw); } catch (e) { return {}; }
    }
    return typeof raw === 'object' ? raw : {};
  }

  function charName() {
    return (state.character && state.character.name) || '忆聊';
  }

  function charInitial() {
    return charName().slice(0, 1) || '忆';
  }

  /* ---------------- media ---------------- */

  function kindText(kind) {
    return ({ image: '图片', gif: '动图', video: '视频' })[kind] || '文件';
  }

  function fmtDur(ms) {
    const s = Math.round(ms / 100) / 10;
    return s >= 60 ? Math.floor(s / 60) + ' 分 ' + Math.round(s % 60) + ' 秒' : s + ' 秒';
  }

  function parseMeta(raw) {
    if (!raw) return null;
    if (typeof raw === 'object') return raw;
    try { return JSON.parse(raw); } catch (e) { return null; }
  }

  function visionBlock(desc) {
    const d = document.createElement('details');
    d.className = 'vision';
    const sm = document.createElement('summary');
    sm.textContent = '识别内容';
    const p = document.createElement('p');
    p.textContent = desc;
    d.appendChild(sm);
    d.appendChild(p);
    return d;
  }

  function clearAttachment() {
    state.media = null;
    $('#attachPreview').hidden = true;
    $('#attachThumb').removeAttribute('src');
    updateSendState();
  }

  /* ---------------- character card ---------------- */

  const CHAR_FIELDS = ['who', 'personality', 'worldview', 'mood', 'channels', 'schedule', 'examples', 'greeting', 'perTurn'];

  function fieldEl(key) {
    return $('#c' + key.charAt(0).toUpperCase() + key.slice(1));
  }

  function syncCharacterUI() {
    const c = state.character || {};
    $('#cardAvatar').textContent = charInitial();
    $('#cardName').textContent = charName();
    $('#cardLine').textContent = c.oneLiner || '会记住你说过的每件事';
    $$('.msg .avatar').forEach((a) => { a.textContent = charInitial(); });
  }

  function fillCharacter() {
    const c = state.character || {};
    $('#cName').value = c.name || '忆聊';
    $('#cLine').value = c.oneLiner || '';
    CHAR_FIELDS.forEach((k) => {
      const el = fieldEl(k);
      if (el) el.value = c[k] || '';
    });
    renderWorldbook(c.worldbook || []);
    updateCharPreview();
  }

  function updateCharPreview() {
    const name = ($('#cName').value || '忆聊').trim();
    $('#charAvatar').textContent = name.slice(0, 1) || '忆';
    $('#charNamePrev').textContent = name;
    $('#charLinePrev').textContent = ($('#cLine').value || '').trim() || '还没有一句话人设';
  }

  function renderWorldbook(list) {
    const box = $('#wbList');
    box.innerHTML = '';
    if (!list.length) list = [{ k: '', v: '' }];
    list.forEach((e) => box.appendChild(wbRow(e.k || '', e.v || '')));
    updateWbCount();
  }

  function wbRow(k, v) {
    const row = document.createElement('div');
    row.className = 'wb-row';
    row.innerHTML =
      '<input class="wb-key" maxlength="100" placeholder="关键词：核爆，十字路口" autocomplete="off">' +
      '<textarea class="wb-val" rows="3" maxlength="800" placeholder="要TA想起的内容：…"></textarea>' +
      '<button class="wb-del" aria-label="删除">' + ICON.trash + '</button>';
    row.querySelector('.wb-key').value = k;
    row.querySelector('.wb-val').value = v;
    row.querySelector('.wb-del').addEventListener('click', () => {
      row.remove();
      updateWbCount();
    });
    return row;
  }

  function updateWbCount() {
    let n = 0;
    $$('#wbList .wb-row').forEach((r) => {
      if (r.querySelector('.wb-key').value.trim() && r.querySelector('.wb-val').value.trim()) n++;
    });
    $('#wbCount').textContent = n;
  }

  function collectCharacter() {
    const c = { name: ($('#cName').value || '').trim() || '忆聊', oneLiner: $('#cLine').value.trim(), worldbook: [] };
    CHAR_FIELDS.forEach((k) => {
      const el = fieldEl(k);
      c[k] = el ? el.value.trim() : '';
    });
    $$('#wbList .wb-row').forEach((row) => {
      const k = row.querySelector('.wb-key').value.trim();
      const v = row.querySelector('.wb-val').value.trim();
      if (k && v) c.worldbook.push({ k: k, v: v });
    });
    return c;
  }

  function saveCharacter() {
    const payload = collectCharacter();
    const raw = call('saveCharacter', JSON.stringify(payload));
    let saved = payload;
    if (raw) {
      if (typeof raw === 'string') {
        try { saved = JSON.parse(raw); } catch (e) { saved = payload; }
      } else if (typeof raw === 'object') {
        saved = raw;
      }
    }
    state.character = saved;
    if (state.settings) state.settings.character = saved;
    syncCharacterUI();
    closeSheets();
    toast('人格卡已保存');
  }


  /* ---------------- 语音输入 ---------------- */

  let voiceTimer = null;
  let voiceStart = 0;

  function startVoiceClock() {
    voiceStart = Date.now();
    $('#voiceLabel').textContent = '正在听… 0 秒';
    clearInterval(voiceTimer);
    voiceTimer = setInterval(() => {
      const s = Math.floor((Date.now() - voiceStart) / 1000);
      $('#voiceLabel').textContent = '正在听… ' + s + ' 秒（再点麦克风结束）';
    }, 500);
  }

  function stopVoiceClock() {
    clearInterval(voiceTimer);
    voiceTimer = null;
  }

  /* ---------------- 视频通话 ---------------- */

  /* 通话页：把形象搬到画面正中，3D 的用全身取景，摄像头缩成右上角小窗 */
  /** 按当前场景套用取景：通话里可切全身/半身，聊天页固定头肩 */
  function applyCallFraming() {
    if (!AV.viewer) return;
    const inCall = $('#callScreen') && !$('#callScreen').hidden;
    if (!inCall) {
      AV.viewer.setFraming('bust');
      return;
    }
    AV.viewer.setFraming(callState.framing === 'full' ? 'full' : 'half');
  }

  function syncBodyButton() {
    const b = $('#callBody');
    if (!b) return;
    b.hidden = avCfg().kind !== 'model';   // 2D 形象没有全身/半身之分
    const isFull = callState.framing === 'full';
    b.textContent = isFull ? '🧍 全身' : '👤 半身';
    b.classList.toggle('on', isFull);
  }

  /** 把摄像头画面镜像到右上角小窗（有些 WebView 里缩小后的 video 会渲染成黑块） */
  function drawPip() {
    const cv = $('#callPip');
    const v = $('#callVideo');
    if (!cv || !v || cv.hidden || !v.videoWidth) return;
    const g = cv.getContext('2d');
    const W = cv.width, Hh = cv.height;
    const vw = v.videoWidth, vh = v.videoHeight;
    const scale = Math.max(W / vw, Hh / vh);
    const dw = vw * scale, dh = vh * scale;
    g.drawImage(v, (W - dw) / 2, (Hh - dh) / 2, dw, dh);
  }

  function startPip() {
    stopPip();
    const cv = $('#callPip');
    if (!cv) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    cv.width = Math.round(92 * dpr);
    cv.height = Math.round(122 * dpr);
    cv.hidden = false;
    callState.pip = setInterval(drawPip, 100);
    setTimeout(drawPip, 120);
  }

  function stopPip() {
    clearInterval(callState.pip);
    callState.pip = null;
    const cv = $('#callPip');
    if (cv) {
      cv.hidden = true;
      const g = cv.getContext('2d');
      if (g) g.clearRect(0, 0, cv.width, cv.height);
    }
  }

  function mountAvatarInCall() {
    const cfg = avCfg();
    const screen = $('#callScreen');
    const stage = $('#callStage');
    if (!screen || !stage || !cfg.kind) return;
    screen.classList.add('with-avatar');
    stage.hidden = false;
    startPip();
    // 画布按舞台高度算尺寸：高 = 可视高度，宽按 0.74 比例，3D 的再按这个框渲染
    const boxH = Math.max(160, (stage.clientHeight || 600) - 150 - 10);
    const boxW = Math.round(boxH * 0.74);
    if (cfg.kind === 'model') {
      const cv = $('#av3d');
      if (cv) {
        stage.appendChild(cv);
        cv.hidden = false;
        cv.style.width = boxW + 'px';
        cv.style.height = boxH + 'px';
      }
      if (AV.viewer) {
        AV.viewer.setVisible(true);
        applyCallFraming();
      }
      syncBodyButton();
      syncViewerSizeSoon();
    } else if (cfg.kind === 'made') {
      const cv = $('#avCanvas');
      if (cv) {
        stage.appendChild(cv);
        cv.hidden = false;
        cv.style.width = boxW + 'px';
        cv.style.height = boxH + 'px';
      }
    } else if (cfg.kind === 'image') {
      const img = $('#avImg');
      if (img) {
        stage.appendChild(img);
        img.hidden = false;
        img.style.width = boxW + 'px';
        img.style.height = boxH + 'px';
      }
    } else {
      screen.classList.remove('with-avatar');
    }
  }

  function unmountAvatarFromCall() {
    const screen = $('#callScreen');
    const stage = $('#callStage');
    if (screen) screen.classList.remove('with-avatar');
    if (stage) stage.hidden = true;
    // 通话时给画布写死的尺寸要清掉，否则聊天页会留个巨大的画布
    ['#av3d', '#avCanvas', '#avImg'].forEach((sel) => {
      const el = $(sel);
      if (el) {
        el.style.width = '';
        el.style.height = '';
      }
    });
    if (AV.viewer) AV.viewer.setFraming('bust');
    renderAvatarStage();
  }

  function startCall() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      toast('这台设备的 WebView 不支持实时预览，先用「拍一张」吧', 3600);
      return;
    }
    call('requestCameraPermission');
  }

  async function openCamera() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: callState.facing, width: { ideal: 1280 } },
        audio: false
      });
      callState.stream = stream;
      const vEl = $('#callVideo');
      vEl.srcObject = stream;
      const p = vEl.play && vEl.play();
      if (p && p.catch) p.catch((err) => console.warn('video play failed', err));
      $('#callScreen').hidden = false;
      callState.running = true;
      callState.frames = 0;
      callState.seq = 0;
      callState.lines = [];
      callState.busy = false;
      callState.t0 = Date.now();
      $('#callFrames').textContent = '0 帧';
      $('#callTime').textContent = '00:00';
      $('#callSub').textContent = '正在看…';
      clearInterval(callState.loop);
      clearInterval(callState.tick);
      callState.tick = setInterval(updateCallClock, 1000);
      callState.loop = setInterval(grabFrame, 3000);
      setTimeout(grabFrame, 900);
      mountAvatarInCall();
    } catch (e) {
      toast('打不开摄像头：' + ((e && e.message) || '未知原因'), 4200);
      endCall();
    }
  }

  function updateCallClock() {
    const s = Math.max(0, Math.floor((Date.now() - callState.t0) / 1000));
    $('#callTime').textContent = String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  }

  function captureFrame() {
    const v = $('#callVideo');
    if (!v || !v.videoWidth) return null;
    const w = 512;
    const h = Math.max(1, Math.round((v.videoHeight / v.videoWidth) * w));
    const c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    const g = c.getContext('2d');
    g.drawImage(v, 0, 0, w, h);
    // 顺手算一下亮度：太暗的话提示用户开灯，别让 TA 一直说“黑的”
    try {
      const d = g.getImageData(0, 0, w, h).data;
      let sum = 0, n = 0;
      for (let i = 0; i < d.length; i += 4 * 13) {
        sum += d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114;
        n++;
      }
      const luma = n ? sum / n : 0;
      if (luma < 16 && Date.now() - callState.darkAt > 20000) {
        callState.darkAt = Date.now();
        const sub = $('#callSub');
        if (sub) sub.textContent = '有点暗，TA 可能看不清——开个灯或者对着亮的地方？';
      }
    } catch (e) {
    }
    try {
      return c.toDataURL('image/jpeg', 0.6).split(',')[1];
    } catch (e) {
      return null;
    }
  }

  function grabFrame() {
    if (!callState.running || callState.busy || callState.mic) return;
    const b64 = captureFrame();
    if (!b64) return;
    callState.busy = true;
    callState.seq++;
    callState.frames++;
    $('#callFrames').textContent = callState.frames + ' 帧';
    call('talkFrame', callState.seq, b64, callState.lines.slice(-2).join(' | '));
    clearTimeout(callState.guard);
    callState.guard = setTimeout(() => { callState.busy = false; }, 12000);
  }

  function endCall() {
    callState.running = false;
    clearInterval(callState.loop);
    clearInterval(callState.tick);
    clearTimeout(callState.guard);
    if (callState.stream) {
      callState.stream.getTracks().forEach((t) => t.stop());
      callState.stream = null;
    }
    const v = $('#callVideo');
    if (v) v.srcObject = null;
    $('#callScreen').hidden = true;
    stopPip();
    unmountAvatarFromCall();
    callState.busy = false;
    callState.mic = false;
    callState.resumeMic = false;
    call('stopCallVoice');
    call('stopSpeak');
  }

  async function flipCamera() {
    callState.facing = callState.facing === 'environment' ? 'user' : 'environment';
    if (callState.stream) {
      callState.stream.getTracks().forEach((t) => t.stop());
      callState.stream = null;
    }
    await openCamera();
  }

  /* ---------------- markdown ---------------- */


  function inline(escaped) {
    const codes = [];
    let s = escaped.replace(/`([^`\n]+)`/g, (m, c) => {
      codes.push('<code>' + c + '</code>');
      return '\u0000C' + (codes.length - 1) + '\u0000';
    });
    s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" rel="noreferrer">$1</a>');
    s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[\s（(])\*([^*\n]+)\*(?=[\s，。！？）)]|$)/g, '$1<em>$2</em>');
    s = s.replace(/~~([^~\n]+)~~/g, '<del>$1</del>');
    s = s.replace(/\u0000C(\d+)\u0000/g, (m, i) => codes[+i]);
    return s;
  }

  function md(src) {
    if (!src) return '';
    let text = String(src).replace(/\r\n/g, '\n');
    const blocks = [];
    text = text.replace(/```[^\n]*\n?([\s\S]*?)```/g, (m, code) => {
      blocks.push('<pre><code>' + esc(code.replace(/\n$/, '')) + '</code></pre>');
      return '\u0000B' + (blocks.length - 1) + '\u0000';
    });
    text = esc(text);

    const out = [];
    let para = [];
    let list = null; // {type, items}
    let quote = [];

    const flushPara = () => {
      if (!para.length) return;
      out.push('<p>' + para.map(inline).join('<br>') + '</p>');
      para = [];
    };
    const flushList = () => {
      if (!list) return;
      out.push('<' + list.type + '>' + list.items.map((i) => '<li>' + inline(i) + '</li>').join('') + '</' + list.type + '>');
      list = null;
    };
    const flushQuote = () => {
      if (!quote.length) return;
      out.push('<blockquote>' + quote.map(inline).join('<br>') + '</blockquote>');
      quote = [];
    };
    const flushAll = () => { flushPara(); flushList(); flushQuote(); };

    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const line = raw.trim();

      if (/^\u0000B\d+\u0000$/.test(line)) {
        flushAll();
        out.push(blocks[+line.replace(/\u0000B|\u0000/g, '')]);
        continue;
      }
      if (!line) { flushPara(); flushList(); flushQuote(); continue; }

      const h = line.match(/^(#{1,4})\s+(.*)$/);
      if (h) {
        flushAll();
        const lv = Math.min(3, h[1].length + 1);
        out.push('<h' + lv + '>' + inline(h[2]) + '</h' + lv + '>');
        continue;
      }
      if (/^([-*_])\1{2,}$/.test(line)) { flushAll(); out.push('<hr>'); continue; }

      const ul = raw.match(/^\s*[-*+]\s+(.*)$/);
      if (ul) {
        flushPara(); flushQuote();
        if (!list || list.type !== 'ul') { flushList(); list = { type: 'ul', items: [] }; }
        list.items.push(ul[1]);
        continue;
      }
      const ol = raw.match(/^\s*\d+[.)]\s+(.*)$/);
      if (ol) {
        flushPara(); flushQuote();
        if (!list || list.type !== 'ol') { flushList(); list = { type: 'ol', items: [] }; }
        list.items.push(ol[1]);
        continue;
      }
      const q = raw.match(/^\s*>\s?(.*)$/);
      if (q) { flushPara(); flushList(); quote.push(q[1]); continue; }

      flushList(); flushQuote();
      para.push(line);
    }
    flushAll();
    return '<div class="md">' + out.join('') + '</div>';
  }

  /* ---------------- rendering ---------------- */

  const chat = $('#chat');
  const emptyEl = $('#empty');

  function nearBottom() {
    return chat.scrollHeight - chat.scrollTop - chat.clientHeight < 90;
  }

  function toBottom(force) {
    if (!force && !state.pinned) return;
    chat.scrollTop = chat.scrollHeight;
  }

  function hideEmpty() {
    emptyEl.classList.add('hidden');
  }

  function showEmptyIfNeeded() {
    const has = chat.querySelector('.msg');
    if (!has) {
      emptyEl.classList.remove('hidden');
      $('#greet').textContent = greetWord();
    }
  }

  function addMsg(role, text, ts, rawMeta) {
    hideEmpty();
    const media = parseMeta(rawMeta);
    const row = document.createElement('div');
    row.className = 'msg ' + (role === 'user' ? 'user' : 'ai');

    if (role !== 'user') {
      const av = document.createElement('div');
      av.className = 'avatar';
      av.textContent = charInitial();
      row.appendChild(av);
    }

    const main = document.createElement('div');
    main.className = 'msg-main';

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    if (role === 'user') {
      if (media && media.thumb) {
        bubble.classList.add('has-media');
        const img = document.createElement('img');
        img.className = 'bubble-img';
        img.src = media.thumb;
        img.alt = '';
        bubble.appendChild(img);
      }
      if (text) {
        const t = document.createElement('div');
        t.className = 'bubble-text';
        t.textContent = text;
        bubble.appendChild(t);
      }
    } else {
      bubble.innerHTML = md(text);
    }
    main.appendChild(bubble);

    if (role === 'user' && media && media.desc) {
      main.appendChild(visionBlock(media.desc));
    }

    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.innerHTML = '<span>' + timeStr(ts) + '</span>';
    if (role !== 'user') {
      const btn = document.createElement('button');
      btn.innerHTML = ICON.copy + '<span>复制</span>';
      btn.addEventListener('click', () => copyText(text));
      meta.appendChild(btn);

      const spk = document.createElement('button');
      spk.innerHTML = ICON.speaker + '<span>朗读</span>';
      spk.addEventListener('click', () => speak(text));
      meta.appendChild(spk);
    }
    main.appendChild(meta);

    row.appendChild(main);
    chat.appendChild(row);
    return { row, main, bubble, meta };
  }

  function assistantShell() {
    hideEmpty();
    const row = document.createElement('div');
    row.className = 'msg ai';

    const av = document.createElement('div');
    av.className = 'avatar';
    av.textContent = charInitial();
    row.appendChild(av);

    const main = document.createElement('div');
    main.className = 'msg-main';

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = '<div class="typing"><i></i><i></i><i></i></div>';

    main.appendChild(bubble);
    row.appendChild(main);
    chat.appendChild(row);
    return { row, main, bubble };
  }

  function renderStream(view) {
    const t = state.buffer;
    if (t) {
      view.bubble.innerHTML = md(t);
      const mdEl = view.bubble.querySelector('.md') || view.bubble;
      let host = mdEl.lastElementChild || mdEl;
      if (/^(UL|OL|PRE)$/.test(host.tagName)) host = host.lastElementChild || host;
      if (!view.caret) {
        view.caret = document.createElement('span');
        view.caret.className = 'caret';
      }
      host.appendChild(view.caret);
    }
    if (state.think) {
      if (!view.reason) {
        view.reason = document.createElement('div');
        view.reason.className = 'reason';
        view.main.insertBefore(view.reason, view.bubble);
      }
      view.reason.textContent = state.think;
    }
    toBottom();
  }

  let renderPending = null;
  function scheduleRender(view) {
    if (renderPending) return;
    renderPending = setTimeout(() => {
      renderPending = null;
      if (state.streaming) renderStream(view);
    }, 55);
  }

  function finalize(view, ev) {
    view.bubble.innerHTML = md(state.buffer || '（空回复）');
    const meta = document.createElement('div');
    meta.className = 'meta';
    const t = timeStr(Date.now());
    const bits = [t];
    if (ev && ev.ms) bits.push((ev.ms / 1000).toFixed(1) + 's');
    if (ev && (ev.in || ev.out)) bits.push(ev.in + '↑ ' + ev.out + '↓ tok');
    meta.innerHTML = '<span>' + bits.join(' · ') + '</span>';

    const btn = document.createElement('button');
    btn.innerHTML = ICON.copy + '<span>复制</span>';
    btn.addEventListener('click', () => copyText(state.buffer));
    meta.appendChild(btn);

    const spk = document.createElement('button');
    spk.innerHTML = ICON.speaker + '<span>朗读</span>';
    spk.addEventListener('click', () => speak(state.buffer));
    meta.appendChild(spk);

    view.main.appendChild(meta);

    if (ev && ev.recall && ev.recall.length) {
      const box = document.createElement('div');
      box.className = 'recall';
      const chip = document.createElement('button');
      chip.className = 'recall-chip';
      chip.innerHTML = ICON.brain + '<span>用到 ' + ev.recall.length + ' 条记忆</span>';
      const detail = document.createElement('div');
      detail.className = 'recall-list';
      detail.hidden = true;
      detail.innerHTML = ev.recall.map((r) =>
        '· <b>' + esc(kindLabel(r.kind)) + '</b> ' + esc(r.content)
      ).join('<br>');
      chip.addEventListener('click', () => { detail.hidden = !detail.hidden; });
      box.appendChild(chip);
      box.appendChild(detail);
      view.main.appendChild(box);
      state.lastRecall = ev.recall;
    }
  }

  function lastUserText() {
    const users = $$('.msg.user .bubble');
    return users.length ? users[users.length - 1].textContent : '';
  }

  function kindLabel(k) {
    return ({ profile: '身份', preference: '偏好', event: '事件', relationship: '关系', plan: '计划' })[k] || '其他';
  }

  function copyText(t) {
    try {
      const ta = document.createElement('textarea');
      ta.value = t;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      toast('已复制');
    } catch (e) { toast('复制失败'); }
  }

  /* ---------------- toast ---------------- */

  let toastTimer = null;
  function toast(html, ms) {
    const el = $('#toast');
    el.innerHTML = html;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), ms || 2200);
  }

  /* ---------------- sessions ---------------- */

  function refreshSessions() {
    const list = callJson('sessionsJson') || [];
    state.sessions = list;
    renderSessions();
    const mc = call('memoryCount');
    if (mc !== null) setMemCount(parseInt(mc, 10) || 0);
  }

  function renderSessions() {
    const box = $('#sessionList');
    box.innerHTML = '';
    if (!state.sessions.length) {
      box.innerHTML = '<p class="hint" style="text-align:center;margin:26px 0">还没有对话</p>';
      return;
    }
    state.sessions.forEach((s) => {
      const row = document.createElement('div');
      row.className = 'sess' + (s.id === state.sid ? ' active' : '');
      row.innerHTML =
        '<div class="sess-ico">' + ICON.chat + '</div>' +
        '<div class="sess-main"><div class="sess-title">' + esc(s.title || '新对话') + '</div>' +
        '<div class="sess-sub">' + relTime(s.updated) + ' · ' + s.count + ' 条消息</div></div>' +
        '<button class="sess-del">删除</button>';

      row.addEventListener('click', (e) => {
        if (e.target.closest('.sess-del')) return;
        openSession(s.id);
        closeSheets();
      });

      const del = row.querySelector('.sess-del');
      let armed = false, timer = null;
      del.addEventListener('click', (e) => {
        e.stopPropagation();
        if (!armed) {
          armed = true;
          del.classList.add('arm');
          del.textContent = '确认删除';
          timer = setTimeout(() => { armed = false; del.classList.remove('arm'); del.textContent = '删除'; }, 2600);
          return;
        }
        clearTimeout(timer);
        call('deleteSession', s.id);
        if (s.id === state.sid) {
          state.sid = null;
          chat.querySelectorAll('.msg').forEach((n) => n.remove());
          showEmptyIfNeeded();
        }
        refreshSessions();
        toast('对话已删除');
      });

      box.appendChild(row);
    });
  }

  function createSession() {
    const id = call('newSession');
    state.sid = id;
    return id;
  }

  function openSession(id) {
    const data = callJson('openSession', id);
    if (!data || data.error) { toast('打开对话失败'); return; }
    state.sid = id;
    state.buffer = '';
    state.think = '';
    state.streaming = false;
    state.cur = null;
    chat.querySelectorAll('.msg').forEach((n) => n.remove());
    emptyEl.classList.remove('hidden');

    (data.messages || []).forEach((m) => {
      if (m.role === 'user' || m.role === 'assistant') addMsg(m.role, m.content, m.ts, m.meta);
    });
    showEmptyIfNeeded();
    if (data.messages && data.messages.length) hideEmpty();
    if (typeof data.memCount === 'number') setMemCount(data.memCount);
    renderSessions();
    requestAnimationFrame(() => toBottom(true));
    updateBrandSub();
  }

  function newChat() {
    if (state.streaming) stopStreaming();
    const id = createSession();
    if (!id) { toast('新建对话失败'); return; }
    openSession(id);
    closeSheets();
    $('#input').focus();
  }

  function updateBrandSub() {
    const cur = state.sessions.find((s) => s.id === state.sid);
    const title = cur && cur.title && cur.title !== '新对话' ? cur.title : '新对话';
    $('#brandSub').textContent = state.memCount + ' 条记忆 · ' + title;
  }

  function setMemCount(n) {
    state.memCount = n;
    $('#memCount').textContent = n;
    $('#memSheetCount').textContent = n;
    updateBrandSub();
  }

  /* ---------------- chat flow ---------------- */

  function autoGrow() {
    const ta = $('#input');
    ta.style.height = 'auto';
    ta.style.height = Math.min(126, ta.scrollHeight) + 'px';
  }

  function updateSendState() {
    const btn = $('#btnSend');
    const has = $('#input').value.trim().length > 0 || !!state.media;
    btn.classList.toggle('streaming', state.streaming);
    btn.disabled = !state.streaming && !has;
  }

  function sendMessage() {
    if (state.streaming) { stopStreaming(); return; }
    const input = $('#input');
    const text = input.value.trim();
    const media = state.media;
    if (!text && !media) return;
    if (media && !(state.settings && state.settings.visionModel)) {
      toast('先在设置 → 视觉识别里配一个模型');
      openSheet('settings');
      return;
    }
    if (!state.sid) createSession();

    input.value = '';
    autoGrow();

    const localMeta = media ? JSON.stringify({
      kind: media.kind, thumb: media.thumb, frames: media.frames, dur: media.dur
    }) : '';
    addMsg('user', text, Date.now(), localMeta);
    updateSendState();
    state.buffer = '';
    state.think = '';
    state.streaming = true;
    state.pinned = true;

    const view = assistantShell();
    state.cur = view;
    updateSendState();
    toBottom(true);

    let reqId;
    if (media) {
      reqId = call('sendWithMedia', state.sid, text, media.id);
      clearAttachment();
    } else {
      reqId = call('send', state.sid, text);
    }
    state.reqId = reqId;
    if (!reqId) {
      state.streaming = false;
      view.bubble.textContent = '发送失败：桥接不可用。';
      updateSendState();
    }
  }

  function stopStreaming() {
    call('stop');
    state.streaming = false;
    updateSendState();
  }

  function onEvent(ev) {
    if (!ev || !ev.t) return;
    switch (ev.t) {
      case 'token':
        if (ev.id !== state.reqId) return;
        state.buffer += ev.d;
        if (state.cur) scheduleRender(state.cur);
        break;

      case 'reason':
        if (ev.id !== state.reqId) return;
        state.think += ev.d;
        if (state.cur) scheduleRender(state.cur);
        break;

      case 'done':
        if (ev.id !== state.reqId) return;
        state.streaming = false;
        if (state.cur) {
          finalize(state.cur, ev);
          state.cur = null;
        }
        if (AV.viewer && avCfg().kind === 'model' && Math.random() < 0.3) AV.viewer.play('nod');
        if (state.buffer && state.settings && state.settings.autoSpeak) {
          speak(state.buffer);
        }
        updateSendState();
        state.buffer = '';
        refreshSessions();
        toBottom();
        if (ev.stopped) toast('已停止生成');
        break;

      case 'error':
        if (ev.id && ev.id !== state.reqId) return;
        state.streaming = false;
        if (state.cur) {
          state.cur.row.classList.add('error');
          state.cur.bubble.innerHTML = md(ev.msg || '出错了');
          state.cur.main.appendChild(metaWithCopy());
          state.cur = null;
        }
        updateSendState();
        toast(esc(ev.msg || '请求失败'), 4000);
        break;

      case 'media':
        if (ev.status === 'ready') {
          state.media = ev;
          $('#attachThumb').src = ev.thumb;
          $('#attachKind').textContent = kindText(ev.kind) +
            (ev.frames > 1 ? ' · ' + ev.frames + ' 帧' : '');
          $('#attachName').textContent = ev.dur ? fmtDur(ev.dur) : '已选择';
          $('#attachPreview').hidden = false;
          updateSendState();
          if (state.autoSend && !$('#input').value.trim()
              && state.settings && state.settings.visionModel) {
            state.autoSend = false;
            $('#input').value = ev.kind === 'video' ? '你看看这段画面里有什么？' : '你看看这是什么？';
            autoGrow();
            sendMessage();
            break;
          }
          state.autoSend = false;
        } else if (ev.status === 'error') {
          state.autoSend = false;
          toast(esc(ev.msg || '读取失败'), 3200);
        } else {
          state.autoSend = false;
        }
        break;

      case 'voice':
        if (ev.state === 'recording') {
          state.voice = 'recording';
          $('#btnVoice').classList.add('rec');
          $('#voiceBar').hidden = false;
          startVoiceClock();
        } else if (ev.state === 'thinking') {
          state.voice = 'thinking';
          $('#btnVoice').classList.remove('rec');
          $('#voiceLabel').textContent = '正在转文字…';
          stopVoiceClock();
        } else if (ev.state === 'done') {
          state.voice = 'idle';
          $('#btnVoice').classList.remove('rec');
          $('#voiceBar').hidden = true;
          stopVoiceClock();
          if (ev.text) {
            $('#input').value = ev.text;
            autoGrow();
            sendMessage();
          }
        } else {
          state.voice = 'idle';
          $('#btnVoice').classList.remove('rec');
          $('#voiceBar').hidden = true;
          stopVoiceClock();
          if (ev.msg) toast(esc(ev.msg), 3200);
        }
        break;

      case 'camera':
        if (ev.granted) openCamera();
        else toast('没给相机权限，开不了视频通话', 3200);
        break;

      case 'call':
        if (ev.type === 'speech-start') {
          $('#callSub').textContent = '听到了…';
        } else if (ev.type === 'speech-end') {
          $('#callSub').textContent = '正在转文字…';
        } else if (ev.type === 'heard') {
          if (!ev.text) { $('#callSub').textContent = '没听清，再说一次？'; break; }
          const b64 = captureFrame();
          if (!b64) break;
          callState.seq++;
          callState.frames++;
          $('#callFrames').textContent = callState.frames + ' 帧';
          callState.busy = true;
          $('#callSub').textContent = '你：' + ev.text;
          if (callState.mic) call('stopCallVoice');
          call('talkAsk', callState.seq, b64, ev.text);
        } else if (ev.type === 'error') {
          toast(esc(ev.msg || '麦克风出错了'), 3200);
        }
        break;

      case 'tts':
        stopAvatarTalking();
        if (callState.resumeMic && callState.running && callState.mic) {
          callState.resumeMic = false;
          call('startCallVoice');
        }
        break;

      case 'grow':
        if (ev.traits) renderTraits(ev.traits);
        else if (state.openSheet === 'grow') renderTraits(callJson('traitsJson') || []);
        if (ev.manual) {
          toast('抽完了：新增 ' + (ev.added || 0) + ' 条，' + (ev.bumped || 0) + ' 条又出现一次'
            + (ev.activated ? '，<b>' + ev.activated + ' 条已生效</b>' : ''), 3600);
        } else if ((ev.added || 0) + (ev.bumped || 0) > 0) {
          toast('她好像有点变了：新增 ' + (ev.added || 0) + ' 条' + (ev.activated ? '，生效 ' + ev.activated + ' 条' : ''), 3200);
        }
        break;

      case 'schedule':
        if (ev.schedule) renderSchedule(ev.schedule);
        toast('作息表已按人格卡重排', 2200);
        break;

      case 'proactive':
        if (ev.text) {
          const sid = ev.sid || state.sid;
          if (sid && sid !== state.sid) {
            openSession(sid);
          }
          if (ev.text) {
            addMsg('assistant', ev.text, Date.now(), '');
            toBottom(true);
          }
          if (ev.activity) $('#brandSub').textContent = (state.memCount || 0) + ' 条记忆 · ' + ev.activity;
        }
        break;

      case 'model':
        if (ev.status === 'progress') {
          const hint = $('#avModelHint');
          if (hint) hint.innerHTML = '正在导入模型 <b>' + (ev.pct || 0) + '%</b> …';
          break;
        }
        if (ev.status === 'ready') {
          AV.draft = AV.draft || avDraftDefault();
          AV.draft.kind = 'model';
          AV.draft.modelUrl = ev.url;
          AV.draft.modelSize = ev.size || 0;
          AV.loadingUrl = '';
          const mb = ev.size ? (ev.size / 1048576) : 0;
          // 导入完立刻落盘成当前形象，重启后不会再丢
          try {
            if (!AV.draft) AV.draft = avDraftDefault();
            AV.draft.kind = 'model';
            AV.draft.modelSize = ev.size || 0;
            if (AV.draft.shown === undefined) AV.draft.shown = true;
            persistAvatar(AV.draft);
          } catch (e) {
            console.warn('auto save avatar failed', e);
          }
          const hint = $('#avModelHint');
          if (hint) {
            hint.innerHTML = '3D 模型要 <b>.vrm</b>（VRM 0.x / 1.0）或 <b>.glb</b>。网店卖的 VRChat 模型是 unitypackage，App 读不了，得先用 Unity + UniVRM 导出成 .vrm。';
          }
          fillAvatarSheet();
          toast('模型已导入（' + (mb ? mb.toFixed(1) + 'MB' : '已就绪') + '），点「保存形象」生效'
            + (mb > 200 ? '<br><span style="color:#f2b04c">这个模型挺大，加载可能慢甚至失败</span>' : ''), mb > 200 ? 5200 : 3600);
        } else if (ev.status === 'error') {
          toast('导入失败：' + esc(ev.msg || '未知原因'), 4200);
        }
        break;

      case 'anim':
        if (ev.status === 'ready') {
          const s2 = callJson('settingsJson');
          if (s2) state.settings = normalizeSettings(s2);
          refreshAnimInfo();
          toast('动作文件已导入（' + ((ev.size || 0) / 1024).toFixed(0) + 'KB），点「播放」试一下', 3600);
        } else if (ev.status === 'error') {
          toast('导入动作失败：' + esc(ev.msg || '未知原因'), 4200);
        }
        break;

      case 'avatar':
        if (ev.status === 'ready') {
          AV.draft = AV.draft || avDraftDefault();
          AV.draft.kind = 'image';
          AV.draft.pickedUrl = ev.url;
          fillAvatarSheet();
          try { persistAvatar(AV.draft); } catch (e) { }
          toast('图片已就位，已经在聊天页生效');
        } else if (ev.status === 'error') {
          toast('上传失败：' + esc(ev.msg || '未知原因'), 4000);
        }
        break;

      case 'talk':
        callState.busy = false;
        clearTimeout(callState.guard);
        if (ev.id !== callState.seq) break;
        if (ev.error) {
          $('#callSub').textContent = '看不了：' + ev.error;
          break;
        }
        $('#callSub').textContent = ev.text || '…';
        if (ev.text) {
          callState.lines.push(String(ev.text).replace(/\s+/g, ' ').slice(0, 120));
          if (callState.lines.length > 4) callState.lines.shift();
        }
        if (callState.voice && ev.text) speak(ev.text);
        if (callState.mic && callState.running) {
          callState.resumeMic = true;
          clearTimeout(callState.resumeTimer);
          callState.resumeTimer = setTimeout(() => {
            if (callState.resumeMic && callState.running && callState.mic) {
              callState.resumeMic = false;
              call('startCallVoice');
            }
          }, callState.voice ? 15000 : 2600);
        }
        break;

      case 'stage':
        if (ev.id && ev.id !== state.reqId) return;
        if (state.cur) state.cur.bubble.innerHTML = '<div class="stage">' + esc(ev.text || '处理中…') + '</div>';
        break;

      case 'mem':
        setMemCount(ev.count || (state.memCount + (ev.added || 0)));
        if (ev.items && ev.items.length) {
          toast('记住了 <b>' + ev.added + '</b> 条新记忆' + (ev.merged ? ' · 更新 ' + ev.merged + ' 条' : ''), 2600);
        }
        if (state.openSheet === 'memory') loadMemories($('#memSearch').value);
        break;

      case 'notice':
        toast(esc(ev.msg || ''));
        break;

      case 'test':
        const hint = $('#testHint');
        if (hint) {
          hint.textContent = ev.msg || '';
          hint.style.color = ev.ok ? '#a8f0c6' : '#ffc3cd';
        }
        toast(esc(ev.msg || ''), 4000);
        break;

      case 'sessions':
        refreshSessions();
        break;
    }
  }

  function metaWithCopy() {
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.innerHTML = '<span>' + timeStr(Date.now()) + '</span>';
    return meta;
  }

  window.__ev = onEvent;
  window.__back = function () {
    if (callState.running) { endCall(); return true; }
    if (state.openSheet) { closeSheets(); return true; }
    return false;
  };

  /* ---------------- 虚拟形象 ---------------- */

  const AV = {
    draft: null, timer: null, phase: Math.random() * 3400,
    speaking: false, speakTimer: null, lastKey: '', prevTimer: null, prevKey: '',
    viewer: null, viewerUrl: '', loadingUrl: '', libs3d: null, sizeTimer: null
  };

  function avCfg() {
    const a = state.settings && state.settings.avatar;
    return a && typeof a === 'object' ? a : {};
  }

  function avHeight(size) {
    return size === 'sm' ? 88 : size === 'lg' ? 168 : 124;
  }

  function avDraftDefault() {
    return {
      kind: 'made',
      rec: Object.assign(AvatarArt.defaults(), AvatarArt.PRESETS.mint),
      shown: true, size: 'md', side: 'right', talk: true
    };
  }

  /* ---- 3D 模型（VRM/glTF）：库按需加载，只有一个 WebGL 实例，画布在舞台和面板之间搬 ---- */

  function loadScriptOnce(src) {
    return new Promise((resolve, reject) => {
      const el = document.createElement('script');
      el.src = src;
      el.onload = () => resolve();
      el.onerror = () => reject(new Error('加载不了 ' + src));
      document.head.appendChild(el);
    });
  }

  function ensure3dLibs() {
    if (window.VRMViewer) return Promise.resolve();
    if (AV.libs3d) return AV.libs3d;
    AV.libs3d = loadScriptOnce('lib/three.min.js')
      .then(() => loadScriptOnce('lib/GLTFLoader.js'))
      .then(() => loadScriptOnce('lib/three-vrm.min.js'))
      .then(() => loadScriptOnce('viewer3d.js'))
      .catch((e) => { AV.libs3d = null; throw e; });
    return AV.libs3d;
  }

  function mount3d(container) {
    const cv = $('#av3d');
    if (!cv || !container) return null;
    if (cv.parentNode !== container) container.appendChild(cv);
    cv.hidden = false;
    return cv;
  }

  function syncViewerSize() {
    if (!AV.viewer) return;
    const cv = $('#av3d');
    if (!cv || cv.hidden) return;
    const r = cv.getBoundingClientRect();
    if (r.width < 20 || r.height < 20) return;
    AV.viewer.resize(Math.round(r.width), Math.round(r.height));
  }

  function syncViewerSizeSoon() {
    clearTimeout(AV.sizeTimer);
    syncViewerSize();
    AV.sizeTimer = setTimeout(syncViewerSize, 80);
    AV.sizeTimer = setTimeout(syncViewerSize, 380);  // 面板动画结束后再量一次
  }

  function activeModelUrl(forPreview) {
    if (forPreview) {
      const d = AV.draft;
      if (d && d.kind === 'model' && d.modelUrl) return d.modelUrl;
    }
    return (state.settings && state.settings.modelUrl) || '';
  }

  function showModel(container, forPreview) {
    const cv = mount3d(container);
    if (!cv) return;
    syncViewerSizeSoon();
    const url = activeModelUrl(forPreview);
    if (!url) {
      toast('没有模型文件，先上传一个 .vrm', 3200);
      return;
    }
    if (AV.viewer && AV.viewerUrl === url) {
      syncViewerSize();
      return;
    }
    if (AV.loadingUrl === url) return;
    AV.loadingUrl = url;
    const stage = $('#avatarStage');
    if (stage) stage.classList.add('loading');
    const clearLoading = () => { if (stage) stage.classList.remove('loading'); };
    ensure3dLibs()
      .then(() => {
        if (!AV.viewer) {
          AV.viewer = window.VRMViewer.create({ canvas: $('#av3d'), width: 200, height: 260 });
        }
        return AV.viewer.load(url).then(() => {
          AV.viewerUrl = url;
          syncViewerSize();
          AV.viewer.setTalking(AV.speaking);
          applyCallFraming();
          clearLoading();
        });
      })
      .catch((e) => {
        AV.loadingUrl = '';
        clearLoading();
        toast('模型没加载起来：' + ((e && e.message) || '未知原因'), 4200);
      });
  }

  function hide3d() {
    const cv = $('#av3d');
    const fig = $('#avFigure');
    if (!cv) return;
    if (fig && cv.parentNode !== fig) fig.appendChild(cv);
    cv.hidden = true;
  }

  function renderAvatarStage() {
    const st = $('#avatarStage');
    if (!st || !window.AvatarArt) return;
    const cfg = avCfg();
    const show = !!cfg.kind && cfg.shown !== false;
    st.hidden = !show;
    st.classList.toggle('side-left', cfg.side === 'left');
    if (!show) {
      const root = document.querySelector('.app');
      if (root) root.style.setProperty('--av-h', '0px');
      if (AV.viewer) AV.viewer.setVisible(false);
      stopAvatarLoop();
      return;
    }
    const h = avHeight(cfg.size);
    st.style.height = h + 'px';
    const appRoot = document.querySelector('.app');
    if (appRoot) appRoot.style.setProperty('--av-h', h + 'px');
    const cv = $('#avCanvas'), img = $('#avImg'), fig = $('#avFigure');
    if (cfg.kind === 'model') {
      cv.hidden = true;
      img.hidden = true;
      fig.classList.remove('float');
      if (AV.viewer) AV.viewer.setVisible(true);
      showModel(fig, false);
      startAvatarLoop();
      return;
    }
    if (AV.viewer) AV.viewer.setVisible(false);
    hide3d();
    if (cfg.kind === 'image') {
      cv.hidden = true;
      img.hidden = false;
      const url = (state.settings && state.settings.avatarUrl) || '';
      if (url && img.getAttribute('src') !== url) img.src = url;
      fig.classList.add('float');
    } else {
      img.hidden = true;
      cv.hidden = false;
      AvatarArt.paint(cv, cfg.rec || AvatarArt.defaults(), { blink: 0, mouth: 0 }, 200, 260);
      fig.classList.remove('float');
    }
    startAvatarLoop();
  }

  function startAvatarLoop() {
    stopAvatarLoop();
    AV.timer = setInterval(tickAvatar, 100);
  }

  function stopAvatarLoop() {
    if (AV.timer) { clearInterval(AV.timer); AV.timer = null; }
    AV.lastKey = '';
  }

  function tickAvatar() {
    const cfg = avCfg();
    if (!cfg.kind || cfg.shown === false) return;
    if (cfg.kind === 'image') return;
    const now = Date.now();
    if (cfg.kind === 'model') {
      if (AV.viewer) {
        const talk = AV.speaking && cfg.talk !== false;
        AV.viewer.setTalking(talk);
        AV.viewer.setMouth(talk ? 0.2 + Math.abs(Math.sin(now / 96)) * 0.65 : 0);
      }
      return;
    }
    const talking = AV.speaking && cfg.talk !== false;
    const blink = ((now + AV.phase) % 3600) < 130;
    const mouth = talking ? 0.2 + Math.abs(Math.sin(now / 96)) * 0.7 : 0;
    const key = (blink ? 1 : 0) + '|' + Math.round(mouth * 6);
    if (key === AV.lastKey) return;
    AV.lastKey = key;
    const cv = $('#avCanvas');
    if (cv) AvatarArt.paint(cv, cfg.rec || AvatarArt.defaults(), { blink: blink ? 0.95 : 0, mouth: mouth }, 200, 260);
  }

  function speak(text) {
    if (!text) return;
    AV.speaking = true;
    if (AV.viewer) AV.viewer.setTalking(true);
    const st = $('#avatarStage');
    if (st) st.classList.add('talking');
    clearTimeout(AV.speakTimer);
    AV.speakTimer = setTimeout(stopAvatarTalking, 1800 + Math.min(24000, String(text).length * 240));
    call('speak', text);
  }

  function stopAvatarTalking() {
    AV.speaking = false;
    if (AV.viewer) AV.viewer.setTalking(false);
    clearTimeout(AV.speakTimer);
    const st = $('#avatarStage');
    if (st) st.classList.remove('talking');
  }

  function refreshAvatarEntry() {
    const cfg = avCfg();
    const cv = $('#avChipCanvas'), img = $('#avChipImg');
    if (!cv) return;
    const name = $('#avatarName'), line = $('#avatarLine');
    if (cfg.kind === 'model') {
      img.hidden = true;
      cv.hidden = true;
      const chip = $('#avatarChip');
      if (chip) chip.classList.add('is3d');
      name.textContent = '虚拟形象 · 3D';
      line.textContent = 'VRM 模型' + (cfg.modelSize ? ' · ' + (cfg.modelSize / 1048576).toFixed(1) + 'MB' : '') + ' · 点这里换';
      return;
    }
    if (cfg.kind === 'image') {
      cv.hidden = true;
      img.hidden = false;
      let url = (state.settings && state.settings.avatarUrl) || '';
      url = url.replace('/avatar?', '/avatar-thumb?');
      img.src = url;
      name.textContent = '虚拟形象';
      line.textContent = '用的是你上传的图 · 点这里换';
    } else if (cfg.kind === 'made') {
      img.hidden = true;
      cv.hidden = false;
      const chip2 = $('#avatarChip');
      if (chip2) chip2.classList.remove('is3d');
      AvatarArt.paint(cv, cfg.rec || AvatarArt.defaults(), { blink: 0, mouth: 0 }, 40, 52);
      name.textContent = '虚拟形象';
      line.textContent = '手捏的小人 · 点这里改';
    } else {
      img.hidden = true;
      cv.hidden = false;
      const chip0 = $('#avatarChip');
      if (chip0) chip0.classList.remove('is3d');
      cv.getContext('2d').clearRect(0, 0, cv.width, cv.height);
      name.textContent = '虚拟形象';
      line.textContent = '还没有：上传一张，或自己捏一个';
    }
  }

  let avBuilt = false;

  function buildAvatarControls() {
    if (avBuilt || !window.AvatarArt) return;
    avBuilt = true;

    const pbox = $('#avPresets');
    Object.keys(AvatarArt.PRESETS).forEach((k) => {
      const p = AvatarArt.PRESETS[k];
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.preset = k;
      const c = document.createElement('canvas');
      b.appendChild(c);
      const t = document.createElement('span');
      t.textContent = p.name;
      b.appendChild(t);
      pbox.appendChild(b);
      AvatarArt.paint(c, Object.assign(AvatarArt.defaults(), p), { blink: 0, mouth: 0 }, 44, 57);
    });

    AvatarArt.HAIR_STYLES.forEach((h) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.v = h.id;
      b.textContent = h.name;
      $('#avHairStyles').appendChild(b);
    });
    AvatarArt.ACCS.forEach((a) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.v = String(a.id);
      b.textContent = a.name;
      $('#avAccs').appendChild(b);
    });
    const sw = (box, list, field) => {
      list.forEach((c) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.dataset.v = c;
        b.dataset.field = field;
        b.style.background = c;
        $(box).appendChild(b);
      });
    };
    sw('#avHairColors', AvatarArt.HAIR_COLORS, 'hair');
    sw('#avEyes', AvatarArt.EYES, 'eye');
    sw('#avSkins', AvatarArt.SKINS, 'skin');
    sw('#avCloth', AvatarArt.CLOTH, 'cloth');

    const ensureMade = () => {
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.kind = 'made';
      AV.draft.preset = '';
      if (!AV.draft.rec) AV.draft.rec = AvatarArt.defaults();
      else AV.draft.rec = Object.assign(AvatarArt.defaults(), AV.draft.rec);
      return AV.draft.rec;
    };

    $('#avPresets').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.kind = 'made';
      AV.draft.preset = b.dataset.preset;
      AV.draft.rec = Object.assign(AvatarArt.defaults(), AvatarArt.PRESETS[b.dataset.preset]);
      fillAvatarSheet();
    });

    $('#avHairStyles').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      ensureMade().hairStyle = b.dataset.v;
      fillAvatarSheet();
    });
    $('#avAccs').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      ensureMade().acc = parseInt(b.dataset.v, 10) || 0;
      fillAvatarSheet();
    });
    ['#avHairColors', '#avEyes', '#avSkins', '#avCloth'].forEach((sel) => {
      $(sel).addEventListener('click', (e) => {
        const b = e.target.closest('button');
        if (!b) return;
        ensureMade()[b.dataset.field] = b.dataset.v;
        fillAvatarSheet();
      });
    });

    $('#btnAvUpload').addEventListener('click', () => call('pickAvatar'));
    $('#btnAvUploadModel').addEventListener('click', () => {
      toast('选 .vrm 或 .glb；大模型导入要等几秒', 3000);
      call('pickAvatarModel');
    });
    $('#btnAvMake').addEventListener('click', () => {
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.kind = 'made';
      if (!AV.draft.rec) AV.draft.rec = Object.assign(AvatarArt.defaults(), AvatarArt.PRESETS.mint);
      fillAvatarSheet();
      $('#avEditor').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    });
    $('#btnAvRandom').addEventListener('click', () => {
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.kind = 'made';
      AV.draft.preset = '';
      AV.draft.rec = AvatarArt.randomRec();
      fillAvatarSheet();
      toast('换了个样子，不满意再来一次');
    });
    $('#swAvShow').addEventListener('click', () => {
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.shown = AV.draft.shown === false;
      fillAvatarSheet();
    });
    $('#swAvTalk').addEventListener('click', () => {
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.talk = AV.draft.talk === false;
      fillAvatarSheet();
    });
    $('#avSizes').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.size = b.dataset.size;
      fillAvatarSheet();
    });
    $('#avSides').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      AV.draft = AV.draft || avDraftDefault();
      AV.draft.side = b.dataset.side;
      fillAvatarSheet();
    });
    $('#btnAnimImport').addEventListener('click', () => {
      toast('选一个 .vrma 动作文件', 2600);
      call('pickAvatarAnim');
    });
    $('#btnAnimPlay').addEventListener('click', () => {
      playStoredAnim();
    });
    $('#btnAnimClear').addEventListener('click', () => {
      const b = $('#btnAnimClear');
      if (b.dataset.armed !== '1') {
        b.dataset.armed = '1';
        b.textContent = '确认清掉？';
        setTimeout(() => { b.dataset.armed = '0'; b.textContent = '清掉'; }, 3000);
        return;
      }
      b.dataset.armed = '0';
      b.textContent = '清掉';
      const raw = callJson('clearAvatarAnim');
      if (raw) state.settings = normalizeSettings(raw);
      refreshAnimInfo();
      toast('动作文件已删除');
    });
    $('#btnRefit').addEventListener('click', () => {
      if (!AV.viewer) {
        toast('先加载 3D 模型');
        return;
      }
      AV.viewer.refit();
      toast('已重新摆正站姿');
    });

    $('#btnAvSave').addEventListener('click', saveAvatarSheet);
    $('#btnAvClear').addEventListener('click', () => {
      const b = $('#btnAvClear');
      if (b.dataset.armed !== '1') {
        b.dataset.armed = '1';
        b.textContent = '确认不用了？';
        setTimeout(() => { b.dataset.armed = '0'; b.textContent = '不用形象'; }, 3000);
        return;
      }
      b.dataset.armed = '0';
      b.textContent = '不用形象';
      const raw = callJson('clearAvatar');
      if (raw) state.settings = normalizeSettings(raw);
      AV.draft = null;
      renderAvatarStage();
      refreshAvatarEntry();
      startProactiveLoop();
      closeSheets();
      toast('形象已移除');
    });
  }

  function markSel(sel, attr, val) {
    $$(sel + ' button').forEach((b) => b.classList.toggle('on', b.dataset[attr] === String(val)));
  }

  function fillAvatarSheet() {
    const d = AV.draft || avDraftDefault();
    AV.draft = d;
    const isImg = d.kind === 'image';
    const isModel = d.kind === 'model';
    const cv = $('#avPreview'), img = $('#avPreviewImg');
    cv.hidden = isImg || isModel;
    img.hidden = !isImg;
    if (isModel) {
      showModel($('#avStage'), true);
    } else if (isImg) {
      hide3d();
      img.src = d.pickedUrl || (state.settings && state.settings.avatarUrl) || '';
    } else {
      hide3d();
      AvatarArt.paint(cv, d.rec || AvatarArt.defaults(), { blink: 0, mouth: 0 }, 200, 260);
    }
    const saved = $('#avSaved');
    if (saved) {
      const c = avCfg();
      if (!c.kind) saved.textContent = '当前没有保存形象';
      else if (c.kind === 'model') saved.textContent = '当前已保存：3D 模型 · ' + (((c.modelSize || (state.settings && state.settings.modelSize) || 0) / 1048576).toFixed(1)) + 'MB';
      else if (c.kind === 'image') saved.textContent = '当前已保存：上传的图片';
      else saved.textContent = '当前已保存：手捏的小人';
    }
    const empty = $('#avEmpty');
    empty.hidden = !!avCfg().kind || isImg || isModel;
    empty.textContent = '预览 · 还没保存';

    $('#avEditor').hidden = d.kind !== 'made';
    if (d.rec) {
      markSel('#avHairStyles', 'v', d.rec.hairStyle);
      markSel('#avHairColors', 'v', d.rec.hair);
      markSel('#avEyes', 'v', d.rec.eye);
      markSel('#avSkins', 'v', d.rec.skin);
      markSel('#avCloth', 'v', d.rec.cloth);
      markSel('#avAccs', 'v', d.rec.acc || 0);
    }
    markSel('#avSizes', 'size', d.size || 'md');
    markSel('#avSides', 'side', d.side || 'right');
    markSel('#avPresets', 'preset', d.preset || '');
    $('#swAvShow').classList.toggle('on', d.shown !== false);
    $('#swAvTalk').classList.toggle('on', d.talk !== false);
  }

  function startPreviewLoop() {
    stopPreviewLoop();
    AV.prevKey = '';
    AV.prevTimer = setInterval(() => {
      const d = AV.draft;
      if (!d || d.kind === 'image' || d.kind === 'model') return;
      const blink = ((Date.now() + AV.phase) % 3600) < 130;
      const key = blink ? 'b' : 'o';
      if (key === AV.prevKey) return;
      AV.prevKey = key;
      AvatarArt.paint($('#avPreview'), d.rec || AvatarArt.defaults(), { blink: blink ? 0.95 : 0, mouth: 0 }, 200, 260);
    }, 100);
  }

  function stopPreviewLoop() {
    if (AV.prevTimer) { clearInterval(AV.prevTimer); AV.prevTimer = null; }
    AV.prevKey = '';
  }

  function persistAvatar(d) {
    if (!d || !d.kind) return false;
    const payload = {
      kind: d.kind,
      rec: d.rec || AvatarArt.defaults(),
      shown: d.shown !== false,
      size: d.size || 'md',
      side: d.side || 'right',
      talk: d.talk !== false
    };
    if (d.kind === 'model') payload.modelSize = d.modelSize || (state.settings && state.settings.modelSize) || 0;
    const raw = callJson('saveAvatar', JSON.stringify(payload));
    if (raw) state.settings = normalizeSettings(raw);
    refreshAvatarEntry();
    return true;
  }

  function saveAvatarSheet(silent) {
    const d = AV.draft;
    if (!d || !d.kind) { toast('先上传一张图，或者捏一个'); return; }
    persistAvatar(d);
    if (silent) return;
    renderAvatarStage();
    stopPreviewLoop();
    toast('形象已保存');
    closeSheets();
  }

  const AV_ACTIONS = [
    ['wave', '挥手'], ['nod', '点头'], ['shake', '摇头'], ['bow', '鞠躬'],
    ['stretch', '伸懒腰'], ['spin', '转一圈'], ['tilt', '歪头']
  ];

  function avatarPoke() {
    const fig = $('#avFigure');
    if (fig) {
      fig.classList.remove('poked');
      void fig.offsetWidth;
      fig.classList.add('poked');
      setTimeout(() => fig.classList.remove('poked'), 520);
    }
    if (AV.viewer && avCfg().kind === 'model') AV.viewer.play('poke');
  }

  /** 点在形象上：短按=戳一下，长按 0.7s=打开面板；移动时头跟着手指看 */
  function bindAvatarPointer(el) {
    if (!el || el.dataset.bound === '1') return;
    el.dataset.bound = '1';
    let timer = null, longPressed = false, moved = false, t0 = 0;
    const clear = () => { clearTimeout(timer); timer = null; };

    el.addEventListener('pointerdown', (e) => {
      longPressed = false;
      moved = false;
      t0 = Date.now();
      clear();
      timer = setTimeout(() => {
        longPressed = true;
        openAvatarSheet();
      }, 700);
    });

    el.addEventListener('pointermove', (e) => {
      if (e.buttons === 0 && e.pointerType === 'mouse') return;
      moved = true;
      if (!AV.viewer) return;
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) return;
      const nx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
      const ny = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
      AV.viewer.setPointer(nx, ny);
    });

    const finish = (e) => {
      clear();
      if (longPressed) return;
      if (Date.now() - t0 > 620) return;
      if (moved) return;
      if (e && e.preventDefault) e.preventDefault();
      avatarPoke();
    };
    el.addEventListener('pointerup', finish);
    el.addEventListener('pointercancel', clear);
    el.addEventListener('pointerleave', clear);
    el.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  function renderActionChips() {
    const box = $('#avActions');
    if (!box || box.dataset.built === '1') return;
    box.dataset.built = '1';
    AV_ACTIONS.forEach((a) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.act = a[0];
      b.textContent = a[1];
      box.appendChild(b);
    });
    box.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (!AV.viewer || !avCfg().kind || avCfg().kind === 'image') {
        toast('动作只有 3D 模型才有，先上传一个 .vrm', 3200);
        return;
      }
      AV.viewer.play(b.dataset.act);
    });
  }

  function animInfoText() {
    const sz = (state.settings && state.settings.animSize) || 0;
    if (!sz) return '还没导入动作文件。支持 VRM Animation（.vrma），只取骨骼旋转；导入后点「播放」试一次。';
    return '已导入动作文件 · ' + (sz / 1024).toFixed(0) + 'KB';
  }

  function refreshAnimInfo() {
    const el = $('#animInfo');
    if (el) el.textContent = animInfoText();
  }

  function playStoredAnim() {
    const url = (state.settings && state.settings.animUrl) || '';
    if (!url) {
      toast('还没导入 .vrma 动作文件');
      return;
    }
    if (!AV.viewer || (avCfg().kind !== 'model')) {
      toast('要先把 3D 模型立起来，动作才有地方放');
      return;
    }
    AV.viewer.playAnim(url).then((r) => {
      toast('播放中 · ' + (r.duration || 0).toFixed(1) + 's / ' + r.tracks + ' 条骨骼轨道', 2600);
    }).catch((e) => {
      toast('动作播放失败：' + ((e && e.message) || '未知原因'), 4200);
    });
  }

  /* ---------------- 插件市场 ---------------- */

  function marketTab() {
    const on = $$('#marketTabs button').find((b) => b.classList.contains('on'));
    return on ? on.dataset.tab : 'all';
  }

  function marketCard(p, installed) {
    const card = document.createElement('div');
    card.className = 'market-card' + (installed ? ' installed' : '');
    const badge = p.type === 'persona' ? '人格卡' : (p.type === 'worldbook' ? '世界书' : p.type);
    card.innerHTML = '<div class="market-top">'
      + '<span class="market-badge ' + esc(p.type || '') + '">' + esc(badge) + '</span>'
      + '<div class="market-name">' + esc(p.name || p.id || '') + '</div>'
      + '</div>'
      + '<div class="market-desc">' + esc(p.desc || '') + '</div>';
    const foot = document.createElement('div');
    foot.className = 'market-foot';
    const meta = document.createElement('div');
    meta.className = 'market-meta';
    meta.textContent = (p.author ? p.author + ' · ' : '') + (p.tag || '');
    foot.appendChild(meta);

    const btn = document.createElement('button');
    btn.className = 'mini-btn' + (installed ? ' danger' : ' accent');
    btn.textContent = installed ? (p.type === 'persona' ? '还原' : '卸载') : '安装';
    btn.addEventListener('click', () => {
      if (installed) {
        const r = callJson('uninstallPlugin', p.id);
        if (r && r.ok) {
          if (r.character) applyCharacterFromPlugin(r.character);
          toast(esc(r.message || '已卸载'));
          loadMarket();
        } else {
          toast(esc((r && r.message) || '卸载失败'));
        }
      } else {
        const r = callJson('installPlugin', p.id);
        if (r && r.ok) {
          if (r.character) applyCharacterFromPlugin(r.character);
          toast(esc(r.message || '装好了'), 2600);
          loadMarket();
        } else {
          toast(esc((r && r.message) || '安装失败'), 3200);
        }
      }
    });
    foot.appendChild(btn);
    card.appendChild(foot);
    return card;
  }

  function applyCharacterFromPlugin(c) {
    if (!c) return;
    state.character = c;
    state.settings.character = c;
    syncCharacterUI();
    if (state.openSheet === 'character') fillCharacter();
  }

  function renderMarket(data) {
    const box = $('#marketList');
    if (!box) return;
    const plugins = (data && data.plugins) || [];
    const installed = (data && data.installed) || [];
    const tab = marketTab();
    box.innerHTML = '';
    let shown = 0;
    plugins.forEach((p) => {
      const isIn = installed.indexOf(p.id) >= 0;
      if (tab === 'installed' && !isIn) return;
      if (tab === 'persona' && p.type !== 'persona') return;
      if (tab === 'worldbook' && p.type !== 'worldbook') return;
      box.appendChild(marketCard(p, isIn));
      shown++;
    });
    if (!shown) {
      box.innerHTML = '<p class="hint">这一类还没有东西。填一个自己的市场地址试试？</p>';
    }
    const line = $('#marketLine');
    if (line) line.textContent = plugins.length + ' 个插件 · 已装 ' + installed.length + ' 个';
    const url = $('#marketUrl');
    if (url && data && data.url != null) url.value = data.url;
  }

  function loadMarket() {
    const data = callJson('marketJson');
    if (data) { state.market = data; renderMarket(data); }
    else toast('市场暂时读不出来');
  }

  /* ---------------- 成长 / 主动开口 ---------------- */

  function growCfg() {
    const g = state.settings && state.settings.grow;
    return g && typeof g === 'object' ? g : { enabled: true, autoApply: true };
  }

  function proactiveCfg() {
    const p = state.settings && state.settings.proactive;
    return p && typeof p === 'object' ? p : { on: false, max: 10, start: 8, end: 24, idle: 6, count: 0 };
  }

  function traitRow(t) {
    const row = document.createElement('div');
    row.className = 'trait-row' + (t.active ? '' : ' off');
    row.innerHTML = '<span class="trait-kind">' + esc(t.kind || '性格') + '</span>'
      + '<span class="trait-text">' + esc(t.content || '') + '</span>'
      + '<span class="trait-ev">' + (t.source === 'manual' ? '手动' : '证据 ' + (t.evidence || 1)) + '</span>';
    const tog = document.createElement('button');
    tog.textContent = t.active ? '✓' : '○';
    tog.title = t.active ? '点一下先不用' : '点一下启用';
    tog.addEventListener('click', () => {
      const raw = callJson('toggleTrait', t.id);
      if (raw) renderTraits(raw);
    });
    const del = document.createElement('button');
    del.textContent = '×';
    del.addEventListener('click', () => {
      call('deleteTrait', t.id);
      renderTraits(callJson('traitsJson') || []);
    });
    row.appendChild(tog);
    row.appendChild(del);
    return row;
  }

  function renderTraits(list) {
    const box = $('#traitList');
    if (!box) return;
    const arr = Array.isArray(list) ? list : [];
    box.innerHTML = '';
    const active = arr.filter((t) => t.active);
    const rest = arr.filter((t) => !t.active);
    active.concat(rest).forEach((t) => box.appendChild(traitRow(t)));
    const hint = $('#growHint');
    if (hint) {
      hint.textContent = arr.length
        ? (active.length ? '已经在用的 ' + active.length + ' 条，剩下的还只是苗头' : '有 ' + arr.length + ' 条苗头，证据够了会自动生效')
        : '还没有。聊得多了才会慢慢有；也可以自己加一条。';
    }
    const line = $('#growCardLine');
    if (line) line.textContent = arr.length ? (active.length + ' 条长出来的 · 主动开口' ) : '让她慢慢变 · 按作息主动开口';
  }

  function renderSchedule(list) {
    const box = $('#schedGrid');
    if (!box) return;
    const arr = Array.isArray(list) && list.length ? list : (state.settings && state.settings.schedule) || [];
    const hour = new Date().getHours();
    box.innerHTML = '';
    arr.forEach((b) => {
      const cell = document.createElement('div');
      cell.className = 'sched-cell' + (hour >= b.from && hour < b.to ? ' now' : '');
      cell.innerHTML = '<div class="sched-time">' + pad2(b.from) + ':00 - ' + pad2(b.to) + ':00</div>'
        + '<div class="sched-act">' + esc(b.text || '') + '</div>';
      box.appendChild(cell);
    });
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function fillGrow() {
    const g = growCfg(), p = proactiveCfg();
    $('#swGrow').classList.toggle('on', g.enabled !== false);
    $('#swGrowAuto').classList.toggle('on', g.autoApply !== false);
    $('#swProactive').classList.toggle('on', !!p.on);
    $('#pmax').value = String(p.max || 10);
    $('#pmaxVal').textContent = p.max || 10;
    $('#pstart').value = String(p.start == null ? 8 : p.start);
    $('#pend').value = String(p.end == null ? 24 : p.end);
    $('#pidle').value = String(p.idle || 6);
    const left = Math.max(0, (p.max || 10) - (p.count || 0));
    const ph = $('#proactiveHint');
    if (ph) ph.textContent = '今天还 ' + left + ' 次';
    renderTraits(callJson('traitsJson') || []);
    renderSchedule(callJson('scheduleJson') || []);
  }

  let growTimer = null;
  function startProactiveLoop() {
    clearInterval(growTimer);
    growTimer = setInterval(() => {
      if (document.hidden) return;
      if (!state.settings || !state.settings.apiKey) return;
      call('proactiveTick');
    }, 90000);
    setTimeout(() => call('proactiveTick'), 4000);
  }

  function openAvatarSheet() {
    const cur = avCfg();
    AV.draft = cur.kind ? JSON.parse(JSON.stringify(cur)) : avDraftDefault();
    AV.draft.rec = Object.assign(AvatarArt.defaults(), AV.draft.rec || {});
    buildAvatarControls();
    renderActionChips();
    refreshAnimInfo();
    openSheet('avatar');
  }

  /* ---------------- sheets ---------------- */

  function openSheet(name) {
    closeSheets(true);
    const map = { sessions: '#sheetSessions', memory: '#sheetMemory', settings: '#sheetSettings', character: '#sheetCharacter', avatar: '#sheetAvatar', grow: '#sheetGrow', market: '#sheetMarket' };
    const el = $(map[name]);
    if (!el) return;
    state.openSheet = name;
    el.classList.add('open');
    $('#scrim').classList.add('show');
    if (name === 'memory') loadMemories('');
    if (name === 'settings') fillSettings();
    if (name === 'character') fillCharacter();
    if (name === 'sessions') renderSessions();
    if (name === 'avatar') { fillAvatarSheet(); startPreviewLoop(); }
    if (name === 'grow') { fillGrow(); }
    if (name === 'market') { loadMarket(); }
  }

  function closeSheets(silent) {
    $$('.sheet').forEach((s) => s.classList.remove('open'));
    $('#scrim').classList.remove('show');
    if (state.openSheet === 'settings') $('#sheetSettings .sheet-foot').hidden = false;
    if (state.openSheet === 'avatar') {
      stopPreviewLoop();
      renderAvatarStage();
    }
    state.openSheet = null;
    if (!silent) {
      const inp = $('#memSearch');
      if (inp) inp.value = '';
    }
  }

  /* ---------------- memories ---------------- */

  let memTimer = null;
  function loadMemories(q) {
    const list = callJson('memories', q || '') || [];
    const box = $('#memList');
    box.innerHTML = '';
    if (!list.length) {
      box.innerHTML = '<p class="hint" style="text-align:center;margin:30px 0">' +
        (q ? '没有匹配的记忆' : '还没有记忆。多聊几句，我会自己记。') + '</p>';
      return;
    }
    list.forEach((m) => box.appendChild(memRow(m)));
  }

  function memRow(m) {
    const row = document.createElement('div');
    row.className = 'mem';
    const dots = [1, 2, 3, 4, 5].map((i) => '<i class="' + (i <= m.importance ? 'on' : '') + '"></i>').join('');
    row.innerHTML =
      '<div class="mem-top"><span class="tag ' + m.kind + '">' + esc(m.label) + '</span>' +
      '<div class="dots">' + dots + '</div></div>' +
      '<div class="mem-text">' + esc(m.content) + '</div>' +
      '<div class="mem-foot">' + relTime(m.ts) + (m.uses ? ' · 被想起 ' + m.uses + ' 次' : '') + '</div>' +
      '<button class="mem-del" aria-label="删除">' + ICON.trash + '</button>';

    const del = row.querySelector('.mem-del');
    let armed = false, timer = null;
    del.addEventListener('click', () => {
      if (!armed) {
        armed = true;
        del.classList.add('arm');
        timer = setTimeout(() => { armed = false; del.classList.remove('arm'); }, 2600);
        return;
      }
      clearTimeout(timer);
      call('deleteMemory', m.id);
      row.style.opacity = '0';
      row.style.transform = 'translateX(12px)';
      row.style.transition = 'opacity .2s, transform .2s';
      setTimeout(() => {
        row.remove();
        setMemCount(Math.max(0, state.memCount - 1));
      }, 200);
      toast('记忆已删除');
    });
    return row;
  }

  /* ---------------- settings ---------------- */

  function fillSettings() {
    let s = callJson('settingsJson');
    if (!s || typeof s !== 'object') s = state.settings;
    if (!s || typeof s !== 'object') s = {};
    if (typeof s === 'string') s = normalizeSettings(s);
    $('#fKey').value = s.apiKey || '';
    $('#fBase').value = s.baseUrl || 'https://api.deepseek.com';
    $('#fModel').value = s.model || 'deepseek-chat';
    syncCharacterUI();
    $('#fEvery').value = String(s.extractEvery || 1);
    $('#fTopK').value = String(s.topK || 6);
    $('#topKVal').textContent = s.topK || 6;
    $('#fEmbBase').value = s.embBase || '';
    $('#fEmbModel').value = s.embModel || '';
    $('#fEmbKey').value = s.embKey || '';
    $('#fVisBase').value = s.visionBase || '';
    $('#fVisModel').value = s.visionModel || '';
    $('#fVisKey').value = s.visionKey || '';
    $('#fAsrBase').value = s.asrBase || '';
    $('#fAsrKey').value = s.asrKey || '';
    $('#fAsrModel').value = s.asrModel || '';
    $('#fAsrLimit').value = s.asrLimit || 200;
    setVoiceLeft('#asrLeft', s.asrLeft, s.asrLimit);
    $('#fTtsBase').value = s.ttsBase || '';
    $('#fTtsKey').value = s.ttsKey || '';
    $('#fTtsModel').value = s.ttsModel || '';
    $('#fTtsVoice').value = s.ttsVoice || '';
    $('#fTtsLimit').value = s.ttsLimit || 200;
    setVoiceLeft('#ttsLeft', s.ttsLeft, s.ttsLimit);
    setSwitch(!!s.memoryEnabled);
    $('#swSpeak').classList.toggle('on', !!s.autoSpeak);
    markModelChip(s.model);
    refreshAvatarEntry();
    const hint = $('#testHint');
    if (hint) hint.textContent = '';
  }

  function setVoiceLeft(sel, left, limit) {
    const el = $(sel);
    if (!el) return;
    const n = typeof left === 'number' ? left : (limit || 200);
    el.textContent = '今天还能 ' + n + ' 条';
    el.classList.toggle('out', n <= 0);
  }

  function markChipGroup(sel, btn) {
    $$(sel + ' button').forEach((b) => b.classList.toggle('on', b === btn));
  }

  function setSwitch(on) {
    $('#swMemory').classList.toggle('on', on);
  }

  function markModelChip(model) {
    $$('#modelChips button').forEach((b) => b.classList.toggle('on', b.dataset.model === model));
  }

  function saveSettings() {
    const payload = {
      apiKey: $('#fKey').value.trim() || (state.settings && state.settings.apiKey) || '',
      baseUrl: $('#fBase').value.trim(),
      model: $('#fModel').value.trim(),
      temperature: state.settings ? state.settings.temperature : 1.0,
      maxTokens: state.settings ? state.settings.maxTokens : 2048,
      memoryEnabled: $('#swMemory').classList.contains('on'),
      extractEvery: parseInt($('#fEvery').value, 10) || 1,
      topK: parseInt($('#fTopK').value, 10) || 6,
      embBase: $('#fEmbBase').value.trim(),
      embModel: $('#fEmbModel').value.trim(),
      embKey: $('#fEmbKey').value.trim(),
      visionBase: $('#fVisBase').value.trim(),
      visionModel: $('#fVisModel').value.trim(),
      visionKey: $('#fVisKey').value.trim(),
      autoSpeak: $('#swSpeak').classList.contains('on'),
      asrBase: $('#fAsrBase').value.trim(),
      asrKey: $('#fAsrKey').value.trim(),
      asrModel: $('#fAsrModel').value.trim(),
      asrLimit: parseInt($('#fAsrLimit').value, 10) || 200,
      ttsBase: $('#fTtsBase').value.trim(),
      ttsKey: $('#fTtsKey').value.trim(),
      ttsModel: $('#fTtsModel').value.trim(),
      ttsVoice: $('#fTtsVoice').value.trim(),
      ttsLimit: parseInt($('#fTtsLimit').value, 10) || 200
    };
    const r = callJson('saveSettings', JSON.stringify(payload));
    if (r) state.settings = normalizeSettings(r);
    const s = state.settings;
    $('#keyBanner').hidden = !!(s && s.apiKey);
    updateBrandSub();
    toast('设置已保存');
    closeSheets();
  }

  /* ---------------- events ---------------- */

  function bindStatic() {
    $$('#growTabs button').forEach((b) => b.addEventListener('click', () => {
      $$('#growTabs button').forEach((x) => x.classList.toggle('on', x === b));
      const tab = b.dataset.tab;
      $('#paneGrow').hidden = tab !== 'grow';
      $('#paneDaily').hidden = tab !== 'daily';
    }));
    $('#swGrow').addEventListener('click', () => $('#swGrow').classList.toggle('on'));
    $('#swGrowAuto').addEventListener('click', () => $('#swGrowAuto').classList.toggle('on'));
    $('#swProactive').addEventListener('click', () => $('#swProactive').classList.toggle('on'));
    $('#pmax').addEventListener('input', (e) => { $('#pmaxVal').textContent = e.target.value; });
    $('#btnTraitAdd').addEventListener('click', () => {
      const text = $('#traitText').value.trim();
      if (!text) { toast('先写一句'); return; }
      const raw = callJson('addTrait', $('#traitKind').value, text);
      if (raw) renderTraits(raw);
      $('#traitText').value = '';
      toast('加上了，已经写进人格');
    });
    $('#btnGrowNow').addEventListener('click', () => {
      toast('让她回想一下最近的对话…');
      call('growNow');
    });
    $('#btnGenSchedule').addEventListener('click', () => {
      toast('正在按人格卡重排作息…');
      call('genSchedule');
    });
    $('#btnGrowSave').addEventListener('click', () => {
      call('saveGrowSettings', JSON.stringify({
        enabled: $('#swGrow').classList.contains('on'),
        autoApply: $('#swGrowAuto').classList.contains('on')
      }));
      call('saveProactive', JSON.stringify({
        on: $('#swProactive').classList.contains('on'),
        max: parseInt($('#pmax').value, 10) || 10,
        start: parseInt($('#pstart').value, 10) || 0,
        end: parseInt($('#pend').value, 10) || 24,
        idle: parseInt($('#pidle').value, 10) || 6
      }));
      const s2 = callJson('settingsJson');
      if (s2) state.settings = normalizeSettings(s2);
      toast('已保存');
      closeSheets();
    });

    $('#btnSend').addEventListener('click', () => {
      if (state.streaming) { stopStreaming(); return; }
      sendMessage();
    });

    $('#input').addEventListener('input', () => { autoGrow(); updateSendState(); });
    $('#input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        if (!state.streaming) sendMessage();
      }
    });
    $('#input').addEventListener('focus', () => setTimeout(() => toBottom(true), 260));

    $('#btnSessions').addEventListener('click', () => openSheet('sessions'));
    $('#btnAttach').addEventListener('click', (e) => {
      e.stopPropagation();
      if (state.streaming) { toast('等这轮说完再发'); return; }
      const menu = $('#attachMenu');
      menu.hidden = !menu.hidden;
    });
    $$('#attachMenu button').forEach((b) => b.addEventListener('click', (e) => {
      e.stopPropagation();
      $('#attachMenu').hidden = true;
      const act = b.dataset.act;
      if (act === 'call') {
        startCall();
        return;
      }
      if (act === 'gallery') {
        call('pickMedia');
      } else {
        state.autoSend = true;
        call('pickCamera', act === 'video' ? 'video' : 'photo');
      }
    }));
    document.addEventListener('click', () => {
      const m = $('#attachMenu');
      if (m && !m.hidden) m.hidden = true;
    });
    $('#callBody').addEventListener('click', () => {
      callState.framing = callState.framing === 'full' ? 'half' : 'full';
      applyCallFraming();
      syncBodyButton();
      toast(callState.framing === 'full' ? '切到全身' : '切到半身', 1400);
    });
    $('#callEnd').addEventListener('click', endCall);
    $('#callFlip').addEventListener('click', flipCamera);
    $('#callMic').addEventListener('click', () => {
      if (!callState.running) return;
      callState.mic = !callState.mic;
      $('#callMic').classList.toggle('on', callState.mic);
      $('#callMic').textContent = callState.mic ? '🎙 正在听' : '🎙 语音对话';
      if (callState.mic) {
        call('startCallVoice');
        $('#callSub').textContent = '我在听，直接说话就行';
      } else {
        callState.resumeMic = false;
        call('stopCallVoice');
        $('#callSub').textContent = '语音已关，继续看画面';
      }
    });
    $('#callVoice').addEventListener('click', () => {
      callState.voice = !callState.voice;
      $('#callVoice').classList.toggle('on', callState.voice);
      $('#callVoice').textContent = callState.voice ? '🔊 朗读' : '🔇 静音';
      if (!callState.voice) call('stopSpeak');
    });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden && callState.running) endCall();
      if (!document.hidden) setTimeout(() => call('proactiveTick'), 1500);
    });
    $('#btnVoice').addEventListener('click', () => {
      if (state.streaming) { toast('等这轮说完再发'); return; }
      if (state.voice === 'recording') { call('stopVoice'); return; }
      if (state.voice === 'thinking') return;
      call('startVoice');
    });
    $('#voiceCancel').addEventListener('click', () => call('cancelVoice'));
    $('#swSpeak').addEventListener('click', () => {
      $('#swSpeak').classList.toggle('on');
    });
    $('#attachRemove').addEventListener('click', () => {
      if (state.media) call('clearMedia', state.media.id);
      clearAttachment();
    });
    $$('#asrChips button').forEach((b) => b.addEventListener('click', () => {
      const p = ASR_PRESETS[b.dataset.preset];
      if (!p) return;
      $('#fAsrBase').value = p.base;
      $('#fAsrModel').value = p.model;
      markChipGroup('#asrChips', b);
      toast('听：' + b.textContent + (p.base ? '，记得填 Key' : '（用内置接口）'));
    }));
    $$('#ttsChips button').forEach((b) => b.addEventListener('click', () => {
      const p = TTS_PRESETS[b.dataset.preset];
      if (!p) return;
      $('#fTtsBase').value = p.base;
      $('#fTtsModel').value = p.model;
      $('#fTtsVoice').value = p.voice;
      markChipGroup('#ttsChips', b);
      toast('说：' + b.textContent + (p.base ? '，记得填 Key' : '（用系统 TTS）'));
    }));
    $$('#visionChips button').forEach((b) => b.addEventListener('click', () => {
      $('#fVisBase').value = b.dataset.base;
      $('#fVisModel').value = b.dataset.model;
      toast('已填入 ' + b.dataset.model + '，别忘了填 Key');
    }));
    $('#btnCharacter').addEventListener('click', () => openSheet('character'));
    $('#openCharacter').addEventListener('click', () => openSheet('character'));
    $('#openGrow').addEventListener('click', () => openSheet('grow'));
    $('#openMarket').addEventListener('click', () => openSheet('market'));
    $$('#marketTabs button').forEach((b) => b.addEventListener('click', () => {
      $$('#marketTabs button').forEach((x) => x.classList.toggle('on', x === b));
      renderMarket(state.market);
    }));
    $('#btnMarketRefresh').addEventListener('click', () => {
      toast('正在拉取市场…');
      loadMarket();
    });
    $('#btnMarketUrl').addEventListener('click', () => {
      const url = $('#marketUrl').value.trim();
      const data = callJson('setMarketUrl', url);
      state.market = data;
      renderMarket(data);
      toast(url ? '地址已保存，正在用这个市场' : '已切回内置目录');
    });
    $('#openAvatar').addEventListener('click', openAvatarSheet);
    bindAvatarPointer($('#avFigure'));
    bindAvatarPointer($('#callStage'));
    $('#btnSaveChar').addEventListener('click', saveCharacter);
    $('#wbAdd').addEventListener('click', () => {
      $('#wbList').appendChild(wbRow('', ''));
      updateWbCount();
    });
    $('#wbList').addEventListener('input', updateWbCount);
    ['#cName', '#cLine'].forEach((sel) => $(sel).addEventListener('input', updateCharPreview));
    $('#btnMemory').addEventListener('click', () => openSheet('memory'));
    $('#btnSettings').addEventListener('click', () => openSheet('settings'));
    $('#scrim').addEventListener('click', () => closeSheets());
    $$('[data-close]').forEach((b) => b.addEventListener('click', () => closeSheets()));

    $('#btnNewChat').addEventListener('click', newChat);
    $('#keyBannerBtn').addEventListener('click', () => openSheet('settings'));

    $('#btnSave').addEventListener('click', saveSettings);
    $('#btnTest').addEventListener('click', () => {
      toast('正在测试连接…');
      call('testConnection');
    });
    $('#btnReindex').addEventListener('click', () => { call('reindexMemories'); toast('正在重建向量…'); });
    $('#btnWipe').addEventListener('click', () => {
      const b = $('#btnWipe');
      if (b.dataset.armed !== '1') {
        b.dataset.armed = '1';
        b.textContent = '确认清空？';
        setTimeout(() => { b.dataset.armed = '0'; b.textContent = '清空全部'; }, 3000);
        return;
      }
      call('clearAll');
      state.sid = null;
      state.sessions = [];
      chat.querySelectorAll('.msg').forEach((n) => n.remove());
      showEmptyIfNeeded();
      setMemCount(0);
      refreshSessions();
      b.dataset.armed = '0';
      b.textContent = '清空全部';
      toast('数据已清空');
    });
    $('#btnExport').addEventListener('click', () => {
      const raw = call('exportJson');
      if (!raw) { toast('导出失败'); return; }
      copyText(raw);
      toast('数据已复制到剪贴板');
    });

    $('#swMemory').addEventListener('click', () => {
      setSwitch(!$('#swMemory').classList.contains('on'));
    });
    $('#fTopK').addEventListener('input', (e) => { $('#topKVal').textContent = e.target.value; });
    $$('#modelChips button').forEach((b) => b.addEventListener('click', () => {
      $('#fModel').value = b.dataset.model;
      markModelChip(b.dataset.model);
    }));

    $('#toggleKey').addEventListener('click', () => {
      const inp = $('#fKey');
      const on = inp.type === 'password';
      inp.type = on ? 'text' : 'password';
      $('#toggleKey').classList.toggle('on', on);
    });

    let memDebounce = null;
    $('#memSearch').addEventListener('input', (e) => {
      clearTimeout(memDebounce);
      const v = e.target.value;
      memDebounce = setTimeout(() => loadMemories(v), 180);
    });

    $$('#chips .chip').forEach((c) => c.addEventListener('click', () => {
      $('#input').value = c.dataset.q;
      autoGrow();
      updateSendState();
      sendMessage();
    }));

    chat.addEventListener('scroll', () => {
      state.pinned = nearBottom();
      $('#scrollDown').classList.toggle('show', !state.pinned && !!chat.querySelector('.msg'));
    });
    $('#scrollDown').addEventListener('click', () => { state.pinned = true; toBottom(true); });

    window.addEventListener('resize', () => {
      if (state.pinned) toBottom(true);
    });
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', () => {
        if (state.pinned) setTimeout(() => toBottom(true), 60);
      });
    }
  }

  /* ---------------- boot ---------------- */

  function boot() {
    bindStatic();
    $('#greet').textContent = greetWord();

    const b = callJson('bootstrap');
    if (b) {
      state.settings = normalizeSettings(b.settings);
      state.character = (state.settings && state.settings.character) || {};
      state.sessions = b.sessions || [];
      setMemCount(b.memCount || 0);
      $('#keyBanner').hidden = !!b.hasKey;
      syncCharacterUI();
      renderAvatarStage();
      refreshAvatarEntry();
      startProactiveLoop();
    } else {
      $('#brandSub').textContent = '未连接原生层';
    }

    if (state.sessions.length) {
      openSession(state.sessions[0].id);
    } else {
      const id = createSession();
      if (id) openSession(id);
    }

    // 后台被叫醒时生成的消息，在这里补上
    try {
      const pend = callJson('pendingEvents');
      if (Array.isArray(pend) && pend.length) {
        pend.forEach((ev) => {
          if (ev && ev.t === 'proactive') onEvent(ev);
        });
      }
    } catch (e) {
    }

    updateSendState();
    setTimeout(() => toBottom(true), 50);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
