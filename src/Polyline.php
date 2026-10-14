<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/** Polyline through a list of [x, y] points. */
final class Polyline extends Node
{
    /** @param list<array{0: float|int, 1: float|int}> $points */
    public static function make(array $points): self
    {
        if (count($points) < 2 || count($points) > 4_096) {
            throw new InvalidArgumentException('Polyline requires between 2 and 4096 points.');
        }
        $pairs = [];
        foreach ($points as $point) {
            if (!is_array($point) || count($point) !== 2) {
                throw new InvalidArgumentException('Polyline points must be [x, y] pairs.');
            }
            $pairs[] = Markup::number((float) $point[0]).','.Markup::number((float) $point[1]);
        }
        $node = new self();
        $node->attributes['points'] = implode(' ', $pairs);

        return $node;
    }

    protected function tag(): string
    {
        return 'polyline';
    }
}
