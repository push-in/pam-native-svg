<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** Timing curves evaluated natively for SvgAnimation. */
enum SvgEasing: int
{
    case Linear = 1;
    case EaseIn = 2;
    case EaseOut = 3;
    case EaseInOut = 4;
}
