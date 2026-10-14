<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/**
 * Anything that can be placed inside an {@see Svg} document.
 */
interface SvgNode
{
    public function toMarkup(): string;
}
