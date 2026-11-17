package io.github.captheorem.spectral;

public enum Op {
    REGISTER("register"),
    RENEW("renew"),
    LOOKUP("lookup"),
    STATUS("status");

    private final String wireName;

    Op(String wireName) {
        this.wireName = wireName;
    }

    @Override
    public String toString() {
        return wireName;
    }
}
