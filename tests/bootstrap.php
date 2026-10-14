<?php

declare(strict_types=1);

// Shared autoloader: vendor/autoload.php when present, otherwise PAM_NATIVE_SRC or a
// sibling pam-native checkout for the Pam\Native\* framework classes.

$autoload = dirname(__DIR__).'/vendor/autoload.php';
if (is_file($autoload)) {
    require $autoload;
}
$frameworkCandidates = array_filter([
    getenv('PAM_NATIVE_SRC') ?: null,
    dirname(__DIR__, 2).'/pam-native/packages/native/src',
    dirname(__DIR__, 2).'/pam-ze-chat/vendor/pushinbr/pam-native/packages/native/src',
]);
$framework = null;
foreach ($frameworkCandidates as $candidate) {
    if (is_dir($candidate)) {
        $framework = $candidate;
        break;
    }
}
$roots = ['Pam\\Native\\Svg\\' => dirname(__DIR__).'/src/'];
if ($framework !== null) {
    $roots['Pam\\Native\\'] = rtrim($framework, '/').'/';
}
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) {
                require $file;
            }

            return;
        }
    }
}, true, true);
