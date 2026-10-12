package com.opentagger.ui;

import java.nio.file.Path;
import java.util.Map;

/** Accès de test à MainFrame.relocated() (package-privé). */
public final class MainFrameRelocateAccess {
    private MainFrameRelocateAccess() {}
    public static Path relocated(Path p, Map<Path, Path> moves) { return MainFrame.relocated(p, moves); }
}
