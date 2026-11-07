package dev.dialogmenu;

/** 字体几何同时用于可见字形、换行与原生命中。 */
record TextStyle(int size, double x, double y) {
    TextStyle {
        if (size < 1 || size > 128 || !Double.isFinite(x) || !Double.isFinite(y) || x <= 0 || y <= 0
            || Math.round(size * y) < 1 || Math.round(size * y) > 128)
            throw new IllegalArgumentException("拉伸后文字高度须为1..128，scale.x/y须为有限正数");
        if (size * x > 512)
            throw new IllegalArgumentException("拉伸后文字水平尺寸须不超过512");
    }

    boolean plain() { return x == 1 && y == 1; }
    int height() { return (int) Math.round(size * y); }
    int space() { return (int) Math.round(size * x / 2); }
    int stride() { return height() + Math.max(1, (int) Math.round(y)); }
    String name() {
        return "text_rgba_" + size + (plain() ? "" : "_x"
            + Long.toHexString(Double.doubleToLongBits(x)) + "_y"
            + Long.toHexString(Double.doubleToLongBits(y)));
    }
}
