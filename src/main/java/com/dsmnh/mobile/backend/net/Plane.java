package com.dsmnh.mobile.backend.net;

/** 协议平面：App（44444）与节点（44443），契约 §1。 */
public enum Plane {
    APP("app"),
    NODE("node");

    public final String wire;

    Plane(String wire) {
        this.wire = wire;
    }

    public static Plane fromWire(String wire) {
        for (Plane plane : values()) {
            if (plane.wire.equals(wire)) {
                return plane;
            }
        }
        return null;
    }
}
