<?php

declare(strict_types=1);

// Contract tests for pushinbr/pam-native-svg. Run with `pam tests/run.php`.

require __DIR__.'/bootstrap.php';

use Pam\Native\Element;
use Pam\Native\Svg\Circle;
use Pam\Native\Svg\ClipPath;
use Pam\Native\Svg\FillRule;
use Pam\Native\Svg\G;
use Pam\Native\Svg\GradientUnits;
use Pam\Native\Svg\Internal\SourceCache;
use Pam\Native\Svg\LineCap;
use Pam\Native\Svg\LineJoin;
use Pam\Native\Svg\Line;
use Pam\Native\Svg\LinearGradient;
use Pam\Native\Svg\Path;
use Pam\Native\Svg\Polygon;
use Pam\Native\Svg\ProgressRing;
use Pam\Native\Svg\RadialGradient;
use Pam\Native\Svg\Rect;
use Pam\Native\Svg\Stop;
use Pam\Native\Svg\Svg;
use Pam\Native\Svg\SvgAnimation;
use Pam\Native\Svg\SvgEasing;
use Pam\Native\Svg\SvgEventKind;
use Pam\Native\Svg\SvgFit;
use Pam\Native\Svg\SvgPluginProvider;
use Pam\Native\Svg\SvgProperty;
use Pam\Native\Svg\Transform;
use Pam\Native\TemplateRegistry;

$tests = [];
$test = static function (string $name, Closure $body) use (&$tests): void {
    $tests[$name] = $body;
};
$assert = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (Closure $body, string $message): void {
    try {
        $body();
    } catch (InvalidArgumentException) {
        return;
    }
    throw new RuntimeException($message);
};
$frameworkLoaded = class_exists(Element::class);

$test('builds typed nodes into deterministic markup', static function () use ($assert): void {
    $svg = Svg::make(24, 24)->add(
        Path::make()->moveTo(4, 12)->lineTo(10, 18)->lineTo(20, 6)->fill('none')->stroke('currentColor', 2)
            ->strokeLinecap(LineCap::Round)->strokeLinejoin(LineJoin::Round),
        Circle::make(12, 12, 10)->id('ring')->strokeDasharray([62.83, 62.83])->strokeDashoffset(31.4)->opacity(0.5),
        Rect::make(0, 0, 4, 4, 1)->fillRule(FillRule::EvenOdd),
        Line::make(0, 0, 24, 24)->stroke('#ff000080'),
        Polygon::make([[0, 0], [1, 0], [1, 1]]),
    );
    $markup = $svg->toMarkup();
    $assert(str_starts_with($markup, '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">'), 'root');
    $assert(str_contains($markup, '<path d="M4 12 L10 18 L20 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'), 'path: '.$markup);
    $assert(str_contains($markup, '<circle cx="12" cy="12" r="10" id="ring" stroke-dasharray="62.83,62.83" stroke-dashoffset="31.4" opacity="0.5"/>'), 'circle');
    $assert(str_contains($markup, '<rect x="0" y="0" width="4" height="4" rx="1" ry="1" fill-rule="evenodd"/>'), 'rect');
    $assert(str_contains($markup, '<polygon points="0,0 1,0 1,1"/>'), 'polygon');
    $assert($svg->toMarkup() === $markup && strlen($svg->cacheKey()) === 32, 'stable hash');
});

$test('groups, transforms, gradients and clip paths serialize', static function () use ($assert): void {
    $markup = Svg::make(48, 48)->viewBox(0, 0, 24, 24)->add(
        LinearGradient::make('fade', 0, 0, 0, 1)->stops(Stop::at(0, '#fff'), Stop::at(1, '#000', 0.25))
            ->units(GradientUnits::ObjectBoundingBox),
        RadialGradient::make('glow')->stops(Stop::at(0, '#f00'), Stop::at(1, '#00f'))
            ->gradientTransform(Transform::scale(1, 0.5)),
        ClipPath::make('clip', Circle::make(12, 12, 12)),
        G::make(Rect::make(0, 0, 24, 24)->fillGradient('fade'))->id('group')->clipPath('clip')
            ->transform(Transform::rotate(-90, 12, 12)->thenScale(2)->thenTranslate(1, 2)->thenSkewX(10)),
    )->toMarkup();
    $assert(str_contains($markup, 'viewBox="0 0 24 24"'), 'viewBox');
    $assert(str_contains($markup, '<defs><linearGradient id="fade" x1="0" y1="0" x2="0" y2="1" gradientUnits="objectBoundingBox"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000" stop-opacity="0.25"/></linearGradient></defs>'), 'linear: '.$markup);
    $assert(str_contains($markup, 'gradientTransform="scale(1 0.5)"'), 'gradient transform');
    $assert(str_contains($markup, '<defs><clipPath id="clip"><circle cx="12" cy="12" r="12"/></clipPath></defs>'), 'clip');
    $assert(str_contains($markup, 'transform="rotate(-90 12 12) scale(2 2) translate(1 2) skewX(10)"'), 'transform');
    $assert(str_contains($markup, 'fill="url(#fade)"') && str_contains($markup, 'clip-path="url(#clip)"'), 'refs');
});

$test('builders are immutable', static function () use ($assert): void {
    $base = Circle::make(1, 1, 1);
    $assert($base !== $base->fill('#fff') && !str_contains($base->toMarkup(), 'fill'), 'node mutated');
    $svg = Svg::make();
    $assert($svg->add(Circle::make(1, 1, 1))->toMarkup() !== $svg->toMarkup(), 'svg mutated');
    $ring = ProgressRing::make(0.2);
    $assert($ring !== $ring->size(10), 'ring mutated');
});

$test('rejects invalid input', static function () use ($throws): void {
    $throws(static fn () => Circle::make(0, 0, -1), 'negative radius');
    $throws(static fn () => Circle::make(0, 0, 1)->fill('javascript:alert(1)'), 'bad color');
    $throws(static fn () => Circle::make(0, 0, 1)->id('1bad'), 'bad id');
    $throws(static fn () => Path::make('M0 0 <script>'), 'bad path');
    $throws(static fn () => Circle::make(0, 0, 1)->opacity(2), 'opacity range');
    $throws(static fn () => Circle::make(0, 0, 1)->trim(0, 1.5), 'trim range');
    $throws(static fn () => Svg::make(0, 1), 'empty svg');
    $throws(static fn () => Svg::fromString('<!DOCTYPE svg><svg/>'), 'doctype');
    $throws(static fn () => Svg::fromString('<svg><!ENTITY x "y"></svg>'), 'entity');
    $throws(static fn () => Svg::fromString('<div/>'), 'not svg');
    $throws(static fn () => Svg::fromString('<svg>'.str_repeat('a', Svg::MAX_SOURCE_BYTES).'</svg>'), 'too large');
    $throws(static fn () => Svg::fromString('<svg/>')->add(Circle::make(1, 1, 1)), 'edit parsed');
    $throws(static fn () => SvgAnimation::to('x', SvgProperty::Rotation, 360)->repeatForever(), 'infinite without from');
    $throws(static fn () => SvgAnimation::to('x', SvgProperty::Opacity, 1)->duration(-1), 'negative duration');
    $throws(static fn () => Polygon::make([[0, 0]]), 'polygon points');
});

$test('fromString and fromFile validate once and reuse the hash', static function () use ($assert): void {
    SourceCache::clear();
    $source = '<svg viewBox="0 0 24 24"><path d="M0 0L24 24" stroke="currentColor"/></svg>';
    $first = Svg::fromString($source);
    $second = Svg::fromString($source);
    $assert($first->cacheKey() === $second->cacheKey() && $first->cacheKey() === hash('xxh128', $source), 'hash');
    $assert(SourceCache::stats()['entries'] === 1, 'one cache entry');
    $file = sys_get_temp_dir().'/pam-svg-test-'.bin2hex(random_bytes(4)).'.svg';
    file_put_contents($file, $source);
    try {
        $fromFile = Svg::fromFile($file);
        $again = Svg::fromFile($file);
        $assert($fromFile->cacheKey() === $first->cacheKey() && $again->toMarkup() === $source, 'file hash');
        $assert(SourceCache::stats()['files'] === 1, 'file index');
    } finally {
        unlink($file);
    }
});

$test('source cache is bounded by entries and bytes', static function () use ($assert): void {
    SourceCache::clear();
    for ($i = 0; $i < SourceCache::MAX_ENTRIES + 20; $i++) {
        Svg::fromString('<svg><rect width="'.$i.'" height="1"/></svg>');
    }
    $stats = SourceCache::stats();
    $assert($stats['entries'] === SourceCache::MAX_ENTRIES, 'entry bound '.$stats['entries']);
    SourceCache::clear();
    $big = '<svg>'.str_repeat('<g/>', 60_000).'</svg>';
    for ($i = 0; $i < 30; $i++) {
        Svg::fromString($big.'<!--'.$i.'-->');
    }
    $assert(SourceCache::stats()['bytes'] <= SourceCache::MAX_BYTES, 'byte bound');
    SourceCache::clear();
});

$test('animations encode compact native specs', static function () use ($assert): void {
    $animation = SvgAnimation::to('spinner', SvgProperty::Rotation, 360)->from(0)->duration(900)
        ->easing(SvgEasing::Linear)->repeatForever()->origin(12, 12)->delay(10);
    $assert($animation->toArray() === ['i' => 'spinner', 'p' => 5, 't' => 360.0, 'd' => 900, 'e' => 1, 'f' => 0.0, 'w' => 10, 'r' => -1, 'o' => [12.0, 12.0]], 'spec '.json_encode($animation->toArray()));
    $finite = SvgAnimation::to('p', SvgProperty::TrimEnd, 0.5)->repeat(1, true)->animateOnMount();
    $assert($finite->toArray()['a'] === 1 && $finite->toArray()['m'] === 1 && $finite->toArray()['r'] === 1, 'flags');
    $svg = Svg::make()->animate($finite, SvgAnimation::to('p', SvgProperty::TrimEnd, 0.7));
    $assert(count($svg->animations()) === 1 && $svg->animations()[0]->to === 0.7, 'same id+property replaces');
});

$test('progress markup is stable so native parses it once', static function () use ($assert): void {
    $a = ProgressRing::make(0.1)->size(44)->strokeWidth(3)->toSvg();
    $b = ProgressRing::make(0.9)->size(44)->strokeWidth(3)->toSvg();
    $assert($a->cacheKey() === $b->cacheKey(), 'progress must not change the hash');
    $assert($b->animations()[0]->to === 0.9 && $b->animations()[0]->property === SvgProperty::TrimEnd, 'target');
    $assert(ProgressRing::make(7)->toSvg()->animations()[0]->to === 1.0, 'clamped');
    $spinner = ProgressRing::indeterminate()->toSvg();
    $assert($spinner->animations()[0]->repeat === -1 && $spinner->animations()[0]->property === SvgProperty::Rotation, 'spinner');
    $assert(str_contains($a->toMarkup(), 'pam-trim-end="0"') && str_contains($a->toMarkup(), 'id="pam-progress"'), 'trim markup');
});

$test('host properties carry source, hash, appearance and animations', static function () use ($assert): void {
    $props = Svg::fromString('<svg><circle id="c" r="1"/></svg>')->tint('#fff')->color('#000')->fit(SvgFit::Cover)
        ->animate(SvgAnimation::to('c', SvgProperty::Opacity, 0.5))->hostProperties();
    $assert($props['source'] === '<svg><circle id="c" r="1"/></svg>' && strlen((string) $props['key']) === 32, 'source');
    $assert($props['tint'] === '#fff' && $props['color'] === '#000' && $props['fit'] === 2 && $props['fitSet'] === true, 'appearance');
    $assert($props['anim'] === '[{"i":"c","p":1,"t":0.5,"d":250,"e":4}]', 'anim '.$props['anim']);
    $assert($props['events'] === false, 'events default');
    $plain = Svg::make()->hostProperties();
    $assert(!isset($plain['anim']) && !isset($plain['tint']) && $plain['fitSet'] === false, 'defaults');
});

$test('all coded variants are sequential integers', static function () use ($assert): void {
    foreach ([SvgProperty::cases(), SvgEasing::cases(), SvgFit::cases(), LineCap::cases(), LineJoin::cases(), FillRule::cases(), GradientUnits::cases(), SvgEventKind::cases()] as $cases) {
        $values = array_map(static fn ($case) => $case->value, $cases);
        $assert($values === range(1, count($values)), 'non-sequential enum');
    }
    $idl = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.idl.json'), true, 512, JSON_THROW_ON_ERROR);
    $assert(count($idl['enums']['SvgProperty']) === count(SvgProperty::cases()), 'idl in sync');
});

if ($frameworkLoaded) {
    $test('renders a svg.view custom element with events', static function () use ($assert): void {
        $element = Svg::make()->add(Circle::make(12, 12, 10)->id('c'))->size(24)->class('icon')
            ->accessibilityLabel('Upload')->onAnimationEnd(static function (string $id, SvgProperty $property): void {})
            ->toElement();
        $assert($element instanceof Element, 'not an element');
        $assert(ProgressRing::make(0.5)->toElement() instanceof Element, 'ring element');
    });

    $test('registers SvgIcon and ProgressRing template tags', static function () use ($assert): void {
        TemplateRegistry::reset();
        (new SvgPluginProvider())->register();
        $icon = TemplateRegistry::factory('SvgIcon');
        $ring = TemplateRegistry::factory('ProgressRing');
        $assert($icon instanceof Closure && $ring instanceof Closure, 'tags missing');
        $svg = $icon(['source' => '<svg><path d="M0 0L1 1"/></svg>', 'size' => '20', 'tint' => '#fff', 'label' => 'x'], [], null);
        $assert($svg instanceof Svg && $svg->hostProperties()['tint'] === '#fff', 'icon');
        $progress = $ring(['progress' => '0.4', 'size' => 32, 'stroke' => '2', 'color' => '#ff0000', 'track' => ''], [], null);
        $assert($progress instanceof ProgressRing && $progress->toSvg()->animations()[0]->to === 0.4, 'ring');
        $assert(!str_contains($progress->toSvg()->toMarkup(), '#ffffff40'), 'track removed');
        $spinner = $ring(['indeterminate' => true], [], null);
        $assert($spinner->toSvg()->animations()[0]->repeat === -1, 'indeterminate tag');
        TemplateRegistry::reset();
    });
}

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        fwrite(STDOUT, "PASS {$name}\n");
    } catch (Throwable $error) {
        $failed++;
        fwrite(STDERR, "FAIL {$name}: {$error->getMessage()}\n");
    }
}
fwrite(STDOUT, count($tests).' tests, '.$failed." failures\n");
exit($failed > 0 ? 1 : 0);
