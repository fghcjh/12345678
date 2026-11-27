/* 忆聊 · 虚拟形象：程序化绘制的小人（不依赖任何图片资源）
   一行代码画一张半身像：AvatarArt.draw(ctx, w, h, recipe, {blink, mouth}) */
(function (global) {
  'use strict';

  var HAIR_STYLES = [
    { id: 'short', name: '短发' },
    { id: 'long', name: '长发' },
    { id: 'twin', name: '双马尾' },
    { id: 'bun', name: '丸子头' },
    { id: 'wolf', name: '碎短发' }
  ];
  var HAIR_COLORS = ['#3c4157', '#6b4632', '#c08a4e', '#e7d3a1', '#9a6ad0', '#4a7fd0', '#d7637f', '#aeb8c9'];
  var SKINS = ['#f8ddc9', '#f2cbb2', '#dfae91', '#b98a6c'];
  var EYES = ['#4f74a8', '#6b4b8a', '#3f7a63', '#8a5a3f', '#b85552', '#5b6272'];
  var CLOTH = ['#2b3450', '#3c3c5e', '#4b3042', '#26414c', '#3b2f20', '#20263a'];
  var ACCS = [{ id: 0, name: '无' }, { id: 1, name: '眼镜' }, { id: 2, name: '猫耳' }, { id: 3, name: '耳机' }, { id: 4, name: '发卡' }];

  /* 五官位置（设计坐标系 200x260） */
  var EYE_Y = 117, BROW_Y = 99.5, NOSE_Y = 131, MOUTH_Y = 144, BLUSH_Y = 133;

  /* ---------------- 颜色工具 ---------------- */

  function hex2rgb(h) {
    h = String(h || '#888888').replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function rgb2hex(r, g, b) {
    var f = function (v) { return ('0' + Math.max(0, Math.min(255, Math.round(v))).toString(16)).slice(-2); };
    return '#' + f(r) + f(g) + f(b);
  }
  /** amt>0 变亮（0..1），amt<0 变暗 */
  function mix(hex, amt) {
    var c = hex2rgb(hex);
    var t = amt >= 0 ? 255 : 0, k = Math.abs(amt);
    return rgb2hex(c[0] + (t - c[0]) * k, c[1] + (t - c[1]) * k, c[2] + (t - c[2]) * k);
  }
  function rgba(hex, a) {
    var c = hex2rgb(hex);
    return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a + ')';
  }

  /* ---------------- 形状工具 ---------------- */

  function roundRect(ctx, x, y, w, h, r) {
    var rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }

  function facePath(ctx) {
    ctx.beginPath();
    ctx.moveTo(57, 94);
    ctx.bezierCurveTo(59, 62, 75, 47, 100, 47);
    ctx.bezierCurveTo(125, 47, 141, 62, 143, 94);
    ctx.bezierCurveTo(144, 117, 134, 140, 118, 155);
    ctx.bezierCurveTo(112, 161, 88, 161, 82, 155);
    ctx.bezierCurveTo(66, 140, 56, 117, 57, 94);
    ctx.closePath();
  }

  /* ---------------- 各部件 ---------------- */

  function drawCloth(ctx, rec) {
    var base = rec.cloth;
    var g = ctx.createLinearGradient(0, 182, 0, 260);
    g.addColorStop(0, mix(base, 0.16));
    g.addColorStop(0.55, base);
    g.addColorStop(1, mix(base, -0.28));
    // 肩线：中间低（脖子处）、两侧抬起来再垂下去，别做成圆顶
    ctx.beginPath();
    ctx.moveTo(-8, 262);
    ctx.bezierCurveTo(-4, 220, 22, 198, 58, 188);
    ctx.quadraticCurveTo(82, 180, 100, 180);
    ctx.quadraticCurveTo(118, 180, 142, 188);
    ctx.bezierCurveTo(178, 198, 204, 220, 208, 262);
    ctx.closePath();
    ctx.fillStyle = g;
    ctx.fill();

    // 领口露出的皮肤
    ctx.beginPath();
    ctx.moveTo(84, 177);
    ctx.quadraticCurveTo(100, 202, 116, 177);
    ctx.closePath();
    ctx.fillStyle = mix(rec.skin, -0.1);
    ctx.fill();

    // 领边
    ctx.beginPath();
    ctx.moveTo(82, 177);
    ctx.quadraticCurveTo(100, 206, 118, 177);
    ctx.strokeStyle = rgba(mix(base, 0.45), 0.5);
    ctx.lineWidth = 2;
    ctx.stroke();

    // 肩部阴影
    var s = ctx.createLinearGradient(130, 190, 208, 246);
    s.addColorStop(0, 'rgba(0,0,0,0)');
    s.addColorStop(1, 'rgba(0,0,0,.3)');
    ctx.beginPath();
    ctx.moveTo(142, 188);
    ctx.bezierCurveTo(178, 198, 204, 220, 208, 262);
    ctx.lineTo(164, 262);
    ctx.bezierCurveTo(162, 226, 152, 198, 142, 188);
    ctx.closePath();
    ctx.fillStyle = s;
    ctx.fill();
  }

  function drawNeck(ctx, rec) {
    var sk = rec.skin;
    ctx.fillStyle = mix(sk, -0.13);
    roundRect(ctx, 83, 148, 34, 40, 13);
    ctx.fill();
    var g = ctx.createLinearGradient(0, 148, 0, 180);
    g.addColorStop(0, 'rgba(84,46,36,.42)');
    g.addColorStop(1, 'rgba(84,46,36,0)');
    ctx.fillStyle = g;
    roundRect(ctx, 83, 148, 34, 30, 13);
    ctx.fill();
  }

  function drawEars(ctx, rec) {
    var sk = rec.skin;
    ctx.fillStyle = mix(sk, -0.04);
    ctx.beginPath();
    ctx.ellipse(55, 116, 7.5, 11, 0.12, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.ellipse(145, 116, 7.5, 11, -0.12, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = rgba(mix(sk, -0.4), 0.45);
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    ctx.arc(55, 116, 3.6, -1.2, 1.6);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(145, 116, 3.6, Math.PI - 1.6, Math.PI + 1.2);
    ctx.stroke();
  }

  function drawFace(ctx, rec) {
    var sk = rec.skin;
    var g = ctx.createLinearGradient(60, 54, 136, 160);
    g.addColorStop(0, mix(sk, 0.16));
    g.addColorStop(0.45, sk);
    g.addColorStop(1, mix(sk, -0.1));
    facePath(ctx);
    ctx.fillStyle = g;
    ctx.fill();

    // 下巴以下更暗一点
    var sh = ctx.createLinearGradient(0, 132, 0, 160);
    sh.addColorStop(0, 'rgba(120,70,55,0)');
    sh.addColorStop(1, 'rgba(120,70,55,.13)');
    ctx.save();
    facePath(ctx);
    ctx.clip();
    ctx.fillStyle = sh;
    ctx.fillRect(50, 120, 100, 50);
    ctx.restore();

    if (rec.blush !== false) {
      [70, 130].forEach(function (x) {
        var b = ctx.createRadialGradient(x, BLUSH_Y, 1, x, BLUSH_Y, 15);
        b.addColorStop(0, 'rgba(244,138,155,.4)');
        b.addColorStop(1, 'rgba(244,138,155,0)');
        ctx.fillStyle = b;
        ctx.beginPath();
        ctx.ellipse(x, BLUSH_Y, 15, 9.5, 0, 0, Math.PI * 2);
        ctx.fill();
      });
    }
  }

  function drawEye(ctx, cx, cy, w, h, open, iris) {
    var hh = Math.max(0.9, h * Math.max(0.04, open));
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx, cy, w, hh, 0, 0, Math.PI * 2);
    ctx.fillStyle = '#fdfdff';
    ctx.fill();
    ctx.clip();

    var sg = ctx.createLinearGradient(0, cy - hh, 0, cy + hh);
    sg.addColorStop(0, 'rgba(110,122,156,.4)');
    sg.addColorStop(0.5, 'rgba(110,122,156,0)');
    ctx.fillStyle = sg;
    ctx.fillRect(cx - w, cy - hh, w * 2, hh * 2);

    var ir = Math.min(w * 0.78, hh * 1.02);
    var ig = ctx.createRadialGradient(cx, cy - ir * 0.3, ir * 0.12, cx, cy, ir);
    ig.addColorStop(0, mix(iris, 0.5));
    ig.addColorStop(0.55, mix(iris, 0.08));
    ig.addColorStop(1, mix(iris, -0.46));
    ctx.beginPath();
    ctx.arc(cx, cy + h * 0.06, ir, 0, Math.PI * 2);
    ctx.fillStyle = ig;
    ctx.fill();

    ctx.beginPath();
    ctx.arc(cx, cy + h * 0.06, ir * 0.44, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(22,24,36,.9)';
    ctx.fill();

    ctx.beginPath();
    ctx.arc(cx - ir * 0.32, cy - h * 0.24, ir * 0.29, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,255,255,.95)';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + ir * 0.3, cy + h * 0.34, ir * 0.14, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,255,255,.62)';
    ctx.fill();
    ctx.restore();

    // 上眼线
    ctx.beginPath();
    ctx.ellipse(cx, cy, w, hh, 0, Math.PI * 1.02, Math.PI * 1.98);
    ctx.strokeStyle = 'rgba(38,30,46,.94)';
    ctx.lineWidth = 2.5;
    ctx.lineCap = 'round';
    ctx.stroke();

    // 眼尾
    if (open > 0.45) {
      ctx.beginPath();
      ctx.moveTo(cx + w * 0.9, cy - hh * 0.5);
      ctx.lineTo(cx + w * 1.3, cy - hh * 1.1);
      ctx.lineWidth = 1.9;
      ctx.stroke();
    }
  }

  function drawBrow(ctx, cx, cy, w, hair) {
    ctx.beginPath();
    ctx.moveTo(cx - w / 2, cy + 2.2);
    ctx.quadraticCurveTo(cx, cy - 3.4, cx + w / 2, cy + 1.2);
    ctx.strokeStyle = rgba(mix(hair, -0.22), 0.8);
    ctx.lineWidth = 2.3;
    ctx.lineCap = 'round';
    ctx.stroke();
  }

  function drawMouth(ctx, open, smile) {
    var x = 100, y = MOUTH_Y;
    if (open < 0.07) {
      ctx.beginPath();
      ctx.moveTo(x - 7, y - 1);
      ctx.quadraticCurveTo(x, y + 4.6 + smile, x + 7, y - 1);
      ctx.strokeStyle = 'rgba(150,80,92,.85)';
      ctx.lineWidth = 2.2;
      ctx.lineCap = 'round';
      ctx.stroke();
      return;
    }
    var ow = 6.5 + open * 5, oh = 1.6 + open * 8;
    ctx.beginPath();
    ctx.ellipse(x, y + 1.2, ow, oh, 0, 0, Math.PI * 2);
    ctx.fillStyle = '#93414f';
    ctx.fill();
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(x, y + 1.2, ow, oh, 0, 0, Math.PI * 2);
    ctx.clip();
    ctx.beginPath();
    ctx.ellipse(x, y + 1.2 + oh * 0.6, ow * 0.66, oh * 0.5, 0, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(214,120,130,.9)';
    ctx.fill();
    ctx.fillStyle = 'rgba(255,250,250,.92)';
    ctx.fillRect(x - ow, y - oh * 0.32, ow * 2, 2.4);
    ctx.restore();
  }

  /* ---------- 头发 ---------- */

  function hairSilhouette(ctx, rec) {
    var c = rec.hair, st = rec.hairStyle;
    var dark = mix(c, -0.32), light = mix(c, 0.2);
    var g;

    if (st === 'long') {
      g = ctx.createLinearGradient(46, 40, 158, 250);
      g.addColorStop(0, light);
      g.addColorStop(0.45, c);
      g.addColorStop(1, dark);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.moveTo(44, 252);
      ctx.bezierCurveTo(34, 188, 40, 84, 100, 38);
      ctx.bezierCurveTo(160, 84, 166, 188, 156, 252);
      ctx.bezierCurveTo(140, 260, 118, 260, 100, 255);
      ctx.bezierCurveTo(82, 260, 60, 260, 44, 252);
      ctx.closePath();
      ctx.fill();
      ctx.strokeStyle = rgba(mix(c, 0.34), 0.3);
      ctx.lineWidth = 1.3;
      for (var i = 0; i < 3; i++) {
        var off = (i - 1) * 15;
        ctx.beginPath();
        ctx.moveTo(100 + off * 0.55, 74);
        ctx.quadraticCurveTo(74 + off, 150, 72 + off, 236);
        ctx.stroke();
      }
    } else if (st === 'twin') {
      g = ctx.createLinearGradient(40, 40, 160, 200);
      g.addColorStop(0, light);
      g.addColorStop(1, dark);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.ellipse(100, 98, 55, 66, 0, 0, Math.PI * 2);
      ctx.fill();
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.moveTo(100 + s * 44, 78);
        ctx.bezierCurveTo(100 + s * 80, 92, 100 + s * 90, 146, 100 + s * 68, 198);
        ctx.bezierCurveTo(100 + s * 62, 208, 100 + s * 48, 206, 100 + s * 47, 192);
        ctx.bezierCurveTo(100 + s * 58, 150, 100 + s * 54, 108, 100 + s * 40, 88);
        ctx.closePath();
        ctx.fill();
      });
      // 皮筋
      ctx.fillStyle = '#f2789b';
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.ellipse(100 + s * 45, 84, 6.5, 8, s * 0.35, 0, Math.PI * 2);
        ctx.fill();
      });
      ctx.strokeStyle = 'rgba(255,255,255,.35)';
      ctx.lineWidth = 1.2;
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.arc(100 + s * 45, 84, 4, 0, Math.PI * 2);
        ctx.stroke();
      });
    } else if (st === 'bun') {
      g = ctx.createLinearGradient(50, 40, 150, 170);
      g.addColorStop(0, light);
      g.addColorStop(1, dark);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.ellipse(100, 100, 52, 60, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.beginPath();
      ctx.arc(100, 38, 20, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = rgba(dark, 0.5);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(100, 38, 12.5, -0.7, 2.5);
      ctx.stroke();
    } else if (st === 'wolf') {
      g = ctx.createLinearGradient(50, 40, 150, 160);
      g.addColorStop(0, light);
      g.addColorStop(1, dark);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.ellipse(100, 96, 53, 58, 0, 0, Math.PI * 2);
      ctx.fill();
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.moveTo(100 + s * 50, 76);
        ctx.lineTo(100 + s * 59, 134);
        ctx.lineTo(100 + s * 44, 116);
        ctx.closePath();
        ctx.fill();
      });
    } else {
      g = ctx.createLinearGradient(50, 40, 150, 160);
      g.addColorStop(0, light);
      g.addColorStop(1, dark);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.ellipse(100, 96, 54, 62, 0, 0, Math.PI * 2);
      ctx.fill();
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.moveTo(100 + s * 50, 92);
        ctx.bezierCurveTo(100 + s * 61, 116, 100 + s * 57, 140, 100 + s * 45, 150);
        ctx.bezierCurveTo(100 + s * 41, 136, 100 + s * 43, 110, 100 + s * 37, 92);
        ctx.closePath();
        ctx.fill();
      });
    }
  }

  function hairCap(ctx, bottom) {
    ctx.beginPath();
    ctx.moveTo(50, 104);
    ctx.bezierCurveTo(50, 56, 68, 36, 100, 36);
    ctx.bezierCurveTo(132, 36, 150, 56, 150, 104);
    bottom(ctx);
    ctx.closePath();
    ctx.fill();
  }

  function hairFront(ctx, rec) {
    var c = rec.hair, st = rec.hairStyle;
    var l = mix(c, 0.26), d = mix(c, -0.24);
    ctx.fillStyle = c;

    if (st === 'long') {
      // 中分：额头中间露出一小块，两边滑向耳前
      hairCap(ctx, function (x) {
        x.bezierCurveTo(148, 92, 138, 86, 128, 90);
        x.bezierCurveTo(118, 94, 112, 82, 108, 70);
        x.lineTo(100, 58);
        x.lineTo(92, 70);
        x.bezierCurveTo(88, 82, 82, 94, 72, 90);
        x.bezierCurveTo(62, 86, 52, 92, 50, 104);
      });
    } else if (st === 'wolf') {
      hairCap(ctx, function (x) {
        x.lineTo(144, 92); x.lineTo(134, 74); x.lineTo(124, 94); x.lineTo(112, 68);
        x.lineTo(102, 92); x.lineTo(92, 70); x.lineTo(82, 92); x.lineTo(70, 74);
        x.lineTo(58, 96); x.lineTo(50, 104);
      });
    } else if (st === 'bun') {
      hairCap(ctx, function (x) {
        x.bezierCurveTo(148, 82, 134, 80, 122, 88);
        x.bezierCurveTo(106, 98, 78, 102, 66, 98);
        x.bezierCurveTo(58, 96, 52, 98, 50, 104);
      });
    } else if (st === 'twin') {
      hairCap(ctx, function (x) {
        x.bezierCurveTo(146, 92, 132, 82, 118, 86);
        x.bezierCurveTo(106, 90, 94, 90, 82, 86);
        x.bezierCurveTo(68, 82, 54, 92, 50, 104);
      });
    } else {
      hairCap(ctx, function (x) {
        x.bezierCurveTo(146, 88, 136, 80, 126, 84);
        x.bezierCurveTo(116, 88, 110, 96, 100, 90);
        x.bezierCurveTo(88, 84, 82, 94, 72, 90);
        x.bezierCurveTo(62, 86, 56, 92, 50, 104);
      });
    }

    // 耳前那一缕
    [-1, 1].forEach(function (s) {
      ctx.beginPath();
      ctx.moveTo(100 + s * 42, 62);
      ctx.bezierCurveTo(100 + s * 51, 82, 100 + s * 50, 104, 100 + s * 45, 118);
      ctx.bezierCurveTo(100 + s * 41, 102, 100 + s * 40, 78, 100 + s * 36, 66);
      ctx.closePath();
      ctx.fill();
    });

    // 高光与暗部
    ctx.strokeStyle = rgba(l, 0.34);
    ctx.lineWidth = 2.2;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(70, 62);
    ctx.quadraticCurveTo(82, 46, 100, 42);
    ctx.stroke();
    ctx.strokeStyle = rgba(d, 0.28);
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(57, 100);
    ctx.bezierCurveTo(59, 72, 77, 48, 100, 40);
    ctx.stroke();
  }

  function drawAccessory(ctx, rec) {
    var acc = rec.acc || 0;
    if (!acc) return;

    if (acc === 2) {
      var c = rec.hair;
      [-1, 1].forEach(function (s) {
        ctx.beginPath();
        ctx.moveTo(100 + s * 30, 50);
        ctx.lineTo(100 + s * 40, 22);
        ctx.lineTo(100 + s * 51, 52);
        ctx.closePath();
        ctx.fillStyle = mix(c, -0.06);
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(100 + s * 35, 46);
        ctx.lineTo(100 + s * 40, 30);
        ctx.lineTo(100 + s * 46, 48);
        ctx.closePath();
        ctx.fillStyle = 'rgba(244,150,170,.85)';
        ctx.fill();
      });
    } else if (acc === 1) {
      ctx.strokeStyle = 'rgba(228,234,246,.92)';
      ctx.lineWidth = 2.1;
      roundRect(ctx, 63, 104, 33, 26, 9); ctx.stroke();
      roundRect(ctx, 104, 104, 33, 26, 9); ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(96, 116);
      ctx.quadraticCurveTo(100, 112, 104, 116);
      ctx.stroke();
      ctx.fillStyle = 'rgba(176,212,255,.13)';
      roundRect(ctx, 63, 104, 33, 26, 9); ctx.fill();
      roundRect(ctx, 104, 104, 33, 26, 9); ctx.fill();
    } else if (acc === 3) {
      ctx.strokeStyle = mix(rec.hair, -0.2);
      ctx.lineWidth = 6;
      ctx.beginPath();
      ctx.arc(100, 104, 53, Math.PI * 1.1, Math.PI * 1.9);
      ctx.stroke();
      [-1, 1].forEach(function (s) {
        roundRect(ctx, 100 + s * 50 - (s > 0 ? 3 : 11), 92, 14, 34, 7);
        ctx.fillStyle = '#2a3040';
        ctx.fill();
        ctx.strokeStyle = 'rgba(120,140,190,.8)';
        ctx.lineWidth = 2;
        ctx.stroke();
      });
    } else if (acc === 4) {
      ctx.save();
      ctx.translate(133, 68);
      ctx.rotate(0.42);
      ctx.fillStyle = '#f2a0b6';
      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.bezierCurveTo(-12, -10, -15, 7, 0, 4);
      ctx.bezierCurveTo(15, 7, 12, -10, 0, 0);
      ctx.fill();
      ctx.beginPath();
      ctx.arc(0, 2.4, 2.8, 0, Math.PI * 2);
      ctx.fillStyle = '#ffe3ec';
      ctx.fill();
      ctx.restore();
    }
  }

  /** 主绘制。rec: 配方；st: {blink:0..1, mouth:0..1, sway:-1..1} */
  function draw(ctx, w, h, rec, st) {
    st = st || {};
    var R = rec || {};
    R.skin = R.skin || SKINS[1];
    R.hair = R.hair || HAIR_COLORS[0];
    R.eye = R.eye || EYES[0];
    R.cloth = R.cloth || CLOTH[0];
    R.hairStyle = R.hairStyle || 'short';

    ctx.save();
    ctx.clearRect(0, 0, w, h);
    ctx.scale(w / 200, h / 260);

    drawCloth(ctx, R);
    drawNeck(ctx, R);
    drawEars(ctx, R);
    hairSilhouette(ctx, R);
    drawFace(ctx, R);

    var open = 1 - (st.blink || 0) * 0.97;
    drawEye(ctx, 79, EYE_Y, 11.6, 9.6, open, R.eye);
    drawEye(ctx, 121, EYE_Y, 11.6, 9.6, open, R.eye);
    drawBrow(ctx, 79, BROW_Y, 23, R.hair);
    drawBrow(ctx, 121, BROW_Y, 23, R.hair);

    ctx.beginPath();
    ctx.moveTo(101, NOSE_Y);
    ctx.quadraticCurveTo(104, NOSE_Y + 5, 100, NOSE_Y + 6);
    ctx.strokeStyle = rgba(mix(R.skin, -0.45), 0.45);
    ctx.lineWidth = 1.6;
    ctx.lineCap = 'round';
    ctx.stroke();

    drawMouth(ctx, st.mouth || 0, 0);
    hairFront(ctx, R);
    drawAccessory(ctx, R);

    // 左缘冷光
    var rim = ctx.createLinearGradient(50, 60, 76, 150);
    rim.addColorStop(0, 'rgba(150,200,255,.26)');
    rim.addColorStop(1, 'rgba(150,200,255,0)');
    facePath(ctx);
    ctx.strokeStyle = rim;
    ctx.lineWidth = 2;
    ctx.stroke();

    ctx.restore();
  }

  var PRESETS = {
    mint: { name: '薄荷', hairStyle: 'short', hair: '#9a6ad0', skin: '#f8ddc9', eye: '#4f74a8', cloth: '#26414c', acc: 4 },
    yan: { name: '小满', hairStyle: 'twin', hair: '#c08a4e', skin: '#f2cbb2', eye: '#6b4b8a', cloth: '#4b3042', acc: 0 },
    mo: { name: '墨', hairStyle: 'wolf', hair: '#3c4157', skin: '#f2cbb2', eye: '#5b6272', cloth: '#2b3450', acc: 0 },
    lu: { name: '阿岚', hairStyle: 'long', hair: '#4a7fd0', skin: '#f8ddc9', eye: '#3f7a63', cloth: '#3c3c5e', acc: 1 },
    tao: { name: '桃', hairStyle: 'bun', hair: '#d7637f', skin: '#f8ddc9', eye: '#8a5a3f', cloth: '#3b2f20', acc: 2 },
    yin: { name: '银', hairStyle: 'long', hair: '#aeb8c9', skin: '#dfae91', eye: '#b85552', cloth: '#20263a', acc: 3 }
  };

  function defaults() {
    return { hairStyle: 'short', hair: HAIR_COLORS[0], skin: SKINS[1], eye: EYES[0], cloth: CLOTH[0], acc: 0, blush: true };
  }

  function pick(a) { return a[Math.floor(Math.random() * a.length)]; }

  function randomRec() {
    var r = defaults();
    r.hairStyle = pick(HAIR_STYLES).id;
    r.hair = pick(HAIR_COLORS);
    r.skin = pick(SKINS);
    r.eye = pick(EYES);
    r.cloth = pick(CLOTH);
    r.acc = pick([0, 0, 1, 2, 4]);
    return r;
  }

  /** 把配方画到某个 canvas（处理 DPR） */
  function paint(canvas, rec, st, logicalW, logicalH) {
    if (!canvas) return;
    var W = logicalW || 200, H = logicalH || 260;
    var dpr = Math.min(3, global.devicePixelRatio || 1);
    var cw = Math.round(W * dpr), ch = Math.round(H * dpr);
    if (canvas.width !== cw || canvas.height !== ch) {
      canvas.width = cw;
      canvas.height = ch;
    }
    var ctx = canvas.getContext('2d');
    ctx.save();
    ctx.scale(dpr, dpr);
    draw(ctx, W, H, rec, st);
    ctx.restore();
  }

  global.AvatarArt = {
    HAIR_STYLES: HAIR_STYLES,
    HAIR_COLORS: HAIR_COLORS,
    SKINS: SKINS,
    EYES: EYES,
    CLOTH: CLOTH,
    ACCS: ACCS,
    PRESETS: PRESETS,
    defaults: defaults,
    randomRec: randomRec,
    draw: draw,
    paint: paint,
    mix: mix
  };
})(window);
