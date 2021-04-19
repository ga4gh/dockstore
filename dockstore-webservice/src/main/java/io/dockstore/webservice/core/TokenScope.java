package io.dockstore.webservice.core;

public enum TokenScope {
    AUTHENTICATE("/authenticate"), ACTIVITIES_UPDATE("/activities/update");
    private final String text;


    TokenScope(final String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }

    public static TokenScope getEnumByString(String scope) {
        for (TokenScope e: TokenScope.values()) {
            if (e.toString().equals(scope)) {
                return e;
            }
        }
        return null;
    }
}
