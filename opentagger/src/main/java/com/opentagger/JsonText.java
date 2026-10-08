package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Remplace {@code JsonNode.asText(String)}, déprécié dans Jackson. Même comportement : la valeur par défaut est
 * renvoyée pour un nœud absent ou explicitement {@code null} ; sinon le texte du nœud (comme {@code asText()}).
 */
public final class JsonText {

    private JsonText() {}

    public static String of(JsonNode node, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) return defaultValue;
        String s = node.asText();
        return s == null ? defaultValue : s;
    }
}
