<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Svg\Internal\Markup;

/** Clip region referenced by {@see Node::clipPath()}. Its shapes are never painted. */
final readonly class ClipPath implements SvgNode
{
    /** @var list<Node> */
    private array $shapes;

    private function __construct(private string $id, Node ...$shapes)
    {
        $this->shapes = array_values($shapes);
    }

    public static function make(string $id, Node ...$shapes): self
    {
        return new self(Markup::id($id), ...$shapes);
    }

    public function toMarkup(): string
    {
        return '<defs>'.Markup::element(
            'clipPath',
            ['id' => $this->id],
            implode('', array_map(static fn (Node $shape): string => $shape->toMarkup(), $this->shapes)),
        ).'</defs>';
    }
}
