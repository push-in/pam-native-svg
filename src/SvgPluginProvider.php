<?php

declare(strict_types=1);

namespace Pam\Native\Svg;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;

/**
 * Registers the declarative tags:
 *
 * ```html
 * <SvgIcon src="assets/icons/send.svg" tint="#1d9bf0" size="24" />
 * <SvgIcon :source="$markup" color="#fff" size="20" label="Enviar" />
 * <ProgressRing :progress="$fraction" size="44" stroke="3" color="#fff" track="#ffffff40" />
 * <ProgressRing indeterminate size="24" />
 * ```
 */
final class SvgPluginProvider implements PluginProvider
{
    public function register(): void
    {
        TemplateRegistry::component('SvgIcon', static function (array $props, array $_children, ?object $_scope): Svg {
            $source = $props['source'] ?? null;
            $svg = is_string($source) && $source !== ''
                ? Svg::fromString($source)
                : Svg::fromFile(self::path((string) ($props['src'] ?? '')));
            $size = self::number($props['size'] ?? null);
            if ($size !== null) {
                $svg = $svg->size($size, self::number($props['height'] ?? null));
            }
            if (is_string($props['tint'] ?? null) && $props['tint'] !== '') {
                $svg = $svg->tint($props['tint']);
            }
            if (is_string($props['color'] ?? null) && $props['color'] !== '') {
                $svg = $svg->color($props['color']);
            }
            if (is_string($props['label'] ?? null) && $props['label'] !== '') {
                $svg = $svg->accessibilityLabel($props['label']);
            }
            if (is_string($props['class'] ?? null) && trim($props['class']) !== '') {
                $svg = $svg->class($props['class']);
            }

            return $svg;
        });

        TemplateRegistry::component('ProgressRing', static function (array $props, array $_children, ?object $_scope): ProgressRing {
            $ring = self::flag($props['indeterminate'] ?? false)
                ? ProgressRing::indeterminate()
                : ProgressRing::make(self::number($props['progress'] ?? 0) ?? 0.0);
            foreach (['size' => 'size', 'stroke' => 'strokeWidth'] as $prop => $method) {
                $value = self::number($props[$prop] ?? null);
                if ($value !== null) {
                    $ring = $ring->{$method}($value);
                }
            }
            if (is_string($props['color'] ?? null) && $props['color'] !== '') {
                $ring = $ring->color($props['color']);
            }
            if (array_key_exists('track', $props)) {
                $ring = $ring->trackColor(is_string($props['track']) && $props['track'] !== '' ? $props['track'] : null);
            }
            $duration = self::number($props['duration'] ?? null);
            if ($duration !== null) {
                $ring = $ring->duration((int) $duration);
            }
            if (is_string($props['label'] ?? null) && $props['label'] !== '') {
                $ring = $ring->accessibilityLabel($props['label']);
            }

            return $ring;
        });
    }

    public function boot(): void
    {
    }

    private static function path(string $path): string
    {
        if ($path === '' || str_starts_with($path, '/')) {
            return $path;
        }
        $base = defined('PAM_NATIVE_APP_ROOT') && is_string(constant('PAM_NATIVE_APP_ROOT'))
            ? constant('PAM_NATIVE_APP_ROOT')
            : (getcwd() ?: '.');

        return rtrim($base, '/').'/'.$path;
    }

    private static function number(mixed $value): ?float
    {
        return is_int($value) || is_float($value) || (is_string($value) && is_numeric($value)) ? (float) $value : null;
    }

    private static function flag(mixed $value): bool
    {
        return is_bool($value) ? $value : filter_var($value, FILTER_VALIDATE_BOOL, FILTER_NULL_ON_FAILURE) ?? ($value === '');
    }
}
