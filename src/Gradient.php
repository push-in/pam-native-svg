<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;
use Pam\Native\Svg\Internal\Markup;

/**
 * Base for linear and radial gradients. Reference them with
 * {@see Node::fillGradient()} / {@see Node::strokeGradient()}.
 */
abstract class Gradient implements SvgNode
{
    /** @var array<string, string> */
    protected array $attributes = [];

    /** @var list<Stop> */
    private array $stops = [];

    final protected function __construct(string $id)
    {
        $this->attributes['id'] = Markup::id($id);
    }

    abstract protected function tag(): string;

    final public function stops(Stop ...$stops): static
    {
        if (count($stops) < 1 || count($stops) > 32) {
            throw new InvalidArgumentException('Gradients require between 1 and 32 stops.');
        }
        $copy = clone $this;
        $copy->stops = array_values($stops);

        return $copy;
    }

    final public function units(GradientUnits $units): static
    {
        $copy = clone $this;
        $copy->attributes['gradientUnits'] = $units === GradientUnits::UserSpaceOnUse ? 'userSpaceOnUse' : 'objectBoundingBox';

        return $copy;
    }

    final public function gradientTransform(Transform $transform): static
    {
        $copy = clone $this;
        $copy->attributes['gradientTransform'] = $transform->toString();

        return $copy;
    }

    final public function toMarkup(): string
    {
        if ($this->stops === []) {
            throw new InvalidArgumentException('Gradients require at least one stop.');
        }

        return '<defs>'.Markup::element(
            $this->tag(),
            $this->attributes,
            implode('', array_map(static fn (Stop $stop): string => $stop->toMarkup(), $this->stops)),
        ).'</defs>';
    }
}
