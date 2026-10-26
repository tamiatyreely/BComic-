package com.example.comiclsposed;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ComicModule extends XposedModule {
    private static final String TAG = "ComicModule";
    private static final int MAX_CHARS = 80000;

    private final Set<String> dumped = new HashSet<>();
    private final StringBuilder record = new StringBuilder();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载,进程: " + param.getProcessName());
    }

    private synchronized void note(String msg) {
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

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 页面创建:弹提示 + 记录名字
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity act = (Activity) self;
                        String name = act.getClass().getName();
                        note("【页面】" + name);
                        toast(act, "【页面】" + name);
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onCreate 失败", t);
        }

        // 弹窗显示:弹提示 + 记录名字
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

        // 页面显示 2 秒后,记录控件树并复制到剪贴板
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity act = (Activity) self;
                        if (dumped.add(act.getClass().getName())) {
                            act.getWindow().getDecorView().postDelayed(() -> {
                                dumpTree(act);
                                copyRecord(act);
                                toast(act, "记录已复制到剪贴板");
                            }, 2000);
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

    private void dumpTree(Activity act) {
        try {
            StringBuilder sb = new StringBuilder();
            walk(act.getWindow().getDecorView(), 0, sb, new int[]{0});
            note("【控件树】" + act.getClass().getName() + "\n" + sb);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "dumpTree 失败", t);
        }
    }

    private void walk(View v, int depth, StringBuilder sb, int[] count) {
        if (count[0] > 300 || depth > 30) return;
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
        if (!idName.isEmpty() || !text.isEmpty()) {
            count[0]++;
            for (int i = 0; i < depth; i++) sb.append(' ');
            sb.append(v.getClass().getSimpleName())
              .append(" id=").append(idName)
              .append(" text=").append(text)
              .append(" vis=").append(v.getVisibility())
              .append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, sb, count);
            }
        }
    }
}
