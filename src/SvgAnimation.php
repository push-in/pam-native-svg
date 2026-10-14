<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/**
 * Declarative native animation of one property of one node (by id).
 *
 * The native view owns the timeline: PHP only sends the target. When the
 * target changes the property animates from its current on-screen value, so
 * pushing a new upload/audio progress never restarts the curve and never
 * re-renders PHP per frame. Identical specs are ignored on re-render.
 *
 * ```php
 * SvgAnimation::to('progress', SvgProperty::TrimEnd, $progress)->duration(200);
 * SvgAnimation::to('spinner', SvgProperty::Rotation, 360)->from(0)->duration(900)
 *     ->easing(SvgEasing::Linear)->repeatForever();
 * ```
 */
final readonly class SvgAnimation
{
    public const int MAX_DURATION = 60_000;

    private function __construct(
        public string $id,
        public SvgProperty $property,
        public float $to,
        public ?float $from = null,
        public int $duration = 250,
        public SvgEasing $easing = SvgEasing::EaseInOut,
        public int $delay = 0,
        public int $repeat = 0,
        public bool $reverse = false,
        public bool $animateOnMount = false,
        public ?float $originX = null,
        public ?float $originY = null,
    ) {
        foreach ([$to, $from ?? 0.0, $originX ?? 0.0, $originY ?? 0.0] as $value) {
            if (!is_finite($value)) {
                throw new InvalidArgumentException('Animation values must be finite.');
            }
        }
        if ($duration < 0 || $duration > self::MAX_DURATION || $delay < 0 || $delay > self::MAX_DURATION) {
            throw new InvalidArgumentException('Animation duration and delay must be between 0 and 60000 ms.');
        }
        if ($repeat < -1 || $repeat > 10_000) {
            throw new InvalidArgumentException('Animation repeat must be -1 (forever) or between 0 and 10000.');
        }
        if ($repeat === -1 && ($from === null || $duration === 0)) {
            throw new InvalidArgumentException('Infinite animations require from() and a positive duration.');
        }
    }

    public static function to(string $id, SvgProperty $property, float $value): self
    {
        return new self(Markup::id($id), $property, $value);
    }

    public function from(float $value): self
    {
        return $this->with(['from' => $value]);
    }

    public function duration(int $milliseconds): self
    {
        return $this->with(['duration' => $milliseconds]);
    }

    public function easing(SvgEasing $easing): self
    {
        return $this->with(['easing' => $easing]);
    }

    public function delay(int $milliseconds): self
    {
        return $this->with(['delay' => $milliseconds]);
    }

    public function repeat(int $times, bool $reverse = false): self
    {
        return $this->with(['repeat' => $times, 'reverse' => $reverse]);
    }

    public function repeatForever(bool $reverse = false): self
    {
        return $this->with(['repeat' => -1, 'reverse' => $reverse]);
    }

    /** Animate from the static/`from` value on first mount instead of jumping to the target. */
    public function animateOnMount(bool $enabled = true): self
    {
        return $this->with(['animateOnMount' => $enabled]);
    }

    /** Pivot for Rotation/Scale in viewBox units; defaults to the node's bounds center. */
    public function origin(float $x, float $y): self
    {
        return $this->with(['originX' => $x, 'originY' => $y]);
    }

    public function key(): string
    {
        return $this->id.':'.$this->property->value;
    }

    /** @return array<string, int|float|string|list<float>> */
    public function toArray(): array
    {
        $entry = [
            'i' => $this->id,
            'p' => $this->property->value,
            't' => $this->to,
            'd' => $this->duration,
            'e' => $this->easing->value,
        ];
        if ($this->from !== null) {
            $entry['f'] = $this->from;
        }
        if ($this->delay > 0) {
            $entry['w'] = $this->delay;
        }
        if ($this->repeat !== 0) {
            $entry['r'] = $this->repeat;
        }
        if ($this->reverse) {
            $entry['a'] = 1;
        }
        if ($this->animateOnMount) {
            $entry['m'] = 1;
        }
        if ($this->originX !== null) {
            $entry['o'] = [$this->originX, (float) $this->originY];
        }

        return $entry;
    }

    /** @param array<string, mixed> $changes */
    private function with(array $changes): self
    {
        $values = get_object_vars($this);
        foreach ($changes as $name => $value) {
            $values[$name] = $value;
        }

        /** @phpstan-ignore-next-line argument.type */
        return new self(...$values);
    }
}
