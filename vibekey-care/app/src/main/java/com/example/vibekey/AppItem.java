package com.example.vibekey;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/** 휴대폰에 설치된 앱 하나를 나타냅니다. */
public class AppItem {
    public final String label;
    public final String packageName;
    public final Drawable icon;

    public AppItem(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }

    @NonNull
    @Override
    public String toString() {
        return label;
    }
}
