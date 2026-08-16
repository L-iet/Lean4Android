package org.lean4android.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LeanDimensions(
    val workspacePadding: Dp = 12.dp,
    val drawerWidth: Dp = 300.dp,
    val drawerPadding: Dp = 12.dp,
    val drawerItemSpacing: Dp = 8.dp,
    val compactProjectTreeHeight: Dp = 120.dp,
    val projectActionsMaxHeight: Dp = 320.dp,
    val screenPadding: Dp = 24.dp,
    val findFieldWidth: Dp = 240.dp,
    val messagesMaxHeight: Dp = 160.dp,
    val messagesCollapseButtonSize: Dp = 30.dp,
    val symbolButtonHeight: Dp = 34.dp,
    val symbolButtonMinWidth: Dp = 36.dp,
    val splitterThickness: Dp = 16.dp,
    val outputMinHeight: Dp = 100.dp,
    val outputMaxHeight: Dp = 200.dp,
    val compactSpacing: Dp = 2.dp,
    val smallSpacing: Dp = 6.dp,
    val standardSpacing: Dp = 8.dp,
    val sectionSpacing: Dp = 16.dp,
    val drawerElevation: Dp = 8.dp,
    val settingsRowPadding: Dp = 8.dp,
    val editorVerticalPadding: Dp = 12.dp,
    val editorGutterHorizontalPadding: Dp = 10.dp,
    val editorSourceHorizontalPadding: Dp = 12.dp,
    val projectTreeVerticalPadding: Dp = 4.dp,
    val projectTreeBaseIndent: Dp = 8.dp,
    val projectTreeDepthIndent: Dp = 16.dp,
    val projectTreeEndPadding: Dp = 12.dp,
    val projectTreeRowVerticalPadding: Dp = 7.dp,
    val projectRowHorizontalPadding: Dp = 12.dp,
    val projectRowVerticalPadding: Dp = 10.dp,
    val hoverBlockSpacing: Dp = 6.dp,
    val hoverCodePadding: Dp = 8.dp,
)

@Immutable
data class PaneStyle(
    val containerColor: Color,
    val contentColor: Color,
    val shape: Shape,
    val contentPadding: PaddingValues,
    val titleStyle: TextStyle,
    val bodyStyle: TextStyle,
    val collapsedContentPadding: PaddingValues = contentPadding,
)

@Immutable
data class SplitterStyle(
    val trackColor: Color,
    val handleColor: Color,
    val labelStyle: TextStyle,
    val handlePadding: Dp,
)

@Immutable
data class TabStyle(
    val activeContainerColor: Color,
    val inactiveContainerColor: Color,
    val shape: Shape,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val spacing: Dp,
)

@Immutable
data class SymbolRowStyle(
    val spacing: Dp,
    val horizontalContentPadding: Dp,
    val verticalContentPadding: Dp,
    val textStyle: TextStyle,
)

@Immutable
data class EditorStyle(
    val containerColor: Color,
    val contentColor: Color,
    val gutterColor: Color,
    val gutterContentColor: Color,
    val cursorColor: Color,
    val codeStyle: TextStyle,
    val shape: Shape,
)

@Immutable
data class ShellStyle(
    val drawerShape: Shape,
    val projectCardColor: Color,
    val projectCardContentColor: Color,
    val projectCardShape: Shape,
    val emptyEditorColor: Color,
    val emptyEditorContentColor: Color,
    val screenHeadingStyle: TextStyle,
    val sectionHeadingStyle: TextStyle,
    val supportingStyle: TextStyle,
)

@Immutable
data class ProjectTreeStyle(
    val containerColor: Color,
    val contentColor: Color,
    val shape: Shape,
)

@Immutable
data class HoverStyle(
    val linkColor: Color,
    val codeContainerColor: Color,
    val codeContentColor: Color,
    val largeHeadingStyle: TextStyle,
    val smallHeadingStyle: TextStyle,
    val bodyStyle: TextStyle,
    val codeStyle: TextStyle,
)

@Immutable
data class LeanComponentStyles(
    val editor: EditorStyle,
    val goals: PaneStyle,
    val output: PaneStyle,
    val messages: PaneStyle,
    val errorMessages: PaneStyle,
    val splitter: SplitterStyle,
    val tabs: TabStyle,
    val symbolRow: SymbolRowStyle,
    val shell: ShellStyle,
    val projectTree: ProjectTreeStyle,
    val hover: HoverStyle,
)

private val LocalLeanDimensions = staticCompositionLocalOf { LeanDimensions() }
private val LocalLeanComponentStyles = staticCompositionLocalOf<LeanComponentStyles> {
    error("Lean component styles are only available inside Lean4AndroidTheme")
}

object LeanTheme {
    val dimensions: LeanDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalLeanDimensions.current

    val components: LeanComponentStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalLeanComponentStyles.current
}

@Composable
fun Lean4AndroidTheme(
    darkTheme: Boolean,
    dimensions: LeanDimensions = LeanDimensions(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        val colors = MaterialTheme.colorScheme
        val typography = MaterialTheme.typography
        val shapes = MaterialTheme.shapes
        val codeBody = typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        val styles = LeanComponentStyles(
            editor = EditorStyle(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurface,
                gutterColor = colors.surface,
                gutterContentColor = colors.onSurfaceVariant,
                cursorColor = colors.primary,
                codeStyle = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = shapes.small,
            ),
            goals = PaneStyle(
                containerColor = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                shape = shapes.small,
                contentPadding = PaddingValues(10.dp),
                titleStyle = typography.titleSmall,
                bodyStyle = codeBody,
            ),
            output = PaneStyle(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                shape = shapes.medium,
                contentPadding = PaddingValues(12.dp),
                titleStyle = typography.titleSmall,
                bodyStyle = codeBody,
            ),
            messages = PaneStyle(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                shape = shapes.small,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                titleStyle = typography.titleSmall,
                bodyStyle = typography.bodySmall,
                collapsedContentPadding = PaddingValues(horizontal = 8.dp),
            ),
            errorMessages = PaneStyle(
                containerColor = colors.errorContainer,
                contentColor = colors.onErrorContainer,
                shape = shapes.small,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                titleStyle = typography.titleSmall,
                bodyStyle = typography.bodySmall,
                collapsedContentPadding = PaddingValues(horizontal = 8.dp),
            ),
            splitter = SplitterStyle(
                trackColor = colors.outlineVariant,
                handleColor = colors.surface,
                labelStyle = typography.labelSmall,
                handlePadding = 1.dp,
            ),
            tabs = TabStyle(
                activeContainerColor = colors.primaryContainer,
                inactiveContainerColor = colors.surfaceVariant,
                shape = shapes.small,
                horizontalPadding = 12.dp,
                verticalPadding = 8.dp,
                spacing = 4.dp,
            ),
            symbolRow = SymbolRowStyle(
                spacing = 2.dp,
                horizontalContentPadding = 7.dp,
                verticalContentPadding = 0.dp,
                textStyle = typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            ),
            shell = ShellStyle(
                drawerShape = shapes.extraSmall,
                projectCardColor = colors.surfaceVariant,
                projectCardContentColor = colors.onSurfaceVariant,
                projectCardShape = shapes.medium,
                emptyEditorColor = colors.surfaceVariant,
                emptyEditorContentColor = colors.onSurfaceVariant,
                screenHeadingStyle = typography.headlineSmall,
                sectionHeadingStyle = typography.titleSmall,
                supportingStyle = typography.bodySmall,
            ),
            projectTree = ProjectTreeStyle(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                shape = shapes.small,
            ),
            hover = HoverStyle(
                linkColor = colors.primary,
                codeContainerColor = colors.surface,
                codeContentColor = colors.onSurface,
                largeHeadingStyle = typography.titleMedium,
                smallHeadingStyle = typography.titleSmall,
                bodyStyle = typography.bodySmall,
                codeStyle = codeBody,
            ),
        )
        CompositionLocalProvider(
            LocalLeanDimensions provides dimensions,
            LocalLeanComponentStyles provides styles,
            content = content,
        )
    }
}
