# iOS validation checklist (Mac required)

The iOS renderer (`ios/Sources/SvgViewFactory.swift`) and core (`ios/Sources/SvgCore.swift`)
were written without an Apple SDK. Before relying on an iOS build:

1. Core: from the package root run
   `mkdir -p .build && swiftc -O ios/Sources/SvgCore.swift tests/ios-core/main.swift -o .build/svg-core && .build/svg-core`
   — expect `PASS svg core`.
2. App build: `pam composer require pushinbr/pam-native-svg`, then build the iOS host
   (`vendor/bin/pam-native build . --platform ios`) and fix any Swift 6 strict-concurrency warnings.
3. Simulator (iPhone 15, iOS 17+), light and dark:
   - icon via `Svg::fromFile` with and without `tint`; `currentColor` via `color()`;
   - `ProgressRing` 0 → 0.3 → 1 (arc starts at 12 o'clock, clockwise, no restart between updates);
   - `ProgressRing::indeterminate()` spins and stops when the screen is popped;
   - linear and radial gradient fills, gradient stroke, dashed stroke with `pathLength`, clip path;
   - `onAnimationEnd` fires once per finite animation.
4. Instruments → Core Animation: no main-thread work per frame while a ring animates;
   Time Profiler shows no `PamSvgView` frames during steady animation.
5. Settings → Accessibility → Reduce Motion: finite animations jump to their end value.
6. Memory warning (Simulator → Debug → Simulate Memory Warning) clears the document cache
   without visual glitches.
