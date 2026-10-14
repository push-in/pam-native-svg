<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Element;
use Pam\Native\Renderable;
use Pam\Native\Svg\Internal\Markup;

/**
 * Circular progress (upload, download, audio) drawn by svg.view.
 *
 * The markup never contains the progress value, so its hash stays stable and
 * the native side parses it once; each new progress only updates the native
 * TrimEnd target and the arc animates on the render thread.
 *
 * ```php
 * ProgressRing::make($upload->fraction)->size(44)->strokeWidth(3)->color('#ffffff');
 * ProgressRing::indeterminate()->size(24);
 * ```
 */
final class ProgressRing implements Renderable
{
    public const string ARC_ID = 'pam-progress';
    public const string SPINNER_ID = 'pam-spinner';

    private float $size = 40.0;
    private float $strokeWidth = 3.0;
    private string $color = '#ffffff';
    private ?string $trackColor = '#ffffff40';
    private int $duration = 200;
    private bool $spinning = false;
    private LineCap $cap = LineCap::Round;
    private ?string $label = null;

    private function __construct(private float $progress)
    {
    }

    public static function make(float $progress): self
    {
        return new self(self::clamp($progress));
    }

    public static function indeterminate(): self
    {
        $ring = new self(0.25);
        $ring->spinning = true;

        return $ring;
    }

    public function progress(float $progress): self
    {
        $copy = clone $this;
        $copy->progress = self::clamp($progress);

        return $copy;
    }

    public function size(float $size): self
    {
        $copy = clone $this;
        $copy->size = max(1.0, $size);

        return $copy;
    }

    public function strokeWidth(float $width): self
    {
        $copy = clone $this;
        $copy->strokeWidth = max(0.5, $width);

        return $copy;
    }

    public function color(string $color): self
    {
        $copy = clone $this;
        $copy->color = Markup::color($color);

        return $copy;
    }

    public function trackColor(?string $color): self
    {
        $copy = clone $this;
        $copy->trackColor = $color === null ? null : Markup::color($color);

        return $copy;
    }

    public function duration(int $milliseconds): self
    {
        $copy = clone $this;
        $copy->duration = max(0, min(SvgAnimation::MAX_DURATION, $milliseconds));

        return $copy;
    }

    public function lineCap(LineCap $cap): self
    {
        $copy = clone $this;
        $copy->cap = $cap;

        return $copy;
    }

    public function accessibilityLabel(string $label): self
    {
        $copy = clone $this;
        $copy->label = $label;

        return $copy;
    }

    public function toSvg(): Svg
    {
        $center = $this->size / 2;
        $radius = max(0.5, ($this->size - $this->strokeWidth) / 2);
        $arc = Circle::make($center, $center, $radius)
            ->id(self::ARC_ID)
            ->fill('none')
            ->stroke($this->color, $this->strokeWidth)
            ->strokeLinecap($this->cap)
            ->trim(0.0, $this->spinning ? 0.25 : 0.0)
            ->transform(Transform::rotate(-90, $center, $center));
        $nodes = [];
        if ($this->trackColor !== null) {
            $nodes[] = Circle::make($center, $center, $radius)->fill('none')->stroke($this->trackColor, $this->strokeWidth);
        }
        $nodes[] = G::make($arc)->id(self::SPINNER_ID);

        $svg = Svg::make($this->size, $this->size)->add(...$nodes)->size($this->size);
        $svg = $this->spinning
            ? $svg->animate(
                SvgAnimation::to(self::SPINNER_ID, SvgProperty::Rotation, 360)->from(0)->duration(900)
                    ->easing(SvgEasing::Linear)->repeatForever()->origin($center, $center),
            )
            : $svg->animate(
                SvgAnimation::to(self::ARC_ID, SvgProperty::TrimEnd, $this->progress)
                    ->duration($this->duration)->easing(SvgEasing::EaseOut),
            );

        return $this->label === null ? $svg : $svg->accessibilityLabel($this->label);
    }

    public function toElement(): Element
    {
        return $this->toSvg()->toElement();
    }

    private static function clamp(float $value): float
    {
        return is_finite($value) ? max(0.0, min(1.0, $value)) : 0.0;
    }
}
