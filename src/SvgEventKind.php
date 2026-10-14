<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** Events emitted by the native svg.view. */
enum SvgEventKind: int
{
    case AnimationEnd = 1;
}
