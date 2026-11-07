package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.ui.LibraryChecks.Check;

import java.util.Locale;

/**
 * Mode de chaque règle de l'onglet « À corriger », comme les règles de Bliss (demande de l'utilisateur, 2026-11-07 :
 * « SongKong + Bliss = OpenTagger ») : <b>désactivée</b> (pas vérifiée), <b>manuelle</b> (constat, puis « Corriger » avec
 * aperçu) ou <b>automatique</b> (corrigée sans demander après chaque scan, chaque correction annulable par Édition →
 * Annuler un enregistrement). Manuelle par défaut : rien ne change tant que l'utilisateur ne choisit pas « automatique ».
 */
final class RuleModes {

    private RuleModes() {}

    enum Mode {
        OFF, MANUAL, AUTO;

        String label() {
            return switch (this) {
                case OFF -> I18n.t("Désactivée");
                case MANUAL -> I18n.t("Manuelle");
                case AUTO -> I18n.t("Automatique");
            };
        }
    }

    private static String key(Check c) { return "rules.mode." + c.name().toLowerCase(Locale.ROOT); }

    static Mode get(Check c) {
        String v = Config.get().str(key(c), "").trim().toUpperCase(Locale.ROOT);
        try {
            Mode m = v.isEmpty() ? Mode.MANUAL : Mode.valueOf(v);
            return m == Mode.AUTO && !canAuto(c) ? Mode.MANUAL : m;
        } catch (IllegalArgumentException e) {
            return Mode.MANUAL;
        }
    }

    static void set(Check c, Mode m) {
        Config.get().set(key(c), (m == Mode.AUTO && !canAuto(c) ? Mode.MANUAL : m).name().toLowerCase(Locale.ROOT));
    }

    /** Les règles qui savent corriger seules ; les autres ne font qu'un constat (à identifier, à vérifier à l'œil). */
    static boolean canAuto(Check c) {
        return switch (c) {
            case NO_TITLE, NO_ARTIST, NO_ALBUM, COVER_MISMATCH, MISSING_TRACKS -> false;
            default -> true;
        };
    }

    static boolean anyAuto() {
        for (Check c : Check.values()) if (get(c) == Mode.AUTO) return true;
        return false;
    }
}
