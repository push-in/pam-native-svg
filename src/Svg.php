<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\Internal\Wire;
use Pam\Native\Renderable;
use Pam\Native\Style;
use Pam\Native\Svg\Internal\Markup;
use Pam\Native\Svg\Internal\SourceCache;
use Pam\Native\UI\CustomView;
use RuntimeException;

/**
 * Native vector graphics rendered by the `svg.view` host view.
 *
 * Build a document from typed nodes, or load existing markup once:
 *
 * ```php
 * Svg::make(24, 24)->add(Path::make('M4 12l6 6L20 6')->stroke('currentColor', 2)->fill('none'))
 *     ->size(20)->color('#ffffff');
 * Svg::fromFile(__DIR__.'/icons/send.svg')->tint('#1d9bf0')->size(24);
 * ```
 *
 * The markup travels with a content hash; the native side parses each hash
 * once into a retained node tree (bounded LRU) and only re-applies tint,
 * size and animation targets on later renders.
 */
final class Svg implements Renderable
{
    public const string NAME = 'svg.view';
    public const int MAX_SOURCE_BYTES = 262_144;
    public const int MAX_NODES = 4_096;
    public const int MAX_ANIMATIONS = 64;

    /** @var list<SvgNode> */
    private array $nodes = [];

    /** @var array{0: float, 1: float, 2: float, 3: float} */
    private array $viewBox;

    private ?string $parsedSource = null;
    private ?string $markup = null;
    private ?string $hash = null;
    private ?float $layoutWidth = null;
    private ?float $layoutHeight = null;
    private ?string $tint = null;
    private ?string $currentColor = null;
    private ?SvgFit $fit = null;

    /** @var array<string, SvgAnimation> */
    private array $animations = [];

    /** @var Closure(string, SvgProperty): void|null */
    private ?Closure $animationEnd = null;

    /** @var list<string> */
    private array $classes = [];
    private ?string $label = null;
    private ?string $testId = null;

    private function __construct(private readonly float $width, private readonly float $height)
    {
        $this->viewBox = [0.0, 0.0, $width, $height];
    }

    public static function make(float $width = 24.0, float $height = 24.0): self
    {
        if (!is_finite($width) || !is_finite($height) || $width <= 0.0 || $height <= 0.0) {
            throw new InvalidArgumentException('SVG documents require a positive width and height.');
        }

        return new self($width, $height);
    }

    /**
     * Uses existing SVG markup. Validation and hashing are cached by content
     * hash, so rendering the same icon again costs one hash lookup.
     */
    public static function fromString(string $markup): self
    {
        $hash = SourceCache::hash($markup);
        $source = SourceCache::get($hash) ?? self::validate($markup);
        SourceCache::put($hash, $source);

        $svg = new self(24.0, 24.0);
        $svg->parsedSource = $source;
        $svg->markup = $source;
        $svg->hash = $hash;

        return $svg;
    }

    /** Reads an .svg file once per (path, mtime, size) and reuses its cached hash. */
    public static function fromFile(string $path): self
    {
        $real = realpath($path);
        if ($real === false || !is_file($real) || !is_readable($real)) {
            throw new InvalidArgumentException("SVG file {$path} is not readable.");
        }
        $size = filesize($real);
        if ($size === false || $size > self::MAX_SOURCE_BYTES) {
            throw new InvalidArgumentException('SVG files are limited to 256 KiB.');
        }
        $fingerprint = $real.'|'.(int) filemtime($real).'|'.$size;
        $hash = SourceCache::fileHash($fingerprint);
        if ($hash !== null) {
            $source = SourceCache::get($hash);
            if ($source !== null) {
                $svg = new self(24.0, 24.0);
                $svg->parsedSource = $source;
                $svg->markup = $source;
                $svg->hash = $hash;

                return $svg;
            }
        }
        $contents = file_get_contents($real);
        if ($contents === false) {
            throw new RuntimeException("SVG file {$path} could not be read.");
        }
        $svg = self::fromString($contents);
        SourceCache::rememberFile($fingerprint, (string) $svg->hash);

        return $svg;
    }

    public function add(SvgNode ...$nodes): self
    {
        $this->assertBuilt();
        $copy = clone $this;
        foreach ($nodes as $node) {
            $copy->nodes[] = $node;
        }
        if (count($copy->nodes) > self::MAX_NODES) {
            throw new InvalidArgumentException('SVG documents are limited to '.self::MAX_NODES.' top-level nodes.');
        }
        $copy->markup = null;
        $copy->hash = null;

        return $copy;
    }

    public function viewBox(float $minX, float $minY, float $width, float $height): self
    {
        $this->assertBuilt();
        if ($width <= 0.0 || $height <= 0.0 || !is_finite($minX + $minY + $width + $height)) {
            throw new InvalidArgumentException('viewBox requires a finite origin and positive size.');
        }
        $copy = clone $this;
        $copy->viewBox = [$minX, $minY, $width, $height];
        $copy->markup = null;
        $copy->hash = null;

        return $copy;
    }

    /** Layout size in dp; height defaults to width. */
    public function size(float $width, ?float $height = null): self
    {
        $height ??= $width;
        if ($width < 0.0 || $height < 0.0 || !is_finite($width + $height)) {
            throw new InvalidArgumentException('SVG size must be finite and non-negative.');
        }
        $copy = clone $this;
        $copy->layoutWidth = $width;
        $copy->layoutHeight = $height;

        return $copy;
    }

    /** Recolors every painted fill and stroke (gradients included), keeping per-node alpha. Null removes it. */
    public function tint(?string $color): self
    {
        $copy = clone $this;
        $copy->tint = $color === null ? null : Markup::color($color);

        return $copy;
    }

    /** Value of `currentColor` inside the document (defaults to black). */
    public function color(string $color): self
    {
        $copy = clone $this;
        $copy->currentColor = Markup::color($color);

        return $copy;
    }

    public function fit(SvgFit $fit): self
    {
        $copy = clone $this;
        $copy->fit = $fit;

        return $copy;
    }

    /** Adds or replaces (same node id + property) native animations. */
    public function animate(SvgAnimation ...$animations): self
    {
        $copy = clone $this;
        foreach ($animations as $animation) {
            $copy->animations[$animation->key()] = $animation;
        }
        if (count($copy->animations) > self::MAX_ANIMATIONS) {
            throw new InvalidArgumentException('SVG views are limited to 64 animations.');
        }

        return $copy;
    }

    /** @param Closure(string, SvgProperty): void $handler called when a finite animation settles */
    public function onAnimationEnd(Closure $handler): self
    {
        $copy = clone $this;
        $copy->animationEnd = $handler;

        return $copy;
    }

    public function class(string ...$classes): self
    {
        $copy = clone $this;
        $copy->classes = [...$copy->classes, ...$classes];

        return $copy;
    }

    public function accessibilityLabel(string $label): self
    {
        $copy = clone $this;
        $copy->label = $label;

        return $copy;
    }

    public function testId(string $id): self
    {
        $copy = clone $this;
        $copy->testId = $id;

        return $copy;
    }

    public function toMarkup(): string
    {
        if ($this->markup !== null) {
            return $this->markup;
        }
        $body = implode('', array_map(static fn (SvgNode $node): string => $node->toMarkup(), $this->nodes));
        $markup = Markup::element('svg', [
            'xmlns' => 'http://www.w3.org/2000/svg',
            'width' => Markup::number($this->width),
            'height' => Markup::number($this->height),
            'viewBox' => Markup::numbers($this->viewBox),
        ], $body === '' ? '<g/>' : $body);
        if (strlen($markup) > self::MAX_SOURCE_BYTES) {
            throw new InvalidArgumentException('SVG documents are limited to 256 KiB of markup.');
        }
        $this->markup = $markup;

        return $markup;
    }

    public function cacheKey(): string
    {
        return $this->hash ??= SourceCache::hash($this->toMarkup());
    }

    /** @return list<SvgAnimation> */
    public function animations(): array
    {
        return array_values($this->animations);
    }

    /** @return array<string, string|int|bool> */
    public function hostProperties(): array
    {
        $properties = [
            'source' => $this->toMarkup(),
            'key' => $this->cacheKey(),
            'fit' => ($this->fit ?? SvgFit::Contain)->value,
            'fitSet' => $this->fit !== null,
            'events' => $this->animationEnd !== null,
        ];
        if ($this->tint !== null) {
            $properties['tint'] = $this->tint;
        }
        if ($this->currentColor !== null) {
            $properties['color'] = $this->currentColor;
        }
        if ($this->animations !== []) {
            $properties['anim'] = json_encode(
                array_map(static fn (SvgAnimation $animation): array => $animation->toArray(), array_values($this->animations)),
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION,
            );
        }

        return $properties;
    }

    public function toElement(): Element
    {
        $view = CustomView::make(self::NAME, $this->hostProperties());
        if ($this->layoutWidth !== null) {
            $view = $view->style(new Style(width: $this->layoutWidth, height: $this->layoutHeight));
        }
        if ($this->classes !== []) {
            $view = $view->class(...$this->classes);
        }
        if ($this->label !== null) {
            $view = $view->accessibilityLabel($this->label);
        }
        if ($this->testId !== null) {
            $view = $view->testId($this->testId);
        }
        $handler = $this->animationEnd;
        if ($handler === null) {
            return $view;
        }

        return $view->onNativeEvent(static function (string $payload) use ($handler): void {
            $values = Wire::decodeMap($payload);
            if ((int) ($values['event'] ?? 0) !== SvgEventKind::AnimationEnd->value) {
                return;
            }
            $property = SvgProperty::tryFrom((int) ($values['property'] ?? 0));
            if ($property !== null) {
                $handler((string) ($values['id'] ?? ''), $property);
            }
        });
    }

    private function assertBuilt(): void
    {
        if ($this->parsedSource !== null) {
            throw new InvalidArgumentException('Documents loaded with fromString()/fromFile() cannot be edited; tint, size and animate them instead.');
        }
    }

    private static function validate(string $markup): string
    {
        $source = trim($markup);
        if ($source === '' || strlen($source) > self::MAX_SOURCE_BYTES) {
            throw new InvalidArgumentException('SVG markup must be between 1 byte and 256 KiB.');
        }
        if (preg_match('//u', $source) !== 1) {
            throw new InvalidArgumentException('SVG markup must be valid UTF-8.');
        }
        if (preg_match('/<!(DOCTYPE|ENTITY)/i', $source) === 1) {
            throw new InvalidArgumentException('SVG markup cannot declare a DOCTYPE or entities.');
        }
        if (preg_match('/<svg[\s>]/', $source) !== 1) {
            throw new InvalidArgumentException('SVG markup requires an <svg> root element.');
        }

        return $source;
    }
}
