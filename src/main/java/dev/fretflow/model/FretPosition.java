package dev.fretflow.model;

public record FretPosition(int stringNumber, int fret, ScoreNote note) {
    public String compact() {
        return stringNumber + ":" + fret;
    }
}

