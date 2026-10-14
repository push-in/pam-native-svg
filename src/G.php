<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use InvalidArgumentException;

/**
 * Group: children inherit its presentation attributes; opacity and
 * transform apply to the whole group (one compositing layer natively).
 */
final class G extends Node
{
    /** @var list<Node> */
    private array $children = [];

    public static function make(Node ...$children): self
    {
        return (new self())->add(...$children);
    }

    public function add(Node ...$children): self
    {
        $copy = clone $this;
        foreach ($children as $child) {
            $copy->children[] = $child;
        }
        if (count($copy->children) > Svg::MAX_NODES) {
            throw new InvalidArgumentException('SVG groups are limited to '.Svg::MAX_NODES.' children.');
        }

        return $copy;
    }

    protected function tag(): string
    {
        return 'g';
    }

    protected function content(): string
    {
        return implode('', array_map(static fn (Node $child): string => $child->toMarkup(), $this->children));
    }
}
