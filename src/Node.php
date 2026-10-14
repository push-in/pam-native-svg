<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/**
 * Base for drawable SVG nodes: presentation attributes are inherited by
 * children exactly like SVG (`fill`, `stroke`, ...), `opacity` and
 * `transform` apply to the node itself.
 *
 * Give a node an {@see id()} to animate it natively with {@see SvgAnimation}.
 */
abstract class Node implements SvgNode
{
    /** @var array<string, string> */
    protected array $attributes = [];

    abstract protected function tag(): string;

    protected function content(): string
    {
        return '';
    }

    final public function toMarkup(): string
    {
        return Markup::element($this->tag(), $this->attributes, $this->content());
    }

    final public function id(string $id): static
    {
        return $this->attr('id', Markup::id($id));
    }

    final public function fill(string $color): static
    {
        return $this->attr('fill', Markup::color($color));
    }

    final public function fillGradient(string $gradientId): static
    {
        return $this->attr('fill', 'url(#'.Markup::id($gradientId).')');
    }

    final public function stroke(string $color, ?float $width = null): static
    {
        $copy = $this->attr('stroke', Markup::color($color));

        return $width === null ? $copy : $copy->strokeWidth($width);
    }

    final public function strokeGradient(string $gradientId, ?float $width = null): static
    {
        $copy = $this->attr('stroke', 'url(#'.Markup::id($gradientId).')');

        return $width === null ? $copy : $copy->strokeWidth($width);
    }

    final public function strokeWidth(float $width): static
    {
        return $this->attr('stroke-width', Markup::number(self::positive($width, 'Stroke width')));
    }

    final public function strokeLinecap(LineCap $cap): static
    {
        return $this->attr('stroke-linecap', match ($cap) {
            LineCap::Butt => 'butt',
            LineCap::Round => 'round',
            LineCap::Square => 'square',
        });
    }

    final public function strokeLinejoin(LineJoin $join): static
    {
        return $this->attr('stroke-linejoin', match ($join) {
            LineJoin::Miter => 'miter',
            LineJoin::Round => 'round',
            LineJoin::Bevel => 'bevel',
        });
    }

    final public function strokeMiterlimit(float $limit): static
    {
        return $this->attr('stroke-miterlimit', Markup::number(max(1.0, $limit)));
    }

    /** @param list<float|int> $pattern empty list removes dashing */
    final public function strokeDasharray(array $pattern): static
    {
        if (count($pattern) > 32) {
            throw new InvalidArgumentException('Dash patterns are limited to 32 values.');
        }
        foreach ($pattern as $value) {
            self::positive((float) $value, 'Dash value');
        }

        return $this->attr('stroke-dasharray', $pattern === [] ? 'none' : Markup::numbers($pattern, ','));
    }

    final public function strokeDashoffset(float $offset): static
    {
        return $this->attr('stroke-dashoffset', Markup::number($offset));
    }

    /**
     * Author-space length of the path: dash values and offsets are measured
     * in this unit. `pathLength(100)` turns dashes into percentages.
     */
    final public function pathLength(float $length): static
    {
        if ($length <= 0.0) {
            throw new InvalidArgumentException('pathLength must be positive.');
        }

        return $this->attr('pathLength', Markup::number($length));
    }

    /**
     * Draws only the [start, end] fraction of the stroke (CAShapeLayer
     * strokeStart/strokeEnd). Ideal for progress rings; animate it with
     * SvgProperty::TrimEnd.
     */
    final public function trim(float $start, float $end): static
    {
        Markup::unit($start, 'Trim start');
        Markup::unit($end, 'Trim end');

        return $this->attr('pam-trim-start', Markup::number($start))->attr('pam-trim-end', Markup::number($end));
    }

    final public function opacity(float $opacity): static
    {
        return $this->attr('opacity', Markup::number(Markup::unit($opacity, 'Opacity')));
    }

    final public function fillOpacity(float $opacity): static
    {
        return $this->attr('fill-opacity', Markup::number(Markup::unit($opacity, 'Fill opacity')));
    }

    final public function strokeOpacity(float $opacity): static
    {
        return $this->attr('stroke-opacity', Markup::number(Markup::unit($opacity, 'Stroke opacity')));
    }

    final public function fillRule(FillRule $rule): static
    {
        return $this->attr('fill-rule', $rule === FillRule::EvenOdd ? 'evenodd' : 'nonzero');
    }

    final public function transform(Transform $transform): static
    {
        if ($transform->isIdentity()) {
            $copy = clone $this;
            unset($copy->attributes['transform']);

            return $copy;
        }

        return $this->attr('transform', $transform->toString());
    }

    final public function clipPath(string $clipPathId): static
    {
        return $this->attr('clip-path', 'url(#'.Markup::id($clipPathId).')');
    }

    final protected function attr(string $name, string $value): static
    {
        $copy = clone $this;
        $copy->attributes[$name] = $value;

        return $copy;
    }

    final protected static function positive(float $value, string $label): float
    {
        if (!is_finite($value) || $value < 0.0) {
            throw new InvalidArgumentException("{$label} must be a finite non-negative number.");
        }

        return $value;
    }
}
