<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** SVG stroke-linejoin. */
enum LineJoin: int
{
    case Miter = 1;
    case Round = 2;
    case Bevel = 3;
}
