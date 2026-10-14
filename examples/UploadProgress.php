<?php

declare(strict_types=1);

namespace App\Examples;

use Pam\Native\Component;
use Pam\Native\Svg\Path;
use Pam\Native\Svg\ProgressRing;
use Pam\Native\Svg\Svg;
use Pam\Native\Svg\SvgAnimation;
use Pam\Native\Svg\SvgEasing;
use Pam\Native\Svg\SvgProperty;
use Pam\Native\UI\Row;

/**
 * Upload chip: a native progress ring that animates on the render thread,
 * a checkmark that draws itself when the upload completes, and an icon
 * loaded once from disk and tinted per theme.
 */
final class UploadProgress extends Component
{
    public float $fraction = 0.0;
    public bool $done = false;

    public function render(): \Pam\Native\Renderable
    {
        $check = Svg::make(24, 24)
            ->add(Path::make('M5 12l5 5L19 7')->id('check')->fill('none')->stroke('#ffffff', 2.5)->pathLength(1)->trim(0, 0))
            ->size(24)
            ->animate(
                SvgAnimation::to('check', SvgProperty::TrimEnd, $this->done ? 1.0 : 0.0)
                    ->duration(260)->easing(SvgEasing::EaseOut),
            );

        return Row::make(
            ProgressRing::make($this->fraction)->size(44)->strokeWidth(3)->color('#ffffff')->trackColor('#ffffff40')
                ->accessibilityLabel('Enviando'),
            $check,
            Svg::fromFile(__DIR__.'/assets/send.svg')->tint('#1d9bf0')->size(20),
        );
    }
}
