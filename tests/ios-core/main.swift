// macOS/iOS-SDK check of the shared Swift core (parser, paths, colors, cache, specs).
// Run on a Mac from the package root:
//   swiftc -O ios/Sources/SvgCore.swift tests/ios-core/main.swift -o .build/svg-core && .build/svg-core
import CoreGraphics
import Foundation

var failures = 0
func check(_ condition: @autoclosure () -> Bool, _ label: String) {
    if condition() { print("PASS \(label)") } else { failures += 1; print("FAIL \(label)") }
}
func near(_ a: CGFloat, _ b: CGFloat, _ tolerance: CGFloat = 1e-3) -> Bool { abs(a - b) <= tolerance }

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)

do {
    let ring = try SvgParser.parse(String(contentsOf: root.appendingPathComponent("tests/fixtures/progress-ring.svg")))
    let arc = ring.byId["pam-progress"] as? SvgShape
    check(arc != nil && arc!.trimEnd == 0 && arc!.style.fill == .none && arc!.style.lineCap == 2, "PHP progress ring fixture")
    check(arc.map { near($0.data.length, 2 * .pi * 20.5, 0.1) } ?? false, "circle length")
    check(ring.byId["pam-spinner"] is SvgGroup, "spinner group")

    let circle = try SvgParser.parse(#"<svg viewBox="0 0 24 24"><circle id="c" cx="12" cy="12" r="10"/></svg>"#).byId["c"] as! SvgShape
    check(circle.data.path.currentPoint == CGPoint(x: 22, y: 12), "circle closes at 3 o'clock")

    let line = try SvgParser.parse(#"<svg><line id="l" x1="0" y1="5" x2="50" y2="5" pathLength="100" stroke-dasharray="1,2,3"/></svg>"#).byId["l"] as! SvgShape
    check(near(line.dashScale, 0.5) && line.style.dashes == [1, 2, 3, 1, 2, 3], "pathLength + odd dash arrays")

    let arc2 = SvgPathDataParser.parse("M0 0a5 5 0 105 5")
    check(near(arc2.path.currentPoint.x, 5) && near(arc2.path.currentPoint.y, 5), "packed arc flags")
    let compact = SvgPathDataParser.parse("M1.5.5L-1-2")
    check(compact.path.currentPoint == CGPoint(x: -1, y: -2), "compact numbers")

    let styled = try SvgParser.parse(#"<svg><style>.a{fill:#00ff00}</style><g fill="red"><rect id="r" class="a" width="1" height="1" style="stroke:blue"/></g></svg>"#)
    let rect = styled.byId["r"] as! SvgShape
    check(rect.style.fill == .solid(0xFF00_FF00) && rect.style.stroke == .solid(0xFF00_00FF), "cascade")

    let t = SvgValues.transform("translate(10 20) scale(2)")!
    check(CGPoint(x: 1, y: 1).applying(t) == CGPoint(x: 12, y: 22), "transform order")
    let r = SvgValues.transform("rotate(90 5 5)")!
    let p = CGPoint(x: 10, y: 5).applying(r)
    check(near(p.x, 5) && near(p.y, 10), "rotate around center")

    check(SvgColor.argb("#11223380") == 0x8011_2233 && SvgColor.argb("rgba(255,0,0,0.5)") == 0x80FF_0000, "colors")

    let gradients = try SvgParser.parse(#"<svg><defs><linearGradient id="b"><stop offset="0%" stop-color="#000"/><stop offset="100%" stop-color="#fff"/></linearGradient><radialGradient id="g" href="#b" r="25%"/></defs><rect width="1" height="1" fill="url(#g)"/></svg>"#)
    check(gradients.gradients["g"]?.stops.count == 2 && gradients.gradients["g"]?.radial == true, "gradient href")

    let fit = try SvgParser.parse(#"<svg viewBox="0 0 10 20"><rect width="1" height="1"/></svg>"#).fit(width: 100, height: 100, override: nil)
    check(fit.a == 5 && fit.tx == 25, "fit contain")

    var rejected = 0
    for bad in [#"<!DOCTYPE svg [<!ENTITY x "y">]><svg/>"#, "<svg><g></svg>", "<g/>"] {
        if (try? SvgParser.parse(bad)) == nil { rejected += 1 }
    }
    check(rejected == 3, "rejects malformed/doctype")

    let cache = SvgDocumentCache(maxEntries: 2)
    let a = try cache.obtain("a", "<svg/>")
    let hit = try cache.obtain("a", "<broken")
    check(hit === a, "cache hit skips parse")
    _ = try cache.obtain("b", "<svg/>")
    _ = cache.get("a")
    _ = try cache.obtain("c", "<svg/>")
    check(cache.get("a") != nil && cache.get("b") == nil, "LRU eviction")

    let specs = SvgAnimationSpec.parseAll(#"[{"i":"p","p":4,"t":0.75,"d":200,"e":3},{"i":"s","p":5,"t":360.0,"f":0.0,"d":900,"e":1,"r":-1,"o":[12.0,12.0]},{"i":"x","p":99,"t":1}]"#)
    check(specs.count == 2 && specs[0].duration == 0.2 && specs[1].repeatCount == -1 && specs[1].origin == CGPoint(x: 12, y: 12), "animation specs")
} catch {
    failures += 1
    print("FAIL unexpected \(error)")
}

print(failures == 0 ? "PASS svg core" : "\(failures) failures")
exit(failures == 0 ? 0 : 1)
