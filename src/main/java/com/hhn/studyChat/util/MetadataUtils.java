package com.hhn.studyChat.util;

import com.digitalpebble.stormcrawler.Metadata;

/**
 * Hilfsmethoden für Metadata-Operationen im StudyChat Crawler
 */
public class MetadataUtils {

    /**
     * Erstellt eine Kopie einer Metadata-Instanz
     *
     * @param source Die zu kopierende Metadata-Instanz
     * @return Eine neue Metadata-Instanz mit allen kopierten Werten
     */
    public static Metadata copyMetadata(Metadata source) {
        if (source == null) {
            return new Metadata();
        }

        Metadata copy = new Metadata();

        // Alle Schlüssel-Wert-Paare kopieren
        for (String key : source.keySet()) {
            String[] values = source.getValues(key);
            if (values != null) {
                for (String value : values) {
                    copy.addValue(key, value);
                }
            }
        }

        return copy;
    }

    /**
     * Erstellt eine Kopie einer Metadata-Instanz und fügt einen neuen Wert hinzu
     *
     * @param source Die zu kopierende Metadata-Instanz
     * @param key Der Schlüssel für den neuen Wert
     * @param value Der neue Wert
     * @return Eine neue Metadata-Instanz mit allen kopierten Werten plus dem neuen Wert
     */
    public static Metadata copyMetadataWithValue(Metadata source, String key, String value) {
        Metadata copy = copyMetadata(source);
        copy.setValue(key, value);
        return copy;
    }

    /**
     * Erstellt eine Kopie einer Metadata-Instanz und fügt mehrere neue Werte hinzu
     *
     * @param source Die zu kopierende Metadata-Instanz
     * @param additionalValues Die hinzuzufügenden Schlüssel-Wert-Paare
     * @return Eine neue Metadata-Instanz mit allen kopierten Werten plus den neuen Werten
     */
    public static Metadata copyMetadataWithValues(Metadata source, String... additionalValues) {
        if (additionalValues.length % 2 != 0) {
            throw new IllegalArgumentException("additionalValues must contain an even number of elements (key-value pairs)");
        }

        Metadata copy = copyMetadata(source);

        for (int i = 0; i < additionalValues.length; i += 2) {
            String key = additionalValues[i];
            String value = additionalValues[i + 1];
            copy.setValue(key, value);
        }

        return copy;
    }

    /**
     * Gibt die Tiefe aus den Metadaten zurück oder 0 als Standard
     *
     * @param metadata Die Metadata-Instanz
     * @return Die Tiefe als Integer oder 0 wenn nicht vorhanden
     */
    public static int getDepth(Metadata metadata) {
        if (metadata == null) {
            return 0;
        }

        String depthStr = metadata.getFirstValue(StudyChatConstants.DEPTH_KEY);
        if (depthStr != null) {
            try {
                return Integer.parseInt(depthStr);
            } catch (NumberFormatException e) {
                // Ignorieren und 0 zurückgeben
            }
        }

        return 0;
    }

    /**
     * Setzt die Tiefe in den Metadaten
     *
     * @param metadata Die Metadata-Instanz
     * @param depth Die zu setzende Tiefe
     */
    public static void setDepth(Metadata metadata, int depth) {
        if (metadata != null) {
            metadata.setValue(StudyChatConstants.DEPTH_KEY, String.valueOf(depth));
        }
    }

    /**
     * Prüft, ob eine URL bereits eine bestimmte Tiefe erreicht hat
     *
     * @param metadata Die Metadata-Instanz der URL
     * @param maxDepth Die maximale erlaubte Tiefe
     * @return true wenn die URL die maximale Tiefe überschreitet
     */
    public static boolean exceedsMaxDepth(Metadata metadata, int maxDepth) {
        int currentDepth = getDepth(metadata);
        return (currentDepth + 1) > maxDepth;
    }

    private MetadataUtils() {
        // Utility-Klasse, keine Instanziierung
    }
}