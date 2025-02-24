// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.editor.tables.ui.presentation

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hints.fireUpdateEvent
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.executeCommand
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.siblings
import com.intellij.refactoring.suggested.startOffset
import com.intellij.ui.LightweightHint
import com.intellij.util.ui.GraphicsUtil
import org.intellij.plugins.markdown.editor.tables.TableModificationUtils.selectColumn
import org.intellij.plugins.markdown.editor.tables.actions.TableActionKeys
import org.intellij.plugins.markdown.editor.tables.ui.presentation.GraphicsUtils.clearOvalOverEditor
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTableImpl
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTableRowImpl
import org.intellij.plugins.markdown.util.hasType
import java.awt.*
import java.awt.event.MouseEvent
import java.lang.ref.WeakReference
import javax.swing.SwingUtilities

internal class HorizontalBarPresentation(private val editor: Editor, private val table: MarkdownTableImpl): BasePresentation() {
  private val fontMetrics
    get() = obtainFontMetrics(editor)

  private var lastSelectedIndex: Int? = null

  private fun obtainBarsModel(cached: Boolean = false): List<Rectangle> {
    if (!table.isValid || editor.isDisposed) {
      return emptyList()
    }
    return when {
      cached -> CachedValuesManager.getCachedValue(table) {
        CachedValueProvider.Result.create(buildBarsModel(), PsiModificationTracker.MODIFICATION_COUNT)
      }
      else -> buildBarsModel()
    }
  }

  private val barsModel
    get() = obtainBarsModel()

  override val width
    get() = calculateRowWidth()

  override val height
    get() = barHeight

  override fun paint(graphics: Graphics2D, attributes: TextAttributes) {
    if (!table.isValid || editor.isDisposed) {
      return
    }
    GraphicsUtil.setupAntialiasing(graphics)
    GraphicsUtil.setupRoundedBorderAntialiasing(graphics)
    paintBars(graphics)
  }

  override fun toString() = "HorizontalBarPresentation"

  override fun mouseClicked(event: MouseEvent, translated: Point) {
    when {
      SwingUtilities.isLeftMouseButton(event) && event.clickCount.mod(2) == 0 -> handleMouseLeftDoubleClick(event, translated)
      SwingUtilities.isLeftMouseButton(event) -> handleMouseLeftClick(event, translated)
    }
  }

  override fun mouseMoved(event: MouseEvent, translated: Point) {
    val index = determineColumnIndex(translated)
    updateSelectedIndexIfNeeded(index)
  }

  override fun mouseExited() {
    updateSelectedIndexIfNeeded(null)
  }

  // Needed for cached barsModel to work
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as HorizontalBarPresentation
    if (editor != other.editor) return false
    if (table != other.table) return false
    return true
  }

  override fun hashCode(): Int {
    var result = editor.hashCode()
    result = 31 * result + table.hashCode()
    return result
  }

  private fun obtainCommittedDocument(): Document? {
    val file = table.containingFile
    return file?.let { PsiDocumentManager.getInstance(table.project).getLastCommittedDocument(it) }
  }

  private fun calculateRowWidth(): Int {
    if (!table.isValid || editor.isDisposed) {
      return 0
    }
    val header = table.headerRow ?: return 0
    val document = obtainCommittedDocument() ?: return 0
    return fontMetrics.stringWidth(document.getText(header.textRange))
  }

  private fun updateSelectedIndexIfNeeded(index: Int?) {
    if (lastSelectedIndex != index) {
      lastSelectedIndex = index
      // Force full re-render by lying about previous dimensions
      fireUpdateEvent(Dimension(0, 0))
    }
  }

  private fun buildBarsModel(): List<Rectangle> {
    val header = requireNotNull(table.headerRow)
    val text = obtainCommittedDocument()?.text ?: return emptyList()
    val positions = calculatePositions(header, text)
    val sectors = buildSectors(positions)
    return sectors.map { (offset, width) -> Rectangle(offset - barHeight / 2, 0, width + barHeight, barHeight) }
  }

  private fun calculatePositions(header: MarkdownTableRowImpl, text: CharSequence): List<Int> {
    val separators = header.firstChild.siblings(forward = true).filter { it.hasType(MarkdownTokenTypes.TABLE_SEPARATOR) }.toList()
    require(barHeight % 2 == 0) { "barHeight value should be even" }
    val result = ArrayList<Int>(separators.size)
    for ((index, separator) in separators.withIndex()) {
      val offset = separator.startOffset
      var position = editor.visualPositionToXY(editor.offsetToVisualPosition(offset)).x
      val symbolWidth = fontMetrics.charWidth(text[offset])
      position += when {
        index == separators.lastIndex -> symbolWidth / 2
        index != 0 -> symbolWidth / 2
        // index == 0
        else -> leftPadding + symbolWidth / 2
      }
      result.add(position)
    }
    return result
  }

  private fun buildSectors(positions: List<Int>): List<Pair<Int, Int>> {
    return positions.windowed(2).map { (left, right) -> left to (right - left) }.toList()
  }

  private fun determineColumnIndex(point: Point): Int? {
    return barsModel.indexOfFirst { it.contains(point) }.takeUnless { it < 0 }
  }

  private fun calculateToolbarPosition(componentHeight: Int, columnIndex: Int): Point {
    val position = editor.offsetToXY(table.startOffset)
    // Position hint relative to the editor
    val editorParent = editor.contentComponent.topLevelAncestor.locationOnScreen
    val editorPosition = editor.contentComponent.locationOnScreen
    position.translate(editorPosition.x - editorParent.x, editorPosition.y - editorParent.y)
    // Translate hint right above the bar
    position.translate(leftPadding, -editor.lineHeight)
    position.translate(0, -componentHeight)
    val rect = barsModel[columnIndex]
    val bottomPadding = 2
    position.translate(rect.x, -rect.y - barHeight * 2 - bottomPadding)
    //// Center hint against the bar
    //position.translate(rect.width / 2 - barHeight * 2, 0)
    //position.translate(-(componentWidth / 2), 0)
    return position
  }

  private fun showToolbar(columnIndex: Int) {
    val actionToolbar = TableActionKeys.createActionToolbar(
      columnActionGroup,
      isHorizontal = true,
      editor,
      createDataProvider(table, columnIndex)
    )
    val hint = LightweightHint(actionToolbar.component)
    hint.setForceShowAsPopup(true)
    val targetPoint = calculateToolbarPosition(hint.component.preferredSize.height, columnIndex)
    val hintManager = HintManagerImpl.getInstanceImpl()
    hintManager.hideAllHints()
    val flags = HintManager.HIDE_BY_ANY_KEY or HintManager.HIDE_BY_SCROLLING or HintManager.HIDE_BY_CARET_MOVE or HintManager.HIDE_BY_TEXT_CHANGE
    hintManager.showEditorHint(hint, editor, targetPoint, flags, 0, false)
  }

  private fun handleMouseLeftDoubleClick(event: MouseEvent, translated: Point) {
    val columnIndex = determineColumnIndex(translated) ?: return
    invokeLater {
      executeCommand {
        table.selectColumn(editor, columnIndex, withHeader = true, withSeparator = false)
      }
    }
  }

  private fun handleMouseLeftClick(event: MouseEvent, translated: Point) {
    val columnIndex = determineColumnIndex(translated) ?: return
    showToolbar(columnIndex)
  }

  private fun actuallyPaintBars(graphics: Graphics2D, rect: Rectangle, hover: Boolean, accent: Boolean) {
    val paintCount = when {
      accent -> 2
      else -> 1
    }
    repeat(paintCount) {
      graphics.color = when {
        hover -> TableInlayProperties.barHoverColor
        else -> TableInlayProperties.barColor
      }
      graphics.fillRoundRect(rect.x, 0, rect.width, barHeight, barHeight, barHeight)
      graphics.clearOvalOverEditor(rect.x, 0, barHeight, barHeight)
      graphics.clearOvalOverEditor(rect.x + rect.width - barHeight, 0, barHeight, barHeight)
    }
  }

  private fun paintBars(graphics: Graphics2D) {
    val currentBarsModel = barsModel
    // First pass: paint each bar without circles
    for ((index, rect) in currentBarsModel.withIndex()) {
      val mouseIsOver = lastSelectedIndex == index
      actuallyPaintBars(graphics, rect, hover = mouseIsOver, accent = false)
    }
    // Second pass: paint each circle to fill up gaps
    repeat(2) {
      paintCircles(currentBarsModel) { x, _, _ ->
        graphics.color = TableInlayProperties.barColor
        graphics.fillOval(x, 0, barHeight, barHeight)
      }
    }
  }

  private fun paintCircles(rects: List<Rectangle>, width: Int = barHeight, block: (Int, Rectangle, Int) -> Unit) {
    if (rects.isNotEmpty()) {
      for ((index, rect) in rects.withIndex()) {
        block(rect.x, rect, index)
      }
      rects.last().let { block(it.x + it.width - width, it, -1) }
    }
  }

  companion object {
    private val columnActionGroup
      get() = ActionManager.getInstance().getAction("Markdown.TableColumnActions") as ActionGroup

    // Should be even
    const val barHeight = TableInlayProperties.barSize
    const val leftPadding = VerticalBarPresentation.barWidth + TableInlayProperties.leftRightPadding * 2

    private fun wrapPresentation(factory: PresentationFactory, editor: Editor, presentation: InlayPresentation): InlayPresentation {
      return factory.inset(
        PresentationWithCustomCursor(editor, presentation),
        top = TableInlayProperties.topDownPadding,
        down = TableInlayProperties.topDownPadding
      )
    }

    fun create(factory: PresentationFactory, editor: Editor, table: MarkdownTableImpl): InlayPresentation {
      return wrapPresentation(factory, editor, HorizontalBarPresentation(editor, table))
    }

    private fun obtainFontMetrics(editor: Editor): FontMetrics {
      val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
      return editor.contentComponent.getFontMetrics(font)
    }

    private fun createDataProvider(table: MarkdownTableImpl, columnIndex: Int): DataProvider {
      val tableReference = WeakReference(table)
      return DataProvider {
        when {
          TableActionKeys.COLUMN_INDEX.`is`(it) -> columnIndex
          TableActionKeys.ELEMENT.`is`(it) -> tableReference
          else -> null
        }
      }
    }
  }
}
