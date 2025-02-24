// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.spellchecker.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionProfileWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.spellchecker.inspections.SpellCheckingInspection;
import com.intellij.ui.SimpleEditorCustomization;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Allows to enforce editors to use/don't use spell checking ignoring user-defined spelling inspection settings.
 * <p/>
 * Thread-safe.
 *
 * @author Denis Zhdanov
 */
public class SpellCheckingEditorCustomization extends SimpleEditorCustomization {

  /**
   * @deprecated use {@link SpellCheckingEditorCustomizationProvider#getDisabledCustomization()} instead
   */
  @Deprecated
  @ApiStatus.ScheduledForRemoval(inVersion = "2021.3")
  public static final SpellCheckingEditorCustomization DISABLED =
    (SpellCheckingEditorCustomization)SpellCheckingEditorCustomizationProvider.getInstance().getDisabledCustomization();

  private static final Map<String, LocalInspectionToolWrapper> SPELL_CHECK_TOOLS = new HashMap<>();
  private static final boolean READY = init();

  @NotNull
  public static SpellCheckingEditorCustomization getInstance(boolean enabled) {
    return (SpellCheckingEditorCustomization)SpellCheckingEditorCustomizationProvider.getInstance().getCustomization(enabled);
  }

  SpellCheckingEditorCustomization(boolean enabled) {
    super(enabled);
  }

  @SuppressWarnings({"unchecked"})
  private static boolean init() {
    // It's assumed that default spell checking inspection settings are just fine for processing all types of data.
    // Please perform corresponding settings tuning if that assumption is broken at future.

    Class<LocalInspectionTool>[] inspectionClasses = (Class<LocalInspectionTool>[])new Class<?>[]{SpellCheckingInspection.class};
    for (Class<LocalInspectionTool> inspectionClass : inspectionClasses) {
      try {
        LocalInspectionTool tool = inspectionClass.newInstance();
        SPELL_CHECK_TOOLS.put(tool.getShortName(), new LocalInspectionToolWrapper(tool));
      }
      catch (Throwable e) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void customize(@NotNull EditorEx editor) {
    boolean apply = isEnabled();

    if (!READY) {
      return;
    }

    Project project = editor.getProject();
    if (project == null) {
      return;
    }

    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) {
      return;
    }

    Function<? super InspectionProfile, ? extends InspectionProfileWrapper> strategy = InspectionProfileWrapper.getCustomInspectionProfileWrapper(file);
    if (strategy == null) {
      strategy = new MyInspectionProfileStrategy();
      InspectionProfileWrapper.setCustomInspectionProfileWrapperTemporarily(file, strategy);
    }

    if (!(strategy instanceof MyInspectionProfileStrategy)) {
      return;
    }

    ((MyInspectionProfileStrategy)strategy).setUseSpellCheck(apply);

    if (apply) {
      editor.putUserData(IntentionManager.SHOW_INTENTION_OPTIONS_KEY, false);
    }

    // Update representation.
    DaemonCodeAnalyzer analyzer = DaemonCodeAnalyzer.getInstance(project);
    if (analyzer != null) {
      analyzer.restart(file);
    }
  }

  public static boolean isSpellCheckingDisabled(@NotNull PsiFile file) {
    Function<? super InspectionProfile, ? extends InspectionProfileWrapper>
      strategy = InspectionProfileWrapper.getCustomInspectionProfileWrapper(file);
    return strategy instanceof MyInspectionProfileStrategy && !((MyInspectionProfileStrategy)strategy).myUseSpellCheck;
  }

  public static Set<String> getSpellCheckingToolNames() {
    return Collections.unmodifiableSet(SPELL_CHECK_TOOLS.keySet());
  }

  private static class MyInspectionProfileStrategy implements Function<InspectionProfile, InspectionProfileWrapper> {
    private final Map<InspectionProfile, MyInspectionProfileWrapper> myWrappers = ContainerUtil.createWeakMap();
    private boolean myUseSpellCheck;

    @NotNull
    @Override
    public InspectionProfileWrapper apply(@NotNull InspectionProfile inspectionProfile) {
      if (!READY) {
        return new InspectionProfileWrapper((InspectionProfileImpl)inspectionProfile);
      }
      MyInspectionProfileWrapper wrapper = myWrappers.get(inspectionProfile);
      if (wrapper == null) {
        myWrappers.put(inspectionProfile, wrapper = new MyInspectionProfileWrapper((InspectionProfileImpl)inspectionProfile));
      }
      wrapper.setUseSpellCheck(myUseSpellCheck);
      return wrapper;
    }

    public void setUseSpellCheck(boolean useSpellCheck) {
      myUseSpellCheck = useSpellCheck;
    }
  }

  private static class MyInspectionProfileWrapper extends InspectionProfileWrapper {
    private boolean myUseSpellCheck;

    MyInspectionProfileWrapper(@NotNull InspectionProfileImpl inspectionProfile) {
      super(inspectionProfile);
    }

    @Override
    public boolean isToolEnabled(HighlightDisplayKey key, PsiElement element) {
      return SPELL_CHECK_TOOLS.containsKey(key.toString()) ? myUseSpellCheck : super.isToolEnabled(key, element);
    }

    public void setUseSpellCheck(boolean useSpellCheck) {
      myUseSpellCheck = useSpellCheck;
    }
  }
}
