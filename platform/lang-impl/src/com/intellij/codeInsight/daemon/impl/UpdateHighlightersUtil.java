/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.intention.impl.FileLevelIntentionComponent;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.ex.SweepProcessor;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.impl.RangeMarkerTree;
import com.intellij.openapi.editor.impl.RedBlackTree;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.Consumer;
import com.intellij.util.Processor;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class UpdateHighlightersUtil {
  private static final Comparator<HighlightInfo> BY_START_OFFSET_NODUPS = new Comparator<HighlightInfo>() {
    @Override
    public int compare(HighlightInfo o1, HighlightInfo o2) {
      int d = o1.getActualStartOffset() - o2.getActualStartOffset();
      if (d != 0) return d;
      d = o1.getActualEndOffset() - o2.getActualEndOffset();
      if (d != 0) return d;

      d = Comparing.compare(o1.getSeverity(), o2.getSeverity());
      if (d != 0) return -d; // higher severity first, to prevent warnings overlap errors

      if (!Comparing.equal(o1.type, o2.type)) {
        return String.valueOf(o1.type).compareTo(String.valueOf(o2.type));
      }

      if (!Comparing.equal(o1.getGutterIconRenderer(), o2.getGutterIconRenderer())) {
        return String.valueOf(o1.getGutterIconRenderer()).compareTo(String.valueOf(o2.getGutterIconRenderer()));
      }

      if (!Comparing.equal(o1.forcedTextAttributes, o2.forcedTextAttributes)) {
        return String.valueOf(o1.getGutterIconRenderer()).compareTo(String.valueOf(o2.getGutterIconRenderer()));
      }

      if (!Comparing.equal(o1.forcedTextAttributesKey, o2.forcedTextAttributesKey)) {
        return String.valueOf(o1.getGutterIconRenderer()).compareTo(String.valueOf(o2.getGutterIconRenderer()));
      }

      return Comparing.compare(o1.description, o2.description);
    }
  };

  private static final Key<List<HighlightInfo>> FILE_LEVEL_HIGHLIGHTS = Key.create("FILE_LEVEL_HIGHLIGHTS");
  private static final Comparator<TextRange> BY_START_OFFSET = new Comparator<TextRange>() {
    @Override
    public int compare(final TextRange o1, final TextRange o2) {
      return o1.getStartOffset() - o2.getStartOffset();
    }
  };

  static void cleanFileLevelHighlights(@NotNull Project project, final int group, PsiFile psiFile) {
    if (psiFile == null || !psiFile.getViewProvider().isPhysical()) return;
    VirtualFile vFile = psiFile.getViewProvider().getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    for (FileEditor fileEditor : manager.getEditors(vFile)) {
      final List<HighlightInfo> infos = fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS);
      if (infos == null) continue;
      List<HighlightInfo> infosToRemove = new ArrayList<HighlightInfo>();
      for (HighlightInfo info : infos) {
        if (info.group == group) {
          manager.removeTopComponent(fileEditor, info.fileLevelComponent);
          infosToRemove.add(info);
        }
      }
      infos.removeAll(infosToRemove);
    }
  }

  private static boolean isCoveredByOffsets(HighlightInfo info, HighlightInfo coveredBy) {
    return coveredBy.startOffset <= info.startOffset && info.endOffset <= coveredBy.endOffset && info.getGutterIconRenderer() == null;
  }

  private static class HighlightersRecycler {
    private final MultiMap<TextRange, RangeHighlighter> incinerator = new MultiMap<TextRange, RangeHighlighter>(){
      @Override
      protected Map<TextRange, Collection<RangeHighlighter>> createMap() {
        return new THashMap<TextRange, Collection<RangeHighlighter>>();
      }

      @Override
      protected Collection<RangeHighlighter> createCollection() {
        return new SmartList<RangeHighlighter>();
      }
    };

    private void recycleHighlighter(@NotNull RangeHighlighter highlighter) {
      if (highlighter.isValid()) {
        incinerator.putValue(ProperTextRange.create(highlighter), highlighter);
      }
    }

    private RangeHighlighter pickupHighlighterFromGarbageBin(int startOffset, int endOffset, int layer){
      TextRange range = new TextRange(startOffset, endOffset);
      Collection<RangeHighlighter> collection = incinerator.get(range);
      for (RangeHighlighter highlighter : collection) {
        if (highlighter.isValid() && highlighter.getLayer() == layer) {
          incinerator.removeValue(range, highlighter);
          return highlighter;
        }
      }
      return null;
    }

    @NotNull
    private Collection<? extends RangeHighlighter> forAllInGarbageBin() {
      return incinerator.values();
    }
  }

  static void addHighlighterToEditorIncrementally(@NotNull Project project,
                                                  @NotNull Document document,
                                                  @NotNull PsiFile file,
                                                  int startOffset,
                                                  int endOffset,
                                                  @NotNull final HighlightInfo info,
                                                  @Nullable final EditorColorsScheme colorsScheme, // if null global scheme will be used
                                                  final int group,
                                                  @NotNull Map<TextRange, RangeMarker> ranges2markersCache) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    if (info.isFileLevelAnnotation || info.getGutterIconRenderer() != null) return;
    if (info.getStartOffset() < startOffset || info.getEndOffset() > endOffset) return;

    MarkupModel markup = DocumentMarkupModel.forDocument(document, project, true);
    final SeverityRegistrar severityRegistrar = SeverityRegistrar.getInstance(project);
    final boolean myInfoIsError = isSevere(info, severityRegistrar);
    Processor<HighlightInfo> otherHighlightInTheWayProcessor = new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo oldInfo) {
        if (!myInfoIsError && isCovered(info, severityRegistrar, oldInfo)) {
          return false;
        }

        return oldInfo.group != group || !oldInfo.equalsByActualOffset(info);
      }
    };
    boolean allIsClear = DaemonCodeAnalyzerImpl.processHighlights(document, project,
                                                                  null, info.getActualStartOffset(), info.getActualEndOffset(),
                                                                  otherHighlightInTheWayProcessor);
    if (!allIsClear) {
      return;
    }

    createOrReuseHighlighterFor(info, colorsScheme, document, group, file, (MarkupModelEx)markup, null, ranges2markersCache, severityRegistrar);

    clearWhiteSpaceOptimizationFlag(document);
    assertMarkupConsistent(markup, project);
  }

  public static void setHighlightersToEditor(@NotNull Project project,
                                             @NotNull Document document,
                                             int startOffset,
                                             int endOffset,
                                             @NotNull Collection<HighlightInfo> highlights,
                                             @Nullable final EditorColorsScheme colorsScheme, // if null global scheme will be used
                                             int group) {
    TextRange range = new TextRange(startOffset, endOffset);
    ApplicationManager.getApplication().assertIsDispatchThread();

    PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    cleanFileLevelHighlights(project, group, psiFile);

    MarkupModel markup = DocumentMarkupModel.forDocument(document, project, true);
    assertMarkupConsistent(markup, project);

    setHighlightersInRange(project, document, range, colorsScheme, new ArrayList<HighlightInfo>(highlights), (MarkupModelEx)markup, group);
  }

  @Deprecated //for teamcity
  public static void setHighlightersToEditor(@NotNull Project project,
                                             @NotNull Document document,
                                             int startOffset,
                                             int endOffset,
                                             @NotNull Collection<HighlightInfo> highlights,
                                             int group) {
    setHighlightersToEditor(project, document, startOffset, endOffset, highlights, null, group);
  }

  // set highlights inside startOffset,endOffset but outside range
  static void setHighlightersOutsideRange(@NotNull final Project project,
                                          @NotNull final Document document,
                                          @NotNull final List<HighlightInfo> infos,
                                          @Nullable final EditorColorsScheme colorsScheme,
                                          // if null global scheme will be used
                                          final int startOffset,
                                          final int endOffset,
                                          @NotNull final ProperTextRange range,
                                          final int group) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    cleanFileLevelHighlights(project, group, psiFile);

    final MarkupModel markup = DocumentMarkupModel.forDocument(document, project, true);
    assertMarkupConsistent(markup, project);

    final SeverityRegistrar severityRegistrar = SeverityRegistrar.getInstance(project);
    final HighlightersRecycler infosToRemove = new HighlightersRecycler();
    ContainerUtil.quickSort(infos, BY_START_OFFSET_NODUPS);

    DaemonCodeAnalyzerImpl.processHighlightsOverlappingOutside(document, project, null, range.getStartOffset(), range.getEndOffset(), new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo info) {
        if (info.group == group) {
          RangeHighlighter highlighter = info.highlighter;
          int hiStart = highlighter.getStartOffset();
          int hiEnd = highlighter.getEndOffset();
          if (!info.fromInjection && hiEnd < document.getTextLength() && (hiEnd <= startOffset || hiStart>=endOffset)) return true; // injections are oblivious to restricting range
          boolean toRemove = !(hiEnd == document.getTextLength() && range.getEndOffset() == document.getTextLength()) &&
                             !range.containsRange(hiStart, hiEnd)
                             ;
          if (toRemove) {
            infosToRemove.recycleHighlighter(highlighter);
            info.highlighter = null;
          }
        }
        return true;
      }
    });

    final Map<TextRange, RangeMarker> ranges2markersCache = new THashMap<TextRange, RangeMarker>(10);
    final boolean[] changed = {false};
    RangeMarkerTree.sweep(new RangeMarkerTree.Generator<HighlightInfo>(){
      @Override
      public boolean generate(Processor<HighlightInfo> processor) {
        return ContainerUtil.process(infos, processor);
      }
    }, new SweepProcessor<HighlightInfo>() {
      @Override
      public boolean process(int offset, HighlightInfo info, boolean atStart, Collection<HighlightInfo> overlappingIntervals) {
        if (!atStart) return true;
        if (!info.fromInjection && info.getEndOffset() < document.getTextLength() && (info.getEndOffset() <= startOffset || info.getStartOffset()>=endOffset)) return true; // injections are oblivious to restricting range

        if (info.isFileLevelAnnotation && psiFile != null && psiFile.getViewProvider().isPhysical()) {
          addFileLevelHighlight(project, group, info, psiFile);
          changed[0] = true;
          return true;
        }
        if (isWarningCoveredByError(info, overlappingIntervals, severityRegistrar)) {
          return true;
        }
        if (info.getStartOffset() < range.getStartOffset() || info.getEndOffset() > range.getEndOffset()) {
          createOrReuseHighlighterFor(info, colorsScheme, document, group, psiFile, (MarkupModelEx)markup, infosToRemove,
                                        ranges2markersCache, severityRegistrar);
          changed[0] = true;
        }
        return true;
      }
    });
    for (RangeHighlighter highlighter : infosToRemove.forAllInGarbageBin()) {
      highlighter.dispose();
      changed[0] = true;
    }

    if (changed[0]) {
      clearWhiteSpaceOptimizationFlag(document);
    }
    assertMarkupConsistent(markup, project);
  }

  static void setHighlightersInRange(@NotNull final Project project,
                                     @NotNull final Document document,
                                     @NotNull final TextRange range,
                                     @Nullable final EditorColorsScheme colorsScheme, // if null global scheme will be used
                                     @NotNull final List<HighlightInfo> highlights,
                                     @NotNull final MarkupModelEx markup,
                                     final int group) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    final SeverityRegistrar severityRegistrar = SeverityRegistrar.getInstance(project);
    final HighlightersRecycler infosToRemove = new HighlightersRecycler();
    DaemonCodeAnalyzerImpl.processHighlights(document, project, null, range.getStartOffset(), range.getEndOffset(), new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo info) {
        if (info.group == group) {
          RangeHighlighter highlighter = info.highlighter;
          int hiStart = highlighter.getStartOffset();
          int hiEnd = highlighter.getEndOffset();
          boolean willBeRemoved = hiEnd == document.getTextLength() && range.getEndOffset() == document.getTextLength()
                                  /*|| range.intersectsStrict(hiStart, hiEnd)*/ || range.containsRange(hiStart, hiEnd) /*|| hiStart <= range.getStartOffset() && hiEnd >= range.getEndOffset()*/;
          if (willBeRemoved) {
            infosToRemove.recycleHighlighter(highlighter);
            info.highlighter = null;
          }
        }
        return true;
      }
    });

    ContainerUtil.quickSort(highlights, BY_START_OFFSET_NODUPS);
    final Map<TextRange, RangeMarker> ranges2markersCache = new THashMap<TextRange, RangeMarker>(10);
    final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    final boolean[] changed = {false};
    RangeMarkerTree.sweep(new RangeMarkerTree.Generator<HighlightInfo>(){
      @Override
      public boolean generate(final Processor<HighlightInfo> processor) {
        return ContainerUtil.process(highlights, processor);
      }
    }, new SweepProcessor<HighlightInfo>() {
      @Override
      public boolean process(int offset, HighlightInfo info, boolean atStart, Collection<HighlightInfo> overlappingIntervals) {
        if (!atStart) {
          return true;
        }
        if (info.isFileLevelAnnotation && psiFile != null && psiFile.getViewProvider().isPhysical()) {
          addFileLevelHighlight(project, group, info, psiFile);
          changed[0] = true;
          return true;
        }
        if (isWarningCoveredByError(info, overlappingIntervals, severityRegistrar)) {
          return true;
        }
        if (info.getStartOffset() >= range.getStartOffset() && info.getEndOffset() <= range.getEndOffset() && psiFile != null) {
          createOrReuseHighlighterFor(info, colorsScheme, document, group, psiFile, markup, infosToRemove, ranges2markersCache, severityRegistrar);
          changed[0] = true;
        }
        return true;
      }
    });
    for (RangeHighlighter highlighter : infosToRemove.forAllInGarbageBin()) {
      highlighter.dispose();
      changed[0] = true;
    }

    if (changed[0]) {
      clearWhiteSpaceOptimizationFlag(document);
    }
    assertMarkupConsistent(markup, project);
  }

  private static boolean isWarningCoveredByError(@NotNull HighlightInfo info,
                                                 @NotNull Collection<HighlightInfo> overlappingIntervals,
                                                 @NotNull SeverityRegistrar severityRegistrar) {
    if (!isSevere(info, severityRegistrar)) {
      for (HighlightInfo overlapping : overlappingIntervals) {
        if (isCovered(info, severityRegistrar, overlapping)) return true;
      }
    }
    return false;
  }

  private static boolean isCovered(@NotNull HighlightInfo warning, @NotNull SeverityRegistrar severityRegistrar, @NotNull HighlightInfo candidate) {
    if (!isCoveredByOffsets(warning, candidate)) return false;
    HighlightSeverity severity = candidate.getSeverity();
    if (severity == HighlightInfoType.SYMBOL_TYPE_SEVERITY) return false; // syntax should not interfere with warnings
    return isSevere(candidate, severityRegistrar);
  }

  private static boolean isSevere(@NotNull HighlightInfo info, @NotNull SeverityRegistrar severityRegistrar) {
    HighlightSeverity severity = info.getSeverity();
    return severityRegistrar.compare(HighlightSeverity.ERROR, severity) <= 0 || severity == HighlightInfoType.SYMBOL_TYPE_SEVERITY;
  }

  // return true if changed
  private static RangeHighlighter createOrReuseHighlighterFor(@NotNull final HighlightInfo info,
                                                              @Nullable final EditorColorsScheme colorsScheme, // if null global scheme will be used
                                                              @NotNull final Document document,
                                                              final int group,
                                                              @NotNull final PsiFile psiFile,
                                                              @NotNull MarkupModelEx markup,
                                                              @Nullable HighlightersRecycler infosToRemove,
                                                              @NotNull final Map<TextRange, RangeMarker> ranges2markersCache,
                                                              SeverityRegistrar severityRegistrar) {
    final int infoStartOffset = info.startOffset;
    int infoEndOffset = info.endOffset;

    if (infoEndOffset == infoStartOffset && !info.isAfterEndOfLine) {
      infoEndOffset++; //show something in case of empty highlightinfo
    }
    final int docLength = document.getTextLength();
    if (infoEndOffset > docLength) {
      infoEndOffset = docLength;
    }

    info.text = document.getText().substring(infoStartOffset, infoEndOffset);
    info.group = group;

    int layer = getLayer(info, severityRegistrar);
    RangeHighlighterEx highlighter = infosToRemove == null ? null : (RangeHighlighterEx)infosToRemove.pickupHighlighterFromGarbageBin(info.startOffset, info.endOffset, layer);

    final int finalInfoEndOffset = infoEndOffset;
    Consumer<RangeHighlighterEx> changeAttributes = new Consumer<RangeHighlighterEx>() {
      @Override
      public void consume(RangeHighlighterEx finalHighlighter) {
        finalHighlighter.setTextAttributes(info.getTextAttributes(psiFile, colorsScheme));

        info.highlighter = finalHighlighter;
        finalHighlighter.setAfterEndOfLine(info.isAfterEndOfLine);

        Color color = info.getErrorStripeMarkColor(psiFile, colorsScheme);
        finalHighlighter.setErrorStripeMarkColor(color);
        if (info != finalHighlighter.getErrorStripeTooltip()) {
          finalHighlighter.setErrorStripeTooltip(info);
        }
        GutterIconRenderer renderer = info.getGutterIconRenderer();
        finalHighlighter.setGutterIconRenderer(renderer);

        ranges2markersCache.put(new TextRange(infoStartOffset, finalInfoEndOffset), info.highlighter);
        if (info.quickFixActionRanges != null) {
          List<Pair<HighlightInfo.IntentionActionDescriptor, RangeMarker>> list =
            new ArrayList<Pair<HighlightInfo.IntentionActionDescriptor, RangeMarker>>(info.quickFixActionRanges.size());
          for (Pair<HighlightInfo.IntentionActionDescriptor, TextRange> pair : info.quickFixActionRanges) {
            TextRange textRange = pair.second;
            RangeMarker marker = getOrCreate(document, ranges2markersCache, textRange);
            list.add(Pair.create(pair.first, marker));
          }
          info.quickFixActionMarkers = new CopyOnWriteArrayList<Pair<HighlightInfo.IntentionActionDescriptor, RangeMarker>>(list);
        }
        TextRange fixRange = new TextRange(info.fixStartOffset, info.fixEndOffset);
        if (fixRange.equalsToRange(infoStartOffset, finalInfoEndOffset)) {
          info.fixMarker = null; // null means it the same as highlighter'
        }
        else {
          info.fixMarker = getOrCreate(document, ranges2markersCache, fixRange);
        }
      }
    };

    if (highlighter == null) {
      highlighter = markup.addRangeHighlighterAndChangeAttributes(infoStartOffset, infoEndOffset, layer, null,
                                                                  HighlighterTargetArea.EXACT_RANGE, false, changeAttributes);
    }
    else {
      markup.changeAttributesInBatch(highlighter, changeAttributes);
    }

    assert Comparing.equal(info.getTextAttributes(psiFile, colorsScheme), highlighter.getTextAttributes()) : "Info: " +
                                                                                               info.getTextAttributes(psiFile, colorsScheme) +
                                                                                               "; colorsSheme: " + (colorsScheme == null ? "[global]" : colorsScheme.getName()) +
                                                                                               "; highlighter:" +
                                                                                               highlighter.getTextAttributes();
    return highlighter;
  }

  private static int getLayer(@NotNull HighlightInfo info, @NotNull SeverityRegistrar severityRegistrar) {
    final HighlightSeverity severity = info.getSeverity();
    int layer;
    if (severity == HighlightSeverity.WARNING) {
      layer = HighlighterLayer.WARNING;
    }
    else if (severityRegistrar.compare(severity, HighlightSeverity.ERROR) >= 0) {
      layer = HighlighterLayer.ERROR;
    }
    else if (severity == HighlightInfoType.INJECTED_FRAGMENT_SEVERITY) {
      layer = HighlighterLayer.CARET_ROW-1;
    }
    else {
      layer = HighlighterLayer.ADDITIONAL_SYNTAX;
    }
    return layer;
  }

  private static RangeMarker getOrCreate(@NotNull Document document, @NotNull Map<TextRange, RangeMarker> ranges2markersCache, @NotNull TextRange textRange) {
    RangeMarker marker = ranges2markersCache.get(textRange);
    if (marker == null) {
      marker = document.createRangeMarker(textRange);
      ranges2markersCache.put(textRange, marker);
    }
    return marker;
  }

  private static void addFileLevelHighlight(@NotNull final Project project, final int group, @NotNull final HighlightInfo info, @NotNull final PsiFile psiFile) {
    VirtualFile vFile = psiFile.getViewProvider().getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    for (FileEditor fileEditor : manager.getEditors(vFile)) {
      if (fileEditor instanceof TextEditor) {
        FileLevelIntentionComponent component = new FileLevelIntentionComponent(info.description, info.severity, info.quickFixActionRanges,
                                                                                project, psiFile, ((TextEditor)fileEditor).getEditor());
        manager.addTopComponent(fileEditor, component);
        List<HighlightInfo> fileLevelInfos = fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS);
        if (fileLevelInfos == null) {
          fileLevelInfos = new ArrayList<HighlightInfo>();
          fileEditor.putUserData(FILE_LEVEL_HIGHLIGHTS, fileLevelInfos);
        }
        info.fileLevelComponent = component;
        info.group = group;
        fileLevelInfos.add(info);
      }
    }
  }

  static void setLineMarkersToEditor(@NotNull Project project,
                                     @NotNull Document document,
                                     int startOffset,
                                     int endOffset,
                                     @NotNull Collection<LineMarkerInfo> markers,
                                     int group) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    List<LineMarkerInfo> oldMarkers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project);
    List<LineMarkerInfo> array = new ArrayList<LineMarkerInfo>(oldMarkers == null ? markers.size() : oldMarkers.size());
    MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, true);
    HighlightersRecycler toReuse = new HighlightersRecycler();
    if (oldMarkers != null) {
      for (LineMarkerInfo info : oldMarkers) {
        RangeHighlighter highlighter = info.highlighter;
        boolean toRemove = !highlighter.isValid() ||
                           info.updatePass == group &&
                           startOffset <= highlighter.getStartOffset() &&
                           (highlighter.getEndOffset() < endOffset || highlighter.getEndOffset() == document.getTextLength());

        if (toRemove) {
          toReuse.recycleHighlighter(highlighter);
        }
        else {
          array.add(info);
        }
      }
    }

    for (LineMarkerInfo info : markers) {
      PsiElement element = info.getElement();
      if (element == null) {
        continue;
      }

      TextRange textRange = element.getTextRange();
      if (textRange == null) continue;
      TextRange elementRange = InjectedLanguageManager.getInstance(project).injectedToHost(element, textRange);
      if (startOffset > elementRange.getStartOffset() || elementRange.getEndOffset() > endOffset) {
        continue;
      }
      RangeHighlighter marker = toReuse.pickupHighlighterFromGarbageBin(info.startOffset, info.endOffset, HighlighterLayer.ADDITIONAL_SYNTAX);
      if (marker == null) {
        marker = markupModel.addRangeHighlighter(info.startOffset, info.endOffset, HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE);
      }
      LineMarkerInfo.LineMarkerGutterIconRenderer renderer = (LineMarkerInfo.LineMarkerGutterIconRenderer)info.createGutterRenderer();
      LineMarkerInfo.LineMarkerGutterIconRenderer oldRenderer = marker.getGutterIconRenderer() instanceof LineMarkerInfo.LineMarkerGutterIconRenderer ? (LineMarkerInfo.LineMarkerGutterIconRenderer)marker.getGutterIconRenderer() : null;
      if (oldRenderer == null || renderer == null || !renderer.equals(oldRenderer)) {
        marker.setGutterIconRenderer(renderer);
      }
      if (!Comparing.equal(marker.getLineSeparatorColor(), info.separatorColor)) {
        marker.setLineSeparatorColor(info.separatorColor);
      }
      if (!Comparing.equal(marker.getLineSeparatorPlacement(), info.separatorPlacement)) {
        marker.setLineSeparatorPlacement(info.separatorPlacement);
      }
      info.highlighter = marker;
      array.add(info);
    }

    for (RangeHighlighter highlighter : toReuse.forAllInGarbageBin()) {
      highlighter.dispose();
    }

    DaemonCodeAnalyzerImpl.setLineMarkers(document, array, project);
  }

  private static final Key<Boolean> TYPING_INSIDE_HIGHLIGHTER_OCCURRED = Key.create("TYPING_INSIDE_HIGHLIGHTER_OCCURRED");
  static boolean isWhitespaceOptimizationAllowed(@NotNull Document document) {
    return document.getUserData(TYPING_INSIDE_HIGHLIGHTER_OCCURRED) == null;
  }
  private static void disableWhiteSpaceOptimization(@NotNull Document document) {
    document.putUserData(TYPING_INSIDE_HIGHLIGHTER_OCCURRED, Boolean.TRUE);
  }
  private static void clearWhiteSpaceOptimizationFlag(@NotNull Document document) {
    document.putUserData(TYPING_INSIDE_HIGHLIGHTER_OCCURRED, null);
  }

  static void updateHighlightersByTyping(@NotNull Project project, @NotNull DocumentEvent e) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    final Document document = e.getDocument();
    if (document instanceof DocumentEx && ((DocumentEx)document).isInBulkUpdate()) return;

    final MarkupModel markup = DocumentMarkupModel.forDocument(document, project, true);
    assertMarkupConsistent(markup, project);

    final int start = e.getOffset() - 1;
    final int end = start + Math.max(e.getOldLength(), e.getNewLength());

    final List<HighlightInfo> toRemove = new ArrayList<HighlightInfo>();
    DaemonCodeAnalyzerImpl.processHighlights(document, project, null, start, end, new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo info) {
        RangeHighlighter highlighter = info.highlighter;
        boolean remove = false;
        if (info.needUpdateOnTyping()) {
          int highlighterStart = highlighter.getStartOffset();
          int highlighterEnd = highlighter.getEndOffset();
          if (info.isAfterEndOfLine) {
            if (highlighterStart < document.getTextLength()) {
              highlighterStart += 1;
            }
            if (highlighterEnd < document.getTextLength()) {
              highlighterEnd += 1;
            }
          }
          if (!highlighter.isValid() || start < highlighterEnd && highlighterStart <= end) {
            remove = true;
          }
        }
        if (remove) {
          toRemove.add(info);
        }
        return true;
      }
    });

    for (HighlightInfo info : toRemove) {
      if (!info.highlighter.isValid() || info.type.equals(HighlightInfoType.WRONG_REF)) {
        info.highlighter.dispose();
      }
    }
    
    assertMarkupConsistent(markup, project);

    if (!toRemove.isEmpty()) {
      disableWhiteSpaceOptimization(document);
    }
  }

  @NotNull
  @TestOnly
  static List<HighlightInfo> getFileLeveleHighlights(@NotNull Project project, @NotNull PsiFile file) {
    VirtualFile vFile = file.getViewProvider().getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    List<HighlightInfo> result = new ArrayList<HighlightInfo>();
    for (FileEditor fileEditor : manager.getEditors(vFile)) {
      final List<HighlightInfo> infos = fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS);
      if (infos == null) continue;
      for (HighlightInfo info : infos) {
          result.add(info);
      }
    }
    return result;
  }

  private static void assertMarkupConsistent(@NotNull final MarkupModel markup, @NotNull Project project) {
    if (!RedBlackTree.VERIFY) {
      return;
    }
    Document document = markup.getDocument();
    DaemonCodeAnalyzerImpl.processHighlights(document, project, null, 0, document.getTextLength(), new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo info) {
        assert ((MarkupModelEx)markup).containsHighlighter(info.highlighter);
        return true;
      }
    });
    RangeHighlighter[] allHighlighters = markup.getAllHighlighters();
    for (RangeHighlighter highlighter : allHighlighters) {
      if (!highlighter.isValid()) continue;
      Object tooltip = highlighter.getErrorStripeTooltip();
      if (!(tooltip instanceof HighlightInfo)) {
        continue;
      }
      final HighlightInfo info = (HighlightInfo)tooltip;
      boolean contains = !DaemonCodeAnalyzerImpl.processHighlights(document, project, null, info.getActualStartOffset(), info.getActualEndOffset(), new Processor<HighlightInfo>() {
        @Override
        public boolean process(HighlightInfo highlightInfo) {
          return UpdateHighlightersUtil.BY_START_OFFSET_NODUPS.compare(highlightInfo, info) != 0;
        }
      });
      assert contains: info;
    }
  }
}
