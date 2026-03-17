package com.bytedance.tools.codelocator.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class ComposeCaptureInfo implements Serializable {

    @SerializedName("a")
    private String mComposeCaptureVersion;

    @SerializedName("b")
    private List<ComposeComponentNodeInfo> mComponentTree;

    @SerializedName("c")
    private List<ComposeRenderNodeInfo> mRenderTree;

    @SerializedName("d")
    private List<ComposeSemanticsNodeInfo> mSemanticsTree;

    @SerializedName("e")
    private List<ComposeLinkInfo> mLinks;

    @SerializedName("f")
    private List<String> mErrors;

    public String getComposeCaptureVersion() {
        return mComposeCaptureVersion;
    }

    public void setComposeCaptureVersion(String composeCaptureVersion) {
        this.mComposeCaptureVersion = composeCaptureVersion;
    }

    public List<ComposeComponentNodeInfo> getComponentTree() {
        return mComponentTree;
    }

    public void setComponentTree(List<ComposeComponentNodeInfo> componentTree) {
        this.mComponentTree = componentTree;
    }

    public List<ComposeRenderNodeInfo> getRenderTree() {
        return mRenderTree;
    }

    public void setRenderTree(List<ComposeRenderNodeInfo> renderTree) {
        this.mRenderTree = renderTree;
    }

    public List<ComposeSemanticsNodeInfo> getSemanticsTree() {
        return mSemanticsTree;
    }

    public void setSemanticsTree(List<ComposeSemanticsNodeInfo> semanticsTree) {
        this.mSemanticsTree = semanticsTree;
    }

    public List<ComposeLinkInfo> getLinks() {
        return mLinks;
    }

    public void setLinks(List<ComposeLinkInfo> links) {
        this.mLinks = links;
    }

    public List<String> getErrors() {
        return mErrors;
    }

    public void setErrors(List<String> errors) {
        this.mErrors = errors;
    }
}
