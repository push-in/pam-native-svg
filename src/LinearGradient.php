<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Linear gradient; coordinates default to objectBoundingBox fractions (0..1). */
final class LinearGradient extends Gradient
{
    public static function make(string $id, float $x1 = 0.0, float $y1 = 0.0, float $x2 = 1.0, float $y2 = 0.0): self
    {
        $gradient = new self($id);
        $gradient->attributes += [
            'x1' => Markup::number($x1),
            'y1' => Markup::number($y1),
            'x2' => Markup::number($x2),
            'y2' => Markup::number($y2),
        ];

        return $gradient;
    }

    protected function tag(): string
    {
        return 'linearGradient';
    }
}
