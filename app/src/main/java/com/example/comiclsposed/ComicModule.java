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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ComicModule extends XposedModule {
    private static final String TAG = "ComicModule";
    private static final int MAX_CHARS = 60000;

    // ===== 开关 =====
    private static final boolean AUTO_CHECKIN = true;     // 自动签到(每天只执行一次)
    private static final boolean BLOCK_ADS = true;        // 拦截开屏广告和弹窗
    private static final boolean DEBUG_CLIPBOARD = true;  // 把运行记录复制到剪贴板(确认没问题后改成 false)

    private static final String[] BLOCK_API = {"Comic/ListFlash", "Comic/GetOperaterBanner"};
    private static final String[] BLOCK_PREF_KEYS = {"previous_splash_cache"};

    private static final String PREF = "comic_mod";
    private static final String HIDDEN_TAG = "cm_hidden";
    private static final String MAIN_ACTIVITY = "com.bilibili.comic.home.view.FlutterMainActivity";
    private static final String MARKETING_URL =
            "https://manga.bilibili.com/blackboard/YihuDyrSvatVqMVn.html";

    private static final String CHECK_JS =
            "(function(){try{var e=performance.getEntriesByType('resource'),s=[],n=[];"
            + "for(var i=0;i<e.length;i++){var u=e[i].name,t=e[i].initiatorType;"
            + "if(t==='xmlhttprequest'||t==='fetch'){n.push(u.split('?')[0].split('/').pop());}"
            + "if(u.indexOf('Activity/ClockIn?')>-1){s.push(String(e[i].responseStatus));}}"
            + "var tx=(document.body&&document.body.innerText)||'';"
            + "var g=(tx.match(/已获得/g)||[]).length;"
            + "return 'n='+e.length+'|clock='+s.join(',')+'|got='+g+'|api='+n.join(',');"
            + "}catch(z){return 'err='+z;}})()";

    // Flutter 标准编码:失败回复 / 空成功回复
    private static final byte[] ERROR_ENVELOPE = buildErrorEnvelope();
    private static final byte[] NULL_SUCCESS = new byte[]{0, 0};

    private final StringBuilder record = new StringBuilder();
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
                "(?i)(access_key|accesskey|token|sessdata|cookie|sign|csrf|sid)=([^&\\s,;\"]*)",
                "$1=<hidden>");
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
            int n = 0;
            for (Method m : jni.getDeclaredMethods()) {
                if (!m.getName().equals("handlePlatformMessage")) continue;
                hook(m).intercept(chain -> {
                    try {
                        if (BLOCK_ADS) {
                            Object a3 = null;
                            try {
                                a3 = chain.getArg(3);
                            } catch (Throwable ignored) {
                            }
                            if (tryBlock(chain.getThisObject(), chain.getArg(0),
                                    chain.getArg(1), chain.getArg(2), a3)) {
                                return null;
                            }
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

    private boolean tryBlock(Object jni, Object chObj, Object msgObj, Object idObj, Object dataObj)
            throws Exception {
        if (jni == null || respondMethod == null) return false;
        if (!(chObj instanceof String) || !(msgObj instanceof ByteBuffer)
                || !(idObj instanceof Integer)) return false;
        String ch = (String) chObj;
        boolean isHttp = ch.equals("httpclient");
        boolean isBasic = ch.equals("c.b/basic");
        if (!isHttp && !isBasic) return false;

        ByteBuffer bb = ((ByteBuffer) msgObj).duplicate();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        String s = new String(b, StandardCharsets.ISO_8859_1);
        int id = (Integer) idObj;

        if (isHttp) {
            for (String api : BLOCK_API) {
                if (s.contains(api)) {
                    respond(jni, id, ERROR_ENVELOPE, dataObj);
                    note("【拦截】已拦截请求 " + api);
                    return true;
                }
            }
        } else if (s.contains("getPreference")) {
            for (String k : BLOCK_PREF_KEYS) {
                if (s.contains(k)) {
                    respond(jni, id, NULL_SUCCESS, dataObj);
                    note("【拦截】已清空缓存 " + k);
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

    // ---------- 自动签到(每天只执行一次) ----------

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
                note("【自动签到】还没有初始化");
                if (!today.equals(sp.getString("warn_date", ""))) {
                    sp.edit().putString("warn_date", today).apply();
                    toast(act, "自动签到:请先手动打开一次营销中心完成初始化");
                }
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
                                toast(act, "✅ 今日自动签到完成");
                            } else {
                                toast(act, "自动签到失败(状态 " + clock + "),下次打开会重试");
                            }
                            cleanup(decor, wv);
                        } else if (lastCheck) {
                            finished[0] = true;
                            if (n >= 20 && got >= dayIdx) {
                                sp.edit().putString("ok_date", today).apply();
                                note("【自动签到】页面显示今天已签到,记为完成");
                            } else if (!today.equals(sp.getString("warn_date", ""))) {
                                sp.edit().putString("warn_date", today).apply();
                                toast(act, "自动签到未生效,请手动点一次营销中心");
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

        // 网页加载:你手动打开营销中心时,保存网页环境(初始化)
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
                                decor.postDelayed(() -> copyRecord(act), 10000);
                                decor.postDelayed(() -> copyRecord(act), 24000);
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
                    note("【自动签到】已保存网页环境(初始化完成)");
                    toast(w.getContext(), "自动签到:初始化完成");
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
