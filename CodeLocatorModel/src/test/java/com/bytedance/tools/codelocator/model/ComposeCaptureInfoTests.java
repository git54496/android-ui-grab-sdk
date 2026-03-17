package com.bytedance.tools.codelocator.model;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ComposeCaptureInfoTests {

    private final Gson gson = new Gson();

    @Test
    public void wViewSerializesComposeCaptureWithShortFieldName() {
        WView view = new WView();
        ComposeCaptureInfo captureInfo = new ComposeCaptureInfo();
        captureInfo.setComposeCaptureVersion("1");

        ComposeComponentNodeInfo componentNode = new ComposeComponentNodeInfo();
        componentNode.setComponentId("component.0");
        componentNode.setDisplayName("HomeScreen");
        componentNode.setSourcePath("app/src/main/java/com/demo/HomeScreen.kt");
        componentNode.setSourceLine(42);
        componentNode.setSemanticsId("101");
        componentNode.setSemanticsLinkStrategy("layoutinfo_semantics_id");
        componentNode.setSemanticsLinkConfidence(0.95f);
        captureInfo.setComponentTree(Collections.singletonList(componentNode));

        ComposeRenderNodeInfo renderNode = new ComposeRenderNodeInfo();
        renderNode.setRenderId("render:101");
        renderNode.setLeft(10);
        renderNode.setTop(20);
        renderNode.setRight(210);
        renderNode.setBottom(320);
        captureInfo.setRenderTree(Collections.singletonList(renderNode));

        ComposeSemanticsNodeInfo semanticsNode = new ComposeSemanticsNodeInfo();
        semanticsNode.setSemanticsId("101");
        semanticsNode.setRenderId("render:101");
        semanticsNode.setLegacyNodeId("0");
        semanticsNode.setText("Home");
        captureInfo.setSemanticsTree(Collections.singletonList(semanticsNode));

        ComposeLinkInfo linkInfo = new ComposeLinkInfo();
        linkInfo.setSourceNodeType("component");
        linkInfo.setSourceId("component.0");
        linkInfo.setTargetNodeType("render");
        linkInfo.setTargetId("render:101");
        captureInfo.setLinks(Collections.singletonList(linkInfo));

        view.setComposeCapture(captureInfo);

        String json = gson.toJson(view);
        assertNotNull(json);
        assertEquals(true, json.contains("\"b6\""));

        WView parsed = gson.fromJson(json, WView.class);
        assertNotNull(parsed.getComposeCapture());
        assertEquals("1", parsed.getComposeCapture().getComposeCaptureVersion());
        assertEquals("component.0", parsed.getComposeCapture().getComponentTree().get(0).getComponentId());
        assertEquals("101", parsed.getComposeCapture().getComponentTree().get(0).getSemanticsId());
        assertEquals("layoutinfo_semantics_id", parsed.getComposeCapture().getComponentTree().get(0).getSemanticsLinkStrategy());
        assertEquals(0.95f, parsed.getComposeCapture().getComponentTree().get(0).getSemanticsLinkConfidence(), 0.0001f);
        assertEquals("render:101", parsed.getComposeCapture().getRenderTree().get(0).getRenderId());
        assertEquals("101", parsed.getComposeCapture().getSemanticsTree().get(0).getSemanticsId());
    }
}
