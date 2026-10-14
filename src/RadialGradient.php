<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Radial gradient centered at (cx, cy) with radius r; defaults to the bounding box center. */
final class RadialGradient extends Gradient
{
    public static function make(string $id, float $cx = 0.5, float $cy = 0.5, float $r = 0.5): self
    {
        $gradient = new self($id);
        $gradient->attributes += [
            'cx' => Markup::number($cx),
            'cy' => Markup::number($cy),
            'r' => Markup::number(max(0.0, $r)),
        ];

        return $gradient;
    }

    protected function tag(): string
    {
        return 'radialGradient';
    }
}
