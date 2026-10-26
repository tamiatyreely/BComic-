package com.example.comiclsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ComicModule extends XposedModule {
    private static final String TAG = "ComicModule";
    private static final int MAX_CHARS = 80000;

    private static final String PROBE_JS =
            "(function(){try{"
            + "var r=performance.getEntriesByType('resource').filter(function(e){"
            + "return e.initiatorType==='xmlhttprequest'||e.initiatorType==='fetch';})"
            + ".map(function(e){return e.name.split('#')[0];});"
            + "return JSON.stringify({title:document.title,ua:navigator.userAgent,"
            + "text:((document.body&&document.body.innerText)||'').substring(0,500),"
            + "api:r.slice(0,40)});"
            + "}catch(e){return 'err '+e;}})()";

    private final Set<String> dumped = new HashSet<>();
    private final Set<String> probed = new HashSet<>();
    private final StringBuilder record = new StringBuilder();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载,进程: " + param.getProcessName());
    }

    private static String scrub(String s) {
        if (s == null) return "null";
        return s.replaceAll(
                "(?i)(access_key|accesskey|token|sessdata|cookie|sign|csrf|sid)=([^&\\s,;\"]*)",
                "$1=<hidden>");
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
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private synchronized void copyRecord(Context ctx) {
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

    private String cookieNames(String url) {
        try {
            String c = CookieManager.getInstance().getCookie(url);
            if (c == null) return "(无)";
            StringBuilder sb = new StringBuilder();
            for (String part : c.split(";")) {
                int i = part.indexOf('=');
                sb.append(i > 0 ? part.substring(0, i).trim() : part.trim()).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "error";
        }
    }

    private void probeWebView(Object self, Object urlObj) {
        try {
            if (!(self instanceof WebView) || !(urlObj instanceof String)) return;
            String url = (String) urlObj;
            if (!url.contains("manga.bilibili.com/blackboard")) return;
            final WebView wv = (WebView) self;
            String key = System.identityHashCode(wv) + url;
            if (!probed.add(key)) return;
            long[] delays = {5000, 12000};
            for (long d : delays) {
                wv.postDelayed(() -> {
                    try {
                        wv.evaluateJavascript(PROBE_JS, value -> {
                            String v = value == null ? "null" : value;
                            if (v.length() > 3000) v = v.substring(0, 3000);
                            note("【网页探测】" + v);
                            note("【Cookie名】" + cookieNames("https://manga.bilibili.com"));
                            copyRecord(wv.getContext());
                            toast(wv.getContext(), "网页探测完成,已复制");
                        });
                    } catch (Throwable ignored) {
                    }
                }, d);
            }
        } catch (Throwable ignored) {
        }
    }

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
                        toast(act, "【页面】" + name);
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

        // 网页加载
        try {
            Method load1 = WebView.class.getDeclaredMethod("loadUrl", String.class);
            hook(load1).intercept(chain -> {
                try {
                    note("【网页】loadUrl " + chain.getArg(0));
                    probeWebView(chain.getThisObject(), chain.getArg(0));
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            Method load2 = WebView.class.getDeclaredMethod("loadUrl", String.class, Map.class);
            hook(load2).intercept(chain -> {
                try {
                    note("【网页】loadUrl " + chain.getArg(0));
                    probeWebView(chain.getThisObject(), chain.getArg(0));
                } catch (Throwable ignored) {
                }
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
                        toast(d.getContext(), "【弹窗】" + name);
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Dialog 失败", t);
        }

        // 页面显示后记录控件树
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity act = (Activity) self;
                        final String name = act.getClass().getName();
                        final boolean splash = name.toLowerCase().contains("splash");
                        if (splash || dumped.add(name)) {
                            final View decor = act.getWindow().getDecorView();
                            long[] delays = splash ? new long[]{200, 800, 2000} : new long[]{2000};
                            for (long d : delays) {
                                final boolean last = (d == delays[delays.length - 1]);
                                decor.postDelayed(() -> {
                                    dumpTree(act, splash);
                                    copyRecord(act);
                                    if (last) toast(act, "记录已复制到剪贴板");
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
