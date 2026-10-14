<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** SVG fill-rule / clip-rule. */
enum FillRule: int
{
    case NonZero = 1;
    case EvenOdd = 2;
}
