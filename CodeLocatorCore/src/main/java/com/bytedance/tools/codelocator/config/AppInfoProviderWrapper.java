package com.bytedance.tools.codelocator.config;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bytedance.tools.codelocator.CodeLocator;
import com.bytedance.tools.codelocator.compose.ComposeCaptureCollector;
import com.bytedance.tools.codelocator.compose.ComposeSemanticsCollector;
import com.bytedance.tools.codelocator.model.ComposeCaptureInfo;
import com.bytedance.tools.codelocator.model.ColorInfo;
import com.bytedance.tools.codelocator.model.ComposeNodeInfo;
import com.bytedance.tools.codelocator.model.ExtraAction;
import com.bytedance.tools.codelocator.model.ExtraInfo;
import com.bytedance.tools.codelocator.model.SchemaInfo;
import com.bytedance.tools.codelocator.model.WView;
import com.bytedance.tools.codelocator.utils.ActivityUtils;
import com.bytedance.tools.codelocator.utils.CodeLocatorConstants;
import com.bytedance.tools.codelocator.utils.GsonUtils;
import com.bytedance.tools.codelocator.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class AppInfoProviderWrapper implements AppInfoProvider {

    private AppInfoProvider mOutAppInfoProvider;

    public AppInfoProviderWrapper(AppInfoProvider appInfoProvider) {
        mOutAppInfoProvider = appInfoProvider;
    }

    @Nullable
    @Override
    public HashMap<String, String> providerAppInfo(Context context) {
        final HashMap<String, String> appInfoMap = new HashMap<>();
        appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_DPI, String.valueOf(context.getResources().getDisplayMetrics().densityDpi));
        appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_DENSITY, String.valueOf(context.getResources().getDisplayMetrics().density));
        appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_PKG_NAME, String.valueOf(context.getPackageName()));
        appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_DEBUGGABLE, String.valueOf(ActivityUtils.isApkInDebug(context)));

        final String versionName = getVersionName(context);
        if (versionName != null) {
            appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_APP_VERSION_NAME, versionName);
        }

        final int versionCode = getVersionCode(context);
        if (versionCode != 0) {
            appInfoMap.put(AppInfoProvider.CODELOCATOR_KEY_APP_VERSION_CODE, "" + versionCode);
        }
        if (mOutAppInfoProvider != null) {
            try {
                final HashMap<String, String> outAppInfoMap = mOutAppInfoProvider.providerAppInfo(context);
                if (outAppInfoMap != null) {
                    appInfoMap.putAll(outAppInfoMap);
                }
            } catch (Throwable t) {
                Log.d(CodeLocator.TAG, "获取应用AppInfo失败, " + Log.getStackTraceString(t));
            }
        }
        return appInfoMap;
    }

    @Nullable
    public static String getVersionName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (Throwable t) {
            Log.d(CodeLocator.TAG, "getVersionName error " + Log.getStackTraceString(t));
        }
        return null;
    }

    public static int getVersionCode(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (Throwable t) {
            Log.d(CodeLocator.TAG, "getVersionName error " + Log.getStackTraceString(t));
        }
        return 0;
    }

    @Override
    public boolean canProviderData(View view) {
        if (mOutAppInfoProvider != null) {
            try {
                if (mOutAppInfoProvider.canProviderData(view)) {
                    return true;
                }
            } catch (Throwable t) {
                Log.d(CodeLocator.TAG, "获取View Data失败, " + Log.getStackTraceString(t));
            }
        }
        return isPrimaryComposeCaptureView(view);
    }

    @Nullable
    @Override
    public Object getViewData(View viewParent, @NonNull View view) {
        Object outData = null;
        if (mOutAppInfoProvider != null) {
            try {
                outData = mOutAppInfoProvider.getViewData(viewParent, view);
                if (outData != null) {
                    return outData;
                }
            } catch (Throwable t) {
                Log.e(CodeLocator.TAG, "获取View Data失败, " + Log.getStackTraceString(t));
            }
        }
        final View composeHost = findComposeHost(viewParent, view);
        if (composeHost == null) {
            return null;
        }
        final ComposeCaptureCollector.CaptureResult captureResult = ComposeCaptureCollector.capture(composeHost);
        return buildComposeViewData(composeHost, view, captureResult);
    }

    @Override
    public void setViewData(@Nullable View viewParent, @NonNull View view, String dataJson) {
        if (mOutAppInfoProvider != null) {
            try {
                mOutAppInfoProvider.setViewData(viewParent, view, dataJson);
            } catch (Throwable t) {
                Log.e(CodeLocator.TAG, "设置View Data失败, " + Log.getStackTraceString(t));
            }
        }
    }

    @Override
    public WView convertCustomView(View view, Rect windowRect) {
        if (mOutAppInfoProvider != null) {
            try {
                return mOutAppInfoProvider.convertCustomView(view, windowRect);
            } catch (Throwable t) {
                Log.e(CodeLocator.TAG, "转换自定义View失败, " + Log.getStackTraceString(t));
            }
        }
        return null;
    }

    @Override
    public Collection<ExtraInfo> processViewExtra(Activity activity, View view, WView wView) {
        Collection<ExtraInfo> collection = getExtraInfoByConfig(view);
        if (isPrimaryComposeCaptureView(view)) {
            final ComposeCaptureCollector.CaptureResult captureResult = ComposeCaptureCollector.capture(view);
            wView.setComposeCapture(captureResult.getCapture());
            wView.setComposeNodes(captureResult.getLegacyNodes().isEmpty()
                    ? new ArrayList<ComposeNodeInfo>()
                    : captureResult.getLegacyNodes());
            final ExtraInfo composeSummary = buildComposeSummaryExtra(captureResult);
            if (composeSummary != null) {
                if (collection == null) {
                    collection = new LinkedList<>();
                }
                collection.add(composeSummary);
            }
        }
        if (mOutAppInfoProvider != null) {
            try {
                final Collection<ExtraInfo> extras = mOutAppInfoProvider.processViewExtra(activity, view, wView);
                if (collection == null) {
                    collection = extras;
                } else if (extras != null) {
                    collection.addAll(extras);
                }
                return collection;
            } catch (Throwable t) {
                Log.e(CodeLocator.TAG, "processViewExtra, " + Log.getStackTraceString(t));
            }
        }
        return collection;
    }

    @Nullable
    private ExtraInfo buildComposeSummaryExtra(@NonNull ComposeCaptureCollector.CaptureResult captureResult) {
        if (!captureResult.hasCapture() && captureResult.getErrors().isEmpty()) {
            return null;
        }
        final StringBuilder summary = new StringBuilder();
        summary.append("componentCount=").append(captureResult.getComponentCount());
        summary.append(", renderNodeCount=").append(captureResult.getRenderCount());
        summary.append(", semanticsNodeCount=").append(captureResult.getSemanticsCount());
        summary.append(", sourceMappedComponentCount=").append(captureResult.getSourceMappedComponentCount());
        summary.append(", linkedComponentCount=").append(captureResult.getLinkedComponentCount());
        summary.append(", heuristicLinkedComponentCount=").append(captureResult.getHeuristicLinkedComponentCount());
        if (!captureResult.getErrors().isEmpty()) {
            summary.append(", errors=").append(captureResult.getErrors());
        }
        final ExtraAction action = new ExtraAction(
                ExtraAction.ActionType.NONE,
                summary.toString(),
                "ComposeSummary",
                null
        );
        return new ExtraInfo("ComposeSummary", ExtraInfo.ShowType.EXTRA_TABLE, action);
    }

    @NonNull
    private HashMap<String, Object> buildComposeViewData(@NonNull View hostView,
                                                         @NonNull View targetView,
                                                         @NonNull ComposeCaptureCollector.CaptureResult captureResult) {
        final HashMap<String, Object> out = new HashMap<>();
        out.put("host_view_class", hostView.getClass().getName());
        out.put("target_view_class", targetView.getClass().getName());
        out.put("target_is_host", hostView == targetView);
        out.put("component_count", captureResult.getComponentCount());
        out.put("render_node_count", captureResult.getRenderCount());
        out.put("semantics_node_count", captureResult.getSemanticsCount());
        out.put("source_mapped_component_count", captureResult.getSourceMappedComponentCount());
        out.put("linked_component_count", captureResult.getLinkedComponentCount());
        out.put("heuristic_linked_component_count", captureResult.getHeuristicLinkedComponentCount());
        out.put("nodes", captureResult.getLegacyNodes());
        final ComposeCaptureInfo capture = captureResult.getCapture();
        if (capture != null) {
            out.put("compose_capture_version", capture.getComposeCaptureVersion());
            out.put("component_tree", capture.getComponentTree());
            out.put("render_tree", capture.getRenderTree());
            out.put("semantics_tree", capture.getSemanticsTree());
            out.put("links", capture.getLinks());
        }
        if (!captureResult.getErrors().isEmpty()) {
            out.put("errors", captureResult.getErrors());
        }
        return out;
    }

    @Nullable
    private View findComposeHost(@Nullable View viewParent, @Nullable View targetView) {
        View host = findComposeHostByParent(viewParent);
        if (host != null) {
            return host;
        }
        return findComposeHostByParent(targetView);
    }

    private boolean isPrimaryComposeCaptureView(@Nullable View view) {
        if (!ComposeSemanticsCollector.isComposeCaptureView(view)) {
            return false;
        }
        ViewParent parent = view == null ? null : view.getParent();
        while (parent instanceof View) {
            if (ComposeSemanticsCollector.isComposeCaptureView((View) parent)) {
                return false;
            }
            parent = parent.getParent();
        }
        return true;
    }

    @Nullable
    private View findComposeHostByParent(@Nullable View view) {
        View current = view;
        while (current != null) {
            if (ComposeSemanticsCollector.isComposeCaptureView(current)) {
                return current;
            }
            final ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private Collection<ExtraInfo> getExtraInfoByConfig(View view) {
        Collection<ExtraInfo> collection = null;
        try {
            Set<String> extraViewInfo = CodeLocator.getExtraViewInfo();
            if (extraViewInfo != null && !extraViewInfo.isEmpty()) {
                final Class<? extends View> aClass = view.getClass();
                for (String extra : extraViewInfo) {
                    if (extra == null || extra.isEmpty()) {
                        continue;
                    }
                    extra = extra.trim();
                    if (extra.toLowerCase().startsWith("f:")) {
                        final String fieldName = extra.substring(2).trim();
                        final Field classField = ReflectUtils.getClassField(aClass, fieldName);
                        if (classField == null) {
                            continue;
                        }
                        final Object result = classField.get(view);
                        ExtraAction extraAction = new ExtraAction(ExtraAction.ActionType.NONE, result == null ? "null" : result.toString(), fieldName, null);
                        ExtraInfo extraInfo = new ExtraInfo(fieldName, ExtraInfo.ShowType.EXTRA_TABLE, extraAction);
                        if (collection == null) {
                            collection = new LinkedList<>();
                        }
                        collection.add(extraInfo);
                    } else if (extra.toLowerCase().startsWith("m:")) {
                        final String methodName = extra.substring(2).trim();
                        final Method method = ReflectUtils.getClassMethod(aClass, methodName);
                        if (method == null) {
                            continue;
                        }
                        final Object result = method.invoke(view);
                        ExtraAction extraAction = new ExtraAction(ExtraAction.ActionType.NONE, result == null ? "null" : result.toString(), methodName, null);
                        ExtraInfo extraInfo = new ExtraInfo(methodName, ExtraInfo.ShowType.EXTRA_TABLE, extraAction);
                        if (collection == null) {
                            collection = new LinkedList<>();
                        }
                        collection.add(extraInfo);
                    }
                }
            }
            if (view != null && view.getTag(CodeLocatorConstants.R.id.codeLocator_view_extra) != null) {
                final Object extraInfo = view.getTag(CodeLocatorConstants.R.id.codeLocator_view_extra);
                ExtraAction extraAction = new ExtraAction(ExtraAction.ActionType.NONE, extraInfo instanceof String ? extraInfo.toString() : GsonUtils.sGson.toJson(extraInfo), "CodeLocatorExtra", null);
                ExtraInfo codeLocatorExtraInfo = new ExtraInfo("CodeLocatorExtra", ExtraInfo.ShowType.EXTRA_TABLE, extraAction);
                if (collection == null) {
                    collection = new LinkedList<>();
                }
                collection.add(codeLocatorExtraInfo);
            }
        } catch (Throwable t) {
            Log.e(CodeLocator.TAG, "processViewExtra error, " + Log.getStackTraceString(t));
        }
        return collection;
    }

    @Nullable
    @Override
    public Collection<SchemaInfo> providerAllSchema() {
        if (mOutAppInfoProvider != null) {
            try {
                return mOutAppInfoProvider.providerAllSchema();
            } catch (Throwable t) {
                Log.e(CodeLocator.TAG, "providerAllSchema error, " + Log.getStackTraceString(t));
            }
        }
        return null;
    }

    @Override
    public boolean processSchema(String schema) {
        boolean schemaProcessed = false;
        if (mOutAppInfoProvider != null) {
            try {
                schemaProcessed = mOutAppInfoProvider.processSchema(schema);
            } catch (Throwable t) {
                Log.d(CodeLocator.TAG, "processSchema error, " + Log.getStackTraceString(t));
            }
        }
        if (!schemaProcessed) {
            if (schema != null && schema.contains(".")) {
                try {
                    final Class<?> anyClass = Class.forName(schema);
                    final Intent intent = new Intent(CodeLocator.getCurrentActivity(), anyClass);
                    if (Activity.class.isAssignableFrom(anyClass)) {
                        CodeLocator.getCurrentActivity().startActivity(intent);
                    } else if (Service.class.isAssignableFrom(anyClass)) {
                        CodeLocator.getCurrentActivity().startService(intent);
                    }
                    schemaProcessed = true;
                } catch (Throwable ignore) {
                }
            }
        }
        return schemaProcessed;
    }

    @Nullable
    @Override
    public List<ColorInfo> providerColorInfo(@NonNull Context context) {
        if (mOutAppInfoProvider != null) {
            try {
                List<ColorInfo> colorInfos = mOutAppInfoProvider.providerColorInfo(context);
                if (colorInfos != null) {
                    return colorInfos;
                }
                colorInfos = new LinkedList<>();
                final Class colorClass = Class.forName(context.getPackageName() + ".R$color");
                final Field[] declaredFields = colorClass.getDeclaredFields();
                Resources res = context.getResources();
                for (Field field : declaredFields) {
                    try {
                        colorInfos.add(new ColorInfo(field.getName(), res.getColor((Integer) field.get(null)), ""));
                    } catch (Throwable t) {
                        Log.d(CodeLocator.TAG, "Error Get " + field + " " + Log.getStackTraceString(t));
                    }
                }
                return colorInfos;
            } catch (Throwable t) {
                Log.d(CodeLocator.TAG, "providerColorInfo error, " + Log.getStackTraceString(t));
            }
        }
        return null;
    }
}
