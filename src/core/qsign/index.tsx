/**
 * MusicFree 本地签名桥 —— 用隐藏 WebView 跑 WASM 模拟器
 *
 * 为什么要这样：MusicFree 的插件跑在 Hermes 里（没有 WebAssembly），
 * 而 Android 的 WebView 是 Chromium（完整支持 WebAssembly）。
 * 所以让 WebView 加载 assets/qsign.html（内嵌了 WASM 签名器），
 * 通过 postMessage 把签名请求送进去，结果再回传。
 *
 * 效果：完全离线本地签名，不需要任何服务器。
 *
 * 用法：
 *   1) 在 App 根组件里挂一次 <QSignHost />（零尺寸，不显示）
 *   2) 任何地方 await qsignSign(url, headers) 就能拿到 6 个签名头
 *   3) 插件沙箱里也会自动有 globalThis.__qsign(url, headers)（见 plugin.ts 补丁）
 */
import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { WebView, WebViewMessageEvent } from 'react-native-webview';

type Pending = {
  resolve: (v: Record<string, string>) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

const pending = new Map<string, Pending>();
// 懒加载：App 启动时不挂 WebView，只有真正要签名时才挂（避免影响启动流程）
let mountRequested = false;
const mountListeners = new Set<() => void>();
function requestMount() {
  if (mountRequested) return;
  mountRequested = true;
  mountListeners.forEach((fn) => {
    try { fn(); } catch (e) {}
  });
}
let webviewRef: any = null;
let seq = 0;
let readyResolvers: Array<() => void> = [];

function nextId() {
  seq += 1;
  return 'q' + Date.now().toString(36) + '_' + seq;
}

function onMessage(event: WebViewMessageEvent) {
  let msg: any;
  try {
    msg = JSON.parse(event.nativeEvent.data);
  } catch (e) {
    return;
  }
  if (!msg || msg.__qsign !== 1 || !msg.id) return;
  const item = pending.get(msg.id);
  if (!item) return;
  pending.delete(msg.id);
  clearTimeout(item.timer);
  if (msg.ok) item.resolve(msg.payload || {});
  else item.reject(new Error((msg.payload && msg.payload.error) || 'qsign failed'));
}

/** 是否已就绪 */
export function qsignReady() {
  return !!webviewRef;
}

/** 等 WebView 加载完（首次调用签名前内部会自动等） */
function waitReady(timeoutMs = 20000) {
  if (webviewRef && (webviewRef as any).__loaded) return Promise.resolve();
  return new Promise<void>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('qsign webview 加载超时')), timeoutMs);
    readyResolvers.push(() => {
      clearTimeout(t);
      resolve();
    });
  });
}

/**
 * 本地生成签名头
 * @param url    完整请求 URL（含 query）
 * @param header 基础请求头对象
 * @returns {Promise<{ [k: string]: string }>} 6 个签名头；失败会 reject
 */
export async function qsignSign(url: string, header: Record<string, string> = {}) {
  requestMount();          // 第一次调用时才把 WebView 挂上
  await waitReady();
  const id = nextId();
  const payload = JSON.stringify({ cmd: 'sign', id, url, header });
  return new Promise<Record<string, string>>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('qsign 签名超时（首次调用需要启动模拟器，约 1-3 秒）'));
    }, 30000);
    pending.set(id, { resolve, reject, timer });
    try {
      webviewRef.postMessage(payload);
    } catch (e: any) {
      pending.delete(id);
      clearTimeout(timer);
      reject(new Error('postMessage 失败: ' + (e && e.message)));
    }
  });
}

/**
 * 挂在 App 根组件里（只挂一次）。零尺寸、不可见、不拦截触摸。
 * 建议放在最外层 View 的最后一个子节点。
 */
export function QSignHost() {
  const ref = useRef<any>(null);
  const [show, setShow] = useState(mountRequested);
  useEffect(() => {
    const fn = () => setShow(true);
    mountListeners.add(fn);
    if (mountRequested) fn();
    return () => {
      mountListeners.delete(fn);
      webviewRef = null;
    };
  }, []);
  if (!show) return null;   // 没请求签名就不渲染，零干扰
  return (
    <View pointerEvents="none" style={{ position: 'absolute', width: 1, height: 1, left: 0, top: 0, zIndex: -1 }}>
    <WebView
      ref={ref}
      source={{ uri: 'file:///android_asset/qsign.html' }}
      originWhitelist={['*']}
      allowFileAccess
      allowFileAccessFromFileURLs
      allowUniversalAccessFromFileURLs
      javaScriptEnabled
      domStorageEnabled={false}
      cacheEnabled={false}
      onMessage={onMessage}
      onLoadEnd={() => {
        webviewRef = ref.current;
        (ref.current as any).__loaded = true;
        const list = readyResolvers;
        readyResolvers = [];
        list.forEach((fn) => fn());
      }}
      onError={() => { webviewRef = null; }}
      pointerEvents="none"
      // 绝对定位 + 零尺寸 + zIndex -1：完全不参与布局，也不挡触摸
      // 关键：尺寸不能是 0、opacity 不能是 0 —— 否则 Chromium 视作被遮挡，会对渲染进程节流/冻结
      style={{ width: 1, height: 1, opacity: 0.01 }}
    />
    </View>
  );
}
