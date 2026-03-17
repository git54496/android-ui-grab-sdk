package com.bytedance.tools.codelocator.compose;

import android.os.Build;
import android.os.Bundle;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bytedance.tools.codelocator.model.ComposeNodeInfo;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Capture Compose semantics through AccessibilityNodeInfo to avoid hard dependency on Compose internals.
 */
public final class ComposeSemanticsCollector {

    private static final int MAX_NODE_COUNT = 1500;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT_SAMPLE_COUNT = 5;
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    private static final String ABSTRACT_COMPOSE_VIEW = "androidx.compose.ui.platform.AbstractComposeView";
    private static final String ANDROID_COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final String TEST_TAG_KEY = "androidx.compose.ui.semantics.testTag";
    private static final String TEST_TAG_KEYWORD = "testtag";

    private ComposeSemanticsCollector() {
    }

    public static boolean isComposeHostView(@Nullable View view) {
        if (view == null) {
            return false;
        }
        Class<?> current = view.getClass();
        while (current != null && current != Object.class) {
            final String className = current.getName();
            if (COMPOSE_VIEW.equals(className) || ABSTRACT_COMPOSE_VIEW.equals(className)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    public static boolean isComposeCaptureView(@Nullable View view) {
        return isComposeHostView(view) || isAndroidComposeView(view);
    }

    @NonNull
    public static CaptureResult capture(@Nullable View view) {
        if (!isComposeCaptureView(view)) {
            return CaptureResult.empty();
        }
        final CaptureResult semanticsResult = captureFromSemanticsOwner(view);
        if (semanticsResult != null && semanticsResult.hasNodes()) {
            return semanticsResult;
        }

        final View accessibilityView = resolveAccessibilityCaptureView(view);
        AccessibilityNodeInfo root = null;
        try {
            root = accessibilityView == null ? null : accessibilityView.createAccessibilityNodeInfo();
            if (root == null) {
                return mergeErrors(semanticsResult, CaptureResult.error("compose_accessibility_root_null"));
            }

            final Counter counter = new Counter();
            final List<String> textSamples = new ArrayList<>();
            final ComposeNodeInfo rootNode = parseNode(root, "0", 0, counter, textSamples);
            if (rootNode == null) {
                return mergeErrors(semanticsResult, CaptureResult.error("compose_node_parse_failed"));
            }

            final List<ComposeNodeInfo> nodes = new ArrayList<>(1);
            nodes.add(rootNode);
            return new CaptureResult(nodes, counter.totalCount, counter.clickableCount, textSamples, null);
        } catch (Throwable t) {
            return mergeErrors(semanticsResult, CaptureResult.error(buildThrowableError("compose_capture_failed", t, accessibilityView)));
        } finally {
            recycleNode(root);
        }
    }

    @Nullable
    private static CaptureResult captureFromSemanticsOwner(@Nullable View view) {
        final View semanticsView = findAndroidComposeView(view);
        if (semanticsView == null) {
            return null;
        }
        try {
            final Object semanticsOwner = invokeNoArg(semanticsView, "getSemanticsOwner");
            if (semanticsOwner == null) {
                return CaptureResult.error("compose_semantics_owner_null");
            }

            Object root = invokeNoArg(semanticsOwner, "getUnmergedRootSemanticsNode");
            if (root == null) {
                root = invokeNoArg(semanticsOwner, "getRootSemanticsNode");
            }
            if (root == null) {
                return CaptureResult.error("compose_semantics_root_null");
            }

            final Counter counter = new Counter();
            final List<String> textSamples = new ArrayList<>();
            final ComposeNodeInfo rootNode = parseSemanticsNode(root, "0", 0, counter, textSamples);
            if (rootNode == null) {
                return CaptureResult.error("compose_semantics_node_parse_failed");
            }

            final List<ComposeNodeInfo> nodes = new ArrayList<>(1);
            nodes.add(rootNode);
            return new CaptureResult(nodes, counter.totalCount, counter.clickableCount, textSamples, null);
        } catch (Throwable t) {
            return CaptureResult.error(buildThrowableError("compose_semantics_capture_failed", t, semanticsView));
        }
    }

    @Nullable
    private static ComposeNodeInfo parseNode(@Nullable AccessibilityNodeInfo node,
                                             @NonNull String nodeId,
                                             int depth,
                                             @NonNull Counter counter,
                                             @NonNull List<String> textSamples) {
        if (node == null || depth > MAX_DEPTH || counter.totalCount >= MAX_NODE_COUNT) {
            return null;
        }

        final ComposeNodeInfo composeNode = new ComposeNodeInfo();
        composeNode.setNodeId(nodeId);

        final Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        composeNode.setLeft(rect.left);
        composeNode.setTop(rect.top);
        composeNode.setRight(rect.right);
        composeNode.setBottom(rect.bottom);
        composeNode.setText(charSeqToString(node.getText()));
        composeNode.setContentDescription(charSeqToString(node.getContentDescription()));
        composeNode.setTestTag(extractTestTag(node));
        composeNode.setClickable(node.isClickable());
        composeNode.setEnabled(node.isEnabled());
        composeNode.setFocused(node.isFocused());
        composeNode.setVisibleToUser(node.isVisibleToUser());
        composeNode.setSelected(node.isSelected());
        composeNode.setCheckable(node.isCheckable());
        composeNode.setChecked(node.isChecked());
        composeNode.setFocusable(node.isFocusable());
        composeNode.setActions(extractActions(node));

        counter.totalCount++;
        if (composeNode.isClickable()) {
            counter.clickableCount++;
        }
        collectTextSamples(composeNode, textSamples);

        final int childCount = node.getChildCount();
        if (childCount > 0 && depth < MAX_DEPTH && counter.totalCount < MAX_NODE_COUNT) {
            final List<ComposeNodeInfo> children = new ArrayList<>();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    final ComposeNodeInfo childNode = parseNode(
                            child,
                            nodeId + "." + i,
                            depth + 1,
                            counter,
                            textSamples
                    );
                    if (childNode != null) {
                        children.add(childNode);
                    }
                } finally {
                    recycleNode(child);
                }
            }
            if (!children.isEmpty()) {
                composeNode.setChildren(children);
            }
        }
        return composeNode;
    }

    @Nullable
    private static ComposeNodeInfo parseSemanticsNode(@Nullable Object semanticsNode,
                                                      @NonNull String nodeId,
                                                      int depth,
                                                      @NonNull Counter counter,
                                                      @NonNull List<String> textSamples) {
        if (semanticsNode == null || depth > MAX_DEPTH || counter.totalCount >= MAX_NODE_COUNT) {
            return null;
        }

        final ComposeNodeInfo composeNode = new ComposeNodeInfo();
        composeNode.setNodeId(nodeId);

        final Object bounds = invokeNoArg(semanticsNode, "getBoundsInWindow");
        composeNode.setLeft(Math.round(readFloat(bounds, "getLeft")));
        composeNode.setTop(Math.round(readFloat(bounds, "getTop")));
        composeNode.setRight(Math.round(readFloat(bounds, "getRight")));
        composeNode.setBottom(Math.round(readFloat(bounds, "getBottom")));

        final Object config = invokeNoArg(semanticsNode, "getConfig");
        final Map<String, Object> props = readSemanticsProps(config);

        composeNode.setText(extractSemanticsText(props.get("Text")));
        composeNode.setContentDescription(extractContentDescription(props.get("ContentDescription")));
        composeNode.setTestTag(asNonEmptyString(props.get("TestTag")));
        composeNode.setClickable(hasAction(props, "OnClick"));
        composeNode.setEnabled(!props.containsKey("Disabled"));
        composeNode.setFocused(asBoolean(props.get("Focused")));
        composeNode.setVisibleToUser(!props.containsKey("InvisibleToUser") && !props.containsKey("HideFromAccessibility"));
        composeNode.setSelected(asBoolean(props.get("Selected")));
        composeNode.setCheckable(props.containsKey("ToggleableState"));
        composeNode.setChecked(isChecked(props.get("ToggleableState")));
        composeNode.setFocusable(props.containsKey("Focused") || hasAction(props, "RequestFocus"));
        composeNode.setActions(extractSemanticsActions(props));

        counter.totalCount++;
        if (composeNode.isClickable()) {
            counter.clickableCount++;
        }
        collectTextSamples(composeNode, textSamples);

        final List<Object> children = asObjectList(invokeNoArg(semanticsNode, "getChildren"));
        if (!children.isEmpty() && depth < MAX_DEPTH && counter.totalCount < MAX_NODE_COUNT) {
            final List<ComposeNodeInfo> parsedChildren = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                final ComposeNodeInfo childNode = parseSemanticsNode(
                        children.get(i),
                        nodeId + "." + i,
                        depth + 1,
                        counter,
                        textSamples
                );
                if (childNode != null) {
                    parsedChildren.add(childNode);
                }
            }
            if (!parsedChildren.isEmpty()) {
                composeNode.setChildren(parsedChildren);
            }
        }
        return composeNode;
    }

    private static void collectTextSamples(@NonNull ComposeNodeInfo node, @NonNull List<String> textSamples) {
        if (textSamples.size() >= MAX_TEXT_SAMPLE_COUNT) {
            return;
        }
        addTextSample(textSamples, node.getText());
        addTextSample(textSamples, node.getContentDescription());
        addTextSample(textSamples, node.getTestTag());
    }

    private static void addTextSample(@NonNull List<String> textSamples, @Nullable String text) {
        if (TextUtils.isEmpty(text) || textSamples.size() >= MAX_TEXT_SAMPLE_COUNT) {
            return;
        }
        if (!textSamples.contains(text)) {
            textSamples.add(text);
        }
    }

    @Nullable
    private static String extractTestTag(@NonNull AccessibilityNodeInfo node) {
        final Bundle extras = node.getExtras();
        if (extras == null || extras.isEmpty()) {
            return null;
        }

        final CharSequence direct = extras.getCharSequence(TEST_TAG_KEY);
        if (!TextUtils.isEmpty(direct)) {
            return direct.toString();
        }

        for (String key : extras.keySet()) {
            if (key == null) {
                continue;
            }
            final String normalized = key.toLowerCase(Locale.US);
            if (!normalized.contains(TEST_TAG_KEYWORD)) {
                continue;
            }
            final Object value = extras.get(key);
            if (value == null) {
                continue;
            }
            final String text = String.valueOf(value);
            if (!TextUtils.isEmpty(text)) {
                return text;
            }
        }
        return null;
    }

    @NonNull
    private static List<String> extractActions(@NonNull AccessibilityNodeInfo node) {
        final List<String> actions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final List<AccessibilityNodeInfo.AccessibilityAction> actionList = node.getActionList();
            if (actionList != null) {
                for (AccessibilityNodeInfo.AccessibilityAction action : actionList) {
                    if (action == null) {
                        continue;
                    }
                    String name = actionName(action.getId());
                    if (TextUtils.isEmpty(name) && action.getLabel() != null) {
                        name = action.getLabel().toString();
                    }
                    if (!TextUtils.isEmpty(name) && !actions.contains(name)) {
                        actions.add(name);
                    }
                }
            }
        } else {
            final int mask = node.getActions();
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_CLICK, "CLICK");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_LONG_CLICK, "LONG_CLICK");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "SCROLL_FORWARD");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "SCROLL_BACKWARD");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_FOCUS, "FOCUS");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, "CLEAR_FOCUS");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_SELECT, "SELECT");
            addActionFromMask(actions, mask, AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, "CLEAR_SELECTION");
        }
        return actions.isEmpty() ? Collections.<String>emptyList() : actions;
    }

    private static void addActionFromMask(@NonNull List<String> actions, int allMask, int actionMask, @NonNull String name) {
        if ((allMask & actionMask) != 0 && !actions.contains(name)) {
            actions.add(name);
        }
    }

    @Nullable
    private static String actionName(int actionId) {
        switch (actionId) {
            case AccessibilityNodeInfo.ACTION_FOCUS:
                return "FOCUS";
            case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS:
                return "CLEAR_FOCUS";
            case AccessibilityNodeInfo.ACTION_SELECT:
                return "SELECT";
            case AccessibilityNodeInfo.ACTION_CLEAR_SELECTION:
                return "CLEAR_SELECTION";
            case AccessibilityNodeInfo.ACTION_CLICK:
                return "CLICK";
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                return "LONG_CLICK";
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                return "ACCESSIBILITY_FOCUS";
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                return "CLEAR_ACCESSIBILITY_FOCUS";
            case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
                return "NEXT_AT_MOVEMENT_GRANULARITY";
            case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
                return "PREVIOUS_AT_MOVEMENT_GRANULARITY";
            case AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT:
                return "NEXT_HTML_ELEMENT";
            case AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT:
                return "PREVIOUS_HTML_ELEMENT";
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                return "SCROLL_FORWARD";
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                return "SCROLL_BACKWARD";
            case AccessibilityNodeInfo.ACTION_COPY:
                return "COPY";
            case AccessibilityNodeInfo.ACTION_PASTE:
                return "PASTE";
            case AccessibilityNodeInfo.ACTION_CUT:
                return "CUT";
            case AccessibilityNodeInfo.ACTION_SET_SELECTION:
                return "SET_SELECTION";
            case AccessibilityNodeInfo.ACTION_EXPAND:
                return "EXPAND";
            case AccessibilityNodeInfo.ACTION_COLLAPSE:
                return "COLLAPSE";
            case AccessibilityNodeInfo.ACTION_DISMISS:
                return "DISMISS";
            case AccessibilityNodeInfo.ACTION_SET_TEXT:
                return "SET_TEXT";
            default:
                return null;
        }
    }

    @Nullable
    private static String charSeqToString(@Nullable CharSequence charSequence) {
        return charSequence == null ? null : charSequence.toString();
    }

    private static void recycleNode(@Nullable AccessibilityNodeInfo node) {
        if (node != null) {
            node.recycle();
        }
    }

    private static boolean isAndroidComposeView(@Nullable View view) {
        return view != null && ANDROID_COMPOSE_VIEW.equals(view.getClass().getName());
    }

    @Nullable
    private static View resolveAccessibilityCaptureView(@Nullable View root) {
        final View androidComposeView = findAndroidComposeView(root);
        return androidComposeView != null ? androidComposeView : root;
    }

    @Nullable
    private static View findAndroidComposeView(@Nullable View root) {
        if (root == null) {
            return null;
        }
        if (isAndroidComposeView(root)) {
            return root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        final ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final View current = queue.removeFirst();
            if (isAndroidComposeView(current)) {
                return current;
            }
            if (current instanceof ViewGroup) {
                final ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    final View child = group.getChildAt(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            }
        }
        return null;
    }

    @NonNull
    private static String buildThrowableError(@NonNull String prefix,
                                              @Nullable Throwable throwable,
                                              @Nullable View targetView) {
        final StringBuilder error = new StringBuilder(prefix);
        if (throwable != null) {
            error.append(":").append(throwable.getClass().getSimpleName());
        }
        final String targetClassName = targetView == null ? null : targetView.getClass().getName();
        if (!TextUtils.isEmpty(targetClassName)) {
            error.append(":target=").append(targetClassName);
        }
        final String message = sanitizeErrorMessage(throwable == null ? null : throwable.getMessage());
        if (!TextUtils.isEmpty(message)) {
            error.append(":message=").append(message);
        }
        return error.toString();
    }

    @Nullable
    private static String sanitizeErrorMessage(@Nullable String message) {
        if (TextUtils.isEmpty(message)) {
            return null;
        }
        final String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    @Nullable
    private static Object invokeNoArg(@Nullable Object target, @NonNull String methodName) {
        if (target == null) {
            return null;
        }
        try {
            final Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float readFloat(@Nullable Object target, @NonNull String methodName) {
        final Object value = invokeNoArg(target, methodName);
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    @NonNull
    private static Map<String, Object> readSemanticsProps(@Nullable Object config) {
        if (!(config instanceof Iterable)) {
            return Collections.emptyMap();
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        final Iterator<?> iterator = ((Iterable<?>) config).iterator();
        while (iterator.hasNext()) {
            final Object entry = iterator.next();
            if (!(entry instanceof Map.Entry)) {
                continue;
            }
            final Map.Entry<?, ?> mapEntry = (Map.Entry<?, ?>) entry;
            final Object key = mapEntry.getKey();
            if (key == null) {
                continue;
            }
            final Object name = invokeNoArg(key, "getName");
            if (name instanceof String && !TextUtils.isEmpty((String) name)) {
                out.put((String) name, mapEntry.getValue());
            }
        }
        return out;
    }

    @NonNull
    private static List<Object> asObjectList(@Nullable Object value) {
        if (!(value instanceof Iterable)) {
            return Collections.emptyList();
        }
        final List<Object> out = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    @Nullable
    private static String extractSemanticsText(@Nullable Object value) {
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                final String text = extractAnnotatedText(item);
                if (!TextUtils.isEmpty(text)) {
                    return text;
                }
            }
        }
        return extractAnnotatedText(value);
    }

    @Nullable
    private static String extractAnnotatedText(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        final Object text = invokeNoArg(value, "getText");
        if (text instanceof String && !TextUtils.isEmpty((String) text)) {
            return (String) text;
        }
        if (value instanceof CharSequence) {
            final String out = value.toString();
            return TextUtils.isEmpty(out) ? null : out;
        }
        return null;
    }

    @Nullable
    private static String extractContentDescription(@Nullable Object value) {
        if (value instanceof Iterable) {
            final List<String> parts = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                final String text = asNonEmptyString(item);
                if (!TextUtils.isEmpty(text)) {
                    parts.add(text);
                }
            }
            if (!parts.isEmpty()) {
                return TextUtils.join(", ", parts);
            }
        }
        return asNonEmptyString(value);
    }

    @Nullable
    private static String asNonEmptyString(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        final String text = String.valueOf(value);
        return TextUtils.isEmpty(text) ? null : text;
    }

    private static boolean asBoolean(@Nullable Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static boolean isChecked(@Nullable Object toggleableState) {
        if (toggleableState == null) {
            return false;
        }
        final String value = String.valueOf(toggleableState);
        return "On".equalsIgnoreCase(value) || "Indeterminate".equalsIgnoreCase(value);
    }

    private static boolean hasAction(@NonNull Map<String, Object> props, @NonNull String key) {
        return props.containsKey(key);
    }

    @NonNull
    private static List<String> extractSemanticsActions(@NonNull Map<String, Object> props) {
        final List<String> actions = new ArrayList<>();
        appendSemanticsAction(actions, props, "OnClick", "CLICK");
        appendSemanticsAction(actions, props, "OnLongClick", "LONG_CLICK");
        appendSemanticsAction(actions, props, "ScrollBy", "SCROLL");
        appendSemanticsAction(actions, props, "ScrollToIndex", "SCROLL_TO_INDEX");
        appendSemanticsAction(actions, props, "SetProgress", "SET_PROGRESS");
        appendSemanticsAction(actions, props, "SetSelection", "SET_SELECTION");
        appendSemanticsAction(actions, props, "SetText", "SET_TEXT");
        appendSemanticsAction(actions, props, "InsertTextAtCursor", "INSERT_TEXT");
        appendSemanticsAction(actions, props, "CopyText", "COPY_TEXT");
        appendSemanticsAction(actions, props, "CutText", "CUT_TEXT");
        appendSemanticsAction(actions, props, "PasteText", "PASTE_TEXT");
        appendSemanticsAction(actions, props, "Expand", "EXPAND");
        appendSemanticsAction(actions, props, "Collapse", "COLLAPSE");
        appendSemanticsAction(actions, props, "Dismiss", "DISMISS");
        appendSemanticsAction(actions, props, "RequestFocus", "FOCUS");
        appendSemanticsAction(actions, props, "OnImeAction", "IME_ACTION");
        appendSemanticsAction(actions, props, "PerformImeAction", "IME_ACTION");
        return actions.isEmpty() ? Collections.<String>emptyList() : actions;
    }

    private static void appendSemanticsAction(@NonNull List<String> actions,
                                              @NonNull Map<String, Object> props,
                                              @NonNull String key,
                                              @NonNull String fallbackName) {
        if (!props.containsKey(key)) {
            return;
        }
        final Object value = props.get(key);
        final Object label = invokeNoArg(value, "getLabel");
        final String actionName = asNonEmptyString(label);
        final String name = TextUtils.isEmpty(actionName) ? fallbackName : actionName;
        if (!actions.contains(name)) {
            actions.add(name);
        }
    }

    @NonNull
    private static CaptureResult mergeErrors(@Nullable CaptureResult first, @NonNull CaptureResult second) {
        if (second.hasNodes()) {
            return second;
        }
        final String firstError = first == null ? null : first.getError();
        final String secondError = second.getError();
        if (TextUtils.isEmpty(firstError)) {
            return second;
        }
        if (TextUtils.isEmpty(secondError)) {
            return first;
        }
        return CaptureResult.error(firstError + ";fallback=" + secondError);
    }

    private static final class Counter {
        int totalCount = 0;
        int clickableCount = 0;
    }

    public static final class CaptureResult {
        private static final CaptureResult EMPTY =
                new CaptureResult(Collections.<ComposeNodeInfo>emptyList(), 0, 0, Collections.<String>emptyList(), null);

        private final List<ComposeNodeInfo> mNodes;
        private final int mNodeCount;
        private final int mClickableNodeCount;
        private final List<String> mTextSamples;
        private final String mError;

        private CaptureResult(@NonNull List<ComposeNodeInfo> nodes,
                              int nodeCount,
                              int clickableNodeCount,
                              @NonNull List<String> textSamples,
                              @Nullable String error) {
            this.mNodes = nodes;
            this.mNodeCount = nodeCount;
            this.mClickableNodeCount = clickableNodeCount;
            this.mTextSamples = textSamples;
            this.mError = error;
        }

        @NonNull
        public static CaptureResult empty() {
            return EMPTY;
        }

        @NonNull
        public static CaptureResult error(@NonNull String error) {
            return new CaptureResult(
                    Collections.<ComposeNodeInfo>emptyList(),
                    0,
                    0,
                    Collections.<String>emptyList(),
                    error
            );
        }

        @NonNull
        public List<ComposeNodeInfo> getNodes() {
            return mNodes;
        }

        public int getNodeCount() {
            return mNodeCount;
        }

        public int getClickableNodeCount() {
            return mClickableNodeCount;
        }

        @NonNull
        public List<String> getTextSamples() {
            return mTextSamples;
        }

        @Nullable
        public String getError() {
            return mError;
        }

        public boolean hasNodes() {
            return mNodes != null && !mNodes.isEmpty();
        }
    }
}
