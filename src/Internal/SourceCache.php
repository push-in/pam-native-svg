<?php

declare(strict_types=1);

namespace Pam\Native\Svg\Internal;

/**
 * @internal process-wide bounded LRU of validated SVG sources keyed by
 * content hash, plus a file index keyed by path, mtime and size so icons
 * read from disk are hashed and validated once per worker.
 */
final class SourceCache
{
    public const int MAX_ENTRIES = 128;
    public const int MAX_BYTES = 4 * 1024 * 1024;

    /** @var array<string, string> hash => source (insertion order = LRU order) */
    private static array $sources = [];

    /** @var array<string, string> file fingerprint => hash */
    private static array $files = [];

    private static int $bytes = 0;

    public static function hash(string $source): string
    {
        return hash('xxh128', $source);
    }

    public static function get(string $hash): ?string
    {
        if (!isset(self::$sources[$hash])) {
            return null;
        }
        $source = self::$sources[$hash];
        unset(self::$sources[$hash]);
        self::$sources[$hash] = $source;

        return $source;
    }

    public static function put(string $hash, string $source): void
    {
        if (isset(self::$sources[$hash])) {
            self::get($hash);

            return;
        }
        self::$sources[$hash] = $source;
        self::$bytes += strlen($source);
        while (count(self::$sources) > self::MAX_ENTRIES || self::$bytes > self::MAX_BYTES) {
            $oldest = array_key_first(self::$sources);
            if ($oldest === null) {
                break;
            }
            self::$bytes -= strlen(self::$sources[$oldest]);
            unset(self::$sources[$oldest]);
            self::$files = array_filter(self::$files, static fn (string $hash): bool => $hash !== $oldest);
        }
    }

    public static function fileHash(string $fingerprint): ?string
    {
        $hash = self::$files[$fingerprint] ?? null;

        return $hash !== null && isset(self::$sources[$hash]) ? $hash : null;
    }

    public static function rememberFile(string $fingerprint, string $hash): void
    {
        self::$files[$fingerprint] = $hash;
        if (count(self::$files) > self::MAX_ENTRIES) {
            unset(self::$files[array_key_first(self::$files)]);
        }
    }

    /** @return array{entries: int, bytes: int, files: int} */
    public static function stats(): array
    {
        return ['entries' => count(self::$sources), 'bytes' => self::$bytes, 'files' => count(self::$files)];
    }

    public static function clear(): void
    {
        self::$sources = [];
        self::$files = [];
        self::$bytes = 0;
    }
}
