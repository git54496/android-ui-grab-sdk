package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class ComposeRenderNodeInfo implements Serializable {

    @SerializedName("a")
    private String mRenderId;

    @SerializedName("b")
    private String mParentRenderId;

    @SerializedName("c")
    private int mLeft;

    @SerializedName("d")
    private int mTop;

    @SerializedName("e")
    private int mRight;

    @SerializedName("f")
    private int mBottom;

    @SerializedName("g")
    private boolean mVisible = true;

    @SerializedName("h")
    private float mAlpha = 1f;

    @SerializedName("i")
    private float mZIndex;

    @SerializedName("j")
    private String mModifierSummary;

    @SerializedName("k")
    private String mStyleSummary;

    @SerializedName("l")
    private String mComponentId;

    @SerializedName("m")
    private List<ComposeRenderNodeInfo> mChildren;

    @SerializedName("n")
    private String mTypeName;

    public String getRenderId() {
        return mRenderId;
    }

    public void setRenderId(String renderId) {
        this.mRenderId = renderId;
    }

    public String getParentRenderId() {
        return mParentRenderId;
    }

    public void setParentRenderId(String parentRenderId) {
        this.mParentRenderId = parentRenderId;
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

    public boolean isVisible() {
        return mVisible;
    }

    public void setVisible(boolean visible) {
        this.mVisible = visible;
    }

    public float getAlpha() {
        return mAlpha;
    }

    public void setAlpha(float alpha) {
        this.mAlpha = alpha;
    }

    public float getZIndex() {
        return mZIndex;
    }

    public void setZIndex(float zIndex) {
        this.mZIndex = zIndex;
    }

    public String getModifierSummary() {
        return mModifierSummary;
    }

    public void setModifierSummary(String modifierSummary) {
        this.mModifierSummary = modifierSummary;
    }

    public String getStyleSummary() {
        return mStyleSummary;
    }

    public void setStyleSummary(String styleSummary) {
        this.mStyleSummary = styleSummary;
    }

    public String getComponentId() {
        return mComponentId;
    }

    public void setComponentId(String componentId) {
        this.mComponentId = componentId;
    }

    public List<ComposeRenderNodeInfo> getChildren() {
        return mChildren;
    }

    public void setChildren(List<ComposeRenderNodeInfo> children) {
        this.mChildren = children;
    }

    public String getTypeName() {
        return mTypeName;
    }

    public void setTypeName(String typeName) {
        this.mTypeName = typeName;
    }
}
