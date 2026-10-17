package com.dmk.astrocompanion;

/**
 * Phone <-> watch messages (Wear OS Data Layer; both apps have the same package name and key).
 * Phone -> watch: messages SET ("key=value" lines), REFRESH, UPDATE, STATUS_REQ, and a channel
 * APKS carrying a zip with AstroWatchFace.apk and / or AstroCompanion.apk.
 * Watch -> phone: the data item STATUS (builds, settings, last refresh, update progress).
 */
final class Link {
    static final String SET = "/astro/set";
    static final String REFRESH = "/astro/refresh";
    static final String UPDATE = "/astro/update";
    static final String STATUS_REQ = "/astro/status-request";
    static final String STATUS = "/astro/status";
    static final String APKS = "/astro/apks";

    // status keys
    static final String K_APP = "app", K_FACE = "face", K_STATUS = "status", K_UPD = "upd", K_TIDE = "tideIdx",
            K_TIDE_NAME = "tideName", K_PANEL = "panel", K_AUTO = "autoUpd", K_RAIN = "rainAuto", K_COMPASS = "compass",
            K_ALLOWED = "allowed", K_TIME = "t", K_MODEL = "model", K_REFRESHED = "wTime", K_BATT = "battery", K_CHG = "charging";

    private Link() {}
}
