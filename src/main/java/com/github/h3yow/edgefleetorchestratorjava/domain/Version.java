package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 语义化版本号（major.minor.patch），支持解析与按数值比较。
 * 不允许负号段；段数不足时按 0 补齐，多余非数字后缀将被拒绝。
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version segments must be non-negative: "
                    + major + "." + minor + "." + patch);
        }
    }

    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("firmware version must not be blank");
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("invalid firmware version (expect major[.minor[.patch]]): " + text);
        }
        int[] seg = new int[3];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.matches("\\d+")) {
                throw new IllegalArgumentException("invalid firmware version segment '" + p + "' in: " + text);
            }
            seg[i] = Integer.parseInt(p);
        }
        return new Version(seg[0], seg[1], seg[2]);
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
