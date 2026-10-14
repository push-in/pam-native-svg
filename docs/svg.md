# PAM Native SVG

`pushinbr/pam-native-svg` renders vector graphics natively through the `svg.view` host view:
Android draws a retained node tree on Canvas (RenderNode layers on API 29+), iOS builds a
Core Animation `CAShapeLayer` tree. PHP never runs per frame.

## Building documents

```php
use Pam\Native\Svg\{Svg, Path, Circle, Rect, Line, G, LinearGradient, RadialGradient, Stop, ClipPath, Transform, LineCap};

Svg::make(24, 24)                       // width/height; viewBox defaults to 0 0 24 24
    ->viewBox(0, 0, 24, 24)
    ->add(
        LinearGradient::make('fade', 0, 0, 0, 1)->stops(Stop::at(0, '#fff'), Stop::at(1, '#fff', 0)),
        Rect::make(0, 0, 24, 24, rx: 6)->fillGradient('fade'),
        G::make(
            Circle::make(12, 12, 10)->fill('none')->stroke('currentColor', 2)
                ->strokeDasharray([4, 2])->strokeDashoffset(1)->strokeLinecap(LineCap::Round),
            Line::make(4, 12, 20, 12)->stroke('currentColor', 2)->opacity(0.6),
        )->transform(Transform::rotate(-90, 12, 12)),
    )
    ->size(24)            // layout size in dp (height defaults to width)
    ->color('#ffffff');   // value of currentColor
```

Nodes: `Path` (full path data, including arcs, or the fluent builder), `Circle`, `Ellipse`, `Rect`,
`Line`, `Polyline`, `Polygon`, `G`, `ClipPath`, `LinearGradient`, `RadialGradient`, `Stop`.
Presentation: `fill`, `fillGradient`, `fillOpacity`, `fillRule`, `stroke`, `strokeGradient`,
`strokeWidth`, `strokeLinecap`, `strokeLinejoin`, `strokeMiterlimit`, `strokeDasharray`,
`strokeDashoffset`, `strokeOpacity`, `pathLength`, `trim(start, end)`, `opacity`, `transform`,
`clipPath`, `id`.

## Existing markup

```php
Svg::fromFile(__DIR__.'/assets/icons/send.svg')->tint('#1d9bf0')->size(24);
Svg::fromString($markup)->fit(SvgFit::Cover);
```

`fromString()` hashes the markup (xxh128) and keeps validated sources in a bounded LRU
(128 entries / 4 MiB); `fromFile()` additionally indexes files by path, mtime and size, so each icon
is read and hashed once per worker. DOCTYPE/ENTITY declarations are rejected and sources are
limited to 256 KiB. The native side parses each hash once into a retained tree kept in a
process-wide LRU (64 documents / 2 MiB), trimmed on memory pressure.

`tint()` recolors every painted fill and stroke (gradients included) while keeping per-node
alpha — the equivalent of an icon `tintColor`. `color()` sets `currentColor`.

Supported markup: `svg` (nested), `g`, `a`, `switch`, `symbol`/`use`, `defs`, `path`, `circle`,
`ellipse`, `rect`, `line`, `polyline`, `polygon`, `linearGradient`, `radialGradient` (href
inheritance, `gradientUnits`, `gradientTransform`), `clipPath`, `<style>` with tag/class/id
selectors, `style=""`, presentation attributes, `preserveAspectRatio`, `pathLength`, `display`,
`visibility`. Not supported: `text`, `mask`, `filter`, `pattern`, `image`, markers, CSS animations.

## Native animations

```php
use Pam\Native\Svg\{SvgAnimation, SvgProperty, SvgEasing};

$svg->animate(
    SvgAnimation::to('progress', SvgProperty::TrimEnd, $fraction)->duration(200)->easing(SvgEasing::EaseOut),
    SvgAnimation::to('spinner', SvgProperty::Rotation, 360)->from(0)->duration(900)
        ->easing(SvgEasing::Linear)->repeatForever()->origin(12, 12),
)->onAnimationEnd(fn (string $id, SvgProperty $property) => ...);
```

Animatable properties: `Opacity`, `StrokeDashoffset`, `TrimStart`, `TrimEnd`, `Rotation`,
`Scale`, `TranslateX`, `TranslateY`, `StrokeWidth`. Rules:

- The native view owns the timeline. A new target animates from the value currently on screen,
  so pushing progress 0.3 → 0.31 → 0.32 never restarts the curve; identical specs are ignored.
- On first mount the property jumps to the target unless `from()` or `animateOnMount()` is set.
- `repeat(n, reverse)` / `repeatForever()` (requires `from()`), `delay()`, `origin()` for
  rotation/scale pivots (defaults to the node's bounds center).
- Android runs one Choreographer callback per view only while an animation is active and the view
  is attached and visible; animator-duration-scale 0 jumps to the end. iOS uses
  `CABasicAnimation` on `strokeEnd`/`strokeStart`/`lineDashPhase`/`lineWidth`/`opacity`/
  `transform`, rendered by the render server; off-window views pause their layer time;
  Reduce Motion jumps to the end.

## Progress ring and tags

```php
ProgressRing::make($upload->fraction)->size(44)->strokeWidth(3)->color('#fff')->trackColor('#ffffff40');
ProgressRing::indeterminate()->size(24);
```

The ring's markup never contains the progress, so its hash is stable and it is parsed once.

```html
<SvgIcon src="assets/icons/send.svg" tint="#1d9bf0" size="24" label="Enviar" />
<SvgIcon :source="$markup" color="#fff" size="20" />
<ProgressRing :progress="$fraction" size="44" stroke="3" color="#fff" track="#ffffff40" />
<ProgressRing indeterminate size="24" />
```

Relative `src` paths resolve against `PAM_NATIVE_APP_ROOT` when defined, else the working directory.

## Performance versus react-native-svg

| | react-native-svg | pam-native-svg |
| --- | --- | --- |
| Parse | JS → shadow tree each render | once per content hash, shared across views |
| Animated progress | Reanimated/Animated props crossing to native each frame | native timeline (Choreographer / Core Animation) |
| Paths/shaders | rebuilt on prop changes | Paths, Matrices and Shaders built once per document |
| Rotation/opacity | re-draw | RenderNode properties (Android 10+), CALayer transforms (iOS) |
| Hidden views | keep animating | no frames while detached/invisible |

## Validation

- `pam tests/run.php` — PHP contracts.
- `android/`: `gradle -PpamPluginApi=<pam-native-android-plugin-api.aar> testDebugUnitTest`
  (parser, paths, cascade, cache, timeline) and `connectedDebugAndroidTest` on an emulator
  (pixel rendering, trim, dashes, gradients, tint, RenderNode layers, frame loop visibility, events).
- iOS: see [ios-validation.md](ios-validation.md).
