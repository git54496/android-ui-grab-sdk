package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class ComposeLinkInfo implements Serializable {

    @SerializedName("a")
    private String mSourceNodeType;

    @SerializedName("b")
    private String mTargetNodeType;

    @SerializedName("c")
    private String mSourceId;

    @SerializedName("d")
    private String mTargetId;

    @SerializedName("e")
    private float mConfidence;

    @SerializedName("f")
    private String mLinkStrategy;

    public String getSourceNodeType() {
        return mSourceNodeType;
    }

    public void setSourceNodeType(String sourceNodeType) {
        this.mSourceNodeType = sourceNodeType;
    }

    public String getTargetNodeType() {
        return mTargetNodeType;
    }

    public void setTargetNodeType(String targetNodeType) {
        this.mTargetNodeType = targetNodeType;
    }

    public String getSourceId() {
        return mSourceId;
    }

    public void setSourceId(String sourceId) {
        this.mSourceId = sourceId;
    }

    public String getTargetId() {
        return mTargetId;
    }

    public void setTargetId(String targetId) {
        this.mTargetId = targetId;
    }

    public float getConfidence() {
        return mConfidence;
    }

    public void setConfidence(float confidence) {
        this.mConfidence = confidence;
    }

    public String getLinkStrategy() {
        return mLinkStrategy;
    }

    public void setLinkStrategy(String linkStrategy) {
        this.mLinkStrategy = linkStrategy;
    }
}
