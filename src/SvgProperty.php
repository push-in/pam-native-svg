<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

/** Node properties the native renderer can animate without re-rendering PHP. */
enum SvgProperty: int
{
    case Opacity = 1;
    case StrokeDashoffset = 2;
    case TrimStart = 3;
    case TrimEnd = 4;
    case Rotation = 5;
    case Scale = 6;
    case TranslateX = 7;
    case TranslateY = 8;
    case StrokeWidth = 9;
}
