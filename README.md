<!-- pam:product-page:start -->
<div align="center">

# PAM Native SVG

**Native vector graphics and render-thread animations for PAM Native.**

Icons, progress rings, gradients and dashed strokes parsed once, retained natively and animated without crossing back to PHP.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-svg?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-svg)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](docs/svg.md) · [Quick start](#quick-start) · [iOS validation](docs/ios-validation.md) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-svg/issues)**

</div>

---

## Why PAM Native SVG

A drop-in replacement for `react-native-svg` in PAM Native apps. PHP builds or loads the markup; the
native side parses each content hash once into a retained tree (Android Canvas + RenderNode,
iOS CAShapeLayer) and runs animations itself, so a progress ring updating 30 times a second never
re-renders PHP or re-parses a path.

| | |
| --- | --- |
| **Best for** | Icons, progress rings, badges, gradients, decorative vectors |
| **Native path** | Android Canvas / RenderNode · Core Animation |
| **Animation** | Choreographer (only while visible) · CABasicAnimation |
| **Dependencies** | None beyond the platform SDKs |

## Quick start

```bash
pam composer require pushinbr/pam-native-svg
```

```php
use Pam\Native\Svg\{Svg, Path, ProgressRing, SvgAnimation, SvgProperty};

ProgressRing::make($upload->fraction)->size(44)->strokeWidth(3)->color('#ffffff');

Svg::fromFile(__DIR__.'/assets/send.svg')->tint('#1d9bf0')->size(24);

Svg::make(24, 24)
    ->add(Path::make('M5 12l5 5L19 7')->id('check')->fill('none')->stroke('#fff', 2)->pathLength(1)->trim(0, 0))
    ->animate(SvgAnimation::to('check', SvgProperty::TrimEnd, $done ? 1 : 0)->duration(260));
```

```html
<SvgIcon src="assets/icons/send.svg" tint="#1d9bf0" size="24" />
<ProgressRing :progress="$fraction" size="44" stroke="3" color="#fff" />
```
<!-- pam:product-page:end -->

## Features

- `Svg`, `Path` (all commands incl. arcs), `Circle`, `Ellipse`, `Rect`, `Line`, `Polyline`,
  `Polygon`, `G`, `ClipPath`, `LinearGradient`, `RadialGradient`, transforms, opacity,
  dash arrays/offsets with `pathLength`, `trim()`.
- `Svg::fromString()` / `Svg::fromFile()` with hash-keyed caches on both sides; `tint()`,
  `color()` (currentColor), `size()`, `fit()`.
- Native animations of opacity, dash offset, trim, rotation, scale, translation and stroke
  width; continuous retargeting; repeat/reverse/delay; `onAnimationEnd`.
- Bounded memory: 128-entry/4 MiB PHP source LRU, 64-document/2 MiB native LRU trimmed on memory
  pressure; no frames while a view is hidden or detached.

See [docs/svg.md](docs/svg.md) for the full API, supported markup and the comparison with
`react-native-svg`, and [examples/UploadProgress.php](examples/UploadProgress.php).

## Testing

```bash
pam tests/run.php                                  # PHP contracts
cd android && gradle -PpamPluginApi=/path/pam-native-android-plugin-api-1.39.2.aar \
  testDebugUnitTest connectedDebugAndroidTest      # JVM + emulator instrumentation
```

iOS: follow [docs/ios-validation.md](docs/ios-validation.md) on a Mac.

## License

Apache-2.0.
