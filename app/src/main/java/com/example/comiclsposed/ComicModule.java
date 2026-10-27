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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final String[] BLOCK_API = {"Comic/ListFlash", "Comic/GetOperaterBanner"};
    private static final String[] BLOCK_PREF_KEYS = {"previous_splash_cache"};

    // 首页顶部标签:名字里包含这些词的才保留
    private static final String[] KEEP_HOME_TABS = {"关注", "精选", "推荐"};

    // 调试:返回内容里出现这些词时,记录该接口和附近内容(用来找商城/畅读卡横幅的来源)
    private static final String[] KEYWORDS = {"畅读卡", "商城", "mall"};
    // 调试:这些接口多记录一些内容
    private static final String[] BIG_API = {"AppInit", "GetABFlags", "GetInitInfo"};

    private static final String PREF = "comic_mod";
    private static final String HIDDEN_TAG = "cm_hidden";
    private static final String MAIN_ACTIVITY = "com.bilibili.comic.home.view.FlutterMainActivity";
    private static final String MARKETING_URL =
            "https://manga.bilibili.com/blackboard/YihuDyrSvatVqMVn.html";

    private static final String CHECK_JS =
            "(function(){try{var e=performance.getEntriesByType('resource'),s=[],n=[];"
            + "for(var i=0;i<e.length;i++){var u=e[i].name,t=e[i].initiatorType;"
            + "if(t==='xmlhttprequest'||t==='fetch'){n.push(u.split('?')[0].split('/').pop()+':'+e[i].responseStatus);}"
            + "if(u.indexOf('Activity/ClockIn?')>-1){s.push(String(e[i].responseStatus));}}"
            + "var tx=(document.body&&document.body.innerText)||'';"
            + "var g=(tx.match(/已获得/g)||[]).length;"
            + "return 'n='+e.length+'|clock='+s.join(',')+'|got='+g+'|api='+n.join(',');"
            + "}catch(z){return 'err='+z;}})()";

    // 在真实的营销中心页面里记录签到相关请求(只在你手动打开营销中心时运行)
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

    // Flutter 标准编码:失败回复 / 空成功回复
    private static final byte[] ERROR_ENVELOPE = buildErrorEnvelope();
    private static final byte[] NULL_SUCCESS = new byte[]{0, 0};

    private final StringBuilder record = new StringBuilder();
    private final Map<Integer, String> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private Method respondMethod;
    private Method cleanupMethod;
    private boolean autoStarted = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载,进程: " + param.getProcessName());
    }

    // ---------- 工具方法 ----------

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
                "(?i)(access_key|accesskey|token|sessdata|cookie|sign|csrf|sid|bili_jct|buvid\\w*|local_id)=([^&\\s,;\"]*)",
                "$1=<hidden>");
        s = s.replaceAll(
                "(?i)(\\\\?\"(?:access_key|accesskey|token|sessdata|cookie|sign|csrf|bili_jct)\\\\?\"\\s*:\\s*\\\\?\")[^\"\\\\]*",
                "$1<hidden>");
        s = s.replaceAll("\\d{8,}", "<num>");
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

    private synchronized void copyRecord(Context ctx) {
        if (!DEBUG_CLIPBOARD) return;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ComicModule", record.toString()));
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

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // Flutter 标准编码里的字符串(类型 7),够用于日志
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

    private static String readable(byte[] b, int len, int max) {
        StringBuilder sb = new StringBuilder();
        boolean lastDot = false;
        int n = Math.min(len, b.length);
        for (int i = 0; i < n && sb.length() < max; i++) {
            int x = b[i] & 0xff;
            if (x >= 32 && x < 127) {
                sb.append((char) x);
                lastDot = false;
            } else if (x >= 0xC0) {
                // 可能是 UTF-8 中文,交给下面的整体解码
                lastDot = false;
                int need = x >= 0xF0 ? 4 : (x >= 0xE0 ? 3 : 2);
                if (i + need <= n) {
                    sb.append(new String(b, i, need, StandardCharsets.UTF_8));
                    i += need - 1;
                }
            } else if (!lastDot) {
                sb.append('·');
                lastDot = true;
            }
        }
        return sb.toString();
    }

    // ---- Flutter 回复里的 body 字符串:定位 / 替换 / 改写 ----

    // 找到 "body" 这个键对应的字符串,返回 {长度字段起点, 字符串起点, 字符串字节数}
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

    // 用新的 body 字符串替换旧的,其余字节原样保留
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

    // 首页顶部标签过滤:返回改写后的 JSON,不需要改写时返回 null。info 里写说明文字
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
                String name = o == null ? "" : o.optString("name", "");
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
            if (keep.length() == 0) {
                info.append("没有匹配的标签,保持原样。原有: ").append(dropped);
                return null;
            }
            if (keep.length() == arr.length()) {
                info.append("标签本来就只有: ").append(kept);
                return null;
            }
            data.put("home_type", keep);
            info.append("保留: ").append(kept).append("| 移除: ").append(dropped);
            return root.toString();
        } catch (Throwable t) {
            info.append("改写失败: ").append(t);
            return null;
        }
    }

    private static String snippet(String s, int idx, int before, int after) {
        int a = Math.max(0, idx - before);
        int b = Math.min(s.length(), idx + after);
        return s.substring(a, b);
    }

    private static String apiName(String url) {
        int i = url.indexOf("twirp/");
        if (i < 0) return null;
        String rest = url.substring(i + 6);
        int q = rest.indexOf('?');
        return q < 0 ? rest : rest.substring(0, q);
    }

    private int bump(String key) {
        synchronized (counts) {
            Integer v = counts.get(key);
            int c = v == null ? 1 : v + 1;
            counts.put(key, c);
            return c;
        }
    }

    // ---------- 拦截开屏广告和弹窗(FlutterJNI 层) ----------

    private void hookFlutterJni(ClassLoader cl) {
        try {
            Class<?> jni = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, cl);
            try {
                respondMethod = jni.getDeclaredMethod("invokePlatformMessageResponseCallback",
                        int.class, ByteBuffer.class, int.class);
                respondMethod.setAccessible(true);
            } catch (Throwable t) {
                note("【拦截】找不到回复方法: " + t);
                return;
            }
            try {
                cleanupMethod = jni.getDeclaredMethod("cleanupMessageData", long.class);
                cleanupMethod.setAccessible(true);
            } catch (Throwable ignored) {
                cleanupMethod = null;
            }

            // 处理接口返回:记录(调试)+ 改写首页标签;失败不影响拦截功能
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
                    } catch (Throwable t) {
                        note("【返回】处理出错: " + t);
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                note("【拦截】处理返回内容失败: " + t);
            }

            int n = 0;
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
                    } catch (Throwable t) {
                        note("【拦截】出错: " + t);
                    }
                    return chain.proceed();
                });
                n++;
            }
            note("【拦截】已监听 FlutterJNI x" + n);
        } catch (Throwable t) {
            note("【拦截】FlutterJNI 监听失败(非主进程可忽略): " + t);
        }
    }

    // 返回 null 表示不改动;否则返回替换后的参数
    private Object[] handleResponse(Integer id, String api, Object msgObj, Object posObj) {
        if (!(msgObj instanceof ByteBuffer) || !(posObj instanceof Integer)) return null;
        ByteBuffer bb = ((ByteBuffer) msgObj).duplicate();
        int len = Math.min((Integer) posObj, bb.capacity());
        if (len <= 0) return null;
        bb.rewind();
        byte[] b = new byte[len];
        bb.get(b, 0, len);

        if (api.startsWith("fawkes:")) {
            if (bump("fk:" + api) <= 1) note("【配置值】" + api + " => " + readable(b, len, 300));
            return null;
        }

        int[] loc = findBody(b, len);
        if (loc == null) {
            if (bump("nb:" + api) <= 1) note("【接口】" + api + " (没找到body) " + readable(b, len, 120));
            return null;
        }
        String body = new String(b, loc[1], loc[2], StandardCharsets.UTF_8);

        // 记录:每个接口首次出现时记一次
        boolean big = false;
        for (String k : BIG_API) {
            if (api.endsWith("/" + k)) big = true;
        }
        int maxN = (api.endsWith("/GetABFlags") || api.endsWith("/GetConf")) ? 4 : 1;
        int n = bump("api:" + api);
        if (n <= maxN) {
            int cap = big ? (api.endsWith("/GetABFlags") ? 800 : 4500) : 300;
            note("【接口】" + api + " (" + loc[2] + "字符) " + (body.length() > cap ? body.substring(0, cap) : body));
        }
        // 关键词:出现商城/畅读卡时,记录附近内容
        for (String kw : KEYWORDS) {
            int idx = body.indexOf(kw);
            if (idx >= 0 && bump("kw:" + api + kw) <= 1) {
                note("【关键词 " + kw + "】" + api + " ..." + snippet(body, idx, 400, 700));
            }
        }

        // 改写:首页顶部标签
        if (FILTER_HOME_TABS && api.endsWith("/GetClassPageAllTabs")) {
            StringBuilder info = new StringBuilder();
            String nj = rewriteTabs(body, info);
            if (bump("tabnote") <= 3) note("【标签】" + info);
            if (nj != null) {
                byte[] nb = replaceBody(b, len, loc, nj);
                ByteBuffer d = ByteBuffer.allocateDirect(nb.length);
                d.put(nb);
                return new Object[]{id, d, nb.length};
            }
        }
        return null;
    }

    // 返回 true 表示这条消息被拦截,不再交给 App
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

        if (isFawkes) {
            String line = String.join(" ", decodeStrings(b));
            if (line.contains("goods.dynamic") || line.contains("strategy.show")
                    || line.contains("flutter.skin_replace") || line.contains("config.animated_cover")) {
                pending.put(id, "fawkes:" + line);
            }
            return false;
        }

        String s = new String(b, StandardCharsets.ISO_8859_1);

        if (isHttp) {
            if (BLOCK_ADS) {
                for (String api : BLOCK_API) {
                    if (s.contains(api)) {
                        respond(jni, id, ERROR_ENVELOPE, dataObj);
                        if (bump("blk:" + api) <= 3) note("【拦截】已拦截请求 " + api);
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

        // c.b/basic
        if (BLOCK_ADS && s.contains("getPreference")) {
            for (String k : BLOCK_PREF_KEYS) {
                if (s.contains(k)) {
                    respond(jni, id, NULL_SUCCESS, dataObj);
                    if (bump("blk:" + k) <= 3) note("【拦截】已清空缓存 " + k);
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

    // ---------- 自动签到(每天最多提示一次) ----------

    // 每天只提示一次:成功或失败,以当天第一次得出结果时为准
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
            if (today.equals(sp.getString("ok_date", ""))) {
                note("【自动签到】今天已完成,跳过");
                return;
            }
            final String ua = sp.getString("ua", "");
            if (ua.isEmpty()) {
                note("【自动签到】没有保存的网页环境");
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
            wv.loadUrl(MARKETING_URL + "?ext_attr_id=" + UUID.randomUUID()
                    + "&jumpFrom=0&origin_caller=flutter");
            note("【自动签到】后台页面已启动");
            scheduleChecks(act, decor, wv, sp, today);
        } catch (Throwable t) {
            note("【自动签到】启动失败: " + t);
        }
    }

    private void scheduleChecks(final Activity act, final ViewGroup decor, final WebView wv,
                                final SharedPreferences sp, final String today) {
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        final long[] delays = {6000, 10000, 15000, 20000};

        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);   // 周日=1 ... 周六=7
        final int dayIdx = (dow == Calendar.SUNDAY) ? 7 : dow - 1;     // 周一=1 ... 周日=7

        for (int i = 0; i < delays.length; i++) {
            final boolean lastCheck = (i == delays.length - 1);
            h.postDelayed(() -> {
                if (finished[0]) return;
                try {
                    wv.evaluateJavascript(CHECK_JS, value -> {
                        if (finished[0]) return;
                        String v = value == null ? "" : value;
                        note("【自动签到】检查: " + v);
                        String clock = field(v, "clock");
                        int n = toInt(field(v, "n"));
                        int got = toInt(field(v, "got"));

                        if (!clock.isEmpty()) {
                            finished[0] = true;
                            if (clock.contains("200")) {
                                sp.edit().putString("ok_date", today).apply();
                                report(act, sp, today, "✅ 自动签到成功");
                            } else {
                                report(act, sp, today, "❌ 自动签到未成功(状态 " + clock + ")");
                            }
                            cleanup(decor, wv);
                        } else if (lastCheck) {
                            finished[0] = true;
                            if (n >= 20 && got >= dayIdx) {
                                sp.edit().putString("ok_date", today).apply();
                                report(act, sp, today, "✅ 今日已签到");
                            } else {
                                report(act, sp, today, "❌ 自动签到未成功");
                            }
                            cleanup(decor, wv);
                        }
                    });
                } catch (Throwable t) {
                    note("【自动签到】检查失败: " + t);
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

    // 你手动打开营销中心时:保存网页环境,并记录页面发出的签到相关请求
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
        long[] reads = {9000, 16000};
        for (long d : reads) {
            h.postDelayed(() -> {
                try {
                    w.evaluateJavascript(READ_JS, v -> {
                        String s = v == null ? "" : v;
                        if (s.length() > 3500) s = s.substring(0, 3500);
                        note("【页面抓取】" + s);
                        copyRecord(w.getContext());
                    });
                } catch (Throwable ignored) {
                }
            }, d);
        }
    }

    // ---------- hook ----------

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        hookFlutterJni(param.getClassLoader());

        // 页面创建(只记录)
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self != null) note("【页面】" + self.getClass().getName());
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onCreate 失败", t);
        }

        // 网页加载:你手动打开营销中心时,保存网页环境并抓取签到请求
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
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook WebView 失败", t);
        }

        // 记录 App 给网页提供了哪些 JS 桥
        try {
            Method aji = WebView.class.getDeclaredMethod("addJavascriptInterface",
                    Object.class, String.class);
            hook(aji).intercept(chain -> {
                try {
                    Object obj = chain.getArg(0);
                    Object name = chain.getArg(1);
                    if (obj != null) {
                        StringBuilder sb = new StringBuilder();
                        for (Method m : obj.getClass().getMethods()) {
                            if (m.isAnnotationPresent(JavascriptInterface.class)) {
                                sb.append(m.getName()).append(' ');
                            }
                        }
                        note("【JS桥】" + name + " <- " + obj.getClass().getName() + " : " + sb);
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook addJavascriptInterface 失败", t);
        }

        // 进入主页后启动自动签到
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
                            if (DEBUG_CLIPBOARD) {
                                long[] cps = {12000, 26000, 45000, 70000, 100000};
                                for (long c : cps) {
                                    decor.postDelayed(() -> copyRecord(act), c);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onResume 失败", t);
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
