<?php

declare(strict_types=1);

// Regenerates the PHP-built fixtures parsed by the Android JVM tests and the iOS core test.
// Usage: pam tests/fixtures/generate.php

require dirname(__DIR__).'/bootstrap.php';

use Pam\Native\Svg\ProgressRing;

file_put_contents(__DIR__.'/progress-ring.svg', ProgressRing::make(0.3)->size(44)->strokeWidth(3)->toSvg()->toMarkup()."\n");
file_put_contents(__DIR__.'/spinner.svg', ProgressRing::indeterminate()->size(24)->toSvg()->toMarkup()."\n");
