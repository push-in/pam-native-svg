<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** How the viewBox is fitted into the view bounds (overrides preserveAspectRatio). */
enum SvgFit: int
{
    case Contain = 1;
    case Cover = 2;
    case Stretch = 3;
}
