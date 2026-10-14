<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Rectangle with optional rounded corners (rx/ry). */
final class Rect extends Node
{
    public static function make(float $x, float $y, float $width, float $height, float $rx = 0.0, ?float $ry = null): self
    {
        $node = new self();
        $node->attributes['x'] = Markup::number($x);
        $node->attributes['y'] = Markup::number($y);
        $node->attributes['width'] = Markup::number(self::positive($width, 'Width'));
        $node->attributes['height'] = Markup::number(self::positive($height, 'Height'));
        if ($rx > 0.0 || ($ry ?? 0.0) > 0.0) {
            $node->attributes['rx'] = Markup::number(self::positive($rx, 'Corner radius'));
            $node->attributes['ry'] = Markup::number(self::positive($ry ?? $rx, 'Corner radius'));
        }

        return $node;
    }

    protected function tag(): string
    {
        return 'rect';
    }
}
