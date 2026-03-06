package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

/**
 * Compose semantics node snapshot captured from AccessibilityNodeInfo.
 */
public class ComposeNodeInfo implements Serializable {

    @SerializedName("a")
    private String mNodeId;

    @SerializedName("b")
    private int mLeft;

    @SerializedName("c")
    private int mTop;

    @SerializedName("d")
    private int mRight;

    @SerializedName("e")
    private int mBottom;

    @SerializedName("f")
    private String mText;

    @SerializedName("g")
    private String mContentDescription;

    @SerializedName("h")
    private String mTestTag;

    @SerializedName("i")
    private boolean mClickable;

    @SerializedName("j")
    private boolean mEnabled;

    @SerializedName("k")
    private boolean mFocused;

    @SerializedName("l")
    private boolean mVisibleToUser = true;

    @SerializedName("m")
    private boolean mSelected;

    @SerializedName("n")
    private boolean mCheckable;

    @SerializedName("o")
    private boolean mChecked;

    @SerializedName("p")
    private boolean mFocusable;

    @SerializedName("q")
    private List<String> mActions;

    @SerializedName("r")
    private List<ComposeNodeInfo> mChildren;

    public String getNodeId() {
        return mNodeId;
    }

    public void setNodeId(String nodeId) {
        this.mNodeId = nodeId;
    }

    public int getLeft() {
        return mLeft;
    }

    public void setLeft(int left) {
        this.mLeft = left;
    }

    public int getTop() {
        return mTop;
    }

    public void setTop(int top) {
        this.mTop = top;
    }

    public int getRight() {
        return mRight;
    }

    public void setRight(int right) {
        this.mRight = right;
    }

    public int getBottom() {
        return mBottom;
    }

    public void setBottom(int bottom) {
        this.mBottom = bottom;
    }

    public String getText() {
        return mText;
    }

    public void setText(String text) {
        this.mText = text;
    }

    public String getContentDescription() {
        return mContentDescription;
    }

    public void setContentDescription(String contentDescription) {
        this.mContentDescription = contentDescription;
    }

    public String getTestTag() {
        return mTestTag;
    }

    public void setTestTag(String testTag) {
        this.mTestTag = testTag;
    }

    public boolean isClickable() {
        return mClickable;
    }

    public void setClickable(boolean clickable) {
        this.mClickable = clickable;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.mEnabled = enabled;
    }

    public boolean isFocused() {
        return mFocused;
    }

    public void setFocused(boolean focused) {
        this.mFocused = focused;
    }

    public boolean isVisibleToUser() {
        return mVisibleToUser;
    }

    public void setVisibleToUser(boolean visibleToUser) {
        this.mVisibleToUser = visibleToUser;
    }

    public boolean isSelected() {
        return mSelected;
    }

    public void setSelected(boolean selected) {
        this.mSelected = selected;
    }

    public boolean isCheckable() {
        return mCheckable;
    }

    public void setCheckable(boolean checkable) {
        this.mCheckable = checkable;
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setChecked(boolean checked) {
        this.mChecked = checked;
    }

    public boolean isFocusable() {
        return mFocusable;
    }

    public void setFocusable(boolean focusable) {
        this.mFocusable = focusable;
    }

    public List<String> getActions() {
        return mActions;
    }

    public void setActions(List<String> actions) {
        this.mActions = actions;
    }

    public List<ComposeNodeInfo> getChildren() {
        return mChildren;
    }

    public void setChildren(List<ComposeNodeInfo> children) {
        this.mChildren = children;
    }

    public int getChildCount() {
        return mChildren == null ? 0 : mChildren.size();
    }
}
