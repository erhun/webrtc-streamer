package com.genymobile.scrcpy.audio;

public enum AudioScheme {
    PCM("pcm"),
    OPUS("opus");

    private final String name;

    AudioScheme(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static AudioScheme findByName(String name) {
        for (AudioScheme scheme : values()) {
            if (scheme.name.equals(name)) {
                return scheme;
            }
        }
        return null;
    }
}
