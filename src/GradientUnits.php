<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** SVG gradientUnits. */
enum GradientUnits: int
{
    case ObjectBoundingBox = 1;
    case UserSpaceOnUse = 2;
}
