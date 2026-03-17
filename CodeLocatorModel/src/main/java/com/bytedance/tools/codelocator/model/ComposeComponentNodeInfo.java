package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class ComposeComponentNodeInfo implements Serializable {

    @SerializedName("a")
    private String mComponentId;

    @SerializedName("b")
    private String mDisplayName;

    @SerializedName("c")
    private String mSourcePathToken;

    @SerializedName("d")
    private String mSourcePath;

    @SerializedName("e")
    private int mSourceLine;

    @SerializedName("f")
    private int mSourceColumn;

    @SerializedName("g")
    private float mConfidence;

    @SerializedName("h")
    private boolean mFrameworkNode;

    @SerializedName("i")
    private List<ComposeComponentNodeInfo> mChildren;

    @SerializedName("j")
    private String mPathResolution;

    @SerializedName("k")
    private String mSemanticsId;

    @SerializedName("l")
    private String mSemanticsLinkStrategy;

    @SerializedName("m")
    private float mSemanticsLinkConfidence;

    public String getComponentId() {
        return mComponentId;
    }

    public void setComponentId(String componentId) {
        this.mComponentId = componentId;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public void setDisplayName(String displayName) {
        this.mDisplayName = displayName;
    }

    public String getSourcePathToken() {
        return mSourcePathToken;
    }

    public void setSourcePathToken(String sourcePathToken) {
        this.mSourcePathToken = sourcePathToken;
    }

    public String getSourcePath() {
        return mSourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.mSourcePath = sourcePath;
    }

    public int getSourceLine() {
        return mSourceLine;
    }

    public void setSourceLine(int sourceLine) {
        this.mSourceLine = sourceLine;
    }

    public int getSourceColumn() {
        return mSourceColumn;
    }

    public void setSourceColumn(int sourceColumn) {
        this.mSourceColumn = sourceColumn;
    }

    public float getConfidence() {
        return mConfidence;
    }

    public void setConfidence(float confidence) {
        this.mConfidence = confidence;
    }

    public boolean isFrameworkNode() {
        return mFrameworkNode;
    }

    public void setFrameworkNode(boolean frameworkNode) {
        this.mFrameworkNode = frameworkNode;
    }

    public List<ComposeComponentNodeInfo> getChildren() {
        return mChildren;
    }

    public void setChildren(List<ComposeComponentNodeInfo> children) {
        this.mChildren = children;
    }

    public String getPathResolution() {
        return mPathResolution;
    }

    public void setPathResolution(String pathResolution) {
        this.mPathResolution = pathResolution;
    }

    public String getSemanticsId() {
        return mSemanticsId;
    }

    public void setSemanticsId(String semanticsId) {
        this.mSemanticsId = semanticsId;
    }

    public String getSemanticsLinkStrategy() {
        return mSemanticsLinkStrategy;
    }

    public void setSemanticsLinkStrategy(String semanticsLinkStrategy) {
        this.mSemanticsLinkStrategy = semanticsLinkStrategy;
    }

    public float getSemanticsLinkConfidence() {
        return mSemanticsLinkConfidence;
    }

    public void setSemanticsLinkConfidence(float semanticsLinkConfidence) {
        this.mSemanticsLinkConfidence = semanticsLinkConfidence;
    }
}
