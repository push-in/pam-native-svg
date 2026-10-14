<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Axis-aligned ellipse. */
final class Ellipse extends Node
{
    public static function make(float $cx, float $cy, float $rx, float $ry): self
    {
        $node = new self();
        $node->attributes['cx'] = Markup::number($cx);
        $node->attributes['cy'] = Markup::number($cy);
        $node->attributes['rx'] = Markup::number(self::positive($rx, 'Radius'));
        $node->attributes['ry'] = Markup::number(self::positive($ry, 'Radius'));

        return $node;
    }

    protected function tag(): string
    {
        return 'ellipse';
    }
}
