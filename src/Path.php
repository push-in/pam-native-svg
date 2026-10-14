<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/**
 * Arbitrary SVG path. Pass `d` path data directly (all commands, including
 * arcs, are supported natively) or build it fluently:
 *
 * ```php
 * Path::make()->moveTo(4, 12)->lineTo(10, 18)->lineTo(20, 6)->stroke('#fff', 2);
 * Path::make('M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20z');
 * ```
 */
final class Path extends Node
{
    public const int MAX_DATA_BYTES = 65_536;

    private string $data = '';

    public static function make(string $d = ''): self
    {
        $node = new self();

        return $d === '' ? $node : $node->data($d);
    }

    public function data(string $d): self
    {
        $d = trim($d);
        if (strlen($d) > self::MAX_DATA_BYTES) {
            throw new InvalidArgumentException('Path data is limited to 64 KiB.');
        }
        if ($d !== '' && preg_match('/^[MmLlHhVvCcSsQqTtAaZzEe0-9.,+\-\s]+$/D', $d) !== 1) {
            throw new InvalidArgumentException('Path data contains invalid characters.');
        }
        $copy = clone $this;
        $copy->data = $d;
        $copy->attributes['d'] = $d;

        return $copy;
    }

    public function moveTo(float $x, float $y): self
    {
        return $this->append('M'.Markup::numbers([$x, $y]));
    }

    public function lineTo(float $x, float $y): self
    {
        return $this->append('L'.Markup::numbers([$x, $y]));
    }

    public function horizontalTo(float $x): self
    {
        return $this->append('H'.Markup::number($x));
    }

    public function verticalTo(float $y): self
    {
        return $this->append('V'.Markup::number($y));
    }

    public function cubicTo(float $x1, float $y1, float $x2, float $y2, float $x, float $y): self
    {
        return $this->append('C'.Markup::numbers([$x1, $y1, $x2, $y2, $x, $y]));
    }

    public function quadTo(float $x1, float $y1, float $x, float $y): self
    {
        return $this->append('Q'.Markup::numbers([$x1, $y1, $x, $y]));
    }

    public function arcTo(float $rx, float $ry, float $rotation, bool $largeArc, bool $sweep, float $x, float $y): self
    {
        return $this->append('A'.Markup::numbers([$rx, $ry, $rotation, (int) $largeArc, (int) $sweep, $x, $y]));
    }

    public function close(): self
    {
        return $this->append('Z');
    }

    public function d(): string
    {
        return $this->data;
    }

    protected function tag(): string
    {
        return 'path';
    }

    private function append(string $segment): self
    {
        return $this->data($this->data === '' ? $segment : $this->data.' '.$segment);
    }
}
