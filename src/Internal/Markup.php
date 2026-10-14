<?php

declare(strict_types=1);

namespace Pam\Native\Svg\Internal;

use InvalidArgumentException;

/**
 * @internal number formatting, escaping and validation shared by the builders
 */
final class Markup
{
    private const string COLOR = '/^(none|currentColor|transparent|#[0-9a-fA-F]{3,4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}'
        .'|rgba?\(\s*[0-9.]+%?\s*,\s*[0-9.]+%?\s*,\s*[0-9.]+%?\s*(,\s*[0-9.]+%?\s*)?\)|url\(#[A-Za-z_][A-Za-z0-9_.-]{0,63}\)|[a-z]{3,20})$/D';

    public static function number(float|int $value): string
    {
        if (!is_finite((float) $value)) {
            throw new InvalidArgumentException('SVG numbers must be finite.');
        }
        if (is_int($value)) {
            return (string) $value;
        }
        $text = rtrim(rtrim(sprintf('%.4F', $value), '0'), '.');

        return $text === '-0' || $text === '' ? '0' : $text;
    }

    /** @param list<float|int> $values */
    public static function numbers(array $values, string $separator = ' '): string
    {
        return implode($separator, array_map(self::number(...), $values));
    }

    public static function color(string $value): string
    {
        $value = trim($value);
        if (preg_match(self::COLOR, $value) !== 1) {
            throw new InvalidArgumentException("Invalid SVG color {$value}.");
        }

        return $value;
    }

    public static function id(string $id): string
    {
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/D', $id) !== 1) {
            throw new InvalidArgumentException("Invalid SVG id {$id}.");
        }

        return $id;
    }

    public static function attribute(string $value): string
    {
        return htmlspecialchars($value, ENT_QUOTES | ENT_XML1 | ENT_SUBSTITUTE, 'UTF-8');
    }

    /** @param array<string, string> $attributes */
    public static function element(string $name, array $attributes, string $children = ''): string
    {
        $markup = '<'.$name;
        foreach ($attributes as $key => $value) {
            $markup .= ' '.$key.'="'.self::attribute($value).'"';
        }

        return $children === '' ? $markup.'/>' : $markup.'>'.$children.'</'.$name.'>';
    }

    public static function unit(float $value, string $label): float
    {
        if (!is_finite($value) || $value < 0.0 || $value > 1.0) {
            throw new InvalidArgumentException("{$label} must be between 0 and 1.");
        }

        return $value;
    }
}
