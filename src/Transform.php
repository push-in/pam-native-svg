<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/**
 * Immutable SVG transform list, applied left to right like the `transform` attribute.
 *
 * ```php
 * Transform::rotate(-90, 12, 12)->scale(1.5);
 * ```
 */
final readonly class Transform
{
    private const int MAX_OPERATIONS = 16;

    /** @param list<string> $operations */
    private function __construct(private array $operations)
    {
        if (count($operations) > self::MAX_OPERATIONS) {
            throw new InvalidArgumentException('SVG transforms are limited to 16 operations.');
        }
    }

    public static function identity(): self
    {
        return new self([]);
    }

    public static function translate(float $x, float $y = 0.0): self
    {
        return self::identity()->thenTranslate($x, $y);
    }

    public static function scale(float $x, ?float $y = null): self
    {
        return self::identity()->thenScale($x, $y);
    }

    public static function rotate(float $degrees, ?float $cx = null, ?float $cy = null): self
    {
        return self::identity()->thenRotate($degrees, $cx, $cy);
    }

    public static function matrix(float $a, float $b, float $c, float $d, float $e, float $f): self
    {
        return new self(['matrix('.Markup::numbers([$a, $b, $c, $d, $e, $f]).')']);
    }

    public function thenTranslate(float $x, float $y = 0.0): self
    {
        return $this->with('translate('.Markup::numbers([$x, $y]).')');
    }

    public function thenScale(float $x, ?float $y = null): self
    {
        return $this->with('scale('.Markup::numbers([$x, $y ?? $x]).')');
    }

    public function thenRotate(float $degrees, ?float $cx = null, ?float $cy = null): self
    {
        if (($cx === null) !== ($cy === null)) {
            throw new InvalidArgumentException('Rotation centers require both cx and cy.');
        }

        return $this->with('rotate('.Markup::numbers($cx === null ? [$degrees] : [$degrees, $cx, (float) $cy]).')');
    }

    public function thenSkewX(float $degrees): self
    {
        return $this->with('skewX('.Markup::number($degrees).')');
    }

    public function thenSkewY(float $degrees): self
    {
        return $this->with('skewY('.Markup::number($degrees).')');
    }

    public function isIdentity(): bool
    {
        return $this->operations === [];
    }

    public function toString(): string
    {
        return implode(' ', $this->operations);
    }

    private function with(string $operation): self
    {
        return new self([...$this->operations, $operation]);
    }
}
