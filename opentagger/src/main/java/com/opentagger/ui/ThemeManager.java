package com.opentagger.ui;

import com.formdev.flatlaf.FlatLaf;
import com.opentagger.Config;
import com.opentagger.I18n;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Insets;
import java.util.List;

/**
 * Thèmes de l'interface — jusqu'ici {@code FlatDarkLaf} était codé en dur dans
 * {@code MainFrame.launch()}, sans aucun choix possible (demande utilisateur 2026-09-07 :
 * "j'aimerais changer de template").
 *
 * <p>Deux couches bien distinctes, c'est tout l'intérêt de les séparer ici :
 * <ul>
 *   <li>le THÈME lui-même (palette complète fournie par FlatLaf ou le pack IntelliJ) — remplaçable
 *       à chaud, c'est ce que l'utilisateur choisit ;</li>
 *   <li>les retouches PROPRES à l'appli ({@link #applyAppTweaks}) — angles arrondis, ascenseurs
 *       fins, accent turquoise… — réappliquées par-dessus CHAQUE thème, sinon changer de thème
 *       ferait perdre toute l'identité visuelle patiemment construite (voir l'historique détaillé
 *       dans applyAppTweaks()).</li>
 * </ul>
 *
 * <p>Les retouches qui codaient en dur des gris sombres (fonds de boutons, lignes alternées du
 * tableau) sont conditionnées à {@link FlatLaf#isLafDark()} : appliquées telles quelles sur un thème
 * clair, elles produisaient des boutons gris anthracite sur fond blanc — le genre de détail qui fait
 * passer un thème clair pour "cassé" alors que seul le vernis maison était en cause.
 */
public final class ThemeManager {

    private ThemeManager() {}

    /** Accent maison (turquoise) — conservé sur TOUS les thèmes : les composants dessinés à la main
     *  (chips de statut, en-tête, boutons principaux) l'utilisent en dur dans leur code, un thème
     *  qui imposerait son propre accent créerait deux accents concurrents à l'écran. */
    static final Color ACCENT = new Color(0x4DB6AC);

    public record Theme(String id, String label, String className) {}

    public static final String DEFAULT_ID = "dark";

    /** Liste volontairement curatée (~20 sur les 48 disponibles dans le pack) — un menu de 48
     *  entrées reproduirait exactement le problème de surcharge qu'on vient de corriger ailleurs.
     *  Noms de classes vérifiés un par un dans flatlaf-intellij-themes-3.4.jar. */
    public static final List<Theme> THEMES = List.of(
        // ── Thèmes de base FlatLaf (aucune dépendance au pack) ──────────────────
        new Theme("dark",      "Sombre (défaut)",   "com.formdev.flatlaf.FlatDarkLaf"),
        new Theme("light",     "Clair",             "com.formdev.flatlaf.FlatLightLaf"),
        new Theme("darcula",   "Darcula",           "com.formdev.flatlaf.FlatDarculaLaf"),
        new Theme("intellij",  "IntelliJ (clair)",  "com.formdev.flatlaf.FlatIntelliJLaf"),

        // ── Sombres ─────────────────────────────────────────────────────────────
        new Theme("nord",         "Nord",             "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme"),
        new Theme("dracula",      "Dracula",          "com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme"),
        new Theme("onedark",      "One Dark",         "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme"),
        new Theme("arcdark",      "Arc Dark",         "com.formdev.flatlaf.intellijthemes.FlatArcDarkIJTheme"),
        new Theme("carbon",       "Carbon",           "com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme"),
        new Theme("cobalt2",      "Cobalt 2",         "com.formdev.flatlaf.intellijthemes.FlatCobalt2IJTheme"),
        new Theme("gruvbox",      "Gruvbox Dark",     "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkHardIJTheme"),
        new Theme("monokaipro",   "Monokai Pro",      "com.formdev.flatlaf.intellijthemes.FlatMonokaiProIJTheme"),
        new Theme("xcodedark",    "Xcode Dark",       "com.formdev.flatlaf.intellijthemes.FlatXcodeDarkIJTheme"),
        new Theme("solarizeddark","Solarized Dark",   "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme"),
        new Theme("spacegray",    "Spacegray",        "com.formdev.flatlaf.intellijthemes.FlatSpacegrayIJTheme"),
        new Theme("materialdark", "Material Darker",  "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialDarkerIJTheme"),
        new Theme("palenight",    "Material Palenight","com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialPalenightIJTheme"),
        new Theme("deepocean",    "Material Deep Ocean","com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialDeepOceanIJTheme"),
        new Theme("githubdark",   "GitHub Dark",      "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme"),
        new Theme("nightowl",     "Night Owl",        "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatNightOwlIJTheme"),

        // ── Clairs ──────────────────────────────────────────────────────────────
        new Theme("arc",           "Arc (clair)",       "com.formdev.flatlaf.intellijthemes.FlatArcIJTheme"),
        new Theme("solarizedlight","Solarized Light",   "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme"),
        new Theme("github",        "GitHub (clair)",    "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubIJTheme"),
        new Theme("lightowl",      "Light Owl",         "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatLightOwlIJTheme"),
        new Theme("materiallight", "Material Lighter",  "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialLighterIJTheme")
    );

    public static String currentId() {
        String id = Config.get().str("ui.theme", DEFAULT_ID);
        return THEMES.stream().anyMatch(t -> t.id().equals(id)) ? id : DEFAULT_ID;
    }

    /** Installe le thème enregistré — appelé UNE fois avant la construction de la moindre fenêtre
     *  (voir MainFrame.launch()), donc sans rafraîchissement à chaud à ce stade. */
    public static void applyStartup() {
        install(currentId());
    }

    /** Change de thème à chaud et le retient. Toutes les fenêtres ouvertes sont rafraîchies —
     *  y compris les dialogues déjà affichés, d'où {@link FlatLaf#updateUI()} plutôt qu'un
     *  SwingUtilities.updateComponentTreeUI() sur la seule fenêtre principale. */
    public static void apply(String id) {
        install(id);
        Config.get().set("ui.theme", id);
        FlatLaf.updateUI();
    }

    private static void install(String id) {
        Theme theme = THEMES.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElse(THEMES.get(0));
        try {
            UIManager.setLookAndFeel(theme.className());
        } catch (Exception e) {
            // Thème indisponible (pack absent d'un build allégé, classe renommée en amont) : le
            // thème sombre de base fait partie du coeur FlatLaf, il ne peut pas manquer lui.
            System.err.println("[Theme] " + theme.id() + " indisponible (" + e + ") — repli sur le thème sombre");
            try { UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf"); }
            catch (Exception ignored) {}
        }
        applyAppTweaks();
    }

    /**
     * Retouches maison réappliquées par-dessus le thème choisi — {@code setLookAndFeel()} réinitialise
     * TOUS les défauts UIManager, donc l'ordre compte : thème d'abord, ces valeurs ensuite.
     *
     * <p>Historique (conservé du code d'origine) : FlatDarkLaf tournait avec ses réglages par défaut,
     * et le look "moderne" de l'appli venait uniquement de retouches composant par composant — les
     * contrôles Swing standards (JComboBox, JSpinner, JScrollBar, JTabbedPane, cases à cocher)
     * gardaient l'angle droit et l'accent bleu par défaut, d'où l'impression d'ensemble "datée"
     * malgré les retouches locales.
     */
    static void applyAppTweaks() {
        boolean dark = FlatLaf.isLafDark();

        // Accent unique = le teal des boutons principaux — sinon coches/radios/curseurs/barres de
        // progression gardent l'accent du thème, en désaccord avec les composants dessinés à la main.
        UIManager.put("Component.accentColor", ACCENT);
        UIManager.put("Component.focusColor",  ACCENT);

        // FlatLaf mappe par défaut la coche des JCheckBoxMenuItem sur "@buttonArrowColor" — la même
        // teinte grise très discrète que les petites flèches de ComboBox/Spinner. Adaptée à une
        // flèche décorative, illisible pour une coche censée dire "activé" au premier coup d'œil
        // (signalé en direct : "impossible de savoir que c'est des cases à cocher").
        UIManager.put("CheckBoxMenuItem.icon.checkmarkColor", ACCENT);
        UIManager.put("CheckBoxMenuItem.icon.disabledCheckmarkColor", ACCENT.darker());

        // Angles arrondis uniformes — FlatLaf par défaut est presque à angle droit (arc=4), ce qui
        // lit "utilitaire des années 2010" à côté d'un Cover Flow et de chips déjà bien travaillés.
        UIManager.put("Component.arc",   10);
        UIManager.put("Button.arc",      10);
        UIManager.put("ProgressBar.arc", 999); // pilule complète, pas juste arrondie
        UIManager.put("CheckBox.arc",    4);

        // Ascenseurs fins et arrondis façon navigateur moderne — ceux par défaut sont larges et
        // carrés, très visibles sur le grand tableau principal (des dizaines de milliers de lignes
        // chez cet utilisateur, donc un ascenseur omniprésent à l'écran).
        UIManager.put("ScrollBar.width",       11);
        UIManager.put("ScrollBar.thumbArc",    999);
        UIManager.put("ScrollBar.trackArc",    999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 3, 2, 3));
        UIManager.put("ScrollBar.showButtons", false);

        // Séparateurs d'onglets nets (Préférences a 9 onglets à plat) — sans ça l'onglet actif ne se
        // distingue que par une fine ligne de soulignement, ambigu au-delà de 4-5 onglets.
        UIManager.put("TabbedPane.showTabSeparators",       true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);

        // Un peu plus d'air dans les boutons — le texte touchait presque les bords par défaut.
        UIManager.put("Button.margin", new Insets(4, 12, 4, 12));

        // Relief 3D global (retour utilisateur 2026-08-10) : sans ça chaque bouton "nu" d'un dialogue
        // non retouché individuellement paraissait plat. Les COULEURS ci-dessous sont en revanche
        // celles d'un fond sombre — sur un thème clair elles donneraient des boutons anthracite sur
        // blanc, donc on laisse le thème clair gérer ses propres fonds (ses boutons ont déjà une
        // bordure visible, contrairement aux thèmes sombres).
        UIManager.put("Button.borderWidth",       1);
        UIManager.put("ToggleButton.borderWidth", 1);
        UIManager.put("ToggleButton.selectedBackground", ACCENT);
        if (dark) {
            UIManager.put("Table.alternateRowColor",        new Color(45, 47, 52));
            UIManager.put("Button.background",              new Color(0x3A3A3E));
            UIManager.put("Button.borderColor",             new Color(0x4A4A4E));
            UIManager.put("Button.hoverBackground",         new Color(0x45454A));
            UIManager.put("Button.pressedBackground",       new Color(0x2E2E32));
            UIManager.put("ToggleButton.background",        new Color(0x3A3A3E));
            UIManager.put("ToggleButton.borderColor",       new Color(0x4A4A4E));
            UIManager.put("ToggleButton.hoverBackground",   new Color(0x45454A));
            UIManager.put("ToggleButton.pressedBackground", new Color(0x2E2E32));
        }
    }

    /** Sous-menu "Thème" (Affichage) — un bouton radio par thème, application immédiate. */
    public static javax.swing.JMenu buildMenu() {
        javax.swing.JMenu menu = new javax.swing.JMenu(I18n.t("Thème"));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        String current = currentId();
        for (Theme t : THEMES) {
            javax.swing.JRadioButtonMenuItem item =
                    new javax.swing.JRadioButtonMenuItem(t.label(), t.id().equals(current));
            item.addActionListener(e -> apply(t.id()));
            group.add(item);
            menu.add(item);
            // Séparateur après les 4 thèmes de base, puis avant les thèmes clairs — repères visuels
            // dans une liste de ~25 entrées qui serait sinon un mur indifférencié.
            if (t.id().equals("intellij") || t.id().equals("nightowl")) menu.addSeparator();
        }
        return menu;
    }
}
