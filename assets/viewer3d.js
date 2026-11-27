/* 忆聊 · 3D 形象（VRM / glTF）
   依赖：three.min.js、GLTFLoader.js、three-vrm.min.js（都在 assets/lib/）
   用法：
     const v = VRMViewer.create({ canvas, width, height });
     v.load(url).then(info => ...);
     v.setMouth(0.8); v.setTalking(true); v.dispose();
*/
(function (global) {
  'use strict';

  function create(opts) {
    const canvas = opts.canvas;
    const W = opts.width || 200;
    const H = opts.height || 260;

    const MAX_TEX = opts.maxTexture || 1024;   // 贴图长边上限：半身像用不到 2K/4K
    const FPS = opts.fps || 32;                // 渲染帧率上限，省电

    let renderer, scene, camera, root, vrm, clock;
    let raf = 0, ready = false, disposed = false;
    let lastFrame = 0, hasHumanoid = false;
    let framing = 'bust';      // bust = 半身（聊天页），full = 全身（通话页）
    let modelObj = null;
    let limbs = null;
    const elbowUp = { l: 1, r: 1 };
    let bendSign = 1;   // 脊椎/头往前倾到底是 +x 还是 -x，各模型不一样
    let action = null;         // { name, start, dur }
    let animPlaying = false;
    let pointer = { x: 0, y: 0, until: 0 };
    let ACTS = null;
    let mouth = 0, mouthTarget = 0, blink = -1, nextBlink = 1.6 + Math.random() * 2.6;
    let talking = false, visible = true, info = null, debug = null;
    const bones = {};
    let baseY = 0;

    let progressMark = 0;

    function log() {
      if (opts.onlog) opts.onlog.apply(null, arguments);
    }

    function initScene() {
      renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
      renderer.setPixelRatio(Math.min(2, global.devicePixelRatio || 1));
      renderer.setSize(W, H, false);
      renderer.setClearColor(0x000000, 0);
      if ('outputEncoding' in renderer) renderer.outputEncoding = THREE.sRGBEncoding;

      scene = new THREE.Scene();
      camera = new THREE.PerspectiveCamera(30, W / H, 0.01, 50);

      const hemi = new THREE.HemisphereLight(0xffffff, 0x334455, 1.6);
      scene.add(hemi);
      const dir = new THREE.DirectionalLight(0xffffff, 1.2);
      dir.position.set(0.6, 1.6, 1.2);
      scene.add(dir);

      clock = new THREE.Clock();
    }

    /** 贴图降采样：手机上 2048 的贴图每个吃 16MB 显存，缩到 1024 只要 4MB，
        而这个画布最多一百多像素高，肉眼看不出差别。 */
    function shrinkTextures(rootObj, max) {
      const done = new Set();
      let saved = 0, shrunk = 0;
      const shrink = function (tex) {
        if (!tex || !tex.isTexture || done.has(tex)) return;
        done.add(tex);
        const img = tex.image;
        if (!img || !img.width || !img.height) return;
        const big = Math.max(img.width, img.height);
        if (big <= max) return;
        shrunk++;
        const s = max / big;
        const cw = Math.max(1, Math.round(img.width * s));
        const ch = Math.max(1, Math.round(img.height * s));
        try {
          const c = global.document.createElement('canvas');
          c.width = cw;
          c.height = ch;
          const g = c.getContext('2d');
          g.imageSmoothingQuality = 'high';
          g.drawImage(img, 0, 0, cw, ch);
          saved += Math.round((img.width * img.height - cw * ch) * 4 / 1048576);
          tex.image = c;
          tex.needsUpdate = true;
          if (img.close) {
            try { img.close(); } catch (e) { }
          }
        } catch (e) {
          log('tex shrink failed: ' + e.message);
        }
      };
      let found = 0;
      // MToon 的贴图槽名字不统一，既扫所有自有属性，也扫一遍已知槽位
      const SLOTS = ['map', 'normalMap', 'alphaMap', 'emissiveMap', 'aoMap', 'roughnessMap',
        'metalnessMap', 'bumpMap', 'gradientMap', 'matcapTexture', 'outlineWidthTexture',
        'outlineColorTexture', 'shadeMultiplyTexture', 'shadeColorTexture', 'rimMultiplyTexture',
        'uvAnimationMaskTexture', 'outlineWidthMultiplyTexture', 'specularMap', 'envMap'];
      const visit = function (v) {
        if (v && v.isTexture) {
          found++;
          shrink(v);
        }
      };
      rootObj.traverse(function (o) {
        if (!o.material) return;
        const mats = Array.isArray(o.material) ? o.material : [o.material];
        mats.forEach(function (m) {
          try {
            Object.keys(m).forEach(function (k) { visit(m[k]); });
          } catch (e) {
          }
          SLOTS.forEach(function (k) { visit(m[k]); });
        });
      });
      log('tex ' + found + ' slot, ' + shrunk + ' shrunk, ~' + saved + 'MB');
    }

    /** 导入后自动调整骨骼/模型：身高不在 0.9~2.2m 的按 1.5m 归一，
        水平方向归到原点，然后重新摆站姿。 */
    function normalizeModel(obj) {
      if (!obj) return;
      let info = { scaled: false, height: 0 };
      try {
        obj.updateMatrixWorld(true);
        const box = new THREE.Box3().setFromObject(obj);
        if (box.isEmpty()) return info;
        const h = box.max.y - box.min.y;
        info.height = h;
        if (h > 0.02 && (h < 0.9 || h > 2.2)) {
          const k = 1.5 / h;
          obj.scale.multiplyScalar(k);
          obj.updateMatrixWorld(true);
          info.scaled = true;
          log('auto scale x' + k.toFixed(3));
        }
        const box2 = new THREE.Box3().setFromObject(obj);
        const cx = (box2.min.x + box2.max.x) / 2;
        const cz = (box2.min.z + box2.max.z) / 2;
        if (Math.abs(cx) > 0.02 || Math.abs(cz) > 0.02) {
          obj.position.x -= cx;
          obj.position.z -= cz;
          obj.updateMatrixWorld(true);
          log('auto center ' + cx.toFixed(2) + ',' + cz.toFixed(2));
        }
      } catch (e) {
        log('normalize failed: ' + e.message);
      }
      return info;
    }

    function frameCamera(object3d) {
      const head = bones.head;
      const tmp = new THREE.Vector3();
      let headX = 0;
      if (head) {
        head.getWorldPosition(tmp);
        headX = tmp.x;
      }

      // 以模型包围盒顶端（头顶）为准取景，head 骨骼在颅底，直接用会切掉额头
      let topY = null, bottomY = null;
      if (object3d) {
        const box = new THREE.Box3().setFromObject(object3d);
        if (!box.isEmpty()) {
          topY = box.max.y;
          bottomY = box.min.y;
        }
      }
      if (topY === null) topY = (head ? tmp.y : 1.5) + 0.17;

      const span = topY - (bottomY || 0);
      let visible, center;
      if (framing === 'full' && bottomY !== null) {
        const h = Math.max(0.5, topY - bottomY);
        visible = h * 1.14;
        center = (topY + bottomY) / 2 + h * 0.02;
      } else if (framing === 'half') {
        visible = 0.88;                       // 头到腰胯
        center = topY + 0.05 - visible / 2;
      } else {
        visible = 0.68;                       // 聊天页那种头肩
        center = topY + 0.06 - visible / 2;
      }
      const dist = visible / (2 * Math.tan(Math.PI * camera.fov / 360));

      camera.position.set(headX * 0.5, center, dist);
      camera.lookAt(headX * 0.5, center, 0);
      camera.updateProjectionMatrix();
      debug = { headX: headX, headY: tmp.y, topY: topY, bottomY: bottomY, centerY: center, dist: dist, span: span };
    }

    function onLoaded(gltf) {
      vrm = gltf.userData && gltf.userData.vrm;
      const obj = vrm ? vrm.scene : gltf.scene;
      root = new THREE.Group();
      root.add(obj);
      scene.add(root);
      modelObj = obj;

      if (vrm) {
        try {
          if (THREE_VRM.VRMUtils && THREE_VRM.VRMUtils.rotateVRM0) THREE_VRM.VRMUtils.rotateVRM0(vrm);
          if (THREE_VRM.VRMUtils && THREE_VRM.VRMUtils.removeUnnecessaryVertices) THREE_VRM.VRMUtils.removeUnnecessaryVertices(gltf.scene);
        } catch (e) {
          log('vrm utils: ' + e.message);
        }
        const names = ['head', 'neck', 'chest', 'upperChest', 'spine', 'hips'];
        names.forEach(function (n) {
          try {
            bones[n] = normBone(THREE_VRM.VRMHumanBoneName[n.charAt(0).toUpperCase() + n.slice(1)]) || null;
          } catch (e) {
            bones[n] = null;
          }
        });
      }

      obj.traverse(function (o) {
        if (o.isSkinnedMesh) o.frustumCulled = false;
        if (o.isMesh) o.castShadow = false;
      });

      shrinkTextures(obj, MAX_TEX);

      hasHumanoid = !!(vrm && vrm.humanoid && vrm.humanoid.getNormalizedBoneNode &&
        vrm.humanoid.getNormalizedBoneNode(THREE_VRM.VRMHumanBoneName.Head));

      // 眼睛看向镜头，比直视前方“活”一点
      try {
        if (vrm && vrm.lookAt) {
          vrm.lookAt.target = camera;
          vrm.lookAt.autoUpdate = true;
        }
      } catch (e) {
      }

      const norm = normalizeModel(obj);
      poseIdle();
      root.rotation.y = 0;
      try {
        vrm ? vrm.update(0) : obj.updateMatrixWorld(true);
      } catch (e) {
      }
      frameCamera(obj);

      const exprs = [];
      try {
        if (vrm && vrm.expressionManager) {
          exprs.push.apply(exprs, vrm.expressionManager.expressions.map(function (e) { return e.expressionName; }));
        }
      } catch (e) {
      }
      info = {
        vrm: !!vrm,
        version: vrm && vrm.meta ? (vrm.meta.metaVersion || '0') : '',
        title: vrm && vrm.meta ? (vrm.meta.title || vrm.meta.name || '') : '',
        bones: Object.keys(bones).filter(function (k) { return !!bones[k]; }),
        expressions: exprs,
        debug: debug
      };
      calibrateElbows();
      calibrateBend();
      poseIdle();
      // 摆完姿势后把弹簧骨骼（裙摆/头发）复位到静止状态，
      // 否则初始化时缓存的世界矩阵和现在对不上，裙子会炸成圈
      try {
        if (vrm.springBoneManager && vrm.springBoneManager.setInitState) {
          vrm.springBoneManager.setInitState();
        }
      } catch (e) {
        log('spring init: ' + e.message);
      }
      snapshotRest();
      log('loaded', JSON.stringify(info));
      ready = true;
      loop();
    }

    let armBase = null;     // 放下手臂后的基准角度，用来叠微动
    let poseSign = 1;
    let poseChecks = 0;
    let poseNext = 0;

    function rawBone(name) {
      try {
        if (!vrm || !vrm.humanoid) return null;
        if (vrm.humanoid.getRawBoneNode) {
          const b = vrm.humanoid.getRawBoneNode(name);
          if (b) return b;
        }
        return vrm.humanoid.getBoneNode ? (vrm.humanoid.getBoneNode(name) || null) : null;
      } catch (e) {
        return null;
      }
    }

    function normBone(name) {
      try {
        return vrm.humanoid.getNormalizedBoneNode ? (vrm.humanoid.getNormalizedBoneNode(name) || null) : null;
      } catch (e) {
        return null;
      }
    }

    /** 手是不是举在肩膀上方——用真实骨骼量，不依赖归一化空间的轴向 */
    function handsAreUp() {
      try {
        vrm.update(0);
        vrm.scene.updateMatrixWorld(true);
      } catch (e) {
      }
      const H = THREE_VRM.VRMHumanBoneName;
      const pairs = [
        [rawBone(H.LeftUpperArm), rawBone(H.LeftHand) || rawBone(H.LeftLowerArm)],
        [rawBone(H.RightUpperArm), rawBone(H.RightHand) || rawBone(H.RightLowerArm)]
      ];
      const a = new THREE.Vector3(), b = new THREE.Vector3();
      let checked = false, up = false;
      pairs.forEach(function (pair) {
        if (!pair[0] || !pair[1]) return;
        pair[0].getWorldPosition(a);
        pair[1].getWorldPosition(b);
        checked = true;
        if (b.y > a.y + 0.03) up = true;
      });
      return checked ? up : false;
    }

    /** 把 T-pose 的手臂放下来。VRM0 / VRM1 的骨骼轴向相反，符号靠量出来。 */
    function applyPose(sign, angle) {
      const H = THREE_VRM.VRMHumanBoneName;
      const a = angle == null ? 1.34 : angle;
      const lU = normBone(H.LeftUpperArm), rU = normBone(H.RightUpperArm);
      if (!lU || !rU) return false;
      const lL = normBone(H.LeftLowerArm), rL = normBone(H.RightLowerArm);
      const lH = normBone(H.LeftHand), rH = normBone(H.RightHand);

      lU.rotation.set(0, 0, sign * a);
      rU.rotation.set(0, 0, -sign * a);
      if (lL) lL.rotation.set(0, 0, sign * -0.12);
      if (rL) rL.rotation.set(0, 0, sign * 0.12);
      if (lH) lH.rotation.set(0, 0, sign * -0.06);
      if (rH) rH.rotation.set(0, 0, sign * 0.06);

      armBase = { l: lU, r: rU, lz: sign * a, rz: -sign * a };
      limbs = { lU: lU, rU: rU, lL: lL, rL: rL, lH: lH, rH: rH };
      poseSign = sign;
      return true;
    }

    /** 两只手的平均高度（越小 = 手臂越垂直于身体） */
    function handHeight() {
      try {
        vrm.update(0);
        vrm.scene.updateMatrixWorld(true);
      } catch (e) {
      }
      const H = THREE_VRM.VRMHumanBoneName;
      const v = new THREE.Vector3();
      let sum = 0, n = 0;
      [rawBone(H.LeftHand) || rawBone(H.LeftLowerArm), rawBone(H.RightHand) || rawBone(H.RightLowerArm)].forEach(function (b) {
        if (!b) return;
        b.getWorldPosition(v);
        sum += v.y;
        n++;
      });
      return n ? sum / n : 0;
    }

    /** 抬手臂时肘该往哪边弯：两个方向各试一次，看哪个把手举得更高 */
    function calibrateElbows() {
      const H = THREE_VRM.VRMHumanBoneName;
      if (!limbs || !limbs.lU || !limbs.rU) return;
      const v = new THREE.Vector3();
      const handY = function (hand) {
        if (!hand) return 0;
        v.set(0, 0, 0);
        hand.getWorldPosition(v);
        return v.y;
      };
      const test = function (upper, lower, hand, sign) {
        if (!upper || !lower) return 0;
        const uz = upper.rotation.z;
        upper.rotation.z = sign * 0.28;
        lower.rotation.z = 0.95;
        try {
          vrm.update(0);
          vrm.scene.updateMatrixWorld(true);
        } catch (e) {
        }
        const a = handY(hand);
        upper.rotation.z = uz;
        return a;
      };
      // 左手
      const l1 = test(limbs.lU, limbs.lL, limbs.lH, 1);
      const l2 = test(limbs.lU, limbs.lL, limbs.lH, -1);
      elbowUp.l = Math.abs(l2) > Math.abs(l1) ? -1 : 1;
      // 右手
      const r1 = test(limbs.rU, limbs.rL, limbs.rH, 1);
      const r2 = test(limbs.rU, limbs.rL, limbs.rH, -1);
      elbowUp.r = Math.abs(r2) > Math.abs(r1) ? -1 : 1;
      log('elbow ' + JSON.stringify(elbowUp) + ' y ' + l1.toFixed(2) + '/' + l2.toFixed(2) + ' ' + r1.toFixed(2) + '/' + r2.toFixed(2));
    }

    /** 前倾方向标定：点头、鞠躬、伸懒腰都得知道“往前”是哪个符号 */
    function calibrateBend() {
      const spine = bones.spine || bones.chest || bones.hips;
      const head = bones.head;
      if (!spine || !head) return;
      const v = new THREE.Vector3();
      const headZ = function () {
        try {
          vrm.update(0);
          vrm.scene.updateMatrixWorld(true);
        } catch (e) {
        }
        head.getWorldPosition(v);
        return v.z;
      };
      const beforeZ = headZ();
      spine.rotation.x = 0.30;
      const plusZ = headZ();
      spine.rotation.x = -0.30;
      const minusZ = headZ();
      spine.rotation.x = 0;
      bendSign = (plusZ > minusZ) ? 1 : -1;
      log('bend ' + bendSign + ' z ' + beforeZ.toFixed(2) + ' / ' + plusZ.toFixed(2) + ' / ' + minusZ.toFixed(2));
    }

    function poseIdle() {
      if (!vrm || !vrm.humanoid || !THREE_VRM.VRMHumanBoneName) return;
      if (!applyPose(1)) return;
      if (handsAreUp()) applyPose(-1);
      handHeight();
      poseChecks = 0;
      poseNext = 0;
      log('pose ' + poseSign);
    }

    /* ---------- 动作库：叠加在站立姿势上的程序化动作 ---------- */
    const EASE = function (p) { return Math.sin(Math.PI * Math.max(0, Math.min(1, p))); };

    function buildActions() {
      const H = THREE_VRM.VRMHumanBoneName;
      const sign = function () { return poseSign; };
      ACTS = {
        wave: {
          dur: 2.8, name: '挥手', expr: 'happy',
          run: function (p) {
            if (!limbs || !limbs.rU) return;
            const sg = sign();
            const up = EASE(Math.min(1, p * 1.15));
            // 先抬到接近水平（-0.2），小臂再往上折，手就举到肩以上
            const baseZ = sg * 1.34;
            const upZ = sg * -0.22;
            limbs.rU.rotation.z = baseZ * (1 - up) + upZ * up;
            if (limbs.rL) {
              const baseL = sg * 0.12;
              const bend = sg * elbowUp.r * 1.05;
              limbs.rL.rotation.z = baseL * (1 - up) + bend * up
                + Math.sin(p * Math.PI * 9) * 0.30 * up;
            }
          }
        },
        nod: {
          dur: 1.0, name: '点头', expr: 'happy',
          run: function (p) {
            if (bones.head) bones.head.rotation.x += Math.sin(p * Math.PI * 2) * 0.17 * bendSign;
          }
        },
        shake: {
          dur: 1.1, name: '摇头',
          run: function (p) {
            if (bones.head) bones.head.rotation.y += Math.sin(p * Math.PI * 4) * 0.22 * (1 - p * 0.4);
          }
        },
        bow: {
          dur: 1.5, name: '鞠躬', expr: 'happy',
          run: function (p) {
            const k = EASE(p);
            if (bones.spine) bones.spine.rotation.x += bendSign * 0.36 * k;
            if (bones.head) bones.head.rotation.x += bendSign * 0.30 * k;
          }
        },
        stretch: {
          dur: 2.4, name: '伸懒腰',
          run: function (p) {
            if (!limbs) return;
            const k = EASE(p);
            const sg = sign();
            // 负数 = 举过肩，-0.62 大约斜上方 45°
            limbs.lU.rotation.z = sg * 1.34 * (1 - k) + sg * -0.62 * k;
            limbs.rU.rotation.z = -sg * 1.34 * (1 - k) + -sg * -0.62 * k;
            if (limbs.lL) limbs.lL.rotation.z = sg * -0.12 * (1 - k) + sg * -0.10 * k;
            if (limbs.rL) limbs.rL.rotation.z = sg * 0.12 * (1 - k) + sg * 0.10 * k;
            if (bones.spine) bones.spine.rotation.x -= bendSign * 0.08 * k;
            if (bones.head) bones.head.rotation.x -= bendSign * 0.12 * k;
          }
        },
        spin: {
          dur: 1.7, name: '转一圈', expr: 'happy',
          run: function (p) {
            if (!root) return;
            const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
            root.rotation.y = e * Math.PI * 2;
          }
        },
        tilt: {
          dur: 1.8, name: '歪头',
          run: function (p) {
            const k = EASE(p);
            if (bones.head) {
              bones.head.rotation.z += 0.34 * k;
              bones.head.rotation.y += 0.15 * k;
            }
          }
        },
        poke: {
          dur: 0.9, name: '被戳一下', expr: 'surprised',
          run: function (p) {
            const k = EASE(p);
            if (bones.head) {
              bones.head.rotation.x -= bendSign * 0.14 * k;
              bones.head.rotation.z += 0.10 * Math.sin(p * Math.PI * 3);
            }
            if (root) root.position.y += 0.015 * k;
            if (bones.chest) bones.chest.rotation.x -= 0.05 * k;
          }
        }
      };
      return ACTS;
    }

    /* ---------- .vrma（VRM Animation）----------
       注：three-vrm 的归一化骨骼是一个独立于场景的骨架（VRMHumanoidRig），
       AnimationMixer 按 uuid 绑定找不到它，所以这里直接自己采样关键帧写回去。 */
    const restQuats = new Map();
    let animData = null;

    function snapshotRest() {
      restQuats.clear();
      if (!vrm || !vrm.humanoid) return;
      const rootObj = vrm.humanoid.normalizedHumanBonesRoot || vrm.scene;
      rootObj.traverse(function (o) {
        if (o && o.quaternion) restQuats.set(o.uuid, o.quaternion.clone());
      });
    }

    function restoreRest() {
      if (!vrm || !vrm.humanoid) return;
      const rootObj = vrm.humanoid.normalizedHumanBonesRoot || vrm.scene;
      rootObj.traverse(function (o) {
        const q = restQuats.get(o.uuid);
        if (q && o.quaternion) o.quaternion.copy(q);
      });
    }

    function buildAnimData(gltf) {
      const json = gltf.parser && gltf.parser.json;
      const ext = json && json.extensions && json.extensions.VRMC_vrm_animation;
      const human = ext && ext.humanoid && ext.humanoid.humanBones;
      const src = gltf.animations && gltf.animations[0];
      if (!human || !src) return null;

      const nameBone = {};
      Object.keys(human).forEach(function (boneName) {
        const n = json.nodes && json.nodes[human[boneName].node];
        if (n && n.name) nameBone[n.name] = boneName;
      });

      const tracks = [];
      src.tracks.forEach(function (tr) {
        const dot = tr.name.lastIndexOf('.');
        if (dot < 0) return;
        if (tr.name.slice(dot + 1) !== 'quaternion') return;   // 只取旋转
        const boneName = nameBone[tr.name.slice(0, dot)];
        if (!boneName) return;
        if (!normBone(boneName)) return;
        tracks.push({ bone: boneName, times: tr.times, values: tr.values });
      });
      if (!tracks.length) return null;
      return { dur: src.duration, tracks: tracks };
    }

    const _qA = new THREE.Quaternion();
    const _qB = new THREE.Quaternion();

    function sampleQuat(times, values, t, out) {
      const n = times.length;
      if (!n) return out;
      if (t <= times[0]) {
        return out.set(values[0], values[1], values[2], values[3]);
      }
      if (t >= times[n - 1]) {
        const i = (n - 1) * 4;
        return out.set(values[i], values[i + 1], values[i + 2], values[i + 3]);
      }
      let lo = 0, hi = n - 1;
      while (hi - lo > 1) {
        const mid = (lo + hi) >> 1;
        if (times[mid] <= t) lo = mid;
        else hi = mid;
      }
      const t0 = times[lo], t1 = times[hi];
      const k = t1 > t0 ? (t - t0) / (t1 - t0) : 0;
      const i0 = lo * 4, i1 = hi * 4;
      return out.set(
        values[i0] + (values[i1] - values[i0]) * k,
        values[i0 + 1] + (values[i1 + 1] - values[i0 + 1]) * k,
        values[i0 + 2] + (values[i1 + 2] - values[i0 + 2]) * k,
        values[i0 + 3] + (values[i1 + 3] - values[i0 + 3]) * k
      ).normalize();
    }

    function stepAnim(dt) {
      if (!animData) return;
      animData.t += dt;
      if (animData.t > animData.dur) {
        animPlaying = false;
        animData = null;
        restoreRest();
        poseIdle();
        return;
      }
      animData.tracks.forEach(function (tr) {
        const bone = normBone(tr.bone);
        if (!bone) return;
        sampleQuat(tr.times, tr.values, animData.t, _qA);
        bone.quaternion.copy(_qA);
      });
    }

    function playAnim(url) {
      return new Promise(function (resolve, reject) {
        if (!vrm) {
          reject(new Error('先加载模型再放动作'));
          return;
        }
        const loader = new THREE.GLTFLoader();
        loader.load(url, function (gltf) {
          try {
            const data = buildAnimData(gltf);
            if (!data) {
              reject(new Error('这个 .vrma 里没有能用的骨骼轨道'));
              return;
            }
            snapshotRest();
            data.t = 0;
            animData = data;
            animPlaying = true;
            resolve({ duration: data.dur, tracks: data.tracks.length });
          } catch (e) {
            reject(e);
          }
        }, null, function (err) {
          reject(err || new Error('动作文件加载失败'));
        });
      });
    }

    function stopAnim() {
      animPlaying = false;
      animData = null;
      restoreRest();
      poseIdle();
    }

    function play(name) {
      if (!ACTS) buildActions();
      const a = ACTS[name];
      if (!a) return false;
      action = { name: name, start: clock.elapsedTime, dur: a.dur };
      return true;
    }

    function setPointer(nx, ny) {
      pointer.x = Math.max(-1, Math.min(1, nx || 0));
      pointer.y = Math.max(-1, Math.min(1, ny || 0));
      pointer.until = clock.elapsedTime + 1.6;
    }

    function currentAction() {
      return action ? action.name : '';
    }

    function setExpr(name, v) {
      try {
        if (vrm && vrm.expressionManager) vrm.expressionManager.setValue(name, v);
      } catch (e) {
      }
    }

    function loop(ts) {
      if (disposed) return;
      raf = global.requestAnimationFrame(loop);
      if (!ready || !visible) return;
      if (ts && lastFrame && ts - lastFrame < 1000 / FPS - 1) return;
      lastFrame = ts || 0;
      const dt = Math.min(0.08, clock.getDelta());
      const t = clock.elapsedTime;

      mouth += (mouthTarget - mouth) * Math.min(1, dt * 12);

      if (animPlaying) {
        stepAnim(Math.min(dt, 1 / 30));
        setExpr('aa', Math.min(1, mouth));
        if (vrm && vrm.update) vrm.update(Math.min(dt, 1 / 60));
        try {
          renderer.render(scene, camera);
        } catch (e) {
        }
        return;
      }

      // 站着的微动：呼吸 + 重心左右挪 + 头慢慢看 + 手臂垂着轻晃
      const breath = Math.sin(t * 1.35);
      const talk = talking ? 1 : 0;
      if (bones.hips) {
        bones.hips.rotation.z = Math.sin(t * 0.33) * 0.010;
        bones.hips.rotation.y = Math.sin(t * 0.21 + 1.1) * 0.012;
      }
      if (bones.spine) {
        bones.spine.rotation.x = breath * 0.012;
        bones.spine.rotation.y = Math.sin(t * 0.28) * 0.018;
        bones.spine.rotation.z = -Math.sin(t * 0.33) * 0.008;
      }
      if (bones.chest) {
        bones.chest.rotation.x = Math.sin(t * 1.35 + 0.5) * 0.014;
      }
      if (bones.neck) {
        bones.neck.rotation.y = Math.sin(t * 0.36 + 0.4) * 0.05;
      }
      if (bones.head) {
        bones.head.rotation.y = Math.sin(t * 0.36 + 0.4) * 0.10 + Math.sin(t * 0.13 + 2.1) * 0.05;
        bones.head.rotation.x = Math.sin(t * 0.55 + 0.8) * 0.025 + breath * 0.008 + talk * Math.sin(t * 5.4) * 0.012;
        bones.head.rotation.z = Math.sin(t * 0.24 + 0.6) * 0.022;
      }
      // 每帧先把手臂复位到基准站姿，动作再往上叠，免得动作收尾时残留姿势
      if (limbs) {
        const sgn = poseSign;
        const ang = armBase ? Math.abs(armBase.lz) : 1.34;
        limbs.lU.rotation.set(0, 0, sgn * ang);
        limbs.rU.rotation.set(0, 0, -sgn * ang);
        if (limbs.lL) limbs.lL.rotation.set(0, 0, sgn * -0.12);
        if (limbs.rL) limbs.rL.rotation.set(0, 0, sgn * 0.12);
        if (limbs.lH) limbs.lH.rotation.set(0, 0, sgn * -0.06);
        if (limbs.rH) limbs.rH.rotation.set(0, 0, sgn * 0.06);
        limbs.lU.rotation.z += Math.sin(t * 0.62) * 0.014;
        limbs.rU.rotation.z -= Math.sin(t * 0.62 + 0.4) * 0.014;
      }

      // 手指/鼠标指哪儿，头就看哪儿（松手 1.6 秒后自己转回来）
      if (bones.head && t < pointer.until) {
        const k = Math.min(1, (pointer.until - t) / 0.6);
        bones.head.rotation.y += pointer.x * 0.5 * k;
        bones.head.rotation.x += pointer.y * 0.28 * k;
      }

      // 正在播的动作叠加在站姿上
      if (action) {
        const p = (t - action.start) / action.dur;
        if (p >= 1) {
          action = null;
        } else if (ACTS && ACTS[action.name]) {
          ACTS[action.name].run(p);
          if (ACTS[action.name].expr) setExpr(ACTS[action.name].expr, EASE(p) * 0.9);
        }
      }
      if (root) {
        root.position.y = breath * 0.004;
      }
      // 不是 VRM 的普通 glb：没有骨骼，就整体轻轻浮动
      if (!hasHumanoid && root) {
        root.rotation.y = Math.sin(t * 0.22) * 0.10;
        root.position.y = Math.sin(t * 1.3) * 0.004;
      }
      // 前几秒复核手臂方向：发现还举着就翻过来（最多折腾 3 次）
      if (poseChecks < 3 && t > poseNext) {
        poseNext = t + 0.7;
        if (handsAreUp()) {
          applyPose(-poseSign, armBase ? Math.abs(armBase.lz) : 1.34);
          poseChecks++;
        } else {
          poseChecks = 3;
        }
      }

      // 眨眼
      if (blink < 0) {
        nextBlink -= dt;
        if (nextBlink <= 0) {
          blink = 0;
          nextBlink = 2.2 + Math.random() * 3.4;
        }
      } else {
        blink += dt / 0.14;
        if (blink >= 1) blink = -1;
      }
      const blinkVal = blink < 0 ? 0 : Math.sin(Math.min(1, blink) * Math.PI) * 0.95;
      setExpr('blink', blinkVal);
      setExpr('aa', Math.min(1, mouth));
      if (talking) setExpr('happy', 0.15 + Math.sin(t * 2) * 0.05);

      // 物理固定步长：掉帧/切后台回来不会被一大步甩飞头发，步数也有上限
      if (vrm && vrm.update) {
        let acc = dt, guard = 0;
        while (acc > 1e-4 && guard < 3) {
          const step = Math.min(acc, 1 / 60);
          try {
            vrm.update(step);
          } catch (e) {
          }
          acc -= step;
          guard++;
        }
      }
      try {
        renderer.render(scene, camera);
      } catch (e) {
      }
    }

    initScene();
    loop();

    return {
      load: function (url) {
        ready = false;
        return new Promise(function (resolve, reject) {
          const loader = new THREE.GLTFLoader();
          try {
            loader.register(function (parser) { return new THREE_VRM.VRMLoaderPlugin(parser); });
          } catch (e) {
            log('vrm plugin: ' + e.message);
          }
          loader.load(url, function (gltf) {
            try {
              onLoaded(gltf);
              resolve(info);
            } catch (e) {
              reject(e);
            }
          }, function (ev) {
            if (ev && ev.total) {
              const pct = Math.round(ev.loaded / ev.total * 100);
              if (pct >= progressMark + 20) {
                progressMark = Math.floor(pct / 20) * 20;
                log('progress', pct + '%');
              }
            }
          }, function (err) {
            reject(err || new Error('模型加载失败'));
          });
        });
      },
      resize: function (w, h) {
        if (!renderer || !camera) return;
        // 大画布（通话页）降一点采样，手机上跑得动
        const big = w * h > 240000;
        renderer.setPixelRatio(Math.min(big ? 1.5 : 2, global.devicePixelRatio || 1));
        renderer.setSize(w, h, false);
        camera.aspect = w / h;
        camera.updateProjectionMatrix();
      },
      setFraming: function (mode) {
        framing = mode === 'full' ? 'full' : (mode === 'half' ? 'half' : 'bust');
        if (modelObj) frameCamera(modelObj);
      },
      play: function (name) { return play(name); },
      playAnim: function (url) { return playAnim(url); },
      stopAnim: function () { stopAnim(); },
      isPlayingAnim: function () { return animPlaying; },
      refit: function () {
        if (!modelObj) return;
        normalizeModel(modelObj);
        poseIdle();
        try {
          vrm ? vrm.update(0) : modelObj.updateMatrixWorld(true);
        } catch (e) {
        }
        frameCamera(modelObj);
        return true;
      },
      info: function () { return info; },
      probe: function () {
        const H = THREE_VRM.VRMHumanBoneName;
        const pick = function (b) {
          if (!b) return null;
          const v = new THREE.Vector3();
          b.getWorldPosition(v);
          return { x: +v.x.toFixed(3), y: +v.y.toFixed(3), z: +v.z.toFixed(3) };
        };
        const hb = rawBone(H.Head);
        const he = hb ? new THREE.Euler().setFromQuaternion(hb.getWorldQuaternion(new THREE.Quaternion())) : null;
        return {
          headRot: he ? { x: +(he.x * 57.3).toFixed(1), y: +(he.y * 57.3).toFixed(1), z: +(he.z * 57.3).toFixed(1) } : null,
          rootY: root ? +(root.rotation.y * 57.3).toFixed(0) : 0,
          head: pick(rawBone(H.Head)),
          lHand: pick(rawBone(H.LeftHand)),
          rHand: pick(rawBone(H.RightHand)),
          lSho: pick(rawBone(H.LeftUpperArm)),
          rSho: pick(rawBone(H.RightUpperArm)),
          hips: pick(rawBone(H.Hips)),
          action: currentAction(),
          rUz: limbs && limbs.rU ? +limbs.rU.rotation.z.toFixed(3) : null,
          sign: poseSign,
          acts: ACTS ? Object.keys(ACTS).length : 0,
          t: +clock.elapsedTime.toFixed(2),
          aStart: action ? +action.start.toFixed(2) : null,
          aDur: action ? action.dur : null
        };
      },
      setPointer: function (x, y) { setPointer(x, y); },
      currentAction: function () { return currentAction(); },
      actions: function () {
        if (!ACTS) buildActions();
        return Object.keys(ACTS).map(function (k) { return { id: k, name: ACTS[k].name }; });
      },
      setMouth: function (v) { mouthTarget = Math.max(0, Math.min(1, v || 0)); },
      setTalking: function (b) {
        talking = !!b;
        if (!talking) {
          mouthTarget = 0;
          setExpr('happy', 0);
        }
      },
      setVisible: function (b) { visible = !!b; if (b) clock.getDelta(); },
      reset: function () {
        ready = false;
        mouth = 0; mouthTarget = 0; blink = -1;
        if (root) {
          scene.remove(root);
          root.traverse(function (o) {
            if (o.geometry) o.geometry.dispose();
            if (o.material) {
              const ms = Array.isArray(o.material) ? o.material : [o.material];
              ms.forEach(function (m) {
                if (m.map) m.map.dispose();
                m.dispose();
              });
            }
          });
          root = null;
        }
      },
      dispose: function () {
        disposed = true;
        if (raf) global.cancelAnimationFrame(raf);
        this.reset();
        try {
          renderer.dispose();
          renderer.forceContextLoss && renderer.forceContextLoss();
        } catch (e) {
        }
      }
    };
  }

  global.VRMViewer = { create: create };
})(window);
