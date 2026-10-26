package com.example.comiclsposed;

import android.app.Activity;
import android.app.Dialog;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ComicModule extends XposedModule {
    private static final String TAG = "ComicModule";
    private static final int MAX_CHARS = 60000;

    // ===== 开关 =====
    private static final boolean AUTO_CHECKIN = true;     // 自动签到
    private static final boolean BLOCK_ADS = true;        // 拦截开屏广告和弹窗(实验性)
    private static final boolean DEBUG_CLIPBOARD = true;  // 签到结束时把记录复制到剪贴板(调试用)

    // 被拦截的接口、被清空的本地缓存
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
            + "var x=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').substring(0,150);"
            + "return 'clock='+s.join(',')+'|api='+n.join(',')+'|txt='+x;"
            + "}catch(z){return 'err='+z;}})()";

    private final StringBuilder record = new StringBuilder();
    private final Set<String> hookedHandlers = new HashSet<>();
    private boolean autoStarted = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载,进程: " + param.getProcessName());
    }

    // ---------- 工具方法 ----------

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

    // ---------- 拦截开屏广告和弹窗 ----------

    private void hookMethodChannel(ClassLoader cl) {
        try {
            Class<?> mc = Class.forName("io.flutter.plugin.common.MethodChannel", false, cl);

            Field nf = null;
            try {
                nf = mc.getDeclaredField("name");
            } catch (Throwable ignored) {
                for (Field f : mc.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        nf = f;
                        break;
                    }
                }
            }
            if (nf == null) {
                note("【拦截】找不到 MethodChannel 的名字字段");
                return;
            }
            nf.setAccessible(true);
            final Field nameField = nf;

            Method set = null;
            for (Method m : mc.getDeclaredMethods()) {
                if (m.getName().equals("setMethodCallHandler")) {
                    set = m;
                    break;
                }
            }
            if (set == null) {
                note("【拦截】找不到 setMethodCallHandler");
                return;
            }

            hook(set).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object ch = chain.getThisObject();
                    Object handler = chain.getArg(0);
                    if (ch != null && handler != null) {
                        String name = String.valueOf(nameField.get(ch));
                        if (name.equals("httpclient") || name.equals("c.b/basic")) {
                            hookHandler(handler.getClass(), name);
                        }
                    }
                } catch (Throwable t) {
                    note("【拦截】处理 handler 出错: " + t);
                }
                return result;
            });
            note("【拦截】已监听 MethodChannel");
        } catch (Throwable t) {
            note("【拦截】MethodChannel 监听失败: " + t);
        }
    }

    private void hookHandler(Class<?> cls, String channel) {
        synchronized (hookedHandlers) {
            if (!hookedHandlers.add(cls.getName())) return;
        }
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals("onMethodCall") || m.getParameterTypes().length != 2) continue;
            try {
                hook(m).intercept(chain -> {
                    if (BLOCK_ADS && tryBlock(chain.getArg(0), chain.getArg(1))) {
                        return null;
                    }
                    return chain.proceed();
                });
                n++;
            } catch (Throwable t) {
                note("【拦截】hook 失败: " + t);
            }
        }
        note("【拦截】" + channel + " 的处理类: " + cls.getName() + " (hook x" + n + ")");
    }

    private boolean tryBlock(Object call, Object result) {
        try {
            if (call == null || result == null) return false;
            Class<?> cc = call.getClass();
            String method = String.valueOf(cc.getField("method").get(call));
            String args = String.valueOf(cc.getField("arguments").get(call));
            ClassLoader cl = cc.getClassLoader();

            if ("send".equals(method)) {
                for (String api : BLOCK_API) {
                    if (args.contains(api)) {
                        Class<?> rc = Class.forName("io.flutter.plugin.common.MethodChannel$Result", false, cl);
                        rc.getMethod("error", String.class, String.class, Object.class)
                                .invoke(result, "blocked", "blocked by module", null);
                        note("【拦截】已拦截请求 " + api);
                        return true;
                    }
                }
            } else if ("getPreference".equals(method)) {
                for (String k : BLOCK_PREF_KEYS) {
                    if (args.contains(k)) {
                        Class<?> rc = Class.forName("io.flutter.plugin.common.MethodChannel$Result", false, cl);
                        rc.getMethod("success", Object.class).invoke(result, (Object) null);
                        note("【拦截】已清空缓存 " + k);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            note("【拦截】判断出错: " + t);
        }
        return false;
    }

    // ---------- 自动签到 ----------

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
                toast(act, "自动签到:请先手动打开一次营销中心完成初始化");
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
        final long[] delays = {5000, 9000, 13000, 18000};
        for (int i = 0; i < delays.length; i++) {
            final boolean lastCheck = (i == delays.length - 1);
            h.postDelayed(() -> {
                if (finished[0]) return;
                try {
                    wv.evaluateJavascript(CHECK_JS, value -> {
                        if (finished[0]) return;
                        String v = value == null ? "" : value;
                        note("【自动签到】检查: " + v);
                        int a = v.indexOf("clock=");
                        int b = a < 0 ? -1 : v.indexOf('|', a);
                        String tail = a < 0 ? "" : v.substring(a + 6, b < 0 ? v.length() : b).trim();
                        if (!tail.isEmpty()) {
                            finished[0] = true;
                            if (tail.contains("200")) {
                                sp.edit().putString("ok_date", today).apply();
                                toast(act, "✅ 营销中心自动签到完成");
                            } else {
                                toast(act, "签到请求已发出,状态: " + tail);
                            }
                            copyRecord(act);
                            cleanup(decor, wv);
                        } else if (lastCheck) {
                            finished[0] = true;
                            toast(act, "后台已打开营销中心,但没检测到签到请求");
                            copyRecord(act);
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
        hookMethodChannel(param.getClassLoader());

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

        // 网页加载:记录;用户手动打开营销中心时保存网页环境(初始化)
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

        // 原生弹窗(只记录)
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            hook(show).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self != null) note("【弹窗】" + self.getClass().getName());
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Dialog 失败", t);
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
                        if (AUTO_CHECKIN && !autoStarted
                                && MAIN_ACTIVITY.equals(act.getClass().getName())) {
                            autoStarted = true;
                            View decor = act.getWindow().getDecorView();
                            decor.postDelayed(() -> startAutoCheckin(act), 1500);
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
