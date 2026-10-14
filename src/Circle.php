<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Circle centered at (cx, cy). The stroke starts at 3 o'clock and runs clockwise, as in SVG. */
final class Circle extends Node
{
    public static function make(float $cx, float $cy, float $r): self
    {
        $node = new self();
        $node->attributes['cx'] = Markup::number($cx);
        $node->attributes['cy'] = Markup::number($cy);
        $node->attributes['r'] = Markup::number(self::positive($r, 'Radius'));

        return $node;
    }

    protected function tag(): string
    {
        return 'circle';
    }
}
