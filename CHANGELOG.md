# Changelog

## 0.1.0 - 2026-10-14

- New package: native SVG for PAM Native through the `svg.view` host view.
- Typed builders: `Svg`, `Path` (all path commands incl. arcs), `Circle`, `Ellipse`, `Rect`, `Line`,
  `Polyline`, `Polygon`, `G`, `ClipPath`, `LinearGradient`, `RadialGradient`, `Stop`, `Transform`.
- `Svg::fromString()` / `Svg::fromFile()` with validation cached by content hash (bounded LRU) and
  `tint()`, `color()` (currentColor), `size()`, `fit()`.
- Native animations (`SvgAnimation`) of opacity, dash offset, trim start/end, rotation, scale,
  translation and stroke width; target changes continue from the on-screen value; repeat,
  reverse, delay, pivots and `onAnimationEnd`.
- `ProgressRing` (determinate and indeterminate) with a stable markup hash; `<SvgIcon>` and
  `<ProgressRing>` template tags.
- Android: dependency-free parser, retained Paths/Shaders/Matrices per document, process-wide
  document LRU trimmed on memory pressure, Choreographer frames only while visible, RenderNode
  layers for transform/opacity animations on API 29+.
- iOS: XMLParser-based core and a CAShapeLayer/CAGradientLayer tree animated by Core Animation
  (source included; validation checklist in `docs/ios-validation.md`).
- Tests: 12 PHP contracts, 25 JVM unit tests, emulator instrumentation (rendering, trim, dashes,
  gradients, tint, RenderNode layers, visibility-gated frames, events), iOS core harness.
