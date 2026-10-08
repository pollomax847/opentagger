package com.opentagger.ui;

import com.opentagger.model.TagInfo;

/** Accès de test aux règles package-privées d'AutoDedup. */
public final class AutoDedupAccess {
    private AutoDedupAccess() {}
    public static boolean sameTrack(TagInfo a, TagInfo b) { return AutoDedup.sameTrack(a, b); }
    public static boolean durationsClose(int a, int b) { return AutoDedup.durationsClose(a, b); }
}
