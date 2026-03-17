package com.bytedance.tools.codelocator.compose;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bytedance.tools.codelocator.model.ComposeCaptureInfo;
import com.bytedance.tools.codelocator.model.ComposeComponentNodeInfo;
import com.bytedance.tools.codelocator.model.ComposeLinkInfo;
import com.bytedance.tools.codelocator.model.ComposeNodeInfo;
import com.bytedance.tools.codelocator.model.ComposeRenderNodeInfo;
import com.bytedance.tools.codelocator.model.ComposeSemanticsNodeInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Capture a richer Compose snapshot while keeping legacy semantics nodes available.
 */
public final class ComposeCaptureCollector {

    private static final String VERSION = "1";
    private static final int MAX_NODE_COUNT = 2000;
    private static final String ANDROID_COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final String ABSTRACT_COMPOSE_VIEW = "androidx.compose.ui.platform.AbstractComposeView";
    private static final String COMPOSE_SOURCE_INFORMATION_KT = "androidx.compose.runtime.tooling.SourceInformationKt";
    private static final String COMPOSE_SLOT_TREE_KT = "androidx.compose.ui.tooling.data.SlotTreeKt";
    private static final int MAX_REASONABLE_SOURCE_LINE = 200000;
    private static final float EXACT_COMPONENT_LINK_CONFIDENCE = 0.95f;
    private static final float HEURISTIC_COMPONENT_LINK_CONFIDENCE = 0.55f;
    private static final float LCA_HEURISTIC_LINK_CONFIDENCE_THRESHOLD = 0.58f;
    private static final String LINK_STRATEGY_LAYOUTINFO_SEMANTICS_ID = "layoutinfo_semantics_id";
    private static final String LINK_STRATEGY_SINGLE_CHILD_UNIQUE_SEMANTICS = "single_child_unique_semantics";
    private static final String LINK_STRATEGY_SEMANTICS_LCA_SIGNAL = "semantics_lca_signal";
    private static final String LINK_STRATEGY_SEMANTICS_LCA_NARROW_SUBTREE = "semantics_lca_narrow_subtree";

    private ComposeCaptureCollector() {
    }

    @NonNull
    public static CaptureResult capture(@Nullable View composeHost) {
        if (!ComposeSemanticsCollector.isComposeHostView(composeHost) && !isAndroidComposeView(composeHost)) {
            return CaptureResult.empty();
        }

        final CaptureAccumulator accumulator = new CaptureAccumulator();
        final View androidComposeView = findAndroidComposeView(composeHost);
        SemanticsBuildResult semanticsBuild = null;
        if (androidComposeView != null) {
            semanticsBuild = captureSemantics(androidComposeView, accumulator);
        } else {
            accumulator.errors.add("android_compose_view_not_found");
        }

        if (semanticsBuild == null || semanticsBuild.semanticsRoots.isEmpty()) {
            final View legacyCaptureView = androidComposeView != null ? androidComposeView : composeHost;
            final ComposeSemanticsCollector.CaptureResult legacyCapture = ComposeSemanticsCollector.capture(legacyCaptureView);
            if (legacyCapture.hasNodes()) {
                semanticsBuild = buildFromLegacyNodes(legacyCapture.getNodes(), accumulator);
            }
            if (!TextUtils.isEmpty(legacyCapture.getError())) {
                accumulator.errors.add(legacyCapture.getError());
            }
        }

        if (semanticsBuild == null) {
            return new CaptureResult(null, Collections.<ComposeNodeInfo>emptyList(), 0, 0, 0, 0, 0, 0, accumulator.errors);
        }

        final ComponentBuildResult componentBuild = captureComponents(composeHost, accumulator);
        final ComponentLinkStats linkStats = applyComponentLinks(componentBuild, accumulator);

        final ComposeCaptureInfo captureInfo = new ComposeCaptureInfo();
        captureInfo.setComposeCaptureVersion(VERSION);
        captureInfo.setComponentTree(componentBuild.componentRoots);
        captureInfo.setRenderTree(semanticsBuild.renderRoots);
        captureInfo.setSemanticsTree(semanticsBuild.semanticsRoots);
        captureInfo.setLinks(accumulator.links);
        captureInfo.setErrors(accumulator.errors.isEmpty() ? Collections.<String>emptyList() : accumulator.errors);

        final int sourceMappedCount = componentBuild.sourceMappedComponentCount;
        return new CaptureResult(
                captureInfo,
                semanticsBuild.legacyRoots,
                componentBuild.componentCount,
                semanticsBuild.renderCount,
                semanticsBuild.semanticsCount,
                sourceMappedCount,
                linkStats.linkedComponentCount,
                linkStats.heuristicLinkedComponentCount,
                accumulator.errors
        );
    }

    @Nullable
    private static SemanticsBuildResult captureSemantics(@NonNull View androidComposeView, @NonNull CaptureAccumulator accumulator) {
        final Object semanticsOwner = invokeNoArg(androidComposeView, "getSemanticsOwner");
        if (semanticsOwner == null) {
            accumulator.errors.add("compose_semantics_owner_null");
            return null;
        }
        Object root = invokeNoArg(semanticsOwner, "getUnmergedRootSemanticsNode");
        if (root == null) {
            root = invokeNoArg(semanticsOwner, "getRootSemanticsNode");
        }
        if (root == null) {
            accumulator.errors.add("compose_semantics_root_null");
            return null;
        }

        final InternalSemanticsNode parsedRoot = parseSemanticsNode(root, "0", null, null, accumulator, 0);
        if (parsedRoot == null) {
            accumulator.errors.add("compose_semantics_node_parse_failed");
            return null;
        }
        final List<ComposeSemanticsNodeInfo> semanticsRoots = new ArrayList<>(1);
        semanticsRoots.add(parsedRoot.semanticsNode);
        final List<ComposeRenderNodeInfo> renderRoots = new ArrayList<>(1);
        renderRoots.add(parsedRoot.renderNode);
        final List<ComposeNodeInfo> legacyRoots = new ArrayList<>(1);
        legacyRoots.add(parsedRoot.legacyNode);
        return new SemanticsBuildResult(
                semanticsRoots,
                renderRoots,
                legacyRoots,
                accumulator.semanticsById.size(),
                accumulator.renderById.size()
        );
    }

    @Nullable
    private static InternalSemanticsNode parseSemanticsNode(@Nullable Object semanticsNode,
                                                            @NonNull String legacyNodeId,
                                                            @Nullable String parentSemanticsId,
                                                            @Nullable String parentRenderId,
                                                            @NonNull CaptureAccumulator accumulator,
                                                            int depth) {
        if (semanticsNode == null || depth > 32 || accumulator.semanticsById.size() >= MAX_NODE_COUNT) {
            return null;
        }

        final ComposeSemanticsNodeInfo semanticsInfo = new ComposeSemanticsNodeInfo();
        final String semanticsId = stringify(invokeNoArg(semanticsNode, "getId"), legacyNodeId);
        final String renderId = "render:" + semanticsId;

        semanticsInfo.setSemanticsId(semanticsId);
        semanticsInfo.setLegacyNodeId(legacyNodeId);
        semanticsInfo.setRenderId(renderId);

        final Object bounds = invokeNoArg(semanticsNode, "getBoundsInWindow");
        semanticsInfo.setLeft(Math.round(readFloat(bounds, "getLeft")));
        semanticsInfo.setTop(Math.round(readFloat(bounds, "getTop")));
        semanticsInfo.setRight(Math.round(readFloat(bounds, "getRight")));
        semanticsInfo.setBottom(Math.round(readFloat(bounds, "getBottom")));

        final Object config = invokeNoArg(semanticsNode, "getConfig");
        final Map<String, Object> props = readSemanticsProps(config);
        semanticsInfo.setText(extractSemanticsText(props.get("Text")));
        semanticsInfo.setContentDescription(extractContentDescription(props.get("ContentDescription")));
        semanticsInfo.setTestTag(asNonEmptyString(props.get("TestTag")));
        semanticsInfo.setClickable(props.containsKey("OnClick"));
        semanticsInfo.setEnabled(!props.containsKey("Disabled"));
        semanticsInfo.setFocused(asBoolean(props.get("Focused")));
        semanticsInfo.setVisibleToUser(!props.containsKey("InvisibleToUser") && !props.containsKey("HideFromAccessibility"));
        semanticsInfo.setSelected(asBoolean(props.get("Selected")));
        semanticsInfo.setCheckable(props.containsKey("ToggleableState"));
        semanticsInfo.setChecked(isChecked(props.get("ToggleableState")));
        semanticsInfo.setFocusable(props.containsKey("Focused") || props.containsKey("RequestFocus"));
        semanticsInfo.setActions(extractSemanticsActions(props));
        semanticsInfo.setRole(extractRoleName(props.get("Role")));
        semanticsInfo.setClassName(inferClassName(semanticsInfo, props));

        final ComposeNodeInfo legacyNode = toLegacyNode(semanticsInfo);
        legacyNode.setNodeId(legacyNodeId);

        final ComposeRenderNodeInfo renderNode = new ComposeRenderNodeInfo();
        renderNode.setRenderId(renderId);
        renderNode.setParentRenderId(parentRenderId);
        renderNode.setLeft(semanticsInfo.getLeft());
        renderNode.setTop(semanticsInfo.getTop());
        renderNode.setRight(semanticsInfo.getRight());
        renderNode.setBottom(semanticsInfo.getBottom());
        renderNode.setVisible(semanticsInfo.isVisibleToUser());
        renderNode.setAlpha(semanticsInfo.isVisibleToUser() ? 1f : 0f);
        renderNode.setModifierSummary(summarizeModifiers(invokeNoArg(semanticsNode, "getLayoutInfo")));
        renderNode.setStyleSummary(summarizeSemantics(semanticsInfo));
        renderNode.setTypeName(inferRenderTypeName(semanticsInfo, renderNode.getModifierSummary()));

        final InternalSemanticsNode internalNode = new InternalSemanticsNode();
        internalNode.semanticsNode = semanticsInfo;
        internalNode.legacyNode = legacyNode;
        internalNode.renderNode = renderNode;
        internalNode.layoutInfo = invokeNoArg(semanticsNode, "getLayoutInfo");
        internalNode.parentSemanticsId = parentSemanticsId;
        internalNode.depth = depth;

        accumulator.semanticsById.put(semanticsId, internalNode);
        accumulator.renderById.put(renderId, renderNode);
        addBidirectionalLink(accumulator.links, "semantics", semanticsId, "render", renderId, 1f, "semantics_exact");

        final List<Object> children = asObjectList(invokeNoArg(semanticsNode, "getChildren"));
        if (!children.isEmpty()) {
            final List<ComposeSemanticsNodeInfo> semanticsChildren = new ArrayList<>();
            final List<ComposeNodeInfo> legacyChildren = new ArrayList<>();
            final List<ComposeRenderNodeInfo> renderChildren = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                final InternalSemanticsNode child = parseSemanticsNode(
                        children.get(i),
                        legacyNodeId + "." + i,
                        semanticsId,
                        renderId,
                        accumulator,
                        depth + 1
                );
                if (child == null) {
                    continue;
                }
                semanticsChildren.add(child.semanticsNode);
                legacyChildren.add(child.legacyNode);
                renderChildren.add(child.renderNode);
            }
            if (!semanticsChildren.isEmpty()) {
                semanticsInfo.setChildren(semanticsChildren);
                legacyNode.setChildren(legacyChildren);
                renderNode.setChildren(renderChildren);
            }
        }
        return internalNode;
    }

    @NonNull
    private static SemanticsBuildResult buildFromLegacyNodes(@NonNull List<ComposeNodeInfo> legacyRoots,
                                                             @NonNull CaptureAccumulator accumulator) {
        final List<ComposeSemanticsNodeInfo> semanticsRoots = new ArrayList<>();
        final List<ComposeRenderNodeInfo> renderRoots = new ArrayList<>();
        final List<ComposeNodeInfo> copiedLegacyRoots = new ArrayList<>();
        for (ComposeNodeInfo legacyRoot : legacyRoots) {
            final InternalSemanticsNode root = buildFromLegacyNode(legacyRoot, null, null, accumulator, 0);
            if (root == null) {
                continue;
            }
            semanticsRoots.add(root.semanticsNode);
            renderRoots.add(root.renderNode);
            copiedLegacyRoots.add(root.legacyNode);
        }
        return new SemanticsBuildResult(
                semanticsRoots,
                renderRoots,
                copiedLegacyRoots,
                accumulator.semanticsById.size(),
                accumulator.renderById.size()
        );
    }

    @Nullable
    private static InternalSemanticsNode buildFromLegacyNode(@Nullable ComposeNodeInfo legacyNode,
                                                             @Nullable String parentRenderId,
                                                             @Nullable String parentSemanticsId,
                                                             @NonNull CaptureAccumulator accumulator,
                                                             int depth) {
        if (legacyNode == null) {
            return null;
        }
        final String semanticsId = "legacy:" + legacyNode.getNodeId();
        final String renderId = "render:" + semanticsId;

        final ComposeSemanticsNodeInfo semanticsNode = new ComposeSemanticsNodeInfo();
        semanticsNode.setSemanticsId(semanticsId);
        semanticsNode.setRenderId(renderId);
        semanticsNode.setLegacyNodeId(legacyNode.getNodeId());
        semanticsNode.setLeft(legacyNode.getLeft());
        semanticsNode.setTop(legacyNode.getTop());
        semanticsNode.setRight(legacyNode.getRight());
        semanticsNode.setBottom(legacyNode.getBottom());
        semanticsNode.setText(legacyNode.getText());
        semanticsNode.setContentDescription(legacyNode.getContentDescription());
        semanticsNode.setTestTag(legacyNode.getTestTag());
        semanticsNode.setClickable(legacyNode.isClickable());
        semanticsNode.setEnabled(legacyNode.isEnabled());
        semanticsNode.setFocused(legacyNode.isFocused());
        semanticsNode.setVisibleToUser(legacyNode.isVisibleToUser());
        semanticsNode.setSelected(legacyNode.isSelected());
        semanticsNode.setCheckable(legacyNode.isCheckable());
        semanticsNode.setChecked(legacyNode.isChecked());
        semanticsNode.setFocusable(legacyNode.isFocusable());
        semanticsNode.setActions(legacyNode.getActions());

        final ComposeNodeInfo copiedLegacyNode = toLegacyNode(semanticsNode);
        copiedLegacyNode.setNodeId(legacyNode.getNodeId());

        final ComposeRenderNodeInfo renderNode = new ComposeRenderNodeInfo();
        renderNode.setRenderId(renderId);
        renderNode.setParentRenderId(parentRenderId);
        renderNode.setLeft(legacyNode.getLeft());
        renderNode.setTop(legacyNode.getTop());
        renderNode.setRight(legacyNode.getRight());
        renderNode.setBottom(legacyNode.getBottom());
        renderNode.setVisible(legacyNode.isVisibleToUser());
        renderNode.setAlpha(legacyNode.isVisibleToUser() ? 1f : 0f);
        renderNode.setStyleSummary(summarizeSemantics(semanticsNode));

        final InternalSemanticsNode internalNode = new InternalSemanticsNode();
        internalNode.semanticsNode = semanticsNode;
        internalNode.legacyNode = copiedLegacyNode;
        internalNode.renderNode = renderNode;
        internalNode.parentSemanticsId = parentSemanticsId;
        internalNode.depth = depth;

        accumulator.semanticsById.put(semanticsId, internalNode);
        accumulator.renderById.put(renderId, renderNode);
        addBidirectionalLink(accumulator.links, "semantics", semanticsId, "render", renderId, 1f, "legacy_semantics");

        if (legacyNode.getChildren() != null && !legacyNode.getChildren().isEmpty()) {
            final List<ComposeSemanticsNodeInfo> semanticsChildren = new ArrayList<>();
            final List<ComposeNodeInfo> legacyChildren = new ArrayList<>();
            final List<ComposeRenderNodeInfo> renderChildren = new ArrayList<>();
            for (ComposeNodeInfo child : legacyNode.getChildren()) {
                final InternalSemanticsNode parsedChild = buildFromLegacyNode(
                        child,
                        renderId,
                        semanticsId,
                        accumulator,
                        depth + 1
                );
                if (parsedChild == null) {
                    continue;
                }
                semanticsChildren.add(parsedChild.semanticsNode);
                legacyChildren.add(parsedChild.legacyNode);
                renderChildren.add(parsedChild.renderNode);
            }
            semanticsNode.setChildren(semanticsChildren);
            copiedLegacyNode.setChildren(legacyChildren);
            renderNode.setChildren(renderChildren);
        }
        return internalNode;
    }

    @NonNull
    private static ComponentBuildResult captureComponents(@Nullable View composeHost,
                                                          @NonNull CaptureAccumulator accumulator) {
        final Object compositionData = findCompositionData(composeHost);
        if (compositionData == null) {
            accumulator.errors.add("compose_component_capture_unavailable");
            return ComponentBuildResult.empty();
        }

        collectToolingSources(compositionData, accumulator);

        final Object groups = invokeNoArg(compositionData, "getCompositionGroups");
        final List<ComposeComponentNodeInfo> componentRoots = new ArrayList<>();
        final ComponentBuildCounters counters = new ComponentBuildCounters();
        int index = 0;
        for (Object group : asObjectList(groups)) {
            final InternalComponentNode componentNode = parseComponentNode(group, "component." + index, accumulator, 0);
            index++;
            if (componentNode == null) {
                continue;
            }
            counters.componentCount++;
            if (!TextUtils.isEmpty(componentNode.componentNode.getSourcePath())) {
                counters.sourceMappedComponentCount++;
            }
            counters.componentCount += componentNode.descendantCount;
            counters.sourceMappedComponentCount += componentNode.descendantSourceMappedCount;
            componentRoots.add(componentNode.componentNode);
        }
        return new ComponentBuildResult(componentRoots, counters.componentCount, counters.sourceMappedComponentCount);
    }

    @Nullable
    private static InternalComponentNode parseComponentNode(@Nullable Object group,
                                                            @NonNull String componentId,
                                                            @NonNull CaptureAccumulator accumulator,
                                                            int depth) {
        if (group == null || depth > 64 || accumulator.componentById.size() >= MAX_NODE_COUNT) {
            return null;
        }

        final String sourceInfo = asNonEmptyString(invokeNoArg(group, "getSourceInfo"));
        String toolingPosition = null;
        ParsedSource parsedSource = parseSource(group, sourceInfo, toolingPosition);
        final Object node = invokeNoArg(group, "getNode");
        if (!parsedSource.hasUsefulLocation()) {
            final ParsedSource byNode = accumulator.toolingSourceByNode.get(node);
            if (byNode != null && byNode.hasUsefulLocation()) {
                parsedSource = byNode;
                toolingPosition = byNode.rawToken;
            } else {
                final ParsedSource byIdentity = accumulator.toolingSourceByIdentity.get(invokeNoArg(group, "getIdentity"));
                if (byIdentity != null && byIdentity.hasUsefulLocation()) {
                    parsedSource = byIdentity;
                    toolingPosition = byIdentity.rawToken;
                }
            }
        }

        final ComposeComponentNodeInfo componentNode = new ComposeComponentNodeInfo();
        componentNode.setComponentId(componentId);
        componentNode.setDisplayName(chooseDisplayName(parsedSource, sourceInfo, invokeNoArg(group, "getKey"), node));
        componentNode.setSourcePathToken(firstNonEmpty(toolingPosition, sourceInfo, parsedSource.sourceFile));
        componentNode.setSourcePath(parsedSource.sourceFile);
        componentNode.setSourceLine(parsedSource.lineNumber);
        componentNode.setSourceColumn(parsedSource.offset);
        componentNode.setConfidence(parsedSource.confidence);
        componentNode.setFrameworkNode(isFrameworkNode(componentNode.getDisplayName(), sourceInfo, parsedSource.sourceFile));
        componentNode.setPathResolution(!TextUtils.isEmpty(componentNode.getSourcePathToken()) ? "raw" : "unknown");

        final int semanticsId = readInt(node, "getSemanticsId");
        if (semanticsId > 0) {
            final String linkedSemanticsId = String.valueOf(semanticsId);
            accumulator.componentSemanticsLink.put(componentId, linkedSemanticsId);
        }
        accumulator.componentById.put(componentId, componentNode);

        final InternalComponentNode internalNode = new InternalComponentNode();
        internalNode.componentNode = componentNode;

        final List<ComposeComponentNodeInfo> childNodes = new ArrayList<>();
        int childIndex = 0;
        for (Object childGroup : asObjectList(invokeNoArg(group, "getCompositionGroups"))) {
            final InternalComponentNode childNode = parseComponentNode(
                    childGroup,
                    componentId + "." + childIndex,
                    accumulator,
                    depth + 1
            );
            childIndex++;
            if (childNode == null) {
                continue;
            }
            childNodes.add(childNode.componentNode);
            internalNode.descendantCount += 1 + childNode.descendantCount;
            if (!TextUtils.isEmpty(childNode.componentNode.getSourcePath())) {
                internalNode.descendantSourceMappedCount++;
            }
            internalNode.descendantSourceMappedCount += childNode.descendantSourceMappedCount;
        }
        if (!childNodes.isEmpty()) {
            componentNode.setChildren(childNodes);
        }
        return internalNode;
    }

    @NonNull
    private static ParsedSource parseSource(@Nullable Object group,
                                            @Nullable String sourceInfo,
                                            @Nullable String toolingPosition) {
        final ParsedSource source = parseSourceInfo(sourceInfo);
        if (source.hasUsefulLocation()) {
            return source;
        }
        final ParsedSource fromPosition = parseSourceFromPositionString(toolingPosition);
        if (fromPosition.hasUsefulLocation()) {
            return fromPosition;
        }
        final ParsedSource fallback = parseSourceFromGroupData(group);
        if (fallback.hasUsefulLocation()) {
            if (TextUtils.isEmpty(fallback.functionName)) {
                fallback.functionName = source.functionName;
            }
            if (TextUtils.isEmpty(fallback.sourceFile)) {
                fallback.sourceFile = source.sourceFile;
            }
            return fallback;
        }
        return source;
    }

    @NonNull
    private static ParsedSource parseSourceFromGroupData(@Nullable Object group) {
        final ParsedSource best = ParsedSource.empty();
        final List<Object> data = asObjectList(invokeNoArg(group, "getData"));
        for (Object entry : data) {
            if (entry == null) {
                continue;
            }
            final String text = String.valueOf(entry);
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            final ParsedSource candidate = parseSourceInfo(text);
            if (!candidate.hasUsefulLocation()) {
                continue;
            }
            if (candidate.confidence > best.confidence
                    || (candidate.confidence == best.confidence && candidate.lineNumber > best.lineNumber)) {
                best.functionName = candidate.functionName;
                best.sourceFile = candidate.sourceFile;
                best.lineNumber = candidate.lineNumber;
                best.offset = candidate.offset;
                best.confidence = candidate.confidence;
            }
        }
        return best;
    }

    private static void collectToolingSources(@Nullable Object compositionData,
                                              @NonNull CaptureAccumulator accumulator) {
        try {
            final Object root = invokeStaticSingleArg(COMPOSE_SLOT_TREE_KT, "asTree", compositionData);
            if (root == null) {
                return;
            }
            final ArrayDeque<Object> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                final Object group = stack.pop();
                if (group == null) {
                    continue;
                }
                final String rawPosition = asNonEmptyString(invokeStaticSingleArg(COMPOSE_SLOT_TREE_KT, "getPosition", group));
                final ParsedSource source = parseToolingGroupSource(group);
                if (TextUtils.isEmpty(source.rawToken)) {
                    source.rawToken = rawPosition;
                }
                final Object identity = invokeNoArg(group, "getIdentity");
                if (identity != null && !accumulator.toolingSourceByIdentity.containsKey(identity) && !TextUtils.isEmpty(rawPosition)) {
                    accumulator.toolingSourceByIdentity.put(identity, source);
                }
                final Object node = invokeNoArg(group, "getNode");
                if (node != null && !accumulator.toolingSourceByNode.containsKey(node) && !TextUtils.isEmpty(rawPosition)) {
                    accumulator.toolingSourceByNode.put(node, source);
                }
                if (source.hasUsefulLocation()) {
                    if (identity != null && !accumulator.toolingSourceByIdentity.containsKey(identity)) {
                        accumulator.toolingSourceByIdentity.put(identity, source);
                    }
                    if (node != null && !accumulator.toolingSourceByNode.containsKey(node)) {
                        accumulator.toolingSourceByNode.put(node, source);
                    }
                }

                for (Object child : asObjectList(invokeNoArg(group, "getChildren"))) {
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        } catch (Throwable ignore) {
            // ui-tooling-data can be missing in some host apps; fallback to runtime parsing only.
        }
    }

    @NonNull
    private static ParsedSource parseToolingGroupSource(@Nullable Object group) {
        final ParsedSource source = ParsedSource.empty();
        if (group == null) {
            return source;
        }

        final ParsedSource positionSource = parseSourceFromPositionString(
                asNonEmptyString(invokeStaticSingleArg(COMPOSE_SLOT_TREE_KT, "getPosition", group))
        );

        final Object location = invokeNoArg(group, "getLocation");
        if (location != null) {
            source.functionName = asNonEmptyString(invokeNoArg(group, "getName"));
            source.sourceFile = asNonEmptyString(invokeNoArg(location, "getSourceFile"));
            if (TextUtils.isEmpty(source.sourceFile)) {
                source.sourceFile = inferSourceFileFromName(source.functionName);
            }
            source.lineNumber = sanitizeSourceLine(readInt(location, "getLineNumber"));
            source.offset = readInt(location, "getOffset");
            source.confidence = source.hasUsefulLocation() ? 1f : 0f;
        }

        if (!positionSource.hasUsefulLocation()) {
            return source;
        }

        if (TextUtils.isEmpty(source.rawToken)) {
            source.rawToken = positionSource.rawToken;
        }

        if (!source.hasUsefulLocation()) {
            return positionSource;
        }

        if (TextUtils.isEmpty(source.sourceFile) && !TextUtils.isEmpty(positionSource.sourceFile)) {
            source.sourceFile = positionSource.sourceFile;
        }
        if (source.lineNumber <= 0 && positionSource.lineNumber > 0) {
            source.lineNumber = positionSource.lineNumber;
        }
        if (!source.hasUsefulLocation() && positionSource.hasUsefulLocation()) {
            return positionSource;
        }
        return source;
    }

    @NonNull
    private static ParsedSource parseSourceFromPositionString(@Nullable String position) {
        final ParsedSource source = ParsedSource.empty();
        if (TextUtils.isEmpty(position)) {
            return source;
        }

        source.rawToken = position;

        String value = position.trim();
        if (TextUtils.isEmpty(value)) {
            return source;
        }

        int line = 0;
        final int colon = value.lastIndexOf(':');
        if (colon > 0 && colon + 1 < value.length()) {
            final String suffix = value.substring(colon + 1).trim();
            if (isAllDigits(suffix)) {
                try {
                    line = Integer.parseInt(suffix);
                    value = value.substring(0, colon);
                } catch (NumberFormatException ignore) {
                    line = 0;
                }
            }
        }

        line = sanitizeSourceLine(line);
        final String file = extractFileName(value);
        if (!TextUtils.isEmpty(file)) {
            source.sourceFile = file;
            source.confidence = line > 0 ? 0.9f : 0.7f;
        }
        if (line > 0) {
            source.lineNumber = line;
            source.confidence = Math.max(source.confidence, 0.9f);
        }
        return source;
    }

    private static int sanitizeSourceLine(int line) {
        if (line <= 0 || line > MAX_REASONABLE_SOURCE_LINE) {
            return 0;
        }
        return line;
    }

    private static boolean isAllDigits(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String extractFileName(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String text = raw.trim();
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < text.length()) {
            text = text.substring(slash + 1);
        }
        if (text.endsWith(".kt") || text.endsWith(".java")) {
            return text;
        }
        return null;
    }

    @Nullable
    private static String inferSourceFileFromName(@Nullable String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        String text = name;
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < text.length()) {
            text = text.substring(slash + 1);
        }
        if (text.endsWith("Kt")) {
            text = text.substring(0, text.length() - 2);
        }
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return text + ".kt";
        }
        return null;
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    @NonNull
    private static ComponentLinkStats applyComponentLinks(@NonNull ComponentBuildResult componentBuild,
                                                          @NonNull CaptureAccumulator accumulator) {
        final ComponentLinkStats stats = new ComponentLinkStats();
        if (componentBuild.componentRoots.isEmpty()) {
            return stats;
        }
        final Set<String> linkedComponentIds = new LinkedHashSet<>();
        final Set<String> heuristicLinkedComponentIds = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : accumulator.componentSemanticsLink.entrySet()) {
            if (linkComponentToSemantics(
                    entry.getKey(),
                    entry.getValue(),
                    EXACT_COMPONENT_LINK_CONFIDENCE,
                    LINK_STRATEGY_LAYOUTINFO_SEMANTICS_ID,
                    accumulator,
                    true
            )) {
                linkedComponentIds.add(entry.getKey());
            }
        }
        for (ComposeComponentNodeInfo componentRoot : componentBuild.componentRoots) {
            propagateHeuristicSemantics(componentRoot, accumulator, linkedComponentIds, heuristicLinkedComponentIds);
        }
        stats.linkedComponentCount = linkedComponentIds.size();
        stats.heuristicLinkedComponentCount = heuristicLinkedComponentIds.size();
        return stats;
    }

    private static boolean linkComponentToSemantics(@Nullable String componentId,
                                                    @Nullable String semanticsId,
                                                    float confidence,
                                                    @NonNull String strategy,
                                                    @NonNull CaptureAccumulator accumulator,
                                                    boolean allowBackfillPrimaryOwner) {
        if (TextUtils.isEmpty(componentId) || TextUtils.isEmpty(semanticsId)) {
            return false;
        }
        final ComposeComponentNodeInfo componentNode = accumulator.componentById.get(componentId);
        final InternalSemanticsNode semanticsNode = accumulator.semanticsById.get(semanticsId);
        if (componentNode == null || semanticsNode == null) {
            return false;
        }

        final String existingSemanticsId = componentNode.getSemanticsId();
        final float existingConfidence = componentNode.getSemanticsLinkConfidence();
        if (!TextUtils.isEmpty(existingSemanticsId)
                && !TextUtils.equals(existingSemanticsId, semanticsId)
                && existingConfidence > confidence) {
            return false;
        }

        final boolean isNewAssignment = TextUtils.isEmpty(existingSemanticsId);
        final boolean strongerAssignment = !TextUtils.equals(existingSemanticsId, semanticsId)
                || confidence > existingConfidence
                || !TextUtils.equals(componentNode.getSemanticsLinkStrategy(), strategy);
        if (isNewAssignment || strongerAssignment) {
            componentNode.setSemanticsId(semanticsId);
            componentNode.setSemanticsLinkStrategy(strategy);
            componentNode.setSemanticsLinkConfidence(confidence);
        }

        if (allowBackfillPrimaryOwner || TextUtils.isEmpty(semanticsNode.semanticsNode.getComponentId())) {
            semanticsNode.semanticsNode.setComponentId(componentId);
            semanticsNode.renderNode.setComponentId(componentId);
        }
        addBidirectionalLinkIfAbsent(accumulator.links, "component", componentId, "semantics", semanticsId, confidence, strategy);
        addBidirectionalLinkIfAbsent(
                accumulator.links,
                "component",
                componentId,
                "render",
                semanticsNode.renderNode.getRenderId(),
                confidence,
                strategy
        );
        return isNewAssignment || strongerAssignment;
    }

    @NonNull
    private static Set<String> propagateHeuristicSemantics(@NonNull ComposeComponentNodeInfo componentNode,
                                                           @NonNull CaptureAccumulator accumulator,
                                                           @NonNull Set<String> linkedComponentIds,
                                                           @NonNull Set<String> heuristicLinkedComponentIds) {
        final Set<String> subtreeSemanticsIds = new LinkedHashSet<>();
        final List<ComposeComponentNodeInfo> children = componentNode.getChildren();
        final int childCount = children == null ? 0 : children.size();
        if (children != null) {
            for (ComposeComponentNodeInfo child : children) {
                subtreeSemanticsIds.addAll(
                        propagateHeuristicSemantics(child, accumulator, linkedComponentIds, heuristicLinkedComponentIds)
                );
            }
        }

        final String ownSemanticsId = componentNode.getSemanticsId();
        if (!TextUtils.isEmpty(ownSemanticsId)) {
            subtreeSemanticsIds.add(ownSemanticsId);
            return subtreeSemanticsIds;
        }

        final boolean allowHeuristicLink = !componentNode.isFrameworkNode();
        if (allowHeuristicLink && childCount == 1 && subtreeSemanticsIds.size() == 1) {
            final String inheritedSemanticsId = subtreeSemanticsIds.iterator().next();
            if (linkComponentToSemantics(
                    componentNode.getComponentId(),
                    inheritedSemanticsId,
                    HEURISTIC_COMPONENT_LINK_CONFIDENCE,
                    LINK_STRATEGY_SINGLE_CHILD_UNIQUE_SEMANTICS,
                    accumulator,
                    false
            )) {
                linkedComponentIds.add(componentNode.getComponentId());
                heuristicLinkedComponentIds.add(componentNode.getComponentId());
                return singletonSemanticsId(inheritedSemanticsId);
            }
        }

        if (allowHeuristicLink && subtreeSemanticsIds.size() >= 2) {
            final HeuristicLinkCandidate candidate = buildLcaHeuristicCandidate(
                    componentNode,
                    subtreeSemanticsIds,
                    childCount,
                    accumulator
            );
            if (candidate != null && linkComponentToSemantics(
                    componentNode.getComponentId(),
                    candidate.semanticsId,
                    candidate.confidence,
                    candidate.strategy,
                    accumulator,
                    false
            )) {
                linkedComponentIds.add(componentNode.getComponentId());
                heuristicLinkedComponentIds.add(componentNode.getComponentId());
                return singletonSemanticsId(candidate.semanticsId);
            }
        }
        return subtreeSemanticsIds;
    }

    @NonNull
    private static Set<String> singletonSemanticsId(@NonNull String semanticsId) {
        final Set<String> out = new LinkedHashSet<>();
        out.add(semanticsId);
        return out;
    }

    @Nullable
    private static HeuristicLinkCandidate buildLcaHeuristicCandidate(@NonNull ComposeComponentNodeInfo componentNode,
                                                                     @NonNull Set<String> descendantSemanticsIds,
                                                                     int childCount,
                                                                     @NonNull CaptureAccumulator accumulator) {
        final String lcaSemanticsId = findLowestCommonSemanticsAncestor(descendantSemanticsIds, accumulator);
        if (TextUtils.isEmpty(lcaSemanticsId)) {
            return null;
        }
        final InternalSemanticsNode candidateNode = accumulator.semanticsById.get(lcaSemanticsId);
        if (candidateNode == null) {
            return null;
        }

        final int tokenOverlap = countSignalTokenOverlap(componentNode, candidateNode.semanticsNode);
        if (componentNode.isFrameworkNode() && tokenOverlap == 0) {
            return null;
        }
        final int maxDistance = maxAncestorDistance(lcaSemanticsId, descendantSemanticsIds, accumulator);
        if (maxDistance < 0 || maxDistance > 3) {
            return null;
        }
        final DescendantBounds descendantBounds = collectDescendantBounds(
                lcaSemanticsId,
                descendantSemanticsIds,
                accumulator
        );
        final boolean containsDescendants = candidateBoundsContainDescendants(candidateNode.semanticsNode, descendantBounds);
        if (candidateNode.parentSemanticsId == null && tokenOverlap == 0) {
            return null;
        }
        if (childCount > 3 && descendantSemanticsIds.size() > 4 && tokenOverlap == 0) {
            return null;
        }
        if (tokenOverlap == 0 && (!containsDescendants || !candidateBoundsAreTightToDescendants(candidateNode.semanticsNode, descendantBounds))) {
            return null;
        }

        float confidence = 0.46f;
        if (childCount <= 2) {
            confidence += 0.05f;
        }
        if (descendantSemanticsIds.size() <= 3) {
            confidence += 0.05f;
        }
        if (maxDistance <= 1) {
            confidence += 0.08f;
        } else if (maxDistance <= 2) {
            confidence += 0.05f;
        }
        if (containsDescendants) {
            confidence += 0.04f;
        }
        if (hasStrongSemanticsSignal(candidateNode.semanticsNode)) {
            confidence += 0.04f;
        }
        if (tokenOverlap == 1) {
            confidence += 0.07f;
        } else if (tokenOverlap >= 2) {
            confidence += 0.12f;
        }
        if (candidateNode.parentSemanticsId != null) {
            confidence += 0.03f;
        } else {
            confidence -= 0.05f;
        }
        if (confidence < LCA_HEURISTIC_LINK_CONFIDENCE_THRESHOLD) {
            return null;
        }

        final HeuristicLinkCandidate candidate = new HeuristicLinkCandidate();
        candidate.semanticsId = lcaSemanticsId;
        candidate.confidence = Math.max(HEURISTIC_COMPONENT_LINK_CONFIDENCE, confidence);
        candidate.strategy = tokenOverlap > 0
                ? LINK_STRATEGY_SEMANTICS_LCA_SIGNAL
                : LINK_STRATEGY_SEMANTICS_LCA_NARROW_SUBTREE;
        return candidate;
    }

    @Nullable
    private static String findLowestCommonSemanticsAncestor(@NonNull Set<String> semanticsIds,
                                                            @NonNull CaptureAccumulator accumulator) {
        String firstId = null;
        for (String semanticsId : semanticsIds) {
            if (!TextUtils.isEmpty(semanticsId)) {
                firstId = semanticsId;
                break;
            }
        }
        if (TextUtils.isEmpty(firstId)) {
            return null;
        }

        String current = firstId;
        while (!TextUtils.isEmpty(current)) {
            boolean matchesAll = true;
            for (String semanticsId : semanticsIds) {
                if (!isSemanticsAncestor(current, semanticsId, accumulator)) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                return current;
            }
            current = parentSemanticsIdOf(current, accumulator);
        }
        return null;
    }

    private static boolean isSemanticsAncestor(@Nullable String candidateAncestorId,
                                               @Nullable String semanticsId,
                                               @NonNull CaptureAccumulator accumulator) {
        if (TextUtils.isEmpty(candidateAncestorId) || TextUtils.isEmpty(semanticsId)) {
            return false;
        }
        String current = semanticsId;
        while (!TextUtils.isEmpty(current)) {
            if (TextUtils.equals(candidateAncestorId, current)) {
                return true;
            }
            current = parentSemanticsIdOf(current, accumulator);
        }
        return false;
    }

    private static int maxAncestorDistance(@Nullable String ancestorId,
                                           @NonNull Set<String> semanticsIds,
                                           @NonNull CaptureAccumulator accumulator) {
        int maxDistance = -1;
        for (String semanticsId : semanticsIds) {
            final int distance = ancestorDistance(ancestorId, semanticsId, accumulator);
            if (distance < 0) {
                return -1;
            }
            maxDistance = Math.max(maxDistance, distance);
        }
        return maxDistance;
    }

    private static int ancestorDistance(@Nullable String ancestorId,
                                        @Nullable String semanticsId,
                                        @NonNull CaptureAccumulator accumulator) {
        if (TextUtils.isEmpty(ancestorId) || TextUtils.isEmpty(semanticsId)) {
            return -1;
        }
        int distance = 0;
        String current = semanticsId;
        while (!TextUtils.isEmpty(current)) {
            if (TextUtils.equals(ancestorId, current)) {
                return distance;
            }
            current = parentSemanticsIdOf(current, accumulator);
            distance++;
        }
        return -1;
    }

    @Nullable
    private static String parentSemanticsIdOf(@Nullable String semanticsId,
                                              @NonNull CaptureAccumulator accumulator) {
        if (TextUtils.isEmpty(semanticsId)) {
            return null;
        }
        final InternalSemanticsNode node = accumulator.semanticsById.get(semanticsId);
        return node == null ? null : node.parentSemanticsId;
    }

    @NonNull
    private static DescendantBounds collectDescendantBounds(@Nullable String excludeSemanticsId,
                                                            @NonNull Set<String> semanticsIds,
                                                            @NonNull CaptureAccumulator accumulator) {
        final DescendantBounds bounds = new DescendantBounds();
        for (String semanticsId : semanticsIds) {
            if (TextUtils.equals(excludeSemanticsId, semanticsId)) {
                continue;
            }
            final InternalSemanticsNode semanticsNode = accumulator.semanticsById.get(semanticsId);
            if (semanticsNode == null) {
                continue;
            }
            bounds.include(semanticsNode.semanticsNode);
        }
        return bounds;
    }

    private static boolean candidateBoundsContainDescendants(@NonNull ComposeSemanticsNodeInfo candidateNode,
                                                             @NonNull DescendantBounds bounds) {
        if (!bounds.hasValue) {
            return false;
        }
        return candidateNode.getLeft() <= bounds.left
                && candidateNode.getTop() <= bounds.top
                && candidateNode.getRight() >= bounds.right
                && candidateNode.getBottom() >= bounds.bottom;
    }

    private static boolean candidateBoundsAreTightToDescendants(@NonNull ComposeSemanticsNodeInfo candidateNode,
                                                                @NonNull DescendantBounds bounds) {
        if (!bounds.hasValue) {
            return false;
        }
        final int candidateWidth = Math.max(1, candidateNode.getRight() - candidateNode.getLeft());
        final int candidateHeight = Math.max(1, candidateNode.getBottom() - candidateNode.getTop());
        final int descendantWidth = Math.max(1, bounds.right - bounds.left);
        final int descendantHeight = Math.max(1, bounds.bottom - bounds.top);
        final long candidateArea = (long) candidateWidth * candidateHeight;
        final long descendantArea = (long) descendantWidth * descendantHeight;
        return candidateWidth <= descendantWidth + 48
                && candidateHeight <= descendantHeight + 48
                && candidateArea <= Math.max(descendantArea * 3L, descendantArea + 4096L);
    }

    private static boolean hasStrongSemanticsSignal(@NonNull ComposeSemanticsNodeInfo semanticsNode) {
        return !TextUtils.isEmpty(semanticsNode.getTestTag())
                || !TextUtils.isEmpty(semanticsNode.getText())
                || !TextUtils.isEmpty(semanticsNode.getContentDescription());
    }

    private static int countSignalTokenOverlap(@NonNull ComposeComponentNodeInfo componentNode,
                                               @NonNull ComposeSemanticsNodeInfo semanticsNode) {
        final Set<String> componentTokens = extractMeaningfulTokens(
                safeJoin(
                        componentNode.getDisplayName(),
                        componentNode.getSourcePathToken(),
                        extractFileName(componentNode.getSourcePath())
                )
        );
        if (componentTokens.isEmpty()) {
            return 0;
        }
        final Set<String> semanticsTokens = extractMeaningfulTokens(
                safeJoin(
                        semanticsNode.getText(),
                        semanticsNode.getContentDescription(),
                        semanticsNode.getTestTag(),
                        semanticsNode.getRole(),
                        semanticsNode.getClassName()
                )
        );
        if (semanticsTokens.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String token : componentTokens) {
            if (semanticsTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    @NonNull
    private static Set<String> extractMeaningfulTokens(@Nullable String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return Collections.emptySet();
        }
        final String normalized = rawText
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .toLowerCase(Locale.US);
        final String[] pieces = normalized.split("[^a-z0-9]+");
        final Set<String> out = new LinkedHashSet<>();
        for (String piece : pieces) {
            if (TextUtils.isEmpty(piece) || piece.length() < 2 || isAllDigits(piece) || isLowSignalToken(piece)) {
                continue;
            }
            out.add(piece);
        }
        return out;
    }

    @NonNull
    private static String safeJoin(@Nullable String... values) {
        final List<String> parts = new ArrayList<>();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? "" : TextUtils.join(" ", parts);
    }

    private static boolean isLowSignalToken(@Nullable String token) {
        if (TextUtils.isEmpty(token)) {
            return true;
        }
        return "kt".equals(token)
                || "java".equals(token)
                || "src".equals(token)
                || "main".equals(token)
                || "app".equals(token)
                || "kotlin".equals(token)
                || "com".equals(token)
                || "androidx".equals(token)
                || "compose".equals(token)
                || "component".equals(token)
                || "group".equals(token)
                || "layout".equals(token)
                || "node".equals(token)
                || "modifier".equals(token)
                || "material".equals(token)
                || "screen".equals(token);
    }

    @Nullable
    private static Object findCompositionData(@Nullable View composeHost) {
        if (composeHost == null) {
            return null;
        }
        Object composition = readField(composeHost, "composition");
        if (composition == null && composeHost.getParent() instanceof View) {
            composition = readField((View) composeHost.getParent(), "composition");
        }
        if (composition == null) {
            return null;
        }
        composition = unwrapComposition(composition);
        final Object slotTable = findSlotTable(composition);
        if (slotTable != null) {
            invokeNoArg(slotTable, "collectSourceInformation");
        }
        Object composer = readField(composition, "composer");
        if (composer == null) {
            composer = invokeNoArg(composition, "getComposer");
        }
        Object compositionData = invokeNoArg(composer, "getCompositionData");
        if (compositionData != null) {
            return compositionData;
        }
        compositionData = invokeNoArg(composition, "getCompositionData");
        if (compositionData != null) {
            return compositionData;
        }
        return slotTable;
    }

    @Nullable
    private static Object unwrapComposition(@Nullable Object composition) {
        Object current = composition;
        for (int i = 0; i < 4 && current != null; i++) {
            final Object original = readField(current, "original");
            if (original != null && original != current) {
                current = original;
                continue;
            }
            final Object getter = invokeNoArg(current, "getOriginal");
            if (getter != null && getter != current) {
                current = getter;
                continue;
            }
            break;
        }
        return current;
    }

    @Nullable
    private static Object findSlotTable(@Nullable Object composition) {
        if (composition == null) {
            return null;
        }
        Object slotTable = invokeNoArg(composition, "getSlotTable$runtime");
        if (slotTable != null) {
            return slotTable;
        }
        slotTable = invokeNoArg(composition, "getSlotTable");
        if (slotTable != null) {
            return slotTable;
        }
        return readField(composition, "slotTable");
    }

    @Nullable
    private static ParsedSource parseSourceInfo(@Nullable String rawSourceInfo) {
        if (TextUtils.isEmpty(rawSourceInfo)) {
            return ParsedSource.empty();
        }

        try {
            final Class<?> parserClass = Class.forName(COMPOSE_SOURCE_INFORMATION_KT);
            final Method parseMethod = parserClass.getMethod("parseSourceInformation", String.class);
            final Object parsed = parseMethod.invoke(null, rawSourceInfo);
            if (parsed != null) {
                final ParsedSource source = new ParsedSource();
                source.functionName = asNonEmptyString(invokeNoArg(parsed, "getFunctionName"));
                source.sourceFile = asNonEmptyString(invokeNoArg(parsed, "getSourceFile"));
                final List<Object> locations = asObjectList(invokeNoArg(parsed, "getLocations"));
                if (!locations.isEmpty()) {
                    source.lineNumber = readInt(locations.get(0), "getLineNumber");
                    source.offset = readInt(locations.get(0), "getOffset");
                }
                source.confidence = !TextUtils.isEmpty(source.sourceFile) && source.lineNumber > 0
                        ? 1f
                        : (!TextUtils.isEmpty(source.sourceFile) ? 0.8f : 0.5f);
                return source;
            }
        } catch (Throwable ignore) {
            // Compose runtime versions before the parser existed will fall back to raw token handling.
        }

        final ParsedSource source = new ParsedSource();
        source.functionName = parseNameFromRawSourceInfo(rawSourceInfo);
        source.sourceFile = parseFileFromRawSourceInfo(rawSourceInfo);
        source.lineNumber = parseLineFromRawSourceInfo(rawSourceInfo);
        source.confidence = !TextUtils.isEmpty(source.sourceFile) ? 0.6f : (!TextUtils.isEmpty(source.functionName) ? 0.4f : 0.2f);
        return source;
    }

    @NonNull
    private static String chooseDisplayName(@NonNull ParsedSource parsedSource,
                                            @Nullable String rawSourceInfo,
                                            @Nullable Object key,
                                            @Nullable Object node) {
        if (!TextUtils.isEmpty(parsedSource.functionName)) {
            return parsedSource.functionName;
        }
        final String rawName = parseNameFromRawSourceInfo(rawSourceInfo);
        if (!TextUtils.isEmpty(rawName)) {
            return rawName;
        }
        final String keyName = simplifyName(key);
        if (!TextUtils.isEmpty(keyName)) {
            return keyName;
        }
        final String nodeName = simplifyName(node);
        return TextUtils.isEmpty(nodeName) ? "ComposeGroup" : nodeName;
    }

    private static boolean isFrameworkNode(@Nullable String displayName,
                                           @Nullable String rawSourceInfo,
                                           @Nullable String sourceFile) {
        final String merged = String.valueOf(displayName) + "|" + String.valueOf(rawSourceInfo) + "|" + String.valueOf(sourceFile);
        final String text = merged.toLowerCase(Locale.US);
        return text.contains("androidx.compose")
                || text.contains("compose.material")
                || text.contains("material3")
                || text.contains("subcompose")
                || text.contains("remember");
    }

    @NonNull
    private static String summarizeModifiers(@Nullable Object layoutInfo) {
        final Object modifiers = invokeNoArg(layoutInfo, "getModifierInfo");
        final List<Object> modifierList = asObjectList(modifiers);
        if (modifierList.isEmpty()) {
            return "";
        }
        final Set<String> names = new LinkedHashSet<>();
        for (Object modifierInfo : modifierList) {
            if (names.size() >= 4) {
                break;
            }
            final Object modifier = invokeNoArg(modifierInfo, "getModifier");
            final String name = simplifyName(modifier);
            if (!TextUtils.isEmpty(name)) {
                names.add(name);
            }
        }
        return TextUtils.join(", ", names);
    }

    @NonNull
    private static String summarizeSemantics(@NonNull ComposeSemanticsNodeInfo semanticsNode) {
        final List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(semanticsNode.getText())) {
            parts.add("text=" + semanticsNode.getText());
        }
        if (!TextUtils.isEmpty(semanticsNode.getTestTag())) {
            parts.add("tag=" + semanticsNode.getTestTag());
        }
        if (!TextUtils.isEmpty(semanticsNode.getContentDescription())) {
            parts.add("desc=" + semanticsNode.getContentDescription());
        }
        if (semanticsNode.isClickable()) {
            parts.add("clickable");
        }
        return parts.isEmpty() ? "" : TextUtils.join(" | ", parts);
    }

    @NonNull
    private static ComposeNodeInfo toLegacyNode(@NonNull ComposeSemanticsNodeInfo semanticsNode) {
        final ComposeNodeInfo legacyNode = new ComposeNodeInfo();
        legacyNode.setLeft(semanticsNode.getLeft());
        legacyNode.setTop(semanticsNode.getTop());
        legacyNode.setRight(semanticsNode.getRight());
        legacyNode.setBottom(semanticsNode.getBottom());
        legacyNode.setText(semanticsNode.getText());
        legacyNode.setContentDescription(semanticsNode.getContentDescription());
        legacyNode.setTestTag(semanticsNode.getTestTag());
        legacyNode.setClickable(semanticsNode.isClickable());
        legacyNode.setEnabled(semanticsNode.isEnabled());
        legacyNode.setFocused(semanticsNode.isFocused());
        legacyNode.setVisibleToUser(semanticsNode.isVisibleToUser());
        legacyNode.setSelected(semanticsNode.isSelected());
        legacyNode.setCheckable(semanticsNode.isCheckable());
        legacyNode.setChecked(semanticsNode.isChecked());
        legacyNode.setFocusable(semanticsNode.isFocusable());
        legacyNode.setActions(semanticsNode.getActions());
        return legacyNode;
    }

    private static void addBidirectionalLink(@NonNull List<ComposeLinkInfo> links,
                                             @NonNull String sourceType,
                                             @NonNull String sourceId,
                                             @NonNull String targetType,
                                             @NonNull String targetId,
                                             float confidence,
                                             @NonNull String strategy) {
        links.add(buildLink(sourceType, sourceId, targetType, targetId, confidence, strategy));
        links.add(buildLink(targetType, targetId, sourceType, sourceId, confidence, strategy));
    }

    private static void addBidirectionalLinkIfAbsent(@NonNull List<ComposeLinkInfo> links,
                                                     @NonNull String sourceType,
                                                     @NonNull String sourceId,
                                                     @NonNull String targetType,
                                                     @NonNull String targetId,
                                                     float confidence,
                                                     @NonNull String strategy) {
        if (!hasLink(links, sourceType, sourceId, targetType, targetId, strategy)) {
            links.add(buildLink(sourceType, sourceId, targetType, targetId, confidence, strategy));
        }
        if (!hasLink(links, targetType, targetId, sourceType, sourceId, strategy)) {
            links.add(buildLink(targetType, targetId, sourceType, sourceId, confidence, strategy));
        }
    }

    private static boolean hasLink(@NonNull List<ComposeLinkInfo> links,
                                   @NonNull String sourceType,
                                   @NonNull String sourceId,
                                   @NonNull String targetType,
                                   @NonNull String targetId,
                                   @NonNull String strategy) {
        for (ComposeLinkInfo link : links) {
            if (TextUtils.equals(sourceType, link.getSourceNodeType())
                    && TextUtils.equals(sourceId, link.getSourceId())
                    && TextUtils.equals(targetType, link.getTargetNodeType())
                    && TextUtils.equals(targetId, link.getTargetId())
                    && TextUtils.equals(strategy, link.getLinkStrategy())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static ComposeLinkInfo buildLink(@NonNull String sourceType,
                                             @NonNull String sourceId,
                                             @NonNull String targetType,
                                             @NonNull String targetId,
                                             float confidence,
                                             @NonNull String strategy) {
        final ComposeLinkInfo linkInfo = new ComposeLinkInfo();
        linkInfo.setSourceNodeType(sourceType);
        linkInfo.setSourceId(sourceId);
        linkInfo.setTargetNodeType(targetType);
        linkInfo.setTargetId(targetId);
        linkInfo.setConfidence(confidence);
        linkInfo.setLinkStrategy(strategy);
        return linkInfo;
    }

    private static boolean isAndroidComposeView(@Nullable View view) {
        return view != null && ANDROID_COMPOSE_VIEW.equals(view.getClass().getName());
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

    @Nullable
    private static Object invokeNoArg(@Nullable Object target, @NonNull String methodName) {
        if (target == null) {
            return null;
        }
        try {
            final Method method = findMethod(target.getClass(), methodName);
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(@Nullable Class<?> type, @NonNull String methodName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignore) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Object invokeStaticSingleArg(@NonNull String className,
                                                @NonNull String methodName,
                                                @Nullable Object arg) {
        if (arg == null) {
            return null;
        }
        try {
            final Class<?> cls = Class.forName(className);
            for (Method method : cls.getDeclaredMethods()) {
                if (!methodName.equals(method.getName()) || method.getParameterTypes().length != 1) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(null, arg);
            }
        } catch (Throwable ignore) {
            return null;
        }
        return null;
    }

    @Nullable
    private static Object readField(@Nullable Object target, @NonNull String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            try {
                final Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignore) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static int readInt(@Nullable Object target, @NonNull String methodName) {
        final Object value = invokeNoArg(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private static float readFloat(@Nullable Object target, @NonNull String methodName) {
        final Object value = invokeNoArg(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return 0f;
    }

    @NonNull
    private static String stringify(@Nullable Object value, @NonNull String fallback) {
        final String text = asNonEmptyString(value);
        return TextUtils.isEmpty(text) ? fallback : text;
    }

    @NonNull
    private static Map<String, Object> readSemanticsProps(@Nullable Object config) {
        if (!(config instanceof Iterable)) {
            return Collections.emptyMap();
        }
        final Map<String, Object> props = new LinkedHashMap<>();
        for (Object item : (Iterable<?>) config) {
            if (!(item instanceof Map.Entry)) {
                continue;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
            final Object name = invokeNoArg(entry.getKey(), "getName");
            if (name instanceof String && !TextUtils.isEmpty((String) name)) {
                props.put((String) name, entry.getValue());
            }
        }
        return props;
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
        return value instanceof CharSequence ? value.toString() : null;
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
            return parts.isEmpty() ? null : TextUtils.join(", ", parts);
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

    private static boolean isChecked(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        final String text = String.valueOf(value);
        return "On".equalsIgnoreCase(text) || "Indeterminate".equalsIgnoreCase(text);
    }

    @NonNull
    private static List<String> extractSemanticsActions(@NonNull Map<String, Object> props) {
        final List<String> out = new ArrayList<>();
        appendAction(out, props, "OnClick", "CLICK");
        appendAction(out, props, "OnLongClick", "LONG_CLICK");
        appendAction(out, props, "ScrollBy", "SCROLL");
        appendAction(out, props, "ScrollToIndex", "SCROLL_TO_INDEX");
        appendAction(out, props, "SetProgress", "SET_PROGRESS");
        appendAction(out, props, "SetSelection", "SET_SELECTION");
        appendAction(out, props, "SetText", "SET_TEXT");
        appendAction(out, props, "InsertTextAtCursor", "INSERT_TEXT");
        appendAction(out, props, "CopyText", "COPY_TEXT");
        appendAction(out, props, "CutText", "CUT_TEXT");
        appendAction(out, props, "PasteText", "PASTE_TEXT");
        appendAction(out, props, "Expand", "EXPAND");
        appendAction(out, props, "Collapse", "COLLAPSE");
        appendAction(out, props, "Dismiss", "DISMISS");
        appendAction(out, props, "RequestFocus", "FOCUS");
        return out.isEmpty() ? Collections.<String>emptyList() : out;
    }

    private static void appendAction(@NonNull List<String> out,
                                     @NonNull Map<String, Object> props,
                                     @NonNull String key,
                                     @NonNull String fallbackName) {
        if (!props.containsKey(key)) {
            return;
        }
        final Object label = invokeNoArg(props.get(key), "getLabel");
        final String actionName = asNonEmptyString(label);
        final String name = TextUtils.isEmpty(actionName) ? fallbackName : actionName;
        if (!out.contains(name)) {
            out.add(name);
        }
    }

    private static final String[] ROLE_NAMES = {
            "Button", "Checkbox", "Switch", "RadioButton", "Tab", "Image", "DropdownList"
    };

    @Nullable
    private static String extractRoleName(@Nullable Object roleValue) {
        if (roleValue instanceof Number) {
            final int index = ((Number) roleValue).intValue();
            if (index >= 0 && index < ROLE_NAMES.length) {
                return ROLE_NAMES[index];
            }
            return "Role(" + index + ")";
        }
        if (roleValue != null) {
            final String str = String.valueOf(roleValue);
            if (!TextUtils.isEmpty(str)) {
                return str;
            }
        }
        return null;
    }

    @NonNull
    private static String inferClassName(@NonNull ComposeSemanticsNodeInfo semanticsInfo,
                                         @NonNull Map<String, Object> props) {
        final String role = semanticsInfo.getRole();
        if (!TextUtils.isEmpty(role)) {
            return role;
        }
        if (!TextUtils.isEmpty(semanticsInfo.getText())) {
            return "Text";
        }
        if (props.containsKey("EditableText") || props.containsKey("SetText") || props.containsKey("InsertTextAtCursor")) {
            return "TextField";
        }
        if (!TextUtils.isEmpty(semanticsInfo.getContentDescription()) && !semanticsInfo.isClickable()) {
            return "Image";
        }
        if (props.containsKey("ProgressBarRangeInfo") || props.containsKey("SetProgress")) {
            return "ProgressBar";
        }
        if (props.containsKey("ScrollBy") || props.containsKey("ScrollToIndex")) {
            return "ScrollableContainer";
        }
        if (semanticsInfo.isCheckable()) {
            return "Checkbox";
        }
        if (semanticsInfo.isClickable()) {
            return "Clickable";
        }
        return "Node";
    }

    @NonNull
    private static String inferRenderTypeName(@NonNull ComposeSemanticsNodeInfo semanticsInfo,
                                               @Nullable String modifierSummary) {
        final String className = semanticsInfo.getClassName();
        if (!TextUtils.isEmpty(className) && !"Node".equals(className)) {
            return className;
        }
        if (!TextUtils.isEmpty(modifierSummary)) {
            if (modifierSummary.contains("TextStringSimpleElement") || modifierSummary.contains("TextAnnotatedStringElement")) {
                return "Text";
            }
            if (modifierSummary.contains("ScrollableElement")) {
                return "Scrollable";
            }
            if (modifierSummary.contains("FillElement")) {
                return "Container";
            }
        }
        return className != null ? className : "Node";
    }

    @Nullable
    private static String parseNameFromRawSourceInfo(@Nullable String rawSourceInfo) {
        if (TextUtils.isEmpty(rawSourceInfo)) {
            return null;
        }
        final int start = rawSourceInfo.indexOf("C(");
        if (start >= 0) {
            final int end = rawSourceInfo.indexOf(')', start + 2);
            if (end > start + 2) {
                return rawSourceInfo.substring(start + 2, end);
            }
        }
        return null;
    }

    @Nullable
    private static String parseFileFromRawSourceInfo(@Nullable String rawSourceInfo) {
        if (TextUtils.isEmpty(rawSourceInfo)) {
            return null;
        }
        final int marker = rawSourceInfo.lastIndexOf(':');
        final String suffix = marker >= 0 ? rawSourceInfo.substring(marker + 1) : rawSourceInfo;
        final int hashIndex = suffix.indexOf('#');
        final String candidate = hashIndex >= 0 ? suffix.substring(0, hashIndex) : suffix;
        return candidate.contains(".kt") || candidate.contains(".java") ? candidate : null;
    }

    private static int parseLineFromRawSourceInfo(@Nullable String rawSourceInfo) {
        if (TextUtils.isEmpty(rawSourceInfo)) {
            return 0;
        }
        final int at = rawSourceInfo.lastIndexOf('@');
        if (at < 0) {
            return 0;
        }
        int cursor = at + 1;
        final StringBuilder digits = new StringBuilder();
        while (cursor < rawSourceInfo.length()) {
            final char ch = rawSourceInfo.charAt(cursor);
            if (!Character.isDigit(ch)) {
                break;
            }
            digits.append(ch);
            cursor++;
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    @Nullable
    private static String simplifyName(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        final String name = String.valueOf(value);
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        final int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 && lastDot + 1 < name.length() ? name.substring(lastDot + 1) : name;
    }

    public static final class CaptureResult {
        private static final CaptureResult EMPTY =
                new CaptureResult(null, Collections.<ComposeNodeInfo>emptyList(), 0, 0, 0, 0, 0, 0, Collections.<String>emptyList());

        private final ComposeCaptureInfo mCapture;
        private final List<ComposeNodeInfo> mLegacyNodes;
        private final int mComponentCount;
        private final int mRenderCount;
        private final int mSemanticsCount;
        private final int mSourceMappedComponentCount;
        private final int mLinkedComponentCount;
        private final int mHeuristicLinkedComponentCount;
        private final List<String> mErrors;

        private CaptureResult(@Nullable ComposeCaptureInfo capture,
                              @NonNull List<ComposeNodeInfo> legacyNodes,
                              int componentCount,
                              int renderCount,
                              int semanticsCount,
                              int sourceMappedComponentCount,
                              int linkedComponentCount,
                              int heuristicLinkedComponentCount,
                              @NonNull List<String> errors) {
            this.mCapture = capture;
            this.mLegacyNodes = legacyNodes;
            this.mComponentCount = componentCount;
            this.mRenderCount = renderCount;
            this.mSemanticsCount = semanticsCount;
            this.mSourceMappedComponentCount = sourceMappedComponentCount;
            this.mLinkedComponentCount = linkedComponentCount;
            this.mHeuristicLinkedComponentCount = heuristicLinkedComponentCount;
            this.mErrors = errors;
        }

        @NonNull
        public static CaptureResult empty() {
            return EMPTY;
        }

        @Nullable
        public ComposeCaptureInfo getCapture() {
            return mCapture;
        }

        @NonNull
        public List<ComposeNodeInfo> getLegacyNodes() {
            return mLegacyNodes;
        }

        public int getComponentCount() {
            return mComponentCount;
        }

        public int getRenderCount() {
            return mRenderCount;
        }

        public int getSemanticsCount() {
            return mSemanticsCount;
        }

        public int getSourceMappedComponentCount() {
            return mSourceMappedComponentCount;
        }

        public int getLinkedComponentCount() {
            return mLinkedComponentCount;
        }

        public int getHeuristicLinkedComponentCount() {
            return mHeuristicLinkedComponentCount;
        }

        @NonNull
        public List<String> getErrors() {
            return mErrors;
        }

        public boolean hasCapture() {
            return mCapture != null;
        }
    }

    private static final class CaptureAccumulator {
        final Map<String, InternalSemanticsNode> semanticsById = new LinkedHashMap<>();
        final Map<String, ComposeRenderNodeInfo> renderById = new LinkedHashMap<>();
        final Map<String, ComposeComponentNodeInfo> componentById = new LinkedHashMap<>();
        final Map<String, String> componentSemanticsLink = new LinkedHashMap<>();
        final Map<Object, ParsedSource> toolingSourceByIdentity = new IdentityHashMap<>();
        final Map<Object, ParsedSource> toolingSourceByNode = new IdentityHashMap<>();
        final List<ComposeLinkInfo> links = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private static final class SemanticsBuildResult {
        final List<ComposeSemanticsNodeInfo> semanticsRoots;
        final List<ComposeRenderNodeInfo> renderRoots;
        final List<ComposeNodeInfo> legacyRoots;
        final int semanticsCount;
        final int renderCount;

        private SemanticsBuildResult(@NonNull List<ComposeSemanticsNodeInfo> semanticsRoots,
                                     @NonNull List<ComposeRenderNodeInfo> renderRoots,
                                     @NonNull List<ComposeNodeInfo> legacyRoots,
                                     int semanticsCount,
                                     int renderCount) {
            this.semanticsRoots = semanticsRoots;
            this.renderRoots = renderRoots;
            this.legacyRoots = legacyRoots;
            this.semanticsCount = semanticsCount;
            this.renderCount = renderCount;
        }
    }

    private static final class ComponentBuildResult {
        static final ComponentBuildResult EMPTY = new ComponentBuildResult(Collections.<ComposeComponentNodeInfo>emptyList(), 0, 0);

        final List<ComposeComponentNodeInfo> componentRoots;
        final int componentCount;
        final int sourceMappedComponentCount;

        private ComponentBuildResult(@NonNull List<ComposeComponentNodeInfo> componentRoots,
                                     int componentCount,
                                     int sourceMappedComponentCount) {
            this.componentRoots = componentRoots;
            this.componentCount = componentCount;
            this.sourceMappedComponentCount = sourceMappedComponentCount;
        }

        @NonNull
        static ComponentBuildResult empty() {
            return EMPTY;
        }
    }

    private static final class ComponentBuildCounters {
        int componentCount;
        int sourceMappedComponentCount;
    }

    private static final class ComponentLinkStats {
        int linkedComponentCount;
        int heuristicLinkedComponentCount;
    }

    private static final class InternalSemanticsNode {
        ComposeSemanticsNodeInfo semanticsNode;
        ComposeNodeInfo legacyNode;
        ComposeRenderNodeInfo renderNode;
        Object layoutInfo;
        String parentSemanticsId;
        int depth;
    }

    private static final class InternalComponentNode {
        ComposeComponentNodeInfo componentNode;
        int descendantCount;
        int descendantSourceMappedCount;
    }

    private static final class HeuristicLinkCandidate {
        String semanticsId;
        float confidence;
        String strategy;
    }

    private static final class DescendantBounds {
        boolean hasValue;
        int left;
        int top;
        int right;
        int bottom;

        void include(@NonNull ComposeSemanticsNodeInfo node) {
            if (!hasValue) {
                left = node.getLeft();
                top = node.getTop();
                right = node.getRight();
                bottom = node.getBottom();
                hasValue = true;
                return;
            }
            left = Math.min(left, node.getLeft());
            top = Math.min(top, node.getTop());
            right = Math.max(right, node.getRight());
            bottom = Math.max(bottom, node.getBottom());
        }
    }

    private static final class ParsedSource {
        String functionName;
        String sourceFile;
        int lineNumber;
        int offset;
        float confidence;
        String rawToken;

        boolean hasUsefulLocation() {
            return !TextUtils.isEmpty(sourceFile) || lineNumber > 0;
        }

        @NonNull
        static ParsedSource empty() {
            return new ParsedSource();
        }
    }
}
