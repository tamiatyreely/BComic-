package com.example.comiclsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int MAX_CHARS = 80000;

    // ===== 开关 =====
    private static final boolean AUTO_CHECKIN = true;      // 自动签到
    private static final boolean DEBUG_TOAST = false;      // 每个页面/弹窗都弹提示(调试用)
    private static final boolean DEBUG_CLIPBOARD = true;   // 把记录复制到剪贴板(抓开屏/弹窗时用,抓完改成 false)

    private static final String PREF = "comic_mod";
    private static final String HIDDEN_TAG = "cm_hidden";
    private static final String MAIN_ACTIVITY = "com.bilibili.comic.home.view.FlutterMainActivity";
    private static final String MARKETING_URL =
            "https://manga.bilibili.com/blackboard/YihuDyrSvatVqMVn.html";

    private static final String CHECK_JS =
            "(function(){try{var e=performance.getEntriesByType('resource'),s=[];"
            + "for(var i=0;i<e.length;i++){if(e[i].name.indexOf('Activity/ClockIn')>-1)"
            + "{s.push(String(e[i].responseStatus));}}"
            + "return 'n='+e.length+';clock='+s.join(',');}catch(x){return 'err='+x;}})()";

    private final Set<String> dumped = new HashSet<>();
    private final StringBuilder record = new StringBuilder();
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
        s = s.replaceAll(
                "(?i)\"(token|csrf|sign|access_key|sessdata)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"<hidden>\"");
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

    private String describe(Intent it) {
        if (it == null) return "(no intent)";
        StringBuilder sb = new StringBuilder(it.toString());
        try {
            sb.append("\n    data = ").append(it.getDataString());
            Bundle b = it.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    String lk = k.toLowerCase();
                    String s;
                    if (lk.contains("token") || lk.contains("key")
                            || lk.contains("cookie") || lk.contains("auth")) {
                        s = "<hidden>";
                    } else {
                        s = String.valueOf(b.get(k));
                        if (s.length() > 300) s = s.substring(0, 300) + "...";
                    }
                    sb.append("\n    extra ").append(k).append(" = ").append(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
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
                        int idx = v.indexOf("clock=");
                        String tail = idx < 0 ? "" : v.substring(idx + 6).replace("\"", "").trim();
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
                            toast(act, "后台已打开营销中心,但没检测到签到请求(可能今天已签到)");
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
        // 页面创建
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity act = (Activity) self;
                        String name = act.getClass().getName();
                        note("【页面】" + name + "\n  intent: " + describe(act.getIntent()));
                        if (DEBUG_TOAST) toast(act, "【页面】" + name);
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onCreate 失败", t);
        }

        // 页面跳转
        try {
            Method start = Activity.class.getDeclaredMethod(
                    "startActivityForResult", Intent.class, int.class, Bundle.class);
            hook(start).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object a0 = chain.getArg(0);
                    if (a0 instanceof Intent) {
                        note("【跳转】从 " + (self == null ? "?" : self.getClass().getName())
                                + " 启动: " + describe((Intent) a0));
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook startActivityForResult 失败", t);
        }

        // 网页加载:记录网址;用户手动打开营销中心时,保存网页环境(初始化)
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

        // 弹窗
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            hook(show).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Dialog) {
                        Dialog d = (Dialog) self;
                        String name = d.getClass().getName();
                        note("【弹窗】" + name);
                        if (DEBUG_TOAST) toast(d.getContext(), "【弹窗】" + name);
                        copyRecord(d.getContext());
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Dialog 失败", t);
        }

        // 页面显示:启动自动签到 + 记录控件树
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity act = (Activity) self;
                        final String name = act.getClass().getName();
                        final View decor = act.getWindow().getDecorView();

                        if (AUTO_CHECKIN && !autoStarted && MAIN_ACTIVITY.equals(name)) {
                            autoStarted = true;
                            decor.postDelayed(() -> startAutoCheckin(act), 1500);
                        }

                        final boolean splash = name.toLowerCase().contains("splash");
                        if (splash || dumped.add(name)) {
                            long[] delays = splash ? new long[]{200, 800, 2000} : new long[]{2000};
                            for (long d : delays) {
                                decor.postDelayed(() -> {
                                    dumpTree(act, splash);
                                    copyRecord(act);
                                }, d);
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
            note("【网页】loadUrl " + url);
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

    private void dumpTree(Activity act, boolean all) {
        try {
            StringBuilder sb = new StringBuilder();
            walk(act.getWindow().getDecorView(), 0, sb, new int[]{0}, all);
            note("【控件树】" + act.getClass().getName() + "\n" + sb);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "dumpTree 失败", t);
        }
    }

    private void walk(View v, int depth, StringBuilder sb, int[] count, boolean all) {
        if (count[0] > 250 || depth > 30) return;
        String idName = "";
        try {
            if (v.getId() != View.NO_ID) {
                idName = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }
        String text = "";
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            text = cs == null ? "" : cs.toString();
        }
        if (all || !idName.isEmpty() || !text.isEmpty()) {
            count[0]++;
            for (int i = 0; i < depth; i++) sb.append(' ');
            sb.append(v.getClass().getName())
              .append(" id=").append(idName)
              .append(" text=").append(text)
              .append(" vis=").append(v.getVisibility());
            if (all) sb.append(" size=").append(v.getWidth()).append('x').append(v.getHeight());
            sb.append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, sb, count, all);
            }
        }
    }
}
