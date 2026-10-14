<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Straight segment; only stroked. */
final class Line extends Node
{
    public static function make(float $x1, float $y1, float $x2, float $y2): self
    {
        $node = new self();
        $node->attributes['x1'] = Markup::number($x1);
        $node->attributes['y1'] = Markup::number($y1);
        $node->attributes['x2'] = Markup::number($x2);
        $node->attributes['y2'] = Markup::number($y2);

        return $node;
    }

    protected function tag(): string
    {
        return 'line';
    }
}
