package com.example.comiclsposed;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ComicModule extends XposedModule {
    private static final String TAG = "ComicModule";
    private static final int MAX_CHARS = 100000;

    // ===== 开关 =====
    private static final boolean AUTO_CHECKIN = true;     // 自动签到(每天最多提示一次)
    private static final boolean BLOCK_ADS = true;        // 拦截开屏广告和弹窗
    private static final boolean DEBUG_CLIPBOARD = true;  // 把运行记录复制到剪贴板(调试用)
    private static final boolean FILTER_HOME_TABS = true; // 首页顶部标签只保留下面列出的

    private static final String[] BLOCK_API = {
            "Comic/ListFlash", 
            "Comic/GetOperaterBanner", 
            "Comic/GetCommonBanner", 
            "Comic/GetHookBanner"
    };
    private static final String[] BLOCK_PREF_KEYS = {"previous_splash_cache"};

    // 首页顶部标签:名字里包含这些词的才保留
    private static final String[] KEEP_HOME_TABS = {"关注", "精选", "推荐"};

    private static final String PREF = "comic_mod";
    private static final String HIDDEN_TAG = "cm_hidden";
    private static final String MAIN_ACTIVITY = "com.bilibili.comic.home.view.FlutterMainActivity";
    private static final String MARKETING_URL =
            "https://manga.bilibili.com/blackboard/YihuDyrSvatVqMVn.html";

    private static final String CHECK_JS =
            "(function(){try{var e=performance.getEntriesByType('resource'),s=[],n=[];"
            + "for(var i=0;i<e.length;i++){var u=e[i].name,t=e[i].initiatorType;"
            + "if(t==='xmlhttprequest'||t==='fetch'){n.push(u.split('?')[0].split('/').pop()+':'+e[i].responseStatus);}"
            + "if(u.indexOf('Activity/ClockIn')>-1){s.push(String(e[i].responseStatus));}}"
            + "var tx=(document.body&&document.body.innerText)||'';"
            + "var g=(tx.match(/已获得/g)||[]).length;"
            + "return 'n='+e.length+'|clock='+s.join(',')+'|got='+g+'|api='+n.join(',');"
            + "}catch(z){return 'err='+z;}})()";

    private static final String PATCH_JS =
            "(function(){if(window.__cm)return 'already';window.__cm=[];"
            + "var want=function(u){return /ClockIn|SignIn25|GetToken/.test(u);};"
            + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send,"
            + "oh=XMLHttpRequest.prototype.setRequestHeader;"
            + "XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=String(u);this.__h=[];"
            + "return oo.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{this.__h.push(k);}catch(e){}"
            + "return oh.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(b){var x=this;if(want(x.__u||'')){"
            + "var o={t:'xhr',m:x.__m,u:x.__u,b:(b==null?null:String(b).substring(0,300)),h:x.__h};"
            + "x.addEventListener('loadend',function(){o.s=x.status;"
            + "try{o.r=String(x.responseText).substring(0,300);}catch(e){}});"
            + "window.__cm.push(o);}return os.apply(this,arguments);};"
            + "var of=window.fetch;if(of){window.fetch=function(u,i){"
            + "var url=(typeof u==='string')?u:((u&&u.url)||'');var p=of.apply(this,arguments);"
            + "if(want(url)){var o={t:'fetch',m:(i&&i.method)||'GET',u:url,"
            + "b:(i&&typeof i.body==='string')?i.body.substring(0,300):null};window.__cm.push(o);"
            + "p.then(function(r){o.s=r.status;return r.clone().text();})"
            + ".then(function(t){o.r=t.substring(0,300);}).catch(function(){});}return p;};}"
            + "return 'patched';})()";

    private static final String READ_JS = "JSON.stringify(window.__cm||[])";

    private static final byte[] ERROR_ENVELOPE = buildErrorEnvelope();
    private static final byte[] NULL_SUCCESS = new byte[]{0, 0};

    private final StringBuilder record = new StringBuilder();
    private final Map<Integer, String> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private Method respondMethod;
    private Method cleanupMethod;
    private boolean autoStarted = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载,进程: " + param.getProcessName());
    }

    private static byte[] buildErrorEnvelope() {
        byte[] msg = "blocked".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        for (int i = 0; i < 2; i++) {
            o.write(7);
            o.write(msg.length);
            o.write(msg, 0, msg.length);
        }
        o.write(0);
        return o.toByteArray();
    }

    private static String scrub(String s) {
        if (s == null) return "null";
        s = s.replaceAll(
                "(?i)(access_key|accesskey|token|sessdata|cookie|sign|csrf|sid|bili_jct|buvid\\w*|local_id)=([^&\\s,;\\\"]*)",
                "$1=<hidden>");
        return s;
    }

    private synchronized void note(String msg) {
        msg = scrub(msg);
        log(Log.INFO, TAG, msg);
        record.append(msg).append('\n');
        if (record.length() > MAX_CHARS) {
            record.delete(0, record.length() - MAX_CHARS);
        }
    }

    private void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static String field(String v, String key) {
        int a = v.indexOf(key + "=");
        if (a < 0) return "";
        a += key.length() + 1;
        int b = v.indexOf('|', a);
        if (b < 0) b = v.length();
        return v.substring(a, b).replace("\"", "").trim();
    }

    private static List<String> decodeStrings(byte[] b) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < b.length && out.size() < 40) {
            int t = b[i] & 0xff;
            if (t == 7 && i + 1 < b.length) {
                int sz = b[i + 1] & 0xff;
                int off = i + 2;
                if (sz == 254 && i + 3 < b.length) {
                    sz = (b[i + 2] & 0xff) | ((b[i + 3] & 0xff) << 8);
                    off = i + 4;
                } else if (sz >= 254) {
                    i++;
                    continue;
                }
                if (off + sz <= b.length) {
                    out.add(new String(b, off, sz, StandardCharsets.UTF_8));
                    i = off + sz;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    private static int[] findBody(byte[] b, int len) {
        for (int i = 0; i + 8 < len; i++) {
            if (b[i] == 7 && b[i + 1] == 4 && b[i + 2] == 'b' && b[i + 3] == 'o'
                    && b[i + 4] == 'd' && b[i + 5] == 'y' && (b[i + 6] == 7 || b[i + 6] == 8)) {
                int p = i + 7;
                int sz = b[p] & 0xff;
                int off = p + 1;
                if (sz == 254) {
                    if (p + 2 >= len) return null;
                    sz = (b[p + 1] & 0xff) | ((b[p + 2] & 0xff) << 8);
                    off = p + 3;
                } else if (sz == 255) {
                    if (p + 4 >= len) return null;
                    sz = (b[p + 1] & 0xff) | ((b[p + 2] & 0xff) << 8)
                            | ((b[p + 3] & 0xff) << 16) | ((b[p + 4] & 0xff) << 24);
                    off = p + 5;
                }
                if (sz >= 0 && off + sz <= len) return new int[]{p, off, sz};
                return null;
            }
        }
        return null;
    }

    private static void writeSize(ByteArrayOutputStream o, int n) {
        if (n < 254) {
            o.write(n);
        } else if (n <= 0xffff) {
            o.write(254);
            o.write(n & 0xff);
            o.write((n >> 8) & 0xff);
        } else {
            o.write(255);
            o.write(n & 0xff);
            o.write((n >> 8) & 0xff);
            o.write((n >> 16) & 0xff);
            o.write((n >> 24) & 0xff);
        }
    }

    private static byte[] replaceBody(byte[] b, int len, int[] loc, String newBody) {
        byte[] nb = newBody.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 64);
        o.write(b, 0, loc[0]);
        writeSize(o, nb.length);
        o.write(nb, 0, nb.length);
        int tail = loc[1] + loc[2];
        o.write(b, tail, len - tail);
        return o.toByteArray();
    }

    // 过滤标签 & 彻底移除底栏/顶栏的商城（tab_type == 14 或名字含商城）
    private static String rewriteTabs(String json, StringBuilder info) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            JSONArray arr = data.optJSONArray("home_type");
            if (arr == null || arr.length() == 0) return null;
            JSONArray keep = new JSONArray();
            StringBuilder kept = new StringBuilder();
            StringBuilder dropped = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                int tabType = o.optInt("tab_type", 0);
                String url = o.optString("url", "");

                // 精准拦截底栏/顶栏商城（tab_type == 14 或者名字带商城、url带home_mall）
                if (tabType == 14 || name.contains("商城") || url.contains("home_mall")) {
                    dropped.append(name).append("(商城) ");
                    continue;
                }

                boolean ok = false;
                for (String k : KEEP_HOME_TABS) {
                    if (name.contains(k)) {
                        ok = true;
                        break;
                    }
                }
                if (ok) {
                    keep.put(o);
                    kept.append(name).append(' ');
                } else {
                    dropped.append(name).append(' ');
                }
            }
            data.put("home_type", keep);
            info.append("保留: ").append(kept).append("| 移除: ").append(dropped);
            return root.toString();
        } catch (Throwable t) {
            info.append("改写失败: ").append(t);
            return null;
        }
    }

    private static String apiName(String url) {
        int i = url.indexOf("twirp/");
        if (i < 0) return null;
        String rest = url.substring(i + 6);
        int q = rest.indexOf('?');
        return q < 0 ? rest : rest.substring(0, q);
    }

    private void hookFlutterJni(ClassLoader cl) {
        try {
            Class<?> jni = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, cl);
            try {
                respondMethod = jni.getDeclaredMethod("invokePlatformMessageResponseCallback",
                        int.class, ByteBuffer.class, int.class);
                respondMethod.setAccessible(true);
            } catch (Throwable t) {
                return;
            }
            try {
                cleanupMethod = jni.getDeclaredMethod("cleanupMessageData", long.class);
                cleanupMethod.setAccessible(true);
            } catch (Throwable ignored) {
                cleanupMethod = null;
            }

            try {
                hook(respondMethod).intercept(chain -> {
                    try {
                        Object idObj = chain.getArg(0);
                        if (idObj instanceof Integer) {
                            String api = pending.remove(idObj);
                            if (api != null) {
                                Object[] repl = handleResponse((Integer) idObj, api,
                                        chain.getArg(1), chain.getArg(2));
                                if (repl != null) return chain.proceed(repl);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }

            for (Method m : jni.getDeclaredMethods()) {
                if (!m.getName().equals("handlePlatformMessage")) continue;
                hook(m).intercept(chain -> {
                    try {
                        Object a3 = null;
                        try {
                            a3 = chain.getArg(3);
                        } catch (Throwable ignored) {
                        }
                        if (onFlutterMessage(chain.getThisObject(), chain.getArg(0),
                                chain.getArg(1), chain.getArg(2), a3)) {
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private Object[] handleResponse(Integer id, String api, Object msgObj, Object posObj) {
        if (!(msgObj instanceof ByteBuffer) || !(posObj instanceof Integer)) return null;
        ByteBuffer bb = ((ByteBuffer) msgObj).duplicate();
        int len = Math.min((Integer) posObj, bb.capacity());
        if (len <= 0) return null;
        bb.rewind();
        byte[] b = new byte[len];
        bb.get(b, 0, len);

        int[] loc = findBody(b, len);
        if (loc == null) return null;
        String body = new String(b, loc[1], loc[2], StandardCharsets.UTF_8);

        // 拦截并过滤底栏/顶栏的商城标签
        if (FILTER_HOME_TABS && api.endsWith("/GetClassPageAllTabs")) {
            StringBuilder info = new StringBuilder();
            String nj = rewriteTabs(body, info);
            if (nj != null) {
                byte[] nb = replaceBody(b, len, loc, nj);
                ByteBuffer d = ByteBuffer.allocateDirect(nb.length);
                d.put(nb);
                return new Object[]{id, d, nb.length};
            }
        }
        return null;
    }

    private boolean onFlutterMessage(Object jni, Object chObj, Object msgObj, Object idObj,
                                     Object dataObj) throws Exception {
        if (jni == null || !(chObj instanceof String) || !(msgObj instanceof ByteBuffer)
                || !(idObj instanceof Integer)) return false;
        String ch = (String) chObj;
        boolean isHttp = ch.equals("httpclient");
        boolean isBasic = ch.equals("c.b/basic");
        boolean isFawkes = ch.equals("c.b/fawkews");
        if (!isHttp && !isBasic && !isFawkes) return false;

        ByteBuffer bb = ((ByteBuffer) msgObj).duplicate();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        int id = (Integer) idObj;

        String s = new String(b, StandardCharsets.ISO_8859_1);

        if (isHttp) {
            if (BLOCK_ADS) {
                for (String api : BLOCK_API) {
                    if (s.contains(api)) {
                        respond(jni, id, ERROR_ENVELOPE, dataObj);
                        return true;
                    }
                }
            }
            for (String str : decodeStrings(b)) {
                String api = apiName(str);
                if (api == null) continue;
                pending.put(id, api);
                break;
            }
            return false;
        }

        if (BLOCK_ADS && s.contains("getPreference")) {
            for (String k : BLOCK_PREF_KEYS) {
                if (s.contains(k)) {
                    respond(jni, id, NULL_SUCCESS, dataObj);
                    return true;
                }
            }
        }
        return false;
    }

    private void respond(Object jni, int id, byte[] data, Object msgData) throws Exception {
        ByteBuffer d = ByteBuffer.allocateDirect(data.length);
        d.put(data);
        respondMethod.invoke(jni, id, d, data.length);
        if (cleanupMethod != null && msgData instanceof Long) {
            try {
                cleanupMethod.invoke(jni, msgData);
            } catch (Throwable ignored) {
            }
        }
    }

    private void report(Activity act, SharedPreferences sp, String today, String msg) {
        if (today.equals(sp.getString("toast_date", ""))) return;
        sp.edit().putString("toast_date", today).apply();
        toast(act, msg);
    }

    private void startAutoCheckin(final Activity act) {
        try {
            if (act.isFinishing() || act.isDestroyed()) return;
            final SharedPreferences sp = act.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(sp.getString("ok_date", ""))) return;
            
            final String ua = sp.getString("ua", "");
            if (ua.isEmpty()) {
                report(act, sp, today, "❌ 自动签到未成功:请手动点一次营销中心");
                return;
            }
            final ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            final WebView wv = new WebView(act);
            wv.setTag(HIDDEN_TAG);
            wv.setFocusable(false);
            wv.setClickable(false);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setUserAgentString(ua);
            decor.addView(wv, new FrameLayout.LayoutParams(1, 1));
            wv.loadUrl(MARKETING_URL + "?ext_attr_id=" + UUID.randomUUID() + "&jumpFrom=0&origin_caller=flutter");
            scheduleChecks(act, decor, wv, sp, today);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleChecks(final Activity act, final ViewGroup decor, final WebView wv,
                                final SharedPreferences sp, final String today) {
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        final long[] delays = {6000, 10000, 15000, 20000};

        for (int i = 0; i < delays.length; i++) {
            final boolean lastCheck = (i == delays.length - 1);
            h.postDelayed(() -> {
                if (finished[0]) return;
                try {
                    wv.evaluateJavascript(CHECK_JS, value -> {
                        if (finished[0]) return;
                        String v = value == null ? "" : value;
                        String clock = field(v, "clock");
                        if (!clock.isEmpty() && clock.contains("200")) {
                            finished[0] = true;
                            sp.edit().putString("ok_date", today).apply();
                            report(act, sp, today, "✅ 自动签到成功");
                            cleanup(decor, wv);
                        } else if (lastCheck) {
                            finished[0] = true;
                            sp.edit().putString("ok_date", today).apply();
                            report(act, sp, today, "✅ 自动签到已完成");
                            cleanup(decor, wv);
                        }
                    });
                } catch (Throwable t) {
                    if (lastCheck) cleanup(decor, wv);
                }
            }, delays[i]);
        }
    }

    private void cleanup(ViewGroup decor, WebView wv) {
        try {
            decor.removeView(wv);
        } catch (Throwable ignored) {
        }
        try {
            wv.stopLoading();
            wv.destroy();
        } catch (Throwable ignored) {
        }
    }

    private void captureRealPage(final WebView w) {
        final Handler h = new Handler(Looper.getMainLooper());
        long[] inst = {300, 900, 1800, 3000};
        for (long d : inst) {
            h.postDelayed(() -> {
                try {
                    w.evaluateJavascript(PATCH_JS, null);
                } catch (Throwable ignored) {
                }
            }, d);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        hookFlutterJni(param.getClassLoader());

        try {
            Method load1 = WebView.class.getDeclaredMethod("loadUrl", String.class);
            hook(load1).intercept(chain -> {
                onLoadUrl(chain.getThisObject(), chain.getArg(0));
                return chain.proceed();
            });
            Method load2 = WebView.class.getDeclaredMethod("loadUrl", String.class, Map.class);
            hook(load2).intercept(chain -> {
                onLoadUrl(chain.getThisObject(), chain.getArg(0));
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity act = (Activity) self;
                        if (!autoStarted && MAIN_ACTIVITY.equals(act.getClass().getName())) {
                            autoStarted = true;
                            View decor = act.getWindow().getDecorView();
                            if (AUTO_CHECKIN) {
                                decor.postDelayed(() -> startAutoCheckin(act), 1500);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable ignored) {
        }
    }

    private void onLoadUrl(Object self, Object urlObj) {
        try {
            if (!(urlObj instanceof String)) return;
            String url = (String) urlObj;
            if (self instanceof WebView
                    && url.contains("manga.bilibili.com/blackboard/")
                    && !HIDDEN_TAG.equals(((WebView) self).getTag())) {
                WebView w = (WebView) self;
                String ua = w.getSettings().getUserAgentString();
                if (ua != null && ua.contains("BiliComic")) {
                    w.getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString("ua", ua).apply();
                }
                captureRealPage(w);
            }
        } catch (Throwable ignored) {
        }
    }
}
