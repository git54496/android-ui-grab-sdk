package com.bytedance.tools.codelocator.compose;

import android.os.Build;
import android.os.Bundle;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bytedance.tools.codelocator.model.ComposeNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Capture Compose semantics through AccessibilityNodeInfo to avoid hard dependency on Compose internals.
 */
public final class ComposeSemanticsCollector {

    private static final int MAX_NODE_COUNT = 1500;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT_SAMPLE_COUNT = 5;
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    private static final String ABSTRACT_COMPOSE_VIEW = "androidx.compose.ui.platform.AbstractComposeView";
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

    @NonNull
    public static CaptureResult capture(@Nullable View view) {
        if (!isComposeHostView(view)) {
            return CaptureResult.empty();
        }
        AccessibilityNodeInfo root = null;
        try {
            root = view.createAccessibilityNodeInfo();
            if (root == null) {
                return CaptureResult.error("compose_accessibility_root_null");
            }

            final Counter counter = new Counter();
            final List<String> textSamples = new ArrayList<>();
            final ComposeNodeInfo rootNode = parseNode(root, "0", 0, counter, textSamples);
            if (rootNode == null) {
                return CaptureResult.error("compose_node_parse_failed");
            }

            final List<ComposeNodeInfo> nodes = new ArrayList<>(1);
            nodes.add(rootNode);
            return new CaptureResult(nodes, counter.totalCount, counter.clickableCount, textSamples, null);
        } catch (Throwable t) {
            return CaptureResult.error("compose_capture_failed:" + t.getClass().getSimpleName());
        } finally {
            recycleNode(root);
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
