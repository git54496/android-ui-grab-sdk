package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class ComposeSemanticsNodeInfo implements Serializable {

    @SerializedName("a")
    private String mSemanticsId;

    @SerializedName("b")
    private String mRenderId;

    @SerializedName("c")
    private String mComponentId;

    @SerializedName("d")
    private String mLegacyNodeId;

    @SerializedName("e")
    private int mLeft;

    @SerializedName("f")
    private int mTop;

    @SerializedName("g")
    private int mRight;

    @SerializedName("h")
    private int mBottom;

    @SerializedName("i")
    private String mText;

    @SerializedName("j")
    private String mContentDescription;

    @SerializedName("k")
    private String mTestTag;

    @SerializedName("l")
    private boolean mClickable;

    @SerializedName("m")
    private boolean mEnabled = true;

    @SerializedName("n")
    private boolean mFocused;

    @SerializedName("o")
    private boolean mVisibleToUser = true;

    @SerializedName("p")
    private boolean mSelected;

    @SerializedName("q")
    private boolean mCheckable;

    @SerializedName("r")
    private boolean mChecked;

    @SerializedName("s")
    private boolean mFocusable;

    @SerializedName("t")
    private List<String> mActions;

    @SerializedName("u")
    private List<ComposeSemanticsNodeInfo> mChildren;

    @SerializedName("v")
    private String mRole;

    @SerializedName("w")
    private String mClassName;

    public String getSemanticsId() {
        return mSemanticsId;
    }

    public void setSemanticsId(String semanticsId) {
        this.mSemanticsId = semanticsId;
    }

    public String getRenderId() {
        return mRenderId;
    }

    public void setRenderId(String renderId) {
        this.mRenderId = renderId;
    }

    public String getComponentId() {
        return mComponentId;
    }

    public void setComponentId(String componentId) {
        this.mComponentId = componentId;
    }

    public String getLegacyNodeId() {
        return mLegacyNodeId;
    }

    public void setLegacyNodeId(String legacyNodeId) {
        this.mLegacyNodeId = legacyNodeId;
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

    public List<ComposeSemanticsNodeInfo> getChildren() {
        return mChildren;
    }

    public void setChildren(List<ComposeSemanticsNodeInfo> children) {
        this.mChildren = children;
    }

    public String getRole() {
        return mRole;
    }

    public void setRole(String role) {
        this.mRole = role;
    }

    public String getClassName() {
        return mClassName;
    }

    public void setClassName(String className) {
        this.mClassName = className;
    }
}
