<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Gradient color stop at offset 0..1. */
final readonly class Stop
{
    private function __construct(public float $offset, public string $color, public float $opacity)
    {
    }

    public static function at(float $offset, string $color, float $opacity = 1.0): self
    {
        return new self(Markup::unit($offset, 'Stop offset'), Markup::color($color), Markup::unit($opacity, 'Stop opacity'));
    }

    public function toMarkup(): string
    {
        $attributes = ['offset' => Markup::number($this->offset), 'stop-color' => $this->color];
        if ($this->opacity < 1.0) {
            $attributes['stop-opacity'] = Markup::number($this->opacity);
        }

        return Markup::element('stop', $attributes);
    }
}
