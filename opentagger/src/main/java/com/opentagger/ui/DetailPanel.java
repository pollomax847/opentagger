package com.opentagger.ui;

import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Panneau de détail/édition avec 5 onglets — équivalent du Edit Pane de Jaikoz.
 *
 * Onglets :
 *  1. Général     — artiste, album, titre, année, genre, piste, disc
 *  2. Classique   — compositeur, chef, orchestre, work, movement, opus…
 *  3. Contributeurs — lyriciste, producteur, arrangeur, ingénieur
 *  4. Audio       — BPM, tonalité, langue, flags, rating, ISRC
 *  5. Paroles     — zone de texte + URL
 *  6. URLs & IDs  — MB IDs, AcoustID, Discogs, URLs officielles/Wikipedia
 */
public class DetailPanel extends JPanel {

    // ── Onglet 1 — Général ───────────────────────────────────────────────────
    private final JTextField tfTitle        = tf(24);
    private final JTextField tfArtist       = tf(24);
    private final JTextField tfAlbumArtist  = tf(24);
    private final JTextField tfAlbum        = tf(24);
    private final JTextField tfYear         = tf(6);
    private final JTextField tfGenre        = tf(16);
    private final JTextField tfTrack        = tf(5);
    private final JTextField tfTrackTotal   = tf(5);
    private final JTextField tfDiscNo       = tf(5);
    private final JTextField tfDiscTotal    = tf(5);

    // ── Onglet 2 — Classique ─────────────────────────────────────────────────
    private final JTextField tfComposer          = tf(24);
    private final JTextField tfComposerSort      = tf(24);
    private final JTextField tfConductor         = tf(24);
    private final JTextField tfOrchestra         = tf(24);
    private final JTextField tfEnsemble          = tf(24);
    private final JTextField tfChoir             = tf(24);
    private final JTextField tfWork              = tf(24);
    private final JTextField tfWorkMbid          = tf(24);
    private final JTextField tfMovement          = tf(24);
    private final JTextField tfMovementNo        = tf(5);
    private final JTextField tfMovementTotal     = tf(5);
    private final JTextField tfPart              = tf(24);
    private final JTextField tfPeriod            = tf(16);
    private final JTextField tfOpus              = tf(12);
    private final JTextField tfClassicalCatalog  = tf(12);
    private final JTextField tfClassicalNickname = tf(24);
    private final JTextField tfSection           = tf(24);
    private final JTextField tfOverallWork       = tf(24);
    private final JTextField tfGrouping          = tf(24);
    private final JCheckBox  chkClassical        = new JCheckBox("Musique classique");

    // ── Onglet 3 — Contributeurs ─────────────────────────────────────────────
    private final JTextField tfLyricist    = tf(24);
    private final JTextField tfProducer    = tf(24);
    private final JTextField tfArranger    = tf(24);
    private final JTextField tfEngineer    = tf(24);
    private final JTextField tfMixer       = tf(24);
    private final JTextField tfDjMixer     = tf(24);
    private final JTextField tfTitleSort       = tf(24);
    private final JTextField tfArtistSort      = tf(24);
    private final JTextField tfAlbumSort       = tf(24);
    private final JTextField tfAlbumArtistSort = tf(24);
    private final JTextField tfConductorSort   = tf(24);
    private final JTextField tfOrchestraSort   = tf(24);

    // ── Onglet 4 — Audio ─────────────────────────────────────────────────────
    private final JTextField tfBpm        = tf(8);
    private final JTextField tfKey        = tf(8);
    private final JTextField tfLanguage   = tf(8);
    private final JTextField tfRating     = tf(5);
    private final JTextField tfIsrc       = tf(16);
    private final JTextField tfAmazonId   = tf(16);
    private final JTextField tfTags       = tf(24);
    private final JCheckBox  chkHD             = new JCheckBox("HD (Hi-Res)");
    private final JCheckBox  chkLive           = new JCheckBox("Live");
    private final JCheckBox  chkCompilation    = new JCheckBox("Compilation");
    private final JCheckBox  chkGreatestHits   = new JCheckBox("Greatest Hits");
    private final JCheckBox  chkSoundtrack     = new JCheckBox("Bande originale");
    private final JCheckBox  chkInstrumental   = new JCheckBox("Instrumental");

    // Mood (lecture seule — remplis par Essentia, non éditables manuellement)
    private final JTextField tfMood            = tf(16);
    private final JTextField tfMoodAggressive  = tf(12);
    private final JTextField tfMoodAcoustic    = tf(12);
    private final JTextField tfMoodElectronic  = tf(12);
    private final JTextField tfMoodHappy       = tf(12);
    private final JTextField tfMoodSad         = tf(12);
    private final JTextField tfMoodRelaxed     = tf(12);
    private final JTextField tfMoodDance       = tf(12);
    private final JTextField tfMoodParty       = tf(12);

    // ── Onglet Pochette ──────────────────────────────────────────────────────
    private final JLabel     lblCoverTab  = new JLabel("Aucune pochette", SwingConstants.CENTER);
    private final JLabel     lblCoverInfo = new JLabel(" ", SwingConstants.CENTER);
    private Runnable         onCoverClick;

    // ── Onglet 5 — Paroles ───────────────────────────────────────────────────
    private final JTextArea  taLyrics    = new JTextArea(8, 40);
    private final JTextField tfLyricsUrl = tf(40);

    // ── Onglet 6 — URLs & IDs ────────────────────────────────────────────────
    private final JTextField tfArtistOfficialUrl   = tf(36);
    private final JTextField tfArtistWikipediaUrl  = tf(36);
    private final JTextField tfArtistDiscogsUrl    = tf(36);
    private final JTextField tfReleaseOfficialUrl  = tf(36);
    private final JTextField tfReleaseWikipediaUrl = tf(36);
    private final JTextField tfReleaseDiscogsUrl   = tf(36);
    private final JTextField tfRecordingMbid       = tf(36);
    private final JTextField tfReleaseMbid         = tf(36);
    private final JTextField tfReleaseGroupMbid    = tf(36);
    private final JTextField tfArtistMbid          = tf(36);
    private final JTextField tfAcoustidId          = tf(36);
    private final JTextField tfDiscogsId           = tf(20);
    private final JTextField tfRoonAlbumTag        = tf(20);
    private final JTextField tfRoonTrackTag        = tf(20);

    // ── Callbacks ────────────────────────────────────────────────────────────
    private Runnable onApply;

    // ── Mode multi-sélection ─────────────────────────────────────────────────
    private boolean           multiMode    = false;
    // Checkboxes à valeurs hétérogènes dans la sélection (ne pas écraser)
    private final Set<JCheckBox> indetermCbs = new HashSet<>();

    // ─────────────────────────────────────────────────────────────────────────

    public DetailPanel() {
        super(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Général",       buildGeneralTab());
        tabs.addTab("Pochette",      buildPochetteTab());
        tabs.addTab("Classique",     buildClassicalTab());
        tabs.addTab("Contributeurs", buildContribTab());
        tabs.addTab("Audio / Mood",  buildAudioTab());
        tabs.addTab("Paroles",       buildLyricsTab());
        tabs.addTab("URLs & IDs",    buildIdsTab());
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        // Champs mood en lecture seule
        for (JTextField tf : new JTextField[]{
            tfMood, tfMoodAggressive, tfMoodAcoustic, tfMoodElectronic,
            tfMoodHappy, tfMoodSad, tfMoodRelaxed, tfMoodDance, tfMoodParty}) {
            tf.setEditable(false);
            tf.putClientProperty("FlatLaf.style", "foreground: #8899bb");
        }
        // Champs MB IDs en lecture seule
        for (JTextField tf : new JTextField[]{
            tfRecordingMbid, tfReleaseMbid, tfReleaseGroupMbid,
            tfArtistMbid, tfAcoustidId}) {
            tf.setEditable(false);
            tf.putClientProperty("FlatLaf.style", "foreground: #8899bb");
        }
    }

    public void setOnApply(Runnable r) { this.onApply = r; }

    // ── Remplissage depuis TagInfo ────────────────────────────────────────────

    public void populate(TagInfo t) {
        multiMode = false;
        indetermCbs.clear();
        clearHighlights();
        // Général
        set(tfTitle,        t.title);
        set(tfArtist,       t.artist);
        set(tfAlbumArtist,  t.albumArtist);
        set(tfAlbum,        t.album);
        set(tfYear,         t.year);
        set(tfGenre,        t.genre);
        set(tfTrack,        t.track);
        set(tfTrackTotal,   t.trackTotal);
        set(tfDiscNo,       t.discNo);
        set(tfDiscTotal,    t.discTotal);

        // Classique
        set(tfComposer,          t.composer);
        set(tfComposerSort,      t.composerSort);
        set(tfConductor,         t.conductor);
        set(tfOrchestra,         t.orchestra);
        set(tfEnsemble,          t.ensemble);
        set(tfChoir,             t.choir);
        set(tfWork,              t.work);
        set(tfWorkMbid,          t.workMbid);
        set(tfMovement,          t.movement);
        set(tfMovementNo,        t.movementNo);
        set(tfMovementTotal,     t.movementTotal);
        set(tfPart,              t.part);
        set(tfPeriod,            t.period);
        set(tfOpus,              t.opus);
        set(tfClassicalCatalog,  t.classicalCatalog);
        set(tfClassicalNickname, t.classicalNickname);
        set(tfSection,           t.section);
        set(tfOverallWork,       t.overallWork);
        set(tfGrouping,          t.grouping);
        chkClassical.setSelected("1".equals(t.isClassical));

        // Contributeurs
        set(tfLyricist,       t.lyricist);
        set(tfProducer,       t.producer);
        set(tfArranger,       t.arranger);
        set(tfEngineer,       t.engineer);
        set(tfMixer,          t.mixer);
        set(tfDjMixer,        t.djMixer);
        set(tfTitleSort,      t.titleSort);
        set(tfArtistSort,     t.artistSort);
        set(tfAlbumSort,      t.albumSort);
        set(tfAlbumArtistSort, t.albumArtistSort);
        set(tfConductorSort,  t.conductorSort);
        set(tfOrchestraSort,  t.orchestraSort);

        // Audio / Mood
        set(tfBpm,       t.bpm);
        set(tfKey,       t.initialKey);
        set(tfLanguage,  t.language);
        set(tfRating,    t.rating);
        set(tfIsrc,      t.isrc);
        set(tfAmazonId,  t.amazonId);
        set(tfTags,      t.tags);
        chkHD          .setSelected("1".equals(t.isHD));
        chkLive        .setSelected("1".equals(t.isLive));
        chkCompilation .setSelected("1".equals(t.isCompilation));
        chkGreatestHits.setSelected("1".equals(t.isGreatestHits));
        chkSoundtrack  .setSelected("1".equals(t.isSoundtrack));
        chkInstrumental.setSelected("1".equals(t.isInstrumental));
        set(tfMood,           t.mood);
        set(tfMoodAggressive, t.moodAggressive);
        set(tfMoodAcoustic,   t.moodAcoustic);
        set(tfMoodElectronic, t.moodElectronic);
        set(tfMoodHappy,      t.moodHappy);
        set(tfMoodSad,        t.moodSad);
        set(tfMoodRelaxed,    t.moodRelaxed);
        set(tfMoodDance,      t.moodDanceability);
        set(tfMoodParty,      t.moodParty);

        // Paroles
        taLyrics.setText(t.lyrics);
        taLyrics.setCaretPosition(0);
        set(tfLyricsUrl, t.lyricsUrl);

        // URLs & IDs
        set(tfArtistOfficialUrl,   t.artistOfficialUrl);
        set(tfArtistWikipediaUrl,  t.artistWikipediaUrl);
        set(tfArtistDiscogsUrl,    t.artistDiscogsUrl);
        set(tfReleaseOfficialUrl,  t.releaseOfficialUrl);
        set(tfReleaseWikipediaUrl, t.releaseWikipediaUrl);
        set(tfReleaseDiscogsUrl,   t.releaseDiscogsUrl);
        set(tfRecordingMbid,       t.recordingMbid);
        set(tfReleaseMbid,         t.releaseMbid);
        set(tfReleaseGroupMbid,    t.releaseGroupMbid);
        set(tfArtistMbid,          t.artistMbid);
        set(tfAcoustidId,          t.acoustidId);
        set(tfDiscogsId,           t.discogsId);
        set(tfRoonAlbumTag,        t.roonAlbumTag);
        set(tfRoonTrackTag,        t.roonTrackTag);
    }

    /**
     * Mode multi-sélection : affiche les valeurs communes, laisse vide les champs hétérogènes.
     * En mode multi, collect() n'écrase QUE les champs non vides du formulaire.
     */
    public void populateMulti(List<TagInfo> tags) {
        multiMode = true;
        indetermCbs.clear();
        clearHighlights();
        // Général
        setM(tfTitle,       tags, t -> t.title);
        setM(tfArtist,      tags, t -> t.artist);
        setM(tfAlbumArtist, tags, t -> t.albumArtist);
        setM(tfAlbum,       tags, t -> t.album);
        setM(tfYear,        tags, t -> t.year);
        setM(tfGenre,       tags, t -> t.genre);
        setM(tfTrack,       tags, t -> t.track);
        setM(tfTrackTotal,  tags, t -> t.trackTotal);
        setM(tfDiscNo,      tags, t -> t.discNo);
        setM(tfDiscTotal,   tags, t -> t.discTotal);
        // Classique
        setM(tfComposer,          tags, t -> t.composer);
        setM(tfComposerSort,      tags, t -> t.composerSort);
        setM(tfConductor,         tags, t -> t.conductor);
        setM(tfOrchestra,         tags, t -> t.orchestra);
        setM(tfEnsemble,          tags, t -> t.ensemble);
        setM(tfChoir,             tags, t -> t.choir);
        setM(tfWork,              tags, t -> t.work);
        setM(tfWorkMbid,          tags, t -> t.workMbid);
        setM(tfMovement,          tags, t -> t.movement);
        setM(tfMovementNo,        tags, t -> t.movementNo);
        setM(tfMovementTotal,     tags, t -> t.movementTotal);
        setM(tfPart,              tags, t -> t.part);
        setM(tfPeriod,            tags, t -> t.period);
        setM(tfOpus,              tags, t -> t.opus);
        setM(tfClassicalCatalog,  tags, t -> t.classicalCatalog);
        setM(tfClassicalNickname, tags, t -> t.classicalNickname);
        setM(tfSection,           tags, t -> t.section);
        setM(tfOverallWork,       tags, t -> t.overallWork);
        setM(tfGrouping,          tags, t -> t.grouping);
        setMCb(chkClassical, tags, t -> "1".equals(t.isClassical));
        // Contributeurs
        setM(tfLyricist,       tags, t -> t.lyricist);
        setM(tfProducer,       tags, t -> t.producer);
        setM(tfArranger,       tags, t -> t.arranger);
        setM(tfEngineer,       tags, t -> t.engineer);
        setM(tfMixer,          tags, t -> t.mixer);
        setM(tfDjMixer,        tags, t -> t.djMixer);
        setM(tfTitleSort,      tags, t -> t.titleSort);
        setM(tfArtistSort,     tags, t -> t.artistSort);
        setM(tfAlbumSort,      tags, t -> t.albumSort);
        setM(tfAlbumArtistSort, tags, t -> t.albumArtistSort);
        setM(tfConductorSort,  tags, t -> t.conductorSort);
        setM(tfOrchestraSort,  tags, t -> t.orchestraSort);
        // Audio
        setM(tfBpm,      tags, t -> t.bpm);
        setM(tfKey,      tags, t -> t.initialKey);
        setM(tfLanguage, tags, t -> t.language);
        setM(tfRating,   tags, t -> t.rating);
        setM(tfIsrc,     tags, t -> t.isrc);
        setM(tfAmazonId, tags, t -> t.amazonId);
        setM(tfTags,     tags, t -> t.tags);
        setMCb(chkHD,           tags, t -> "1".equals(t.isHD));
        setMCb(chkLive,         tags, t -> "1".equals(t.isLive));
        setMCb(chkCompilation,  tags, t -> "1".equals(t.isCompilation));
        setMCb(chkGreatestHits, tags, t -> "1".equals(t.isGreatestHits));
        setMCb(chkSoundtrack,   tags, t -> "1".equals(t.isSoundtrack));
        setMCb(chkInstrumental, tags, t -> "1".equals(t.isInstrumental));
        // Paroles, IDs — laisser vide en mode multi
        taLyrics.setText("");
        set(tfLyricsUrl, "");
        for (JTextField tf : new JTextField[]{
            tfArtistOfficialUrl, tfArtistWikipediaUrl, tfArtistDiscogsUrl,
            tfReleaseOfficialUrl, tfReleaseWikipediaUrl, tfReleaseDiscogsUrl,
            tfRecordingMbid, tfReleaseMbid, tfReleaseGroupMbid, tfArtistMbid,
            tfAcoustidId, tfDiscogsId, tfRoonAlbumTag, tfRoonTrackTag}) {
            tf.setText("");
        }
    }

    // ── Helpers pour le mode multi ────────────────────────────────────────────

    private void setM(JTextField tf, List<TagInfo> tags, Function<TagInfo, String> getter) {
        Set<String> vals = new HashSet<>();
        for (TagInfo t : tags) vals.add(getter.apply(t));
        if (vals.size() == 1) {
            tf.setText(vals.iterator().next());
        } else {
            tf.setText("");
            tf.putClientProperty("JTextField.placeholderText", "— valeurs multiples —");
        }
    }

    private void setMCb(JCheckBox cb, List<TagInfo> tags, Function<TagInfo, Boolean> getter) {
        Set<Boolean> vals = new HashSet<>();
        for (TagInfo t : tags) vals.add(getter.apply(t));
        if (vals.size() == 1) {
            cb.setSelected(vals.iterator().next());
        } else {
            cb.setSelected(false);
            indetermCbs.add(cb);
        }
    }

    /** Lit les valeurs des champs et les écrit dans un TagInfo existant. */
    public void collect(TagInfo t) {
        if (multiMode) {
            // Mode lot : n'écraser QUE les champs non vides
            String v;
            if (!(v = g(tfTitle)).isBlank())        t.title        = v;
            if (!(v = g(tfArtist)).isBlank())       t.artist       = v;
            if (!(v = g(tfAlbumArtist)).isBlank())  t.albumArtist  = v;
            if (!(v = g(tfAlbum)).isBlank())        t.album        = v;
            if (!(v = g(tfYear)).isBlank())         t.year         = v;
            if (!(v = g(tfGenre)).isBlank())        t.genre        = v;
            if (!(v = g(tfTrack)).isBlank())        t.track        = v;
            if (!(v = g(tfTrackTotal)).isBlank())   t.trackTotal   = v;
            if (!(v = g(tfDiscNo)).isBlank())       t.discNo       = v;
            if (!(v = g(tfDiscTotal)).isBlank())    t.discTotal    = v;
            if (!(v = g(tfComposer)).isBlank())     t.composer     = v;
            if (!(v = g(tfConductor)).isBlank())    t.conductor    = v;
            if (!(v = g(tfOrchestra)).isBlank())    t.orchestra    = v;
            if (!(v = g(tfEnsemble)).isBlank())     t.ensemble     = v;
            if (!(v = g(tfChoir)).isBlank())        t.choir        = v;
            if (!(v = g(tfWork)).isBlank())         t.work         = v;
            if (!(v = g(tfPeriod)).isBlank())       t.period       = v;
            if (!(v = g(tfOpus)).isBlank())         t.opus         = v;
            if (!(v = g(tfGrouping)).isBlank())     t.grouping     = v;
            if (!(v = g(tfLyricist)).isBlank())     t.lyricist     = v;
            if (!(v = g(tfProducer)).isBlank())     t.producer     = v;
            if (!(v = g(tfArranger)).isBlank())     t.arranger     = v;
            if (!(v = g(tfBpm)).isBlank())              t.bpm              = v;
            if (!(v = g(tfKey)).isBlank())              t.initialKey       = v;
            if (!(v = g(tfLanguage)).isBlank())         t.language         = v;
            if (!(v = g(tfRating)).isBlank())           t.rating           = v;
            if (!(v = g(tfIsrc)).isBlank())             t.isrc             = v;
            if (!(v = g(tfTags)).isBlank())             t.tags             = v;
            if (!(v = g(tfComposerSort)).isBlank())     t.composerSort     = v;
            if (!(v = g(tfConductorSort)).isBlank())    t.conductorSort    = v;
            if (!(v = g(tfOrchestraSort)).isBlank())    t.orchestraSort    = v;
            if (!(v = g(tfEngineer)).isBlank())         t.engineer         = v;
            if (!(v = g(tfMixer)).isBlank())            t.mixer            = v;
            if (!(v = g(tfDjMixer)).isBlank())          t.djMixer          = v;
            if (!(v = g(tfTitleSort)).isBlank())        t.titleSort        = v;
            if (!(v = g(tfArtistSort)).isBlank())       t.artistSort       = v;
            if (!(v = g(tfAlbumSort)).isBlank())        t.albumSort        = v;
            if (!(v = g(tfAlbumArtistSort)).isBlank())  t.albumArtistSort  = v;
            if (!(v = g(tfAmazonId)).isBlank())         t.amazonId         = v;
            if (!(v = g(tfLyricsUrl)).isBlank())        t.lyricsUrl        = v;
            // Checkboxes : ne toucher que celles à valeur homogène
            if (!indetermCbs.contains(chkClassical))
                t.isClassical    = chkClassical.isSelected()    ? "1" : "0";
            if (!indetermCbs.contains(chkHD))
                t.isHD           = chkHD.isSelected()           ? "1" : "0";
            if (!indetermCbs.contains(chkLive))
                t.isLive         = chkLive.isSelected()         ? "1" : "0";
            if (!indetermCbs.contains(chkCompilation))
                t.isCompilation  = chkCompilation.isSelected()  ? "1" : "0";
            if (!indetermCbs.contains(chkGreatestHits))
                t.isGreatestHits = chkGreatestHits.isSelected() ? "1" : "0";
            if (!indetermCbs.contains(chkSoundtrack))
                t.isSoundtrack   = chkSoundtrack.isSelected()   ? "1" : "0";
            if (!indetermCbs.contains(chkInstrumental))
                t.isInstrumental = chkInstrumental.isSelected() ? "1" : "0";
            return;
        }
        // ── Mode fichier unique (comportement normal) ─────────────────────
        t.title        = g(tfTitle);
        t.artist       = g(tfArtist);
        t.albumArtist  = g(tfAlbumArtist);
        t.album        = g(tfAlbum);
        t.year         = g(tfYear);
        t.genre        = g(tfGenre);
        t.track        = g(tfTrack);
        t.trackTotal   = g(tfTrackTotal);
        t.discNo       = g(tfDiscNo);
        t.discTotal    = g(tfDiscTotal);

        t.composer         = g(tfComposer);
        t.composerSort     = g(tfComposerSort);
        t.conductor        = g(tfConductor);
        t.orchestra        = g(tfOrchestra);
        t.ensemble         = g(tfEnsemble);
        t.choir            = g(tfChoir);
        t.work             = g(tfWork);
        t.movement         = g(tfMovement);
        t.movementNo       = g(tfMovementNo);
        t.movementTotal    = g(tfMovementTotal);
        t.part             = g(tfPart);
        t.period           = g(tfPeriod);
        t.opus             = g(tfOpus);
        t.classicalCatalog = g(tfClassicalCatalog);
        t.classicalNickname = g(tfClassicalNickname);
        t.section          = g(tfSection);
        t.overallWork      = g(tfOverallWork);
        t.grouping         = g(tfGrouping);
        t.isClassical      = chkClassical.isSelected() ? "1" : "0";

        t.lyricist         = g(tfLyricist);
        t.producer         = g(tfProducer);
        t.arranger         = g(tfArranger);
        t.engineer         = g(tfEngineer);
        t.mixer            = g(tfMixer);
        t.djMixer          = g(tfDjMixer);
        t.titleSort        = g(tfTitleSort);
        t.artistSort       = g(tfArtistSort);
        t.albumSort        = g(tfAlbumSort);
        t.albumArtistSort  = g(tfAlbumArtistSort);
        t.conductorSort    = g(tfConductorSort);
        t.orchestraSort    = g(tfOrchestraSort);

        t.bpm           = g(tfBpm);
        t.initialKey    = g(tfKey);
        t.language      = g(tfLanguage);
        t.rating        = g(tfRating);
        t.isrc          = g(tfIsrc);
        t.amazonId      = g(tfAmazonId);
        t.tags          = g(tfTags);
        t.isHD          = chkHD.isSelected()          ? "1" : "0";
        t.isLive        = chkLive.isSelected()         ? "1" : "0";
        t.isCompilation = chkCompilation.isSelected()  ? "1" : "0";
        t.isGreatestHits = chkGreatestHits.isSelected() ? "1" : "0";
        t.isSoundtrack  = chkSoundtrack.isSelected()   ? "1" : "0";
        t.isInstrumental = chkInstrumental.isSelected() ? "1" : "0";

        t.lyrics    = taLyrics.getText();
        t.lyricsUrl = g(tfLyricsUrl);

        t.artistOfficialUrl   = g(tfArtistOfficialUrl);
        t.artistWikipediaUrl  = g(tfArtistWikipediaUrl);
        t.artistDiscogsUrl    = g(tfArtistDiscogsUrl);
        t.releaseOfficialUrl  = g(tfReleaseOfficialUrl);
        t.releaseWikipediaUrl = g(tfReleaseWikipediaUrl);
        t.releaseDiscogsUrl   = g(tfReleaseDiscogsUrl);
        t.discogsId           = g(tfDiscogsId);
        t.roonAlbumTag        = g(tfRoonAlbumTag);
        t.roonTrackTag        = g(tfRoonTrackTag);
        // MB IDs et AcoustID : lecture seule, non modifiés
    }

    public void clear() {
        clearHighlights();
        for (JTextField tf : allTextFields()) tf.setText("");
        taLyrics.setText("");
        for (JCheckBox cb : new JCheckBox[]{
            chkClassical, chkHD, chkLive, chkCompilation,
            chkGreatestHits, chkSoundtrack, chkInstrumental}) {
            cb.setSelected(false);
        }
    }

    // ── Construction des onglets ─────────────────────────────────────────────

    private JScrollPane buildGeneralTab() {
        Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("Titre :",          tfTitle);
        fields.put("Artiste :",        tfArtist);
        fields.put("Artiste album :",  tfAlbumArtist);
        fields.put("Album :",          tfAlbum);
        fields.put("Année :",          tfYear);
        fields.put("Genre :",          tfGenre);
        fields.put("Piste :",          tfTrack);
        fields.put("Total pistes :",   tfTrackTotal);
        fields.put("Disque :",         tfDiscNo);
        fields.put("Total disques :",  tfDiscTotal);
        return scroll(formPanel(fields));
    }

    private JScrollPane buildClassicalTab() {
        Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("Est classique :",     chkClassical);
        fields.put("Compositeur :",       tfComposer);
        fields.put("Tri compositeur :",   tfComposerSort);
        fields.put("Chef d'orchestre :",  tfConductor);
        fields.put("Orchestre :",         tfOrchestra);
        fields.put("Ensemble :",          tfEnsemble);
        fields.put("Chœur :",             tfChoir);
        fields.put("Œuvre (Work) :",      tfWork);
        fields.put("MB Work ID :",        tfWorkMbid);
        fields.put("Mouvement :",         tfMovement);
        fields.put("Nº mouvement :",      tfMovementNo);
        fields.put("Total mouvements :",  tfMovementTotal);
        fields.put("Partie :",            tfPart);
        fields.put("Période :",           tfPeriod);
        fields.put("Opus :",              tfOpus);
        fields.put("Catalogue :",         tfClassicalCatalog);
        fields.put("Surnom :",            tfClassicalNickname);
        fields.put("Section (opéra) :",   tfSection);
        fields.put("Œuvre globale :",     tfOverallWork);
        fields.put("Grouping (iTunes) :", tfGrouping);
        return scroll(formPanel(fields));
    }

    private JScrollPane buildContribTab() {
        Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("Parolier :",           tfLyricist);
        fields.put("Producteur :",         tfProducer);
        fields.put("Arrangeur :",          tfArranger);
        fields.put("Ingénieur son :",      tfEngineer);
        fields.put("Mixage :",             tfMixer);
        fields.put("DJ Mixer :",           tfDjMixer);
        fields.put("─── Tris ───",         sep());
        fields.put("Tri titre :",          tfTitleSort);
        fields.put("Tri artiste :",        tfArtistSort);
        fields.put("Tri album :",          tfAlbumSort);
        fields.put("Tri artiste album :",  tfAlbumArtistSort);
        fields.put("Tri chef :",           tfConductorSort);
        fields.put("Tri orchestre :",      tfOrchestraSort);
        return scroll(formPanel(fields));
    }

    private JScrollPane buildAudioTab() {
        Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("BPM :",               tfBpm);
        fields.put("Tonalité :",           tfKey);
        fields.put("Langue :",             tfLanguage);
        fields.put("Note (Rating) :",      tfRating);
        fields.put("ISRC :",               tfIsrc);
        fields.put("Amazon ID :",          tfAmazonId);
        fields.put("Mots-clés :",          tfTags);
        fields.put("─── Caractéristiques ───", sep());
        fields.put("",                     chkHD);
        fields.put(" ",                    chkLive);
        fields.put("  ",                   chkCompilation);
        fields.put("   ",                  chkGreatestHits);
        fields.put("    ",                 chkSoundtrack);
        fields.put("     ",                chkInstrumental);
        fields.put("─── Mood (Essentia) ───", sep());
        fields.put("Mood général :",       tfMood);
        fields.put("Agressif :",           tfMoodAggressive);
        fields.put("Acoustique :",         tfMoodAcoustic);
        fields.put("Électronique :",       tfMoodElectronic);
        fields.put("Joyeux :",             tfMoodHappy);
        fields.put("Triste :",             tfMoodSad);
        fields.put("Relaxant :",           tfMoodRelaxed);
        fields.put("Dansant :",            tfMoodDance);
        fields.put("Festif :",             tfMoodParty);
        return scroll(formPanel(fields));
    }

    // ── Pochette ─────────────────────────────────────────────────────────────

    public void setOnCoverClick(Runnable r) { onCoverClick = r; }

    public void setCoverIcon(java.awt.image.BufferedImage img) {
        if (img == null) { clearCover(); return; }
        int w = img.getWidth(), h = img.getHeight();
        int max = 280;
        double ratio = Math.min((double) max / w, (double) max / h);
        int sw = (int)(w * ratio), sh = (int)(h * ratio);
        java.awt.Image scaled = img.getScaledInstance(sw, sh, java.awt.Image.SCALE_SMOOTH);
        lblCoverTab.setIcon(new ImageIcon(scaled));
        lblCoverTab.setText("");
        lblCoverInfo.setText(w + " × " + h + " px");
    }

    public void clearCover() {
        lblCoverTab.setIcon(null);
        lblCoverTab.setText("Aucune pochette");
        lblCoverInfo.setText(" ");
    }

    private JPanel buildPochetteTab() {
        lblCoverTab.putClientProperty("FlatLaf.style", "foreground: #546E7A");
        lblCoverInfo.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 10 $defaultFont");
        lblCoverTab.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        lblCoverTab.setToolTipText("Double-clic pour gérer la pochette");
        lblCoverTab.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && onCoverClick != null) onCoverClick.run();
            }
        });

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(new EmptyBorder(20, 20, 10, 20));
        lblCoverTab.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        lblCoverInfo.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        center.add(Box.createVerticalGlue());
        center.add(lblCoverTab);
        center.add(Box.createVerticalStrut(8));
        center.add(lblCoverInfo);
        center.add(Box.createVerticalGlue());

        JButton btnChange = new JButton("Changer la pochette…");
        btnChange.addActionListener(e -> { if (onCoverClick != null) onCoverClick.run(); });
        JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
        btnRow.add(btnChange);

        JPanel p = new JPanel(new java.awt.BorderLayout());
        p.add(center, java.awt.BorderLayout.CENTER);
        p.add(btnRow,  java.awt.BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildLyricsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel urlRow = new JPanel(new BorderLayout(8, 0));
        urlRow.add(new JLabel("URL paroles :"), BorderLayout.WEST);
        urlRow.add(tfLyricsUrl, BorderLayout.CENTER);

        taLyrics.setLineWrap(true);
        taLyrics.setWrapStyleWord(true);
        taLyrics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        p.add(urlRow,                  BorderLayout.NORTH);
        p.add(new JScrollPane(taLyrics), BorderLayout.CENTER);
        // Retourner directement le panneau — la textarea a déjà son propre JScrollPane.
        // L'ancien wrapper JScrollPane(p, NEVER, NEVER) était inutile et pouvait rogner
        // le champ URL si la fenêtre était trop petite.
        return p;
    }

    private JScrollPane buildIdsTab() {
        Map<String, JComponent> fields = new LinkedHashMap<>();
        fields.put("─── URLs artiste ───",    sep());
        fields.put("Site officiel :",          tfArtistOfficialUrl);
        fields.put("Wikipedia artiste :",      tfArtistWikipediaUrl);
        fields.put("Discogs artiste :",        tfArtistDiscogsUrl);
        fields.put("─── URLs release ───",     sep());
        fields.put("Site officiel release :",  tfReleaseOfficialUrl);
        fields.put("Wikipedia release :",      tfReleaseWikipediaUrl);
        fields.put("Discogs release :",        tfReleaseDiscogsUrl);
        fields.put("─── IDs MusicBrainz ───",  sep());
        fields.put("Recording MBID :",         tfRecordingMbid);
        fields.put("Release MBID :",           tfReleaseMbid);
        fields.put("Release Group MBID :",     tfReleaseGroupMbid);
        fields.put("Artist MBID :",            tfArtistMbid);
        fields.put("─── Autres IDs ───",       sep());
        fields.put("AcoustID :",               tfAcoustidId);
        fields.put("Discogs ID :",             tfDiscogsId);
        fields.put("Roon Album Tag :",         tfRoonAlbumTag);
        fields.put("Roon Track Tag :",         tfRoonTrackTag);
        return scroll(formPanel(fields));
    }

    // ── Footer avec bouton Appliquer ─────────────────────────────────────────

    private JPanel buildFooter() {
        JButton btnApply = new JButton("Appliquer les modifications");
        btnApply.addActionListener(e -> { if (onApply != null) onApply.run(); });
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        p.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        p.add(btnApply);
        return p;
    }

    // ── Helpers UI ───────────────────────────────────────────────────────────

    private JTextField tf(int cols) {
        JTextField tf = new JTextField(cols);
        return tf;
    }

    private JLabel sep() {
        JLabel l = new JLabel(" ");
        return l;
    }

    private JScrollPane scroll(JPanel p) {
        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JPanel formPanel(Map<String, JComponent> fields) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(8, 14, 8, 14));
        int row = 0;
        for (Map.Entry<String, JComponent> e : fields.entrySet()) {
            String    label = e.getKey();
            JComponent comp = e.getValue();
            boolean   isSep = comp instanceof JLabel;

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = row;
            lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(isSep ? 6 : 2, 4, isSep ? 2 : 2, 8);

            if (isSep) {
                // Séparateur visuel — s'étend sur 2 colonnes
                lc.gridwidth = 2;
                lc.fill = GridBagConstraints.HORIZONTAL;
                lc.weightx = 1.0;
                JLabel sep = new JLabel(label.trim().isEmpty() ? " " : label);
                sep.putClientProperty("FlatLaf.style", "foreground: #666688; font: bold 11 $defaultFont");
                p.add(sep, lc);
            } else if (comp instanceof JCheckBox) {
                lc.gridwidth = 2;
                p.add(comp, lc);
            } else {
                p.add(new JLabel(label), lc);
                GridBagConstraints fc = new GridBagConstraints();
                fc.gridx = 1; fc.gridy = row;
                fc.fill = GridBagConstraints.HORIZONTAL;
                fc.weightx = 1.0;
                fc.insets = new Insets(2, 0, 2, 4);
                p.add(comp, fc);
            }
            row++;
        }
        // Remplisseur pour coller les champs en haut
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row; filler.weighty = 1.0; filler.fill = GridBagConstraints.VERTICAL;
        p.add(Box.createVerticalGlue(), filler);
        return p;
    }

    private void set(JTextField tf, String val) { tf.setText(val != null ? val : ""); }
    private String g(JTextField tf) { return tf.getText().trim(); }

    // ── Preview couleur avant / après tagger ─────────────────────────────────
    // Vert foncé : champ vide qui a reçu une valeur (nouvelle donnée)
    // Ambre foncé : champ non vide dont la valeur a changé (donnée mise à jour)
    private static final Color HL_NEW     = new Color(28, 100, 52);
    private static final Color HL_CHANGED = new Color(115, 70, 10);

    /**
     * Compare les valeurs actuelles des champs avec {@code before} et colore
     * en vert les champs nouveaux, en ambre les champs remplacés.
     * Appelé par MainFrame après populate() sur une entrée TAGGED.
     */
    public void highlightChanges(TagInfo before) {
        if (before == null) { clearHighlights(); return; }
        // Général
        hl(tfTitle,       g(tfTitle),       before.title);
        hl(tfArtist,      g(tfArtist),      before.artist);
        hl(tfAlbumArtist, g(tfAlbumArtist), before.albumArtist);
        hl(tfAlbum,       g(tfAlbum),       before.album);
        hl(tfYear,        g(tfYear),        before.year);
        hl(tfGenre,       g(tfGenre),       before.genre);
        hl(tfTrack,       g(tfTrack),       before.track);
        hl(tfTrackTotal,  g(tfTrackTotal),  before.trackTotal);
        hl(tfDiscNo,      g(tfDiscNo),      before.discNo);
        hl(tfDiscTotal,   g(tfDiscTotal),   before.discTotal);
        // Classique
        hl(tfComposer,          g(tfComposer),          before.composer);
        hl(tfComposerSort,      g(tfComposerSort),      before.composerSort);
        hl(tfConductor,         g(tfConductor),         before.conductor);
        hl(tfOrchestra,         g(tfOrchestra),         before.orchestra);
        hl(tfEnsemble,          g(tfEnsemble),          before.ensemble);
        hl(tfChoir,             g(tfChoir),             before.choir);
        hl(tfWork,              g(tfWork),              before.work);
        hl(tfMovement,          g(tfMovement),          before.movement);
        hl(tfMovementNo,        g(tfMovementNo),        before.movementNo);
        hl(tfMovementTotal,     g(tfMovementTotal),     before.movementTotal);
        hl(tfPart,              g(tfPart),              before.part);
        hl(tfPeriod,            g(tfPeriod),            before.period);
        hl(tfOpus,              g(tfOpus),              before.opus);
        hl(tfClassicalCatalog,  g(tfClassicalCatalog),  before.classicalCatalog);
        hl(tfClassicalNickname, g(tfClassicalNickname), before.classicalNickname);
        hl(tfGrouping,          g(tfGrouping),          before.grouping);
        hlBool(chkClassical,    chkClassical.isSelected(), "1".equals(before.isClassical));
        // Contributeurs
        hl(tfLyricist,        g(tfLyricist),        before.lyricist);
        hl(tfProducer,        g(tfProducer),        before.producer);
        hl(tfArranger,        g(tfArranger),        before.arranger);
        hl(tfEngineer,        g(tfEngineer),        before.engineer);
        hl(tfMixer,           g(tfMixer),           before.mixer);
        hl(tfDjMixer,         g(tfDjMixer),         before.djMixer);
        hl(tfTitleSort,       g(tfTitleSort),       before.titleSort);
        hl(tfArtistSort,      g(tfArtistSort),      before.artistSort);
        hl(tfAlbumSort,       g(tfAlbumSort),       before.albumSort);
        hl(tfAlbumArtistSort, g(tfAlbumArtistSort), before.albumArtistSort);
        hl(tfConductorSort,   g(tfConductorSort),   before.conductorSort);
        hl(tfOrchestraSort,   g(tfOrchestraSort),   before.orchestraSort);
        // Audio
        hl(tfBpm,       g(tfBpm),      before.bpm);
        hl(tfKey,       g(tfKey),      before.initialKey);
        hl(tfLanguage,  g(tfLanguage), before.language);
        hl(tfRating,    g(tfRating),   before.rating);
        hl(tfIsrc,      g(tfIsrc),     before.isrc);
        hl(tfAmazonId,  g(tfAmazonId), before.amazonId);
        hl(tfTags,      g(tfTags),     before.tags);
        hlBool(chkHD,            chkHD.isSelected(),            "1".equals(before.isHD));
        hlBool(chkLive,          chkLive.isSelected(),          "1".equals(before.isLive));
        hlBool(chkCompilation,   chkCompilation.isSelected(),   "1".equals(before.isCompilation));
        hlBool(chkGreatestHits,  chkGreatestHits.isSelected(),  "1".equals(before.isGreatestHits));
        hlBool(chkSoundtrack,    chkSoundtrack.isSelected(),    "1".equals(before.isSoundtrack));
        hlBool(chkInstrumental,  chkInstrumental.isSelected(),  "1".equals(before.isInstrumental));
        // Mood (lecture seule — remplis par Essentia)
        hl(tfMood,           g(tfMood),           before.mood);
        hl(tfMoodAggressive, g(tfMoodAggressive), before.moodAggressive);
        hl(tfMoodAcoustic,   g(tfMoodAcoustic),   before.moodAcoustic);
        hl(tfMoodElectronic, g(tfMoodElectronic), before.moodElectronic);
        hl(tfMoodHappy,      g(tfMoodHappy),      before.moodHappy);
        hl(tfMoodSad,        g(tfMoodSad),        before.moodSad);
        hl(tfMoodRelaxed,    g(tfMoodRelaxed),    before.moodRelaxed);
        hl(tfMoodDance,      g(tfMoodDance),      before.moodDanceability);
        hl(tfMoodParty,      g(tfMoodParty),      before.moodParty);
        // Paroles
        hlArea(taLyrics, taLyrics.getText(), before.lyrics);
        hl(tfLyricsUrl, g(tfLyricsUrl), before.lyricsUrl);
    }

    /** Remet tous les champs à leur couleur par défaut. */
    public void clearHighlights() {
        Color defTf = UIManager.getColor("TextField.background");
        Color defTa = UIManager.getColor("TextArea.background");
        Color defCb = UIManager.getColor("CheckBox.foreground");
        if (defTf == null) defTf = new Color(60, 63, 65);
        if (defTa == null) defTa = new Color(60, 63, 65);
        for (JTextField tf : allTextFields()) {
            if (tf.isEditable()) tf.setBackground(defTf);
            // les champs read-only (MB IDs, mood) restent avec leur propre bg
        }
        if (taLyrics != null) taLyrics.setBackground(defTa);
        if (defCb != null) {
            for (JCheckBox cb : new JCheckBox[]{
                    chkClassical, chkHD, chkLive, chkCompilation,
                    chkGreatestHits, chkSoundtrack, chkInstrumental}) {
                cb.setForeground(defCb);
            }
        }
    }

    private void hl(JTextField tf, String nv, String ov) {
        ov = (ov != null) ? ov : "";
        if (nv.equals(ov)) {
            if (tf.isEditable()) tf.setBackground(UIManager.getColor("TextField.background"));
        } else if (ov.isBlank()) {
            tf.setBackground(HL_NEW);
        } else {
            tf.setBackground(HL_CHANGED);
        }
    }

    private void hlBool(JCheckBox cb, boolean nv, boolean ov) {
        if (nv != ov) cb.setForeground(HL_NEW);
        else {
            Color def = UIManager.getColor("CheckBox.foreground");
            if (def != null) cb.setForeground(def);
        }
    }

    private void hlArea(JTextArea ta, String nv, String ov) {
        ov = (ov != null) ? ov : "";
        if (nv.equals(ov)) {
            ta.setBackground(UIManager.getColor("TextArea.background"));
        } else if (ov.isBlank()) {
            ta.setBackground(HL_NEW);
        } else {
            ta.setBackground(HL_CHANGED);
        }
    }

    private JTextField[] allTextFields() {
        return new JTextField[]{
            tfTitle, tfArtist, tfAlbumArtist, tfAlbum, tfYear, tfGenre,
            tfTrack, tfTrackTotal, tfDiscNo, tfDiscTotal,
            tfComposer, tfComposerSort, tfConductor, tfOrchestra, tfEnsemble, tfChoir,
            tfWork, tfWorkMbid, tfMovement, tfMovementNo, tfMovementTotal,
            tfPart, tfPeriod, tfOpus, tfClassicalCatalog, tfClassicalNickname,
            tfSection, tfOverallWork, tfGrouping,
            tfLyricist, tfProducer, tfArranger, tfEngineer, tfMixer, tfDjMixer,
            tfTitleSort, tfArtistSort, tfAlbumSort, tfAlbumArtistSort,
            tfConductorSort, tfOrchestraSort,
            tfBpm, tfKey, tfLanguage, tfRating, tfIsrc, tfAmazonId, tfTags,
            tfMood, tfMoodAggressive, tfMoodAcoustic, tfMoodElectronic,
            tfMoodHappy, tfMoodSad, tfMoodRelaxed, tfMoodDance, tfMoodParty,
            tfLyricsUrl,
            tfArtistOfficialUrl, tfArtistWikipediaUrl, tfArtistDiscogsUrl,
            tfReleaseOfficialUrl, tfReleaseWikipediaUrl, tfReleaseDiscogsUrl,
            tfRecordingMbid, tfReleaseMbid, tfReleaseGroupMbid, tfArtistMbid,
            tfAcoustidId, tfDiscogsId, tfRoonAlbumTag, tfRoonTrackTag
        };
    }
}
