# UI style and theme architecture

Status: semantic theme/style baseline implemented and API-33 accepted; incremental component extraction remains available during M6

Milestone: M5 foreground architecture prerequisite, retained as an M6 UI constraint

Last updated: 2026-08-19

## New contributor quick start

The implemented design system is
[`LeanTheme.kt`](../../app/src/main/java/org/lean4android/app/ui/theme/LeanTheme.kt).
It contains `Lean4AndroidTheme`, semantic dimensions, immutable editor/pane/shell/
tree/hover styles, and `LeanTheme` composition-local access. Most composables still
live in `MainActivity.kt`; this is intentional incremental migration, not evidence
that styles should move back into screen logic.

Before changing UI code, complete the setup in [README.md](../../README.md). With a
trusted audited Android1 distribution already present, the UI requires JDK 17,
Python 3, the Android SDK (platform 36 and Build Tools 35.0.0), and no native Lean
rebuild. Run:

```shell
make doctor
make ui
```

For a focused theme test plus Kotlin compilation:

```shell
scripts/run-ui-gradle.sh \
  :app:testDebugUnitTest \
  --tests org.lean4android.app.ui.theme.LeanThemeTest \
  :app:compileDebugKotlin
```

Build the installable APK with `make apk`. Recorded warm UI validation is commonly
2–5 minutes; the first cold theme build on `/mnt/d` took 56m53s because it read
about 4.6 GB and wrote about 2.2 GB during staging. Quiet staging is not proof of a
hang. Full setup and recovery are in [MVP_AND_REBUILDING.md](../../MVP_AND_REBUILDING.md).

## 1. Problem and objective

Lean4Android's native Compose UI grew incrementally through M4.6 with visual decisions spread through `MainActivity.kt` and `HoverMarkdown.kt`. The M5 migration introduced a typed semantic styling layer and moved the main editor, pane, shell, project-tree, and hover contracts into it. Some one-off Material typography calls and structural layout remain near their composables where they do not represent reusable design decisions.

The objective remains one discoverable, typed styling mechanism so later M6 work does not reintroduce unrelated literal sets. M5.0 popup/completion and M5.3 font preferences now consume the shared root/theme behavior rather than defining a competing styling system.

This is architectural, not a visual redesign. The implemented migration preserved the accepted light/dark appearance and physical M4/M5 layouts. Behavioral state, content, accessibility descriptions, process actions, project/LSP logic, orientation, pane fractions, and IME policy remain outside style files.

## 2. What belongs in the styling layer

The styling layer should own visual values and semantic visual state:

- app light/dark `ColorScheme`, `Typography`, and `Shapes`;
- spacing, inset, corner, elevation, border, icon/control-size, and common touch-target tokens;
- semantic editor colors such as gutter, editor surface/content/cursor, active/inactive tab, diagnostics, Output, Goals, Messages normal/error, popup, and drawer surfaces;
- semantic text roles such as editor code, output code, panel heading, panel body, supporting text, and Markdown code;
- stable component measurements such as drawer width, tree indent/row padding, symbol-button height/minimum width, splitter thickness/handle, Messages maximum height, Output default bounds, and popup bounds;
- component style objects that group the values a meaningful reusable component consumes; and
- explicit visual variants such as active/inactive, normal/error, docked/popup, or compact/comfortable when those variants are part of the design system.

Not every numeric value is styling. The following stay with layout or behavior code:

- weights and pane fractions derived from current user state;
- orientation/window/IME decisions and clamping algorithms;
- scroll, focus, selection, pointer, keyboard, and accessibility behavior;
- conditional presence of panels and controls;
- animation or timing values that encode interaction semantics rather than appearance;
- document, project, run, LSP, and recovery state; and
- dynamic values derived from content, such as tree depth. Styling may provide the per-level indent token, while code still computes `depth * indent`.

The public `Modifier` parameter of a composable remains caller-owned. A component applies its internal style without replacing or unexpectedly reordering caller constraints, input, semantics, or test tags.

## 3. Current implementation evidence

`MainActivity` installs `Lean4AndroidTheme`, passing the persisted dark-theme,
editor-font, and interface-scale choices. `LeanTheme.kt` owns the 300 dp drawer,
120/320 dp tree bounds, page/editor/gutter spacing, Messages maximum, symbol-control
sizes, splitter thickness, Output bounds, semantic Material colors, monospaced code
roles, and grouped styles for editor, panes, tabs, symbol row, shell, tree, and hover.

`HoverMarkdown.kt` consumes `LeanTheme.components.hover` and shared dimensions.
`EditorPaneLayout.kt` continues to contain tested pane placement, fraction clamping,
IME visibility, diagnostic severity, and compact-layout policy. This is the intended
boundary: the design system supplies visual tokens; pure helpers own behavior.

## 4. Alternatives considered

### A. Keep inline Compose styling

This has the least immediate work and keeps each rendered value close to its element. It works for small prototypes, but the current file size and repeated concepts already exceed that boundary. It does not provide a single place to tune an editor surface or audit light/dark contrast. Rejected as the long-term structure.

### B. Material 3 theme only

Move light/dark schemes, typography, and shapes into `ui/theme/Theme.kt`, then continue using `MaterialTheme` and inline dimensions. This follows standard Compose practice and is a necessary foundation. Material roles, however, do not describe IDE-specific measurements and semantics such as splitter handles, gutter colors, tab variants, symbol rows, or pane bounds. Selected as the foundation, but insufficient alone.

### C. Flat Kotlin constants

Create objects such as `Dimensions` and `EditorColors` containing `Dp` and `Color` constants. This is simple and compile-time safe, but raw colors cannot adapt correctly outside a composition without duplicating light/dark objects, and a large flat namespace becomes a different kind of unstructured file. Constants also fail to show which values form a component contract. Useful only for immutable primitive scales inside a layered system.

### D. Kotlin design tokens plus component style objects

Define small immutable token/style data classes and provide the active set through `CompositionLocal`s installed by `Lean4AndroidTheme`. Components read semantic values such as `LeanTheme.editor`, `LeanTheme.panes`, and `LeanTheme.dimensions`; meaningful components can accept a style override for previews or exceptional call sites. Values remain typed as `Dp`, `Color`, `TextStyle`, `Shape`, and `Dp` elevation.

This fits Compose's composition and recomposition model, keeps light/dark and future font-size preferences reactive, supports preview/test injection, and makes component contracts discoverable. The risk is over-modeling: a data class for every `Row` would create ceremony and obscure layout. Selected, with a rule to style meaningful product components rather than every Compose node.

### E. Modifier preset functions

Extension functions such as `Modifier.editorSurface()` or `Modifier.panelPadding()` can reduce repeated chains. They work well for focused decoration, especially when ordering is part of the contract. Used indiscriminately, they hide size constraints, semantics, or clickable ordering and resemble undocumented CSS classes. Selected only as a narrow implementation tool inside reusable components or for genuinely repeated, order-sensitive decoration; not as the primary style API.

### F. CSS-like class names or utility classes

A registry could map strings or enums like `panel`, `p-3`, or `text-code` to modifiers. This may feel familiar to web developers and can make dense markup compact. Compose is not a DOM/CSS cascade: modifier ordering affects measurement, drawing, input, and semantics, while inherited content values use composition. A class/cascade engine would either be incomplete or reproduce Compose with weaker type safety and harder navigation. String classes add runtime errors; enum utilities still scatter styling decisions across long call-site lists. Rejected at this stage.

### G. External stylesheet or configuration language

JSON, TOML, YAML, XML, or a custom stylesheet could define colors, dimensions, selectors, and variants. It could eventually support user-created themes without recompilation. It also requires a schema, parser, units, inheritance/cascade rules, validation, migrations, error fallback, resource/font resolution, security limits, and a translation layer into Compose. Selectors cannot safely control modifier ordering or adaptive behavior. Runtime configuration would enlarge the testing matrix and could make an invalid theme harm contrast, touch targets, or reachability. Rejected for production styling now. A future bounded theme import may expose only reviewed semantic color/typography tokens, never arbitrary layout or behavior.

### H. Android XML resources and resource qualifiers

Dimensions, colors, and text appearances could live in `res/values`, with night, size, or orientation qualifiers. This is mature for Views and useful for localization/resources. The app is Compose-native; converting every token through Android resources makes previews and typed grouped styles less direct, splits one design system across XML and Kotlin, and encourages orientation-based visual logic outside the existing tested Compose policies. Use Android resources where the platform requires them or for localized strings/assets, but not as the primary Compose styling system.

### I. Separate UI/design-system module or third-party library

A dedicated Gradle module gives strong dependency boundaries and reuse across multiple apps. A third-party design-system library may add ready-made tokens or responsive primitives. Lean4Android currently has one app and a modest visual component set; another module/API or dependency would add build and maintenance cost before reuse is demonstrated. Start with a focused `org.lean4android.app.ui.theme` and `ui.components` package inside `app`. Extract a module only when a second consumer or enforceable compile boundary exists.

### J. Generated tokens from a design tool

Design-token JSON transformed at build time can synchronize engineering with Figma-like tooling. There is no external design source of truth today, and generation would introduce tooling and provenance without reducing current complexity. The selected Kotlin data model can become a generation target later if that workflow appears. Deferred.

### K. Full composable extraction without a style model

Moving each panel to its own Kotlin file would make `MainActivity.kt` smaller and is independently worthwhile. It does not by itself centralize repeated colors, type, spacing, or dimensions; values would merely be distributed among more files. Selected as part of migration, paired with tokens and component styles rather than treated as the solution alone.

## 5. Decision

Use a Compose-native, layered Kotlin design system:

1. `Lean4AndroidTheme` owns Material 3 `ColorScheme`, `Typography`, and `Shapes` and installs app semantic tokens with composition locals.
2. Primitive scales define a small reviewed vocabulary for spacing, control sizes, elevations, and radii. They are implementation building blocks, not a utility-class API used indiscriminately at call sites.
3. Semantic tokens describe purpose rather than pigment or magnitude: editor gutter content, panel surface, error panel content, code text, splitter thickness, drawer width, and similar roles.
4. Component style data classes group the visual contract for meaningful components such as `EditorSurface`, `FileTabStrip`, `ProjectDrawer`, `PaneSplitter`, `MessagesPanel`, `GoalsPanel`, `OutputPanel`, `SymbolRow`, `HoverContent`, and future completion/output popups.
5. Reusable composables move to focused `ui/components` files. Screen/state coordination remains in screen/activity code; pure adaptive rules remain in tested policy files such as `EditorPaneLayout.kt`.
6. Call sites use semantic component defaults, with explicit typed overrides only where a real variant exists. No strings, selectors, cascade, runtime stylesheet parser, or arbitrary user-controlled dimensions are introduced.

The current source layout is intentionally compact:

```text
app/src/main/java/org/lean4android/app/ui/
└── theme/
    └── LeanTheme.kt          root theme, dimensions, semantic component styles
```

The visible component functions remain mostly in `MainActivity.kt`, and Hover
rendering remains in `HoverMarkdown.kt`; both consume the theme. Split
`LeanTheme.kt` into color/type/dimension/style files or extract `ui/components`
only when the resulting ownership is clearer. The semantic API matters more than
reproducing a speculative directory tree mechanically.

## 6. Implemented API shape

The implementation uses immutable style records grouped by `LeanComponentStyles`:

```kotlin
@Immutable
data class PaneStyle(
    val containerColor: Color,
    val contentColor: Color,
    val shape: Shape,
    val contentPadding: PaddingValues,
    val titleStyle: TextStyle,
    val bodyStyle: TextStyle,
)

@Immutable
data class EditorStyle(
    val containerColor: Color,
    val contentColor: Color,
    val gutterColor: Color,
    val gutterContentColor: Color,
    val cursorColor: Color,
    val codeStyle: TextStyle,
    val contentPadding: PaddingValues,
)

object LeanTheme {
    val dimensions: LeanDimensions
        @Composable @ReadOnlyComposable get() = LocalLeanDimensions.current
    val components: LeanComponentStyles
        @Composable @ReadOnlyComposable get() = LocalLeanComponentStyles.current
}
```

Styles containing theme-derived colors or typography are built inside
`Lean4AndroidTheme`. Components read values such as
`LeanTheme.components.output`, `LeanTheme.components.editor`, and
`LeanTheme.dimensions`; screen code does not pass every color and padding
individually. A future extracted component may accept a typed style override when
a real preview/test/product variant requires it.

Material components should continue to receive standard Material colors and typography when the semantic role matches. The app layer supplements Material rather than wrapping every Material primitive. Content color must be set with `Surface`/`CompositionLocalProvider` where descendants should inherit it; foreground, background, and text color should not become three contradictory values.

## 7. Node and component coverage rule

The request to make styling discoverable for each element/node should be satisfied at the meaningful component boundary, not by allocating a unique style class to every structural `Row`, `Column`, or `Box`.

For every extracted product component, its style contract must account for the applicable categories:

- width/height or min/max constraints;
- outer/internal spacing;
- container/background and content/foreground colors;
- explicit text color only when it intentionally differs from inherited content;
- text role and font family;
- shape, border, elevation, icon tint, and control size;
- visual state variants; and
- adaptive values that are visual tokens rather than behavioral calculations.

A category may be intentionally absent. For example, a screen-owned flexible editor width should remain a caller constraint instead of a fake `width = Dp.Unspecified` style field. This keeps style objects honest and avoids coupling visual configuration to the Compose measurement protocol.

## 8. Theme and preference behavior

Initially preserve the existing explicit light/dark preference using reviewed light and dark token sets. System/dynamic color can be evaluated later; enabling it now would change the accepted visual baseline and complicate screenshot/contrast validation.

M5.3 interface-font and editor-font preferences should rebuild the semantic typography/style set at the root. Components then react through composition instead of reading preferences independently. Font ranges remain bounded, editor/gutter metrics share one code typography source, and accessibility font scaling remains in effect. Symbol-row customization changes content, not style tokens.

Future theme customization, if requested, should begin with a versioned, bounded palette mapped to semantic color roles. Layout dimensions, touch targets, semantics, modifier ordering, and component presence remain application controlled. Imported themes must pass contrast and schema checks and fall back atomically to a built-in theme.

## 9. Implemented migration and remaining sequence

Completed:

1. `Lean4AndroidTheme` owns the Material root and reactive dark/interface/editor-font
   preferences.
2. Typed semantic dimensions and grouped component styles use the accepted values.
3. Goals, Output, Messages, splitters, tabs, symbol row, editor chrome, shell,
   drawer/tree, settings surfaces, and Hover consume the shared theme.
4. `LeanThemeTest` covers dimension invariants; focused/full Gradle validation and
   API-33 physical acceptance preserved the visible editor and no-orphan baseline.
5. M5.0 popup/completion and M5.3 font preferences were implemented without a
   second theme mechanism.

Remaining incremental work:

1. Extract meaningful components from `MainActivity.kt` only when this reduces
   ownership complexity without producing a giant state/event parameter list.
2. Replace newly discovered unexplained reusable visual literals; leave genuinely
   local structural values local and comment surprising exceptions.
3. Add screenshot/Compose UI coverage where it is stable enough to catch contrast,
   bounds, semantics, and touch-target regressions.

Do not perform a single massive file move. Each increment should compile, keep diffs reviewable, and retain current behavior. Component extraction and visual-token substitution can be separate commits when that reduces risk.

## 10. Validation and acceptance

Host validation should cover:

- light/dark semantic token selection and stable preference restoration;
- component-style construction and dimension invariants;
- editor/gutter typography alignment across every supported font preference;
- semantic normal/error and active/inactive variants;
- unchanged adaptive-policy tests in `EditorPaneLayoutTest`; and
- Compose UI tests for content descriptions, touch/keyboard actions, and key component bounds where reliable.

Physical validation for a material style change should compare the accepted M4/M5
surfaces in light and dark themes across tablet portrait/landscape and compact phone
sizes. Exercise drawer/tree reachability, tabs, editor/gutter alignment, symbol row,
Messages normal/error, Goals right/bottom, Docked/Popup Output, completion, splitter
drag/collapse, docked/floating IME, settings/font scale, Activity recreation, and a
real offline Run with no orphan child. TalkBack/touch targets must not regress.

Exit criteria:

- root theme setup and semantic visual tokens live outside activity/screen logic;
- meaningful product components consume typed component styles instead of scattered visual literals;
- adaptive behavior and accessibility remain explicit and tested rather than hidden in styles;
- light/dark appearance, layouts, editor behavior, and process lifecycle retain the M4/M5 acceptance baseline;
- later popup, completion, and font variants use the existing styling mechanism; and
- the project has no external stylesheet parser, CSS cascade, arbitrary class-string system, or unnecessary UI dependency.

## 11. Revisit triggers

Reconsider a separate design-system module when a second app/module needs the components or compile-time dependency enforcement becomes valuable. Reconsider generated design tokens when an external design tool becomes the maintained source of truth. Reconsider bounded external theme files only when user-authored themes become a product requirement and after defining schema migration, contrast, failure fallback, and security rules.

Until one of those triggers occurs, Kotlin/Compose is both the implementation language and the safest style-definition language for this native UI.
