package dev.fretflow.model;

import java.util.Locale;

/** A performance mark attached to one score note. */
public record Technique(Kind kind, Phase phase, int number, String detail) {
    public Technique {
        if (kind == null) throw new IllegalArgumentException("Technique kind is required");
        phase = phase == null ? Phase.SINGLE : phase;
        number = Math.max(1, number);
        detail = detail == null ? "" : detail;
    }

    public Technique(Kind kind) {
        this(kind, Phase.SINGLE, 1, "");
    }

    public boolean startsConnection() {
        return kind.isConnection() && phase == Phase.START;
    }

    public boolean stopsConnection() {
        return kind.isConnection() && phase == Phase.STOP;
    }

    public static Phase phaseFromMusicXml(String value) {
        if (value == null) return Phase.SINGLE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "start" -> Phase.START;
            case "stop" -> Phase.STOP;
            case "continue" -> Phase.CONTINUE;
            default -> Phase.SINGLE;
        };
    }

    public enum Kind {
        SLAP,
        POP,
        DEAD_NOTE,
        GHOST_NOTE,
        HAMMER_ON,
        PULL_OFF,
        SLIDE,
        HARMONIC;

        public boolean isConnection() {
            return this == HAMMER_ON || this == PULL_OFF || this == SLIDE;
        }
    }

    public enum Phase {
        SINGLE,
        START,
        STOP,
        CONTINUE
    }
}
